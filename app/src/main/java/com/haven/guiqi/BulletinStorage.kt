package com.haven.guiqi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * BulletinStorage — 留言板（每日留言 + 历史30天 + 收藏）
 *
 * AI 通过 [BULLETIN:内容] 指令写留言。
 * 每天的留言独立存储，超过30天的自动清除。
 * 收藏的留言永久保存，取消收藏后开始30天倒计时。
 */
class BulletinStorage(private val context: Context) {

    private val dir: File
        get() = File(context.filesDir, "bulletin").also { if (!it.exists()) it.mkdirs() }

    private val favFile: File
        get() = File(context.filesDir, "bulletin_favorites.json")

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

    // ===== 收藏 =====

    /** 收藏一条留言（复制到收藏列表，永不过期） */
    fun addFavorite(msg: BulletinMessage) {
        val favs = loadFavorites().toMutableList()
        // 用 timestamp 去重
        if (favs.any { it.timestamp == msg.timestamp }) return
        favs.add(msg)
        saveFavorites(favs)
    }

    /** 取消收藏（从收藏列表移除，留言回到日期文件里走正常30天清理） */
    fun removeFavorite(timestamp: Long) {
        val favs = loadFavorites().toMutableList()
        val removed = favs.find { it.timestamp == timestamp }
        favs.removeAll { it.timestamp == timestamp }
        saveFavorites(favs)

        // 如果原始日期文件已经被清理了，把留言补回当天的文件
        // 这样它会从"今天"开始重新计算30天
        if (removed != null) {
            val originalDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                .format(Date(removed.timestamp))
            val dayMessages = loadDay(originalDate)
            val stillExists = dayMessages.any { it.timestamp == removed.timestamp }
            if (!stillExists) {
                // 原文件已清理，存到今天的文件里，让它从今天开始倒数30天
                val today = todayKey()
                val todayMsgs = loadDay(today).toMutableList()
                todayMsgs.add(removed)
                saveDay(today, todayMsgs)
            }
        }
    }

    /** 是否已收藏 */
    fun isFavorite(timestamp: Long): Boolean =
        loadFavorites().any { it.timestamp == timestamp }

    /** 获取所有收藏（按时间降序） */
    fun getFavorites(): List<BulletinMessage> =
        loadFavorites().sortedByDescending { it.timestamp }

    /** 收藏数量 */
    fun getFavoriteCount(): Int = loadFavorites().size

    private fun loadFavorites(): List<BulletinMessage> {
        if (!favFile.exists()) return emptyList()
        return try {
            val arr = JSONArray(favFile.readText())
            (0 until arr.length()).map { i -> parseBulletin(arr.getJSONObject(i)) }
        } catch (_: Exception) { emptyList() }
    }

    private fun saveFavorites(list: List<BulletinMessage>) {
        val arr = JSONArray()
        for (msg in list) arr.put(bulletinToJson(msg))
        favFile.writeText(arr.toString())
    }

    /** 清理超过30天的数据（收藏的留言不删） */
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
            (0 until arr.length()).map { i -> parseBulletin(arr.getJSONObject(i)) }
        } catch (_: Exception) { emptyList() }
    }

    private fun saveDay(key: String, messages: List<BulletinMessage>) {
        val arr = JSONArray()
        for (msg in messages) arr.put(bulletinToJson(msg))
        File(dir, "$key.json").writeText(arr.toString())
    }

    private fun parseBulletin(obj: JSONObject) = BulletinMessage(
        authorId = obj.getString("author_id"),
        authorName = obj.getString("author_name"),
        content = obj.getString("content"),
        timestamp = obj.getLong("timestamp")
    )

    private fun bulletinToJson(msg: BulletinMessage) = JSONObject().apply {
        put("author_id", msg.authorId)
        put("author_name", msg.authorName)
        put("content", msg.content)
        put("timestamp", msg.timestamp)
    }
}

data class BulletinMessage(
    val authorId: String,
    val authorName: String,
    val content: String,
    val timestamp: Long
)