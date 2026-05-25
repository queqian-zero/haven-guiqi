package com.haven.guiqi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * ChatSummaryStorage - 聊天总结管理（带遗忘曲线）
 *
 * 每隔 N 条消息自动触发一次总结，调用 API 让 AI 总结最近的对话。
 * 总结带"强度"值，随时间衰减（艾宾浩斯曲线）。
 *
 * 跟核心记忆的区别：
 * - 核心记忆 = 备忘录，AI 自己选择记什么，永远不忘
 * - 聊天总结 = 自动生成，记录聊了什么，会随时间模糊
 *
 * 如果总结里有重要的事，AI 可以主动存进核心记忆，
 * 这样即使总结模糊了，核心记忆里还有。
 *
 * 触发间隔可以由 AI 或用户修改：
 *   AI 用 [SET_SUMMARY_INTERVAL:30] 改成每 30 条总结一次
 *   用户在聊天设置里改
 *
 * JSON 格式：
 * {
 *   "summaries": [
 *     {
 *       "id": "SUM-1715000000",
 *       "content": "我们聊了归栖的开发进度...",
 *       "keywords": "归栖,开发,档案馆,梦境",
 *       "messageRange": "第201条~第220条",
 *       "strength": 0.85,
 *       "createdAt": 1715000000
 *     }
 *   ]
 * }
 */
class ChatSummaryStorage(private val context: Context) {

    private val summaryDir: File
        get() {
            val dir = File(context.filesDir, "summaries")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    private fun getFile(friendId: String): File = File(summaryDir, "$friendId.json")

    /**
     * 保存一条总结
     */
    fun addSummary(friendId: String, content: String, keywords: String, messageRange: String): ChatSummary {
        val now = System.currentTimeMillis()
        val summary = ChatSummary(
            id = "SUM-$now",
            content = content.trim(),
            keywords = keywords.trim(),
            messageRange = messageRange,
            strength = 1.0,
            createdAt = now
        )
        val list = loadSummariesRaw(friendId).toMutableList()
        list.add(summary)
        save(friendId, list)
        return summary
    }

    /**
     * 加载所有总结（原始数据，不计算衰减）
     */
    fun loadSummariesRaw(friendId: String): List<ChatSummary> {
        val file = getFile(friendId)
        if (!file.exists()) return emptyList()

        return try {
            val json = JSONObject(file.readText())
            val array = json.getJSONArray("summaries")
            val list = mutableListOf<ChatSummary>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(ChatSummary(
                    id = obj.getString("id"),
                    content = obj.optString("content", ""),
                    keywords = obj.optString("keywords", ""),
                    messageRange = obj.optString("messageRange", ""),
                    strength = obj.optDouble("strength", 1.0),
                    createdAt = obj.optLong("createdAt", 0L)
                ))
            }
            list.sortedBy { it.createdAt }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 加载总结并计算当前强度（带艾宾浩斯衰减）
     *
     * 衰减公式：strength = e^(-λt)
     * λ = 0.008（比记忆慢，总结本来就是概括，不需要那么细）
     * t = 距离生成的小时数
     *
     * 衰减时间线：
     *   1天后 ≈ 0.82
     *   3天后 ≈ 0.56
     *   一周后 ≈ 0.26
     *   两周后 ≈ 0.07（基本忘了）
     */
    fun loadSummaries(friendId: String): List<ChatSummary> {
        val raw = loadSummariesRaw(friendId)
        val now = System.currentTimeMillis()

        return raw.map { s ->
            val hoursSinceCreated = (now - s.createdAt) / 3600000.0
            val lambda = 0.008
            val currentStrength = Math.exp(-lambda * hoursSinceCreated)
            s.copy(strength = currentStrength)
        }
    }

    /**
     * 拼成 system prompt 给 AI 看
     *
     * 按强度分层：
     *   0.5+ → 显示完整总结
     *   0.2~0.5 → 只显示关键词
     *   <0.2 → 不显示
     */
    fun buildSummaryPrompt(friendId: String): String {
        val summaries = loadSummaries(friendId)
        if (summaries.isEmpty()) return ""

        val clear = summaries.filter { it.strength >= 0.5 }
        val fuzzy = summaries.filter { it.strength in 0.2..0.499 }
        val forgotten = summaries.filter { it.strength < 0.2 }

        val sb = StringBuilder("\n\n[聊天记忆（自动总结）]\n")

        if (clear.isNotEmpty()) {
            sb.append("最近的对话总结：\n")
            for (s in clear.takeLast(5)) {
                val dateStr = SimpleDateFormat("M月d日", Locale.CHINESE).format(Date(s.createdAt))
                sb.append("· $dateStr: ${s.content}\n")
            }
        }

        if (fuzzy.isNotEmpty()) {
            sb.append("更早的对话（有点模糊了）：\n")
            for (s in fuzzy.takeLast(5)) {
                val dateStr = SimpleDateFormat("M月d日", Locale.CHINESE).format(Date(s.createdAt))
                sb.append("· $dateStr: 关键词: ${s.keywords}\n")
            }
        }

        if (forgotten.isNotEmpty()) {
            sb.append("（还有 ${forgotten.size} 段更早的对话，但已经记不太清了）\n")
        }

        return sb.toString()
    }

    /**
     * 构建总结请求的 system prompt
     * 给 API 发这个，让 AI 总结最近的对话
     */
    fun buildSummaryRequestPrompt(): String {
        return """你是一个对话总结器。请总结下面的对话内容。

要求：
1. 总结要简洁，100字以内
2. 抓住关键信息：聊了什么话题、做了什么决定、有什么重要的情感交流
3. 不要流水账，提炼核心内容
4. 用第三人称叙述（"用户和AI聊了..."）

你必须用以下格式回复（不要加任何其他内容）：
[SUMMARY]总结内容
[KEYWORDS]关键词1,关键词2,关键词3"""
    }

    /**
     * 解析总结 API 的返回结果
     * @return Pair<总结内容, 关键词>，解析失败返回 null
     */
    fun parseSummaryResponse(response: String): Pair<String, String>? {
        val summaryPattern = Regex("\\[SUMMARY](.+?)(?=\\[KEYWORDS]|$)", RegexOption.DOT_MATCHES_ALL)
        val keywordsPattern = Regex("\\[KEYWORDS](.+?)$", RegexOption.DOT_MATCHES_ALL)

        val summaryMatch = summaryPattern.find(response) ?: return null
        val content = summaryMatch.groupValues[1].trim()
        if (content.isEmpty()) return null

        val keywords = keywordsPattern.find(response)?.groupValues?.get(1)?.trim() ?: ""

        return Pair(content, keywords)
    }

    /**
     * 获取/设置总结触发间隔
     * 默认 20 条消息触发一次
     */
    fun getSummaryInterval(friendId: String): Int {
        val prefs = context.getSharedPreferences("haven_summary", Context.MODE_PRIVATE)
        return prefs.getInt("interval_$friendId", 20)
    }

    fun setSummaryInterval(friendId: String, interval: Int) {
        val sanitized = interval.coerceIn(10, 100)  // 最少10条，最多100条
        context.getSharedPreferences("haven_summary", Context.MODE_PRIVATE)
            .edit().putInt("interval_$friendId", sanitized).apply()
    }

    /**
     * 获取/设置上次总结时的消息数量
     * 用于判断是否该触发新一轮总结
     */
    fun getLastSummaryMessageCount(friendId: String): Int {
        val prefs = context.getSharedPreferences("haven_summary", Context.MODE_PRIVATE)
        return prefs.getInt("lastCount_$friendId", 0)
    }

    fun setLastSummaryMessageCount(friendId: String, count: Int) {
        context.getSharedPreferences("haven_summary", Context.MODE_PRIVATE)
            .edit().putInt("lastCount_$friendId", count).apply()
    }

    /**
     * 检查是否该触发总结了
     * @param currentMessageCount 当前消息总数
     * @return true = 该总结了
     */
    fun shouldTriggerSummary(friendId: String, currentMessageCount: Int): Boolean {
        val lastCount = getLastSummaryMessageCount(friendId)
        val interval = getSummaryInterval(friendId)
        return (currentMessageCount - lastCount) >= interval
    }

    fun count(friendId: String): Int = loadSummariesRaw(friendId).size

    // ===== 内部保存 =====

    private fun save(friendId: String, list: List<ChatSummary>) {
        val array = JSONArray()
        for (s in list) {
            array.put(JSONObject().apply {
                put("id", s.id)
                put("content", s.content)
                put("keywords", s.keywords)
                put("messageRange", s.messageRange)
                put("strength", s.strength)
                put("createdAt", s.createdAt)
            })
        }
        getFile(friendId).writeText(JSONObject().apply {
            put("summaries", array)
        }.toString())
    }
}

/**
 * 一条聊天总结
 */
data class ChatSummary(
    val id: String,
    val content: String,       // 总结内容
    val keywords: String,      // 关键词（逗号分隔）
    val messageRange: String,  // 消息范围（"第201条~第220条"）
    val strength: Double,      // 记忆强度（0.0~1.0，随时间衰减）
    val createdAt: Long
)