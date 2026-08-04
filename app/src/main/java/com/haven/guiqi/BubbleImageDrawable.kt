package com.haven.guiqi

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.LruCache
import java.io.File
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 共享解码缓存：同一张图片气泡只保留一份采样位图，不会为每条消息重复解码。 */
internal object BubbleImageBitmapCache {
    private val cache = object : LruCache<String, Bitmap>(16 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.byteCount / 1024).coerceAtLeast(1)
    }

    @Synchronized
    fun get(path: String): Bitmap? {
        if (path.isBlank()) return null
        val file = File(path)
        if (!file.isFile) return null
        val key = "${file.absolutePath}:${file.lastModified()}:${file.length()}"
        cache.get(key)?.takeIf { !it.isRecycled }?.let { return it }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_DECODE_EDGE) {
            sample *= 2
        }
        val bitmap = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        ) ?: return null
        cache.put(key, bitmap)
        return bitmap
    }

    @Synchronized
    fun remove(path: String) {
        val prefix = "${File(path).absolutePath}:"
        cache.snapshot().keys.filter { it.startsWith(prefix) }.forEach { cache.remove(it) }
    }

    private const val MAX_DECODE_EDGE = 1024
}


/** 自动保真模式分析出的伸缩十字与文字安全区，坐标均为源图像素。 */
internal data class SmartFrameGeometry(
    val stretchLeft: Int,
    val stretchTop: Int,
    val stretchRight: Int,
    val stretchBottom: Int,
    val safeLeft: Int,
    val safeTop: Int,
    val safeRight: Int,
    val safeBottom: Int
)

/**
 * 针对“完整装饰画框”素材的轻量分析器。
 *
 * 它不尝试理解图片内容，只在中央区域寻找变化最小的一条竖带和横带，之后
 * 只拉伸这两条安静带。像素小猫、药瓶、角花等落在其余区域里的内容会始终
 * 等比缩放，不会再被九宫格切开后单独压扁或拉长。
 */
internal object SmartFrameGeometryCache {
    private val cache = WeakHashMap<Bitmap, SmartFrameGeometry>()

    @Synchronized
    fun get(bitmap: Bitmap): SmartFrameGeometry = cache[bitmap] ?: analyze(bitmap).also {
        cache[bitmap] = it
    }

    /** 透明装饰 PNG 保留素材自己的外轮廓，不再被默认圆角矩形二次裁掉。 */
    fun hasTransparentOuterCorners(bitmap: Bitmap): Boolean {
        if (bitmap.width < 2 || bitmap.height < 2) return false
        val insetX = max(0, bitmap.width / 100)
        val insetY = max(0, bitmap.height / 100)
        val points = arrayOf(
            insetX to insetY,
            (bitmap.width - 1 - insetX) to insetY,
            insetX to (bitmap.height - 1 - insetY),
            (bitmap.width - 1 - insetX) to (bitmap.height - 1 - insetY)
        )
        return points.count { (x, y) -> Color.alpha(bitmap.getPixel(x, y)) < 32 } >= 3
    }

    private fun analyze(bitmap: Bitmap): SmartFrameGeometry {
        val width = bitmap.width.coerceAtLeast(2)
        val height = bitmap.height.coerceAtLeast(2)
        val verticalCenter = findQuietVerticalCenter(bitmap)
        val horizontalCenter = findQuietHorizontalCenter(bitmap)
        val halfVerticalBand = max(1, width / 180)
        val halfHorizontalBand = max(1, height / 180)
        val stretchLeft = (verticalCenter - halfVerticalBand).coerceIn(1, width - 2)
        val stretchRight = (verticalCenter + halfVerticalBand + 1)
            .coerceIn(stretchLeft + 1, width - 1)
        val stretchTop = (horizontalCenter - halfHorizontalBand).coerceIn(1, height - 2)
        val stretchBottom = (horizontalCenter + halfHorizontalBand + 1)
            .coerceIn(stretchTop + 1, height - 1)

        val fallbackHorizontalInset = (width * 0.14f).roundToInt()
        val fallbackVerticalInset = (height * 0.14f).roundToInt()
        val probeX = ((stretchLeft + stretchRight) / 2).coerceIn(0, width - 1)
        val probeY = ((stretchTop + stretchBottom) / 2).coerceIn(0, height - 1)
        val alphaThreshold = 28

        var safeLeft = findAlphaBoundary(bitmap, probeY, width / 2, -1, alphaThreshold)
            ?: fallbackHorizontalInset
        var safeRight = findAlphaBoundary(bitmap, probeY, width / 2, 1, alphaThreshold)
            ?: (width - fallbackHorizontalInset)
        var safeTop = findAlphaBoundaryVertical(bitmap, probeX, height / 2, -1, alphaThreshold)
            ?: fallbackVerticalInset
        var safeBottom = findAlphaBoundaryVertical(bitmap, probeX, height / 2, 1, alphaThreshold)
            ?: (height - fallbackVerticalInset)

        // 从外轮廓再向内退一点，避免文字贴住边框。像素素材按短边约 3% 留白。
        val innerMargin = max(2, (min(width, height) * 0.03f).roundToInt())
        safeLeft = (safeLeft + innerMargin).coerceIn(0, width - 2)
        safeRight = (safeRight - innerMargin).coerceIn(safeLeft + 1, width)
        safeTop = (safeTop + innerMargin).coerceIn(0, height - 2)
        safeBottom = (safeBottom - innerMargin).coerceIn(safeTop + 1, height)

        // 极端素材可能没有可识别的透明外沿；确保安全区不会窄到不可用。
        if (safeRight - safeLeft < width * 0.32f) {
            safeLeft = fallbackHorizontalInset
            safeRight = width - fallbackHorizontalInset
        }
        if (safeBottom - safeTop < height * 0.28f) {
            safeTop = fallbackVerticalInset
            safeBottom = height - fallbackVerticalInset
        }

        return SmartFrameGeometry(
            stretchLeft = stretchLeft,
            stretchTop = stretchTop,
            stretchRight = stretchRight,
            stretchBottom = stretchBottom,
            safeLeft = safeLeft,
            safeTop = safeTop,
            safeRight = safeRight,
            safeBottom = safeBottom
        )
    }

    private fun findQuietVerticalCenter(bitmap: Bitmap): Int {
        val width = bitmap.width
        val height = bitmap.height
        val start = (width * 0.32f).roundToInt().coerceIn(1, width - 2)
        val end = (width * 0.68f).roundToInt().coerceIn(start, width - 2)
        val xStep = max(1, width / 160)
        val yStep = max(1, height / 120)
        var bestX = width / 2
        var bestScore = Long.MAX_VALUE
        var x = start
        while (x <= end) {
            var score = 0L
            var samples = 0
            var y = 0
            while (y < height) {
                val left = bitmap.getPixel((x - 1).coerceAtLeast(0), y)
                val center = bitmap.getPixel(x, y)
                val right = bitmap.getPixel((x + 1).coerceAtMost(width - 1), y)
                score += colorDistance(left, right)
                val alpha = Color.alpha(center)
                if (alpha < 72) score += (72 - alpha) * 10L
                samples += 1
                y += yStep
            }
            val normalized = if (samples == 0) score else score / samples
            val centeredScore = normalized + abs(x - width / 2).toLong()
            if (centeredScore < bestScore) {
                bestScore = centeredScore
                bestX = x
            }
            x += xStep
        }
        return bestX
    }

    private fun findQuietHorizontalCenter(bitmap: Bitmap): Int {
        val width = bitmap.width
        val height = bitmap.height
        val start = (height * 0.32f).roundToInt().coerceIn(1, height - 2)
        val end = (height * 0.68f).roundToInt().coerceIn(start, height - 2)
        val yStep = max(1, height / 160)
        val xStep = max(1, width / 120)
        var bestY = height / 2
        var bestScore = Long.MAX_VALUE
        var y = start
        while (y <= end) {
            var score = 0L
            var samples = 0
            var x = 0
            while (x < width) {
                val top = bitmap.getPixel(x, (y - 1).coerceAtLeast(0))
                val center = bitmap.getPixel(x, y)
                val bottom = bitmap.getPixel(x, (y + 1).coerceAtMost(height - 1))
                score += colorDistance(top, bottom)
                val alpha = Color.alpha(center)
                if (alpha < 72) score += (72 - alpha) * 10L
                samples += 1
                x += xStep
            }
            val normalized = if (samples == 0) score else score / samples
            val centeredScore = normalized + abs(y - height / 2).toLong()
            if (centeredScore < bestScore) {
                bestScore = centeredScore
                bestY = y
            }
            y += yStep
        }
        return bestY
    }

    private fun colorDistance(a: Int, b: Int): Long =
        abs(Color.alpha(a) - Color.alpha(b)).toLong() * 2L +
            abs(Color.red(a) - Color.red(b)).toLong() +
            abs(Color.green(a) - Color.green(b)).toLong() +
            abs(Color.blue(a) - Color.blue(b)).toLong()

    private fun findAlphaBoundary(
        bitmap: Bitmap,
        y: Int,
        startX: Int,
        direction: Int,
        alphaThreshold: Int
    ): Int? {
        if (Color.alpha(bitmap.getPixel(startX, y)) < alphaThreshold) return null
        var transparentRun = 0
        var x = startX
        while (x in 0 until bitmap.width) {
            if (Color.alpha(bitmap.getPixel(x, y)) < alphaThreshold) {
                transparentRun += 1
                if (transparentRun >= 3) {
                    return if (direction < 0) x + transparentRun else x - transparentRun + 1
                }
            } else {
                transparentRun = 0
            }
            x += direction
        }
        return null
    }

    private fun findAlphaBoundaryVertical(
        bitmap: Bitmap,
        x: Int,
        startY: Int,
        direction: Int,
        alphaThreshold: Int
    ): Int? {
        if (Color.alpha(bitmap.getPixel(x, startY)) < alphaThreshold) return null
        var transparentRun = 0
        var y = startY
        while (y in 0 until bitmap.height) {
            if (Color.alpha(bitmap.getPixel(x, y)) < alphaThreshold) {
                transparentRun += 1
                if (transparentRun >= 3) {
                    return if (direction < 0) y + transparentRun else y - transparentRun + 1
                }
            } else {
                transparentRun = 0
            }
            y += direction
        }
        return null
    }
}

/** 自动保真画框的绘制与源图坐标映射。 */
internal object SmartFrameBubbleRenderer {

    /**
     * 保真画框的“自然尺寸”。同一张素材无论消息长短都先按这个固定尺寸
     * 显示装饰部分，超出的宽高只交给中央伸缩带承担。
     */
    private const val REFERENCE_MAX_EDGE_DP = 96f

    data class Insets(val left: Float, val top: Float, val right: Float, val bottom: Float)
    data class NaturalSize(val widthPx: Int, val heightPx: Int)

    fun naturalSizePx(bitmap: Bitmap, density: Float): NaturalSize {
        val scale = referenceScale(bitmap, density)
        return NaturalSize(
            widthPx = (bitmap.width * scale).roundToInt().coerceAtLeast(1),
            heightPx = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        )
    }

    fun draw(
        canvas: Canvas,
        bitmap: Bitmap,
        destination: RectF,
        density: Float,
        paint: Paint,
        geometry: SmartFrameGeometry = SmartFrameGeometryCache.get(bitmap),
        sourceScratch: Rect = Rect(),
        destinationScratch: RectF = RectF()
    ) {
        if (destination.width() <= 0f || destination.height() <= 0f) return
        val scale = resolvedScale(bitmap, destination, geometry, density)
        val destinationLeft = destination.left + geometry.stretchLeft * scale
        val destinationRight = destination.right - (bitmap.width - geometry.stretchRight) * scale
        val destinationTop = destination.top + geometry.stretchTop * scale
        val destinationBottom = destination.bottom - (bitmap.height - geometry.stretchBottom) * scale

        fun patch(
            srcLeft: Int,
            srcTop: Int,
            srcRight: Int,
            srcBottom: Int,
            dstLeft: Float,
            dstTop: Float,
            dstRight: Float,
            dstBottom: Float
        ) {
            if (srcRight <= srcLeft || srcBottom <= srcTop || dstRight <= dstLeft || dstBottom <= dstTop) return
            sourceScratch.set(srcLeft, srcTop, srcRight, srcBottom)
            destinationScratch.set(dstLeft, dstTop, dstRight, dstBottom)
            canvas.drawBitmap(bitmap, sourceScratch, destinationScratch, paint)
        }

        val sx0 = geometry.stretchLeft
        val sx1 = geometry.stretchRight
        val sy0 = geometry.stretchTop
        val sy1 = geometry.stretchBottom

        patch(0, 0, sx0, sy0, destination.left, destination.top, destinationLeft, destinationTop)
        patch(sx0, 0, sx1, sy0, destinationLeft, destination.top, destinationRight, destinationTop)
        patch(sx1, 0, bitmap.width, sy0, destinationRight, destination.top, destination.right, destinationTop)

        patch(0, sy0, sx0, sy1, destination.left, destinationTop, destinationLeft, destinationBottom)
        patch(sx0, sy0, sx1, sy1, destinationLeft, destinationTop, destinationRight, destinationBottom)
        patch(sx1, sy0, bitmap.width, sy1, destinationRight, destinationTop, destination.right, destinationBottom)

        patch(0, sy1, sx0, bitmap.height, destination.left, destinationBottom, destinationLeft, destination.bottom)
        patch(sx0, sy1, sx1, bitmap.height, destinationLeft, destinationBottom, destinationRight, destination.bottom)
        patch(sx1, sy1, bitmap.width, bitmap.height, destinationRight, destinationBottom, destination.right, destination.bottom)
    }

    fun contentInsets(
        bitmap: Bitmap,
        destination: RectF,
        density: Float,
        geometry: SmartFrameGeometry = SmartFrameGeometryCache.get(bitmap)
    ): Insets {
        val scale = resolvedScale(bitmap, destination, geometry, density)
        val safeLeft = mapAxis(
            geometry.safeLeft,
            geometry.stretchLeft,
            geometry.stretchRight,
            bitmap.width,
            destination.left,
            destination.right,
            scale
        )
        val safeRight = mapAxis(
            geometry.safeRight,
            geometry.stretchLeft,
            geometry.stretchRight,
            bitmap.width,
            destination.left,
            destination.right,
            scale
        )
        val safeTop = mapAxis(
            geometry.safeTop,
            geometry.stretchTop,
            geometry.stretchBottom,
            bitmap.height,
            destination.top,
            destination.bottom,
            scale
        )
        val safeBottom = mapAxis(
            geometry.safeBottom,
            geometry.stretchTop,
            geometry.stretchBottom,
            bitmap.height,
            destination.top,
            destination.bottom,
            scale
        )
        return Insets(
            left = (safeLeft - destination.left).coerceAtLeast(0f),
            top = (safeTop - destination.top).coerceAtLeast(0f),
            right = (destination.right - safeRight).coerceAtLeast(0f),
            bottom = (destination.bottom - safeBottom).coerceAtLeast(0f)
        )
    }

    fun stretchDestinationRect(
        bitmap: Bitmap,
        destination: RectF,
        density: Float,
        geometry: SmartFrameGeometry = SmartFrameGeometryCache.get(bitmap)
    ): RectF {
        val scale = resolvedScale(bitmap, destination, geometry, density)
        return RectF(
            destination.left + geometry.stretchLeft * scale,
            destination.top + geometry.stretchTop * scale,
            destination.right - (bitmap.width - geometry.stretchRight) * scale,
            destination.bottom - (bitmap.height - geometry.stretchBottom) * scale
        )
    }

    private fun referenceScale(bitmap: Bitmap, density: Float): Float {
        val maxSourceEdge = max(bitmap.width, bitmap.height).coerceAtLeast(1)
        return (REFERENCE_MAX_EDGE_DP * density.coerceAtLeast(0.1f) / maxSourceEdge.toFloat())
            .coerceAtLeast(0.0001f)
    }

    /**
     * 固定采用自然尺寸的缩放比例；只有目标框连固定装饰边都容不下时才临时缩小。
     * 这样短消息、长消息的猫、瓶子和角花会保持同样大小，不再随气泡整体放大。
     */
    private fun resolvedScale(
        bitmap: Bitmap,
        destination: RectF,
        geometry: SmartFrameGeometry,
        density: Float
    ): Float {
        val reference = referenceScale(bitmap, density)
        val fixedSourceWidth = (
            geometry.stretchLeft + (bitmap.width - geometry.stretchRight)
            ).coerceAtLeast(1)
        val fixedSourceHeight = (
            geometry.stretchTop + (bitmap.height - geometry.stretchBottom)
            ).coerceAtLeast(1)
        val fitWidth = destination.width() / fixedSourceWidth.toFloat()
        val fitHeight = destination.height() / fixedSourceHeight.toFloat()
        return min(reference, min(fitWidth, fitHeight)).coerceAtLeast(0.0001f)
    }

    private fun mapAxis(
        source: Int,
        stretchStart: Int,
        stretchEnd: Int,
        sourceSize: Int,
        destinationStart: Float,
        destinationEnd: Float,
        scale: Float
    ): Float {
        val destinationStretchStart = destinationStart + stretchStart * scale
        val destinationStretchEnd = destinationEnd - (sourceSize - stretchEnd) * scale
        return when {
            source <= stretchStart -> destinationStart + source * scale
            source >= stretchEnd -> destinationEnd - (sourceSize - source) * scale
            else -> {
                val fraction = (source - stretchStart).toFloat() /
                    (stretchEnd - stretchStart).coerceAtLeast(1).toFloat()
                destinationStretchStart +
                    (destinationStretchEnd - destinationStretchStart) * fraction
            }
        }
    }
}

internal class SmartFrameBubbleDrawable(
    private val bitmap: Bitmap,
    private val density: Float,
    cornerRadiiPx: FloatArray
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val destination = RectF()
    private val sourceScratch = Rect()
    private val destinationScratch = RectF()
    private val clipPath = Path()
    private val cornerRadii = cornerRadiiPx.copyOf(8)
    private val geometry = SmartFrameGeometryCache.get(bitmap)
    private val preserveSourceSilhouette = SmartFrameGeometryCache.hasTransparentOuterCorners(bitmap)

    override fun draw(canvas: Canvas) {
        destination.set(bounds)
        val saveCount = canvas.save()
        if (!preserveSourceSilhouette) {
            clipPath.reset()
            clipPath.addRoundRect(destination, cornerRadii, Path.Direction.CW)
            canvas.clipPath(clipPath)
        }
        SmartFrameBubbleRenderer.draw(
            canvas = canvas,
            bitmap = bitmap,
            destination = destination,
            density = density,
            paint = paint,
            geometry = geometry,
            sourceScratch = sourceScratch,
            destinationScratch = destinationScratch
        )
        canvas.restoreToCount(saveCount)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getOutline(outline: Outline) {
        val outlineRadius = cornerRadii.maxOrNull()?.coerceAtLeast(0f) ?: 0f
        outline.setRoundRect(bounds, outlineRadius)
        outline.alpha = paint.alpha / 255f
    }
}

/**
 * 普通 PNG/WebP/JPEG 的九宫格绘制器。
 *
 * 四个百分比表示源图四边保持不被拉伸的区域；中间矩形承担伸缩。
 * 目标侧的固定边缘使用 96dp 参考画布映射，避免长消息把角花一起拉宽。
 */
internal object NineSliceBubbleRenderer {
    private const val REFERENCE_SIZE_DP = 96f

    fun draw(
        canvas: Canvas,
        bitmap: Bitmap,
        destination: RectF,
        density: Float,
        leftPercent: Int,
        topPercent: Int,
        rightPercent: Int,
        bottomPercent: Int,
        paint: Paint,
        sourceScratch: Rect = Rect(),
        destinationScratch: RectF = RectF()
    ) {
        if (destination.width() <= 0f || destination.height() <= 0f) return

        val left = leftPercent.coerceIn(0, 45)
        val top = topPercent.coerceIn(0, 45)
        val right = rightPercent.coerceIn(0, 45)
        val bottom = bottomPercent.coerceIn(0, 45)

        val sourceLeft = (bitmap.width * left / 100f).roundToInt()
            .coerceIn(0, bitmap.width - 1)
        val sourceTop = (bitmap.height * top / 100f).roundToInt()
            .coerceIn(0, bitmap.height - 1)
        val sourceRight = (bitmap.width - bitmap.width * right / 100f).roundToInt()
            .coerceIn(sourceLeft + 1, bitmap.width)
        val sourceBottom = (bitmap.height - bitmap.height * bottom / 100f).roundToInt()
            .coerceIn(sourceTop + 1, bitmap.height)

        val referencePx = REFERENCE_SIZE_DP * density
        val destinationLeft = destination.left + min(
            destination.width() * 0.45f,
            referencePx * left / 100f
        )
        val destinationRight = destination.right - min(
            destination.width() * 0.45f,
            referencePx * right / 100f
        )
        val destinationTop = destination.top + min(
            destination.height() * 0.45f,
            referencePx * top / 100f
        )
        val destinationBottom = destination.bottom - min(
            destination.height() * 0.45f,
            referencePx * bottom / 100f
        )

        fun patch(
            srcLeft: Int,
            srcTop: Int,
            srcRight: Int,
            srcBottom: Int,
            dstLeft: Float,
            dstTop: Float,
            dstRight: Float,
            dstBottom: Float
        ) {
            if (srcRight <= srcLeft || srcBottom <= srcTop || dstRight <= dstLeft || dstBottom <= dstTop) {
                return
            }
            sourceScratch.set(srcLeft, srcTop, srcRight, srcBottom)
            destinationScratch.set(dstLeft, dstTop, dstRight, dstBottom)
            canvas.drawBitmap(bitmap, sourceScratch, destinationScratch, paint)
        }

        patch(0, 0, sourceLeft, sourceTop, destination.left, destination.top, destinationLeft, destinationTop)
        patch(sourceLeft, 0, sourceRight, sourceTop, destinationLeft, destination.top, destinationRight, destinationTop)
        patch(sourceRight, 0, bitmap.width, sourceTop, destinationRight, destination.top, destination.right, destinationTop)

        patch(0, sourceTop, sourceLeft, sourceBottom, destination.left, destinationTop, destinationLeft, destinationBottom)
        patch(sourceLeft, sourceTop, sourceRight, sourceBottom, destinationLeft, destinationTop, destinationRight, destinationBottom)
        patch(sourceRight, sourceTop, bitmap.width, sourceBottom, destinationRight, destinationTop, destination.right, destinationBottom)

        patch(0, sourceBottom, sourceLeft, bitmap.height, destination.left, destinationBottom, destinationLeft, destination.bottom)
        patch(sourceLeft, sourceBottom, sourceRight, bitmap.height, destinationLeft, destinationBottom, destinationRight, destination.bottom)
        patch(sourceRight, sourceBottom, bitmap.width, bitmap.height, destinationRight, destinationBottom, destination.right, destination.bottom)
    }
}

internal class NineSliceBubbleDrawable(
    private val bitmap: Bitmap,
    private val density: Float,
    private val leftPercent: Int,
    private val topPercent: Int,
    private val rightPercent: Int,
    private val bottomPercent: Int,
    cornerRadiiPx: FloatArray
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val destination = RectF()
    private val sourceScratch = Rect()
    private val destinationScratch = RectF()
    private val clipPath = Path()
    private val cornerRadii = cornerRadiiPx.copyOf(8)

    override fun draw(canvas: Canvas) {
        destination.set(bounds)
        clipPath.reset()
        clipPath.addRoundRect(destination, cornerRadii, Path.Direction.CW)
        val saveCount = canvas.save()
        canvas.clipPath(clipPath)
        NineSliceBubbleRenderer.draw(
            canvas = canvas,
            bitmap = bitmap,
            destination = destination,
            density = density,
            leftPercent = leftPercent,
            topPercent = topPercent,
            rightPercent = rightPercent,
            bottomPercent = bottomPercent,
            paint = paint,
            sourceScratch = sourceScratch,
            destinationScratch = destinationScratch
        )
        canvas.restoreToCount(saveCount)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getOutline(outline: Outline) {
        // Android 的 Outline 只支持统一圆角；阴影用最大圆角近似，真正的非对称
        // 圆角已经在 draw() 中通过 Path 精确裁切。
        val outlineRadius = cornerRadii.maxOrNull()?.coerceAtLeast(0f) ?: 0f
        outline.setRoundRect(bounds, outlineRadius)
        outline.alpha = paint.alpha / 255f
    }
}
