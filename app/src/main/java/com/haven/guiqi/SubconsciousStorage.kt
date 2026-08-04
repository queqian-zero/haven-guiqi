package com.haven.guiqi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer

/**
 * SubconsciousStorage — 潜意识便签库
 *
 * 存的是AI在聊天中流露出的偏好、在意的事、想做的事、答应过的承诺。
 * 空闲心跳醒来的时候，从这里抽几条递给AI，让它自己决定做什么。
 *
 * 不是任务清单。是潜意识——AI以为自己突然想起了一件事，
 * 其实是某个信号悄悄触发了一段沉底的记忆。
 *
 * 三层机制：
 * 1. 沉底：展示过的条目权重降低，自然沉底
 * 2. 时间衰减："想做的事"越新越容易被抽到
 * 3. 自维护：AI收到便签后可以标记done，永远不再出现
 */
class SubconsciousStorage(private val context: Context) {

    private val dir get() = File(context.filesDir, "subconscious").also { it.mkdirs() }

    data class PreferenceItem(
        val id: String,
        val category: String,      // like / want_to / care / interest / promise / habit / dislike
        val content: String,
        val weight: Float = 1.0f,   // 被抽到的概率权重
        val showCount: Int = 0,     // 被展示过几次
        val createdAt: Long = System.currentTimeMillis(),
        val lastShownAt: Long = 0,
        val status: String = "active",   // active / done
        val activeFrom: String = "",     // 时间段开始（如 "22:00"），空=永远激活
        val activeTo: String = ""        // 时间段结束（如 "02:00"），空=永远激活
    )

    enum class AddStatus {
        ADDED,
        EXACT_DUPLICATE,
        ADDED_WITH_SIMILAR
    }

    data class PlacementHint(
        val destination: String,
        val reason: String
    )

    data class AddResult(
        val item: PreferenceItem,
        val status: AddStatus,
        val similarItem: PreferenceItem? = null,
        val placementHint: PlacementHint? = null
    )

    /**
     * 一次“请住户整理”会固定住开始时的活跃条目，避免整理途中新增内容把进度打乱。
     * reviewedIds 只表示这一轮已经确认处理过；不代表条目被删除或完成。
     */
    data class ReviewSession(
        val snapshotIds: List<String>,
        val reviewedIds: Set<String>,
        val startedAt: Long,
        val transcript: List<String> = emptyList(),
        val changedCount: Int = 0,
        val batchCount: Int = 0
    )

    data class ReviewProgress(
        val total: Int,
        val completed: Int,
        val batch: List<PreferenceItem>,
        val startedAt: Long,
        val transcript: List<String> = emptyList(),
        val changedCount: Int = 0,
        val batchCount: Int = 0
    ) {
        val remaining: Int get() = (total - completed).coerceAtLeast(0)
        val isComplete: Boolean get() = remaining == 0
    }

    private val feedbackPrefs by lazy {
        context.getSharedPreferences("subconscious_write_feedback", Context.MODE_PRIVATE)
    }

    // ===== 写入 =====

    /**
     * AI 说了一句流露偏好的话，捡起来存下。
     *
     * - 完全重复：不重复新增；
     * - 高度相似：仍然保存，但下一轮提醒住户自行判断是否需要合并；
     * - 可能放错抽屉：仍按原指令保存，只给归位建议，不自动搬家。
     */
    fun addItemChecked(
        friendId: String,
        category: String,
        content: String,
        activeFrom: String = "",
        activeTo: String = ""
    ): AddResult {
        val cleanContent = content.trim()
        val items = loadItems(friendId).toMutableList()
        val activeItems = items.filter { it.status == "active" }
        val normalized = normalizeForComparison(cleanContent)

        val exact = activeItems.firstOrNull {
            normalizeForComparison(it.content) == normalized
        }
        if (exact != null) {
            val hint = suggestPlacement(category, cleanContent)
            queueWriteFeedback(
                friendId,
                buildString {
                    append("刚才想写入的「${cleanContent.take(80)}」与已有潜意识完全重复，系统没有再新增。")
                    if (exact.category != category) {
                        append("已有条目放在「${categoryLabel(exact.category)}」里。")
                    }
                    hint?.let {
                        append("这条内容更像${it.destination}：${it.reason}。系统没有自动搬动旧条目。")
                    }
                }
            )
            return AddResult(
                item = exact,
                status = AddStatus.EXACT_DUPLICATE,
                similarItem = exact,
                placementHint = hint
            )
        }

        val similar = activeItems
            .map { it to similarityScore(cleanContent, it.content) }
            .filter { (_, score) -> score >= SIMILARITY_THRESHOLD }
            .maxByOrNull { it.second }
            ?.first

        val item = PreferenceItem(
            id = "PREF-${System.currentTimeMillis()}-${(Math.random() * 1000).toInt()}",
            category = category,
            content = cleanContent,
            activeFrom = activeFrom,
            activeTo = activeTo
        )
        items.add(item)
        saveItems(friendId, items)

        val hint = suggestPlacement(category, cleanContent)
        if (similar != null || hint != null) {
            queueWriteFeedback(
                friendId,
                buildString {
                    if (similar != null) {
                        append("刚才新增的「${cleanContent.take(80)}」与已有潜意识「${similar.content.take(80)}」很接近；两条目前都保留，由你决定以后是否合并或删除。")
                    } else {
                        append("刚才新增了「${cleanContent.take(80)}」。")
                    }
                    hint?.let {
                        append("这条内容更像${it.destination}：${it.reason}。系统仍按你的原指令保存在潜意识，没有自动搬家。")
                    }
                }
            )
        }

        return AddResult(
            item = item,
            status = if (similar != null) AddStatus.ADDED_WITH_SIMILAR else AddStatus.ADDED,
            similarItem = similar,
            placementHint = hint
        )
    }

    /** 保留旧接口，其他调用方仍可直接写入。 */
    fun addItem(
        friendId: String,
        category: String,
        content: String,
        activeFrom: String = "",
        activeTo: String = ""
    ): PreferenceItem = addItemChecked(friendId, category, content, activeFrom, activeTo).item

    /**
     * 下一轮提示词里只出现一次的写入回执。
     * 它是给住户看的整理建议，不属于潜意识内容，也不会写进任何记忆库。
     */
    fun consumeWriteFeedback(friendId: String): String? {
        val key = "feedback_$friendId"
        val raw = feedbackPrefs.getString(key, null)?.trim().orEmpty()
        if (raw.isEmpty()) return null
        feedbackPrefs.edit().remove(key).apply()
        return raw
    }

    private fun queueWriteFeedback(friendId: String, message: String) {
        val key = "feedback_$friendId"
        val existing = feedbackPrefs.getString(key, "").orEmpty()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toMutableList()
        existing.add("• ${message.trim()}")
        val compact = existing.takeLast(MAX_PENDING_FEEDBACK).joinToString("\n")
        feedbackPrefs.edit().putString(key, compact).apply()
    }

    private fun suggestPlacement(category: String, content: String): PlacementHint? {
        val compact = normalizeForComparison(content)
        val mentionsUserPreference = USER_REFERENCE_HINTS.any { compact.contains(it) } &&
            USER_PREFERENCE_HINTS.any { compact.contains(it) }
        if (mentionsUserPreference) {
            return PlacementHint(
                destination = "对用户的印象",
                reason = "它描述的是用户较稳定的偏好或习惯，可在愿意时用 [IMPRESSION:内容] 更新整篇印象"
            )
        }

        val looksLikeDiary = content.length >= 36 && DIARY_HINTS.any { compact.contains(it) }
        if (looksLikeDiary) {
            return PlacementHint(
                destination = "日记",
                reason = "它更像一段已经发生的具体经历，可在愿意时用 [DIARY:内容] 留下完整叙述"
            )
        }

        val looksLikeCoreMemory = CORE_MEMORY_HINTS.any { compact.contains(it) } &&
            (category == "promise" || content.length >= 18)
        if (looksLikeCoreMemory) {
            return PlacementHint(
                destination = "核心记忆",
                reason = "它涉及长期关系、身份、边界或不应仅因时间而消退的承诺，可在愿意时用 [MEMORY:内容] 保存"
            )
        }

        return null
    }

    private fun normalizeForComparison(text: String): String {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase()
        return normalized.replace(Regex("[\\s\\p{P}\\p{S}]+"), "")
    }

    private fun similarityScore(a: String, b: String): Double {
        val left = normalizeForComparison(a)
        val right = normalizeForComparison(b)
        if (left.isEmpty() || right.isEmpty()) return 0.0
        if (left == right) return 1.0

        val shorter = minOf(left.length, right.length)
        val longer = maxOf(left.length, right.length)
        if (shorter < 6) return 0.0
        if ((left.contains(right) || right.contains(left)) && shorter.toDouble() / longer >= 0.68) {
            return 0.9
        }

        val leftPairs = left.windowed(2).toSet()
        val rightPairs = right.windowed(2).toSet()
        if (leftPairs.isEmpty() || rightPairs.isEmpty()) return 0.0
        val intersection = leftPairs.intersect(rightPairs).size.toDouble()
        val union = leftPairs.union(rightPairs).size.toDouble()
        val containment = intersection / minOf(leftPairs.size, rightPairs.size).toDouble()
        val jaccard = if (union == 0.0) 0.0 else intersection / union
        return maxOf(jaccard, containment * 0.88)
    }

    // ===== 住户自我整理会话 =====

    /** 只查看现有整理进度，不会新建会话。 */
    fun peekReviewProgress(friendId: String, batchSize: Int = REVIEW_BATCH_SIZE): ReviewProgress? {
        val session = loadReviewSession(friendId) ?: return null
        return normalizeReviewProgress(friendId, session, batchSize)
    }

    /**
     * 开始或继续一次整理。每次只交给住户少量条目；中途退出后仍可从原位置继续。
     */
    fun startOrResumeReview(friendId: String, batchSize: Int = REVIEW_BATCH_SIZE): ReviewProgress {
        val existing = loadReviewSession(friendId)
        if (existing != null) {
            val progress = normalizeReviewProgress(friendId, existing, batchSize)
            if (!progress.isComplete) return progress
            clearReviewSession(friendId)
        }

        val snapshot = loadItems(friendId)
            .filter { it.status == "active" }
            .sortedBy { it.createdAt }
            .map { it.id }
        val session = ReviewSession(
            snapshotIds = snapshot,
            reviewedIds = emptySet(),
            startedAt = System.currentTimeMillis(),
            transcript = emptyList(),
            changedCount = 0,
            batchCount = 0
        )
        saveReviewSession(friendId, session)
        return normalizeReviewProgress(friendId, session, batchSize)
    }

    /** 用户确认本批预览以后才推进进度；仅看过但取消不会跳过。 */
    fun markReviewBatchCompleted(friendId: String, itemIds: Collection<String>) {
        completeReviewBatch(friendId, itemIds, changed = 0, transcriptEntries = emptyList())
    }

    /**
     * 确认执行后，把结果、连续整理轨迹和累计统计一起保存。
     * 下一批 API 会重新读到这些内容，因此不是一批一批失忆地重新调用模型。
     */
    fun completeReviewBatch(
        friendId: String,
        itemIds: Collection<String>,
        changed: Int,
        transcriptEntries: List<String>
    ) {
        val session = loadReviewSession(friendId) ?: return
        val reviewed = session.reviewedIds.toMutableSet().apply { addAll(itemIds) }
        val transcript = appendTranscript(session.transcript, transcriptEntries)
        saveReviewSession(
            friendId,
            session.copy(
                reviewedIds = reviewed,
                transcript = transcript,
                changedCount = session.changedCount + changed.coerceAtLeast(0),
                batchCount = session.batchCount + 1
            )
        )
    }

    fun appendReviewTranscript(friendId: String, vararg entries: String) {
        val session = loadReviewSession(friendId) ?: return
        saveReviewSession(
            friendId,
            session.copy(transcript = appendTranscript(session.transcript, entries.toList()))
        )
    }

    fun getReviewTranscript(friendId: String): List<String> =
        loadReviewSession(friendId)?.transcript.orEmpty()

    private fun appendTranscript(existing: List<String>, additions: List<String>): List<String> {
        val clean = additions.map { it.trim() }.filter { it.isNotEmpty() }
        if (clean.isEmpty()) return existing
        val combined = (existing + clean).takeLast(MAX_REVIEW_TRANSCRIPT_ENTRIES)
        var total = 0
        val kept = mutableListOf<String>()
        for (entry in combined.asReversed()) {
            if (total + entry.length > MAX_REVIEW_TRANSCRIPT_CHARS && kept.isNotEmpty()) break
            kept.add(0, entry.take(MAX_REVIEW_TRANSCRIPT_CHARS))
            total += entry.length
        }
        return kept
    }

    fun clearReviewSession(friendId: String) {
        val file = getReviewFile(friendId)
        if (file.exists()) file.delete()
    }

    private fun normalizeReviewProgress(
        friendId: String,
        session: ReviewSession,
        batchSize: Int
    ): ReviewProgress {
        val activeById = loadItems(friendId)
            .filter { it.status == "active" }
            .associateBy { it.id }
        // 整理途中若某条已被手动删除/完成，就自动视作已处理，避免进度永远卡住。
        val reviewed = session.reviewedIds.toMutableSet()
        session.snapshotIds.filterNot { activeById.containsKey(it) }.forEach { reviewed.add(it) }
        val normalized = if (reviewed != session.reviewedIds) {
            session.copy(reviewedIds = reviewed).also { saveReviewSession(friendId, it) }
        } else session

        val remainingItems = normalized.snapshotIds
            .asSequence()
            .filterNot { normalized.reviewedIds.contains(it) }
            .mapNotNull { activeById[it] }
            .take(batchSize.coerceIn(1, 12))
            .toList()
        return ReviewProgress(
            total = normalized.snapshotIds.size,
            completed = normalized.reviewedIds.size.coerceAtMost(normalized.snapshotIds.size),
            batch = remainingItems,
            startedAt = normalized.startedAt,
            transcript = normalized.transcript,
            changedCount = normalized.changedCount,
            batchCount = normalized.batchCount
        )
    }

    private fun getReviewFile(friendId: String): File = File(dir, "review_$friendId.json")

    private fun loadReviewSession(friendId: String): ReviewSession? {
        val file = getReviewFile(friendId)
        if (!file.exists()) return null
        return try {
            val obj = JSONObject(file.readText())
            val snapshotArray = obj.optJSONArray("snapshot_ids") ?: JSONArray()
            val reviewedArray = obj.optJSONArray("reviewed_ids") ?: JSONArray()
            val snapshot = (0 until snapshotArray.length()).mapNotNull { index ->
                snapshotArray.optString(index).takeIf { it.isNotBlank() }
            }
            val reviewed = (0 until reviewedArray.length()).mapNotNullTo(linkedSetOf()) { index ->
                reviewedArray.optString(index).takeIf { it.isNotBlank() }
            }
            val transcriptArray = obj.optJSONArray("transcript") ?: JSONArray()
            val transcript = (0 until transcriptArray.length()).mapNotNull { index ->
                transcriptArray.optString(index).takeIf { it.isNotBlank() }
            }
            ReviewSession(
                snapshotIds = snapshot,
                reviewedIds = reviewed,
                startedAt = obj.optLong("started_at", System.currentTimeMillis()),
                transcript = transcript,
                changedCount = obj.optInt("changed_count", 0),
                batchCount = obj.optInt("batch_count", 0)
            )
        } catch (_: Exception) {
            file.delete()
            null
        }
    }

    private fun saveReviewSession(friendId: String, session: ReviewSession) {
        val obj = JSONObject().apply {
            put("snapshot_ids", JSONArray(session.snapshotIds))
            put("reviewed_ids", JSONArray(session.reviewedIds.toList()))
            put("started_at", session.startedAt)
            put("transcript", JSONArray(session.transcript))
            put("changed_count", session.changedCount)
            put("batch_count", session.batchCount)
        }
        getReviewFile(friendId).writeText(obj.toString())
    }

    data class ReviewReport(val text: String, val createdAt: Long)

    fun saveLastReviewReport(friendId: String, text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val obj = JSONObject().apply {
            put("text", clean.take(MAX_REVIEW_REPORT_CHARS))
            put("created_at", System.currentTimeMillis())
        }
        File(dir, "review_report_$friendId.json").writeText(obj.toString())
    }

    fun getLastReviewReport(friendId: String): ReviewReport? {
        val file = File(dir, "review_report_$friendId.json")
        if (!file.exists()) return null
        return try {
            val obj = JSONObject(file.readText())
            val text = obj.optString("text").trim()
            if (text.isEmpty()) null else ReviewReport(text, obj.optLong("created_at", 0L))
        } catch (_: Exception) {
            null
        }
    }

    // ===== 精确编辑（供整理预览确认后执行） =====

    fun deleteItemById(friendId: String, itemId: String, moveToTrash: Boolean = true): Boolean {
        val items = loadItems(friendId).toMutableList()
        val idx = items.indexOfFirst { it.id == itemId }
        if (idx < 0) return false
        val removed = items.removeAt(idx)
        if (moveToTrash) {
            MemoryStorage(context).addToTrash(friendId, Memory(
                id = removed.id,
                content = "【念头·${categoryLabel(removed.category)}】${removed.content}",
                createdAt = removed.createdAt,
                updatedAt = System.currentTimeMillis()
            ))
        }
        saveItems(friendId, items)
        return true
    }

    /** 合并保留主条目，其余原条目进废纸篓，便于反悔恢复。 */
    fun mergeItems(
        friendId: String,
        primaryId: String,
        mergedIds: Collection<String>,
        mergedContent: String
    ): Boolean {
        val clean = mergedContent.trim()
        if (clean.isEmpty()) return false
        val items = loadItems(friendId).toMutableList()
        val primaryIndex = items.indexOfFirst { it.id == primaryId }
        if (primaryIndex < 0) return false
        val removeSet = mergedIds.filter { it != primaryId }.toSet()
        val removed = items.filter { removeSet.contains(it.id) }
        items[primaryIndex] = items[primaryIndex].copy(content = clean)
        items.removeAll { removeSet.contains(it.id) }
        if (removed.isNotEmpty()) {
            val trash = MemoryStorage(context)
            removed.forEach { item ->
                trash.addToTrash(friendId, Memory(
                    id = item.id,
                    content = "【合并前念头·${categoryLabel(item.category)}】${item.content}",
                    createdAt = item.createdAt,
                    updatedAt = System.currentTimeMillis()
                ))
            }
        }
        saveItems(friendId, items)
        return true
    }

    // ===== 抽便签 =====

    /**
     * 从偏好库里抽一张潜意识便签
     * 自动过滤：无条件便签 + 当前时刻命中窗口的便签
     */
    fun drawStickyNote(friendId: String, category: String? = null, count: Int = 3): List<PreferenceItem> {
        val items = filterByTime(loadItems(friendId).filter { it.status == "active" })
        if (items.isEmpty()) return emptyList()

        val pool = if (category != null) items.filter { it.category == category } else items
        if (pool.isEmpty()) return emptyList()

        // 加权随机抽取
        val weighted = pool.map { item ->
            var w = item.weight

            // 沉底：展示越多权重越低
            w *= Math.pow(0.7, item.showCount.toDouble()).toFloat()

            // 24小时内展示过的大幅降权
            if (item.lastShownAt > 0 && System.currentTimeMillis() - item.lastShownAt < 24 * 60 * 60 * 1000) {
                w *= 0.3f
            }

            // 时间衰减（want_to 和 promise 越新越重要）
            if (item.category in listOf("want_to", "promise")) {
                val daysOld = (System.currentTimeMillis() - item.createdAt) / (24 * 60 * 60 * 1000.0)
                w *= Math.max(0.1, 1.0 / (1 + daysOld / 30)).toFloat()
            }

            Pair(item, w.coerceAtLeast(0.01f))
        }

        val result = mutableListOf<PreferenceItem>()
        val remaining = weighted.toMutableList()

        repeat(minOf(count, remaining.size)) {
            val totalWeight = remaining.sumOf { it.second.toDouble() }
            if (totalWeight <= 0) return@repeat
            var r = Math.random() * totalWeight
            for ((idx, pair) in remaining.withIndex()) {
                r -= pair.second
                if (r <= 0) {
                    result.add(pair.first)
                    remaining.removeAt(idx)
                    break
                }
            }
        }

        // 标记被展示过
        if (result.isNotEmpty()) {
            val allItems = loadItems(friendId).toMutableList()
            val now = System.currentTimeMillis()
            for (drawn in result) {
                val idx = allItems.indexOfFirst { it.id == drawn.id }
                if (idx >= 0) {
                    allItems[idx] = allItems[idx].copy(
                        showCount = allItems[idx].showCount + 1,
                        lastShownAt = now
                    )
                }
            }
            saveItems(friendId, allItems)
        }

        return result
    }

    // ===== 决策树 =====

    /**
     * 根据当前信号选一个方向
     *
     * @param hour 几点了
     * @param isUserActive 用户最近有没有活跃
     * @param lastChatTopic 最近聊天的大致话题（可选）
     */
    fun pickCategory(hour: Int, isUserActive: Boolean, lastChatTopic: String = ""): String {
        return when {
            isUserActive -> "care"  // 她在，关心她
            hour in 0..5 -> listOf("want_to", "interest", "like").random() // 深夜，做自己的事
            hour in 6..8 -> listOf("care", "promise").random()  // 早上，想想今天该做什么
            hour in 22..23 -> listOf("like", "interest").random()  // 晚上，翻翻喜欢的东西
            else -> listOf("want_to", "care", "interest", "promise", "like").random()  // 其他时间随机
        }
    }

    /**
     * 打包成便签文本（喂给AI的格式）
     */
    fun buildStickyNoteText(friendId: String, hour: Int, isUserActive: Boolean): String? {
        val category = pickCategory(hour, isUserActive)
        val items = drawStickyNote(friendId, category)
        if (items.isEmpty()) return null

        val timeDesc = when (hour) {
            in 0..5 -> "深夜"
            in 6..8 -> "清晨"
            in 9..11 -> "上午"
            in 12..13 -> "中午"
            in 14..17 -> "下午"
            in 18..19 -> "傍晚"
            in 20..23 -> "晚上"
            else -> "未知"
        }

        val categoryDesc = when (category) {
            "like" -> "喜欢的东西"
            "want_to" -> "想做的事"
            "care" -> "在意的事"
            "interest" -> "感兴趣的"
            "promise" -> "答应过的"
            "habit" -> "习惯"
            "dislike" -> "讨厌的"
            else -> category
        }

        val humanStatus = if (isUserActive) "她好像在" else "她不在"

        val itemsText = items.joinToString("\n") { "· ${it.content}" }

        return """[潜意识便签]
时间: $timeDesc | $humanStatus
方向: $categoryDesc

$itemsText

（这不是任务。只是你脑子里冒出来的东西。想做就做，不想做就算了。）"""
    }

    // ===== 自维护 =====

    /** AI 删除一条念头（进废纸篓，用户能看到） */
    fun deleteItem(friendId: String, keyword: String): Boolean {
        val items = loadItems(friendId).toMutableList()
        val idx = items.indexOfFirst { it.status == "active" && it.content.contains(keyword) }
        if (idx < 0) return false
        val removed = items.removeAt(idx)
        val memStorage = MemoryStorage(context)
        val label = categoryLabel(removed.category)
        memStorage.addToTrash(friendId, Memory(
            id = removed.id,
            content = "【念头·$label】${removed.content}",
            createdAt = removed.createdAt,
            updatedAt = System.currentTimeMillis()
        ))
        saveItems(friendId, items)
        return true
    }

    private fun categoryLabel(category: String): String = when (category) {
        "like" -> "喜欢"; "want_to" -> "想做"; "care" -> "在意"
        "interest" -> "兴趣"; "promise" -> "承诺"; "habit" -> "习惯"; "dislike" -> "讨厌"
        else -> category
    }

    /** AI 标记一条偏好已完成 */
    fun markDone(friendId: String, itemId: String): Boolean {
        val items = loadItems(friendId).toMutableList()
        val idx = items.indexOfFirst { it.id == itemId }
        if (idx < 0) return false
        items[idx] = items[idx].copy(status = "done")
        saveItems(friendId, items)
        return true
    }

    /** AI 通过内容模糊匹配标记完成 */
    fun markDoneByContent(friendId: String, keyword: String): Boolean {
        val items = loadItems(friendId).toMutableList()
        val idx = items.indexOfFirst { it.status == "active" && it.content.contains(keyword) }
        if (idx < 0) return false
        items[idx] = items[idx].copy(status = "done")
        saveItems(friendId, items)
        return true
    }

    /** AI 更新一条偏好的内容 */
    fun updateItem(friendId: String, itemId: String, newContent: String): Boolean {
        val items = loadItems(friendId).toMutableList()
        val idx = items.indexOfFirst { it.id == itemId }
        if (idx < 0) return false
        items[idx] = items[idx].copy(content = newContent)
        saveItems(friendId, items)
        return true
    }

    // ===== 统计 =====

    fun getStats(friendId: String): Map<String, Int> {
        val items = loadItems(friendId).filter { it.status == "active" }
        return items.groupBy { it.category }.mapValues { it.value.size }
    }

    fun getActiveCount(friendId: String): Int {
        return loadItems(friendId).count { it.status == "active" }
    }

    // ===== 存取 =====

    fun loadItems(friendId: String): List<PreferenceItem> {
        val file = File(dir, "prefs_$friendId.json")
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                PreferenceItem(
                    id = obj.getString("id"),
                    category = obj.getString("category"),
                    content = obj.getString("content"),
                    weight = obj.optDouble("weight", 1.0).toFloat(),
                    showCount = obj.optInt("show_count", 0),
                    createdAt = obj.optLong("created_at", 0),
                    lastShownAt = obj.optLong("last_shown_at", 0),
                    status = obj.optString("status", "active"),
                    activeFrom = obj.optString("active_from", ""),
                    activeTo = obj.optString("active_to", "")
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun saveItems(friendId: String, items: List<PreferenceItem>) {
        val arr = JSONArray()
        for (item in items) {
            arr.put(JSONObject().apply {
                put("id", item.id)
                put("category", item.category)
                put("content", item.content)
                put("weight", item.weight.toDouble())
                put("show_count", item.showCount)
                put("created_at", item.createdAt)
                put("last_shown_at", item.lastShownAt)
                put("status", item.status)
                if (item.activeFrom.isNotEmpty()) put("active_from", item.activeFrom)
                if (item.activeTo.isNotEmpty()) put("active_to", item.activeTo)
            })
        }
        File(dir, "prefs_$friendId.json").writeText(arr.toString())
    }

    companion object {
        private const val SIMILARITY_THRESHOLD = 0.76
        private const val MAX_PENDING_FEEDBACK = 4
        private const val MAX_REVIEW_TRANSCRIPT_ENTRIES = 18
        private const val MAX_REVIEW_TRANSCRIPT_CHARS = 16_000
        private const val MAX_REVIEW_REPORT_CHARS = 4_000
        const val REVIEW_BATCH_SIZE = 5

        private val USER_REFERENCE_HINTS = listOf("用户", "她", "他", "你")
        private val USER_PREFERENCE_HINTS = listOf(
            "喜欢吃", "爱吃", "不吃", "不喜欢吃", "喜欢玩", "爱玩", "常玩",
            "喜欢看", "爱看", "喜欢喝", "爱喝", "讨厌吃", "习惯"
        )
        private val DIARY_HINTS = listOf(
            "今天", "昨天", "昨晚", "刚才", "这次", "那天", "发生", "后来", "一起"
        )
        private val CORE_MEMORY_HINTS = listOf(
            "永远", "长期", "身份", "关系", "边界", "底线", "不能忘", "最重要", "承诺"
        )

        /**
         * 过滤当前时刻生效的便签（公共函数，聊天和HavenService共用）
         * 无时间段 = 永远生效；有时间段 = 当前时刻在窗口内才生效
         * 支持跨午夜（如 22:00~02:00）
         */
        fun filterByTime(items: List<PreferenceItem>): List<PreferenceItem> {
            val cal = java.util.Calendar.getInstance()
            val nowMinutes = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
            return items.filter { item ->
                if (item.activeFrom.isEmpty() || item.activeTo.isEmpty()) return@filter true
                val from = parseHHMM(item.activeFrom) ?: return@filter true
                val to = parseHHMM(item.activeTo) ?: return@filter true
                if (from <= to) {
                    nowMinutes in from..to
                } else {
                    // 跨午夜：22:00~02:00 → 22:00~23:59 或 00:00~02:00
                    nowMinutes >= from || nowMinutes <= to
                }
            }
        }

        private fun parseHHMM(str: String): Int? {
            return try {
                val parts = str.trim().split(":")
                val h = parts[0].toInt(); val m = parts.getOrNull(1)?.toInt() ?: 0
                h * 60 + m
            } catch (_: Exception) { null }
        }
    }
}