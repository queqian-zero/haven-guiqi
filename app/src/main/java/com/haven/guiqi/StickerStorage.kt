package com.haven.guiqi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * StickerStorage - 表情包收藏管理（带分组和标签）
 *
 * 文件结构：
 *   应用内部存储/
 *     stickers/              ← 表情包图片文件夹
 *       STK-1715000000.jpg
 *     stickers.json          ← 索引文件
 *
 * stickers.json 格式：
 * {
 *   "stickers": [
 *     {
 *       "id": "STK-1715000000",
 *       "fileName": "STK-xxx.jpg",
 *       "addedAt": 1715000000,
 *       "group": "猫猫",        ← 分组（新增）
 *       "label": "思考"         ← 标签/简短描述（新增）
 *     }
 *   ]
 * }
 *
 * 向后兼容：旧数据没有 group/label 字段时自动填默认值
 */
class StickerStorage(private val context: Context) {

    companion object {
        const val DEFAULT_GROUP = "未分类"
    }

    private val stickerDir: File
        get() {
            val dir = File(context.filesDir, "stickers")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    private val indexFile: File
        get() = File(context.filesDir, "stickers.json")

    // ===== 导入 =====

    /**
     * 从相册导入一张表情包
     * @param group 导入到哪个分组（默认"未分类"）
     */
    fun importFromUri(uri: Uri, group: String = DEFAULT_GROUP): Sticker? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val originalBitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            if (originalBitmap == null) return null

            val maxSize = 512
            val bitmap = if (originalBitmap.width > maxSize || originalBitmap.height > maxSize) {
                val scale = maxSize.toFloat() / maxOf(originalBitmap.width, originalBitmap.height)
                Bitmap.createScaledBitmap(originalBitmap,
                    (originalBitmap.width * scale).toInt(),
                    (originalBitmap.height * scale).toInt(), true)
            } else { originalBitmap }

            val id = "STK-${System.currentTimeMillis()}"
            val fileName = "$id.jpg"
            val file = File(stickerDir, fileName)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }

            val sticker = Sticker(
                id = id,
                fileName = fileName,
                addedAt = System.currentTimeMillis(),
                group = group
            )

            val stickers = loadStickers().toMutableList()
            stickers.add(sticker)
            saveIndex(stickers)

            if (bitmap != originalBitmap) bitmap.recycle()
            originalBitmap.recycle()

            sticker
        } catch (e: Exception) { null }
    }

    // ===== 查询 =====

    /** 获取所有表情包（最新在前） */
    fun loadStickers(): List<Sticker> {
        if (!indexFile.exists()) return emptyList()
        return try {
            val json = JSONObject(indexFile.readText())
            val array = json.getJSONArray("stickers")
            val list = mutableListOf<Sticker>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val sticker = Sticker(
                    id = obj.getString("id"),
                    fileName = obj.getString("fileName"),
                    addedAt = obj.optLong("addedAt", 0L),
                    group = obj.optString("group", DEFAULT_GROUP),
                    label = obj.optString("label", "")
                )
                if (getFile(sticker) != null) list.add(sticker)
            }
            list.sortedByDescending { it.addedAt }
        } catch (e: Exception) { emptyList() }
    }

    /** 获取某个分组的表情包 */
    fun loadByGroup(group: String): List<Sticker> {
        return loadStickers().filter { it.group == group }
    }

    /** 获取所有分组名 + 每组数量，按数量降序 */
    fun loadGroups(): List<Pair<String, Int>> {
        return loadStickers()
            .groupBy { it.group }
            .map { (name, list) -> Pair(name, list.size) }
            .sortedByDescending { it.second }
    }

    /** 根据 ID 查找表情包 */
    fun findById(stickerId: String): Sticker? {
        return loadStickers().find { it.id == stickerId }
    }

    /** 表情包总数 */
    fun count(): Int = loadStickers().size

    /** 获取图片文件（不存在返回 null） */
    fun getFile(sticker: Sticker): File? {
        val file = File(stickerDir, sticker.fileName)
        return if (file.exists()) file else null
    }

    // ===== 修改 =====

    /** 给表情包设置标签 */
    fun setLabel(stickerId: String, label: String) {
        val stickers = loadStickers().toMutableList()
        val index = stickers.indexOfFirst { it.id == stickerId }
        if (index >= 0) {
            stickers[index] = stickers[index].copy(label = label)
            saveIndex(stickers)
        }
    }

    /** 移动表情包到另一个分组 */
    fun setGroup(stickerId: String, group: String) {
        val stickers = loadStickers().toMutableList()
        val index = stickers.indexOfFirst { it.id == stickerId }
        if (index >= 0) {
            stickers[index] = stickers[index].copy(group = group)
            saveIndex(stickers)
        }
    }

    /** 批量移动到某个分组 */
    fun batchSetGroup(stickerIds: List<String>, group: String) {
        val stickers = loadStickers().toMutableList()
        val idSet = stickerIds.toSet()
        for (i in stickers.indices) {
            if (stickers[i].id in idSet) {
                stickers[i] = stickers[i].copy(group = group)
            }
        }
        saveIndex(stickers)
    }

    // ===== 删除 =====

    /** 删除单张表情包 */
    fun deleteSticker(stickerId: String) {
        val stickers = loadStickers().toMutableList()
        val target = stickers.find { it.id == stickerId }
        if (target != null) {
            File(stickerDir, target.fileName).delete()
            stickers.remove(target)
            saveIndex(stickers)
        }
    }

    /** 批量删除表情包 */
    fun batchDelete(stickerIds: List<String>) {
        val stickers = loadStickers().toMutableList()
        val idSet = stickerIds.toSet()
        val toRemove = stickers.filter { it.id in idSet }
        for (s in toRemove) {
            File(stickerDir, s.fileName).delete()
        }
        stickers.removeAll(toRemove)
        saveIndex(stickers)
    }

    /** 删除整个分组（表情包移到未分类） */
    fun deleteGroup(group: String) {
        if (group == DEFAULT_GROUP) return  // 不能删默认分组
        val stickers = loadStickers().toMutableList()
        for (i in stickers.indices) {
            if (stickers[i].group == group) {
                stickers[i] = stickers[i].copy(group = DEFAULT_GROUP)
            }
        }
        saveIndex(stickers)
    }

    /** 重命名分组 */
    fun renameGroup(oldName: String, newName: String) {
        if (oldName == DEFAULT_GROUP) return
        val stickers = loadStickers().toMutableList()
        for (i in stickers.indices) {
            if (stickers[i].group == oldName) {
                stickers[i] = stickers[i].copy(group = newName)
            }
        }
        saveIndex(stickers)
    }

    // ===== AI 用：生成分组概览给 prompt =====

    /**
     * 给 AI 看的表情包概览
     * 格式：猫猫(32张) | 沙雕(48张) | 可爱(15张)
     */
    fun getSummaryForAI(): String {
        val groups = loadGroups()
        if (groups.isEmpty()) return "（没有表情包）"
        return groups.joinToString(" | ") { "${it.first}(${it.second}张)" }
    }

    /**
     * 给 AI 看某个分组的详细列表（带标签）
     * 格式：STK-xxx[思考] | STK-yyy[嗯？] | STK-zzz
     */
    fun getGroupDetailForAI(group: String): String {
        val stickers = loadByGroup(group)
        if (stickers.isEmpty()) return "（这个分组是空的）"
        return stickers.joinToString(" | ") { s ->
            if (s.label.isNotEmpty()) "${s.id}[${s.label}]" else s.id
        }
    }

    // ===== 内部 =====

    private fun saveIndex(stickers: List<Sticker>) {
        val array = JSONArray()
        for (s in stickers) {
            array.put(JSONObject().apply {
                put("id", s.id)
                put("fileName", s.fileName)
                put("addedAt", s.addedAt)
                if (s.group != DEFAULT_GROUP) put("group", s.group)
                if (s.label.isNotEmpty()) put("label", s.label)
            })
        }
        indexFile.writeText(JSONObject().apply { put("stickers", array) }.toString())
    }
}

/**
 * 一张表情包的信息
 */
data class Sticker(
    val id: String,                              // 唯一标识
    val fileName: String,                        // 图片文件名
    val addedAt: Long,                           // 添加时间（毫秒）
    val group: String = StickerStorage.DEFAULT_GROUP,  // 分组
    val label: String = ""                       // 标签/简短描述
)