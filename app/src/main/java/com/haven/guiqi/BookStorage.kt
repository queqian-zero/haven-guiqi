package com.haven.guiqi

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.*

/**
 * BookStorage — 书城数据管理
 *
 * 每本书有章节列表，按章节存储。
 * 书脊颜色随机分配，厚度和高度根据章节数变化。
 *
 * 书架只需要书名、作者、章节数等轻量信息。为避免每次进入馆藏都解析整本小说，
 * 元数据会写入独立的小缓存；旧书第一次读取时用流式解析补建缓存，不把正文装进内存。
 */
class BookStorage(private val context: Context) {

    private val dir get() = File(context.filesDir, "books").also { it.mkdirs() }
    private val metaDir get() = File(context.filesDir, "books_meta").also { it.mkdirs() }

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
        val lastChapter: Int = 0,
        val lastPosition: Int = 0,
        val createdAt: Long = System.currentTimeMillis()
    )

    /** 馆藏主页只显示数量时使用，不再读取任何小说正文。 */
    fun countBooks(): Int {
        return dir.listFiles { file -> file.isFile && file.extension.equals("json", true) }?.size ?: 0
    }

    /** 加载所有书的轻量元数据，不读取章节正文。 */
    fun loadBooksMeta(): List<Book> {
        val files = dir.listFiles { file -> file.isFile && file.extension.equals("json", true) }
            ?: return emptyList()
        val books = mutableListOf<Book>()
        for (source in files.sortedByDescending { it.lastModified() }) {
            if (Thread.currentThread().isInterrupted) break
            val book = loadCachedMeta(source) ?: buildMetaFromSource(source)
            if (book != null) books += book
        }
        return books
    }

    /** 加载所有书（含完整内容）。 */
    fun loadBooks(): List<Book> {
        val books = mutableListOf<Book>()
        val files = dir.listFiles { file -> file.isFile && file.extension.equals("json", true) }
            ?: return books
        for (file in files.sortedByDescending { it.lastModified() }) {
            try {
                val json = JSONObject(file.readText())
                books.add(parseBook(json))
            } catch (_: Exception) {
            }
        }
        return books
    }

    /** 获取一本书。 */
    fun getBook(bookId: String): Book? {
        val file = File(dir, "$bookId.json")
        if (!file.exists()) return null
        return try {
            parseBook(JSONObject(file.readText()))
        } catch (_: Exception) {
            null
        }
    }

    /** 保存一本书（新建或覆盖），并同步写入轻量元数据。 */
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
                for (chapter in book.chapters) {
                    put(JSONObject().apply {
                        put("title", chapter.title)
                        put("content", chapter.content)
                    })
                }
            })
        }
        val source = File(dir, "${book.id}.json")
        source.writeText(json.toString())
        writeMetaCache(source, book.copy(chapters = emptyChapterList(book.chapters.size)))
    }

    /** 更新阅读进度。 */
    fun updateProgress(bookId: String, chapter: Int, position: Int) {
        val book = getBook(bookId) ?: return
        saveBook(book.copy(lastChapter = chapter, lastPosition = position))
    }

    /** 追加章节。 */
    fun appendChapter(bookId: String, chapter: Chapter) {
        val book = getBook(bookId) ?: return
        saveBook(book.copy(chapters = book.chapters + chapter))
    }

    /** 删除一本书，同时删除它的元数据缓存。 */
    fun deleteBook(bookId: String) {
        File(dir, "$bookId.json").delete()
        metaFile(bookId).delete()
    }

    /** 导入单个 txt 文件，自动拆章节。 */
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

    /** 导入多个 txt 文件合并成一本书。 */
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

    private fun loadCachedMeta(source: File): Book? {
        val cache = metaFile(source.nameWithoutExtension)
        if (!cache.isFile) return null
        return try {
            val json = JSONObject(cache.readText(Charsets.UTF_8))
            if (json.optLong("source_modified", -1L) != source.lastModified()) return null
            if (json.optLong("source_length", -1L) != source.length()) return null
            val chapterCount = json.optInt("chapter_count", 0).coerceAtLeast(0)
            Book(
                id = json.optString("id", source.nameWithoutExtension),
                title = json.optString("title", "未命名"),
                author = json.optString("author", ""),
                chapters = emptyChapterList(chapterCount),
                spineColor = json.optInt("spine_color", 0xFF8B4513.toInt()),
                lastChapter = json.optInt("last_chapter", 0),
                lastPosition = json.optInt("last_position", 0),
                createdAt = json.optLong("created_at", source.lastModified())
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 旧书没有元数据缓存时，流式扫描 JSON。
     * chapters 中的 content 会被 JsonReader.skipValue() 跳过，不会生成完整正文字符串。
     */
    private fun buildMetaFromSource(source: File): Book? {
        return try {
            var id = source.nameWithoutExtension
            var title = "未命名"
            var author = ""
            var spineColor = 0xFF8B4513.toInt()
            var lastChapter = 0
            var lastPosition = 0
            var createdAt = source.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
            var chapterCount = 0

            source.reader(Charsets.UTF_8).use { fileReader ->
                JsonReader(fileReader).use { reader ->
                    reader.isLenient = true
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "id" -> id = reader.nextStringOr(id)
                            "title" -> title = reader.nextStringOr(title)
                            "author" -> author = reader.nextStringOr(author)
                            "spine_color" -> spineColor = reader.nextIntOr(spineColor)
                            "last_chapter" -> lastChapter = reader.nextIntOr(lastChapter)
                            "last_position" -> lastPosition = reader.nextIntOr(lastPosition)
                            "created_at" -> createdAt = reader.nextLongOr(createdAt)
                            "chapters" -> {
                                if (reader.peek() == JsonToken.NULL) {
                                    reader.nextNull()
                                } else {
                                    reader.beginArray()
                                    while (reader.hasNext()) {
                                        reader.skipValue()
                                        chapterCount++
                                    }
                                    reader.endArray()
                                }
                            }
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                }
            }

            val book = Book(
                id = id,
                title = title,
                author = author,
                chapters = emptyChapterList(chapterCount),
                spineColor = spineColor,
                lastChapter = lastChapter,
                lastPosition = lastPosition,
                createdAt = createdAt
            )
            writeMetaCache(source, book)
            book
        } catch (_: Exception) {
            null
        }
    }

    private fun writeMetaCache(source: File, book: Book) {
        try {
            val target = metaFile(source.nameWithoutExtension)
            val json = JSONObject().apply {
                put("source_modified", source.lastModified())
                put("source_length", source.length())
                put("id", book.id)
                put("title", book.title)
                put("author", book.author)
                put("chapter_count", book.chapters.size)
                put("spine_color", book.spineColor)
                put("last_chapter", book.lastChapter)
                put("last_position", book.lastPosition)
                put("created_at", book.createdAt)
            }
            val temp = File(target.parentFile, "${target.name}.tmp")
            temp.writeText(json.toString(), Charsets.UTF_8)
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        } catch (_: Exception) {
            // 缓存失败不影响书本本身。
        }
    }

    private fun metaFile(bookId: String): File = File(metaDir, "$bookId.json")

    private fun emptyChapterList(count: Int): List<Chapter> {
        val safeCount = count.coerceAtLeast(0)
        return if (safeCount == 0) emptyList() else Collections.nCopies(safeCount, Chapter("", ""))
    }

    private fun JsonReader.nextStringOr(default: String): String {
        return if (peek() == JsonToken.NULL) {
            nextNull()
            default
        } else {
            nextString()
        }
    }

    private fun JsonReader.nextIntOr(default: Int): Int {
        return try {
            if (peek() == JsonToken.NULL) {
                nextNull()
                default
            } else {
                nextLong().toInt()
            }
        } catch (_: Exception) {
            skipValueSafely()
            default
        }
    }

    private fun JsonReader.nextLongOr(default: Long): Long {
        return try {
            if (peek() == JsonToken.NULL) {
                nextNull()
                default
            } else {
                nextLong()
            }
        } catch (_: Exception) {
            skipValueSafely()
            default
        }
    }

    private fun JsonReader.skipValueSafely() {
        try {
            skipValue()
        } catch (_: Exception) {
        }
    }

    /** 尝试按“第 X 章”拆分章节。 */
    private fun splitChapters(content: String): List<Chapter> {
        val pattern = Regex("(?=第[零一二三四五六七八九十百千\\d]+[章节回])")
        val parts = pattern.split(content).filter { it.trim().isNotEmpty() }

        if (parts.size <= 1) {
            return listOf(Chapter("全文", content.trim()))
        }

        return parts.mapIndexed { index, part ->
            val lines = part.trim().lines()
            val chapterTitle = lines.firstOrNull()?.trim()?.take(30) ?: "第${index + 1}章"
            Chapter(chapterTitle, part.trim())
        }
    }

    /** 随机书脊颜色——暖色调为主。 */
    private fun randomSpineColor(): Int {
        val colors = intArrayOf(
            0xFF8B4513.toInt(),
            0xFFA0522D.toInt(),
            0xFF6B3A2A.toInt(),
            0xFF2F4F4F.toInt(),
            0xFF483D8B.toInt(),
            0xFF556B2F.toInt(),
            0xFF8B0000.toInt(),
            0xFF4A3728.toInt(),
            0xFF2E4057.toInt(),
            0xFF5D4037.toInt(),
            0xFF795548.toInt(),
            0xFF4E342E.toInt()
        )
        return colors[Random().nextInt(colors.size)]
    }

    private fun parseBook(json: JSONObject): Book {
        val chaptersArray = json.optJSONArray("chapters") ?: JSONArray()
        val chapters = mutableListOf<Chapter>()
        for (index in 0 until chaptersArray.length()) {
            val chapter = chaptersArray.getJSONObject(index)
            chapters.add(Chapter(chapter.optString("title", ""), chapter.optString("content", "")))
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
