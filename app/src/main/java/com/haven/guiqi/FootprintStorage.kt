package com.haven.guiqi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * FootprintStorage - 足迹（动态）的本地存储
 *
 * 所有足迹存在一个 footprints.json 文件里
 * 每条足迹可以有评论
 */
class FootprintStorage(private val context: Context) {

    private val file: File
        get() = File(context.filesDir, "footprints.json")

    // ===== 读取所有足迹（最新在前） =====
    fun loadFootprints(): List<Footprint> {
        if (!file.exists()) return emptyList()

        return try {
            val json = JSONObject(file.readText())
            val array = json.getJSONArray("footprints")
            val list = mutableListOf<Footprint>()

            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)

                // 读取评论
                val commentsArray = obj.optJSONArray("comments") ?: JSONArray()
                val comments = mutableListOf<FootprintComment>()
                for (j in 0 until commentsArray.length()) {
                    val cObj = commentsArray.getJSONObject(j)
                    comments.add(FootprintComment(
                        authorId = cObj.optString("author_id", "user"),
                        authorName = cObj.optString("author_name", ""),
                        content = cObj.optString("content", ""),
                        timestamp = cObj.optLong("timestamp", 0L)
                    ))
                }

                list.add(Footprint(
                    id = obj.getString("id"),
                    authorId = obj.optString("author_id", "user"),
                    authorName = obj.optString("author_name", ""),
                    content = obj.optString("content", ""),
                    imagePath = obj.optString("image_path", ""),
                    timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                    comments = comments
                ))
            }

            // 按时间倒序（最新的在前面）
            list.sortedByDescending { it.timestamp }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ===== 保存所有足迹 =====
    fun saveFootprints(footprints: List<Footprint>) {
        val array = JSONArray()
        for (fp in footprints) {
            val commentsArray = JSONArray()
            for (c in fp.comments) {
                commentsArray.put(JSONObject().apply {
                    put("author_id", c.authorId)
                    put("author_name", c.authorName)
                    put("content", c.content)
                    put("timestamp", c.timestamp)
                })
            }

            array.put(JSONObject().apply {
                put("id", fp.id)
                put("author_id", fp.authorId)
                put("author_name", fp.authorName)
                put("content", fp.content)
                put("image_path", fp.imagePath)
                put("timestamp", fp.timestamp)
                put("comments", commentsArray)
            })
        }

        val json = JSONObject().apply {
            put("footprints", array)
        }
        file.writeText(json.toString())
    }

    // ===== 发布新足迹 =====
    fun addFootprint(authorId: String, authorName: String, content: String, imagePath: String = ""): Footprint {
        val footprints = loadFootprints().toMutableList()
        val fp = Footprint(
            id = "FP-${System.currentTimeMillis()}",
            authorId = authorId,
            authorName = authorName,
            content = content,
            imagePath = imagePath,
            timestamp = System.currentTimeMillis()
        )
        footprints.add(fp)
        saveFootprints(footprints)
        return fp
    }

    // ===== 给足迹添加评论 =====
    fun addComment(footprintId: String, authorId: String, authorName: String, content: String) {
        val footprints = loadFootprints().toMutableList()
        val fp = footprints.find { it.id == footprintId } ?: return
        val index = footprints.indexOf(fp)

        val newComments = fp.comments.toMutableList()
        newComments.add(FootprintComment(
            authorId = authorId,
            authorName = authorName,
            content = content,
            timestamp = System.currentTimeMillis()
        ))

        footprints[index] = fp.copy(comments = newComments)
        saveFootprints(footprints)
    }

    // ===== 删除足迹 =====
    fun deleteFootprint(footprintId: String) {
        val footprints = loadFootprints().toMutableList()
        footprints.removeAll { it.id == footprintId }
        saveFootprints(footprints)
    }
}

/**
 * 一条足迹（动态）
 */
data class Footprint(
    val id: String,
    val authorId: String,       // "user" 或好友的 id
    val authorName: String,     // 显示名称
    val content: String,        // 文字内容
    val imagePath: String = "", // 图片路径（可选）
    val timestamp: Long,
    val comments: List<FootprintComment> = emptyList()
)

/**
 * 足迹下面的一条评论
 */
data class FootprintComment(
    val authorId: String,
    val authorName: String,
    val content: String,
    val timestamp: Long
)