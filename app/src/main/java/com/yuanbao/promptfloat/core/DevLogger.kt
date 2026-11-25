package com.yuanbao.promptfloat.core

import android.content.Context
import android.os.Build
import android.provider.Settings
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 开发者日志（排 bug 用）。
 *
 * 设计红线：**绝不记录用户内容**。
 * 不记提示词正文、不记输入框里的字、不记剪贴板文本、不记任何窗口文字。
 * 只记「发生了什么 / 成功还是失败 / 类型 / 长度 / 时间」，这样把日志整份贴出来
 * 就能还原代码执行路径，又不会泄漏隐私。
 *
 * 存储：内存环形缓冲（最多 MAX 条）+ 应用私有文件逐行 JSON，进程被杀也能读回来。
 * 导出：一键生成 Markdown，含环境快照、错误汇总、完整时间线。
 */
object DevLogger {

    private const val MAX = 800
    private const val FILE = "dev_log.ndjson"
    private val buf = ArrayDeque<Entry>()
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.CHINA)
    private var appContext: Context? = null
    private var sessionStart = 0L

    data class Entry(
        val ts: Long,
        val level: String,
        val tag: String,
        val msg: String
    )

    /** Application.onCreate 里调一次 */
    fun init(context: Context) {
        appContext = context.applicationContext
        sessionStart = System.currentTimeMillis()
        val f = file() ?: return
        if (f.exists()) {
            // 只读最近 MAX 行，避免历史无限膨胀
            val lines = runCatching { f.readLines() }.getOrDefault(emptyList())
            lines.takeLast(MAX).forEach { line ->
                runCatching {
                    val o = JSONObject(line)
                    buf.addLast(
                        Entry(
                            o.optLong("ts"),
                            o.optString("level"),
                            o.optString("tag"),
                            o.optString("msg")
                        )
                    )
                }
            }
        }
        i("APP", "进程启动 | 会话开始")
    }

    private fun file(): File? = appContext?.let { File(it.filesDir, FILE) }

    @Synchronized
    private fun write(level: String, tag: String, msg: String) {
        val e = Entry(System.currentTimeMillis(), level, tag, msg)
        buf.addLast(e)
        while (buf.size > MAX) buf.removeFirst()
        // 落盘用 append，逐条即时写，崩溃前也能留下最后一条
        runCatching {
            file()?.appendText(
                JSONObject().apply {
                    put("ts", e.ts)
                    put("level", e.level)
                    put("tag", e.tag)
                    put("msg", e.msg)
                }.toString() + "\n"
            )
        }
    }

    fun d(tag: String, msg: String) = write("D", tag, msg)
    fun i(tag: String, msg: String) = write("I", tag, msg)
    fun w(tag: String, msg: String) = write("W", tag, msg)
    fun e(tag: String, msg: String, t: Throwable? = null) {
        val detail = t?.let {
            " | ${it.javaClass.simpleName}: ${it.message} | @${it.stackTrace.firstOrNull()}"
        }.orEmpty()
        write("E", tag, msg + detail)
    }

    /** 清空历史，方便「从干净的起点复现一次 bug」 */
    @Synchronized
    fun clear() {
        buf.clear()
        runCatching { file()?.delete() }
        sessionStart = System.currentTimeMillis()
        i("APP", "日志已清空，开始新会话")
    }

    private fun time(ts: Long) = fmt.format(Date(ts))

    /** 生成可直接贴给维护者的 Markdown */
    @Synchronized
    fun dumpMarkdown(context: Context): String {
        val ctx = context.applicationContext
        val pm = ctx.packageManager
        val pkg = ctx.packageName
        val ver = runCatching {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, 0).versionName
        }.getOrDefault("?")
        val code = runCatching {
            @Suppress("DEPRECATION")
            val pi = pm.getPackageInfo(pkg, 0)
            if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
        }.getOrDefault(-1L)
        val a11y = runCatching {
            Settings.Secure.getString(
                ctx.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
        }.getOrDefault("?")
        val serviceOn = a11y.contains(pkg)
        val promptCount = runCatching {
            com.yuanbao.promptfloat.data.PromptRepository(ctx).list().size
        }.getOrDefault(-1)
        val prefs = ctx.getSharedPreferences("prompt_float", Context.MODE_PRIVATE)
        val ballEnabled = prefs.getBoolean(
            com.yuanbao.promptfloat.data.PromptRepository.KEY_BALL_ENABLED, true
        )
        val enabledAbis = Build.SUPPORTED_ABIS.joinToString(",")

        val sb = StringBuilder()
        sb.appendLine("# 词贴 · 开发者日志")
        sb.appendLine()
        sb.appendLine("> 导出时间：${time(System.currentTimeMillis())}　·　会话起始：${time(sessionStart)}")
        sb.appendLine()
        sb.appendLine("## 一、环境快照")
        sb.appendLine()
        sb.appendLine("| 项 | 值 |")
        sb.appendLine("|---|---|")
        sb.appendLine("| 版本 | `$ver` (versionCode `$code`) |")
        sb.appendLine("| 包名 | `$pkg` |")
        sb.appendLine("| 设备 | ${Build.MANUFACTURER} ${Build.MODEL} |")
        sb.appendLine("| Android | ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT} |")
        sb.appendLine("| ABI | $enabledAbis |")
        sb.appendLine("| 无障碍已启用 | $serviceOn |")
        sb.appendLine("| 悬浮球开关 | $ballEnabled |")
        sb.appendLine("| 悬浮服务实例 | ${PasteAccessibilityService.isRunning()} |")
        sb.appendLine("| 提示词条数 | $promptCount（**不含正文**） |")
        sb.appendLine("| 日志条数 | ${buf.size} / $MAX |")
        sb.appendLine()
        sb.appendLine("## 二、错误汇总（最近 30 条 E/W）")
        sb.appendLine()
        val problems = buf.filter { it.level == "E" || it.level == "W" }.takeLast(30)
        if (problems.isEmpty()) {
            sb.appendLine("_无错误或警告_")
        } else {
            sb.appendLine("| 时间 | 级别 | 标签 | 事件 |")
            sb.appendLine("|---|---|---|---|")
            problems.forEach {
                sb.appendLine("| ${time(it.ts)} | ${it.level} | `${it.tag}` | ${esc(it.msg)} |")
            }
        }
        sb.appendLine()
        sb.appendLine("## 三、完整时间线")
        sb.appendLine()
        sb.appendLine("| 时间 | 级别 | 标签 | 事件 |")
        sb.appendLine("|---|---|---|---|")
        buf.forEach {
            sb.appendLine("| ${time(it.ts)} | ${it.level} | `${it.tag}` | ${esc(it.msg)} |")
        }
        sb.appendLine()
        sb.appendLine("## 四、怎么读这份日志")
        sb.appendLine()
        sb.appendLine("- `PANEL expand` 后面**紧跟** `PANEL collapse` 且原因是 `a11y-event` → 就是「点一下就收起」的旧 bug。")
        sb.appendLine("- `INSERT focus=null` → 没找到聚焦输入框（输入框失焦 / 自绘控件不暴露焦点）。")
        sb.appendLine("- `INSERT paste=false` + `setText=false` → 宿主输入框拒绝两个动作，已走剪贴板兜底。")
        sb.appendLine("- `SERVICE onServiceConnected` 缺失 → 无障碍服务根本没连上。")
        sb.appendLine("- 标签含义：`APP` 应用、`SERVICE` 服务生命周期、`A11Y` 无障碍事件、`BALL` 悬浮球、`PANEL` 展开面板、`INSERT` 插入链、`UI` 主页面、`DATA` 数据层。")
        sb.appendLine()
        sb.appendLine("## 五、隐私说明")
        sb.appendLine()
        sb.appendLine("本日志**不含**提示词正文、输入框文本、剪贴板内容、窗口文字或任何用户数据；")
        sb.appendLine("只记录事件类型、成功/失败布尔值、条目数量与长度。可放心整份贴给维护者或开公开 issue。")
        sb.appendLine()
        sb.appendLine("<!-- 复现步骤请在此补充：哪个 App、点了什么、期望什么、实际什么 -->")
        sb.appendLine()
        return sb.toString()
    }

    private fun esc(s: String) = s
        .replace("|", "\\|")
        .replace("\n", " ")
        .replace("\r", " ")
}
