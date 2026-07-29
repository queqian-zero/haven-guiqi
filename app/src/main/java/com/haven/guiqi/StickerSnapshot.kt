package com.haven.guiqi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * 表情包聊天快照。
 *
 * create() 保留 v11.3 的旧接口：只生成白底 JPEG，供住户/API 侧继续使用。
 * createPair() 供用户发送表情包：额外生成一份可保留透明通道的本地显示快照。
 */
object StickerSnapshot {

    data class PairSnapshot(
        val displayFile: File,
        val apiFile: File
    )

    private data class SnapshotFiles(
        val displayFile: File?,
        val apiFile: File
    )

    private const val MAX_SIDE = 800
    private const val JPEG_QUALITY = 85

    /** 兼容旧调用：AI/API 侧仍只拿到原有白底 JPEG。 */
    fun create(context: Context, source: File, prefix: String = "sticker"): File? =
        createFiles(context, source, prefix, includeDisplay = false)?.apiFile

    /** 用户侧发送时使用：本地透明显示文件 + API 白底 JPEG。 */
    fun createPair(context: Context, source: File, prefix: String = "sticker"): PairSnapshot? {
        val files = createFiles(context, source, prefix, includeDisplay = true) ?: return null
        val display = files.displayFile ?: run {
            files.apiFile.delete()
            return null
        }
        return PairSnapshot(display, files.apiFile)
    }

    private fun createFiles(
        context: Context,
        source: File,
        prefix: String,
        includeDisplay: Boolean
    ): SnapshotFiles? {
        if (!source.isFile) return null

        val dir = File(context.filesDir, "chat_images").apply { mkdirs() }
        val suffix = "${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
        var displayTarget: File? = null
        val apiTarget = File(dir, "${prefix}_api_$suffix.jpg")
        var decoded: Bitmap? = null
        var scaled: Bitmap? = null
        var flattened: Bitmap? = null

        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(source.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sampleSize = 1
            while (bounds.outWidth / sampleSize > MAX_SIDE * 2 ||
                bounds.outHeight / sampleSize > MAX_SIDE * 2) {
                sampleSize *= 2
            }

            decoded = BitmapFactory.decodeFile(
                source.absolutePath,
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize.coerceAtLeast(1)
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
            ) ?: return null

            val sourceBitmap = decoded!!
            val scale = minOf(
                1f,
                MAX_SIDE.toFloat() / maxOf(sourceBitmap.width, sourceBitmap.height).toFloat()
            )
            scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    sourceBitmap,
                    (sourceBitmap.width * scale).toInt().coerceAtLeast(1),
                    (sourceBitmap.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else {
                sourceBitmap
            }
            val outputBitmap = scaled!!

            if (includeDisplay) {
                val keepAlpha = outputBitmap.hasAlpha()
                displayTarget = File(
                    dir,
                    if (keepAlpha) "${prefix}_display_$suffix.png" else "${prefix}_display_$suffix.jpg"
                )
                FileOutputStream(displayTarget!!).use { output ->
                    val format = if (keepAlpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                    val quality = if (keepAlpha) 100 else 92
                    require(outputBitmap.compress(format, quality, output)) { "本地表情快照保存失败" }
                }
            }

            // AI 侧继续沿用 v11.3 的白底 JPEG，不改变识图链路。
            flattened = Bitmap.createBitmap(
                outputBitmap.width,
                outputBitmap.height,
                Bitmap.Config.ARGB_8888
            ).apply {
                val canvas = Canvas(this)
                canvas.drawColor(Color.WHITE)
                canvas.drawBitmap(outputBitmap, 0f, 0f, null)
            }
            FileOutputStream(apiTarget).use { output ->
                require(flattened!!.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                    "API 表情快照保存失败"
                }
            }

            val displayOkay = !includeDisplay ||
                (displayTarget?.isFile == true && displayTarget!!.length() > 0L)
            if (displayOkay && apiTarget.isFile && apiTarget.length() > 0L) {
                SnapshotFiles(displayTarget, apiTarget)
            } else {
                displayTarget?.delete()
                apiTarget.delete()
                null
            }
        } catch (_: Exception) {
            displayTarget?.delete()
            apiTarget.delete()
            null
        } finally {
            flattened?.recycle()
            if (scaled != null && scaled !== decoded) scaled?.recycle()
            decoded?.recycle()
        }
    }
}
