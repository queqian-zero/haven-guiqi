package com.haven.guiqi

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import java.util.Calendar

/**
 * NestRoomView — 小窝房间渲染
 *
 * 纯代码绘制的像素风房间。
 * 窗外天色跟手机真实时间同步。
 * 蜡烛和落地灯在夜晚亮起。
 * 点家具有反应。
 */
class NestRoomView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    // 房间逻辑尺寸（像素画坐标）
    companion object {
        const val ROOM_W = 320
        const val ROOM_H = 180
        const val FLOOR_Y = 128
    }

    private val paint = Paint().apply { isAntiAlias = false; isFilterBitmap = false }
    private val handler = Handler(Looper.getMainLooper())
    private var timePhase = TimePhase.NIGHT

    // 家具点击区域（逻辑坐标）
    data class Furniture(val name: String, val x: Int, val y: Int, val w: Int, val h: Int, val msg: String)
    private val furnitureList = listOf(
        Furniture("书架", 8, FLOOR_Y - 72, 75, 72, "塞满了书。有几本是你放进去的。"),
        Furniture("落地灯", 100, FLOOR_Y - 72, 24, 72, "灯罩有点歪。就这样吧。"),
        Furniture("地毯", 115, FLOOR_Y + 4, 80, 28, "深红色的旧地毯。踩上去很软。"),
        Furniture("梳妆台", 270, FLOOR_Y - 48, 50, 48, "蜡烛快烧完了。抽屉里不知道放了什么。"),
        Furniture("椅子", 252, FLOOR_Y - 48, 24, 48, "木头椅子。坐上去会吱呀响。"),
        Furniture("床", 290, FLOOR_Y - 48, 30, 48, "被子没叠。枕头上有压出来的痕迹。"),
        Furniture("窗户左", 130, 15, 56, 50, ""),
        Furniture("窗户右", 212, 15, 56, 50, ""),
        Furniture("画", 40, 22, 22, 16, "一幅小风景画。不知道画的是哪里。"),
    )

    // 缩放比例（逻辑坐标 → 屏幕像素）
    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    enum class TimePhase { DAWN, DAY, DUSK, NIGHT }

    init {
        // 每分钟刷新一次时间
        handler.post(object : Runnable {
            override fun run() {
                updateTimePhase()
                invalidate()
                handler.postDelayed(this, 60_000)
            }
        })
        updateTimePhase()
    }

    private fun updateTimePhase() {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        timePhase = when (hour) {
            in 5..6 -> TimePhase.DAWN
            in 7..17 -> TimePhase.DAY
            in 18..19 -> TimePhase.DUSK
            else -> TimePhase.NIGHT
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = MeasureSpec.getSize(heightMeasureSpec)
        scale = h.toFloat() / ROOM_H
        val w = (ROOM_W * scale).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        scale = h.toFloat() / ROOM_H
        offsetX = 0f
        offsetY = 0f
    }

    /** 返回房间实际渲染宽度 */
    fun getRoomWidth(): Int = (ROOM_W * scale).toInt()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(scale, scale)

        drawCeiling(canvas)
        drawWall(canvas)
        drawWainscoting(canvas)
        drawFloor(canvas)
        drawWindows(canvas)
        drawRug(canvas)
        drawFurnitureShapes(canvas)
        drawWallArt(canvas)
        drawLighting(canvas)
        drawVignette(canvas)

        canvas.restore()
    }

    // ========== 绘制各部分 ==========

    private fun drawCeiling(c: Canvas) {
        paint.color = Color.rgb(80, 58, 40)
        c.drawRect(0f, 0f, ROOM_W.toFloat(), 7f, paint)
        paint.color = Color.rgb(60, 42, 30)
        c.drawRect(0f, 7f, ROOM_W.toFloat(), 8f, paint)
    }

    private fun drawWall(c: Canvas) {
        paint.color = Color.rgb(95, 72, 55)
        c.drawRect(0f, 8f, ROOM_W.toFloat(), (FLOOR_Y - 38).toFloat(), paint)
        // 竖条纹壁纸
        paint.color = Color.rgb(90, 68, 52)
        for (x in 0 until ROOM_W step 6) {
            if (x % 12 < 6) c.drawLine(x.toFloat(), 8f, x.toFloat(), (FLOOR_Y - 38).toFloat(), paint)
        }
    }

    private fun drawWainscoting(c: Canvas) {
        val top = FLOOR_Y - 38
        paint.color = Color.rgb(72, 52, 38)
        c.drawRect(0f, top.toFloat(), ROOM_W.toFloat(), (FLOOR_Y - 3).toFloat(), paint)
        // 上沿
        paint.color = Color.rgb(100, 75, 55)
        c.drawRect(0f, top.toFloat(), ROOM_W.toFloat(), (top + 1).toFloat(), paint)
        paint.color = Color.rgb(60, 42, 30)
        c.drawRect(0f, (top + 1).toFloat(), ROOM_W.toFloat(), (top + 2).toFloat(), paint)
        // 凹面板
        paint.color = Color.rgb(62, 44, 32)
        for (x in 4 until ROOM_W step 32) {
            val x2 = minOf(x + 28, ROOM_W - 2)
            c.drawRect(x.toFloat(), (top + 5).toFloat(), x2.toFloat(), (FLOOR_Y - 7).toFloat(), paint)
            paint.color = Color.rgb(82, 60, 44)
            c.drawLine((x + 1).toFloat(), (top + 5).toFloat(), (x + 1).toFloat(), (FLOOR_Y - 7).toFloat(), paint)
            c.drawLine(x.toFloat(), (top + 6).toFloat(), x2.toFloat(), (top + 6).toFloat(), paint)
            paint.color = Color.rgb(62, 44, 32)
        }
        // 踢脚线
        paint.color = Color.rgb(68, 48, 34)
        c.drawRect(0f, (FLOOR_Y - 3).toFloat(), ROOM_W.toFloat(), FLOOR_Y.toFloat(), paint)
        paint.color = Color.rgb(88, 65, 45)
        c.drawLine(0f, (FLOOR_Y - 3).toFloat(), ROOM_W.toFloat(), (FLOOR_Y - 3).toFloat(), paint)
    }

    private fun drawFloor(c: Canvas) {
        val a = Color.rgb(110, 78, 52)
        val b = Color.rgb(100, 70, 47)
        val line = Color.rgb(85, 58, 38)
        val seam = Color.rgb(82, 56, 36)
        for (y in FLOOR_Y until ROOM_H step 5) {
            paint.color = if (((y - FLOOR_Y) / 5) % 2 == 0) a else b
            c.drawRect(0f, y.toFloat(), ROOM_W.toFloat(), minOf(y + 4, ROOM_H).toFloat(), paint)
            paint.color = line
            c.drawLine(0f, y.toFloat(), ROOM_W.toFloat(), y.toFloat(), paint)
        }
        paint.color = seam
        for (x in 0 until ROOM_W step 20) {
            val off = if ((x / 20) % 2 == 1) 10 else 0
            for (y in FLOOR_Y + off until ROOM_H step 10) {
                c.drawLine(x.toFloat(), y.toFloat(), x.toFloat(), minOf(y + 3, ROOM_H).toFloat(), paint)
            }
        }
    }

    private fun drawWindows(c: Canvas) {
        drawOneWindow(c, 148, 15)
        drawOneWindow(c, 230, 15)
    }

    private fun drawOneWindow(c: Canvas, cx: Int, wy: Int) {
        val ww = 36; val wh = 40
        val x1 = cx - ww / 2; val y1 = wy
        // 外框
        paint.color = Color.rgb(55, 38, 26)
        c.drawRect((x1 - 4).toFloat(), (y1 - 4).toFloat(), (x1 + ww + 4).toFloat(), (y1 + wh + 4).toFloat(), paint)
        paint.color = Color.rgb(90, 65, 45)
        c.drawRect((x1 - 2).toFloat(), (y1 - 2).toFloat(), (x1 + ww + 2).toFloat(), (y1 + wh + 2).toFloat(), paint)
        // 天空（根据时间变化）
        val skyColors = when (timePhase) {
            TimePhase.DAWN -> intArrayOf(Color.rgb(180, 130, 100), Color.rgb(140, 160, 200))
            TimePhase.DAY -> intArrayOf(Color.rgb(135, 185, 230), Color.rgb(170, 210, 245))
            TimePhase.DUSK -> intArrayOf(Color.rgb(200, 120, 80), Color.rgb(120, 100, 140))
            TimePhase.NIGHT -> intArrayOf(Color.rgb(25, 30, 60), Color.rgb(45, 55, 90))
        }
        val skyShader = LinearGradient(
            x1.toFloat(), y1.toFloat(), x1.toFloat(), (y1 + wh).toFloat(),
            skyColors[0], skyColors[1], Shader.TileMode.CLAMP
        )
        paint.shader = skyShader
        c.drawRect(x1.toFloat(), y1.toFloat(), (x1 + ww).toFloat(), (y1 + wh).toFloat(), paint)
        paint.shader = null
        // 夜晚画月亮
        if (timePhase == TimePhase.NIGHT || timePhase == TimePhase.DUSK) {
            paint.color = Color.rgb(200, 200, 180)
            c.drawCircle((x1 + ww - 7).toFloat(), (y1 + 8).toFloat(), 3f, paint)
        }
        // 白天画云
        if (timePhase == TimePhase.DAY) {
            paint.color = Color.argb(160, 255, 255, 255)
            c.drawRect((x1 + 5).toFloat(), (y1 + 10).toFloat(), (x1 + 15).toFloat(), (y1 + 14).toFloat(), paint)
            c.drawRect((x1 + 7).toFloat(), (y1 + 8).toFloat(), (x1 + 13).toFloat(), (y1 + 12).toFloat(), paint)
        }
        // 十字框
        paint.color = Color.rgb(90, 65, 45)
        c.drawRect((x1 + ww / 2 - 1).toFloat(), y1.toFloat(), (x1 + ww / 2 + 1).toFloat(), (y1 + wh).toFloat(), paint)
        c.drawRect(x1.toFloat(), (y1 + wh / 2 - 1).toFloat(), (x1 + ww).toFloat(), (y1 + wh / 2 + 1).toFloat(), paint)
        // 窗台
        paint.color = Color.rgb(90, 65, 45)
        c.drawRect((x1 - 5).toFloat(), (y1 + wh + 3).toFloat(), (x1 + ww + 5).toFloat(), (y1 + wh + 7).toFloat(), paint)
        paint.color = Color.rgb(105, 78, 55)
        c.drawLine((x1 - 5).toFloat(), (y1 + wh + 3).toFloat(), (x1 + ww + 5).toFloat(), (y1 + wh + 3).toFloat(), paint)
        // 窗帘
        val curtA = Color.rgb(130, 105, 78)
        val curtB = Color.rgb(110, 88, 65)
        paint.color = curtA
        c.drawRect((x1 - 10).toFloat(), (y1 - 6).toFloat(), (x1 + 6).toFloat(), (y1 + wh + 2).toFloat(), paint)
        c.drawRect((x1 + ww - 6).toFloat(), (y1 - 6).toFloat(), (x1 + ww + 10).toFloat(), (y1 + wh + 2).toFloat(), paint)
        paint.color = curtB
        for (dy in 0 until wh + 8 step 4) {
            c.drawLine((x1 - 8).toFloat(), (y1 - 6 + dy).toFloat(), (x1 + 4).toFloat(), (y1 - 6 + dy).toFloat(), paint)
            c.drawLine((x1 + ww - 4).toFloat(), (y1 - 6 + dy).toFloat(), (x1 + ww + 8).toFloat(), (y1 - 6 + dy).toFloat(), paint)
        }
        // 窗帘杆
        paint.color = Color.rgb(75, 52, 36)
        c.drawRect((x1 - 12).toFloat(), (y1 - 8).toFloat(), (x1 + ww + 12).toFloat(), (y1 - 6).toFloat(), paint)
    }

    private fun drawRug(c: Canvas) {
        val rx = 115; val ry = FLOOR_Y + 4; val rw = 80; val rh = 28
        paint.color = Color.rgb(120, 55, 45)
        c.drawRect(rx.toFloat(), ry.toFloat(), (rx + rw).toFloat(), (ry + rh).toFloat(), paint)
        paint.color = Color.rgb(100, 42, 35)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1f
        c.drawRect((rx + 1).toFloat(), (ry + 1).toFloat(), (rx + rw - 1).toFloat(), (ry + rh - 1).toFloat(), paint)
        paint.color = Color.rgb(140, 68, 55)
        c.drawRect((rx + 4).toFloat(), (ry + 4).toFloat(), (rx + rw - 4).toFloat(), (ry + rh - 4).toFloat(), paint)
        paint.style = Paint.Style.FILL
        // 菱形花纹
        val cxR = rx + rw / 2f; val cyR = ry + rh / 2f
        paint.color = Color.rgb(155, 80, 60)
        val path = Path()
        path.moveTo(cxR, cyR - 6); path.lineTo(cxR + 8, cyR)
        path.lineTo(cxR, cyR + 6); path.lineTo(cxR - 8, cyR); path.close()
        paint.style = Paint.Style.STROKE; c.drawPath(path, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawFurnitureShapes(c: Canvas) {
        // 书架（大）
        drawBookshelf(c, 8, FLOOR_Y - 72, 36, 72, true)
        // 书架（小）
        drawBookshelf(c, 50, FLOOR_Y - 54, 28, 54, false)
        // 落地灯
        drawFloorLamp(c, 105, FLOOR_Y)
        // 梳妆台 + 蜡烛
        drawDresser(c, 270, FLOOR_Y)
        // 椅子
        drawChair(c, 255, FLOOR_Y)
        // 床
        drawBed(c, 288, FLOOR_Y)
        // 小桌
        drawSmallTable(c, 183, FLOOR_Y)
    }

    private fun drawBookshelf(c: Canvas, x: Int, y: Int, w: Int, h: Int, big: Boolean) {
        // 框架
        paint.color = Color.rgb(85, 55, 35)
        c.drawRect(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat(), paint)
        paint.color = Color.rgb(70, 45, 28)
        c.drawRect((x + 2).toFloat(), (y + 2).toFloat(), (x + w - 2).toFloat(), (y + h - 2).toFloat(), paint)
        // 隔层
        val shelves = if (big) 4 else 3
        val shelfH = (h - 4) / shelves
        for (i in 0 until shelves) {
            val sy = y + 2 + i * shelfH
            paint.color = Color.rgb(85, 55, 35)
            c.drawRect((x + 2).toFloat(), (sy + shelfH - 1).toFloat(), (x + w - 2).toFloat(), (sy + shelfH + 1).toFloat(), paint)
            // 书本
            val bookColors = intArrayOf(
                Color.rgb(160, 60, 50), Color.rgb(50, 80, 130), Color.rgb(60, 120, 60),
                Color.rgb(180, 140, 60), Color.rgb(100, 50, 100), Color.rgb(140, 80, 40)
            )
            var bx = x + 4
            while (bx < x + w - 6) {
                val bw = 2 + (bx * 7 + i * 3) % 3
                paint.color = bookColors[(bx + i) % bookColors.size]
                c.drawRect(bx.toFloat(), (sy + 2).toFloat(), (bx + bw).toFloat(), (sy + shelfH - 2).toFloat(), paint)
                bx += bw + 1
            }
        }
    }

    private fun drawFloorLamp(c: Canvas, x: Int, floorY: Int) {
        // 底座
        paint.color = Color.rgb(70, 48, 32)
        c.drawRect((x - 4).toFloat(), (floorY - 4).toFloat(), (x + 8).toFloat(), floorY.toFloat(), paint)
        // 杆
        paint.color = Color.rgb(80, 55, 38)
        c.drawRect((x + 1).toFloat(), (floorY - 60).toFloat(), (x + 3).toFloat(), (floorY - 4).toFloat(), paint)
        // 灯罩
        paint.color = Color.rgb(180, 150, 100)
        c.drawRect((x - 6).toFloat(), (floorY - 68).toFloat(), (x + 10).toFloat(), (floorY - 58).toFloat(), paint)
        // 灯罩内光
        if (timePhase == TimePhase.NIGHT || timePhase == TimePhase.DUSK) {
            paint.color = Color.rgb(255, 220, 150)
            c.drawRect((x - 4).toFloat(), (floorY - 66).toFloat(), (x + 8).toFloat(), (floorY - 60).toFloat(), paint)
        }
    }

    private fun drawDresser(c: Canvas, x: Int, floorY: Int) {
        paint.color = Color.rgb(85, 55, 35)
        c.drawRect(x.toFloat(), (floorY - 32).toFloat(), (x + 36).toFloat(), floorY.toFloat(), paint)
        // 抽屉线
        paint.color = Color.rgb(70, 45, 28)
        c.drawLine(x.toFloat(), (floorY - 16).toFloat(), (x + 36).toFloat(), (floorY - 16).toFloat(), paint)
        // 把手
        paint.color = Color.rgb(140, 110, 70)
        c.drawRect((x + 16).toFloat(), (floorY - 24).toFloat(), (x + 20).toFloat(), (floorY - 22).toFloat(), paint)
        c.drawRect((x + 16).toFloat(), (floorY - 10).toFloat(), (x + 20).toFloat(), (floorY - 8).toFloat(), paint)
        // 蜡烛
        paint.color = Color.rgb(220, 210, 190)
        c.drawRect((x + 10).toFloat(), (floorY - 42).toFloat(), (x + 14).toFloat(), (floorY - 32).toFloat(), paint)
        // 烛火
        if (timePhase == TimePhase.NIGHT || timePhase == TimePhase.DUSK) {
            paint.color = Color.rgb(255, 200, 80)
            c.drawCircle((x + 12).toFloat(), (floorY - 44).toFloat(), 2f, paint)
            paint.color = Color.rgb(255, 160, 50)
            c.drawCircle((x + 12).toFloat(), (floorY - 45).toFloat(), 1f, paint)
        }
    }

    private fun drawChair(c: Canvas, x: Int, floorY: Int) {
        paint.color = Color.rgb(100, 65, 40)
        // 椅背
        c.drawRect(x.toFloat(), (floorY - 30).toFloat(), (x + 3).toFloat(), (floorY - 8).toFloat(), paint)
        // 座面
        c.drawRect(x.toFloat(), (floorY - 14).toFloat(), (x + 14).toFloat(), (floorY - 8).toFloat(), paint)
        // 腿
        c.drawRect(x.toFloat(), (floorY - 8).toFloat(), (x + 2).toFloat(), floorY.toFloat(), paint)
        c.drawRect((x + 12).toFloat(), (floorY - 8).toFloat(), (x + 14).toFloat(), floorY.toFloat(), paint)
    }

    private fun drawBed(c: Canvas, x: Int, floorY: Int) {
        // 床架
        paint.color = Color.rgb(90, 58, 38)
        c.drawRect(x.toFloat(), (floorY - 20).toFloat(), (x + 30).toFloat(), floorY.toFloat(), paint)
        // 床头
        c.drawRect(x.toFloat(), (floorY - 30).toFloat(), (x + 4).toFloat(), (floorY - 10).toFloat(), paint)
        // 被子
        paint.color = Color.rgb(140, 90, 65)
        c.drawRect((x + 4).toFloat(), (floorY - 18).toFloat(), (x + 28).toFloat(), (floorY - 4).toFloat(), paint)
        // 枕头
        paint.color = Color.rgb(200, 185, 160)
        c.drawRect((x + 4).toFloat(), (floorY - 16).toFloat(), (x + 12).toFloat(), (floorY - 10).toFloat(), paint)
    }

    private fun drawSmallTable(c: Canvas, x: Int, floorY: Int) {
        paint.color = Color.rgb(90, 60, 40)
        c.drawRect(x.toFloat(), (floorY - 14).toFloat(), (x + 16).toFloat(), (floorY - 10).toFloat(), paint)
        c.drawRect((x + 2).toFloat(), (floorY - 10).toFloat(), (x + 4).toFloat(), floorY.toFloat(), paint)
        c.drawRect((x + 12).toFloat(), (floorY - 10).toFloat(), (x + 14).toFloat(), floorY.toFloat(), paint)
        // 杯子
        paint.color = Color.rgb(180, 170, 155)
        c.drawRect((x + 6).toFloat(), (floorY - 20).toFloat(), (x + 12).toFloat(), (floorY - 14).toFloat(), paint)
        // 热气（白天才有）
        if (timePhase == TimePhase.DAY || timePhase == TimePhase.DAWN) {
            paint.color = Color.argb(60, 255, 255, 255)
            c.drawLine((x + 8).toFloat(), (floorY - 22).toFloat(), (x + 7).toFloat(), (floorY - 26).toFloat(), paint)
            c.drawLine((x + 10).toFloat(), (floorY - 22).toFloat(), (x + 11).toFloat(), (floorY - 26).toFloat(), paint)
        }
    }

    private fun drawWallArt(c: Canvas) {
        // 小风景画
        paint.color = Color.rgb(65, 45, 32)
        c.drawRect(40f, 22f, 62f, 38f, paint)
        paint.color = Color.rgb(80, 90, 110)
        c.drawRect(42f, 24f, 60f, 31f, paint) // 天空
        paint.color = Color.rgb(70, 95, 55)
        c.drawRect(42f, 31f, 60f, 36f, paint) // 草地
        paint.color = Color.rgb(210, 180, 120)
        c.drawCircle(56f, 26f, 2f, paint) // 太阳

        // 椭圆相框
        paint.color = Color.rgb(65, 45, 32)
        c.drawOval(RectF(186f, 20f, 200f, 36f), paint)
        paint.color = Color.rgb(140, 110, 90)
        c.drawOval(RectF(188f, 22f, 198f, 34f), paint)
    }

    private fun drawLighting(c: Canvas) {
        canvas_ref = c
        if (timePhase == TimePhase.NIGHT || timePhase == TimePhase.DUSK) {
            drawGlow(282, FLOOR_Y - 45, 55, Color.argb(20, 255, 190, 100))
            drawGlow(108, FLOOR_Y - 65, 50, Color.argb(16, 255, 200, 120))
            drawGlow(148, 35, 70, Color.argb(6, 150, 170, 210))
            drawGlow(230, 35, 70, Color.argb(6, 150, 170, 210))
        } else if (timePhase == TimePhase.DAY) {
            drawGlow(148, 40, 90, Color.argb(10, 255, 230, 180))
            drawGlow(230, 40, 90, Color.argb(10, 255, 230, 180))
        } else if (timePhase == TimePhase.DAWN) {
            drawGlow(148, 40, 80, Color.argb(8, 255, 180, 130))
            drawGlow(230, 40, 80, Color.argb(8, 255, 180, 130))
        }
        canvas_ref = null
    }

    private fun drawGlow(cx: Int, cy: Int, radius: Int, color: Int) {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        val maxA = Color.alpha(color)
        for (i in radius downTo 1 step 3) {
            val a = maxA * i / radius
            paint.color = Color.argb(a, r, g, b)
            paint.style = Paint.Style.FILL
            canvas_ref?.drawCircle(cx.toFloat(), cy.toFloat(), i.toFloat(), paint)
        }
    }
    private var canvas_ref: Canvas? = null

    private fun drawVignette(c: Canvas) {
        // 四角变暗
        paint.color = Color.argb(30, 0, 0, 0)
        c.drawRect(0f, 0f, 20f, ROOM_H.toFloat(), paint)
        c.drawRect((ROOM_W - 20).toFloat(), 0f, ROOM_W.toFloat(), ROOM_H.toFloat(), paint)
        paint.color = Color.argb(15, 0, 0, 0)
        c.drawRect(0f, 0f, ROOM_W.toFloat(), 15f, paint)
        c.drawRect(0f, (ROOM_H - 15).toFloat(), ROOM_W.toFloat(), ROOM_H.toFloat(), paint)
    }

    // ========== 触摸交互 ==========

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            // 转换屏幕坐标到逻辑坐标
            val lx = ((event.x - offsetX) / scale).toInt()
            val ly = ((event.y - offsetY) / scale).toInt()

            for (f in furnitureList) {
                if (lx in f.x until f.x + f.w && ly in f.y until f.y + f.h) {
                    if (f.name.startsWith("窗户")) {
                        val timeMsg = when (timePhase) {
                            TimePhase.DAWN -> "天快亮了。外面有鸟叫。"
                            TimePhase.DAY -> "外面阳光很好。云慢慢地飘。"
                            TimePhase.DUSK -> "太阳快落山了。天边是橘红色的。"
                            TimePhase.NIGHT -> "外面很安静。能看到月亮。"
                        }
                        Toast.makeText(context, timeMsg, Toast.LENGTH_SHORT).show()
                    } else if (f.msg.isNotEmpty()) {
                        Toast.makeText(context, f.msg, Toast.LENGTH_SHORT).show()
                    }
                    return true
                }
            }
        }
        return true
    }
}