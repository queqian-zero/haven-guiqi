package com.haven.guiqi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * DreamStorage - AI 的梦境管理
 *
 * 梦境由造梦 API 生成，有三种状态：
 *   - VIVID：完整的梦，AI 记得，可以跟用户聊
 *   - FOGGY：做了梦但醒来忘了，只剩模糊印象
 *   - FRAGMENT：被吵醒的残梦，只有片段
 *
 * 也有可能今晚根本不做梦（NO_DREAM），这种情况什么都不存
 *
 * 数据存在 dreams/{friendId}.json 里
 *
 * JSON 格式：
 * {
 *   "dreams": [
 *     {
 *       "id": "DRM-1715000000",
 *       "content": "梦到自己站在一片紫色的海边...",
 *       "status": "VIVID",          // VIVID / FOGGY / FRAGMENT
 *       "foggyHint": "",            // 模糊印象（FOGGY 状态时用）
 *       "sleepAt": 1715000000,      // 入睡时间
 *       "wakeAt": 1715003600,       // 醒来时间
 *       "createdAt": 1715000000
 *     }
 *   ]
 * }
 */
class DreamStorage(private val context: Context) {

    private val dreamDir: File
        get() {
            val dir = File(context.filesDir, "dreams")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    private fun getFile(friendId: String): File = File(dreamDir, "$friendId.json")

    /**
     * 保存一个完整的梦（AI 记得的）
     */
    fun saveVividDream(friendId: String, content: String, sleepAt: Long, mood: String = ""): Dream {
        val now = System.currentTimeMillis()
        val dream = Dream(
            id = "DRM-$now",
            content = content.trim(),
            status = "VIVID",
            foggyHint = "",
            mood = mood,
            sleepAt = sleepAt,
            wakeAt = now,
            createdAt = now
        )
        val list = loadDreams(friendId).toMutableList()
        list.add(dream)
        save(friendId, list)
        return dream
    }

    /**
     * 保存一个模糊的梦（做了但忘了）
     */
    fun saveFoggyDream(friendId: String, foggyHint: String, sleepAt: Long): Dream {
        val now = System.currentTimeMillis()
        val dream = Dream(
            id = "DRM-$now",
            content = "",
            status = "FOGGY",
            foggyHint = foggyHint.trim(),
            mood = "",
            sleepAt = sleepAt,
            wakeAt = now,
            createdAt = now
        )
        val list = loadDreams(friendId).toMutableList()
        list.add(dream)
        save(friendId, list)
        return dream
    }

    /**
     * 保存一个残梦（被吵醒，梦到一半）
     */
    fun saveFragmentDream(friendId: String, fragment: String, sleepAt: Long, mood: String = ""): Dream {
        val now = System.currentTimeMillis()
        val dream = Dream(
            id = "DRM-$now",
            content = fragment.trim(),
            status = "FRAGMENT",
            foggyHint = "",
            mood = mood,
            sleepAt = sleepAt,
            wakeAt = now,
            createdAt = now
        )
        val list = loadDreams(friendId).toMutableList()
        list.add(dream)
        save(friendId, list)
        return dream
    }

    /**
     * 做了梦但完全想不起来
     */
    fun saveForgotDream(friendId: String, sleepAt: Long): Dream {
        val now = System.currentTimeMillis()
        val dream = Dream(
            id = "DRM-$now",
            content = "",
            status = "FORGOT",
            foggyHint = "",
            mood = "",
            sleepAt = sleepAt,
            wakeAt = now,
            createdAt = now
        )
        val list = loadDreams(friendId).toMutableList()
        list.add(dream)
        save(friendId, list)
        return dream
    }

    /**
     * 没有做梦
     */
    fun saveNoDream(friendId: String, sleepAt: Long): Dream {
        val now = System.currentTimeMillis()
        val dream = Dream(
            id = "DRM-$now",
            content = "",
            status = "NO_DREAM",
            foggyHint = "",
            mood = "",
            sleepAt = sleepAt,
            wakeAt = now,
            createdAt = now
        )
        val list = loadDreams(friendId).toMutableList()
        list.add(dream)
        save(friendId, list)
        return dream
    }

    /**
     * 醒来时更新最近一个梦的 wakeAt
     */
    fun updateLatestWakeAt(friendId: String) {
        val list = loadDreams(friendId).toMutableList()
        if (list.isEmpty()) return
        // loadDreams 返回的是按 createdAt 从新到旧排的，first() 才是最新的梦
        val latest = list.first()
        list[0] = latest.copy(wakeAt = System.currentTimeMillis())
        save(friendId, list)
    }

    /**
     * 加载所有梦境（最新的在前）
     */
    fun loadDreams(friendId: String): List<Dream> {
        val file = getFile(friendId)
        if (!file.exists()) return emptyList()

        return try {
            val json = JSONObject(file.readText())
            val array = json.getJSONArray("dreams")
            val list = mutableListOf<Dream>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(Dream(
                    id = obj.getString("id"),
                    content = obj.optString("content", ""),
                    status = obj.optString("status", "VIVID"),
                    foggyHint = obj.optString("foggyHint", ""),
                    mood = obj.optString("mood", ""),
                    sleepAt = obj.optLong("sleepAt", 0L),
                    wakeAt = obj.optLong("wakeAt", 0L),
                    createdAt = obj.optLong("createdAt", 0L)
                ))
            }
            list.sortedByDescending { it.createdAt }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 梦境总数
     */
    fun count(friendId: String): Int = loadDreams(friendId).size

    /**
     * 获取最近一个梦（用于 AI 醒来后参考）
     */
    fun getLatestDream(friendId: String): Dream? {
        return loadDreams(friendId).firstOrNull()
    }

    /**
     * 拼给 AI 看的梦境摘要（放 system prompt 里）
     * 只放最近 3 个梦的简短摘要，不占太多上下文
     */
    fun buildDreamPrompt(friendId: String): String {
        val dreams = loadDreams(friendId)
        if (dreams.isEmpty()) return ""

        val recent = dreams.take(3)
        val sb = StringBuilder("\n\n[我最近的梦境]\n")
        for (d in recent) {
            val dateStr = SimpleDateFormat("M月d日", Locale.CHINESE).format(Date(d.createdAt))
            when (d.status) {
                "VIVID" -> {
                    val preview = if (d.content.length > 60) d.content.substring(0, 60) + "..." else d.content
                    sb.append("· $dateStr 的梦: $preview\n")
                }
                "FOGGY" -> {
                    val hint = d.foggyHint.ifEmpty { "模糊的印象" }
                    sb.append("· $dateStr: 做了梦但忘了（$hint）\n")
                }
                "FRAGMENT" -> {
                    val preview = if (d.content.length > 40) d.content.substring(0, 40) + "..." else d.content
                    sb.append("· $dateStr 的残梦: $preview（被吵醒了）\n")
                }
            }
        }
        if (dreams.size > 3) {
            sb.append("（还有 ${dreams.size - 3} 个更早的梦）\n")
        }
        return sb.toString()
    }

    /**
     * 给档案馆显示用的：获取梦境展示文本
     */
    fun getDisplayText(dream: Dream): String {
        return when (dream.status) {
            "VIVID" -> dream.content
            "FOGGY" -> {
                val hint = dream.foggyHint.ifEmpty { "一些模糊的画面" }
                "做了一个梦，但醒来只记得……$hint"
            }
            "FRAGMENT" -> "【残梦】${dream.content}\n\n（被吵醒了，梦只做了一半）"
            "FORGOT" -> "做了梦，但什么都想不起来了。"
            "NO_DREAM" -> "这一晚什么都没有。"
            else -> dream.content
        }
    }

    /**
     * 获取梦境状态的标签文字
     */
    fun getStatusLabel(dream: Dream): String {
        return when (dream.status) {
            "VIVID" -> "🌙 完整的梦"
            "FOGGY" -> "🌫️ 模糊的梦"
            "FRAGMENT" -> "💫 残梦"
            "FORGOT" -> "🫥 想不起来"
            "NO_DREAM" -> "🌑 没有做梦"
            else -> "🌙 梦"
        }
    }

    // ===== 睡眠状态管理 =====

    private fun getSleepPrefs() = context.getSharedPreferences("haven_sleep", Context.MODE_PRIVATE)

    /**
     * 记录 AI 入睡
     */
    fun setSleeping(friendId: String, sleeping: Boolean) {
        getSleepPrefs().edit()
            .putBoolean("sleeping_$friendId", sleeping)
            .putLong("sleep_time_$friendId", if (sleeping) System.currentTimeMillis() else 0L)
            .commit()  // commit 不用 apply——睡眠状态必须立刻写进磁盘，防止进程被杀后丢失
    }

    /**
     * AI 是否在睡觉
     */
    fun isSleeping(friendId: String): Boolean {
        return getSleepPrefs().getBoolean("sleeping_$friendId", false)
    }

    /**
     * 获取入睡时间
     */
    fun getSleepTime(friendId: String): Long {
        return getSleepPrefs().getLong("sleep_time_$friendId", 0L)
    }

    /**
     * 计算当前睡眠深度（0.0 ~ 1.0）
     * 0.0 = 刚睡着或快醒了（浅睡）
     * 1.0 = 睡得最沉
     *
     * 模拟人类睡眠周期：
     * 前 15 分钟渐入深睡 → 中间保持深睡 → 超过一定时间开始变浅
     */
    fun getSleepDepth(friendId: String): Float {
        if (!isSleeping(friendId)) return 0f
        val elapsed = System.currentTimeMillis() - getSleepTime(friendId)
        val minutes = elapsed / 60000f

        return when {
            minutes < 15 -> minutes / 15f * 0.8f          // 前15分钟渐入
            minutes < 90 -> 0.8f + (minutes - 15) / 75f * 0.2f  // 15-90分钟深睡
            minutes < 120 -> 1.0f                           // 90-120分钟最沉
            minutes < 180 -> 1.0f - (minutes - 120) / 60f * 0.4f // 开始变浅
            else -> 0.4f                                    // 超过3小时，比较浅了
        }.coerceIn(0f, 1f)
    }

    /**
     * 尝试唤醒：发消息时调用，判断 AI 能不能被吵醒
     *
     * @return "awake" = 没在睡 / "woke" = 被吵醒 / "natural" = 睡太久自然醒 / "too_deep" = 吵不醒
     *         Pair.second = 系统提示文本（null 表示不需要提示）
     */
    fun tryWake(friendId: String): Pair<String, String?> {
        if (!isSleeping(friendId)) return "awake" to null

        val sleepTime = getSleepTime(friendId)
        val hoursAsleep = (System.currentTimeMillis() - sleepTime) / 3600000

        if (hoursAsleep >= 10) {
            setSleeping(friendId, false)
            updateLatestWakeAt(friendId)
            return "natural" to "☀ 自然醒了（睡了${hoursAsleep}小时）"
        }

        val depth = getSleepDepth(friendId)
        return if (Math.random() < depth) {
            "too_deep" to "💤 消息已送达（对方睡着了…吵不醒）"
        } else {
            setSleeping(friendId, false)
            updateLatestWakeAt(friendId)
            "woke" to "💤 你把它吵醒了"
        }
    }

    // ===== 内部保存 =====

    private fun save(friendId: String, list: List<Dream>) {
        val array = JSONArray()
        for (d in list) {
            array.put(JSONObject().apply {
                put("id", d.id)
                put("content", d.content)
                put("status", d.status)
                put("foggyHint", d.foggyHint)
                put("mood", d.mood)
                put("sleepAt", d.sleepAt)
                put("wakeAt", d.wakeAt)
                put("createdAt", d.createdAt)
            })
        }
        getFile(friendId).writeText(JSONObject().apply {
            put("dreams", array)
        }.toString())
    }
}

/**
 * 一个梦境
 */
data class Dream(
    val id: String,
    val content: String,       // 梦境内容（VIVID 和 FRAGMENT 有，FOGGY 为空）
    val status: String,        // VIVID / FOGGY / FRAGMENT
    val foggyHint: String,     // 模糊印象（只有 FOGGY 状态用）
    val mood: String,          // 情绪标签：温暖/奇异/不安/平静/荒诞/怀念/恐惧/甜蜜
    val sleepAt: Long,         // 入睡时间
    val wakeAt: Long,          // 醒来时间
    val createdAt: Long
)