package com.haven.guiqi

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.text.SimpleDateFormat
import java.util.*

class ClockActivity : AppCompatActivity() {

    private lateinit var clockContainer: LinearLayout
    private val c get() = ThemeHelper.getColors(this)
    private val handler = Handler(Looper.getMainLooper())
    private var clockView: AnalogClockView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        setContentView(R.layout.activity_clock)

        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        insetsController.hide(WindowInsetsCompat.Type.navigationBars())
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController.isAppearanceLightStatusBars = !ThemeHelper.isDark(this)

        val contentView = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(contentView) { view, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.setPadding(0, top, 0, 0)
            insets
        }

        clockContainer = findViewById(R.id.clockContainer)
        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btnHistory).setOnClickListener {
            startActivity(Intent(this, ClockHistoryActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        buildPage()
        startClockUpdate()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacksAndMessages(null)
    }

    private fun startClockUpdate() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                clockView?.invalidate()
                handler.postDelayed(this, 1000)
            }
        }, 1000)
    }

    private fun buildPage() {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        clockContainer.removeAllViews()

        // ===== 模拟表盘 =====
        val clockSize = dp(160)
        clockView = AnalogClockView(this)
        clockView!!.layoutParams = LinearLayout.LayoutParams(clockSize, clockSize).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(6)
        }
        clockContainer.addView(clockView)

        // 日期
        val dateText = SimpleDateFormat("yyyy/M/d EEEE", Locale.CHINESE).format(Date())
        val dateTv = TextView(this).apply {
            text = dateText
            textSize = 12f
            setTextColor(c.textSecondary)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }
        }
        clockContainer.addView(dateTv)

        // ===== 系统下一个闹钟 =====
        val nextAlarmTime = getNextSystemAlarm()
        if (nextAlarmTime != null) {
            val naBg = GradientDrawable().apply {
                setColor(c.card)
                cornerRadius = dp(10).toFloat()
                setStroke(1, c.border)
            }
            val naCard = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = naBg
                setPadding(dp(14), dp(12), dp(14), dp(12))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(14) }
            }
            val naIcon = TextView(this).apply {
                text = "⏰"
                textSize = 20f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(12) }
            }
            val naInfo = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val naLabel = TextView(this).apply {
                text = "系统下一个闹钟"
                textSize = 11f
                setTextColor(c.textHint)
            }
            val naTime = TextView(this).apply {
                text = nextAlarmTime
                textSize = 18f
                setTextColor(c.textPrimary)
            }
            naInfo.addView(naLabel)
            naInfo.addView(naTime)
            val naSrc = TextView(this).apply {
                text = "系统时钟"
                textSize = 10f
                setTextColor(c.textHint)
            }
            naCard.addView(naIcon)
            naCard.addView(naInfo)
            naCard.addView(naSrc)
            clockContainer.addView(naCard)
        }

        // ===== 我的闹钟 =====
        addSectionHeader("🔔", "我的闹钟", dp)
        val alarmStorage = AlarmStorage(this)
        val activeAlarms = alarmStorage.getActiveAlarms()
        if (activeAlarms.isEmpty()) {
            addEmptyHint("还没有闹钟\nAI 可以用指令帮你设置", dp)
        } else {
            for (alarm in activeAlarms) {
                addAlarmCard(alarm, dp)
            }
        }

        // ===== TA们的日程 =====
        addSectionHeader("⏳", "TA们的日程", dp)
        val reminderStorage = ReminderStorage(this)
        val pendingReminders = reminderStorage.getAllPending()
        if (pendingReminders.isEmpty()) {
            addEmptyHint("暂时没有日程\nAI 聊天时会自己安排", dp)
        } else {
            for (reminder in pendingReminders) {
                addReminderCard(reminder, dp)
            }
        }

        // ===== 历史入口 =====
        val completedAlarms = alarmStorage.getCompletedAlarms().size
        val completedReminders = reminderStorage.getTriggered().size
        val totalCompleted = completedAlarms + completedReminders
        if (totalCompleted > 0) {
            val histBtn = TextView(this).apply {
                text = "📋  历史：${totalCompleted} 个已完成"
                textSize = 12f
                setTextColor(c.textHint)
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(14), dp(10), dp(14))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
                setOnClickListener {
                    startActivity(Intent(this@ClockActivity, ClockHistoryActivity::class.java))
                }
            }
            clockContainer.addView(histBtn)
        }
    }

    private fun addSectionHeader(icon: String, title: String, dp: (Int) -> Int) {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(0), dp(2), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) }
        }
        val iconTv = TextView(this).apply {
            text = icon
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) }
        }
        val titleTv = TextView(this).apply {
            text = title
            textSize = 13f
            setTextColor(c.textSecondary)
        }
        header.addView(iconTv)
        header.addView(titleTv)
        clockContainer.addView(header)
    }

    private fun addEmptyHint(text: String, dp: (Int) -> Int) {
        val hint = TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(c.textHint)
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.4f)
            setPadding(dp(20), dp(24), dp(20), dp(24))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        clockContainer.addView(hint)
    }

    private fun addAlarmCard(alarm: AlarmStorage.HavenAlarm, dp: (Int) -> Int) {
        val cardBg = GradientDrawable().apply {
            setColor(c.card)
            cornerRadius = dp(10).toFloat()
            setStroke(1, c.border)
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }

        // 第一行：时间 + 备注 + 标签
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val timeTv = TextView(this).apply {
            text = String.format("%02d:%02d", alarm.hour, alarm.minute)
            textSize = 16f
            setTextColor(c.textPrimary)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(10) }
        }
        val noteTv = TextView(this).apply {
            text = alarm.note.ifEmpty { "闹钟" }
            textSize = 12f
            setTextColor(c.textSecondary)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tagBg = GradientDrawable().apply {
            setColor(c.accentBg)
            cornerRadius = dp(6).toFloat()
        }
        val tagTv = TextView(this).apply {
            text = if (alarm.alsoSystem) "系统+归栖" else "归栖"
            textSize = 9f
            setTextColor(c.accent)
            background = tagBg
            setPadding(dp(6), dp(2), dp(6), dp(2))
        }
        row1.addView(timeTv)
        row1.addView(noteTv)
        row1.addView(tagTv)
        card.addView(row1)

        // 第二行：谁设的 + 重复
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        }
        if (alarm.setByName.isNotEmpty()) {
            val setByTv = TextView(this).apply {
                text = "${alarm.setByIcon} ${alarm.setByName} 设的"
                textSize = 10f
                setTextColor(c.accent)
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            }
            row2.addView(setByTv)
        } else {
            val spacer = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            }
            row2.addView(spacer)
        }
        val repeatTv = TextView(this).apply {
            text = when (alarm.repeat) {
                "daily" -> "🔁 每天"
                "weekdays" -> "🔁 工作日"
                else -> "📅 一次性"
            }
            textSize = 10f
            setTextColor(c.textHint)
        }
        row2.addView(repeatTv)
        card.addView(row2)
        clockContainer.addView(card)
    }

    private fun addReminderCard(reminder: ReminderStorage.Reminder, dp: (Int) -> Int) {
        val friend = FriendStorage(this).getFriend(reminder.friendId)
        val name = friend?.name ?: "AI"
        val icon = friend?.icon ?: "🤖"

        val cardBg = GradientDrawable().apply {
            setColor(c.card)
            cornerRadius = dp(10).toFloat()
            setStroke(1, c.border)
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }

        // 第一行：头像 + 名字 + 时间
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val avatarBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(c.accentBg)
        }
        val avatarTv = TextView(this).apply {
            text = icon
            textSize = 12f
            gravity = Gravity.CENTER
            background = avatarBg
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply {
                marginEnd = dp(8)
            }
        }
        val nameTv = TextView(this).apply {
            text = name
            textSize = 12f
            setTextColor(c.textSecondary)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val triggerTime = SimpleDateFormat("M/d HH:mm", Locale.getDefault()).format(Date(reminder.triggerAt))
        val now = System.currentTimeMillis()
        val isToday = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).let {
            it.format(Date(reminder.triggerAt)) == it.format(Date(now))
        }
        val timeLabel = if (isToday) {
            "今天 " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(reminder.triggerAt))
        } else {
            triggerTime
        }
        val timeTv = TextView(this).apply {
            text = "⏰ $timeLabel"
            textSize = 10f
            setTextColor(c.textHint)
        }
        row1.addView(avatarTv)
        row1.addView(nameTv)
        row1.addView(timeTv)
        card.addView(row1)

        // 第二行：理由
        val reasonTv = TextView(this).apply {
            text = reminder.reason
            textSize = 12f
            setTextColor(c.textPrimary)
            setLineSpacing(0f, 1.4f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        }
        card.addView(reasonTv)

        // 第三行：状态
        val statusTv = TextView(this).apply {
            text = "⏳ 等待中"
            textSize = 10f
            setTextColor(c.warning)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(3) }
        }
        card.addView(statusTv)
        clockContainer.addView(card)
    }

    private fun getNextSystemAlarm(): String? {
        return try {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val info = am.nextAlarmClock ?: return null
            val time = Date(info.triggerTime)
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(time)
        } catch (e: Exception) { null }
    }

    // ===== 模拟表盘 =====
    inner class AnalogClockView(context: Context) : View(context) {
        private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = c.borderMedium
        }
        private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = 1.5f
            color = c.textSecondary
            strokeCap = Paint.Cap.ROUND
        }
        private val tickMinorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = 1f
            color = c.border
            strokeCap = Paint.Cap.ROUND
        }
        private val hourPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = 3.5f
            color = c.textPrimary
            strokeCap = Paint.Cap.ROUND
        }
        private val minutePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = 2f
            color = c.textPrimary
            strokeCap = Paint.Cap.ROUND
        }
        private val secondPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = 1f
            color = c.accent
            strokeCap = Paint.Cap.ROUND
        }
        private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = c.textPrimary
        }
        private val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 13f
            color = c.textSecondary
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT
        }
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = c.card
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val radius = Math.min(cx, cy) - 4f

            // 表盘背景
            canvas.drawCircle(cx, cy, radius, bgPaint)
            canvas.drawCircle(cx, cy, radius, facePaint)

            // 刻度
            for (i in 0 until 60) {
                val angle = Math.toRadians((i * 6 - 90).toDouble())
                val isMajor = i % 5 == 0
                val outerR = radius - 4f
                val innerR = if (isMajor) radius - 16f else radius - 10f
                val paint = if (isMajor) tickPaint else tickMinorPaint
                canvas.drawLine(
                    cx + (outerR * Math.cos(angle)).toFloat(),
                    cy + (outerR * Math.sin(angle)).toFloat(),
                    cx + (innerR * Math.cos(angle)).toFloat(),
                    cy + (innerR * Math.sin(angle)).toFloat(),
                    paint
                )
            }

            // 数字
            val nums = arrayOf("12", "3", "6", "9")
            val positions = arrayOf(
                floatArrayOf(cx, cy - radius + 28f),
                floatArrayOf(cx + radius - 26f, cy + 5f),
                floatArrayOf(cx, cy + radius - 20f),
                floatArrayOf(cx - radius + 26f, cy + 5f)
            )
            for (i in nums.indices) {
                canvas.drawText(nums[i], positions[i][0], positions[i][1], numPaint)
            }

            // 指针
            val cal = Calendar.getInstance()
            val h = cal.get(Calendar.HOUR)
            val m = cal.get(Calendar.MINUTE)
            val s = cal.get(Calendar.SECOND)

            // 时针
            val hAngle = Math.toRadians(((h + m / 60f) * 30 - 90).toDouble())
            val hLen = radius * 0.45f
            canvas.drawLine(cx, cy, cx + (hLen * Math.cos(hAngle)).toFloat(), cy + (hLen * Math.sin(hAngle)).toFloat(), hourPaint)

            // 分针
            val mAngle = Math.toRadians(((m + s / 60f) * 6 - 90).toDouble())
            val mLen = radius * 0.65f
            canvas.drawLine(cx, cy, cx + (mLen * Math.cos(mAngle)).toFloat(), cy + (mLen * Math.sin(mAngle)).toFloat(), minutePaint)

            // 秒针
            val sAngle = Math.toRadians((s * 6 - 90).toDouble())
            val sLen = radius * 0.72f
            canvas.drawLine(cx, cy, cx + (sLen * Math.cos(sAngle)).toFloat(), cy + (sLen * Math.sin(sAngle)).toFloat(), secondPaint)

            // 中心点
            canvas.drawCircle(cx, cy, 4f, centerPaint)
        }
    }
}