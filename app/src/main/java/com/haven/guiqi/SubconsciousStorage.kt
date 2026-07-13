package com.haven.guiqi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

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
        val status: String = "active"   // active / done
    )

    // ===== 写入 =====

    /** AI 说了一句流露偏好的话，捡起来存下 */
    fun addItem(friendId: String, category: String, content: String): PreferenceItem {
        val items = loadItems(friendId).toMutableList()
        // 去重：内容太像的不重复存
        if (items.any { it.category == category && it.content == content && it.status == "active" }) {
            return items.first { it.category == category && it.content == content }
        }
        val item = PreferenceItem(
            id = "PREF-${System.currentTimeMillis()}-${(Math.random() * 1000).toInt()}",
            category = category,
            content = content
        )
        items.add(item)
        saveItems(friendId, items)
        return item
    }

    // ===== 抽便签 =====

    /**
     * 从偏好库里抽一张潜意识便签
     *
     * @param friendId AI 的 ID
     * @param category 如果决策树选了方向就传，null 表示随机
     * @param count 抽几条
     * @return 被抽中的条目列表
     */
    fun drawStickyNote(friendId: String, category: String? = null, count: Int = 3): List<PreferenceItem> {
        val items = loadItems(friendId).filter { it.status == "active" }
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
                    status = obj.optString("status", "active")
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
            })
        }
        File(dir, "prefs_$friendId.json").writeText(arr.toString())
    }
}