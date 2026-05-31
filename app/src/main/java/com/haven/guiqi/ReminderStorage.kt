package com.haven.guiqi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * ReminderStorage - AI 的闹钟存储
 *
 * 存储 AI 通过 [REMIND_ME:时间:理由] 给自己设的提醒。
 * 时间到了 HavenService 会被唤醒，读取这条提醒，调 API 让 AI 说话。
 *
 * 数据结构：
 *   id:         唯一 ID
 *   friendId:   哪个 AI 设的
 *   triggerAt:  触发时间戳（毫秒）
 *   reason:     AI 写的理由（"想看看她在干什么"）
 *   triggered:  是否已触发
 *   createdAt:  创建时间
 */
class ReminderStorage(private val context: Context) {

    private val file = File(context.filesDir, "haven_reminders.json")

    data class Reminder(
        val id: String,
        val friendId: String,
        val triggerAt: Long,
        val reason: String,
        val triggered: Boolean = false,
        val createdAt: Long = System.currentTimeMillis()
    )

    /**
     * 保存一条新提醒
     */
    fun addReminder(friendId: String, triggerAt: Long, reason: String): Reminder {
        val reminder = Reminder(
            id = "rem_${System.currentTimeMillis()}_${(Math.random() * 1000).toInt()}",
            friendId = friendId,
            triggerAt = triggerAt,
            reason = reason
        )
        val list = loadAll().toMutableList()
        list.add(reminder)
        save(list)
        return reminder
    }

    /**
     * 获取某个 ID 的提醒
     */
    fun getReminder(id: String): Reminder? {
        return loadAll().find { it.id == id }
    }

    /**
     * 获取某个好友所有未触发的提醒
     */
    fun getPendingReminders(friendId: String): List<Reminder> {
        return loadAll().filter { it.friendId == friendId && !it.triggered }
    }

    /**
     * 获取所有未触发的提醒（跨好友）
     */
    fun getAllPending(): List<Reminder> {
        return loadAll().filter { !it.triggered }
    }

    /**
     * 标记为已触发
     */
    fun markTriggered(id: String) {
        val list = loadAll().toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index >= 0) {
            list[index] = list[index].copy(triggered = true)
            save(list)
        }
    }

    /**
     * 删除一条提醒
     */
    fun deleteReminder(id: String) {
        val list = loadAll().toMutableList()
        list.removeAll { it.id == id }
        save(list)
    }

    /**
     * 清理已触发的旧提醒（超过 7 天的）
     */
    fun cleanup() {
        val cutoff = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
        val list = loadAll().toMutableList()
        list.removeAll { it.triggered && it.createdAt < cutoff }
        save(list)
    }

    /**
     * 解析时间字符串，返回触发时间戳
     *
     * 支持：
     *   相对时间: 30m, 1h, 2d, 1h30m
     *   绝对时间: 22:00, 08:30
     */
    fun parseTime(timeStr: String): Long? {
        val now = System.currentTimeMillis()

        // 尝试相对时间: 30m, 1h, 2d, 1h30m 等
        val relativePattern = Regex("(?:(\\d+)d)?\\s*(?:(\\d+)h)?\\s*(?:(\\d+)m)?")
        val relMatch = relativePattern.matchEntire(timeStr.trim().lowercase())
        if (relMatch != null) {
            val days = relMatch.groupValues[1].toLongOrNull() ?: 0
            val hours = relMatch.groupValues[2].toLongOrNull() ?: 0
            val minutes = relMatch.groupValues[3].toLongOrNull() ?: 0
            val totalMs = (days * 86400 + hours * 3600 + minutes * 60) * 1000
            if (totalMs > 0) return now + totalMs
        }

        // 尝试绝对时间: 22:00, 08:30
        val absPattern = Regex("(\\d{1,2}):(\\d{2})")
        val absMatch = absPattern.matchEntire(timeStr.trim())
        if (absMatch != null) {
            val hour = absMatch.groupValues[1].toInt()
            val minute = absMatch.groupValues[2].toInt()
            if (hour in 0..23 && minute in 0..59) {
                val cal = java.util.Calendar.getInstance()
                cal.set(java.util.Calendar.HOUR_OF_DAY, hour)
                cal.set(java.util.Calendar.MINUTE, minute)
                cal.set(java.util.Calendar.SECOND, 0)
                cal.set(java.util.Calendar.MILLISECOND, 0)
                // 如果这个时间已经过了，就设成明天
                if (cal.timeInMillis <= now) {
                    cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
                }
                return cal.timeInMillis
            }
        }

        return null
    }

    /**
     * 获取所有已触发的提醒
     */
    fun getTriggered(): List<Reminder> {
        return loadAll().filter { it.triggered }
    }

    // ===== 内部方法 =====

    fun loadAll(): List<Reminder> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                Reminder(
                    id = obj.getString("id"),
                    friendId = obj.getString("friendId"),
                    triggerAt = obj.getLong("triggerAt"),
                    reason = obj.optString("reason", ""),
                    triggered = obj.optBoolean("triggered", false),
                    createdAt = obj.optLong("createdAt", 0)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save(list: List<Reminder>) {
        val arr = JSONArray()
        for (r in list) {
            arr.put(JSONObject().apply {
                put("id", r.id)
                put("friendId", r.friendId)
                put("triggerAt", r.triggerAt)
                put("reason", r.reason)
                put("triggered", r.triggered)
                put("createdAt", r.createdAt)
            })
        }
        file.writeText(arr.toString())
    }
}