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

    companion object {
        private val SURFACE_LOCK = Any()
        private const val SURFACE_TOKEN_TTL_MS = 30 * 60 * 1000L
        private const val RECALL_TOKEN_TTL_MS = 30 * 60 * 1000L
        private const val MAX_PENDING_SURFACES = 8
        private const val MAX_PENDING_RECALLS = 12
        private val SUMMARY_RANGE_REGEX = Regex(
            "第\\s*(\\d+)\\s*条\\s*[~～至—－-]+\\s*第?\\s*(\\d+)\\s*条"
        )
    }

    private data class PendingSurface(
        val token: String,
        val summaryId: String,
        val shownAt: Long
    )

    private data class PendingRecall(
        val token: String,
        val summaryId: String,
        val shownAt: Long
    )

    private data class SummaryRange(
        val summary: ChatSummary,
        val start: Int,
        val end: Int
    )

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
     * 旧版按关键词回升的兼容入口。
     *
     * 新的 [RECALL] 流程不再调用它：搜索词可能很宽泛，也可能与总结关键词写法不同，
     * 仅凭关键词无法判断住户实际翻到了哪一段原始聊天。
     */
    fun reinforceByKeyword(friendId: String, query: String) {
        val list = loadSummariesRaw(friendId).toMutableList()
        val queryLower = query.lowercase()
        var changed = false

        for ((idx, s) in list.withIndex()) {
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
     * 把留声里搜到的原始聊天，精确映射到它们所属的聊天总结。
     *
     * 聊天总结保存的是“第 x 条~第 y 条”；留声保存的是原始消息时间戳与正文。
     * 这里先在 ChatStorage 中找回原消息的真实序号（包含系统小字，因此与总结范围口径一致），
     * 再判断该序号落在哪一段总结里。导入留声、尚未被总结的近期消息不会被强行匹配。
     *
     * @return EchoMessage.id -> ChatSummary.id
     */
    fun matchEchoMessagesToSummaries(
        friendId: String,
        echoMessages: List<EchoStorage.EchoMessage>
    ): Map<String, String> {
        if (echoMessages.isEmpty()) return emptyMap()

        val chatMessages = ChatStorage(context).loadMessages(friendId)
        if (chatMessages.isEmpty()) return emptyMap()

        val ranges = loadSummariesRaw(friendId).mapNotNull { summary ->
            parseSummaryRange(summary)?.let { (start, end) ->
                SummaryRange(summary, start, end)
            }
        }
        if (ranges.isEmpty()) return emptyMap()

        val result = linkedMapOf<String, String>()
        echoMessages.forEach { echo ->
            if (!echo.source.equals("chat", ignoreCase = true) || echo.timestamp <= 0L) {
                return@forEach
            }

            val position = findChatMessagePosition(chatMessages, echo) ?: return@forEach
            val matchedRange = ranges
                .filter { position in it.start..it.end }
                .minWithOrNull(
                    compareBy<SummaryRange> { it.end - it.start }
                        .thenByDescending { it.summary.createdAt }
                )
                ?: return@forEach

            result[echo.id] = matchedRange.summary.id
        }
        return result
    }

    /**
     * 为留声检索命中的总结登记一次性确认令牌。
     * 同一总结如果再次被检索，会替换旧的未确认令牌，避免一句回复重复增强同一段记忆。
     */
    fun registerRecallCandidates(friendId: String, summaryIds: List<String>): Map<String, String> {
        val existingSummaryIds = loadSummariesRaw(friendId).map { it.id }.toSet()
        val validIds = summaryIds.distinct().filter { it in existingSummaryIds }
        if (validIds.isEmpty()) return emptyMap()

        val now = System.currentTimeMillis()
        val tokens = linkedMapOf<String, String>()
        synchronized(SURFACE_LOCK) {
            val records = loadPendingRecalls(friendId, now)
            records.removeAll { it.summaryId in validIds }

            validIds.forEach { summaryId ->
                val token = UUID.randomUUID().toString()
                records.add(PendingRecall(token, summaryId, now))
                tokens[summaryId] = token
            }
            savePendingRecalls(friendId, records.takeLast(MAX_PENDING_RECALLS))
        }
        return tokens
    }

    /**
     * 住户在可见回复中确实使用了某组留声结果后，确认并精准回升那一段总结。
     * 令牌只属于当前住户、30 分钟后失效，并且只能消费一次。
     */
    fun confirmRecallCandidate(friendId: String, token: String): Boolean {
        if (token.isBlank()) return false
        val now = System.currentTimeMillis()

        synchronized(SURFACE_LOCK) {
            val records = loadPendingRecalls(friendId, now)
            val normalizedToken = token.trim()
            val recordIndex = records.indexOfFirst {
                it.token.equals(normalizedToken, ignoreCase = true)
            }
            if (recordIndex < 0) {
                savePendingRecalls(friendId, records)
                return false
            }

            val record = records.removeAt(recordIndex)
            savePendingRecalls(friendId, records)

            val list = loadSummariesRaw(friendId).toMutableList()
            val summaryIndex = list.indexOfFirst { it.id == record.summaryId }
            if (summaryIndex < 0) return false

            val summary = list[summaryIndex]
            list[summaryIndex] = summary.copy(
                lastRecalledAt = now,
                recallCount = summary.recallCount + 1
            )
            save(friendId, list)
            return true
        }
    }

    private fun findChatMessagePosition(
        chatMessages: List<StoredMessage>,
        echo: EchoStorage.EchoMessage
    ): Int? {
        fun matches(index: Int, requireContent: Boolean, requireRole: Boolean): Boolean {
            val message = chatMessages[index]
            if (message.type == "tip" || message.timestamp != echo.timestamp) return false
            if (requireRole && !message.role.equals(echo.role, ignoreCase = true)) return false
            if (requireContent && message.content != echo.content) return false
            return true
        }

        val exact = chatMessages.indices.firstOrNull { matches(it, requireContent = true, requireRole = true) }
        if (exact != null) return exact + 1

        val sameRole = chatMessages.indices.firstOrNull { matches(it, requireContent = false, requireRole = true) }
        if (sameRole != null) return sameRole + 1

        val sameContent = chatMessages.indices.firstOrNull { matches(it, requireContent = true, requireRole = false) }
        if (sameContent != null) return sameContent + 1

        val sameTimestamp = chatMessages.indices.firstOrNull { matches(it, requireContent = false, requireRole = false) }
        return sameTimestamp?.plus(1)
    }

    private fun parseSummaryRange(summary: ChatSummary): Pair<Int, Int>? {
        val match = SUMMARY_RANGE_REGEX.find(summary.messageRange) ?: return null
        val first = match.groupValues[1].toIntOrNull() ?: return null
        val second = match.groupValues[2].toIntOrNull() ?: return null
        return minOf(first, second) to maxOf(first, second)
    }

    private fun loadPendingRecalls(friendId: String, now: Long): MutableList<PendingRecall> {
        val raw = recallPrefs.getString(pendingRecallKey(friendId), null) ?: return mutableListOf()
        return try {
            val array = JSONArray(raw)
            val records = mutableListOf<PendingRecall>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val token = obj.optString("token", "")
                val summaryId = obj.optString("summaryId", "")
                val shownAt = obj.optLong("shownAt", 0L)
                val age = now - shownAt
                if (token.isNotBlank() && summaryId.isNotBlank() &&
                    shownAt > 0L && age in 0..RECALL_TOKEN_TTL_MS
                ) {
                    records.add(PendingRecall(token, summaryId, shownAt))
                }
            }
            records
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun savePendingRecalls(friendId: String, records: List<PendingRecall>) {
        val key = pendingRecallKey(friendId)
        if (records.isEmpty()) {
            recallPrefs.edit().remove(key).apply()
            return
        }

        val array = JSONArray()
        records.forEach { record ->
            array.put(JSONObject().apply {
                put("token", record.token)
                put("summaryId", record.summaryId)
                put("shownAt", record.shownAt)
            })
        }
        recallPrefs.edit().putString(key, array.toString()).apply()
    }

    private val recallPrefs
        get() = context.getSharedPreferences("haven_summary_recall", Context.MODE_PRIVATE)

    private fun pendingRecallKey(friendId: String): String = "pending_$friendId"

    /**
     * 从遗忘区随机捞一条浮上来。
     * 这里只挑选候选，不会因为系统把它放进 prompt 就自动增强。
     */
    fun getRandomForgotten(friendId: String): ChatSummary? {
        val summaries = loadSummaries(friendId)
        val forgotten = summaries.filter { it.strength < 0.2 }
        if (forgotten.isEmpty()) return null
        return forgotten.random()
    }

    /**
     * 为一次“遗忘记忆闪回”登记临时确认令牌。
     *
     * 同一位住户可能同时被聊天页、自然醒或提醒服务调用，因此令牌和总结 ID 分开保存；
     * 这样不同请求不会仅凭一个全局 pendingId 互相覆盖。令牌最多保留 30 分钟。
     */
    fun registerSurfacedCandidate(friendId: String, summaryId: String): String {
        val token = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        synchronized(SURFACE_LOCK) {
            val records = loadPendingSurfaces(friendId, now)
            records.add(PendingSurface(token, summaryId, now))
            savePendingSurfaces(friendId, records.takeLast(MAX_PENDING_SURFACES))
        }
        return token
    }

    /**
     * 住户在可见正文中确实认领/引用闪回后，由隐藏指令确认。
     * 只有仍在有效期内且属于当前住户的令牌才能让对应总结回升；令牌只能使用一次。
     */
    fun confirmSurfacedCandidate(friendId: String, token: String): Boolean {
        if (token.isBlank()) return false
        val now = System.currentTimeMillis()

        synchronized(SURFACE_LOCK) {
            val records = loadPendingSurfaces(friendId, now)
            val normalizedToken = token.trim()
            val recordIndex = records.indexOfFirst {
                it.token.equals(normalizedToken, ignoreCase = true)
            }
            if (recordIndex < 0) {
                savePendingSurfaces(friendId, records)
                return false
            }

            val record = records.removeAt(recordIndex)
            // 无论总结是否还存在，令牌都只允许消费一次。
            savePendingSurfaces(friendId, records)

            val list = loadSummariesRaw(friendId).toMutableList()
            val summaryIndex = list.indexOfFirst { it.id == record.summaryId }
            if (summaryIndex < 0) return false

            val summary = list[summaryIndex]
            list[summaryIndex] = summary.copy(
                lastRecalledAt = now,
                recallCount = summary.recallCount + 1
            )
            save(friendId, list)
            return true
        }
    }

    private fun loadPendingSurfaces(friendId: String, now: Long): MutableList<PendingSurface> {
        val raw = surfacePrefs.getString(pendingSurfaceKey(friendId), null) ?: return mutableListOf()
        return try {
            val array = JSONArray(raw)
            val records = mutableListOf<PendingSurface>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val token = obj.optString("token", "")
                val summaryId = obj.optString("summaryId", "")
                val shownAt = obj.optLong("shownAt", 0L)
                val age = now - shownAt
                if (token.isNotBlank() && summaryId.isNotBlank() &&
                    shownAt > 0L && age in 0..SURFACE_TOKEN_TTL_MS
                ) {
                    records.add(PendingSurface(token, summaryId, shownAt))
                }
            }
            records
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun savePendingSurfaces(friendId: String, records: List<PendingSurface>) {
        val key = pendingSurfaceKey(friendId)
        if (records.isEmpty()) {
            surfacePrefs.edit().remove(key).apply()
            return
        }

        val array = JSONArray()
        records.forEach { record ->
            array.put(JSONObject().apply {
                put("token", record.token)
                put("summaryId", record.summaryId)
                put("shownAt", record.shownAt)
            })
        }
        surfacePrefs.edit().putString(key, array.toString()).apply()
    }

    private val surfacePrefs
        get() = context.getSharedPreferences("haven_summary_surface", Context.MODE_PRIVATE)

    private fun pendingSurfaceKey(friendId: String): String = "pending_$friendId"

    /**
     * 获取遗忘区的所有总结（给馆藏 UI 用）
     */
    fun loadForgottenSummaries(friendId: String): List<ChatSummary> {
        return loadSummaries(friendId).filter { it.strength < 0.2 }
    }

    /**
     * 根据当前强度判断一条聊天总结处在哪个记忆层级。
     */
    fun getMemoryState(summary: ChatSummary): SummaryMemoryState = getMemoryState(summary.strength)

    fun getMemoryState(strength: Double): SummaryMemoryState {
        return when {
            strength >= 0.5 -> SummaryMemoryState.CLEAR
            strength >= 0.2 -> SummaryMemoryState.FUZZY
            else -> SummaryMemoryState.FORGOTTEN
        }
    }

    /**
     * 按记忆层级读取聊天总结。
     * 数据本身不会被删掉；这里只决定它当前显示在哪个区域。
     */
    fun loadSummariesByState(friendId: String, state: SummaryMemoryState): List<ChatSummary> {
        return loadSummaries(friendId).filter { getMemoryState(it) == state }
    }

    /**
     * 聊天总结分类抽屉使用的数量统计。
     */
    fun countByState(friendId: String): SummaryMemoryCounts {
        val summaries = loadSummaries(friendId)
        var clear = 0
        var fuzzy = 0
        var forgotten = 0
        summaries.forEach { summary ->
            when (getMemoryState(summary)) {
                SummaryMemoryState.CLEAR -> clear++
                SummaryMemoryState.FUZZY -> fuzzy++
                SummaryMemoryState.FORGOTTEN -> forgotten++
            }
        }
        return SummaryMemoryCounts(clear = clear, fuzzy = fuzzy, forgotten = forgotten)
    }

    /**
     * 由用户手动把一条总结重新唤醒。
     * 更新回忆锚点后，它会立刻恢复为清晰状态，并在之后衰减得更慢。
     */
    fun reinforceById(friendId: String, summaryId: String): Boolean {
        val list = loadSummariesRaw(friendId).toMutableList()
        val index = list.indexOfFirst { it.id == summaryId }
        if (index < 0) return false

        val summary = list[index]
        list[index] = summary.copy(
            lastRecalledAt = System.currentTimeMillis(),
            recallCount = summary.recallCount + 1
        )
        save(friendId, list)
        return true
    }

    /**
     * 永久删除一条聊天总结。原始聊天仍保留在“留声”中。
     */
    fun deleteSummary(friendId: String, summaryId: String): Boolean {
        val list = loadSummariesRaw(friendId).toMutableList()
        val removed = list.removeAll { it.id == summaryId }
        if (removed) save(friendId, list)
        return removed
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
    val lastRecalledAt: Long = 0L,  // 上次真正被回忆的时间（RECALL、人工恢复或确认闪回）
    val recallCount: Int = 0        // 真正回忆的次数（每次回忆衰减变慢）
)

/** 聊天总结当前所处的记忆层级。 */
enum class SummaryMemoryState {
    CLEAR,
    FUZZY,
    FORGOTTEN
}

/** 聊天总结分类抽屉使用的记忆数量。 */
data class SummaryMemoryCounts(
    val clear: Int,
    val fuzzy: Int,
    val forgotten: Int
) {
    val total: Int get() = clear + fuzzy + forgotten
}
