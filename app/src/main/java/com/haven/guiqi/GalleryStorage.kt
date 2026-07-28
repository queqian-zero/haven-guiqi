package com.haven.guiqi

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * 画匣的全屋共享存储。
 *
 * 图片会复制到应用私有目录，不依赖原相册 URI 的长期权限；
 * 大分类与内部分类只用于整理，不限制住户如何使用图片。
 */
class GalleryStorage(private val context: Context) {

    enum class Category(val key: String, val label: String) {
        AVATAR("avatar", "头像"),
        BACKGROUND("background", "背景"),
        STICKER("sticker", "表情包"),
        GENERAL("general", "普通图片");

        companion object {
            fun fromKey(key: String?): Category = values().firstOrNull { it.key == key } ?: GENERAL
        }
    }

    data class Album(
        val id: String,
        val category: Category,
        val name: String,
        val createdAt: Long
    )

    data class Item(
        val id: String,
        val fileName: String,
        val displayName: String,
        val category: Category,
        val albumId: String?,
        val createdAt: Long
    )

    private val rootDir: File = File(context.filesDir, "shared_gallery")
    private val imagesDir: File = File(rootDir, "images")
    private val indexFile: File = File(rootDir, "index.json")
    private val albumsFile: File = File(rootDir, "albums.json")
    private val lock = Any()

    init {
        imagesDir.mkdirs()
    }

    fun listAll(): List<Item> = synchronized(lock) {
        val validAlbumIds = loadAlbumsLocked().map { it.id }.toSet()
        loadIndexLocked()
            .filter { fileFor(it).isFile }
            .map { item ->
                if (item.albumId != null && item.albumId !in validAlbumIds) {
                    item.copy(albumId = null)
                } else {
                    item
                }
            }
            .sortedByDescending { it.createdAt }
    }

    /**
     * category 为空时返回整个画匣。
     * category 不为空且 albumId 为空时返回该大分类全部图片。
     * unclassifiedOnly 为 true 时只返回未放进内部分类的图片。
     */
    fun listByCategory(
        category: Category?,
        albumId: String? = null,
        unclassifiedOnly: Boolean = false
    ): List<Item> {
        val categoryItems = if (category == null) listAll() else listAll().filter { it.category == category }
        return when {
            category == null -> categoryItems
            unclassifiedOnly -> categoryItems.filter { it.albumId == null }
            albumId != null -> categoryItems.filter { it.albumId == albumId }
            else -> categoryItems
        }
    }

    fun count(): Int = listAll().size

    fun fileFor(item: Item): File = File(imagesDir, item.fileName)

    fun listAlbums(category: Category): List<Album> = synchronized(lock) {
        loadAlbumsLocked()
            .filter { it.category == category }
            .sortedWith(compareBy<Album> { it.createdAt }.thenBy { it.name })
    }

    fun findAlbum(albumId: String?): Album? {
        if (albumId.isNullOrBlank()) return null
        return synchronized(lock) { loadAlbumsLocked().firstOrNull { it.id == albumId } }
    }

    fun createAlbum(category: Category, rawName: String): Album = synchronized(lock) {
        val name = normalizeAlbumName(rawName)
        require(name.isNotEmpty()) { "分类名不能为空" }
        require(name.length <= 24) { "分类名最多 24 个字" }

        val albums = loadAlbumsLocked().toMutableList()
        require(albums.none { it.category == category && it.name.equals(name, ignoreCase = true) }) {
            "这个分类已经存在"
        }

        val album = Album(
            id = UUID.randomUUID().toString(),
            category = category,
            name = name,
            createdAt = System.currentTimeMillis()
        )
        albums += album
        saveAlbumsLocked(albums)
        album
    }

    fun renameAlbum(albumId: String, rawName: String): Boolean = synchronized(lock) {
        val name = normalizeAlbumName(rawName)
        require(name.isNotEmpty()) { "分类名不能为空" }
        require(name.length <= 24) { "分类名最多 24 个字" }

        val albums = loadAlbumsLocked().toMutableList()
        val index = albums.indexOfFirst { it.id == albumId }
        if (index < 0) return@synchronized false
        val album = albums[index]
        require(albums.none {
            it.id != albumId && it.category == album.category && it.name.equals(name, ignoreCase = true)
        }) { "这个分类已经存在" }

        albums[index] = album.copy(name = name)
        saveAlbumsLocked(albums)
        true
    }

    /** 删除内部分类时，图片保留并回到该大分类的“未分类”。 */
    fun deleteAlbum(albumId: String): Boolean = synchronized(lock) {
        val albums = loadAlbumsLocked().toMutableList()
        if (albums.none { it.id == albumId }) return@synchronized false

        val updatedItems = loadIndexLocked().map { item ->
            if (item.albumId == albumId) item.copy(albumId = null) else item
        }
        saveIndexLocked(updatedItems)
        saveAlbumsLocked(albums.filterNot { it.id == albumId })
        true
    }

    /**
     * 导入一张图片。先写临时文件并校验，再原子式加入索引，避免半张图留在画匣里。
     */
    fun importImage(uri: Uri, category: Category, albumId: String? = null): Item {
        synchronized(lock) {
            val validAlbumId = validAlbumIdLocked(category, albumId)
            val displayName = queryDisplayName(uri).ifBlank { "图片" }
            val extension = extensionFor(displayName, context.contentResolver.getType(uri))
            val id = UUID.randomUUID().toString()
            val finalName = "$id.$extension"
            val tempFile = File(imagesDir, "$finalName.part")
            val finalFile = File(imagesDir, finalName)

            try {
                context.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "无法读取图片" }
                    FileOutputStream(tempFile).use { output -> input.copyTo(output) }
                }

                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(tempFile.absolutePath, bounds)
                require(bounds.outWidth > 0 && bounds.outHeight > 0) { "文件不是可识别的图片" }

                if (!tempFile.renameTo(finalFile)) {
                    tempFile.copyTo(finalFile, overwrite = true)
                    tempFile.delete()
                }

                val item = Item(
                    id = id,
                    fileName = finalName,
                    displayName = displayName,
                    category = category,
                    albumId = validAlbumId,
                    createdAt = System.currentTimeMillis()
                )
                val items = loadIndexLocked().toMutableList().apply { add(item) }
                saveIndexLocked(items)
                return item
            } catch (e: Exception) {
                tempFile.delete()
                finalFile.delete()
                throw e
            }
        }
    }

    fun move(itemId: String, category: Category, albumId: String? = null): Boolean = synchronized(lock) {
        val items = loadIndexLocked().toMutableList()
        val index = items.indexOfFirst { it.id == itemId }
        if (index < 0) return@synchronized false
        items[index] = items[index].copy(
            category = category,
            albumId = validAlbumIdLocked(category, albumId)
        )
        saveIndexLocked(items)
        true
    }

    fun delete(itemId: String): Boolean = synchronized(lock) {
        val items = loadIndexLocked().toMutableList()
        val item = items.firstOrNull { it.id == itemId } ?: return@synchronized false
        val newItems = items.filterNot { it.id == itemId }
        saveIndexLocked(newItems)
        fileFor(item).delete()
        true
    }

    fun find(itemId: String): Item? = listAll().firstOrNull { it.id == itemId }

    /** 供后续住户指令调用；分类为空时可从整个画匣里取。 */
    fun randomItem(category: Category? = null, albumId: String? = null): Item? {
        return listByCategory(category, albumId).randomOrNull()
    }

    private fun validAlbumIdLocked(category: Category, albumId: String?): String? {
        if (albumId.isNullOrBlank()) return null
        return loadAlbumsLocked().firstOrNull {
            it.id == albumId && it.category == category
        }?.id
    }

    private fun normalizeAlbumName(rawName: String): String {
        return rawName.trim().replace(Regex("\\s+"), " ")
    }

    private fun loadIndexLocked(): List<Item> {
        if (!indexFile.isFile) return emptyList()
        return try {
            val array = JSONArray(indexFile.readText(Charsets.UTF_8))
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val id = obj.optString("id")
                    val fileName = obj.optString("fileName")
                    if (id.isBlank() || fileName.isBlank()) continue
                    add(
                        Item(
                            id = id,
                            fileName = fileName,
                            displayName = obj.optString("displayName", "图片"),
                            category = Category.fromKey(obj.optString("category")),
                            albumId = obj.optString("albumId").takeIf { it.isNotBlank() },
                            createdAt = obj.optLong("createdAt", 0L)
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveIndexLocked(items: List<Item>) {
        rootDir.mkdirs()
        val array = JSONArray()
        items.forEach { item ->
            array.put(JSONObject().apply {
                put("id", item.id)
                put("fileName", item.fileName)
                put("displayName", item.displayName)
                put("category", item.category.key)
                if (item.albumId != null) put("albumId", item.albumId)
                put("createdAt", item.createdAt)
            })
        }
        writeJsonAtomically(indexFile, array.toString())
    }

    private fun loadAlbumsLocked(): List<Album> {
        if (!albumsFile.isFile) return emptyList()
        return try {
            val array = JSONArray(albumsFile.readText(Charsets.UTF_8))
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val id = obj.optString("id")
                    val name = obj.optString("name").trim()
                    if (id.isBlank() || name.isBlank()) continue
                    add(
                        Album(
                            id = id,
                            category = Category.fromKey(obj.optString("category")),
                            name = name,
                            createdAt = obj.optLong("createdAt", 0L)
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveAlbumsLocked(albums: List<Album>) {
        rootDir.mkdirs()
        val array = JSONArray()
        albums.forEach { album ->
            array.put(JSONObject().apply {
                put("id", album.id)
                put("category", album.category.key)
                put("name", album.name)
                put("createdAt", album.createdAt)
            })
        }
        writeJsonAtomically(albumsFile, array.toString())
    }

    private fun writeJsonAtomically(target: File, content: String) {
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.writeText(content, Charsets.UTF_8)
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    private fun queryDisplayName(uri: Uri): String {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) ?: "" else ""
                }.orEmpty()
        } catch (_: Exception) {
            uri.lastPathSegment?.substringAfterLast('/').orEmpty()
        }
    }

    private fun extensionFor(displayName: String, mimeType: String?): String {
        val fromName = displayName.substringAfterLast('.', "").lowercase()
        if (fromName.matches(Regex("[a-z0-9]{2,5}"))) return fromName
        return when (mimeType?.lowercase()) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "image/heic", "image/heif" -> "heic"
            else -> "jpg"
        }
    }
}
