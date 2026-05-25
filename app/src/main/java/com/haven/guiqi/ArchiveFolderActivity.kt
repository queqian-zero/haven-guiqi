package com.haven.guiqi

import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.text.SimpleDateFormat
import java.util.*

/**
 * ArchiveFolderActivity - 打开文件夹后的档案纸页面
 *
 * 每条记忆/日记显示成一张档案纸：
 * - 左侧有装订孔
 * - 顶部虚线分隔，编号在左，日期在右
 * - 中间是内容
 * - 右下角有"Haven"小印章
 * - 底部有页码（第几条/共几条）
 *
 * 分页显示，每页 5 条，底部有翻页按钮
 */
class ArchiveFolderActivity : AppCompatActivity() {

    private lateinit var pagesContainer: LinearLayout
    private lateinit var tvTitle: TextView
    private lateinit var tvFolderName: TextView
    private lateinit var tvPageInfo: TextView
    private lateinit var tvPager: TextView
    private lateinit var btnPrev: TextView
    private lateinit var btnNext: TextView
    private lateinit var colorBar: View

    private var friendId = ""
    private var friendName = ""
    private var folderType = ""

    private var currentPage = 0
    private val pageSize = 5

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        setContentView(R.layout.activity_archive_folder)

        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        insetsController.hide(WindowInsetsCompat.Type.navigationBars())
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController.isAppearanceLightStatusBars = false

        val contentView = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(contentView) { view, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.setPadding(0, top, 0, 0)
            insets
        }

        pagesContainer = findViewById(R.id.pagesContainer)
        tvTitle = findViewById(R.id.tvTitle)
        tvFolderName = findViewById(R.id.tvFolderName)
        tvPageInfo = findViewById(R.id.tvPageInfo)
        tvPager = findViewById(R.id.tvPager)
        btnPrev = findViewById(R.id.btnPrev)
        btnNext = findViewById(R.id.btnNext)
        colorBar = findViewById(R.id.colorBar)

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }

        friendId = intent.getStringExtra("friend_id") ?: ""
        friendName = intent.getStringExtra("friend_name") ?: "好友"
        folderType = intent.getStringExtra("folder_type") ?: "memory"

        // 根据类型设置标题和颜色
        when (folderType) {
            "memory" -> {
                tvTitle.text = "$friendName / 核心记忆"
                tvFolderName.text = "核心记忆"
                setBarColor(0xFF78B48C.toInt())
            }
            "diary" -> {
                tvTitle.text = "$friendName / 日记"
                tvFolderName.text = "日记"
                setBarColor(0xFFB48C64.toInt())
            }
            "dream" -> {
                tvTitle.text = "$friendName / 梦境"
                tvFolderName.text = "梦境"
                setBarColor(0xFF8C78B4.toInt())
            }
            "summary" -> {
                tvTitle.text = "$friendName / 聊天总结"
                tvFolderName.text = "聊天总结"
                setBarColor(0xFF6496B4.toInt())
            }
            "trash" -> {
                tvTitle.text = "$friendName / 废纸篓"
                tvFolderName.text = "废纸篓"
                setBarColor(0xFFA07878.toInt())
            }
        }

        btnPrev.setOnClickListener {
            if (currentPage > 0) {
                currentPage--
                renderPage()
            }
        }
        btnNext.setOnClickListener {
            val total = getTotalCount()
            val totalPages = Math.ceil(total.toDouble() / pageSize).toInt()
            if (currentPage < totalPages - 1) {
                currentPage++
                renderPage()
            }
        }

        renderPage()
    }

    private fun setBarColor(color: Int) {
        val barBg = GradientDrawable().apply {
            setColor(color)
            cornerRadius = (2 * resources.displayMetrics.density)
        }
        colorBar.background = barBg
    }

    private fun getTotalCount(): Int {
        return when (folderType) {
            "memory" -> MemoryStorage(this).loadMemories(friendId).size
            "diary" -> DiaryStorage(this).loadDiaries(friendId).size
            "dream" -> DreamStorage(this).loadDreams(friendId).size
            "summary" -> ChatSummaryStorage(this).count(friendId)
            "trash" -> MemoryStorage(this).loadTrash(friendId).size + DiaryStorage(this).loadTrash(friendId).size
            else -> 0
        }
    }

    /**
     * 渲染当前页的档案纸
     */
    private fun renderPage() {
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }
        pagesContainer.removeAllViews()

        // 加载数据
        val items: List<ArchiveItem> = when (folderType) {
            "memory" -> {
                MemoryStorage(this).loadMemories(friendId).mapIndexed { index, m ->
                    ArchiveItem(
                        id = m.id,
                        number = String.format("MEM-%03d", index + 1),
                        date = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
                            .format(Date(m.createdAt)),
                        content = m.content,
                        globalIndex = index
                    )
                }
            }
            "diary" -> {
                DiaryStorage(this).loadDiaries(friendId).reversed().mapIndexed { index, d ->
                    ArchiveItem(
                        id = d.id,
                        number = String.format("DRY-%03d", index + 1),
                        date = d.date,
                        content = d.content,
                        globalIndex = index
                    )
                }
            }
            "dream" -> {
                val dreamStorage = DreamStorage(this)
                dreamStorage.loadDreams(friendId).mapIndexed { index, d ->
                    val dateStr = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
                        .format(Date(d.createdAt))
                    val statusLabel = dreamStorage.getStatusLabel(d)
                    val displayText = dreamStorage.getDisplayText(d)
                    ArchiveItem(
                        id = d.id,
                        number = String.format("DRM-%03d", index + 1),
                        date = "$dateStr  $statusLabel",
                        content = displayText,
                        globalIndex = index
                    )
                }
            }
            "summary" -> {
                val summaryStorage = ChatSummaryStorage(this)
                summaryStorage.loadSummaries(friendId).reversed().mapIndexed { index, s ->
                    val dateStr = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
                        .format(Date(s.createdAt))
                    // 强度标签
                    val strengthLabel = when {
                        s.strength >= 0.5 -> "🔵 清晰"
                        s.strength >= 0.2 -> "🌫️ 模糊"
                        else -> "💭 淡化"
                    }
                    val displayText = if (s.strength >= 0.5) {
                        s.content
                    } else if (s.strength >= 0.2) {
                        "关键词: ${s.keywords}\n\n（细节已经模糊了…）"
                    } else {
                        "（这段对话的记忆已经很淡了…）\n关键词: ${s.keywords}"
                    }
                    ArchiveItem(
                        id = s.id,
                        number = String.format("SUM-%03d", index + 1),
                        date = "$dateStr  $strengthLabel",
                        content = displayText,
                        globalIndex = index
                    )
                }
            }
            "trash" -> {
                // 合并记忆废纸篓和日记废纸篓
                val memTrash = MemoryStorage(this).loadTrash(friendId).map { m ->
                    val dateStr = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
                        .format(Date(m.createdAt))
                    ArchiveItem(
                        id = m.id,
                        number = "📌",
                        date = "$dateStr  来自核心记忆",
                        content = m.content,
                        globalIndex = 0
                    )
                }
                val diaryTrash = DiaryStorage(this).loadTrash(friendId).map { d ->
                    ArchiveItem(
                        id = d.id,
                        number = "📔",
                        date = "${d.date}  来自日记",
                        content = d.content,
                        globalIndex = 0
                    )
                }
                val combined = (memTrash + diaryTrash).sortedByDescending {
                    it.date
                }
                combined.mapIndexed { index, item ->
                    item.copy(globalIndex = index)
                }
            }
            else -> emptyList()
        }

        val total = items.size
        val totalPages = if (total == 0) 1 else Math.ceil(total.toDouble() / pageSize).toInt()

        // 确保当前页合法
        if (currentPage >= totalPages) currentPage = totalPages - 1
        if (currentPage < 0) currentPage = 0

        tvPageInfo.text = "共 ${total} 条，第 ${currentPage + 1}/${totalPages} 页"
        tvPager.text = "第 ${currentPage + 1} 页 / 共 ${totalPages} 页"

        // 分页按钮状态
        btnPrev.setTextColor(if (currentPage > 0) 0x4DB3A0FF.toInt() else 0x26FFFFFF.toInt())
        btnNext.setTextColor(if (currentPage < totalPages - 1) 0x4DB3A0FF.toInt() else 0x26FFFFFF.toInt())

        if (total == 0) {
            val empty = TextView(this).apply {
                text = when (folderType) {
                    "memory" -> "还没有核心记忆"
                    "diary" -> "还没有日记"
                    "dream" -> "还没有做过梦"
                    "summary" -> "还没有聊天总结"
                    "trash" -> "废纸篓是空的"
                    else -> "空的"
                }
                textSize = 13f
                setTextColor(0x33FFFFFF.toInt())
                gravity = Gravity.CENTER
                setPadding(dp(20), dp(60), dp(20), dp(60))
            }
            pagesContainer.addView(empty)
            return
        }

        // 取当前页的数据
        val start = currentPage * pageSize
        val end = minOf(start + pageSize, total)
        val pageItems = items.subList(start, end)

        for (item in pageItems) {
            val paper = buildArchivePaper(item, total, dp)
            pagesContainer.addView(paper)
        }
    }

    /**
     * 构建一张档案纸
     *
     * 视觉结构：
     * ┌──────────────────────────┐
     * │ ○                        │  ← 装订孔
     * │ ○  MEM-001    2026/05/19 │  ← 编号 + 日期（虚线下方）
     * │    ─ ─ ─ ─ ─ ─ ─ ─ ─ ─  │
     * │    沈眠喜欢猫             │  ← 内容
     * │                   Haven  │  ← 印章
     * │ ○        1 / 5           │  ← 页码
     * └──────────────────────────┘
     */
    private fun buildArchivePaper(item: ArchiveItem, total: Int, dp: (Int) -> Int): LinearLayout {
        // 档案纸背景：暖色泛黄
        val paperBg = GradientDrawable().apply {
            setColor(0xFF1E1E16.toInt())
            cornerRadius = dp(3).toFloat()
            setStroke(1, 0x26B4A078.toInt())
        }

        val paper = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = paperBg
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }

            // 长按复制内容
            setOnLongClickListener {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("archive", item.content))
                Toast.makeText(this@ArchiveFolderActivity, "已复制", Toast.LENGTH_SHORT).show()
                true
            }
        }

        // 左侧装订孔区域
        val holesColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(12), dp(4), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        // 两个装订孔
        for (i in 0..1) {
            val holeBg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x33000000.toInt())
                setStroke(1, 0x26B4A078.toInt())
            }
            val hole = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(6), dp(6)).apply {
                    bottomMargin = if (i == 0) dp(16) else 0
                }
                background = holeBg
            }
            holesColumn.addView(hole)
        }
        paper.addView(holesColumn)

        // 右侧内容区域
        val contentColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dp(4), dp(8), dp(10), dp(6))
        }

        // 第一行：编号 + 日期
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) }
        }
        val numberView = TextView(this).apply {
            text = item.number
            textSize = 9f
            setTextColor(0x66B4A078.toInt())
            typeface = android.graphics.Typeface.MONOSPACE
            letterSpacing = 0.08f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val dateView = TextView(this).apply {
            text = item.date
            textSize = 9f
            setTextColor(0x59B4A078.toInt())
            typeface = android.graphics.Typeface.MONOSPACE
        }
        headerRow.addView(numberView)
        headerRow.addView(dateView)
        contentColumn.addView(headerRow)

        // 虚线分隔
        val dashedLine = TextView(this).apply {
            text = "─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─"
            textSize = 6f
            setTextColor(0x1AB4A078.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
        }
        contentColumn.addView(dashedLine)

        // 内容
        val contentView = TextView(this).apply {
            text = item.content
            textSize = 12f
            setTextColor(0xB3E6DCC0.toInt())
            setLineSpacing(0f, 1.5f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        contentColumn.addView(contentView)

        // 底部：印章 + 页码
        val footerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // 页码（居中）
        val pageNo = TextView(this).apply {
            text = "${item.globalIndex + 1} / $total"
            textSize = 8f
            setTextColor(0x33B4A078.toInt())
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        // Haven 印章
        val stampBg = GradientDrawable().apply {
            setColor(0x00000000.toInt())
            cornerRadius = dp(2).toFloat()
            setStroke(1, 0x1AB3A0FF.toInt())
        }
        val stamp = TextView(this).apply {
            text = "Haven"
            textSize = 7f
            setTextColor(0x33B3A0FF.toInt())
            background = stampBg
            setPadding(dp(5), dp(1), dp(5), dp(1))
            rotation = -3f
        }

        footerRow.addView(pageNo)
        footerRow.addView(stamp)
        contentColumn.addView(footerRow)

        // 顶部的装饰线（档案纸顶部的细线）
        val topLine = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(2)
            )
            setBackgroundColor(0x0DB4A078.toInt())
        }

        // 用一个外层包装，先放顶线再放内容
        val outerWrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        outerWrapper.addView(topLine)

        // 把 paper 加到 outer
        paper.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        paper.addView(contentColumn)
        outerWrapper.addView(paper)

        return outerWrapper
    }

    /**
     * 档案纸的数据
     */
    private data class ArchiveItem(
        val id: String,
        val number: String,     // MEM-001 / DRY-001
        val date: String,
        val content: String,
        val globalIndex: Int    // 全局编号（用于显示 1/5）
    )
}