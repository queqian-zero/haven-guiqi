package com.haven.guiqi

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.UUID

/**
 * 画匣的全屋共享存储。
 *
 * 图片会复制到应用私有目录，不依赖原相册 URI 的长期权限；
 * 大分类与内部分类只用于整理，不限制住户如何使用图片。
 *
 * 11.3 起，聊天表情包也以这里为唯一数据源。表情包标签跟随 Item 保存，
 * 因此从画匣移动、改名或删除分类后，聊天面板和住户指令会立即看到同一份结果。
 */
class GalleryStorage(private val context: Context) {

    companion object {
        /** 所有 GalleryStorage 实例共用一把锁，避免画匣和聊天页同时写索引时互相覆盖。 */
        private val GLOBAL_LOCK = Any()
        private const val RESERVED_UNCLASSIFIED_NAME = "未分类"
    }

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
        val createdAt: Long,
        /** 主要供表情包使用的简短描述；其他图片类型保持空字符串即可。 */
        val label: String = ""
    )

    private val rootDir: File = File(context.filesDir, "shared_gallery")
    private val imagesDir: File = File(rootDir, "images")
    private val indexFile: File = File(rootDir, "index.json")
    private val albumsFile: File = File(rootDir, "albums.json")

    init {
        imagesDir.mkdirs()
    }

    fun listAll(): List<Item> = synchronized(GLOBAL_LOCK) {
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

    fun listAlbums(category: Category): List<Album> = synchronized(GLOBAL_LOCK) {
        loadAlbumsLocked()
            .filter { it.category == category }
            .sortedWith(compareBy<Album> { it.createdAt }.thenBy { it.name })
    }

    fun findAlbum(albumId: String?): Album? {
        if (albumId.isNullOrBlank()) return null
        return synchronized(GLOBAL_LOCK) { loadAlbumsLocked().firstOrNull { it.id == albumId } }
    }

    fun findAlbumByName(category: Category, rawName: String): Album? {
        val name = normalizeAlbumName(rawName)
        if (name.isEmpty()) return null
        return synchronized(GLOBAL_LOCK) {
            loadAlbumsLocked().firstOrNull {
                it.category == category && it.name.equals(name, ignoreCase = true)
            }
        }
    }

    fun createAlbum(category: Category, rawName: String): Album = synchronized(GLOBAL_LOCK) {
        createAlbumLocked(category, rawName)
    }

    /** 找到同名内部分类；不存在时创建。供聊天表情包兼容层使用。 */
    fun getOrCreateAlbum(category: Category, rawName: String): Album = synchronized(GLOBAL_LOCK) {
        val name = normalizeAlbumName(rawName)
        require(name.isNotEmpty()) { "分类名不能为空" }
        loadAlbumsLocked().firstOrNull {
            it.category == category && it.name.equals(name, ignoreCase = true)
        } ?: createAlbumLocked(category, name)
    }

    private fun createAlbumLocked(category: Category, rawName: String): Album {
        val name = normalizeAlbumName(rawName)
        require(name.isNotEmpty()) { "分类名不能为空" }
        require(name.length <= 24) { "分类名最多 24 个字" }
        require(!name.equals(RESERVED_UNCLASSIFIED_NAME, ignoreCase = true)) {
            "“未分类”是系统分类名"
        }

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
        return album
    }

    fun renameAlbum(albumId: String, rawName: String): Boolean = synchronized(GLOBAL_LOCK) {
        val name = normalizeAlbumName(rawName)
        require(name.isNotEmpty()) { "分类名不能为空" }
        require(name.length <= 24) { "分类名最多 24 个字" }
        require(!name.equals(RESERVED_UNCLASSIFIED_NAME, ignoreCase = true)) {
            "“未分类”是系统分类名"
        }

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
    fun deleteAlbum(albumId: String): Boolean = synchronized(GLOBAL_LOCK) {
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
        synchronized(GLOBAL_LOCK) {
            val validAlbumId = validAlbumIdLocked(category, albumId)
            val displayName = queryDisplayName(uri).ifBlank { "图片" }
            val extension = extensionFor(displayName, context.contentResolver.getType(uri))
            val tempFile = File(imagesDir, "${UUID.randomUUID()}.$extension.part")

            try {
                context.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "无法读取图片" }
                    FileOutputStream(tempFile).use { output -> input.copyTo(output) }
                }
                return commitTempFileLocked(
                    tempFile = tempFile,
                    extension = extension,
                    itemId = UUID.randomUUID().toString(),
                    displayName = displayName,
                    category = category,
                    albumId = validAlbumId,
                    createdAt = System.currentTimeMillis(),
                    label = ""
                )
            } catch (e: Exception) {
                tempFile.delete()
                throw e
            }
        }
    }

    /**
     * 把应用已有的图片文件并入画匣。
     *
     * 主要用于把 11.2 及更早版本的旧表情包安全迁移进共享画匣。preferredId
     * 让旧的 STK-xxx 标识继续有效；重复执行时会识别已有条目，因此迁移可重试。
     */
    fun importExistingFile(
        source: File,
        category: Category,
        albumId: String? = null,
        displayName: String = source.name,
        label: String = "",
        preferredId: String? = null,
        createdAt: Long = source.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
    ): Item = synchronized(GLOBAL_LOCK) {
        require(source.isFile) { "图片文件不存在" }
        val validAlbumId = validAlbumIdLocked(category, albumId)
        val requestedId = preferredId?.trim().orEmpty().takeIf { it.isNotEmpty() }

        if (requestedId != null) {
            val items = loadIndexLocked().toMutableList()
            val existingIndex = items.indexOfFirst { it.id == requestedId }
            if (existingIndex >= 0) {
                val existing = items[existingIndex]
                if (fileFor(existing).isFile) {
                    val updated = existing.copy(
                        displayName = displayName.ifBlank { existing.displayName },
                        category = category,
                        albumId = validAlbumId,
                        createdAt = createdAt,
                        label = label.ifBlank { existing.label }
                    )
                    if (updated != existing) {
                        items[existingIndex] = updated
                        saveIndexLocked(items)
                    }
                    return@synchronized updated
                }
            }
        }

        val extension = extensionFor(displayName, null)
        val tempFile = File(imagesDir, "${UUID.randomUUID()}.$extension.part")
        try {
            // 旧表情包与画匣位于同一应用私有文件系统时优先建硬链接：
            // 画匣和旧聊天路径可同时存在，但不会额外复制图片数据。
            try {
                Files.createLink(tempFile.toPath(), source.toPath())
            } catch (_: Exception) {
                source.inputStream().use { input ->
                    FileOutputStream(tempFile).use { output -> input.copyTo(output) }
                }
            }
            commitTempFileLocked(
                tempFile = tempFile,
                extension = extension,
                itemId = requestedId ?: UUID.randomUUID().toString(),
                displayName = displayName.ifBlank { "图片" },
                category = category,
                albumId = validAlbumId,
                createdAt = createdAt,
                label = label.trim()
            )
        } catch (e: Exception) {
            tempFile.delete()
            throw e
        }
    }

    private fun commitTempFileLocked(
        tempFile: File,
        extension: String,
        itemId: String,
        displayName: String,
        category: Category,
        albumId: String?,
        createdAt: Long,
        label: String
    ): Item {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(tempFile.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "文件不是可识别的图片" }

        val finalName = "${UUID.randomUUID()}.$extension"
        val finalFile = File(imagesDir, finalName)
        try {
            if (!tempFile.renameTo(finalFile)) {
                tempFile.copyTo(finalFile, overwrite = true)
                tempFile.delete()
            }

            val item = Item(
                id = itemId,
                fileName = finalName,
                displayName = displayName,
                category = category,
                albumId = albumId,
                createdAt = createdAt,
                label = label
            )
            val items = loadIndexLocked().toMutableList()
            val previousIndex = items.indexOfFirst { it.id == itemId }
            if (previousIndex >= 0) {
                val previous = items[previousIndex]
                if (previous.fileName != finalName) fileFor(previous).delete()
                items[previousIndex] = item
            } else {
                items.add(item)
            }
            saveIndexLocked(items)
            return item
        } catch (e: Exception) {
            tempFile.delete()
            finalFile.delete()
            throw e
        }
    }

    fun move(itemId: String, category: Category, albumId: String? = null): Boolean = synchronized(GLOBAL_LOCK) {
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

    fun moveMany(itemIds: Collection<String>, category: Category, albumId: String? = null): Int =
        synchronized(GLOBAL_LOCK) {
            if (itemIds.isEmpty()) return@synchronized 0
            val idSet = itemIds.toSet()
            val validAlbumId = validAlbumIdLocked(category, albumId)
            var changed = 0
            val items = loadIndexLocked().map { item ->
                if (item.id in idSet) {
                    changed++
                    item.copy(category = category, albumId = validAlbumId)
                } else {
                    item
                }
            }
            if (changed > 0) saveIndexLocked(items)
            changed
        }

    fun setLabel(itemId: String, rawLabel: String): Boolean = synchronized(GLOBAL_LOCK) {
        val items = loadIndexLocked().toMutableList()
        val index = items.indexOfFirst { it.id == itemId }
        if (index < 0) return@synchronized false
        items[index] = items[index].copy(label = normalizeLabel(rawLabel))
        saveIndexLocked(items)
        true
    }

    fun setLabels(itemIds: Collection<String>, rawLabel: String): Int = synchronized(GLOBAL_LOCK) {
        if (itemIds.isEmpty()) return@synchronized 0
        val idSet = itemIds.toSet()
        val label = normalizeLabel(rawLabel)
        var changed = 0
        val items = loadIndexLocked().map { item ->
            if (item.id in idSet) {
                changed++
                item.copy(label = label)
            } else {
                item
            }
        }
        if (changed > 0) saveIndexLocked(items)
        changed
    }

    fun delete(itemId: String): Boolean = synchronized(GLOBAL_LOCK) {
        val items = loadIndexLocked().toMutableList()
        val item = items.firstOrNull { it.id == itemId } ?: return@synchronized false
        saveIndexLocked(items.filterNot { it.id == itemId })
        fileFor(item).delete()
        true
    }

    fun deleteMany(itemIds: Collection<String>): Int = synchronized(GLOBAL_LOCK) {
        if (itemIds.isEmpty()) return@synchronized 0
        val idSet = itemIds.toSet()
        val items = loadIndexLocked()
        val removed = items.filter { it.id in idSet }
        if (removed.isEmpty()) return@synchronized 0
        saveIndexLocked(items.filterNot { it.id in idSet })
        removed.forEach { fileFor(it).delete() }
        removed.size
    }

    fun find(itemId: String): Item? = listAll().firstOrNull { it.id == itemId }

    /** 供住户指令调用；分类为空时可从整个画匣里取。 */
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

    private fun normalizeLabel(rawLabel: String): String {
        return rawLabel.trim().replace(Regex("\\s+"), " ").take(80)
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
                            createdAt = obj.optLong("createdAt", 0L),
                            label = obj.optString("label", "")
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
                if (item.label.isNotEmpty()) put("label", item.label)
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
