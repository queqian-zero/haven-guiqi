package com.haven.guiqi

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import android.graphics.drawable.GradientDrawable
import java.text.SimpleDateFormat
import java.util.*

class DreamArchiveActivity : AppCompatActivity() {

    private lateinit var dreamList: LinearLayout
    private lateinit var filterTabs: LinearLayout
    private lateinit var tvCount: TextView

    private var friendId = ""
    private var friendName = ""
    private var currentFilter: String? = null

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dream_archive)

        friendId = intent.getStringExtra("friend_id") ?: return
        friendName = intent.getStringExtra("friend_name") ?: "好友"

        findViewById<TextView>(R.id.tvTitle).text = "${friendName}的梦境"
        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        tvCount = findViewById(R.id.tvCount)
        filterTabs = findViewById(R.id.filterTabs)
        dreamList = findViewById(R.id.dreamList)

        buildFilterTabs()
        loadDreams()
    }

    private fun buildFilterTabs() {
        filterTabs.removeAllViews()
        val filters = listOf(null to "全部", "VIVID" to "清晰", "FOGGY" to "模糊", "FRAGMENT" to "碎片")
        for ((value, label) in filters) {
            val selected = currentFilter == value
            val bg = GradientDrawable()
            bg.cornerRadius = dp(14).toFloat()
            if (selected) bg.setColor(0xFF1A2B44.toInt())
            else { bg.setColor(0x00000000); bg.setStroke(1, 0xFF1F3050.toInt()) }

            val tab = TextView(this)
            tab.text = label
            tab.textSize = 12f
            tab.setPadding(dp(12), dp(4), dp(12), dp(4))
            tab.setTextColor(if (selected) 0xFFE8E0F0.toInt() else 0xFF5A6A80.toInt())
            tab.background = bg
            tab.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
            tab.setOnClickListener { currentFilter = value; buildFilterTabs(); loadDreams() }
            filterTabs.addView(tab)
        }
    }

    private fun loadDreams() {
        dreamList.removeAllViews()
        try {
            val storage = DreamStorage(this)
            val allDreams = storage.loadDreams(friendId).sortedByDescending { it.createdAt }
            val filtered = if (currentFilter != null) allDreams.filter { it.status == currentFilter } else allDreams
            tvCount.text = "${filtered.size}"

            if (filtered.isEmpty()) {
                val empty = TextView(this)
                empty.text = if (allDreams.isEmpty()) "还没有做过梦" else "没有这类梦境"
                empty.textSize = 13f
                empty.setTextColor(0xFF5A6A80.toInt())
                empty.gravity = Gravity.CENTER
                empty.setPadding(dp(20), dp(60), dp(20), dp(60))
                dreamList.addView(empty)
                return
            }

            for (dream in filtered) {
                try {
                    dreamList.addView(buildDreamCard(dream))
                } catch (e: Exception) {
                    val err = TextView(this)
                    err.text = "⚠ 梦境加载失败"
                    err.textSize = 12f
                    err.setTextColor(0xFFFF6666.toInt())
                    err.setPadding(dp(8), dp(8), dp(8), dp(8))
                    dreamList.addView(err)
                }
            }
        } catch (e: Exception) {
            val err = TextView(this)
            err.text = "⚠ 读取梦境数据失败: ${e.message}"
            err.textSize = 12f
            err.setTextColor(0xFFFF6666.toInt())
            err.setPadding(dp(20), dp(40), dp(20), dp(40))
            dreamList.addView(err)
        }
    }

    private fun buildDreamCard(dream: Dream): View {
        return when (dream.status) {
            "VIVID" -> buildCard(dream, "清晰", 0xFF162040.toInt(), 0xFF1C2A4A.toInt(),
                0xFF2A3A5A.toInt(), 0xFFC9A0DC.toInt(), 0xFF1E1040.toInt(), 0xFFD8DEE9.toInt())
            "FOGGY" -> buildCard(dream, "模糊", 0xFF151D35.toInt(), 0xFF19243E.toInt(),
                0xFF1F3050.toInt(), 0xFF8B7BAA.toInt(), 0xFF18152E.toInt(), 0xFF6B7890.toInt())
            "FRAGMENT" -> buildCard(dream, "碎片", 0xFF12182C.toInt(), 0xFF151C30.toInt(),
                0xFF1A2540.toInt(), 0xFF5A5A7A.toInt(), 0xFF13132A.toInt(), 0xFF3A4560.toInt())
            else -> buildCard(dream, "梦", 0xFF162040.toInt(), 0xFF1C2A4A.toInt(),
                0xFF2A3A5A.toInt(), 0xFFC9A0DC.toInt(), 0xFF1E1040.toInt(), 0xFFD8DEE9.toInt())
        }
    }

    private fun buildCard(
        dream: Dream, label: String,
        bgStart: Int, bgEnd: Int, borderColor: Int,
        accentColor: Int, labelBg: Int, textColor: Int
    ): View {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        val bg = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(bgStart, bgEnd))
        bg.cornerRadius = dp(14).toFloat()
        bg.setStroke(1, borderColor)
        card.background = bg
        card.setPadding(dp(18), dp(16), dp(18), dp(16))
        card.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) }

        // 时间 + 标签行
        val headerRow = LinearLayout(this)
        headerRow.orientation = LinearLayout.HORIZONTAL
        headerRow.gravity = Gravity.CENTER_VERTICAL

        val dateStr = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(dream.createdAt))
        val dateView = TextView(this)
        dateView.text = dateStr
        dateView.textSize = 11f
        dateView.setTextColor(accentColor)
        dateView.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        headerRow.addView(dateView)

        val labelBgDrawable = GradientDrawable()
        labelBgDrawable.setColor(labelBg)
        labelBgDrawable.cornerRadius = dp(10).toFloat()
        val labelView = TextView(this)
        labelView.text = label
        labelView.textSize = 10f
        labelView.setTextColor(accentColor)
        labelView.background = labelBgDrawable
        labelView.setPadding(dp(8), dp(2), dp(8), dp(2))
        headerRow.addView(labelView)

        card.addView(headerRow)

        // 内容
        val content = when (dream.status) {
            "FOGGY" -> dream.foggyHint.ifEmpty { dream.content }.ifEmpty { "好像做了什么梦...但想不起来了" }
            "FRAGMENT" -> {
                val text = dream.content
                if (text.length > 30) text.substring(0, 30) + "——" else text + "——"
            }
            else -> dream.content
        }

        val contentView = TextView(this)
        contentView.text = content
        contentView.textSize = 13f
        contentView.setTextColor(textColor)
        contentView.setLineSpacing(0f, 1.65f)
        contentView.setPadding(0, dp(8), 0, 0)
        card.addView(contentView)

        // 底部标注（模糊/碎片）
        if (dream.status == "FOGGY") {
            val hint = TextView(this)
            hint.text = "记不太清了"
            hint.textSize = 11f
            hint.setTextColor(0xFF5A5A7A.toInt())
            hint.gravity = Gravity.CENTER
            hint.setPadding(0, dp(4), 0, 0)
            card.addView(hint)
        } else if (dream.status == "FRAGMENT") {
            val hint = TextView(this)
            hint.text = "梦到一半断了"
            hint.textSize = 11f
            hint.setTextColor(0xFF3A3A55.toInt())
            hint.gravity = Gravity.CENTER
            hint.setPadding(0, dp(4), 0, 0)
            card.addView(hint)
        }

        // 睡眠信息
        val sleepStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(dream.sleepAt))
        val durationMs = if (dream.wakeAt > dream.sleepAt) dream.wakeAt - dream.sleepAt else 0L
        val hours = durationMs / 3600000
        val minutes = (durationMs % 3600000) / 60000
        val durStr = if (hours > 0) "${hours}h${minutes}m" else "${minutes}m"

        val divider = View(this)
        divider.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
        ).apply { topMargin = dp(10) }
        divider.setBackgroundColor(0xFF1F3050.toInt())
        card.addView(divider)

        val sleepInfo = TextView(this)
        sleepInfo.text = "入睡于 $sleepStr · 睡了 $durStr"
        sleepInfo.textSize = 11f
        sleepInfo.setTextColor(0xFF5A6A80.toInt())
        sleepInfo.setPadding(0, dp(8), 0, 0)
        card.addView(sleepInfo)

        return card
    }
}