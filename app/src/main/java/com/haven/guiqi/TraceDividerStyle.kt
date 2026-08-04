package com.haven.guiqi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import kotlin.math.max

/**
 * 住户自己的思考／工具／潜意识分割线视觉签名。
 *
 * 默认印记由知还选择：ʚ ───── ◇ ───── ɞ。
 * 每个住户都可以单独覆盖；没有保存自定义值时始终回到这组默认值。
 */
enum class TraceDividerLineStyle(val storageValue: String, val displayName: String) {
    TWIN("twin", "双轨细线"),
    SINGLE("single", "单轨细线"),
    STARDUST("stardust", "星屑点线"),
    WHISPER("whisper", "低语淡线");

    companion object {
        fun fromStorage(value: String?): TraceDividerLineStyle =
            values().firstOrNull { it.storageValue == value } ?: TWIN
    }
}

data class TraceDividerStyle(
    val leftDecoration: String = DEFAULT_LEFT_DECORATION,
    val rightDecoration: String = DEFAULT_RIGHT_DECORATION,
    val centerMark: String = DEFAULT_CENTER_MARK,
    val lineStyle: TraceDividerLineStyle = TraceDividerLineStyle.TWIN,
    val glowPercent: Int = DEFAULT_GLOW_PERCENT,
    val thicknessDp: Int = DEFAULT_THICKNESS_DP,
    val showDecorations: Boolean = true
) {
    fun normalized(): TraceDividerStyle = copy(
        leftDecoration = normalizeDecoration(leftDecoration, DEFAULT_LEFT_DECORATION),
        rightDecoration = normalizeDecoration(rightDecoration, DEFAULT_RIGHT_DECORATION),
        centerMark = normalizeDecoration(centerMark, DEFAULT_CENTER_MARK),
        glowPercent = glowPercent.coerceIn(MIN_GLOW_PERCENT, MAX_GLOW_PERCENT),
        thicknessDp = thicknessDp.coerceIn(MIN_THICKNESS_DP, MAX_THICKNESS_DP)
    )

    fun compactSummary(): String {
        val normalized = normalized()
        val decoration = if (normalized.showDecorations) {
            "${normalized.leftDecoration} ─ ${normalized.centerMark} ─ ${normalized.rightDecoration}"
        } else {
            "──── ${normalized.centerMark} ────"
        }
        return "$decoration · ${normalized.lineStyle.displayName} · 荧光 ${normalized.glowPercent}%"
    }

    companion object {
        const val DEFAULT_LEFT_DECORATION = "ʚ"
        const val DEFAULT_RIGHT_DECORATION = "ɞ"
        const val DEFAULT_CENTER_MARK = "◇"
        const val MIN_GLOW_PERCENT = 0
        const val MAX_GLOW_PERCENT = 100
        const val DEFAULT_GLOW_PERCENT = 54
        const val MIN_THICKNESS_DP = 1
        const val MAX_THICKNESS_DP = 3
        const val DEFAULT_THICKNESS_DP = 1
        const val MAX_DECORATION_CODE_POINTS = 4

        val DEFAULT = TraceDividerStyle()

        private fun normalizeDecoration(value: String, fallback: String): String {
            val trimmed = value.trim()
            if (trimmed.isEmpty()) return fallback
            return trimmed.takeCodePoints(MAX_DECORATION_CODE_POINTS)
        }

        private fun String.takeCodePoints(maxCount: Int): String {
            if (isEmpty() || maxCount <= 0) return ""
            var end = 0
            var count = 0
            while (end < length && count < maxCount) {
                end += Character.charCount(codePointAt(end))
                count++
            }
            return substring(0, end)
        }
    }
}

enum class TraceDividerSide {
    LEFT,
    RIGHT
}

/**
 * 分割线两侧共用的绘制 View。
 * 一张 View 自己画单轨、双轨、星屑或低语淡线，设置页预览和聊天页使用同一套实现。
 */
class TraceDividerLineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var traceStyle: TraceDividerStyle = TraceDividerStyle.DEFAULT
        set(value) {
            field = value.normalized()
            invalidate()
        }

    var side: TraceDividerSide = TraceDividerSide.LEFT
        set(value) {
            field = value
            invalidate()
        }

    var darkBackground: Boolean = ThemeHelper.isDark(context)
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val style = traceStyle.normalized()
        val contrast = if (darkBackground) Color.WHITE else Color.BLACK
        val accent = ThemeHelper.getColors(context).accentStrong
        val glowFraction = style.glowPercent / 100f
        val thickness = max(1f, style.thicknessDp * density)
        val centerY = height / 2f

        when (style.lineStyle) {
            TraceDividerLineStyle.TWIN -> {
                drawGradientLine(
                    canvas = canvas,
                    startX = 0f,
                    endX = width.toFloat(),
                    y = centerY - 3f * density,
                    thickness = thickness,
                    contrast = contrast,
                    accent = accent,
                    alphaScale = 1f,
                    glowFraction = glowFraction
                )
                drawGradientLine(
                    canvas = canvas,
                    startX = width * 0.08f,
                    endX = width * 0.92f,
                    y = centerY + 3f * density,
                    thickness = max(1f, thickness * 0.82f),
                    contrast = contrast,
                    accent = accent,
                    alphaScale = 0.46f,
                    glowFraction = glowFraction
                )
            }

            TraceDividerLineStyle.SINGLE -> drawGradientLine(
                canvas = canvas,
                startX = 0f,
                endX = width.toFloat(),
                y = centerY,
                thickness = thickness,
                contrast = contrast,
                accent = accent,
                alphaScale = 1f,
                glowFraction = glowFraction
            )

            TraceDividerLineStyle.STARDUST -> drawStardust(
                canvas = canvas,
                y = centerY,
                contrast = contrast,
                accent = accent,
                thickness = thickness,
                glowFraction = glowFraction
            )

            TraceDividerLineStyle.WHISPER -> drawGradientLine(
                canvas = canvas,
                startX = width * 0.09f,
                endX = width * 0.91f,
                y = centerY,
                thickness = max(1f, thickness * 0.82f),
                contrast = contrast,
                accent = accent,
                alphaScale = 0.56f,
                glowFraction = glowFraction
            )
        }
    }

    private fun drawGradientLine(
        canvas: Canvas,
        startX: Float,
        endX: Float,
        y: Float,
        thickness: Float,
        contrast: Int,
        accent: Int,
        alphaScale: Float,
        glowFraction: Float
    ) {
        if (endX <= startX) return
        val low = withAlpha(contrast, (46 * alphaScale).toInt())
        val medium = withAlpha(contrast, (112 * alphaScale).toInt())
        val glow = withAlpha(accent, (215 * alphaScale * glowFraction).toInt())

        val colors = if (side == TraceDividerSide.LEFT) {
            intArrayOf(Color.TRANSPARENT, low, medium, glow)
        } else {
            intArrayOf(glow, medium, low, Color.TRANSPARENT)
        }
        val positions = floatArrayOf(0f, 0.10f, 0.62f, 1f)

        if (glowFraction > 0f) {
            val glowColors = if (side == TraceDividerSide.LEFT) {
                intArrayOf(
                    Color.TRANSPARENT,
                    Color.TRANSPARENT,
                    withAlpha(accent, (34 * alphaScale * glowFraction).toInt()),
                    withAlpha(accent, (92 * alphaScale * glowFraction).toInt())
                )
            } else {
                intArrayOf(
                    withAlpha(accent, (92 * alphaScale * glowFraction).toInt()),
                    withAlpha(accent, (34 * alphaScale * glowFraction).toInt()),
                    Color.TRANSPARENT,
                    Color.TRANSPARENT
                )
            }
            paint.strokeWidth = thickness + (3.5f * density * glowFraction)
            paint.shader = LinearGradient(
                startX,
                y,
                endX,
                y,
                glowColors,
                positions,
                Shader.TileMode.CLAMP
            )
            canvas.drawLine(startX, y, endX, y, paint)
        }

        paint.strokeWidth = thickness
        paint.shader = LinearGradient(
            startX,
            y,
            endX,
            y,
            colors,
            positions,
            Shader.TileMode.CLAMP
        )
        canvas.drawLine(startX, y, endX, y, paint)
        paint.shader = null
    }

    private fun drawStardust(
        canvas: Canvas,
        y: Float,
        contrast: Int,
        accent: Int,
        thickness: Float,
        glowFraction: Float
    ) {
        val gap = 11f * density
        val radius = max(1.15f * density, thickness * 0.75f)
        val count = max(2, (width / gap).toInt())
        val start = if (side == TraceDividerSide.LEFT) 0 else count - 1
        val end = if (side == TraceDividerSide.LEFT) count else -1
        val step = if (side == TraceDividerSide.LEFT) 1 else -1

        var index = start
        while (index != end) {
            val progress = if (count <= 1) 1f else index.toFloat() / (count - 1).toFloat()
            val inward = if (side == TraceDividerSide.LEFT) progress else 1f - progress
            val x = if (count <= 1) width / 2f else width * progress
            val mixed = blend(contrast, accent, inward * 0.72f)
            val alpha = (42 + 170 * inward * glowFraction).toInt().coerceIn(0, 255)
            if (glowFraction > 0f) {
                dotPaint.color = withAlpha(
                    accent,
                    (46 * inward * glowFraction).toInt().coerceIn(0, 255)
                )
                canvas.drawCircle(
                    x,
                    y,
                    radius + 2.6f * density * glowFraction,
                    dotPaint
                )
            }
            dotPaint.color = withAlpha(mixed, alpha)
            canvas.drawCircle(x, y, radius, dotPaint)
            index += step
        }
    }

    private fun blend(from: Int, to: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(from) + (Color.red(to) - Color.red(from)) * t).toInt(),
            (Color.green(from) + (Color.green(to) - Color.green(from)) * t).toInt(),
            (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t).toInt()
        )
    }

    private fun withAlpha(color: Int, alpha: Int): Int = Color.argb(
        alpha.coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color)
    )
}

/** 统一应用分割线文字的黑白对比、透明度与荧光。 */
fun applyTraceDividerTextStyle(
    view: TextView,
    style: TraceDividerStyle,
    darkBackground: Boolean,
    alphaScale: Float = 1f
) {
    val normalized = style.normalized()
    val textColor = if (darkBackground) Color.WHITE else Color.BLACK
    val accent = ThemeHelper.getColors(view.context).accentStrong
    val glowFraction = normalized.glowPercent / 100f
    view.setTextColor(
        Color.argb(
            (255 * alphaScale).toInt().coerceIn(0, 255),
            Color.red(textColor),
            Color.green(textColor),
            Color.blue(textColor)
        )
    )
    view.setShadowLayer(
        (2.5f + 2.5f * glowFraction) * view.resources.displayMetrics.density,
        0f,
        0f,
        Color.argb(
            (210 * glowFraction * alphaScale).toInt().coerceIn(0, 255),
            Color.red(accent),
            Color.green(accent),
            Color.blue(accent)
        )
    )
}
