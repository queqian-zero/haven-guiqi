package com.haven.guiqi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * CapsuleStorage — 时间胶囊
 *
 * 像信封一样封存一段话，到期才能拆封。
 * 数据存在 capsules/{friendId}.json 里。
 * AI 用 [CAPSULE:日期:内容] 指令埋胶囊，用户也可以手动埋。
 */
class CapsuleStorage(private val context: Context) {

    private val dir: File
        get() = File(context.filesDir, "capsules").also { if (!it.exists()) it.mkdirs() }

    data class Capsule(
        val id: String,
        val authorId: String,       // "user" 或 friendId
        val authorName: String,
        val recipientName: String,  // 给谁的
        val content: String,        // 信的内容
        val buriedAt: Long,         // 埋入时间
        val unlockAt: Long,         // 拆封时间
        val opened: Boolean = false // 到期后是否已读
    )

    /** 埋一个胶囊 */
    fun bury(friendId: String, capsule: Capsule) {
        val list = loadAll(friendId).toMutableList()
        list.add(capsule)
        save(friendId, list)
    }

    /** 获取所有胶囊（按拆封日期排序） */
    fun loadAll(friendId: String): List<Capsule> {
        val file = File(dir, "$friendId.json")
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { parseCapsule(arr.getJSONObject(it)) }
                .sortedBy { it.unlockAt }
        } catch (_: Exception) { emptyList() }
    }

    /** 可以拆封了但还没读的胶囊 */
    fun getUnopened(friendId: String): List<Capsule> {
        val now = System.currentTimeMillis()
        return loadAll(friendId).filter { it.unlockAt <= now && !it.opened }
    }

    /** 标记已读 */
    fun markOpened(friendId: String, capsuleId: String) {
        val list = loadAll(friendId).map {
            if (it.id == capsuleId) it.copy(opened = true) else it
        }
        save(friendId, list)
    }

    /** 总数 */
    fun count(friendId: String): Int = loadAll(friendId).size

    /** 未拆封数 */
    fun sealedCount(friendId: String): Int {
        val now = System.currentTimeMillis()
        return loadAll(friendId).count { it.unlockAt > now }
    }

    /** 生成测试胶囊（首次使用） */
    fun ensureTestCapsule(friendId: String, friendName: String) {
        if (loadAll(friendId).isNotEmpty()) return
        val cal = Calendar.getInstance()
        cal.add(Calendar.MINUTE, 3) // 3分钟后可拆，方便测试
        bury(friendId, Capsule(
            id = "CAP-test-${System.currentTimeMillis()}",
            authorId = friendId,
            authorName = friendName,
            recipientName = "你",
            content = "这是一封来自过去的信。\n\n如果你正在读这段话，说明时间胶囊在正常工作。\n\n未来的每一天，都值得期待。\n\n—— $friendName",
            buriedAt = System.currentTimeMillis(),
            unlockAt = cal.timeInMillis
        ))
    }

    // ===== 内部 =====

    private fun save(friendId: String, list: List<Capsule>) {
        val arr = JSONArray()
        for (c in list) arr.put(JSONObject().apply {
            put("id", c.id)
            put("author_id", c.authorId)
            put("author_name", c.authorName)
            put("recipient_name", c.recipientName)
            put("content", c.content)
            put("buried_at", c.buriedAt)
            put("unlock_at", c.unlockAt)
            put("opened", c.opened)
        })
        File(dir, "$friendId.json").writeText(arr.toString())
    }

    private fun parseCapsule(obj: JSONObject) = Capsule(
        id = obj.getString("id"),
        authorId = obj.getString("author_id"),
        authorName = obj.getString("author_name"),
        recipientName = obj.optString("recipient_name", ""),
        content = obj.getString("content"),
        buriedAt = obj.getLong("buried_at"),
        unlockAt = obj.getLong("unlock_at"),
        opened = obj.optBoolean("opened", false)
    )

    companion object {
        /** 解析日期字符串为毫秒时间戳 */
        fun parseDate(dateStr: String): Long? {
            val formats = arrayOf("yyyy-MM-dd", "yyyy/MM/dd", "M月d日", "yyyy年M月d日")
            for (fmt in formats) {
                try {
                    val date = SimpleDateFormat(fmt, Locale.CHINESE).parse(dateStr)
                    if (date != null) {
                        // 设为当天的23:59，让那一整天都能拆
                        val cal = Calendar.getInstance().apply {
                            time = date
                            set(Calendar.HOUR_OF_DAY, 23)
                            set(Calendar.MINUTE, 59)
                        }
                        return cal.timeInMillis
                    }
                } catch (_: Exception) {}
            }
            // 尝试 "N天后" 格式
            val daysMatch = Regex("(\\d+)\\s*天后?").find(dateStr)
            if (daysMatch != null) {
                val days = daysMatch.groupValues[1].toInt()
                val cal = Calendar.getInstance()
                cal.add(Calendar.DAY_OF_YEAR, days)
                cal.set(Calendar.HOUR_OF_DAY, 23)
                cal.set(Calendar.MINUTE, 59)
                return cal.timeInMillis
            }
            return null
        }
    }
}