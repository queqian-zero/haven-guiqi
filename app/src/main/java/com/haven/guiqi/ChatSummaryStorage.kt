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
                    createdAt = obj.optLong("createdAt", 0L),
                    lastRecalledAt = obj.optLong("lastRecalledAt", 0L),
                    recallCount = obj.optInt("recallCount", 0)
                ))
            }
            list.sortedBy { it.createdAt }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 加载总结并计算当前强度（带艾宾浩斯衰减 + 回忆回升）
     *
     * 衰减公式：strength = e^(-λt)
     *   t = 距离「上次回忆」或「创建」的小时数（取较晚的那个）
     *   λ = 0.008 / (1 + recallCount × 0.3)
     *
     * 回忆次数越多，衰减越慢——这就是真正的艾宾浩斯间隔重复：
     *   0次回忆：一周后 ≈ 0.26，两周后 ≈ 0.07
     *   1次回忆：λ=0.0062，一周后 ≈ 0.35，两周后 ≈ 0.12
     *   3次回忆：λ=0.0042，一周后 ≈ 0.50，两周后 ≈ 0.25
     */
    fun loadSummaries(friendId: String): List<ChatSummary> {
        val raw = loadSummariesRaw(friendId)
        val now = System.currentTimeMillis()

        return raw.map { s ->
            // 从「上次回忆」或「创建时间」取较晚的那个作为衰减起点
            val anchor = if (s.lastRecalledAt > 0) maxOf(s.createdAt, s.lastRecalledAt) else s.createdAt
            val hoursSinceAnchor = (now - anchor) / 3600000.0
            // 回忆越多，衰减越慢
            val lambda = 0.008 / (1.0 + s.recallCount * 0.3)
            val currentStrength = Math.exp(-lambda * hoursSinceAnchor)
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
                val dateStr = SimpleDateFormat("M月d日(E) HH:mm", Locale.CHINESE).format(Date(s.createdAt))
                sb.append("· $dateStr: ${s.content}\n")
            }
        }

        if (fuzzy.isNotEmpty()) {
            sb.append("更早的对话（有点模糊了）：\n")
            for (s in fuzzy.takeLast(5)) {
                val dateStr = SimpleDateFormat("M月d日(E)", Locale.CHINESE).format(Date(s.createdAt))
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
5. 保留时间信息——在总结开头注明这段对话发生在什么日期、星期几、大概什么时间段

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

    /**
     * 回忆回升：AI 用 [RECALL] 搜到了相关内容时调用
     * 根据搜索关键词匹配总结的 keywords，命中的总结获得回升
     */
    fun reinforceByKeyword(friendId: String, query: String) {
        val list = loadSummariesRaw(friendId).toMutableList()
        val queryLower = query.lowercase()
        var changed = false

        for ((idx, s) in list.withIndex()) {
            // 关键词匹配：总结的 keywords 里有任何一个词跟查询相关
            val keywords = s.keywords.split(",").map { it.trim().lowercase() }
            val matches = keywords.any { it.isNotEmpty() && (queryLower.contains(it) || it.contains(queryLower)) }
            if (matches) {
                list[idx] = s.copy(
                    lastRecalledAt = System.currentTimeMillis(),
                    recallCount = s.recallCount + 1
                )
                changed = true
            }
        }
        if (changed) save(friendId, list)
    }

    /**
     * 从遗忘区随机捞一条浮上来
     * 用于潜意识系统：偶尔一条被遗忘的记忆重新浮现
     * @return 浮上来的总结内容（包含日期和关键词），没有则返回 null
     */
    fun getRandomForgotten(friendId: String): ChatSummary? {
        val summaries = loadSummaries(friendId)
        val forgotten = summaries.filter { it.strength < 0.2 }
        if (forgotten.isEmpty()) return null
        return forgotten.random()
    }

    /**
     * 标记一条遗忘区的总结被"浮上来"了（回升它的强度）
     */
    fun markSurfaced(friendId: String, summaryId: String) {
        val list = loadSummariesRaw(friendId).toMutableList()
        val idx = list.indexOfFirst { it.id == summaryId }
        if (idx >= 0) {
            list[idx] = list[idx].copy(
                lastRecalledAt = System.currentTimeMillis(),
                recallCount = list[idx].recallCount + 1
            )
            save(friendId, list)
        }
    }

    /**
     * 获取遗忘区的所有总结（给馆藏 UI 用）
     */
    fun loadForgottenSummaries(friendId: String): List<ChatSummary> {
        return loadSummaries(friendId).filter { it.strength < 0.2 }
    }

    fun count(friendId: String): Int = loadSummariesRaw(friendId).size

    /**
     * 触发聊天总结（在后台线程调用 API）
     *
     * 从 ChatConversationActivity 拆出来。
     * @param onComplete 总结成功时在主线程回调（可用来显示系统提示）
     */
    fun triggerSummary(
        friendId: String,
        currentCount: Int,
        chatStorage: ChatStorage,
        apiUrl: String,
        apiKey: String,
        apiModel: String,
        apiType: String,
        onComplete: (() -> Unit)? = null
    ) {
        Thread {
            try {
                val interval = getSummaryInterval(friendId)
                val messages = chatStorage.loadMessages(friendId)
                val recentMsgs = messages.takeLast(interval)
                val chatContent = recentMsgs.joinToString("\n") { msg ->
                    val role = if (msg.role == "user") "用户" else "AI"
                    val time = java.text.SimpleDateFormat("M月d日(E) HH:mm", java.util.Locale.CHINESE)
                        .format(java.util.Date(msg.timestamp))
                    "[$time] $role: ${msg.content.take(200)}"
                }

                val summaryPrompt = buildSummaryRequestPrompt()
                val api = ApiHelper(apiUrl, apiKey, apiModel, apiType)
                val summaryMessages = listOf(
                    ChatMessage("system", summaryPrompt),
                    ChatMessage("user", chatContent)
                )
                val response = api.sendChat(summaryMessages)

                val result = parseSummaryResponse(response.text)
                if (result != null) {
                    val (content, keywords) = result
                    val range = "第${currentCount - interval + 1}条~第${currentCount}条"
                    addSummary(friendId, content, keywords, range)
                    setLastSummaryMessageCount(friendId, currentCount)
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        onComplete?.invoke()
                    }
                }
            } catch (_: Exception) {
                // 总结失败不影响聊天
            }
        }.start()
    }

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
                put("lastRecalledAt", s.lastRecalledAt)
                put("recallCount", s.recallCount)
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
    val createdAt: Long,
    val lastRecalledAt: Long = 0L,  // 上次被回忆起的时间（RECALL 或浮上来时更新）
    val recallCount: Int = 0        // 被回忆的次数（每次回忆衰减变慢）
)