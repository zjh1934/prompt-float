package com.yuanbao.promptfloat.core

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.yuanbao.promptfloat.PromptApp
import com.yuanbao.promptfloat.R
import com.yuanbao.promptfloat.data.PromptRepository
import kotlin.math.abs

/**
 * 整个 App 的核心：一个无障碍服务同时干两件事
 *
 *  1) 用 TYPE_ACCESSIBILITY_OVERLAY 画悬浮球 / 展开面板。
 *     这一层是无障碍服务自带的，不需要 SYSTEM_ALERT_WINDOW 权限。
 *  2) 点提示词时，用 findFocus(FOCUS_INPUT) 找到「当前输入法正在输入的那个框」，
 *     把文字插进去。普通 App 拿不到别的 App 的 InputConnection，
 *     所以这一步只能借 AccessibilityService —— 这也是为什么必须让用户手动开一次无障碍。
 *
 * 关键：所有浮窗都带 FLAG_NOT_FOCUSABLE。
 * 一旦浮窗抢走焦点，输入框会失焦、键盘收起，findFocus(FOCUS_INPUT) 立刻返回 null，
 * 悬浮球就「把自己打没了」。这个标志就是「不跟输入法抢」的技术保证。
 *
 * 借鉴来源见 README；核心 API 组合参考 Pster Voice 与 RMBLR 的公开实现：
 *   TYPE_ACCESSIBILITY_OVERLAY + PASTE 优先 + SET_TEXT 兜底 + node.recycle()
 */
class PasteAccessibilityService : AccessibilityService() {

    private lateinit var wm: WindowManager
    private lateinit var repo: PromptRepository
    private lateinit var prefs: SharedPreferences

    private var ballView: View? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private var panelView: View? = null
    private var panelParams: WindowManager.LayoutParams? = null

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var dragged = false
    private var lastEventLogAt = 0L

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        repo = (application as PromptApp).prompts
        prefs = getSharedPreferences("prompt_float", Context.MODE_PRIVATE)
        DevLogger.i("SERVICE", "onCreate")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        DevLogger.i("SERVICE", "onServiceConnected ✅ 无障碍已连上")
        if (prefs.getBoolean(PromptRepository.KEY_BALL_ENABLED, true)) showBall()
        else DevLogger.i("BALL", "开关为关，未创建悬浮球")
    }

    /**
     * ★ 这里是「点一下展开、立马自动收起」的根因所在。
     *
     * 旧逻辑：收到 TYPE_WINDOW_STATE_CHANGED 就 collapsePanel()。
     * 但我们自己 wm.addView() 添加 TYPE_ACCESSIBILITY_OVERLAY 面板时，系统**立刻**
     * 会派发一次 windowStateChanged 事件；面板刚加上就被自己收掉了。
     * 键盘弹出、输入法窗口变化也会发同一事件，同样会误收。
     *
     * 修法：**彻底不用无障碍事件去收起面板**。
     * 收起只发生在用户明确操作（点收起 / 点提示词 / 点球切换）或服务销毁时。
     * 这里只做诊断埋点，方便从导出日志里看清「当时到底来了什么事件」。
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val type = event.eventType
        // 高频事件做 500ms 节流，避免日志被刷爆
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastEventLogAt > 500L) {
            lastEventLogAt = now
            DevLogger.d(
                "A11Y",
                "event=${eventTypeName(type)} pkg=${event.packageName?.toString().orEmpty()} " +
                    "panelOpen=${panelView != null}"
            )
        }
        // 注意：**不在这里 collapsePanel()**。切窗口时面板保持展开，
        // 用户点「收起 ✕」或再点一次球即可关闭，行为可预期。
    }

    private fun eventTypeName(t: Int): String = when (t) {
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "WINDOW_STATE"
        AccessibilityEvent.TYPE_VIEW_FOCUSED -> "VIEW_FOCUSED"
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "WINDOW_CONTENT"
        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "VIEW_TEXT"
        AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> "VIEW_SELECTION"
        else -> "TYPE_$t"
    }

    override fun onInterrupt() {
        DevLogger.w("SERVICE", "onInterrupt（系统临时中断无障碍）")
    }

    override fun onDestroy() {
        DevLogger.i("SERVICE", "onDestroy")
        instance = null
        collapsePanel("service-destroy")
        removeBall()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 悬浮球

    private fun showBall() {
        if (ballView != null) return
        val v = LayoutInflater.from(this).inflate(R.layout.overlay_ball, null)
        val p = overlayParams().apply {
            width = dp(56)
            height = dp(56)
            x = dp(16)
            y = dp(260)
        }
        v.setOnTouchListener(ballTouch)
        runCatching { wm.addView(v, p) }
            .onFailure { DevLogger.e("BALL", "addView 失败", it) }
        ballView = v
        ballParams = p
        DevLogger.i("BALL", "showBall 成功 | x=${p.x} y=${p.y}")
    }

    private fun removeBall() {
        ballView?.let { runCatching { wm.removeViewImmediate(it) } }
        ballView = null
        ballParams = null
        DevLogger.i("BALL", "removeBall")
    }

    private val ballTouch = View.OnTouchListener { v, ev ->
        val p = ballParams ?: return@OnTouchListener false
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = p.x
                initialY = p.y
                initialTouchX = ev.rawX
                initialTouchY = ev.rawY
                dragged = false
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (ev.rawX - initialTouchX).toInt()
                val dy = (ev.rawY - initialTouchY).toInt()
                if (abs(dx) > dp(6) || abs(dy) > dp(6)) dragged = true
                if (dragged) {
                    p.x = initialX + dx
                    p.y = initialY + dy
                    runCatching { wm.updateViewLayout(ballView, p) }
                    syncPanelPosition()
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                if (dragged) {
                    snapToEdge()
                    DevLogger.d("BALL", "拖拽结束，贴边")
                } else {
                    DevLogger.d("BALL", "点击悬浮球 → togglePanel")
                    togglePanel()
                }
                v.performClick()
                true
            }
            else -> false
        }
    }

    /** 松手贴边，借 EasyFloat / AdbClipboard 那套交互 */
    private fun snapToEdge() {
        val p = ballParams ?: return
        val screenW = resources.displayMetrics.widthPixels
        val bw = ballView?.width?.takeIf { it > 0 } ?: dp(56)
        p.x = if (p.x + bw / 2 < screenW / 2) dp(8) else screenW - bw - dp(8)
        // 球不能停在最底部：那里是输入框和键盘，会挡住正在打字的地方
        val screenH = resources.displayMetrics.heightPixels
        p.y = p.y.coerceIn(dp(8), screenH - dp(220))
        runCatching { wm.updateViewLayout(ballView, p) }
        syncPanelPosition()
    }

    // ---------------------------------------------------------------- 展开面板

    private fun togglePanel() {
        if (panelView != null) collapsePanel("ball-tap") else expandPanel()
    }

    private fun expandPanel() {
        if (panelView != null) return
        val v = LayoutInflater.from(this).inflate(R.layout.overlay_panel, null)
        val container = v.findViewById<LinearLayout>(R.id.chipContainer)

        // 每次展开都重新读仓库：主页面里增删改完，悬浮球里立刻生效
        val items = repo.list()
        if (items.isEmpty()) {
            container.addView(TextView(this).apply {
                text = getString(R.string.panel_empty)
                setTextColor(resources.getColor(R.color.text_sub, theme))
                textSize = 13f
            })
        } else {
            items.forEach { item ->
                val chip = TextView(this).apply {
                    text = item.title
                    background = resources.getDrawable(R.drawable.bg_chip, theme)
                    setTextColor(resources.getColor(R.color.brand, theme))
                    textSize = 14f
                    isClickable = true
                    isFocusable = true
                }
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, dp(8), 0) }
                container.addView(chip, lp)
                chip.setOnClickListener {
                    DevLogger.d("PANEL", "点选第 ${container.indexOfChild(chip) + 1} 条 → 开始插入")
                    insertPrompt(item.content)
                    collapsePanel("prompt-inserted")
                }
            }
        }
        DevLogger.i("PANEL", "expandPanel(reason=ball-tap) | 条目数=${items.size}")

        v.findViewById<TextView>(R.id.panelClose).setOnClickListener {
            collapsePanel("close-button")
        }

        val p = overlayParams().apply {
            width = resources.displayMetrics.widthPixels - dp(32)
            height = WindowManager.LayoutParams.WRAP_CONTENT
            x = dp(16)
            y = computePanelY()
        }
        runCatching { wm.addView(v, p) }
            .onFailure { DevLogger.e("PANEL", "addView 面板失败", it) }
        panelView = v
        panelParams = p
        setBallActive(true)
        DevLogger.i("PANEL", "面板已展开 | y=${p.y} | 宽度=${p.width}")
    }

    /** reason 写进日志，一眼就能看出「是谁把面板收掉的」 */
    private fun collapsePanel(reason: String = "unspecified") {
        if (panelView == null) {
            DevLogger.d("PANEL", "collapse($reason) 跳过：面板本来就没开")
            return
        }
        panelView?.let { runCatching { wm.removeViewImmediate(it) } }
        panelView = null
        panelParams = null
        setBallActive(false)
        DevLogger.i("PANEL", "collapse(reason=$reason)")
    }

    /**
     * 面板不能死钉在球下方：球被拖到屏幕下半部时，往下展开会被键盘/屏幕边缘吃掉。
     * 用预估高度做一次翻转判断，保证面板始终完整可见。
     */
    private fun computePanelY(): Int {
        val ballY = ballParams?.y ?: dp(260)
        val ballH = ballView?.height?.takeIf { it > 0 } ?: dp(56)
        val screenH = resources.displayMetrics.heightPixels
        val estPanelH = dp(150)
        val below = ballY + ballH + dp(6)
        val flip = below + estPanelH > screenH - dp(24)
        val result = if (!flip) below else maxOf(dp(8), ballY - estPanelH - dp(6))
        DevLogger.d("PANEL", "computePanelY flip=$flip ballY=$ballY screenH=$screenH → y=$result")
        return result
    }

    private fun syncPanelPosition() {
        val p = panelParams ?: return
        val v = panelView ?: return
        p.y = computePanelY()
        runCatching { wm.updateViewLayout(v, p) }
    }

    /** 展开时球变深蓝 + 白环，让「球 ↔ 面板」有明确的从属关系 */
    private fun setBallActive(active: Boolean) {
        val root = ballView?.findViewById<View>(R.id.ballRoot) ?: return
        root.background = resources.getDrawable(
            if (active) R.drawable.bg_ball_active else R.drawable.bg_ball,
            theme
        )
    }

    private fun overlayParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        // NOT_FOCUSABLE 是命门：不抢输入框焦点、不收起键盘
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
    }

    // ---------------------------------------------------------------- 插入文本

    /**
     * 三级兜底，顺序不能反：
     *  1. ACTION_PASTE —— 尊重光标位置和宿主 App 自己的输入处理，不会覆盖半句草稿；
     *  2. ACTION_SET_TEXT —— 部分自绘输入框不接受 PASTE，此时按选区把新文本拼进去再整体写回；
     *  3. 剪贴板 —— 密码框 / Flutter / 部分 WebView 两个动作都拒绝，
     *     此时把提示词留在剪贴板并提示用户长按粘贴。
     */
    private fun insertPrompt(text: String) {
        DevLogger.i("INSERT", "开始 | 文本长度=${text.length}")
        val node = runCatching {
            findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        }.getOrNull()

        if (node == null) {
            DevLogger.w("INSERT", "focus=null 没找到聚焦输入框 → 提示用户")
            toast(R.string.toast_no_field)
            return
        }
        DevLogger.d(
            "INSERT",
            "focus 命中 | class=${node.className} editable=${node.isEditable} " +
                "sel=[${node.textSelectionStart},${node.textSelectionEnd}] " +
                "existingLen=${node.text?.length ?: 0}"
        )

        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val previous = runCatching { cm.primaryClip }.getOrNull()
        cm.setPrimaryClip(ClipData.newPlainText("词贴", text))

        val pasted = try {
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        } catch (e: Exception) {
            DevLogger.e("INSERT", "ACTION_PASTE 抛异常", e)
            false
        }
        DevLogger.i("INSERT", "paste=$pasted")

        if (pasted) {
            // 插入成功就还原剪贴板，不污染用户原来的复制内容
            previous?.let { runCatching { cm.setPrimaryClip(it) } }
            DevLogger.i("INSERT", "结果=成功(PASTE)，剪贴板已还原")
            toast(R.string.toast_pasted)
        } else {
            val setTextOk = trySetText(node, text)
            DevLogger.i("INSERT", "setText=$setTextOk")
            if (setTextOk) {
                previous?.let { runCatching { cm.setPrimaryClip(it) } }
                DevLogger.i("INSERT", "结果=成功(SET_TEXT 兜底)，剪贴板已还原")
                toast(R.string.toast_pasted)
            } else {
                // 故意不还原剪贴板：让提示词留在里面供用户手动长按粘贴
                DevLogger.w("INSERT", "结果=两个动作都被拒绝 → 剪贴板兜底")
                toast(R.string.toast_clipboard)
            }
        }

        // AccessibilityNodeInfo 必须回收，焦点事件很频繁，不回收会泄漏
        runCatching { node.recycle() }
    }

    private fun trySetText(node: AccessibilityNodeInfo, text: String): Boolean {
        val before = node.text?.toString().orEmpty()
        val start = node.textSelectionStart
        val end = node.textSelectionEnd
        val inRange = start in 0..before.length && end >= start && end <= before.length
        val merged = if (inRange) {
            before.substring(0, start) + text + before.substring(end)
        } else {
            before + text
        }
        DevLogger.d(
            "INSERT",
            "SET_TEXT 兜底 | 选区合法=$inRange 原长=${before.length} 写回长度=${merged.length}"
        )
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                merged
            )
        }
        return try {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (e: Exception) {
            DevLogger.e("INSERT", "ACTION_SET_TEXT 抛异常", e)
            false
        }
    }

    // ---------------------------------------------------------------- 主页面联动

    /** MainActivity 改完提示词 / 切完显隐后调用，同进程直连，不绕广播 */
    fun refreshFromSettings() {
        DevLogger.i("SERVICE", "refreshFromSettings（主页面数据/开关有变更）")
        collapsePanel("settings-refresh")
        val enabled = prefs.getBoolean(PromptRepository.KEY_BALL_ENABLED, true)
        if (enabled) showBall() else removeBall()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(resId: Int) =
        Toast.makeText(this, getString(resId), Toast.LENGTH_SHORT).show()

    companion object {
        @Volatile
        var instance: PasteAccessibilityService? = null
            private set

        fun isRunning(): Boolean = instance != null
    }
}
