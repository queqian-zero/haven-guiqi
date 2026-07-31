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

/** 按需给住户看的画匣头像临时编号拼图。 */
object AvatarBrowsePreview {

    const val ALL_LABEL = "全部"
    const val ITEMS_PER_PAGE = 20
    private const val COLUMNS = 4
    private const val TILE_SIZE = 220
    private const val TILE_GAP = 12
    private const val OUTER_PADDING = 20
    private const val HEADER_HEIGHT = 62

    data class Result(
        val requestedAlbum: String,
        val requestedPage: Int,
        val page: Int,
        val totalPages: Int,
        val firstNumber: Int,
        val itemIds: List<String>,
        val imageBase64: String?,
        val totalCount: Int,
        val pageInRange: Boolean,
        val albumExists: Boolean,
        val availableAlbums: List<String>
    ) {
        val shownCount: Int get() = itemIds.size
        val lastNumber: Int get() = if (shownCount == 0) 0 else firstNumber + shownCount - 1
        val numberToId: Map<Int, String>
            get() = itemIds.mapIndexed { index, id -> firstNumber + index to id }.toMap()
    }

    fun buildPage(context: Context, rawAlbum: String, requestedPage: Int): Result {
        val storage = GalleryStorage(context.applicationContext)
        val albums = storage.listAlbums(GalleryStorage.Category.AVATAR)
        val availableAlbums = albums.map { it.name }
        val requestedAlbum = rawAlbum.trim().ifEmpty { ALL_LABEL }
        val allMode = requestedAlbum.equals(ALL_LABEL, true) ||
            requestedAlbum.equals("所有", true) || requestedAlbum.equals("全部头像", true)
        val album = if (allMode) null else storage.findAlbumByName(GalleryStorage.Category.AVATAR, requestedAlbum)
        val albumExists = allMode || album != null
        val allItems = when {
            allMode -> storage.listByCategory(GalleryStorage.Category.AVATAR)
            album != null -> storage.listByCategory(GalleryStorage.Category.AVATAR, album.id)
            else -> emptyList()
        }

        val pageRequest = requestedPage.coerceAtLeast(1)
        val totalCount = allItems.size
        val totalPages = if (totalCount == 0) 0 else ceil(totalCount / ITEMS_PER_PAGE.toDouble()).toInt()
        val pageInRange = totalPages > 0 && pageRequest in 1..totalPages
        if (!pageInRange) {
            return Result(
                requestedAlbum, pageRequest, pageRequest, totalPages, 0,
                emptyList(), null, totalCount, false, albumExists, availableAlbums
            )
        }

        val fromIndex = (pageRequest - 1) * ITEMS_PER_PAGE
        val pageItems = allItems.drop(fromIndex).take(ITEMS_PER_PAGE)
        val firstNumber = fromIndex + 1
        val image = renderPage(
            storage,
            requestedAlbum,
            pageRequest,
            totalPages,
            pageItems,
            firstNumber,
            totalCount
        )
        return Result(
            requestedAlbum, pageRequest, pageRequest, totalPages, firstNumber,
            pageItems.map { it.id }, image, totalCount, true, albumExists, availableAlbums
        )
    }

    private fun renderPage(
        storage: GalleryStorage,
        album: String,
        page: Int,
        totalPages: Int,
        items: List<GalleryStorage.Item>,
        firstNumber: Int,
        totalCount: Int
    ): String? {
        if (items.isEmpty()) return null
        val rows = ceil(items.size / COLUMNS.toDouble()).toInt()
        val width = OUTER_PADDING * 2 + COLUMNS * TILE_SIZE + (COLUMNS - 1) * TILE_GAP
        val height = HEADER_HEIGHT + OUTER_PADDING + rows * TILE_SIZE + max(0, rows - 1) * TILE_GAP + OUTER_PADDING
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textSize = 25f
            color = Color.rgb(35, 35, 35)
        }
        val lastNumber = firstNumber + items.size - 1
        canvas.drawText("头像·$album · 第$page/$totalPages 页 · $firstNumber-$lastNumber（共${totalCount}张）", OUTER_PADDING.toFloat(), 40f, paint)

        items.forEachIndexed { localIndex, item ->
            val row = localIndex / COLUMNS
            val column = localIndex % COLUMNS
            val left = OUTER_PADDING + column * (TILE_SIZE + TILE_GAP)
            val top = HEADER_HEIGHT + OUTER_PADDING + row * (TILE_SIZE + TILE_GAP)
            val tileRect = RectF(left.toFloat(), top.toFloat(), (left + TILE_SIZE).toFloat(), (top + TILE_SIZE).toFloat())
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(244, 244, 244)
            canvas.drawRoundRect(tileRect, 18f, 18f, paint)
            val decoded = drawImageCenterInside(canvas, storage.fileFor(item), tileRect)
            if (!decoded) {
                paint.color = Color.rgb(140, 140, 140)
                paint.textSize = 18f
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText("图片无法读取", tileRect.centerX(), tileRect.centerY(), paint)
                paint.textAlign = Paint.Align.LEFT
            }
            val number = firstNumber + localIndex
            val badgeRect = RectF(tileRect.left + 8f, tileRect.top + 8f, tileRect.left + 64f, tileRect.top + 49f)
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
        while (options.outWidth / sample > maxDecode || options.outHeight / sample > maxDecode) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: return false
        try {
            val available = RectF(target.left + 8f, target.top + 8f, target.right - 8f, target.bottom - 8f)
            val scale = minOf(available.width() / bitmap.width, available.height() / bitmap.height)
            val drawWidth = bitmap.width * scale
            val drawHeight = bitmap.height * scale
            val destination = RectF(
                available.centerX() - drawWidth / 2f,
                available.centerY() - drawHeight / 2f,
                available.centerX() + drawWidth / 2f,
                available.centerY() + drawHeight / 2f
            )
            canvas.drawBitmap(bitmap, null as Rect?, destination, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            return true
        } finally {
            bitmap.recycle()
        }
    }
}
