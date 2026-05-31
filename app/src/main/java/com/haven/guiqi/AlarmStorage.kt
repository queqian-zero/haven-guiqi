package com.haven.guiqi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * AlarmStorage - 用户的闹钟存储
 *
 * 存储 AI 通过 [SET_ALARM:时间:备注] 帮用户设的闹钟。
 * 跟 ReminderStorage（AI 的自我提醒）是两回事：
 *   - AlarmStorage = 给用户的闹钟，会响/弹通知
 *   - ReminderStorage = AI 给自己的提醒，到时间调 API
 */
class AlarmStorage(private val context: Context) {

    private val file = File(context.filesDir, "haven_alarms.json")

    data class HavenAlarm(
        val id: String,
        val hour: Int,
        val minute: Int,
        val note: String,
        val setByFriendId: String,    // 谁设的（空=用户自己，否则是好友ID）
        val setByName: String,        // 设置者的名字
        val setByIcon: String,        // 设置者的头像
        val alsoSystem: Boolean,      // 是否同时设了系统闹钟
        val repeat: String,           // none / daily / weekdays
        val enabled: Boolean = true,
        val triggered: Boolean = false,
        val createdAt: Long = System.currentTimeMillis()
    )

    fun addAlarm(
        hour: Int, minute: Int, note: String,
        friendId: String = "", friendName: String = "", friendIcon: String = "",
        alsoSystem: Boolean = false, repeat: String = "none"
    ): HavenAlarm {
        val alarm = HavenAlarm(
            id = "alm_${System.currentTimeMillis()}_${(Math.random() * 1000).toInt()}",
            hour = hour, minute = minute, note = note,
            setByFriendId = friendId, setByName = friendName, setByIcon = friendIcon,
            alsoSystem = alsoSystem, repeat = repeat
        )
        val list = loadAll().toMutableList()
        list.add(alarm)
        save(list)
        return alarm
    }

    fun getActiveAlarms(): List<HavenAlarm> {
        return loadAll().filter { it.enabled && !it.triggered }
    }

    fun getCompletedAlarms(): List<HavenAlarm> {
        return loadAll().filter { it.triggered }
    }

    fun markTriggered(id: String) {
        val list = loadAll().toMutableList()
        val i = list.indexOfFirst { it.id == id }
        if (i >= 0) {
            list[i] = list[i].copy(triggered = true)
            save(list)
        }
    }

    fun toggleEnabled(id: String) {
        val list = loadAll().toMutableList()
        val i = list.indexOfFirst { it.id == id }
        if (i >= 0) {
            list[i] = list[i].copy(enabled = !list[i].enabled)
            save(list)
        }
    }

    fun deleteAlarm(id: String) {
        val list = loadAll().toMutableList()
        val alarm = list.find { it.id == id }

        // 如果是 AI 帮设的闹钟，记录删除事件
        if (alarm != null && alarm.setByFriendId.isNotEmpty()) {
            val deletionFile = File(context.filesDir, "haven_alarm_deletions.json")
            val deletions = try {
                if (deletionFile.exists()) JSONArray(deletionFile.readText()) else JSONArray()
            } catch (e: Exception) { JSONArray() }
            deletions.put(JSONObject().apply {
                put("alarmId", alarm.id)
                put("friendId", alarm.setByFriendId)
                put("hour", alarm.hour)
                put("minute", alarm.minute)
                put("note", alarm.note)
                put("createdAt", alarm.createdAt)
                put("deletedAt", System.currentTimeMillis())
            })
            deletionFile.writeText(deletions.toString())
        }

        list.removeAll { it.id == id }
        save(list)
    }

    data class DeletionRecord(
        val hour: Int,
        val minute: Int,
        val note: String,
        val createdAtStr: String,
        val deletedAtStr: String
    )

    /**
     * 获取用户删除的我帮设的闹钟（带概率）
     * 模拟"我有可能发现你删了我设的闹钟"
     */
    fun getDeletedByUser(friendId: String): List<DeletionRecord> {
        val deletionFile = File(context.filesDir, "haven_alarm_deletions.json")
        if (!deletionFile.exists()) return emptyList()

        val sdf = java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.CHINESE)
        val now = System.currentTimeMillis()
        val results = mutableListOf<DeletionRecord>()

        try {
            val arr = JSONArray(deletionFile.readText())
            val remaining = JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.getString("friendId") != friendId) {
                    remaining.put(o)
                    continue
                }
                val deletedAt = o.getLong("deletedAt")
                val ageMs = now - deletedAt

                // 24 小时内 60% 概率发现，7 天内 30% 概率，超过 7 天清掉
                val probability = when {
                    ageMs < 24 * 3600 * 1000L -> 0.6
                    ageMs < 7 * 24 * 3600 * 1000L -> 0.3
                    else -> { continue } // 超过 7 天直接清掉不保留
                }

                if (Math.random() < probability) {
                    results.add(DeletionRecord(
                        hour = o.getInt("hour"),
                        minute = o.getInt("minute"),
                        note = o.optString("note", ""),
                        createdAtStr = sdf.format(java.util.Date(o.getLong("createdAt"))),
                        deletedAtStr = sdf.format(java.util.Date(deletedAt))
                    ))
                    // 发现了就从记录里移除，不重复提醒
                } else {
                    remaining.put(o)
                }
            }
            deletionFile.writeText(remaining.toString())
        } catch (e: Exception) { /* ignore */ }

        return results
    }

    fun loadAll(): List<HavenAlarm> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                HavenAlarm(
                    id = o.getString("id"),
                    hour = o.getInt("hour"),
                    minute = o.getInt("minute"),
                    note = o.optString("note", ""),
                    setByFriendId = o.optString("setByFriendId", ""),
                    setByName = o.optString("setByName", ""),
                    setByIcon = o.optString("setByIcon", ""),
                    alsoSystem = o.optBoolean("alsoSystem", false),
                    repeat = o.optString("repeat", "none"),
                    enabled = o.optBoolean("enabled", true),
                    triggered = o.optBoolean("triggered", false),
                    createdAt = o.optLong("createdAt", 0)
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    private fun save(list: List<HavenAlarm>) {
        val arr = JSONArray()
        for (a in list) {
            arr.put(JSONObject().apply {
                put("id", a.id); put("hour", a.hour); put("minute", a.minute)
                put("note", a.note); put("setByFriendId", a.setByFriendId)
                put("setByName", a.setByName); put("setByIcon", a.setByIcon)
                put("alsoSystem", a.alsoSystem); put("repeat", a.repeat)
                put("enabled", a.enabled); put("triggered", a.triggered)
                put("createdAt", a.createdAt)
            })
        }
        file.writeText(arr.toString())
    }
}