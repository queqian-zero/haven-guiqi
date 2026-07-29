package com.haven.guiqi

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File

/**
 * 聊天表情包兼容层。
 *
 * 11.3 起不再维护独立的 stickers/ + stickers.json；所有读写都落到 GalleryStorage
 * 的“表情包”大分类。这样画匣、聊天面板和住户指令看到的是同一份图片与分类。
 *
 * 类名和公开方法暂时保留，避免聊天页、指令处理器和提示词构建器同时大改。
 */
class StickerStorage(context: Context) {

    companion object {
        const val DEFAULT_GROUP = "未分类"
        private const val MIGRATION_PREFS = "haven_gallery_migrations"
        private const val MIGRATION_KEY = "legacy_stickers_to_gallery_v1"
        private val MIGRATION_LOCK = Any()
    }

    private val appContext = context.applicationContext
    private val gallery = GalleryStorage(appContext)

    init {
        synchronized(MIGRATION_LOCK) {
            migrateLegacyStickersIfNeeded()
        }
    }

    /** 从相册导入一张表情包；图片直接进入画匣的“表情包”分类。 */
    fun importFromUri(uri: Uri, group: String = DEFAULT_GROUP): Sticker? {
        return try {
            val item = gallery.importImage(
                uri = uri,
                category = GalleryStorage.Category.STICKER,
                albumId = albumIdForGroup(group)
            )
            toSticker(item)
        } catch (_: Exception) {
            null
        }
    }

    /** 确保聊天导入所选的分组存在。 */
    fun ensureGroup(group: String): Boolean {
        if (isDefaultGroup(group)) return true
        return try {
            albumIdForGroup(group) != null
        } catch (_: Exception) {
            false
        }
    }

    /** 获取所有表情包（最新在前）。 */
    fun loadStickers(): List<Sticker> {
        val albumNames = gallery.listAlbums(GalleryStorage.Category.STICKER)
            .associate { it.id to it.name }
        return gallery.listByCategory(GalleryStorage.Category.STICKER)
            .map { toSticker(it, albumNames) }
    }

    /** 获取某个分组的表情包。 */
    fun loadByGroup(group: String): List<Sticker> {
        return loadStickers().filter { it.group.equals(normalizeGroup(group), ignoreCase = true) }
    }

    /**
     * 获取所有分组名 + 每组数量。
     * “未分类”固定在最前；画匣里即使是空的内部分类，也会出现在聊天面板。
     */
    fun loadGroups(): List<Pair<String, Int>> {
        val items = gallery.listByCategory(GalleryStorage.Category.STICKER)
        val counts = items.groupingBy { it.albumId }.eachCount()
        return buildList {
            add(DEFAULT_GROUP to (counts[null] ?: 0))
            gallery.listAlbums(GalleryStorage.Category.STICKER).forEach { album ->
                add(album.name to (counts[album.id] ?: 0))
            }
        }
    }

    fun findById(stickerId: String): Sticker? {
        val item = gallery.find(stickerId)
            ?.takeIf { it.category == GalleryStorage.Category.STICKER }
            ?: return null
        return toSticker(item)
    }

    fun count(): Int = gallery.listByCategory(GalleryStorage.Category.STICKER).size

    fun getFile(sticker: Sticker): File? {
        val item = gallery.find(sticker.id) ?: return null
        if (item.category != GalleryStorage.Category.STICKER) return null
        return gallery.fileFor(item).takeIf { it.isFile }
    }

    fun setLabel(stickerId: String, label: String) {
        gallery.setLabel(stickerId, label)
    }

    fun setGroup(stickerId: String, group: String) {
        try {
            gallery.move(stickerId, GalleryStorage.Category.STICKER, albumIdForGroup(group))
        } catch (_: Exception) {
            // 兼容旧调用：非法分组名不让聊天页崩溃。
        }
    }

    fun batchSetGroup(stickerIds: List<String>, group: String) {
        try {
            gallery.moveMany(stickerIds, GalleryStorage.Category.STICKER, albumIdForGroup(group))
        } catch (_: Exception) {
            // 兼容旧调用：非法分组名不让聊天页崩溃。
        }
    }

    fun deleteSticker(stickerId: String) {
        gallery.delete(stickerId)
    }

    fun batchDelete(stickerIds: List<String>) {
        gallery.deleteMany(stickerIds)
    }

    /** 删除分组时图片回到“未分类”。 */
    fun deleteGroup(group: String) {
        if (isDefaultGroup(group)) return
        gallery.findAlbumByName(GalleryStorage.Category.STICKER, group)?.let {
            gallery.deleteAlbum(it.id)
        }
    }

    /**
     * 重命名分组。若目标名已经存在，则合并到目标分组，避免重复分类或界面崩溃。
     */
    fun renameGroup(oldName: String, newName: String) {
        if (isDefaultGroup(oldName)) return
        val normalizedNew = normalizeGroup(newName)
        if (normalizedNew.isBlank() || isDefaultGroup(normalizedNew)) return

        val source = gallery.findAlbumByName(GalleryStorage.Category.STICKER, oldName) ?: return
        val target = gallery.findAlbumByName(GalleryStorage.Category.STICKER, normalizedNew)
        try {
            if (target != null && target.id != source.id) {
                val sourceIds = gallery.listByCategory(
                    GalleryStorage.Category.STICKER,
                    albumId = source.id
                ).map { it.id }
                gallery.moveMany(sourceIds, GalleryStorage.Category.STICKER, target.id)
                gallery.deleteAlbum(source.id)
            } else {
                gallery.renameAlbum(source.id, normalizedNew)
            }
        } catch (_: Exception) {
            // 保持旧版 Unit API 的容错行为。
        }
    }

    /** 给 AI 看的表情包概览。空分类不塞进 prompt。 */
    fun getSummaryForAI(): String {
        if (count() == 0) return "（没有表情包）"
        return loadGroups()
            .filter { it.second > 0 }
            .joinToString(" | ") { "${it.first}(${it.second}张)" }
    }

    /** 给 AI 看某个分组的详细列表（带标签）。 */
    fun getGroupDetailForAI(group: String): String {
        val stickers = loadByGroup(group)
        if (stickers.isEmpty()) return "（这个分组是空的）"
        return stickers.joinToString(" | ") { sticker ->
            if (sticker.label.isNotEmpty()) "${sticker.id}[${sticker.label}]" else sticker.id
        }
    }

    private fun toSticker(
        item: GalleryStorage.Item,
        albumNames: Map<String, String> = gallery.listAlbums(GalleryStorage.Category.STICKER)
            .associate { it.id to it.name }
    ): Sticker {
        return Sticker(
            id = item.id,
            fileName = item.fileName,
            addedAt = item.createdAt,
            group = item.albumId?.let { albumNames[it] } ?: DEFAULT_GROUP,
            label = item.label
        )
    }

    private fun albumIdForGroup(rawGroup: String): String? {
        val group = normalizeGroup(rawGroup)
        if (isDefaultGroup(group)) return null
        return gallery.getOrCreateAlbum(GalleryStorage.Category.STICKER, group).id
    }

    private fun normalizeGroup(rawGroup: String): String {
        return rawGroup.trim().replace(Regex("\\s+"), " ")
            .ifEmpty { DEFAULT_GROUP }
    }

    private fun isDefaultGroup(group: String): Boolean {
        return normalizeGroup(group).equals(DEFAULT_GROUP, ignoreCase = true)
    }

    /**
     * 一次性迁移旧表情包。
     *
     * 旧文件和旧索引会保留为只读兜底：旧聊天记录里保存的是这些绝对路径，
     * 保留它们比扫描、重写所有聊天文件更稳，也不会与后台追加聊天记录打架。
     * GalleryStorage 会优先用硬链接并入画匣，因此通常不会额外占一份图片空间。
     * 旧 STK-xxx ID、标签和分组都会保留。
     */
    private fun migrateLegacyStickersIfNeeded() {
        val prefs = appContext.getSharedPreferences(MIGRATION_PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(MIGRATION_KEY, false)) return

        val legacyIndex = File(appContext.filesDir, "stickers.json")
        val legacyDir = File(appContext.filesDir, "stickers")
        if (!legacyIndex.isFile) {
            prefs.edit().putBoolean(MIGRATION_KEY, true).apply()
            return
        }

        try {
            val array = JSONObject(legacyIndex.readText(Charsets.UTF_8)).optJSONArray("stickers")
                ?: return
            var failed = false

            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.optString("id").trim()
                val fileName = obj.optString("fileName").trim()
                if (id.isEmpty() || fileName.isEmpty()) continue

                val source = File(legacyDir, fileName)
                if (!source.isFile) continue

                try {
                    val group = obj.optString("group", DEFAULT_GROUP)
                    gallery.importExistingFile(
                        source = source,
                        category = GalleryStorage.Category.STICKER,
                        albumId = albumIdForGroup(group),
                        displayName = fileName,
                        label = obj.optString("label", ""),
                        preferredId = id,
                        createdAt = obj.optLong("addedAt", source.lastModified())
                    )
                } catch (_: Exception) {
                    failed = true
                }
            }

            if (!failed) {
                // 不删旧目录：它仍为 11.2 以前的聊天记录提供稳定图片路径。
                prefs.edit().putBoolean(MIGRATION_KEY, true).apply()
            }
        } catch (_: Exception) {
            // 保留旧数据，下一次再重试；不让迁移问题阻止聊天页打开。
        }
    }

}

/** 聊天层使用的一张表情包信息；实际文件与元数据由 GalleryStorage 保存。 */
data class Sticker(
    val id: String,
    val fileName: String,
    val addedAt: Long,
    val group: String = StickerStorage.DEFAULT_GROUP,
    val label: String = ""
)
