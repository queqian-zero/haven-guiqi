package com.haven.guiqi

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
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

class ClockHistoryActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private val c get() = ThemeHelper.getColors(this)

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

        container = findViewById(R.id.clockContainer)
        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btnHistory).visibility = View.GONE

        buildHistory()
    }

    private fun buildHistory() {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        container.removeAllViews()

        // 标题
        val title = TextView(this).apply {
            text = "历史记录"
            textSize = 14f
            setTextColor(c.textSecondary)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }
        }
        container.addView(title)

        // 已完成的闹钟
        val completedAlarms = AlarmStorage(this).getCompletedAlarms()
        val completedReminders = ReminderStorage(this).getTriggered()

        data class HistoryItem(
            val type: String,       // "alarm" 或 "reminder"
            val time: Long,         // 排序用
            val label: String,      // 显示的时间
            val note: String,       // 备注/理由
            val who: String,        // 谁设的
            val whoIcon: String,
            val result: String      // 结果
        )

        val items = mutableListOf<HistoryItem>()

        for (a in completedAlarms) {
            items.add(HistoryItem(
                type = "alarm",
                time = a.createdAt,
                label = String.format("%02d:%02d", a.hour, a.minute),
                note = a.note.ifEmpty { "闹钟" },
                who = if (a.setByName.isNotEmpty()) a.setByName else "我",
                whoIcon = if (a.setByIcon.isNotEmpty()) a.setByIcon else "👤",
                result = "已响"
            ))
        }

        for (r in completedReminders) {
            val friend = FriendStorage(this).getFriend(r.friendId)
            items.add(HistoryItem(
                type = "reminder",
                time = r.triggerAt,
                label = SimpleDateFormat("M/d HH:mm", Locale.getDefault()).format(Date(r.triggerAt)),
                note = r.reason,
                who = friend?.name ?: "AI",
                whoIcon = friend?.icon ?: "🤖",
                result = "醒了"
            ))
        }

        // 按时间倒序
        items.sortByDescending { it.time }

        if (items.isEmpty()) {
            val empty = TextView(this).apply {
                text = "还没有历史记录"
                textSize = 13f
                setTextColor(c.textHint)
                gravity = Gravity.CENTER
                setPadding(dp(20), dp(60), dp(20), dp(60))
            }
            container.addView(empty)
            return
        }

        for (item in items) {
            val cardBg = GradientDrawable().apply {
                setColor(c.card)
                cornerRadius = dp(10).toFloat()
                setStroke(1, c.border)
            }
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = cardBg
                setPadding(dp(12), dp(10), dp(12), dp(10))
                alpha = 0.6f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            }

            val iconTv = TextView(this).apply {
                text = item.whoIcon
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(10) }
            }

            val infoCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val noteTv = TextView(this).apply {
                text = item.note
                textSize = 12f
                setTextColor(c.textSecondary)
            }
            val metaTv = TextView(this).apply {
                text = "${item.who} · ${item.label}"
                textSize = 10f
                setTextColor(c.textHint)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(2) }
            }
            infoCol.addView(noteTv)
            infoCol.addView(metaTv)

            val statusTv = TextView(this).apply {
                text = if (item.type == "alarm") "✓ 已响" else "✓ 醒了"
                textSize = 10f
                setTextColor(c.textHint)
            }

            card.addView(iconTv)
            card.addView(infoCol)
            card.addView(statusTv)
            container.addView(card)
        }
    }
}