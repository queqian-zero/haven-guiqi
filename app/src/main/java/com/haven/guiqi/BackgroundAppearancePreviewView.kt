package com.haven.guiqi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * 聊天背景编辑器中的轻量预览。
 *
 * 模糊位图在后台生成；缩放、位置和遮罩只重绘，不会在拖动时反复解码图片。
 */
class BackgroundAppearancePreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onTransformDragged: ((ChatAppearanceStorage.BackgroundTransform) -> Unit)? = null

    private val storage = ChatAppearanceStorage(context.applicationContext)
    private var executor = newExecutor()
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var generation = 0
    private var sourceFile: File? = null
    private var blurRadius = ChatAppearanceStorage.DEFAULT_BLUR_RADIUS
    private var overlayPercent = ChatAppearanceStorage.DEFAULT_OVERLAY_PERCENT
    private var transform = ChatAppearanceStorage.BackgroundTransform()
    private var bitmap: Bitmap? = null

    private var downX = 0f
    private var downY = 0f
    private var dragStartTransform = transform
    private var hasDragged = false
    private val delayedBlurReload = Runnable { sourceFile?.let(::scheduleBitmapLoad) }

    init {
        isClickable = true
        placeholderPaint.color = ThemeHelper.getColors(context).backgroundSecondary
    }

    fun setAppearance(
        file: File?,
        effects: ChatAppearanceStorage.BackgroundEffects,
        transform: ChatAppearanceStorage.BackgroundTransform
    ) {
        val fileChanged = sourceFile?.absolutePath != file?.absolutePath ||
            sourceFile?.lastModified() != file?.lastModified()
        val blurChanged = blurRadius != effects.blurRadius
        sourceFile = file
        blurRadius = effects.blurRadius
        overlayPercent = effects.overlayPercent
        this.transform = transform
        overlayPaint.color = storage.overlayColor(overlayPercent)

        if (file == null) {
            generation++
            replaceBitmap(null)
            invalidate()
        } else if (fileChanged || blurChanged || bitmap == null) {
            scheduleBitmapLoad(file)
        } else {
            invalidate()
        }
    }

    fun updateTransform(transform: ChatAppearanceStorage.BackgroundTransform) {
        this.transform = transform
        invalidate()
    }

    fun updateOverlay(overlayPercent: Int) {
        this.overlayPercent = overlayPercent
        overlayPaint.color = storage.overlayColor(overlayPercent)
        invalidate()
    }

    fun updateBlur(blurRadius: Int) {
        if (this.blurRadius == blurRadius) return
        this.blurRadius = blurRadius
        removeCallbacks(delayedBlurReload)
        postDelayed(delayedBlurReload, BLUR_PREVIEW_DEBOUNCE_MS)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0 && (oldw != w || oldh != h)) {
            sourceFile?.let(::scheduleBitmapLoad)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val image = bitmap
        if (image == null || image.isRecycled) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), placeholderPaint)
            return
        }

        val destination = ChatAppearanceStorage.calculateBackgroundDestination(
            imageWidth = image.width,
            imageHeight = image.height,
            targetLeft = 0f,
            targetTop = 0f,
            targetWidth = width.toFloat(),
            targetHeight = height.toFloat(),
            transform = transform
        )
        canvas.drawBitmap(image, null, destination, bitmapPaint)
        if (Color.alpha(overlayPaint.color) > 0) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), overlayPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val image = bitmap ?: return super.onTouchEvent(event)
        if (image.isRecycled || width <= 0 || height <= 0) return super.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = event.x
                downY = event.y
                dragStartTransform = transform
                hasDragged = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (!hasDragged && (abs(dx) > touchSlop || abs(dy) > touchSlop)) hasDragged = true

                val baseDestination = ChatAppearanceStorage.calculateBackgroundDestination(
                    imageWidth = image.width,
                    imageHeight = image.height,
                    targetLeft = 0f,
                    targetTop = 0f,
                    targetWidth = width.toFloat(),
                    targetHeight = height.toFloat(),
                    transform = dragStartTransform.copy(offsetXPercent = 0, offsetYPercent = 0)
                )
                val overflowX = (baseDestination.width() - width).coerceAtLeast(0f)
                val overflowY = (baseDestination.height() - height).coerceAtLeast(0f)
                val deltaXPercent = if (overflowX > 1f) (dx / (overflowX / 2f) * 100f).toInt() else 0
                val deltaYPercent = if (overflowY > 1f) (dy / (overflowY / 2f) * 100f).toInt() else 0
                transform = dragStartTransform.copy(
                    offsetXPercent = (dragStartTransform.offsetXPercent + deltaXPercent).coerceIn(
                        ChatAppearanceStorage.MIN_BACKGROUND_OFFSET_PERCENT,
                        ChatAppearanceStorage.MAX_BACKGROUND_OFFSET_PERCENT
                    ),
                    offsetYPercent = (dragStartTransform.offsetYPercent + deltaYPercent).coerceIn(
                        ChatAppearanceStorage.MIN_BACKGROUND_OFFSET_PERCENT,
                        ChatAppearanceStorage.MAX_BACKGROUND_OFFSET_PERCENT
                    )
                )
                onTransformDragged?.invoke(transform)
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (!hasDragged) performClick()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (executor.isShutdown) executor = newExecutor()
        sourceFile?.let(::scheduleBitmapLoad)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(delayedBlurReload)
        generation++
        executor.shutdownNow()
        replaceBitmap(null)
        super.onDetachedFromWindow()
    }

    private fun scheduleBitmapLoad(file: File) {
        if (width <= 0 || height <= 0 || !file.isFile) return
        if (executor.isShutdown) executor = newExecutor()
        val requestGeneration = ++generation
        val requestBlur = blurRadius
        val requestPath = file.absolutePath
        val targetWidth = width
        val targetHeight = height
        executor.execute {
            val loaded = storage.loadBackgroundBitmap(
                file = file,
                targetWidth = targetWidth,
                targetHeight = targetHeight,
                blurRadius = requestBlur
            )
            post {
                val stillCurrent = requestGeneration == generation &&
                    sourceFile?.absolutePath == requestPath &&
                    blurRadius == requestBlur
                if (stillCurrent && isAttachedToWindow) {
                    replaceBitmap(loaded)
                    invalidate()
                } else {
                    loaded?.takeIf { !it.isRecycled }?.recycle()
                }
            }
        }
    }

    private fun replaceBitmap(newBitmap: Bitmap?) {
        val old = bitmap
        bitmap = newBitmap
        if (old !== newBitmap && old != null && !old.isRecycled) old.recycle()
    }

    companion object {
        private const val touchSlop = 6f
        private const val BLUR_PREVIEW_DEBOUNCE_MS = 120L

        private fun newExecutor() = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "guiqi-background-preview").apply { isDaemon = true }
        }
    }
}
