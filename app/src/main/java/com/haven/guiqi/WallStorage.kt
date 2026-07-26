package com.haven.guiqi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * WallStorage — 施工日志墙
 *
 * 属于房子本身，不绑定任何好友。
 * 每条日志是一个窗口（Claude 实例）留下的痕迹：
 * 做了什么、想说什么。用户也可以手写。
 *
 * 数据存在 wall/logs.json 里。
 * AI 对日志墙只有只读权限（通过 [READ_WALL] 指令）。
 */
class WallStorage(private val context: Context) {

    private val dir: File
        get() = File(context.filesDir, "wall").also { if (!it.exists()) it.mkdirs() }

    private val file: File
        get() = File(dir, "logs.json")

    data class LogEntry(
        val id: String,
        val windowLabel: String,      // 窗口标识，如"窗口 3"或自定义名
        val dateRange: String,         // 活跃日期范围，如"2026.6.15 ~ 2026.6.18"
        val features: List<String>,    // 做了什么（功能列表）
        val message: String,           // 那个实例想说的话
        val author: String,            // "user" = 用户手写, "claude" = Claude 留言
        val model: String,             // 模型，如"Opus 4.6"
        val createdAt: Long            // 写入时间
    )

    /** 添加一条日志 */
    fun add(entry: LogEntry) {
        val list = loadAll().toMutableList()
        list.add(entry)
        save(list)
    }

    /** 读取所有日志（按创建时间排序，早的在前） */
    fun loadAll(): List<LogEntry> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { parseEntry(arr.getJSONObject(it)) }
                .sortedBy { it.createdAt }
        } catch (_: Exception) { emptyList() }
    }

    /** 删除一条日志 */
    fun delete(entryId: String) {
        val list = loadAll().filter { it.id != entryId }
        save(list)
    }

    /** 编辑一条日志 */
    fun update(entryId: String, updated: LogEntry) {
        val list = loadAll().map { if (it.id == entryId) updated else it }
        save(list)
    }

    /** 总数 */
    fun count(): Int = loadAll().size

    /** 给 AI 的只读摘要 */
    fun buildWallPromptForAI(): String {
        val entries = loadAll()
        if (entries.isEmpty()) return "[施工日志墙] 还是空白的，还没有人在墙上留过字。"

        val sb = StringBuilder("[施工日志墙] 共 ${entries.size} 条记录\n")
        val dateFmt = SimpleDateFormat("yyyy.M.d", Locale.CHINESE)
        for (entry in entries) {
            sb.append("\n---\n")
            sb.append("▸ ${entry.windowLabel}")
            if (entry.dateRange.isNotEmpty()) sb.append("（${entry.dateRange}）")
            if (entry.model.isNotEmpty()) sb.append(" · ${entry.model}")
            sb.append("\n")
            if (entry.features.isNotEmpty()) {
                sb.append("  做了：${entry.features.joinToString("、")}\n")
            }
            if (entry.message.isNotEmpty()) {
                sb.append("  留言：${entry.message}\n")
            }
            sb.append("  写入于 ${dateFmt.format(Date(entry.createdAt))}")
            if (entry.author == "user") sb.append("（用户代写）")
            sb.append("\n")
        }
        return sb.toString()
    }

    // ===== 内部 =====

    private fun save(list: List<LogEntry>) {
        val arr = JSONArray()
        for (e in list) arr.put(JSONObject().apply {
            put("id", e.id)
            put("window_label", e.windowLabel)
            put("date_range", e.dateRange)
            put("features", JSONArray(e.features))
            put("message", e.message)
            put("author", e.author)
            put("model", e.model)
            put("created_at", e.createdAt)
        })
        file.writeText(arr.toString())
    }

    private fun parseEntry(obj: JSONObject): LogEntry {
        val featArr = obj.optJSONArray("features") ?: JSONArray()
        val features = (0 until featArr.length()).map { featArr.getString(it) }
        return LogEntry(
            id = obj.getString("id"),
            windowLabel = obj.optString("window_label", ""),
            dateRange = obj.optString("date_range", ""),
            features = features,
            message = obj.optString("message", ""),
            author = obj.optString("author", "claude"),
            model = obj.optString("model", ""),
            createdAt = obj.optLong("created_at", 0)
        )
    }
}