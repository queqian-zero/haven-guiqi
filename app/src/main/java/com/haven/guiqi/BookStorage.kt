package com.haven.guiqi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.*

/**
 * BookStorage — 书城数据管理
 *
 * 每本书有章节列表，按章节存储。
 * 书脊颜色随机分配，厚度和高度根据章节数变化。
 */
class BookStorage(private val context: Context) {

    private val dir get() = File(context.filesDir, "books").also { it.mkdirs() }

    data class Chapter(
        val title: String,
        val content: String
    )

    data class Book(
        val id: String,
        val title: String,
        val author: String = "",
        val chapters: List<Chapter>,
        val spineColor: Int,
        val lastChapter: Int = 0,     // 最后阅读的章节
        val lastPosition: Int = 0,    // 最后阅读位置（字符偏移）
        val createdAt: Long = System.currentTimeMillis()
    )

    /** 加载所有书（只读元数据，不读章节内容，用于书架显示） */
    fun loadBooksMeta(): List<Book> {
        val books = mutableListOf<Book>()
        val files = dir.listFiles { f -> f.extension == "json" } ?: return books
        for (file in files.sortedByDescending { it.lastModified() }) {
            try {
                val json = JSONObject(file.readText())
                val chaptersArr = json.optJSONArray("chapters") ?: JSONArray()
                // 只统计章节数，不读内容
                val emptyChapters = (0 until chaptersArr.length()).map {
                    Chapter(chaptersArr.getJSONObject(it).optString("title", ""), "")
                }
                books.add(Book(
                    id = json.getString("id"),
                    title = json.optString("title", "未命名"),
                    author = json.optString("author", ""),
                    chapters = emptyChapters,
                    spineColor = json.optInt("spine_color", 0xFF8B4513.toInt()),
                    lastChapter = json.optInt("last_chapter", 0),
                    lastPosition = json.optInt("last_position", 0),
                    createdAt = json.optLong("created_at", System.currentTimeMillis())
                ))
            } catch (_: Exception) { }
        }
        return books
    }

    /** 加载所有书（含完整内容） */
    fun loadBooks(): List<Book> {
        val books = mutableListOf<Book>()
        val files = dir.listFiles { f -> f.extension == "json" } ?: return books
        for (file in files.sortedByDescending { it.lastModified() }) {
            try {
                val json = JSONObject(file.readText())
                books.add(parseBook(json))
            } catch (_: Exception) { }
        }
        return books
    }

    /** 获取一本书 */
    fun getBook(bookId: String): Book? {
        val file = File(dir, "$bookId.json")
        if (!file.exists()) return null
        return try { parseBook(JSONObject(file.readText())) } catch (_: Exception) { null }
    }

    /** 保存一本书（新建或覆盖） */
    fun saveBook(book: Book) {
        val json = JSONObject().apply {
            put("id", book.id)
            put("title", book.title)
            put("author", book.author)
            put("spine_color", book.spineColor)
            put("last_chapter", book.lastChapter)
            put("last_position", book.lastPosition)
            put("created_at", book.createdAt)
            put("chapters", JSONArray().apply {
                for (ch in book.chapters) {
                    put(JSONObject().apply {
                        put("title", ch.title)
                        put("content", ch.content)
                    })
                }
            })
        }
        File(dir, "${book.id}.json").writeText(json.toString())
    }

    /** 更新阅读进度 */
    fun updateProgress(bookId: String, chapter: Int, position: Int) {
        val book = getBook(bookId) ?: return
        saveBook(book.copy(lastChapter = chapter, lastPosition = position))
    }

    /** 追加章节 */
    fun appendChapter(bookId: String, chapter: Chapter) {
        val book = getBook(bookId) ?: return
        saveBook(book.copy(chapters = book.chapters + chapter))
    }

    /** 删除一本书 */
    fun deleteBook(bookId: String) {
        File(dir, "$bookId.json").delete()
    }

    /** 导入单个 txt 文件，自动拆章节 */
    fun importTxt(title: String, content: String, author: String = ""): Book {
        val chapters = splitChapters(content)
        val book = Book(
            id = "BOOK-${System.currentTimeMillis()}",
            title = title,
            author = author,
            chapters = chapters,
            spineColor = randomSpineColor()
        )
        saveBook(book)
        return book
    }

    /** 导入多个 txt 文件合并成一本书 */
    fun importMultipleTxt(title: String, files: List<Pair<String, String>>, author: String = ""): Book {
        val chapters = files.map { (name, content) ->
            Chapter(name.removeSuffix(".txt"), content)
        }
        val book = Book(
            id = "BOOK-${System.currentTimeMillis()}",
            title = title,
            author = author,
            chapters = chapters,
            spineColor = randomSpineColor()
        )
        saveBook(book)
        return book
    }

    /** 尝试按"第X章"拆分章节 */
    private fun splitChapters(content: String): List<Chapter> {
        val pattern = Regex("(?=第[零一二三四五六七八九十百千\\d]+[章节回])")
        val parts = pattern.split(content).filter { it.trim().isNotEmpty() }

        if (parts.size <= 1) {
            // 没有章节标记，整个文件算一章
            return listOf(Chapter("全文", content.trim()))
        }

        return parts.mapIndexed { index, part ->
            val lines = part.trim().lines()
            val title = lines.firstOrNull()?.trim()?.take(30) ?: "第${index + 1}章"
            Chapter(title, part.trim())
        }
    }

    /** 随机书脊颜色——暖色调为主 */
    private fun randomSpineColor(): Int {
        val colors = intArrayOf(
            0xFF8B4513.toInt(), // 深棕
            0xFFA0522D.toInt(), // 赭石
            0xFF6B3A2A.toInt(), // 咖啡
            0xFF2F4F4F.toInt(), // 暗青
            0xFF483D8B.toInt(), // 暗紫
            0xFF556B2F.toInt(), // 暗绿
            0xFF8B0000.toInt(), // 暗红
            0xFF4A3728.toInt(), // 深褐
            0xFF2E4057.toInt(), // 藏蓝
            0xFF5D4037.toInt(), // 棕褐
            0xFF795548.toInt(), // 褐色
            0xFF4E342E.toInt()  // 深棕褐
        )
        return colors[Random().nextInt(colors.size)]
    }

    private fun parseBook(json: JSONObject): Book {
        val chaptersArr = json.optJSONArray("chapters") ?: JSONArray()
        val chapters = mutableListOf<Chapter>()
        for (i in 0 until chaptersArr.length()) {
            val ch = chaptersArr.getJSONObject(i)
            chapters.add(Chapter(ch.optString("title", ""), ch.optString("content", "")))
        }
        return Book(
            id = json.getString("id"),
            title = json.optString("title", "未命名"),
            author = json.optString("author", ""),
            chapters = chapters,
            spineColor = json.optInt("spine_color", 0xFF8B4513.toInt()),
            lastChapter = json.optInt("last_chapter", 0),
            lastPosition = json.optInt("last_position", 0),
            createdAt = json.optLong("created_at", System.currentTimeMillis())
        )
    }
}