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
 * StickerStorage - 表情包收藏管理
 *
 * 表情包图片存在应用内部存储的 stickers 文件夹里
 * 索引信息存在 stickers.json 文件里
 *
 * 文件结构：
 *   应用内部存储/
 *     stickers/              ← 表情包图片文件夹
 *       STK-1715000000.jpg   ← 每张表情包一个文件
 *       STK-1715000001.jpg
 *     stickers.json          ← 索引文件，记录每张表情包的信息
 *
 * stickers.json 格式：
 * {
 *   "stickers": [
 *     {
 *       "id": "STK-1715000000",    // 唯一标识
 *       "fileName": "STK-xxx.jpg", // 文件名
 *       "addedAt": 1715000000      // 添加时间（毫秒）
 *     }
 *   ]
 * }
 *
 * 用法很简单：
 *   val storage = StickerStorage(context)
 *   storage.importFromUri(uri)          // 从相册导入
 *   val list = storage.loadStickers()   // 获取所有表情包
 *   storage.deleteSticker("STK-xxx")    // 删除某个表情包
 */
class StickerStorage(private val context: Context) {

    // 表情包图片存放的文件夹
    private val stickerDir: File
        get() {
            val dir = File(context.filesDir, "stickers")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    // 索引文件
    private val indexFile: File
        get() = File(context.filesDir, "stickers.json")

    /**
     * 从相册导入一张表情包
     *
     * @param uri 用户从相册选的图片的 Uri
     * @return 导入成功返回 Sticker 对象，失败返回 null
     *
     * 原理：
     * 1. 通过 uri 读取图片的字节流
     * 2. 解码成 Bitmap（安卓里的图片对象）
     * 3. 如果图片太大就缩小（表情包不需要很大）
     * 4. 保存到 stickers 文件夹里
     * 5. 在索引文件里记一笔
     */
    fun importFromUri(uri: Uri): Sticker? {
        return try {
            // 读取图片
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val originalBitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()

            if (originalBitmap == null) return null

            // 如果图片太大就缩小，表情包 512px 足够了
            val maxSize = 512
            val bitmap = if (originalBitmap.width > maxSize || originalBitmap.height > maxSize) {
                val scale = maxSize.toFloat() / maxOf(originalBitmap.width, originalBitmap.height)
                val newW = (originalBitmap.width * scale).toInt()
                val newH = (originalBitmap.height * scale).toInt()
                Bitmap.createScaledBitmap(originalBitmap, newW, newH, true)
            } else {
                originalBitmap
            }

            // 生成唯一 ID 和文件名
            val id = "STK-${System.currentTimeMillis()}"
            val fileName = "$id.jpg"
            val file = File(stickerDir, fileName)

            // 保存图片到文件
            val out = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            out.flush()
            out.close()

            // 创建表情包记录
            val sticker = Sticker(
                id = id,
                fileName = fileName,
                addedAt = System.currentTimeMillis()
            )

            // 写入索引
            val stickers = loadStickers().toMutableList()
            stickers.add(sticker)
            saveIndex(stickers)

            sticker
        } catch (e: Exception) {
            // 导入失败不崩溃，返回 null
            null
        }
    }

    /**
     * 获取所有表情包（按添加时间倒序，最新的在前面）
     */
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
                    addedAt = obj.optLong("addedAt", 0L)
                )
                // 确认图片文件还在（万一被手动删了）
                if (getFile(sticker) != null) {
                    list.add(sticker)
                }
            }

            list.sortedByDescending { it.addedAt }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 删除一张表情包
     * 同时删除图片文件和索引记录
     */
    fun deleteSticker(stickerId: String) {
        // 删除图片文件
        val stickers = loadStickers().toMutableList()
        val target = stickers.find { it.id == stickerId }
        if (target != null) {
            File(stickerDir, target.fileName).delete()
            stickers.remove(target)
            saveIndex(stickers)
        }
    }

    /**
     * 根据表情包获取它的图片文件路径
     * 如果文件不存在就返回 null
     */
    fun getFile(sticker: Sticker): File? {
        val file = File(stickerDir, sticker.fileName)
        return if (file.exists()) file else null
    }

    /**
     * 表情包总数
     */
    fun count(): Int = loadStickers().size

    // ===== 保存索引到 JSON 文件 =====
    private fun saveIndex(stickers: List<Sticker>) {
        val array = JSONArray()
        for (s in stickers) {
            array.put(JSONObject().apply {
                put("id", s.id)
                put("fileName", s.fileName)
                put("addedAt", s.addedAt)
            })
        }
        val json = JSONObject().apply {
            put("stickers", array)
        }
        indexFile.writeText(json.toString())
    }
}

/**
 * 一张表情包的信息
 */
data class Sticker(
    val id: String,           // 唯一标识，格式 "STK-时间戳"
    val fileName: String,     // 图片文件名
    val addedAt: Long         // 添加时间（毫秒）
)