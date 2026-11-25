package com.yuanbao.promptfloat.core

import android.accessibilityservice.AccessibilityService
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.yuanbao.promptfloat.PromptApp
import com.yuanbao.promptfloat.R
import com.yuanbao.promptfloat.data.PromptItem
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
 * 关键：所有浮窗都带 FLAG_NOT_FOCUSABLE + FLAG_NOT_TOUCH_MODAL。
 * 一旦浮窗抢走焦点，输入框会失焦、键盘收起，findFocus(FOCUS_INPUT) 立刻返回 null；
 * NOT_TOUCH_MODAL 保证「框外的触摸照常穿透给输入框和键盘」—— 这是「不跟输入法抢」的技术保证，
 * 也正因如此：点框外**不会**收起面板（收起靠点框内空白 / 点条目），这是刻意取舍。
 *
 * 借鉴来源见 CREDITS.md；核心 API 组合参考 Pster Voice 与 RMBLR 的公开实现：
 *   TYPE_ACCESSIBILITY_OVERLAY + PASTE 优先 + SET_TEXT 兜底 + node.recycle()
 */
class PasteAccessibilityService : AccessibilityService() {

    private lateinit var wm: WindowManager
    private lateinit var repo: PromptRepository
    private lateinit var prefs: SharedPreferences

    private var ballView: View? = null
    private var ballParams: WindowManager.LayoutParams? = null

    /** 面板 = 球长大后的同一个容器；展开期间球 View 只是 INVISIBLE，不 remove */
    private var panelView: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var morphBg: GradientDrawable? = null
    private var morphAnimator: ValueAnimator? = null
    private var morphEndAction: (() -> Unit)? = null
    private var animating = false
    private var previewMode = false
    private var previewItem: PromptItem? = null

    private var ballX = 0
    private var ballY = 0
    private var targetW = 0
    private var targetH = 0
    private var targetX = 0
    private var targetY = 0

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var dragged = false
    private var lastEventLogAt = 0L

    /** 动画的一帧：宽高、位置、圆角、球文字 alpha、内容 alpha */
    private data class MorphState(
        val w: Int, val h: Int, val x: Int, val y: Int,
        val corner: Float, val ball: Float, val content: Float
    )

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
     * ★ v0.1.1 修掉「点一下展开、立马自动收起」：
     * 旧逻辑收到 TYPE_WINDOW_STATE_CHANGED 就 collapsePanel()，可我们自己 addView()
     * 添加 overlay 时系统立刻派发该事件，面板刚加上就被自己收掉了；键盘弹出同样误收。
     * 现在**彻底不用无障碍事件收面板**，这里只做诊断埋点。
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastEventLogAt > 500L) {
            lastEventLogAt = now
            DevLogger.d(
                "A11Y",
                "event=${eventTypeName(event.eventType)} " +
                    "pkg=${event.packageName?.toString().orEmpty()} panelOpen=${panelView != null}"
            )
        }
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
        collapsePanelNow("service-destroy")
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
                    DevLogger.d("BALL", "点击悬浮球 → 展开")
                    expandPanel()
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
        // 球不能停在最底部：那里是输入框和键盘
        p.y = p.y.coerceIn(dp(8), resources.displayMetrics.heightPixels - dp(220))
        runCatching { wm.updateViewLayout(ballView, p) }
        syncPanelPosition()
    }

    // ---------------------------------------------------------------- 球 ↔ 框

    private fun expandPanel() {
        if (panelView != null || animating) return
        val items = repo.list()
        if (items.isEmpty()) {
            // 空列表不展开一个空框，给一次明确反馈就够了
            DevLogger.i("PANEL", "提示词为空，不展开")
            toast(R.string.panel_empty)
            return
        }

        val v = LayoutInflater.from(this).inflate(R.layout.overlay_panel, null) as FrameLayout
        val ballLabel = v.findViewById<View>(R.id.morphBallLabel)
        val content = v.findViewById<View>(R.id.contentRoot)
        val chipRow = v.findViewById<HorizontalScrollView>(R.id.chipRow)
        val container = v.findViewById<LinearLayout>(R.id.chipContainer)
        val previewBox = v.findViewById<ScrollView>(R.id.previewBox)
        val previewText = v.findViewById<TextView>(R.id.previewText)

        // 圆角逐帧插值，所以背景由代码造：球形态是正圆(28dp)，框形态是 16dp 圆角
        val bg = GradientDrawable().apply {
            setColor(Color.argb(248, 255, 255, 255))
            setStroke(dp(1), Color.argb(60, 68, 85, 102))
            cornerRadius = dp(28).toFloat()
        }
        v.background = bg
        morphBg = bg

        items.forEach { item ->
            val chip = TextView(this).apply {
                text = item.title
                background = resources.getDrawable(R.drawable.bg_chip, theme)
                setTextColor(resources.getColor(R.color.brand, theme))
                textSize = 14f
                isClickable = true
                isFocusable = false
            }
            container.addView(
                chip,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, dp(8), 0) }
            )
            val index = container.indexOfChild(chip) + 1
            chip.setOnClickListener {
                if (animating) return@setOnClickListener
                DevLogger.d("PANEL", "点选第 $index 条 → 插入并收起")
                insertPrompt(item.content)
                collapsePanel("chip-tap")
            }
            // 长按 = 就地预览全文（不是弹窗：弹窗要抢焦点/额外权限，跟核心冲突）
            chip.setOnLongClickListener {
                if (animating) return@setOnLongClickListener true
                DevLogger.i("PANEL", "长按第 $index 条 → 预览 | 长度=${item.content.length}")
                openPreview(item, chipRow, previewBox, previewText)
                true
            }
        }

        // 框内空白 = 缩回球。子 chip / previewText 各自消费点击，不冒泡到这里。
        // 没有 ✕ 按钮、没有标题、没有说明文字。
        v.setOnClickListener {
            if (animating) return@setOnClickListener
            collapsePanel("tap-blank")
        }
        previewText.setOnClickListener {
            if (animating) return@setOnClickListener
            DevLogger.i("PANEL", "预览态点全文 → 插入并收起")
            previewItem?.let { insertPrompt(it.content) }
            collapsePanel("preview-tap")
        }

        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        ballX = ballParams?.x ?: dp(16)
        ballY = ballParams?.y ?: dp(260)
        previewMode = false
        previewItem = null

        // 宽度按内容自适应：一条 chip 时是刚好的小框，多条才变宽。零空白、零解释文字。
        val paint = TextView(this).paint
        paint.textSize = 14f * resources.displayMetrics.scaledDensity
        var chipsW = 0
        items.forEach { chipsW += (paint.measureText(it.title) + dp(28) + dp(8)).toInt() }
        targetW = (chipsW + dp(20))
            .coerceAtLeast(dp(56))
            .coerceAtMost((screenW - dp(32)).coerceAtLeast(dp(56)))
        targetH = dp(58)
        targetX = ballX.coerceAtMost(screenW - targetW - dp(8)).coerceAtLeast(dp(8))
        targetY = computePanelY(targetH)

        val p = overlayParams().apply {
            width = dp(56)
            height = dp(56)
            x = ballX
            y = ballY
        }
        runCatching { wm.addView(v, p) }
            .onFailure { DevLogger.e("PANEL", "addView 面板失败", it) }
        panelView = v
        panelParams = p

        // ★ 球和框不再并存：球 View 立刻隐藏，同一个容器从 56dp 圆形长大成框
        ballView?.visibility = View.INVISIBLE

        DevLogger.i(
            "PANEL",
            "morph expand | 条目=${items.size} 目标 ${targetW}x${targetH} @($targetX,$targetY)"
        )
        animateMorph(ballState(), panelState(), 220L)
    }

    /** 长按 chip：同一个框原地变成预览态，全文可滚动；点全文插入、点空白收起 */
    private fun openPreview(
        item: PromptItem,
        chipRow: HorizontalScrollView,
        previewBox: ScrollView,
        previewText: TextView
    ) {
        val v = panelView ?: return
        val p = panelParams ?: return
        previewItem = item
        previewMode = true
        previewText.text = item.content
        chipRow.visibility = View.GONE
        previewBox.visibility = View.VISIBLE

        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        targetW = screenW - dp(32)
        targetH = minOf(screenH / 2, dp(320))
        // 给 ScrollView 一个有界高度，长提示词才能在框内滚动而不是把框撑到整屏
        previewBox.layoutParams.height = targetH - dp(20)
        previewBox.requestLayout()
        targetX = ballX.coerceAtMost(screenW - targetW - dp(8)).coerceAtLeast(dp(8))
        targetY = computePanelY(targetH)

        DevLogger.i("PANEL", "preview open | 长度=${item.content.length} → ${targetW}x${targetH}")
        animateMorph(currentState(v, p), panelState(), 200L)
    }

    /** reason 写进日志，一眼看出是谁把面板收掉的 */
    private fun collapsePanel(reason: String = "unspecified") {
        val v = panelView
        val p = panelParams
        if (v == null || p == null) {
            DevLogger.d("PANEL", "collapse($reason) 跳过：面板本来就没开")
            return
        }
        DevLogger.i("PANEL", "morph collapse(reason=$reason)")
        animateMorph(currentState(v, p), ballState(), 180L) {
            collapsePanelNow(reason + "-done")
        }
    }

    /** 同步清理：服务销毁 / 设置刷新时用，不等动画 */
    private fun collapsePanelNow(reason: String) {
        val old = morphAnimator
        morphAnimator = null
        morphEndAction = null
        old?.cancel()
        animating = false
        panelView?.let { runCatching { wm.removeViewImmediate(it) } }
        panelView = null
        panelParams = null
        morphBg = null
        previewMode = false
        previewItem = null
        ballView?.visibility = View.VISIBLE
        DevLogger.i("PANEL", "collapse now(reason=$reason)，已恢复球形")
    }

    private fun ballState() = MorphState(
        dp(56), dp(56), ballX, ballY, dp(28).toFloat(), 1f, 0f
    )

    private fun panelState() = MorphState(
        targetW, targetH, targetX, targetY, dp(16).toFloat(), 0f, 1f
    )

    private fun currentState(v: View, p: WindowManager.LayoutParams): MorphState {
        val ballLabel = v.findViewById<View>(R.id.morphBallLabel)
        val content = v.findViewById<View>(R.id.contentRoot)
        return MorphState(
            p.width, p.height, p.x, p.y,
            morphBg?.cornerRadius ?: dp(16).toFloat(),
            ballLabel?.alpha ?: 0f,
            content?.alpha ?: 1f
        )
    }

    /**
     * 单容器真 morph：逐帧改 WindowManager.LayoutParams 的宽/高/位置，
     * 同时插值 GradientDrawable 圆角与两层内容的 alpha。球长大成框、框缩回球。
     * 220ms（收起 180ms）—— 不用 1 秒，瞬时操作拖久了只会觉得卡。
     */
    private fun animateMorph(from: MorphState, to: MorphState, duration: Long, onEnd: (() -> Unit)? = null) {
        val v = panelView ?: return
        val p = panelParams ?: return
        val bg = morphBg ?: return
        val ballLabel = v.findViewById<View>(R.id.morphBallLabel)
        val content = v.findViewById<View>(R.id.contentRoot)
        morphAnimator?.cancel()
        animating = true

        // 先撤掉旧动画与其回调：ValueAnimator.cancel() 也会回调 onAnimationEnd，
        // 若不清空，收起动画的 onEnd 可能在服务销毁时被重复执行（递归 removeView）。
        val old = morphAnimator
        morphAnimator = null
        morphEndAction = null
        old?.cancel()

        val va = ValueAnimator.ofFloat(0f, 1f)
        va.duration = duration
        va.interpolator = DecelerateInterpolator(1.6f)
        va.addUpdateListener { a ->
            val t = a.animatedValue as Float
            p.width = lerp(from.w, to.w, t)
            p.height = lerp(from.h, to.h, t)
            p.x = lerp(from.x, to.x, t)
            p.y = lerp(from.y, to.y, t)
            bg.cornerRadius = from.corner + (to.corner - from.corner) * t
            ballLabel.alpha = from.ball + (to.ball - from.ball) * t
            content.alpha = from.content + (to.content - from.content) * t
            runCatching { wm.updateViewLayout(v, p) }
        }
        va.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (morphAnimator !== va) return   // 已被取消/替换的旧动画，忽略
                animating = false
                val action = morphEndAction
                morphEndAction = null
                action?.invoke()
            }
        })
        morphEndAction = onEnd
        morphAnimator = va
        va.start()
    }

    private fun lerp(a: Int, b: Int, t: Float): Int = (a + (b - a) * t).toInt()

    /** 球在下半屏时把框翻到球上方，按实际目标高度算，不再用写死的 150dp */
    private fun computePanelY(panelH: Int): Int {
        val screenH = resources.displayMetrics.heightPixels
        val below = ballY + dp(56) + dp(6)
        val flip = below + panelH > screenH - dp(24)
        val result = if (!flip) below else maxOf(dp(8), ballY - panelH - dp(6))
        DevLogger.d("PANEL", "computePanelY flip=$flip ballY=$ballY panelH=$panelH → y=$result")
        return result
    }

    /** 拖拽球时同步；展开态球是 INVISIBLE 拖不到，这里只做保险 */
    private fun syncPanelPosition() {
        val p = panelParams ?: return
        val v = panelView ?: return
        if (animating) return
        targetY = computePanelY(targetH)
        p.y = targetY
        runCatching { wm.updateViewLayout(v, p) }
    }

    private fun overlayParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        // NOT_FOCUSABLE：不抢输入框焦点、不收起键盘
        // NOT_TOUCH_MODAL：框外触摸穿透给输入框/键盘，因此「点框外」不会收起面板
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
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
                DevLogger.w("INSERT", "结果=两个动作都被拒绝 → 剪贴板兜底")
                toast(R.string.toast_clipboard)
            }
        }

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
        collapsePanelNow("settings-refresh")
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
