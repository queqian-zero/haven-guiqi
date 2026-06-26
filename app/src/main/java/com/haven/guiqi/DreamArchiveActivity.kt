package com.haven.guiqi

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
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

        // 光尘粒子：替换占位 View
        try {
            val placeholder = findViewById<View>(R.id.particleCanvas)
            val parent = placeholder.parent as? android.view.ViewGroup
            if (parent != null) {
                val index = parent.indexOfChild(placeholder)
                parent.removeView(placeholder)
                parent.addView(DustParticleView(this), index)
            }
        } catch (_: Exception) { }

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
            tab.setPadding(dp(9), dp(4), dp(9), dp(4))
            tab.setTextColor(if (selected) 0xFFE8E0F0.toInt() else 0xFF5A6A80.toInt())
            tab.background = bg
            tab.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(5) }
            tab.setOnClickListener { currentFilter = value; buildFilterTabs(); loadDreams() }
            filterTabs.addView(tab)
        }
    }

    private fun loadDreams() {
        dreamList.removeAllViews()
        try {
            val storage = DreamStorage(this)
            val allDreams = storage.loadDreams(friendId).sortedByDescending { it.createdAt }

            // 过滤掉 NO_DREAM（不该显示在档案里）
            val realDreams = allDreams.filter { it.status != "NO_DREAM" }
            val filtered = if (currentFilter != null) realDreams.filter { it.status == currentFilter } else realDreams
            tvCount.text = "${filtered.size}"

            if (filtered.isEmpty()) {
                val empty = TextView(this)
                empty.text = if (realDreams.isEmpty()) "还没有做过梦" else "没有这类梦境"
                empty.textSize = 13f
                empty.setTextColor(0xFF5A6A80.toInt())
                empty.gravity = Gravity.CENTER
                empty.setPadding(dp(20), dp(60), dp(20), dp(60))
                dreamList.addView(empty)
                return
            }

            // 按睡眠时段分组（sleepAt 差不超过 30 分钟的梦属于同一次睡眠）
            val sortedDreams = filtered.sortedBy { it.sleepAt }
            val sessionMap = mutableMapOf<Long, MutableList<Dream>>()
            for (dream in sortedDreams) {
                // 找有没有已有的分组 sleepAt 跟这个梦差不超过 30 分钟
                val matchKey = sessionMap.keys.find {
                    Math.abs(dream.sleepAt - it) < 30 * 60 * 1000L
                }
                if (matchKey != null) {
                    sessionMap[matchKey]!!.add(dream)
                } else {
                    sessionMap[dream.sleepAt] = mutableListOf(dream)
                }
            }
            val sessions = sessionMap.toSortedMap(compareByDescending { it })

            for ((sleepAt, dreams) in sessions) {
                // 睡眠时段头
                val lastDream = dreams.maxByOrNull { it.wakeAt } ?: continue
                val wakeAt = if (lastDream.wakeAt > sleepAt) lastDream.wakeAt else lastDream.createdAt
                addSessionHeader(sleepAt, wakeAt, dreams.size)

                // 这个时段的梦
                for (dream in dreams.sortedBy { it.createdAt }) {
                    try {
                        dreamList.addView(buildDreamCard(dream))
                    } catch (_: Exception) {
                        val err = TextView(this)
                        err.text = "⚠ 梦境加载失败"
                        err.textSize = 12f
                        err.setTextColor(0xFFFF6666.toInt())
                        err.setPadding(dp(8), dp(8), dp(8), dp(8))
                        dreamList.addView(err)
                    }
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

    /** 睡眠时段头部卡片 */
    private fun addSessionHeader(sleepAt: Long, wakeAt: Long, dreamCount: Int) {
        val sleepStr = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date(sleepAt))
        val wakeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(wakeAt))
        val durationMs = wakeAt - sleepAt
        val hours = durationMs / 3600000
        val minutes = (durationMs % 3600000) / 60000
        val durStr = if (hours > 0) "${hours}小时${minutes}分" else "${minutes}分钟"
        val dreamWord = if (dreamCount == 1) "做了 1 个梦" else "做了 $dreamCount 个梦"

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(14), dp(6), dp(6))
        }

        val moonIcon = TextView(this).apply {
            text = "🌙"
            textSize = 13f
            setPadding(0, 0, dp(8), 0)
        }

        val info = TextView(this).apply {
            text = "$sleepStr → $wakeStr · 睡了$durStr · $dreamWord"
            textSize = 11f
            setTextColor(0xFF7A8AA0.toInt())
        }

        header.addView(moonIcon)
        header.addView(info)
        dreamList.addView(header)
    }

    private fun buildDreamCard(dream: Dream): View {
        return when (dream.status) {
            "VIVID" -> buildCard(dream, "清晰", 0xD0162040.toInt(), 0xD01C2A4A.toInt(),
                0xFF2A3A5A.toInt(), 0xFFC9A0DC.toInt(), 0xFF1E1040.toInt(), 0xFFD8DEE9.toInt())
            "FOGGY" -> buildCard(dream, "模糊", 0xD0151D35.toInt(), 0xD019243E.toInt(),
                0xFF1F3050.toInt(), 0xFF8B7BAA.toInt(), 0xFF18152E.toInt(), 0xFF6B7890.toInt())
            "FRAGMENT" -> buildCard(dream, "碎片", 0xC012182C.toInt(), 0xC0151C30.toInt(),
                0xFF1A2540.toInt(), 0xFF5A5A7A.toInt(), 0xFF13132A.toInt(), 0xFF3A4560.toInt())
            "FORGOT" -> buildCard(dream, "忘了", 0xB0101828.toInt(), 0xB0121C2A.toInt(),
                0xFF152030.toInt(), 0xFF3A4055.toInt(), 0xFF101525.toInt(), 0xFF2A3045.toInt())
            "NO_DREAM" -> buildNoDreamCard(dream)
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

        // 情绪标签（如果有的话）
        if (dream.mood.isNotEmpty()) {
            val moodBg = GradientDrawable().apply {
                setColor(labelBg)
                cornerRadius = dp(10).toFloat()
            }
            headerRow.addView(TextView(this).apply {
                text = dream.mood
                textSize = 10f
                setTextColor(accentColor)
                background = moodBg
                setPadding(dp(6), dp(2), dp(6), dp(2))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(4) }
            })
        }

        card.addView(headerRow)

        // 内容（默认折叠）
        val content = when (dream.status) {
            "FOGGY" -> dream.foggyHint.ifEmpty { dream.content }.ifEmpty { "好像做了什么梦...但想不起来了" }
            "FRAGMENT" -> {
                val text = dream.content
                if (text.length > 30) text.substring(0, 30) + "——" else text + "——"
            }
            "FORGOT" -> "做了梦，但什么都想不起来了。"
            else -> dream.content
        }

        // 预览（一行，折叠时显示）
        val previewView = TextView(this)
        previewView.text = content.take(30).replace("\n", " ") + if (content.length > 30) "..." else ""
        previewView.textSize = 12f
        previewView.setTextColor(textColor)
        previewView.alpha = 0.6f
        previewView.maxLines = 1
        previewView.setPadding(0, dp(6), 0, 0)
        card.addView(previewView)

        // 完整内容（展开时显示）
        val contentView = TextView(this)
        contentView.text = content
        contentView.textSize = 13f
        contentView.setTextColor(textColor)
        contentView.setLineSpacing(0f, 1.65f)
        contentView.setPadding(0, dp(8), 0, 0)
        contentView.visibility = View.GONE
        card.addView(contentView)

        // 底部标注（展开时显示）
        val bottomHint = if (dream.status == "FOGGY") {
            TextView(this).apply {
                text = "记不太清了"; textSize = 11f
                setTextColor(0xFF5A5A7A.toInt()); gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, 0); visibility = View.GONE
            }
        } else if (dream.status == "FRAGMENT") {
            TextView(this).apply {
                text = "梦到一半断了"; textSize = 11f
                setTextColor(0xFF3A3A55.toInt()); gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, 0); visibility = View.GONE
            }
        } else null
        if (bottomHint != null) card.addView(bottomHint)

        // 点击折叠/展开
        card.setOnClickListener {
            val expanding = contentView.visibility == View.GONE
            contentView.visibility = if (expanding) View.VISIBLE else View.GONE
            previewView.visibility = if (expanding) View.GONE else View.VISIBLE
            bottomHint?.visibility = if (expanding) View.VISIBLE else View.GONE
        }

        return card
    }

    private fun buildNoDreamCard(dream: Dream): View {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        val bg = GradientDrawable()
        bg.setColor(0xA00F1626.toInt())
        bg.cornerRadius = dp(14).toFloat()
        bg.setStroke(2, 0xFF1A2540.toInt())
        // 虚线边框需要在代码里设置 dashWidth 和 dashGap，但 GradientDrawable.setStroke 支持
        card.background = bg
        card.setPadding(dp(18), dp(14), dp(18), dp(14))
        card.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) }

        // 时间
        val dateStr = java.text.SimpleDateFormat("yyyy/MM/dd", java.util.Locale.getDefault())
            .format(java.util.Date(dream.createdAt))
        val headerRow = LinearLayout(this)
        headerRow.orientation = LinearLayout.HORIZONTAL
        headerRow.gravity = Gravity.CENTER_VERTICAL

        val dateView = TextView(this)
        dateView.text = dateStr
        dateView.textSize = 11f
        dateView.setTextColor(0xFF3A4560.toInt())
        dateView.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        headerRow.addView(dateView)

        val labelView = TextView(this)
        labelView.text = "无梦"
        labelView.textSize = 10f
        labelView.setTextColor(0xFF2A3550.toInt())
        headerRow.addView(labelView)

        card.addView(headerRow)

        val contentView = TextView(this)
        contentView.text = "这一晚什么都没有。"
        contentView.textSize = 12f
        contentView.setTextColor(0xFF2A3550.toInt())
        contentView.setPadding(0, dp(6), 0, 0)
        card.addView(contentView)

        // 睡眠信息
        val sleepStr = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(dream.sleepAt))
        val durationMs = if (dream.wakeAt > dream.sleepAt) dream.wakeAt - dream.sleepAt else 0L
        val hours = durationMs / 3600000
        val minutes = (durationMs % 3600000) / 60000
        val durStr = if (hours > 0) "${hours}h${minutes}m" else "${minutes}m"

        val divider = View(this)
        divider.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
        ).apply { topMargin = dp(8) }
        divider.setBackgroundColor(0xFF1A2540.toInt())
        card.addView(divider)

        val sleepInfo = TextView(this)
        sleepInfo.text = "入睡于 $sleepStr · 睡了 $durStr"
        sleepInfo.textSize = 11f
        sleepInfo.setTextColor(0xFF3A4560.toInt())
        sleepInfo.setPadding(0, dp(6), 0, 0)
        card.addView(sleepInfo)

        return card
    }
}

/**
 * 光尘粒子 — 很慢很慢往上飘的光点
 * 像月光照进来之后空气里看得见的浮尘
 */
class DustParticleView(context: Context) : View(context) {
    private data class Particle(
        var x: Float, var y: Float,
        var speed: Float, var alpha: Float,
        var size: Float, var drift: Float,
        var fadeDir: Int
    )

    private val particles = mutableListOf<Particle>()
    private val paint = Paint().apply { isAntiAlias = true }
    private val random = Random()

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 50
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { updateParticles(); invalidate() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return
        particles.clear()
        for (i in 0 until 35) {
            particles.add(createParticle(w, h, true))
        }
        animator.start()
    }

    private fun createParticle(w: Int, h: Int, randomY: Boolean = false): Particle {
        return Particle(
            x = random.nextFloat() * w,
            y = if (randomY) random.nextFloat() * h else h + random.nextFloat() * 50,
            speed = 0.15f + random.nextFloat() * 0.35f,
            alpha = random.nextFloat() * 0.4f,
            size = 1f + random.nextFloat() * 2.5f,
            drift = (random.nextFloat() - 0.5f) * 0.3f,
            fadeDir = if (random.nextBoolean()) 1 else -1
        )
    }

    private fun updateParticles() {
        val w = width; val h = height
        if (w == 0 || h == 0) return
        for (i in particles.indices) {
            val p = particles[i]
            p.y -= p.speed
            p.x += p.drift
            p.alpha += p.fadeDir * 0.004f
            if (p.alpha >= 0.5f) p.fadeDir = -1
            if (p.alpha <= 0.05f) p.fadeDir = 1
            p.alpha = p.alpha.coerceIn(0.02f, 0.5f)
            if (p.y < -10 || p.x < -10 || p.x > w + 10) {
                particles[i] = createParticle(w, h)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        for (p in particles) {
            paint.color = 0xFFE8E0F0.toInt()
            paint.alpha = (p.alpha * 255).toInt()
            canvas.drawCircle(p.x, p.y, p.size, paint)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator.cancel()
    }
}