package com.yuanbao.promptfloat.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 一条预设提示词。
 * title = 悬浮球展开后按钮上显示的短名；content = 真正插入输入框的全文。
 */
data class PromptItem(
    val id: String = UUID.randomUUID().toString(),
    var title: String = "",
    var content: String = "",
    var order: Int = 0
)

/**
 * 本地持久化：SharedPreferences 存一份 JSON 字符串。
 * 刻意不用 Room / DataStore —— 零额外依赖，CI 更不容易翻车，
 * 而且提示词条目通常只有几条到几十条，JSON 完全够用。
 */
class PromptRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("prompt_float", Context.MODE_PRIVATE)

    fun list(): MutableList<PromptItem> {
        val raw = prefs.getString(KEY_ITEMS, null)
            ?: return defaultItems().also { save(it) }
        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<PromptItem>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out += PromptItem(
                    id = o.optString("id", UUID.randomUUID().toString()),
                    title = o.optString("title"),
                    content = o.optString("content"),
                    order = o.optInt("order", i)
                )
            }
            out.sortedBy { it.order }.toMutableList()
        } catch (e: Exception) {
            // 备份文件写坏了不能让 App 打不开：回落到默认条目
            defaultItems().also { save(it) }
        }
    }

    fun save(items: List<PromptItem>) {
        val arr = JSONArray()
        items.forEachIndexed { index, p ->
            p.order = index
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("title", p.title)
                put("content", p.content)
                put("order", p.order)
            })
        }
        prefs.edit().putString(KEY_ITEMS, arr.toString()).apply()
    }

    fun upsert(items: MutableList<PromptItem>, item: PromptItem) {
        val idx = items.indexOfFirst { it.id == item.id }
        if (idx >= 0) items[idx] = item else items.add(item)
        save(items)
    }

    fun remove(items: MutableList<PromptItem>, id: String) {
        items.removeAll { it.id == id }
        save(items)
    }

    /** delta = -1 上移，+1 下移 */
    fun move(items: MutableList<PromptItem>, id: String, delta: Int) {
        val from = items.indexOfFirst { it.id == id }
        val to = from + delta
        if (from < 0 || to < 0 || to >= items.size) return
        val p = items.removeAt(from)
        items.add(to, p)
        save(items)
    }

    fun exportJson(): String =
        runCatching { JSONArray(prefs.getString(KEY_ITEMS, "[]")).toString(2) }
            .getOrDefault("[]")

    /** 导入前做结构校验，避免把随便一段文本写进仓库 */
    fun importJson(text: String): Boolean = try {
        val arr = JSONArray(text)
        for (i in 0 until arr.length()) {
            arr.getJSONObject(i).getString("content")
        }
        prefs.edit().putString(KEY_ITEMS, arr.toString()).apply()
        true
    } catch (e: Exception) {
        false
    }

    private fun defaultItems() = mutableListOf(
        PromptItem(
            title = "先别降智",
            content = "请先完整阅读上下文，不要仅凭最近一句话作答；不确定处先向我确认，再给结论。"
        ),
        PromptItem(
            title = "先核实",
            content = "动手前把需要核实的点列清楚告诉我，不要默认我的描述都对；说得不对的直接指出来。"
        ),
        PromptItem(
            title = "给结论",
            content = "先给一句结论和关键数字，再给必要的限定条件，不要铺陈过程。"
        )
    )

    companion object {
        private const val KEY_ITEMS = "items"
        const val KEY_BALL_ENABLED = "ball_enabled"
    }
}
