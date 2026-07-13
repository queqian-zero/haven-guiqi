package com.haven.guiqi

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import java.text.SimpleDateFormat
import java.util.*

/**
 * SunMoonCardView — 日出日落月出月落弧线卡片
 *
 * 画两条弧线（太阳轨迹 + 月亮轨迹），根据当前时间把太阳和月亮放到弧线上对应的位置。
 * 支持进入动画：太阳和月亮从左端滑到当前位置。
 */
class SunMoonCardView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private var astronomy: AstronomyData? = null
    private var animProgress = 0f  // 0→1 动画进度
    private val c = ThemeHelper.getColors(context)

    private val sunArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f
        color = Color.argb(80, 255, 200, 60)
    }
    private val moonArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.5f
        color = Color.argb(60, 180, 180, 220)
    }
    private val horizonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f
        color = Color.argb(40, 180, 180, 180)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 28f; color = c.textSecondary
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 24f; color = c.textHint
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 30f; color = c.textPrimary; typeface = Typeface.DEFAULT_BOLD
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    fun setData(data: AstronomyData) {
        astronomy = data
        animProgress = 0f
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1200; interpolator = DecelerateInterpolator()
            addUpdateListener { animProgress = it.animatedValue as Float; invalidate() }
        }
        animator.start()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (w * 0.55f).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val astro = astronomy ?: return
        val w = width.toFloat(); val h = height.toFloat()
        val pad = w * 0.06f
        val arcLeft = pad; val arcRight = w - pad
        val arcWidth = arcRight - arcLeft
        val horizonY = h * 0.62f
        val sunPeakH = h * 0.38f
        val moonPeakH = h * 0.28f

        // 地平线
        canvas.drawLine(arcLeft, horizonY, arcRight, horizonY, horizonPaint)

        // 太阳弧线
        val sunPath = Path()
        for (i in 0..100) {
            val t = i / 100f
            val x = arcLeft + t * arcWidth
            val y = horizonY - 4f * sunPeakH * t * (1f - t)
            if (i == 0) sunPath.moveTo(x, y) else sunPath.lineTo(x, y)
        }
        canvas.drawPath(sunPath, sunArcPaint)

        // 月亮弧线
        val moonPath = Path()
        for (i in 0..100) {
            val t = i / 100f
            val x = arcLeft + t * arcWidth
            val y = horizonY - 4f * moonPeakH * t * (1f - t)
            if (i == 0) moonPath.moveTo(x, y) else moonPath.lineTo(x, y)
        }
        canvas.drawPath(moonPath, moonArcPaint)

        // 当前时间在弧线上的位置
        val sunPos = timeToProgress(astro.sunrise, astro.sunset) * animProgress
        val moonPos = timeToProgress(astro.moonrise, astro.moonset) * animProgress

        // 太阳
        if (sunPos in 0.001f..0.999f) {
            val sx = arcLeft + sunPos * arcWidth
            val sy = horizonY - 4f * sunPeakH * sunPos * (1f - sunPos)
            // 光晕
            glowPaint.shader = RadialGradient(sx, sy, w * 0.06f,
                Color.argb(60, 255, 200, 60), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawCircle(sx, sy, w * 0.06f, glowPaint)
            glowPaint.shader = null
            // 太阳圆
            glowPaint.color = Color.rgb(255, 190, 50)
            canvas.drawCircle(sx, sy, w * 0.025f, glowPaint)
            // 芒线
            val rayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.rgb(255, 200, 80)
            }
            for (angle in 0 until 360 step 45) {
                val rad = Math.toRadians(angle.toDouble())
                val r1 = w * 0.032f; val r2 = w * 0.045f
                canvas.drawLine(
                    sx + (r1 * Math.cos(rad)).toFloat(), sy + (r1 * Math.sin(rad)).toFloat(),
                    sx + (r2 * Math.cos(rad)).toFloat(), sy + (r2 * Math.sin(rad)).toFloat(), rayPaint)
            }
        }

        // 月亮
        if (moonPos in 0.001f..0.999f) {
            val mx = arcLeft + moonPos * arcWidth
            val my = horizonY - 4f * moonPeakH * moonPos * (1f - moonPos)
            glowPaint.shader = RadialGradient(mx, my, w * 0.04f,
                Color.argb(40, 200, 200, 240), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawCircle(mx, my, w * 0.04f, glowPaint)
            glowPaint.shader = null
            // 月亮圆 + 阴影遮罩造月牙效果
            glowPaint.color = Color.rgb(220, 220, 240)
            canvas.drawCircle(mx, my, w * 0.02f, glowPaint)
            glowPaint.color = c.card
            canvas.drawCircle(mx - w * 0.008f, my - w * 0.005f, w * 0.015f, glowPaint)
        }

        // 标题
        canvas.drawText("日出日落", arcLeft, pad + titlePaint.textSize * 0.8f, titlePaint)

        // 底部时间标签
        val bottomY = h - pad * 0.5f
        val labelY2 = bottomY
        val labelY1 = bottomY - labelPaint.textSize * 1.3f
        canvas.drawText("日出 ${formatTime12to24(astro.sunrise)}", arcLeft, labelY1, textPaint)
        canvas.drawText("月出 ${formatTime12to24(astro.moonrise)}", arcLeft, labelY2, labelPaint)
        val sunsetStr = "日落 ${formatTime12to24(astro.sunset)}"
        val moonsetStr = "月落 ${formatTime12to24(astro.moonset)}"
        canvas.drawText(sunsetStr, arcRight - textPaint.measureText(sunsetStr), labelY1, textPaint)
        canvas.drawText(moonsetStr, arcRight - labelPaint.measureText(moonsetStr), labelY2, labelPaint)
    }

    /** 把当前时间映射到 sunrise→sunset 的 0~1 进度 */
    private fun timeToProgress(startStr: String, endStr: String): Float {
        val start = parseMinutes(startStr); val end = parseMinutes(endStr)
        if (start < 0 || end < 0 || end <= start) return -1f
        val now = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
        return ((now - start).toFloat() / (end - start)).coerceIn(0f, 1f)
    }

    /** "05:24 AM" 或 "17:30 PM" → 分钟数 */
    private fun parseMinutes(str: String): Int {
        return try {
            val clean = str.trim().uppercase()
            val parts = clean.replace(Regex("[^0-9:]"), "").split(":")
            var h = parts[0].toInt(); val m = parts.getOrNull(1)?.toInt() ?: 0
            if (clean.contains("PM") && h != 12) h += 12
            if (clean.contains("AM") && h == 12) h = 0
            h * 60 + m
        } catch (_: Exception) { -1 }
    }

    /** "05:24 AM" → "05:24" */
    private fun formatTime12to24(str: String): String {
        val mins = parseMinutes(str)
        if (mins < 0) return str.trim()
        return String.format("%02d:%02d", mins / 60, mins % 60)
    }
}