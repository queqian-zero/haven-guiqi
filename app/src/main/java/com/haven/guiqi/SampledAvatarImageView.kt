package com.haven.guiqi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.util.AttributeSet
import android.util.LruCache
import android.view.View
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * 头像专用图片 View：后台按目标尺寸采样，绘制时始终中心裁切为正方形。
 * 避免把相册里的 9:16 大图原尺寸解码到主线程，也不依赖图片自身宽高参与布局。
 */
class SampledAvatarImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val clipPath = Path()
    private var bitmap: Bitmap? = null
    private var avatarShape = ChatAppearanceStorage.AvatarShape.CIRCLE
    private var requestGeneration = 0

    fun setAvatarFile(
        file: File,
        targetSizePx: Int,
        shape: ChatAppearanceStorage.AvatarShape
    ) {
        avatarShape = shape
        requestGeneration += 1
        val generation = requestGeneration
        bitmap = null
        invalidate()

        AvatarBitmapCache.load(file, targetSizePx.coerceAtLeast(1)) { loaded ->
            if (generation != requestGeneration) return@load
            bitmap = loaded
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val image = bitmap ?: return
        if (width <= 0 || height <= 0 || image.width <= 0 || image.height <= 0) return

        clipPath.reset()
        when (avatarShape) {
            ChatAppearanceStorage.AvatarShape.CIRCLE ->
                clipPath.addOval(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
            ChatAppearanceStorage.AvatarShape.SQUARE ->
                clipPath.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        }

        val source = centerCropSource(image.width, image.height, width, height)
        val destination = Rect(0, 0, width, height)
        val saveCount = canvas.save()
        canvas.clipPath(clipPath)
        canvas.drawBitmap(image, source, destination, bitmapPaint)
        canvas.restoreToCount(saveCount)
    }

    private fun centerCropSource(
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int
    ): Rect {
        val sourceRatio = sourceWidth.toFloat() / sourceHeight
        val targetRatio = targetWidth.toFloat() / targetHeight
        return if (sourceRatio > targetRatio) {
            val cropWidth = (sourceHeight * targetRatio).toInt().coerceAtLeast(1)
            val left = ((sourceWidth - cropWidth) / 2).coerceAtLeast(0)
            Rect(left, 0, (left + cropWidth).coerceAtMost(sourceWidth), sourceHeight)
        } else {
            val cropHeight = (sourceWidth / targetRatio).toInt().coerceAtLeast(1)
            val top = ((sourceHeight - cropHeight) / 2).coerceAtLeast(0)
            Rect(0, top, sourceWidth, (top + cropHeight).coerceAtMost(sourceHeight))
        }
    }

    private object AvatarBitmapCache {
        private val executor = Executors.newFixedThreadPool(2)
        private val cache = object : LruCache<String, Bitmap>(cacheSizeKb()) {
            override fun sizeOf(key: String, value: Bitmap): Int =
                (value.byteCount / 1024).coerceAtLeast(1)
        }

        fun load(file: File, targetSizePx: Int, callback: (Bitmap?) -> Unit) {
            val key = "${file.absolutePath}:${file.lastModified()}:$targetSizePx"
            cache.get(key)?.takeIf { !it.isRecycled }?.let {
                callback(it)
                return
            }

            executor.execute {
                val decoded = decodeSampled(file, targetSizePx)
                if (decoded != null) cache.put(key, decoded)
                callbackOnMain(callback, decoded)
            }
        }

        private fun callbackOnMain(callback: (Bitmap?) -> Unit, bitmap: Bitmap?) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                callback(bitmap)
            }
        }

        private fun decodeSampled(file: File, targetSizePx: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val decodeTarget = max(targetSizePx * 2, 128)
            var sample = 1
            while (
                bounds.outWidth / (sample * 2) >= decodeTarget &&
                bounds.outHeight / (sample * 2) >= decodeTarget
            ) {
                sample *= 2
            }

            return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        }

        private fun cacheSizeKb(): Int {
            val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024L).toInt()
            return (maxMemoryKb / 16).coerceAtLeast(4 * 1024)
        }
    }
}
