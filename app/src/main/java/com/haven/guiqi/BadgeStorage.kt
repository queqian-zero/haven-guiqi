package com.haven.guiqi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * BadgeStorage — 亲密徽章
 *
 * 空相框墙。没有预设，每一枚徽章都是人和 AI 一起挂上去的。
 * 人类上传图片+命名，AI 用指令创建或改名。
 * 数据存在 badges/{friendId}.json 里。
 */
class BadgeStorage(private val context: Context) {

    private val dir: File
        get() = File(context.filesDir, "badges").also { if (!it.exists()) it.mkdirs() }

    data class Badge(
        val id: String,
        val name: String,
        val description: String = "",
        val imagePath: String = "",   // 图片路径，空则显示名字首字
        val createdBy: String = "",   // "user" 或 friendId
        val createdAt: Long = System.currentTimeMillis()
    )

    fun loadAll(friendId: String): List<Badge> {
        val file = File(dir, "$friendId.json")
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { parseBadge(arr.getJSONObject(it)) }
                .sortedBy { it.createdAt }
        } catch (_: Exception) { emptyList() }
    }

    fun add(friendId: String, badge: Badge) {
        val list = loadAll(friendId).toMutableList()
        list.add(badge)
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

    fun count(friendId: String): Int = loadAll(friendId).size

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
        })
        File(dir, "$friendId.json").writeText(arr.toString())
    }

    private fun parseBadge(obj: JSONObject) = Badge(
        id = obj.getString("id"),
        name = obj.getString("name"),
        description = obj.optString("description", ""),
        imagePath = obj.optString("image_path", ""),
        createdBy = obj.optString("created_by", ""),
        createdAt = obj.optLong("created_at", 0)
    )
}