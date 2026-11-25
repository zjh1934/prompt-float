package com.yuanbao.promptfloat.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.yuanbao.promptfloat.PromptApp
import com.yuanbao.promptfloat.R
import com.yuanbao.promptfloat.core.DevLogger
import com.yuanbao.promptfloat.core.PasteAccessibilityService
import com.yuanbao.promptfloat.data.PromptItem
import com.yuanbao.promptfloat.data.PromptRepository
import java.io.File

/**
 * 极简主页面：状态、提示词列表、三个动作。不做品牌展示、不铺说明文字。
 * 悬浮球由无障碍服务绘制，这里不碰浮窗，也不和输入法抢焦点。
 */
class MainActivity : Activity() {

    private lateinit var repo: PromptRepository
    private lateinit var prefs: android.content.SharedPreferences

    private lateinit var statusDot: TextView
    private lateinit var statusText: TextView
    private lateinit var listCount: TextView
    private lateinit var emptyView: LinearLayout
    private lateinit var btnA11y: Button
    private lateinit var btnBall: Button
    private lateinit var promptList: LinearLayout

    /** 清空日志前先存一份，供系统文件选择器另存（选择器是异步的） */
    private var pendingLogSnapshot: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        DevLogger.i("UI", "MainActivity.onCreate")

        repo = (application as PromptApp).prompts
        prefs = getSharedPreferences("prompt_float", Context.MODE_PRIVATE)

        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)
        listCount = findViewById(R.id.listCount)
        emptyView = findViewById(R.id.emptyView)
        btnA11y = findViewById(R.id.btnA11y)
        btnBall = findViewById(R.id.btnBall)
        promptList = findViewById(R.id.promptList)

        btnA11y.setOnClickListener {
            DevLogger.i("UI", "点击「开启/管理无障碍」")
            startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }

        btnBall.setOnClickListener {
            val next = !prefs.getBoolean(PromptRepository.KEY_BALL_ENABLED, true)
            prefs.edit().putBoolean(PromptRepository.KEY_BALL_ENABLED, next).apply()
            DevLogger.i("UI", "悬浮球开关 → $next")
            PasteAccessibilityService.instance?.refreshFromSettings()
            refreshStatus()
        }

        findViewById<Button>(R.id.btnAdd).setOnClickListener {
            DevLogger.i("UI", "点击新增")
            showEditDialog(null)
        }
        findViewById<Button>(R.id.btnLog).setOnClickListener { exportDevLog() }
        findViewById<TextView>(R.id.btnMore).setOnClickListener { showToolsMenu() }
        findViewById<Button>(R.id.btnBackup).setOnClickListener { showToolsMenu() }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        renderList()
        DevLogger.d("UI", "onResume | a11y=${isAccessibilityEnabled()}")
    }

    // ---------------------------------------------------------------- 状态（一行）

    private fun refreshStatus() {
        val on = isAccessibilityEnabled()
        statusText.text = getString(
            if (on) R.string.status_a11y_on else R.string.status_a11y_off
        )
        statusDot.setTextColor(
            resources.getColor(if (on) R.color.ok_green else R.color.danger, theme)
        )
        btnA11y.text = getString(if (on) R.string.manage_a11y else R.string.go_a11y)
        btnBall.isEnabled = on
        btnBall.text = getString(
            if (prefs.getBoolean(PromptRepository.KEY_BALL_ENABLED, true))
                R.string.toggle_ball_off else R.string.toggle_ball_on
        )
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = runCatching {
            Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
        }.getOrNull().orEmpty()
        return enabled.contains(packageName)
    }

    // ---------------------------------------------------------------- 开发者日志

    /**
     * 一键导出 MD：写私有文件 + 复制剪贴板 + 打开分享。
     * 分享这一步是关键 —— 用户要「回头把日志贴给我」，得能直接甩出来。
     */
    private fun exportDevLog() {
        DevLogger.i("UI", "点击「导出日志」")
        val md = runCatching { DevLogger.dumpMarkdown(this) }
            .getOrElse {
                DevLogger.e("UI", "生成日志失败", it)
                "# 词贴 开发者日志\n\n生成失败：${it.javaClass.simpleName}\n"
            }
        runCatching {
            File(filesDir, LOG_MD).writeText(md)
        }.onFailure { DevLogger.e("UI", "写日志文件失败", it) }

        runCatching {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("词贴 开发者日志", md))
        }

        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "词贴 开发者日志")
            putExtra(Intent.EXTRA_TEXT, md)
        }
        runCatching {
            startActivity(Intent.createChooser(send, getString(R.string.share_log)))
        }.onFailure { DevLogger.w("UI", "分享面板打开失败（不影响复制与文件）") }

        toast(R.string.log_exported)
        DevLogger.i("UI", "日志导出完成 | 字节=${md.toByteArray().size}")
    }

    /** 用系统文件选择器把 MD 存到用户想要的位置 */
    private fun saveLogToDocument() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, "词贴-开发者日志.md")
        }
        runCatching {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_SAVE_LOG)
        }.onFailure { DevLogger.e("UI", "打开文件选择器失败", it) }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SAVE_LOG && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            runCatching {
                contentResolver.openOutputStream(uri)?.use {
                    it.write((pendingLogSnapshot ?: DevLogger.dumpMarkdown(this)).toByteArray())
                }
                pendingLogSnapshot = null
                DevLogger.i("UI", "日志已另存为文件")
                toast(R.string.log_exported)
            }.onFailure { DevLogger.e("UI", "另存日志失败", it) }
        }
    }

    // ---------------------------------------------------------------- 次级菜单

    private fun showToolsMenu() {
        val labels = arrayOf(
            getString(R.string.export),
            getString(R.string.import_),
            getString(R.string.clear_log),
            getString(R.string.about)
        )
        AlertDialog.Builder(this)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> { DevLogger.i("UI", "导出备份"); exportBackup() }
                    1 -> { DevLogger.i("UI", "导入备份"); showImportDialog() }
                    2 -> {
                        // 先留一份快照再清空，否则「另存」拿到的是空日志
                        pendingLogSnapshot = DevLogger.dumpMarkdown(this)
                        DevLogger.clear()
                        saveLogToDocument()
                        toast(R.string.log_cleared)
                    }
                    3 -> AlertDialog.Builder(this)
                        .setMessage(R.string.about_text)
                        .setPositiveButton("好", null)
                        .show()
                }
            }
            .show()
    }

    // ---------------------------------------------------------------- 列表

    private fun renderList() {
        val items = repo.list()
        promptList.removeAllViews()
        listCount.text = getString(R.string.list_count, items.size)
        emptyView.visibility = if (items.isEmpty()) android.view.View.VISIBLE
                               else android.view.View.GONE

        items.forEach { item ->
            val row = LayoutInflater.from(this)
                .inflate(R.layout.item_prompt, promptList, false)

            row.findViewById<TextView>(R.id.itemTitle).text = item.title
            row.findViewById<TextView>(R.id.itemPreview).text = item.content
            row.findViewById<TextView>(R.id.itemMore).setOnClickListener {
                showItemMenu(items, item)
            }

            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            promptList.addView(row, lp)
        }
    }

    private fun showItemMenu(items: MutableList<PromptItem>, item: PromptItem) {
        val labels = arrayOf(
            getString(R.string.menu_up),
            getString(R.string.menu_down),
            getString(R.string.menu_edit),
            getString(R.string.menu_delete)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_title)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> { repo.move(items, item.id, -1); renderList(); notifyService() }
                    1 -> { repo.move(items, item.id, 1); renderList(); notifyService() }
                    2 -> showEditDialog(item)
                    3 -> AlertDialog.Builder(this)
                        .setMessage(R.string.del_confirm)
                        .setPositiveButton(R.string.menu_delete) { _, _ ->
                            repo.remove(items, item.id); renderList(); notifyService()
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            }
            .show()
    }

    private fun notifyService() {
        DevLogger.d("DATA", "提示词变更，通知服务刷新")
        PasteAccessibilityService.instance?.refreshFromSettings()
    }

    // ---------------------------------------------------------------- 新增 / 编辑

    private fun showEditDialog(existing: PromptItem?) {
        val pad = dp(20)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val etTitle = EditText(this).apply {
            hint = getString(R.string.dlg_label_title)
            setText(existing?.title.orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val etContent = EditText(this).apply {
            hint = getString(R.string.dlg_label_content)
            setText(existing?.content.orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 5
            gravity = Gravity.TOP
        }
        root.addView(etTitle, matchWidth())
        root.addView(etContent, matchWidth())

        AlertDialog.Builder(this)
            .setTitle(
                if (existing == null) R.string.dlg_title_new else R.string.dlg_title_edit
            )
            .setView(root)
            .setPositiveButton(R.string.save) { _, _ ->
                val title = etTitle.text.toString().trim()
                val content = etContent.text.toString()
                if (title.isEmpty() || content.isEmpty()) return@setPositiveButton
                val items = repo.list()
                repo.upsert(
                    items,
                    existing?.copy(title = title, content = content)
                        ?: PromptItem(title = title, content = content)
                )
                DevLogger.i("DATA", "提示词已保存 | 标题长度=${title.length} 正文长度=${content.length}")
                renderList()
                notifyService()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------------------------------------------------------------- 备份

    private fun exportBackup() {
        val json = repo.exportJson()
        File(filesDir, "prompts-backup.json").writeText(json)
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("提示词备份", json))
        toast(R.string.toast_export)
        DevLogger.i("DATA", "备份导出 | 条目数=${repo.list().size} 字节=${json.toByteArray().size}")
    }

    private fun showImportDialog() {
        val et = EditText(this).apply {
            hint = getString(R.string.dlg_import_title)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 6
            gravity = Gravity.TOP
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), 0)
            addView(et, matchWidth())
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.dlg_import_title)
            .setView(root)
            .setPositiveButton(R.string.import_) { _, _ ->
                val ok = repo.importJson(et.text.toString())
                DevLogger.i("DATA", "备份导入结果=$ok")
                toast(if (ok) R.string.toast_import_ok else R.string.toast_import_fail)
                if (ok) { renderList(); notifyService() }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------------------------------------------------------------- 小工具

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun toast(resId: Int) =
        Toast.makeText(this, getString(resId), Toast.LENGTH_SHORT).show()

    companion object {
        private const val LOG_MD = "promptfloat-dev-log.md"
        private const val REQ_SAVE_LOG = 1001
    }
}
