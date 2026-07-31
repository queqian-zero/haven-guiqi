package com.haven.guiqi

import android.content.Context
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Movie
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Animatable
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.util.LruCache
import android.view.View
import androidx.appcompat.widget.AppCompatImageView
import java.io.File
import java.util.concurrent.Executors

/**
 * 能在 Android 8+ 显示动态头像框的轻量 ImageView。
 *
 * Android 9+ 优先使用 AnimatedImageDrawable，并强制无限循环；Android 8 使用 Movie
 * 手动按时间取模播放。普通 PNG/JPG/WebP 仍交给系统 ImageView。
 */
class AnimatedAssetImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {

    private var loadGeneration = 0
    private var animatedDrawable: Animatable? = null
    private var movie: Movie? = null
    private var movieBytes: ByteArray? = null
    private var movieStartAt = 0L
    private var shouldAnimateMovie = false
    private var aggregatedVisible = true

    fun setAssetFile(file: File?) {
        val generation = ++loadGeneration
        clearCurrentAnimation()
        setImageDrawable(null)
        movie = null
        movieBytes = null
        movieStartAt = 0L

        if (file == null || !file.isFile) return
        if (!file.extension.equals("gif", ignoreCase = true)) {
            setLayerType(View.LAYER_TYPE_NONE, null)
            setImageURI(Uri.fromFile(file))
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            loadAnimatedDrawable(file, generation)
        } else {
            loadMovie(file, generation)
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.P)
    private fun loadAnimatedDrawable(file: File, generation: Int) {
        setLayerType(View.LAYER_TYPE_NONE, null)
        decoderExecutor.execute {
            try {
                val decoded = ImageDecoder.decodeDrawable(ImageDecoder.createSource(file))
                post {
                    if (generation != loadGeneration) return@post
                    setImageDrawable(decoded)
                    val animated = decoded as? AnimatedImageDrawable
                    animated?.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                    animatedDrawable = animated
                    updatePlaybackState()
                }
            } catch (_: Exception) {
                post {
                    if (generation == loadGeneration) setImageURI(Uri.fromFile(file))
                }
            }
        }
    }

    private fun loadMovie(file: File, generation: Int) {
        // Movie 在部分 Android 8 设备的硬件画布上不会正确刷新透明 GIF。
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        decoderExecutor.execute {
            try {
                val bytes = gifBytes(file)
                val decoded = Movie.decodeByteArray(bytes, 0, bytes.size)
                    ?: error("GIF 无法解码")
                post {
                    if (generation != loadGeneration) return@post
                    setImageDrawable(null)
                    movieBytes = bytes // Movie 依赖原始字节，保留引用避免底层数据被回收。
                    movie = decoded
                    movieStartAt = SystemClock.uptimeMillis()
                    updatePlaybackState()
                }
            } catch (_: Exception) {
                post {
                    if (generation == loadGeneration) setImageURI(Uri.fromFile(file))
                }
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (movieStartAt == 0L) movieStartAt = SystemClock.uptimeMillis()
        updatePlaybackState()
    }

    override fun onDetachedFromWindow() {
        animatedDrawable?.stop()
        shouldAnimateMovie = false
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        aggregatedVisible = isVisible
        updatePlaybackState()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updatePlaybackState()
    }

    override fun onDraw(canvas: Canvas) {
        val currentMovie = movie
        if (currentMovie == null) {
            super.onDraw(canvas)
            return
        }

        val duration = currentMovie.duration().takeIf { it > 0 } ?: DEFAULT_GIF_DURATION_MS
        val elapsed = (SystemClock.uptimeMillis() - movieStartAt).coerceAtLeast(0L)
        // 不读取 GIF 文件里的“播放一次”标志，始终由 View 自己按时长取模循环。
        currentMovie.setTime((elapsed % duration).toInt())

        val movieWidth = currentMovie.width().coerceAtLeast(1)
        val movieHeight = currentMovie.height().coerceAtLeast(1)
        val sx = width.toFloat() / movieWidth.toFloat()
        val sy = height.toFloat() / movieHeight.toFloat()
        val saveCount = canvas.save()
        canvas.scale(sx, sy)
        currentMovie.draw(canvas, 0f, 0f)
        canvas.restoreToCount(saveCount)

        if (shouldAnimateMovie) postInvalidateOnAnimation()
    }

    private fun updatePlaybackState() {
        val shouldRun = isAttachedToWindow &&
            aggregatedVisible &&
            visibility == View.VISIBLE &&
            windowVisibility == View.VISIBLE

        animatedDrawable?.let { drawable ->
            if (shouldRun) {
                if (!drawable.isRunning) drawable.start()
            } else if (drawable.isRunning) {
                drawable.stop()
            }
        }

        shouldAnimateMovie = shouldRun && movie != null
        if (shouldAnimateMovie) {
            if (movieStartAt == 0L) movieStartAt = SystemClock.uptimeMillis()
            invalidate()
        }
    }

    private fun clearCurrentAnimation() {
        animatedDrawable?.stop()
        animatedDrawable = null
    }

    companion object {
        private const val DEFAULT_GIF_DURATION_MS = 1000
        private val decoderExecutor = Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "guiqi-animated-asset").apply { isDaemon = true }
        }
        private val byteCache = object : LruCache<String, ByteArray>(8 * 1024 * 1024) {
            override fun sizeOf(key: String, value: ByteArray): Int = value.size
        }

        private fun gifBytes(file: File): ByteArray {
            val cacheKey = "${file.absolutePath}:${file.lastModified()}:${file.length()}"
            val cached = synchronized(byteCache) { byteCache.get(cacheKey) }
            if (cached != null) return cached
            val bytes = file.readBytes()
            synchronized(byteCache) { byteCache.put(cacheKey, bytes) }
            return bytes
        }
    }
}
