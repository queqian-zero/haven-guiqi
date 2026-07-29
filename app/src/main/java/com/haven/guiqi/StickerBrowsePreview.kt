package com.haven.guiqi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.ceil
import kotlin.math.max

/**
 * 给住户视觉浏览表情包时使用的临时编号拼图。
 *
 * 每次只生成当前一页（4×5，共 20 张），不会一次性把整个分类都解码、压缩、
 * 转成 Base64。拼图只作为后台视觉输入，不写入聊天记录，也不显示给用户。
 */
object StickerBrowsePreview {

    const val STICKERS_PER_PAGE = 20
    private const val COLUMNS = 4
    private const val TILE_SIZE = 220
    private const val TILE_GAP = 12
    private const val OUTER_PADDING = 20
    private const val HEADER_HEIGHT = 62

    data class Result(
        val requestedGroup: String,
        val requestedPage: Int,
        val page: Int,
        val totalPages: Int,
        val firstNumber: Int,
        val stickerIds: List<String>,
        val imageBase64: String?,
        val totalCount: Int,
        val pageInRange: Boolean
    ) {
        val shownCount: Int get() = stickerIds.size
        val lastNumber: Int get() = if (shownCount == 0) 0 else firstNumber + shownCount - 1
        val numberToId: Map<Int, String>
            get() = stickerIds.mapIndexed { index, id -> firstNumber + index to id }.toMap()
    }

    fun buildPage(context: Context, rawGroup: String, requestedPage: Int): Result {
        val group = rawGroup.trim().ifEmpty { StickerStorage.DEFAULT_GROUP }
        val pageRequest = requestedPage.coerceAtLeast(1)
        val storage = StickerStorage(context.applicationContext)
        val all = storage.loadByGroup(group)
        val totalCount = all.size
        val totalPages = if (totalCount == 0) 0 else ceil(totalCount / STICKERS_PER_PAGE.toDouble()).toInt()
        val pageInRange = totalPages > 0 && pageRequest in 1..totalPages

        if (!pageInRange) {
            return Result(
                requestedGroup = group,
                requestedPage = pageRequest,
                page = pageRequest,
                totalPages = totalPages,
                firstNumber = 0,
                stickerIds = emptyList(),
                imageBase64 = null,
                totalCount = totalCount,
                pageInRange = false
            )
        }

        val fromIndex = (pageRequest - 1) * STICKERS_PER_PAGE
        val pageStickers = all.drop(fromIndex).take(STICKERS_PER_PAGE)
        val firstNumber = fromIndex + 1
        val image = renderPage(
            storage = storage,
            group = group,
            page = pageRequest,
            totalPages = totalPages,
            stickers = pageStickers,
            firstNumber = firstNumber,
            totalCount = totalCount
        )

        return Result(
            requestedGroup = group,
            requestedPage = pageRequest,
            page = pageRequest,
            totalPages = totalPages,
            firstNumber = firstNumber,
            stickerIds = pageStickers.map { it.id },
            imageBase64 = image,
            totalCount = totalCount,
            pageInRange = true
        )
    }

    private fun renderPage(
        storage: StickerStorage,
        group: String,
        page: Int,
        totalPages: Int,
        stickers: List<Sticker>,
        firstNumber: Int,
        totalCount: Int
    ): String? {
        if (stickers.isEmpty()) return null

        val rows = ceil(stickers.size / COLUMNS.toDouble()).toInt()
        val width = OUTER_PADDING * 2 + COLUMNS * TILE_SIZE + (COLUMNS - 1) * TILE_GAP
        val height = HEADER_HEIGHT + OUTER_PADDING + rows * TILE_SIZE + max(0, rows - 1) * TILE_GAP + OUTER_PADDING
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        paint.textSize = 25f
        paint.color = Color.rgb(35, 35, 35)
        val lastNumber = firstNumber + stickers.size - 1
        canvas.drawText(
            "$group · 第$page/$totalPages 页 · $firstNumber-$lastNumber（共${totalCount}张）",
            OUTER_PADDING.toFloat(),
            40f,
            paint
        )

        stickers.forEachIndexed { localIndex, sticker ->
            val row = localIndex / COLUMNS
            val column = localIndex % COLUMNS
            val left = OUTER_PADDING + column * (TILE_SIZE + TILE_GAP)
            val top = HEADER_HEIGHT + OUTER_PADDING + row * (TILE_SIZE + TILE_GAP)
            val tileRect = RectF(
                left.toFloat(),
                top.toFloat(),
                (left + TILE_SIZE).toFloat(),
                (top + TILE_SIZE).toFloat()
            )

            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(244, 244, 244)
            canvas.drawRoundRect(tileRect, 18f, 18f, paint)

            val file = storage.getFile(sticker)
            val decoded = if (file != null) drawImageCenterInside(canvas, file, tileRect) else false
            if (!decoded) {
                paint.color = Color.rgb(140, 140, 140)
                paint.textSize = 18f
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText("图片无法读取", tileRect.centerX(), tileRect.centerY(), paint)
                paint.textAlign = Paint.Align.LEFT
            }

            val number = firstNumber + localIndex
            val badgeRect = RectF(
                tileRect.left + 8f,
                tileRect.top + 8f,
                tileRect.left + 64f,
                tileRect.top + 49f
            )
            paint.color = Color.argb(220, 0, 0, 0)
            canvas.drawRoundRect(badgeRect, 13f, 13f, paint)
            paint.color = Color.WHITE
            paint.textSize = 24f
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText(number.toString(), badgeRect.centerX(), badgeRect.top + 29f, paint)
            paint.textAlign = Paint.Align.LEFT
        }

        return try {
            val bytes = ByteArrayOutputStream().use { output ->
                // 保持足够清晰，尤其照顾带小字的表情包；性能主要靠“一次只处理一页”解决。
                bitmap.compress(Bitmap.CompressFormat.JPEG, 88, output)
                output.toByteArray()
            }
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        } finally {
            bitmap.recycle()
        }
    }

    private fun drawImageCenterInside(canvas: Canvas, file: File, target: RectF): Boolean {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return false

        val maxDecode = TILE_SIZE * 2
        var sample = 1
        while (options.outWidth / sample > maxDecode || options.outHeight / sample > maxDecode) {
            sample *= 2
        }
        val bitmap = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        ) ?: return false

        try {
            val inset = 8f
            val available = RectF(
                target.left + inset,
                target.top + inset,
                target.right - inset,
                target.bottom - inset
            )
            val scale = minOf(
                available.width() / bitmap.width.toFloat(),
                available.height() / bitmap.height.toFloat()
            )
            val drawWidth = bitmap.width * scale
            val drawHeight = bitmap.height * scale
            val destination = RectF(
                available.centerX() - drawWidth / 2f,
                available.centerY() - drawHeight / 2f,
                available.centerX() + drawWidth / 2f,
                available.centerY() + drawHeight / 2f
            )
            canvas.drawBitmap(
                bitmap,
                null as Rect?,
                destination,
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            )
            return true
        } finally {
            bitmap.recycle()
        }
    }
}
