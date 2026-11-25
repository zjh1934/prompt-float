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
 * ★ 层级（v0.1.4 按真机反馈重设计）：**只有两级**
 *    球  →  一排提示词标签（固定一整排的主框）
 *   长按 chip 弹出的全文气泡是「临时浮层」，不改主框、不撑大主框、不构成第三级。
 *
 * ★ 收起（v0.1.4 纠正）：**点框外任意处**收起 —— 通用范式。
 *   FLAG_NOT_TOUCH_MODAL 让窗口外的触摸穿透给下层（输入框/键盘照常响应），
 *   FLAG_WATCH_OUTSIDE_TOUCH 让本窗口同时收到一次 MotionEvent.ACTION_OUTSIDE 通知，
 *   它**不消费**这次触摸。上一版说「会吃掉点输入框那一下」是错的，已改。
 *   代价（如实说）：点输入框本身也算「框外」，面板会收起；但插入是在点 chip 时
 *   同步完成的，正常流程不受影响。
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

    /** 长按全文预览：独立临时气泡窗口，不属于主框层级 */
    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

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

    // ---------------------------------------------------------------- 球 → 框（第二级）

    private fun expandPanel() {
        if (panelView != null || animating) return
        val items = repo.list()
        if (items.isEmpty()) {
            DevLogger.i("PANEL", "提示词为空，不展开")
            toast(R.string.panel_empty)
            return
        }

        val v = LayoutInflater.from(this).inflate(R.layout.overlay_panel, null) as FrameLayout
        val chipRow = v.findViewById<HorizontalScrollView>(R.id.chipRow)
        val container = v.findViewById<LinearLayout>(R.id.chipContainer)
        val contentRoot = v.findViewById<FrameLayout>(R.id.contentRoot)

        // 圆角逐帧插值：球形态正圆(28dp) → 框形态 16dp 圆角
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
                dismissBubble()
                insertPrompt(item.content)
                collapsePanel("chip-tap")
            }
            // 长按 = 在标签行上方弹一个临时气泡看全文。不撑大主框、不进第三级。
            chip.setOnLongClickListener {
                if (animating) return@setOnLongClickListener true
                DevLogger.i("PANEL", "长按第 $index 条 → 气泡预览 | 长度=${item.content.length}")
                showBubble(item, chip)
                true
            }
        }

        // ★ 点框外任意处 → 收起（通用范式）。ACTION_OUTSIDE 不消费触摸，输入框照常响应。
        v.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_OUTSIDE) {
                DevLogger.i("PANEL", "outside-tap → 收起气泡并缩回球")
                dismissBubble()
                collapsePanel("outside-tap")
                true
            } else false
        }
        // 点框内空白：只收气泡，不收主框（因为这不是「外部」）
        val blankTap = View.OnClickListener { dismissBubble() }
        v.setOnClickListener(blankTap)
        contentRoot.setOnClickListener(blankTap)
        chipRow.setOnClickListener(blankTap)

        val screenW = resources.displayMetrics.widthPixels
        ballX = ballParams?.x ?: dp(16)
        ballY = ballParams?.y ?: dp(260)

        // ★ 固定一整排：与词条数量无关。只有一条 chip 时也是完整一排，
        //   右侧留白由「点框外收起」承担，不再需要「点框内空白」这种脆弱交互。
        targetW = (screenW - dp(32)).coerceAtLeast(dp(56))
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

        // 球和框不再并存：球 View 立刻隐藏，同一个容器从 56dp 圆形长大成框
        ballView?.visibility = View.INVISIBLE

        DevLogger.i(
            "PANEL",
            "morph expand | 条目=${items.size} 固定一排 ${targetW}x${targetH} @($targetX,$targetY)"
        )
        animateMorph(ballState(), panelState(), 220L)
    }

    // ---------------------------------------------------------------- 长按气泡（临时浮层）

    /**
     * 长按 chip → 在标签行**上方**弹一个独立气泡，显示这条提示词全文。
     * 主框尺寸、内容、层级都不变，所以是「两级 + 一个看一眼就走的浮层」，不是三级。
     * 松手后气泡保留（长文看得完）；点框外 / 点 chip / 收起面板时一并消失。
     */
    private fun showBubble(item: PromptItem, chip: View) {
        dismissBubble()
        val p = panelParams ?: return
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels

        val b = LayoutInflater.from(this).inflate(R.layout.overlay_bubble, null) as LinearLayout
        val card = b.findViewById<LinearLayout>(R.id.bubbleCard)
        val scroll = b.findViewById<ScrollView>(R.id.bubbleScroll)
        val text = b.findViewById<TextView>(R.id.bubbleText)
        val arrow = b.findViewById<View>(R.id.bubbleArrow)

        text.text = item.content
        val cardBg = GradientDrawable().apply {
            setColor(Color.argb(252, 255, 255, 255))
            setStroke(dp(1), Color.argb(70, 68, 85, 102))
            cornerRadius = dp(12).toFloat()
        }
        card.background = cardBg
        val arrowBg = GradientDrawable().apply {
            setColor(Color.argb(252, 255, 255, 255))
            cornerRadius = dp(2).toFloat()
        }
        arrow.background = arrowBg

        // 给 ScrollView 有界高度：长提示词在气泡内滚动，不会把气泡撑到整屏
        val maxBubbleH = minOf(screenH / 3, dp(240))
        scroll.layoutParams = scroll.layoutParams.apply { height = maxBubbleH - dp(24) }
        val bubbleH = maxBubbleH + dp(12)

        val loc = IntArray(2)
        chip.getLocationOnScreen(loc)
        val chipTop = loc[1]
        val chipBottom = chipTop + chip.height
        val chipCenterX = loc[0] + chip.width / 2

        val yAbove = chipTop - bubbleH - dp(8)
        val above = yAbove >= dp(8)
        val bubbleY = if (above) {
            yAbove
        } else {
            // 上方放不下就落到主框下方（不压住主框）
            maxOf(chipBottom + dp(8), targetY + targetH + dp(8))
        }
        if (!above) {
            // 气泡在下：小三角要放到卡片上面
            b.removeView(arrow)
            b.addView(arrow, 0)
            arrow.rotation = 45f
        }

        // 三角横向对准被长按的那条 chip
        arrow.translationX = (chipCenterX - (targetX + targetW / 2))
            .toFloat()
            .coerceIn((-targetW / 2 + dp(20)).toFloat(), (targetW / 2 - dp(20)).toFloat())

        val bp = overlayParams().apply {
            width = targetW
            height = WindowManager.LayoutParams.WRAP_CONTENT
            x = targetX
            y = bubbleY
            // 气泡不监听外部事件：让 ACTION_OUTSIDE 归主框统一处理，
            // 否则上层气泡可能把「点框外」这次通知吃掉。
            flags = flags and WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH.inv()
        }
        runCatching { wm.addView(b, bp) }
            .onFailure { DevLogger.e("PANEL", "addView 气泡失败", it); return }
        bubbleView = b
        bubbleParams = bp
        // 气泡自身可点：点它 = 收气泡 + 缩回球。
        // 这样即便双层窗口下 ACTION_OUTSIDE 派发有歧义，交互也确定；
        // NOT_TOUCH_MODAL 仍保证「气泡外」的触摸穿透给输入框，同时主框收 ACTION_OUTSIDE。
        val bubbleTap = View.OnClickListener {
            DevLogger.i("PANEL", "bubble-tap → 收起")
            dismissBubble()
            collapsePanel("bubble-tap")
        }
        b.setOnClickListener(bubbleTap)
        card.setOnClickListener(bubbleTap)
        scroll.setOnClickListener(bubbleTap)
        text.setOnClickListener(bubbleTap)

        b.alpha = 0f
        b.animate().alpha(1f).setDuration(120L).start()
        DevLogger.i(
            "PANEL",
            "bubble show | 长度=${item.content.length} above=$above y=$bubbleY 高=$bubbleH"
        )
    }

    private fun dismissBubble() {
        val b = bubbleView ?: return
        runCatching { wm.removeViewImmediate(b) }
        bubbleView = null
        bubbleParams = null
        DevLogger.i("PANEL", "bubble dismiss")
    }

    // ---------------------------------------------------------------- 收起

    /** reason 写进日志，一眼看出是谁把面板收掉的 */
    private fun collapsePanel(reason: String = "unspecified") {
        val v = panelView
        val p = panelParams
        if (v == null || p == null) {
            DevLogger.d("PANEL", "collapse($reason) 跳过：面板本来就没开")
            return
        }
        dismissBubble()
        DevLogger.i("PANEL", "morph collapse(reason=$reason)")
        // 展开动画还没结束就收到收起指令（快速点框外）：直接落地，不叠加两段动画
        if (animating) {
            collapsePanelNow(reason + "-skip-anim")
            return
        }
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
        dismissBubble()
        panelView?.let { runCatching { wm.removeViewImmediate(it) } }
        panelView = null
        panelParams = null
        morphBg = null
        ballView?.visibility = View.VISIBLE
        DevLogger.i("PANEL", "collapse now(reason=$reason)，已恢复球形")
    }

    // ---------------------------------------------------------------- morph 动画

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
     * 220ms（收起 180ms）—— 高频瞬时操作，1 秒只会觉得卡。
     */
    private fun animateMorph(from: MorphState, to: MorphState, duration: Long, onEnd: (() -> Unit)? = null) {
        val v = panelView ?: return
        val p = panelParams ?: return
        val bg = morphBg ?: return
        val ballLabel = v.findViewById<View>(R.id.morphBallLabel)
        val content = v.findViewById<View>(R.id.contentRoot)

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

    /** 球在下半屏时把框翻到球上方，按实际目标高度算 */
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
        // NOT_TOUCH_MODAL：窗口外的触摸穿透给下层（输入框/键盘照常响应）
        // WATCH_OUTSIDE_TOUCH：同时收到一次 ACTION_OUTSIDE 通知，用来「点框外收起」。
        //   它不消费这次触摸 —— 这正是上一版判断错的地方。
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
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
