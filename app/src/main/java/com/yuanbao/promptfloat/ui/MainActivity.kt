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
import com.yuanbao.promptfloat.core.PasteAccessibilityService
import com.yuanbao.promptfloat.data.PromptItem
import com.yuanbao.promptfloat.data.PromptRepository
import java.io.File

/**
 * 主页面：只负责「权限状态 + 提示词增删改排序 + 备份导入导出」。
 * 悬浮球本身不在这里画，也不在这里放任何输入框 —— 避免和输入法抢焦点。
 */
class MainActivity : Activity() {

    private lateinit var repo: PromptRepository
    private lateinit var prefs: android.content.SharedPreferences

    private lateinit var statusText: TextView
    private lateinit var statusSub: TextView
    private lateinit var listCount: TextView
    private lateinit var emptyView: LinearLayout
    private lateinit var btnA11y: Button
    private lateinit var btnBall: Button
    private lateinit var promptList: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        repo = (application as PromptApp).prompts
        prefs = getSharedPreferences("prompt_float", Context.MODE_PRIVATE)

        statusText = findViewById(R.id.statusText)
        statusSub = findViewById(R.id.statusSub)
        listCount = findViewById(R.id.listCount)
        emptyView = findViewById(R.id.emptyView)
        btnA11y = findViewById(R.id.btnA11y)
        btnBall = findViewById(R.id.btnBall)
        promptList = findViewById(R.id.promptList)

        btnA11y.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }

        btnBall.setOnClickListener {
            val next = !prefs.getBoolean(PromptRepository.KEY_BALL_ENABLED, true)
            prefs.edit().putBoolean(PromptRepository.KEY_BALL_ENABLED, next).apply()
            PasteAccessibilityService.instance?.refreshFromSettings()
            refreshStatus()
        }

        findViewById<Button>(R.id.btnAdd).setOnClickListener { showEditDialog(null) }
        findViewById<Button>(R.id.btnExport).setOnClickListener { exportBackup() }
        findViewById<Button>(R.id.btnImport).setOnClickListener { showImportDialog() }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        renderList()
    }

    // ---------------------------------------------------------------- 状态

    private fun refreshStatus() {
        val on = isAccessibilityEnabled()
        statusText.text = getString(
            if (on) R.string.status_a11y_on else R.string.status_a11y_off
        )
        statusSub.text = getString(
            if (on) R.string.status_a11y_sub_on else R.string.status_a11y_sub_off
        )
        // 已启用时按钮降级为「管理」，别再让用户以为还要再开一次
        btnA11y.text = getString(
            if (on) R.string.manage_a11y else R.string.go_a11y
        )
        btnBall.isEnabled = on
        btnBall.text = getString(
            if (prefs.getBoolean(PromptRepository.KEY_BALL_ENABLED, true))
                R.string.toggle_ball_off else R.string.toggle_ball_on
        )
    }

    /** 系统里存的是已启用服务的组件名列表，用包名做宽松匹配即可 */
    private fun isAccessibilityEnabled(): Boolean {
        val enabled = runCatching {
            Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
        }.getOrNull().orEmpty()
        return enabled.contains(packageName)
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

    /** 四个操作收进一个菜单，窄屏上不再挤占标题、也不容易误触删除 */
    private fun showItemMenu(items: MutableList<com.yuanbao.promptfloat.data.PromptItem>,
                             item: com.yuanbao.promptfloat.data.PromptItem) {
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
}
