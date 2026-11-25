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

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        repo = (application as PromptApp).prompts
        prefs = getSharedPreferences("prompt_float", Context.MODE_PRIVATE)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        if (prefs.getBoolean(PromptRepository.KEY_BALL_ENABLED, true)) showBall()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 切换窗口时收起面板，避免旧面板挡在新 App 上面
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) collapsePanel()
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        collapsePanel()
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
        ballView = v
        ballParams = p
    }

    private fun removeBall() {
        ballView?.let { runCatching { wm.removeViewImmediate(it) } }
        ballView = null
        ballParams = null
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
                if (dragged) snapToEdge() else togglePanel()
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
        if (panelView != null) collapsePanel() else expandPanel()
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
                    insertPrompt(item.content)
                    collapsePanel()
                }
            }
        }

        v.findViewById<TextView>(R.id.panelClose).setOnClickListener { collapsePanel() }

        val p = overlayParams().apply {
            width = resources.displayMetrics.widthPixels - dp(32)
            height = WindowManager.LayoutParams.WRAP_CONTENT
            x = dp(16)
            y = computePanelY()
        }
        runCatching { wm.addView(v, p) }
        panelView = v
        panelParams = p
        setBallActive(true)
    }

    private fun collapsePanel() {
        panelView?.let { runCatching { wm.removeViewImmediate(it) } }
        panelView = null
        panelParams = null
        setBallActive(false)
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
        return if (below + estPanelH <= screenH - dp(24)) {
            below
        } else {
            maxOf(dp(8), ballY - estPanelH - dp(6))
        }
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
        val node = runCatching {
            findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        }.getOrNull()

        if (node == null) {
            toast(R.string.toast_no_field)
            return
        }

        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val previous = runCatching { cm.primaryClip }.getOrNull()
        cm.setPrimaryClip(ClipData.newPlainText("词贴", text))

        val pasted = try {
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        } catch (e: Exception) {
            false
        }

        if (pasted) {
            // 插入成功就还原剪贴板，不污染用户原来的复制内容
            previous?.let { runCatching { cm.setPrimaryClip(it) } }
            toast(R.string.toast_pasted)
        } else {
            val setTextOk = trySetText(node, text)
            if (setTextOk) {
                previous?.let { runCatching { cm.setPrimaryClip(it) } }
                toast(R.string.toast_pasted)
            } else {
                // 故意不还原剪贴板：让提示词留在里面供用户手动长按粘贴
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
        val merged = if (start in 0..before.length && end >= start && end <= before.length) {
            before.substring(0, start) + text + before.substring(end)
        } else {
            before + text
        }
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                merged
            )
        }
        return try {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (e: Exception) {
            false
        }
    }

    // ---------------------------------------------------------------- 主页面联动

    /** MainActivity 改完提示词 / 切完显隐后调用，同进程直连，不绕广播 */
    fun refreshFromSettings() {
        collapsePanel()
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
