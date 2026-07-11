package com.haven.guiqi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * BulletinStorage — 留言板（每日留言 + 历史30天）
 *
 * AI 通过 [BULLETIN:内容] 指令写留言。
 * 每天的留言独立存储，超过30天的自动清除。
 * 点击留言条展开看今天全部留言，有"历史留言"入口看往期。
 */
class BulletinStorage(private val context: Context) {

    private val dir: File
        get() = File(context.filesDir, "bulletin").also { if (!it.exists()) it.mkdirs() }

    private fun todayKey(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

    /** 添加一条今日留言 */
    fun addMessage(friendId: String, friendName: String, content: String) {
        val key = todayKey()
        val messages = loadDay(key).toMutableList()
        messages.add(BulletinMessage(
            authorId = friendId,
            authorName = friendName,
            content = content,
            timestamp = System.currentTimeMillis()
        ))
        saveDay(key, messages)
    }

    /** 获取今日所有留言 */
    fun getTodayMessages(): List<BulletinMessage> = loadDay(todayKey())

    /** 获取今日最新一条（用于滚动条显示） */
    fun getLatestToday(): BulletinMessage? = getTodayMessages().lastOrNull()

    /** 获取所有有留言的日期（降序），最多30天 */
    fun getHistoryDates(): List<String> {
        cleanOldData()
        return dir.listFiles()
            ?.filter { it.extension == "json" }
            ?.map { it.nameWithoutExtension }
            ?.sorted()
            ?.reversed()
            ?: emptyList()
    }

    /** 获取指定日期的留言 */
    fun getMessagesForDate(date: String): List<BulletinMessage> = loadDay(date)

    /** 清理超过30天的数据 */
    private fun cleanOldData() {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -30)
        val cutoff = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(cal.time)
        dir.listFiles()
            ?.filter { it.nameWithoutExtension < cutoff }
            ?.forEach { it.delete() }
    }

    // ===== 内部读写 =====

    private fun loadDay(key: String): List<BulletinMessage> {
        val file = File(dir, "$key.json")
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                BulletinMessage(
                    authorId = obj.getString("author_id"),
                    authorName = obj.getString("author_name"),
                    content = obj.getString("content"),
                    timestamp = obj.getLong("timestamp")
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun saveDay(key: String, messages: List<BulletinMessage>) {
        val arr = JSONArray()
        for (msg in messages) {
            arr.put(JSONObject().apply {
                put("author_id", msg.authorId)
                put("author_name", msg.authorName)
                put("content", msg.content)
                put("timestamp", msg.timestamp)
            })
        }
        File(dir, "$key.json").writeText(arr.toString())
    }
}

data class BulletinMessage(
    val authorId: String,
    val authorName: String,
    val content: String,
    val timestamp: Long
)