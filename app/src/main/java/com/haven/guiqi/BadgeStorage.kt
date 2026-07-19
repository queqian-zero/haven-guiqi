package com.haven.guiqi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * BadgeStorage — 亲密徽章（带解锁系统）
 *
 * 每枚徽章有解锁条件：
 * - 结构化条件（系统自动检测）：days>=100, dreams>=5, promises_done>=3
 * - 描述文字（AI 申请解锁，人类确认）
 * - 两种都没有 = 创建即解锁
 *
 * 未解锁：图片灰色模糊，能看到名字和条件
 * 已解锁：完整显示，记录解锁时间
 */
class BadgeStorage(private val context: Context) {

    private val dir: File
        get() = File(context.filesDir, "badges").also { if (!it.exists()) it.mkdirs() }

    data class Badge(
        val id: String,
        val name: String,
        val description: String = "",
        val imagePath: String = "",
        val createdBy: String = "",
        val createdAt: Long = System.currentTimeMillis(),
        val unlockCondition: String = "",   // 人看的解锁描述（"一起聊满100天"）
        val autoCondition: String = "",     // 系统检测的条件（"days>=100"）
        val unlockedAt: Long = -1,          // -1=已解锁(旧数据兼容), 0=锁着, >0=解锁时间
        val pendingUnlock: Boolean = false  // AI 申请了解锁，等人类确认
    ) {
        val isUnlocked: Boolean get() = unlockedAt != 0L
    }

    fun loadAll(friendId: String): List<Badge> {
        val file = File(dir, "$friendId.json")
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { parseBadge(arr.getJSONObject(it)) }
                .sortedBy { it.createdAt }
        } catch (_: Exception) { emptyList() }
    }

    /** 创建徽章（有条件的默认锁着，没条件的直接解锁） */
    fun add(friendId: String, badge: Badge) {
        val actual = if (badge.autoCondition.isEmpty() && badge.unlockCondition.isEmpty() && badge.unlockedAt == -1L) {
            badge.copy(unlockedAt = badge.createdAt)  // 没条件，直接解锁
        } else if (badge.unlockedAt == -1L) {
            badge.copy(unlockedAt = 0)  // 有条件，锁着
        } else badge
        val list = loadAll(friendId).toMutableList()
        list.add(actual)
        save(friendId, list)
    }

    fun rename(friendId: String, badgeId: String, newName: String) {
        val list = loadAll(friendId).map {
            if (it.id == badgeId) it.copy(name = newName) else it
        }
        save(friendId, list)
    }

    fun updateImage(friendId: String, badgeId: String, imagePath: String) {
        val list = loadAll(friendId).map {
            if (it.id == badgeId) it.copy(imagePath = imagePath) else it
        }
        save(friendId, list)
    }

    fun delete(friendId: String, badgeId: String) {
        val list = loadAll(friendId).filter { it.id != badgeId }
        save(friendId, list)
    }

    /** 解锁一枚徽章 */
    fun unlock(friendId: String, badgeId: String) {
        val list = loadAll(friendId).map {
            if (it.id == badgeId) it.copy(unlockedAt = System.currentTimeMillis(), pendingUnlock = false) else it
        }
        save(friendId, list)
    }

    /** AI 申请解锁（标记 pending，等人类确认） */
    fun requestUnlock(friendId: String, badgeName: String): Boolean {
        val list = loadAll(friendId).toMutableList()
        val idx = list.indexOfFirst { it.name == badgeName && !it.isUnlocked }
        if (idx < 0) return false
        list[idx] = list[idx].copy(pendingUnlock = true)
        save(friendId, list)
        return true
    }

    /** 获取等待确认的徽章 */
    fun getPending(friendId: String): List<Badge> =
        loadAll(friendId).filter { it.pendingUnlock && !it.isUnlocked }

    /** 拒绝解锁请求 */
    fun rejectUnlock(friendId: String, badgeId: String) {
        val list = loadAll(friendId).map {
            if (it.id == badgeId) it.copy(pendingUnlock = false) else it
        }
        save(friendId, list)
    }

    fun count(friendId: String): Int = loadAll(friendId).size
    fun unlockedCount(friendId: String): Int = loadAll(friendId).count { it.isUnlocked }
    fun lockedCount(friendId: String): Int = loadAll(friendId).count { !it.isUnlocked }

    // ===== 系统自动检测解锁 =====

    fun checkAutoUnlocks(friendId: String): List<Badge> {
        val badges = loadAll(friendId)
        val unlocked = mutableListOf<Badge>()
        val updated = badges.map { badge ->
            if (badge.isUnlocked || badge.autoCondition.isEmpty()) return@map badge
            if (evaluateCondition(badge.autoCondition, friendId)) {
                unlocked.add(badge)
                badge.copy(unlockedAt = System.currentTimeMillis())
            } else badge
        }
        if (unlocked.isNotEmpty()) save(friendId, updated)
        return unlocked
    }

    private fun evaluateCondition(condition: String, friendId: String): Boolean {
        // 格式：key>=N 或 key>N
        val match = Regex("(\\w+)\\s*(>=|>)\\s*(\\d+)").find(condition) ?: return false
        val key = match.groupValues[1]
        val op = match.groupValues[2]
        val target = match.groupValues[3].toIntOrNull() ?: return false

        val actual = when (key) {
            "days" -> {
                // 优先用第一条消息的时间（"相遇那天"），没有聊天记录再退回好友创建时间
                val firstMsg = ChatStorage(context).getFirstTimestamp(friendId)
                val created = FriendStorage(context).loadFriends().find { it.id == friendId }?.createdAt ?: 0L
                val earliest = if (firstMsg > 0) firstMsg else created
                if (earliest > 0) ((System.currentTimeMillis() - earliest) / 86400000).toInt() else 0
            }
            "messages" -> ChatStorage(context).getMessageCount(friendId)
            "dreams" -> DreamStorage(context).loadDreams(friendId).size
            "diaries" -> DiaryStorage(context).loadDiaries(friendId).size
            "promises_done" -> SubconsciousStorage(context).loadItems(friendId)
                .count { it.category == "promise" && it.status == "done" }
            "capsules" -> CapsuleStorage(context).count(friendId)
            "badges_unlocked" -> unlockedCount(friendId)
            else -> 0
        }

        return when (op) {
            ">=" -> actual >= target
            ">" -> actual > target
            else -> false
        }
    }

    // ===== 内部 =====

    private fun save(friendId: String, list: List<Badge>) {
        val arr = JSONArray()
        for (b in list) arr.put(JSONObject().apply {
            put("id", b.id)
            put("name", b.name)
            put("description", b.description)
            put("image_path", b.imagePath)
            put("created_by", b.createdBy)
            put("created_at", b.createdAt)
            put("unlock_condition", b.unlockCondition)
            put("auto_condition", b.autoCondition)
            put("unlocked_at", b.unlockedAt)
            put("pending_unlock", b.pendingUnlock)
        })
        File(dir, "$friendId.json").writeText(arr.toString())
    }

    private fun parseBadge(obj: JSONObject) = Badge(
        id = obj.getString("id"),
        name = obj.getString("name"),
        description = obj.optString("description", ""),
        imagePath = obj.optString("image_path", ""),
        createdBy = obj.optString("created_by", ""),
        createdAt = obj.optLong("created_at", 0),
        unlockCondition = obj.optString("unlock_condition", ""),
        autoCondition = obj.optString("auto_condition", ""),
        unlockedAt = obj.optLong("unlocked_at", -1),  // -1=旧数据，视为已解锁
        pendingUnlock = obj.optBoolean("pending_unlock", false)
    )
}