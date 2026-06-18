package com.haven.guiqi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * BookSocialStorage — 书城的"人"
 *
 * 管三件事：
 * 1. 在场状态 — 谁在图书馆，在看哪本书第几页
 * 2. 批注 — 谁在哪一页留了什么话
 * 3. 阅读进度 — 每个人各自读到哪了（不是全局进度）
 *
 * 跟 BookStorage 分开。BookStorage 管"书"，这个管"人和书的关系"。
 */
class BookSocialStorage(private val context: Context) {

    private val dir get() = File(context.filesDir, "book_social").also { it.mkdirs() }

    // ==================== 在场状态 ====================

    data class Presence(
        val readerId: String,     // 好友ID（"user" = 用户自己）
        val readerName: String,
        val bookId: String,
        val bookTitle: String,
        val chapter: Int,
        val timestamp: Long       // 最后活跃时间
    )

    /** 更新在场状态：某人打开了某本书 */
    fun setPresence(readerId: String, readerName: String, bookId: String, bookTitle: String, chapter: Int) {
        val all = loadPresences().toMutableList()
        all.removeAll { it.readerId == readerId }
        all.add(Presence(readerId, readerName, bookId, bookTitle, chapter, System.currentTimeMillis()))
        savePresences(all)
    }

    /** 更新章节（翻页时调用） */
    fun updatePresenceChapter(readerId: String, chapter: Int) {
        val all = loadPresences().toMutableList()
        val idx = all.indexOfFirst { it.readerId == readerId }
        if (idx >= 0) {
            all[idx] = all[idx].copy(chapter = chapter, timestamp = System.currentTimeMillis())
            savePresences(all)
        }
    }

    /** 离开图书馆 */
    fun clearPresence(readerId: String) {
        val all = loadPresences().toMutableList()
        all.removeAll { it.readerId == readerId }
        savePresences(all)
    }

    /** 获取所有在场的人（超过30分钟没活动的自动清掉） */
    fun getActivePresences(): List<Presence> {
        val all = loadPresences()
        val cutoff = System.currentTimeMillis() - 30 * 60 * 1000
        val active = all.filter { it.timestamp > cutoff }
        if (active.size != all.size) savePresences(active)
        return active
    }

    /** 某本书当前有谁在读 */
    fun getBookReaders(bookId: String): List<Presence> {
        return getActivePresences().filter { it.bookId == bookId }
    }

    private fun loadPresences(): List<Presence> {
        val file = File(dir, "presences.json")
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                Presence(
                    readerId = obj.getString("reader_id"),
                    readerName = obj.optString("reader_name", ""),
                    bookId = obj.getString("book_id"),
                    bookTitle = obj.optString("book_title", ""),
                    chapter = obj.optInt("chapter", 0),
                    timestamp = obj.optLong("timestamp", 0)
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun savePresences(list: List<Presence>) {
        val arr = JSONArray()
        for (p in list) {
            arr.put(JSONObject().apply {
                put("reader_id", p.readerId)
                put("reader_name", p.readerName)
                put("book_id", p.bookId)
                put("book_title", p.bookTitle)
                put("chapter", p.chapter)
                put("timestamp", p.timestamp)
            })
        }
        File(dir, "presences.json").writeText(arr.toString())
    }

    // ==================== 批注 ====================

    data class Annotation(
        val id: String,
        val bookId: String,
        val chapter: Int,
        val authorId: String,     // 谁写的
        val authorName: String,
        val content: String,      // 批注内容
        val paragraph: Int,       // 在第几段旁边（-1 = 页面级别的感想）
        val color: Int,           // 批注颜色（每人不同）
        val timestamp: Long
    )

    /** 添加批注 */
    fun addAnnotation(bookId: String, chapter: Int, authorId: String, authorName: String,
                      content: String, paragraph: Int = -1): Annotation {
        val annotations = loadAnnotations(bookId).toMutableList()
        val annotation = Annotation(
            id = "ANN-${System.currentTimeMillis()}",
            bookId = bookId,
            chapter = chapter,
            authorId = authorId,
            authorName = authorName,
            content = content,
            paragraph = paragraph,
            color = getReaderColor(authorId),
            timestamp = System.currentTimeMillis()
        )
        annotations.add(annotation)
        saveAnnotations(bookId, annotations)
        return annotation
    }

    /** 获取某本书某章的所有批注 */
    fun getChapterAnnotations(bookId: String, chapter: Int): List<Annotation> {
        return loadAnnotations(bookId).filter { it.chapter == chapter }
    }

    /** 删除批注 */
    fun deleteAnnotation(bookId: String, annotationId: String) {
        val annotations = loadAnnotations(bookId).toMutableList()
        annotations.removeAll { it.id == annotationId }
        saveAnnotations(bookId, annotations)
    }

    /** 获取某本书的所有批注数 */
    fun getAnnotationCount(bookId: String): Int = loadAnnotations(bookId).size

    private fun loadAnnotations(bookId: String): List<Annotation> {
        val file = File(dir, "annotations_$bookId.json")
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                Annotation(
                    id = obj.getString("id"),
                    bookId = obj.getString("book_id"),
                    chapter = obj.getInt("chapter"),
                    authorId = obj.getString("author_id"),
                    authorName = obj.optString("author_name", ""),
                    content = obj.getString("content"),
                    paragraph = obj.optInt("paragraph", -1),
                    color = obj.optInt("color", 0xFF888888.toInt()),
                    timestamp = obj.optLong("timestamp", 0)
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun saveAnnotations(bookId: String, list: List<Annotation>) {
        val arr = JSONArray()
        for (a in list) {
            arr.put(JSONObject().apply {
                put("id", a.id)
                put("book_id", a.bookId)
                put("chapter", a.chapter)
                put("author_id", a.authorId)
                put("author_name", a.authorName)
                put("content", a.content)
                put("paragraph", a.paragraph)
                put("color", a.color)
                put("timestamp", a.timestamp)
            })
        }
        File(dir, "annotations_$bookId.json").writeText(arr.toString())
    }

    // ==================== 阅读进度（每人独立） ====================

    data class ReaderProgress(
        val readerId: String,
        val chapter: Int,
        val position: Int,
        val timestamp: Long
    )

    /** 保存某人在某本书的阅读进度 */
    fun saveProgress(bookId: String, readerId: String, chapter: Int, position: Int = 0) {
        val all = loadAllProgress(bookId).toMutableMap()
        all[readerId] = ReaderProgress(readerId, chapter, position, System.currentTimeMillis())
        saveAllProgress(bookId, all)
    }

    /** 获取某人在某本书的阅读进度 */
    fun getProgress(bookId: String, readerId: String): ReaderProgress? {
        return loadAllProgress(bookId)[readerId]
    }

    /** 获取所有人在某本书的进度（用于显示"大家读到哪了"） */
    fun getAllProgress(bookId: String): Map<String, ReaderProgress> = loadAllProgress(bookId)

    private fun loadAllProgress(bookId: String): Map<String, ReaderProgress> {
        val file = File(dir, "progress_$bookId.json")
        if (!file.exists()) return emptyMap()
        return try {
            val obj = JSONObject(file.readText())
            val map = mutableMapOf<String, ReaderProgress>()
            for (key in obj.keys()) {
                val p = obj.getJSONObject(key)
                map[key] = ReaderProgress(
                    readerId = key,
                    chapter = p.getInt("chapter"),
                    position = p.optInt("position", 0),
                    timestamp = p.optLong("timestamp", 0)
                )
            }
            map
        } catch (_: Exception) { emptyMap() }
    }

    private fun saveAllProgress(bookId: String, map: Map<String, ReaderProgress>) {
        val obj = JSONObject()
        for ((key, p) in map) {
            obj.put(key, JSONObject().apply {
                put("chapter", p.chapter)
                put("position", p.position)
                put("timestamp", p.timestamp)
            })
        }
        File(dir, "progress_$bookId.json").writeText(obj.toString())
    }

    // ==================== 颜色分配 ====================

    /** 每个人一个固定颜色，用于批注和在场标识 */
    fun getReaderColor(readerId: String): Int {
        val colors = intArrayOf(
            0xFFE57373.toInt(), // 柔红（用户）
            0xFF81C784.toInt(), // 柔绿
            0xFF64B5F6.toInt(), // 柔蓝
            0xFFFFB74D.toInt(), // 柔橙
            0xFFBA68C8.toInt(), // 柔紫
            0xFF4DB6AC.toInt(), // 柔青
            0xFFF06292.toInt(), // 柔粉
            0xFFAED581.toInt()  // 柔黄绿
        )
        if (readerId == "user") return colors[0]
        val hash = readerId.hashCode().let { if (it < 0) -it else it }
        return colors[(hash % (colors.size - 1)) + 1]
    }
}