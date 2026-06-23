package com.haven.guiqi

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import androidx.core.content.ContextCompat

/**
 * LineIconDrawable — 桌面线条图标
 *
 * 从 DesktopActivity 拆出来。纯 Canvas 画图，不依赖 Activity 状态。
 * 支持类型：chat / nest / archive / world / workshop / clock / weather /
 *          calendar / music / browser / settings / beautify / grid
 */
class LineIconDrawable(
    private val type: String,
    private val size: Int,
    private val iconColor: Int
) : Drawable() {

    constructor(context: Context, type: String, size: Int)
        : this(type, size, ContextCompat.getColor(context, R.color.haven_desktop_icon_color))

    private val paint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = size * 0.08f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = iconColor
        setShadowLayer(3f, 0f, 1f, 0x66000000)
    }

    private val fillPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = iconColor
        setShadowLayer(3f, 0f, 1f, 0x66000000)
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val cx = b.centerX().toFloat()
        val cy = b.centerY().toFloat()
        val s = size.toFloat()
        val u = s / 24f

        canvas.save()
        canvas.translate(cx - s / 2f, cy - s / 2f)

        when (type) {
            "chat" -> {
                val path = Path().apply {
                    addRoundRect(RectF(3*u,3*u,21*u,17*u), 2*u, 2*u, Path.Direction.CW)
                    moveTo(3*u, 17*u); lineTo(3*u, 21*u); lineTo(7*u, 17*u)
                }
                canvas.drawPath(path, paint)
            }
            "nest" -> {
                canvas.drawRoundRect(RectF(4*u,3*u,20*u,20*u), 1.5f*u, 1.5f*u, paint)
                canvas.drawLine(12*u,3*u,12*u,20*u, paint)
                canvas.drawLine(4*u,11.5f*u,20*u,11.5f*u, paint)
                canvas.drawLine(2*u,20*u,22*u,20*u, paint)
                canvas.drawRoundRect(RectF(14.5f*u,16.5f*u,17.5f*u,19.5f*u), 0.5f*u, 0.5f*u, fillPaint)
                canvas.drawCircle(8*u,15.5f*u,1.2f*u,fillPaint)
            }
            "archive" -> {
                canvas.drawRoundRect(RectF(3*u,2*u,21*u,22*u), 2*u, 2*u, paint)
                canvas.drawLine(3*u,12*u,21*u,12*u, paint)
                canvas.drawRoundRect(RectF(6*u,4*u,10*u,10*u), 1*u, 1*u, paint)
                canvas.drawRoundRect(RectF(12*u,5*u,16*u,10*u), 1*u, 1*u, paint)
                val folder = Path().apply {
                    moveTo(6*u,14*u); lineTo(6*u,20*u); lineTo(18*u,20*u)
                    lineTo(18*u,14*u); lineTo(13*u,14*u); lineTo(12*u,15.5f*u); lineTo(6*u,15.5f*u)
                }
                canvas.drawPath(folder, paint)
            }
            "world" -> {
                val left = Path().apply {
                    moveTo(12*u,6*u); lineTo(12*u,20*u); lineTo(3*u,19*u); lineTo(3*u,5*u); close()
                }
                canvas.drawPath(left, paint)
                val right = Path().apply {
                    moveTo(12*u,6*u); lineTo(12*u,20*u); lineTo(21*u,19*u); lineTo(21*u,5*u); close()
                }
                canvas.drawPath(right, paint)
                canvas.drawCircle(17*u,3*u,0.8f*u,fillPaint)
                canvas.drawCircle(20*u,5*u,0.5f*u,fillPaint)
            }
            "workshop" -> {
                canvas.drawCircle(9*u,15*u,4*u, paint)
                canvas.drawCircle(9*u,15*u,1.5f*u, paint)
                for (i in 0 until 6) {
                    val a = Math.toRadians((i*60).toDouble())
                    canvas.drawLine(
                        9*u+(4*u*Math.cos(a)).toFloat(), 15*u+(4*u*Math.sin(a)).toFloat(),
                        9*u+(5.5f*u*Math.cos(a)).toFloat(), 15*u+(5.5f*u*Math.sin(a)).toFloat(), paint)
                }
                canvas.drawLine(15*u,3*u,19*u,7*u, paint)
                canvas.drawLine(19*u,7*u,17*u,9*u, paint)
                canvas.drawLine(15*u,3*u,13*u,5*u, paint)
                canvas.drawLine(13*u,5*u,15*u,7*u, paint)
                canvas.drawLine(15*u,7*u,12*u,10*u, paint)
            }
            "clock" -> {
                canvas.drawCircle(12*u,12*u,10*u, paint)
                canvas.drawLine(12*u,12*u,12*u,7*u, paint)
                canvas.drawLine(12*u,12*u,16*u,14*u, paint)
                canvas.drawCircle(12*u,12*u,0.5f*u, fillPaint)
            }
            "weather" -> {
                canvas.drawCircle(10*u,9*u,3.5f*u, paint)
                val rays = arrayOf(
                    floatArrayOf(10f,3f,10f,1.5f), floatArrayOf(10f,15f,10f,16.5f),
                    floatArrayOf(4f,9f,2.5f,9f), floatArrayOf(16f,9f,17.5f,9f),
                    floatArrayOf(5.5f,4.5f,4.5f,3.5f), floatArrayOf(14.5f,4.5f,15.5f,3.5f)
                )
                for (r in rays) canvas.drawLine(r[0]*u,r[1]*u,r[2]*u,r[3]*u, paint)
                val cloud = Path().apply {
                    moveTo(8*u,19*u); lineTo(20*u,19*u)
                    cubicTo(22*u,19*u,22*u,15*u,19*u,15*u)
                    cubicTo(19*u,13*u,16*u,12*u,14*u,13.5f*u)
                    cubicTo(12*u,12*u,9*u,12.5f*u,8*u,15*u)
                    cubicTo(6*u,15*u,6*u,19*u,8*u,19*u)
                }
                canvas.drawPath(cloud, paint)
            }
            "calendar" -> {
                canvas.drawRoundRect(RectF(3*u,4*u,21*u,22*u), 2*u, 2*u, paint)
                canvas.drawLine(8*u,2*u,8*u,6*u, paint)
                canvas.drawLine(16*u,2*u,16*u,6*u, paint)
                canvas.drawLine(3*u,10*u,21*u,10*u, paint)
            }
            "music" -> {
                canvas.drawLine(9*u,18*u,9*u,5*u, paint)
                canvas.drawLine(9*u,5*u,21*u,3*u, paint)
                canvas.drawLine(21*u,3*u,21*u,16*u, paint)
                canvas.drawCircle(6*u,18*u,3*u, paint)
                canvas.drawCircle(18*u,16*u,3*u, paint)
            }
            "browser" -> {
                canvas.drawCircle(12*u,12*u,10*u, paint)
                canvas.drawLine(2*u,12*u,22*u,12*u, paint)
                val m = Path().apply {
                    moveTo(12*u,2*u); cubicTo(16*u,6*u,16*u,18*u,12*u,22*u)
                    moveTo(12*u,2*u); cubicTo(8*u,6*u,8*u,18*u,12*u,22*u)
                }
                canvas.drawPath(m, paint)
            }
            "settings" -> {
                canvas.drawCircle(12*u,12*u,3*u, paint)
                canvas.drawCircle(12*u,12*u,7*u, paint)
                for (i in 0 until 8) {
                    val a = Math.toRadians((i*45).toDouble())
                    canvas.drawLine(
                        12*u+(7*u*Math.cos(a)).toFloat(), 12*u+(7*u*Math.sin(a)).toFloat(),
                        12*u+(9.5f*u*Math.cos(a)).toFloat(), 12*u+(9.5f*u*Math.sin(a)).toFloat(), paint)
                }
            }
            "beautify" -> {
                canvas.drawCircle(12*u,12*u,9*u, paint)
                canvas.drawCircle(8*u,8*u,1.5f*u, fillPaint)
                canvas.drawCircle(14*u,7*u,1.5f*u, fillPaint)
                canvas.drawCircle(17*u,11*u,1.5f*u, fillPaint)
                canvas.drawCircle(8*u,14*u,1.5f*u, fillPaint)
                canvas.drawCircle(14*u,16*u,2.5f*u, paint)
            }
            "grid" -> {
                canvas.drawRoundRect(RectF(3*u,3*u,10.5f*u,10.5f*u), 1.5f*u, 1.5f*u, paint)
                canvas.drawRoundRect(RectF(13.5f*u,3*u,21*u,10.5f*u), 1.5f*u, 1.5f*u, paint)
                canvas.drawRoundRect(RectF(3*u,13.5f*u,10.5f*u,21*u), 1.5f*u, 1.5f*u, paint)
                canvas.drawRoundRect(RectF(13.5f*u,13.5f*u,21*u,21*u), 1.5f*u, 1.5f*u, paint)
            }
        }
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha; fillPaint.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth(): Int = size
    override fun getIntrinsicHeight(): Int = size
}