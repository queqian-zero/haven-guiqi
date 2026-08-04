package com.haven.guiqi

import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.transition.AutoTransition
import android.transition.TransitionManager
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
import kotlin.math.roundToInt

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
    private lateinit var pagerBar: View

    private var friendId = ""
    private var friendName = ""
    private var folderType = ""

    private var currentPage = 0
    private val pageSize = 5

    /** 聊天总结抽屉：三只抽屉分别记住自己的页码，且同一时间只展开一只。 */
    private var expandedSummaryState: SummaryMemoryState? = null
    private val summaryDrawerPages = mutableMapOf(
        SummaryMemoryState.CLEAR to 0,
        SummaryMemoryState.FUZZY to 0,
        SummaryMemoryState.FORGOTTEN to 0
    )
    private val summaryDrawerViews = mutableMapOf<SummaryMemoryState, SummaryDrawerUi>()

    /** 当前主题色 */
    private val c get() = ThemeHelper.getColors(this)

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
        insetsController.isAppearanceLightStatusBars = !ThemeHelper.isDark(this)

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
        pagerBar = findViewById(R.id.pagerBar)

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }

        friendId = intent.getStringExtra("friend_id") ?: ""
        friendName = intent.getStringExtra("friend_name") ?: "好友"
        folderType = intent.getStringExtra("folder_type") ?: "memory"

        // 梦境走专属页面
        if (folderType == "dream") {
            val dreamIntent = android.content.Intent(this, DreamArchiveActivity::class.java)
            dreamIntent.putExtra("friend_id", friendId)
            dreamIntent.putExtra("friend_name", friendName)
            startActivity(dreamIntent)
            finish()
            return
        }

        // 根据类型设置标题和颜色
        when (folderType) {
            "memory" -> {
                tvTitle.text = "$friendName / 核心记忆"
                tvFolderName.text = "核心记忆"
                setBarColor(c.folderMemory)
            }
            "diary" -> {
                tvTitle.text = "$friendName / 日记"
                tvFolderName.text = "日记"
                setBarColor(c.folderDiary)
            }
            "dream" -> {
                tvTitle.text = "$friendName / 梦境"
                tvFolderName.text = "梦境"
                setBarColor(c.folderDream)
            }
            "summary" -> {
                tvTitle.text = "$friendName / 聊天总结"
                tvFolderName.text = "聊天总结"
                setBarColor(c.folderSummary)
            }
            "summary_clear" -> {
                tvTitle.text = "$friendName / 清晰记忆"
                tvFolderName.text = "清晰记忆"
                setBarColor(c.folderSummary)
            }
            "summary_fuzzy" -> {
                tvTitle.text = "$friendName / 模糊记忆"
                tvFolderName.text = "模糊记忆"
                setBarColor(c.folderSummary)
            }
            "summary_forgotten" -> {
                tvTitle.text = "$friendName / 遗忘区"
                tvFolderName.text = "遗忘区"
                setBarColor(c.folderSummary)
            }
            "trash" -> {
                tvTitle.text = "$friendName / 废纸篓"
                tvFolderName.text = "废纸篓"
                setBarColor(c.folderTrash)
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

        if (folderType == "summary") {
            renderSummaryDrawerHub()
        } else {
            renderPage()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::pagesContainer.isInitialized && folderType == "summary") {
            renderSummaryDrawerHub()
        }
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
            "summary", "summary_clear", "summary_fuzzy", "summary_forgotten" ->
                loadSummariesForCurrentFolder().size
            "trash" -> MemoryStorage(this).loadTrash(friendId).size + DiaryStorage(this).loadTrash(friendId).size
            else -> 0
        }
    }

    private fun isSummaryFolder(): Boolean {
        return folderType == "summary" || folderType.startsWith("summary_")
    }

    private fun summaryStateForCurrentFolder(): SummaryMemoryState? {
        return when (folderType) {
            "summary_clear" -> SummaryMemoryState.CLEAR
            "summary_fuzzy" -> SummaryMemoryState.FUZZY
            "summary_forgotten" -> SummaryMemoryState.FORGOTTEN
            else -> null
        }
    }

    private fun loadSummariesForCurrentFolder(): List<ChatSummary> {
        val storage = ChatSummaryStorage(this)
        val state = summaryStateForCurrentFolder()
        return if (state == null) {
            storage.loadSummaries(friendId)
        } else {
            storage.loadSummariesByState(friendId, state)
        }
    }

    private fun buildSummaryArchiveItems(
        state: SummaryMemoryState? = summaryStateForCurrentFolder()
    ): List<ArchiveItem> {
        val storage = ChatSummaryStorage(this)
        val summaries = if (state == null) {
            storage.loadSummaries(friendId)
        } else {
            storage.loadSummariesByState(friendId, state)
        }
        return summaries.reversed().mapIndexed { index, summary ->
            val dateStr = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
                .format(Date(summary.createdAt))
            val state = storage.getMemoryState(summary)
            val strengthPercent = (summary.strength * 100.0).roundToInt().coerceIn(0, 100)
            val strengthLabel = when (state) {
                SummaryMemoryState.CLEAR -> "🔵 清晰"
                SummaryMemoryState.FUZZY -> "🌫️ 模糊"
                SummaryMemoryState.FORGOTTEN -> "💤 遗忘"
            }
            val metadataText = buildList {
                if (summary.messageRange.isNotBlank()) {
                    add("范围：${summary.messageRange}")
                }
                if (summary.keywords.isNotBlank()) {
                    add("关键词：${summary.keywords}")
                }
            }.joinToString("\n")

            ArchiveItem(
                id = summary.id,
                number = String.format("SUM-%03d", index + 1),
                date = "$dateStr  $strengthLabel ${strengthPercent}%",
                content = summary.content,
                globalIndex = index,
                rawContent = summary.content,
                metadata = metadataText,
                summaryState = state
            )
        }
    }

    /**
     * 渲染当前页的档案纸
     */
    private fun renderPage() {
        if (folderType == "summary") {
            renderSummaryDrawerHub()
            return
        }

        pagerBar.visibility = View.VISIBLE
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
            "summary", "summary_clear", "summary_fuzzy", "summary_forgotten" -> {
                buildSummaryArchiveItems()
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
        btnPrev.setTextColor(if (currentPage > 0) c.accent else c.textHint)
        btnNext.setTextColor(if (currentPage < totalPages - 1) c.accent else c.textHint)

        if (total == 0) {
            val empty = TextView(this).apply {
                text = when (folderType) {
                    "memory" -> "还没有核心记忆"
                    "diary" -> "还没有日记"
                    "dream" -> "还没有做过梦"
                    "summary" -> "还没有聊天总结"
                    "summary_clear" -> "目前没有清晰的聊天记忆"
                    "summary_fuzzy" -> "目前没有模糊的聊天记忆"
                    "summary_forgotten" -> "遗忘区还是空的"
                    "trash" -> "废纸篓是空的"
                    else -> "空的"
                }
                textSize = 13f
                setTextColor(c.textHint)
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
     * “聊天总结”里的三只收纳抽屉。
     *
     * 抽屉负责展开/收起的档案感；每只抽屉内部独立分页，避免总结越积越长。
     * 这些说明只存在于人类可见界面，不会写入 AI 的记忆或系统提示词。
     */
    private fun renderSummaryDrawerHub() {
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }
        val storage = ChatSummaryStorage(this)
        val counts = storage.countByState(friendId)
        val total = counts.clear + counts.fuzzy + counts.forgotten

        pagesContainer.removeAllViews()
        summaryDrawerViews.clear()
        pagerBar.visibility = View.GONE
        tvPageInfo.text = "共 $total 条"

        addSummaryDrawer(
            state = SummaryMemoryState.CLEAR,
            title = "清晰记忆",
            count = counts.clear,
            badge = "≥ 50%",
            description = "AI 在正常聊天中可以看到完整总结。长按档案纸可以加强、复制或删除总结。",
            accentColor = c.folderSummary,
            emptyText = "目前没有清晰的聊天记忆",
            dp = dp
        )

        addSummaryDrawer(
            state = SummaryMemoryState.FUZZY,
            title = "模糊记忆",
            count = counts.fuzzy,
            badge = "20%～49%",
            description = "AI 平时只会得到日期和关键词，看不到完整总结；你仍然可以查看原文。",
            accentColor = c.folderSummary,
            emptyText = "目前没有模糊的聊天记忆",
            dp = dp
        )

        addSummaryDrawer(
            state = SummaryMemoryState.FORGOTTEN,
            title = "遗忘区",
            count = counts.forgotten,
            badge = "< 20%",
            description = "AI 平时不会自然看到这些总结；原始聊天仍保留在“留声”里，少量内容可能按现有规则偶尔浮现。",
            accentColor = c.folderSummary,
            emptyText = "遗忘区还是空的",
            dp = dp
        )

        // 从其他页面回来或操作记忆后，保留刚才打开的那只抽屉。
        expandedSummaryState?.let { state ->
            summaryDrawerViews[state]?.let { ui ->
                renderSummaryDrawerPage(ui)
                ui.body.visibility = View.VISIBLE
                ui.arrow.text = "⌄"
            }
        }
    }

    private fun addSummaryDrawer(
        state: SummaryMemoryState,
        title: String,
        count: Int,
        badge: String,
        description: String,
        accentColor: Int,
        emptyText: String,
        dp: (Int) -> Int
    ) {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }

        val drawerBg = GradientDrawable().apply {
            setColor(c.border)
            cornerRadius = dp(12).toFloat()
            setStroke(1, (accentColor and 0x00FFFFFF) or 0x55000000)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = drawerBg
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            isClickable = true
            isFocusable = true
        }

        val handleBg = GradientDrawable().apply {
            setColor((accentColor and 0x00FFFFFF) or 0x66000000)
            cornerRadius = dp(3).toFloat()
        }
        val handle = View(this).apply {
            background = handleBg
            layoutParams = LinearLayout.LayoutParams(dp(5), dp(54)).apply {
                marginEnd = dp(12)
            }
        }
        header.addView(handle)

        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleView = TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(c.textPrimary)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val countView = TextView(this).apply {
            text = "$count 条  ·  $badge"
            textSize = 10f
            setTextColor(if (count > 0) accentColor else c.textHint)
        }
        titleRow.addView(titleView)
        titleRow.addView(countView)
        textColumn.addView(titleRow)

        val descriptionView = TextView(this).apply {
            text = description
            textSize = 11f
            setTextColor(c.textSecondary)
            setLineSpacing(0f, 1.35f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }
        textColumn.addView(descriptionView)
        header.addView(textColumn)

        val arrow = TextView(this).apply {
            text = "›"
            textSize = 20f
            setTextColor(c.textHint)
            setPadding(dp(8), 0, 0, 0)
        }
        header.addView(arrow)
        wrapper.addView(header)

        val bodyBg = GradientDrawable().apply {
            setColor(c.accentBg)
            cornerRadii = floatArrayOf(
                dp(4).toFloat(), dp(4).toFloat(),
                dp(4).toFloat(), dp(4).toFloat(),
                dp(12).toFloat(), dp(12).toFloat(),
                dp(12).toFloat(), dp(12).toFloat()
            )
            setStroke(1, c.paperBorder)
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bodyBg
            setPadding(dp(8), dp(10), dp(8), dp(8))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(4)
                marginStart = dp(4)
                marginEnd = dp(4)
            }
        }
        val cardsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        body.addView(cardsContainer)

        val localPager = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(2), dp(4), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val prev = TextView(this).apply {
            text = "‹  上一页"
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(10), dp(8), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val pageInfo = TextView(this).apply {
            textSize = 10f
            setTextColor(c.textSecondary)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val next = TextView(this).apply {
            text = "下一页  ›"
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(10), dp(8), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        localPager.addView(prev)
        localPager.addView(pageInfo)
        localPager.addView(next)
        body.addView(localPager)
        wrapper.addView(body)
        pagesContainer.addView(wrapper)

        val ui = SummaryDrawerUi(
            state = state,
            body = body,
            cardsContainer = cardsContainer,
            pager = localPager,
            pageInfo = pageInfo,
            prev = prev,
            next = next,
            arrow = arrow,
            emptyText = emptyText
        )
        summaryDrawerViews[state] = ui

        header.setOnClickListener { toggleSummaryDrawer(state) }
        prev.setOnClickListener {
            val page = summaryDrawerPages[state] ?: 0
            if (page > 0) {
                summaryDrawerPages[state] = page - 1
                renderSummaryDrawerPage(ui, animateCards = true)
            }
        }
        next.setOnClickListener {
            val totalPages = summaryDrawerTotalPages(state)
            val page = summaryDrawerPages[state] ?: 0
            if (page < totalPages - 1) {
                summaryDrawerPages[state] = page + 1
                renderSummaryDrawerPage(ui, animateCards = true)
            }
        }
    }

    private fun toggleSummaryDrawer(state: SummaryMemoryState) {
        val target = summaryDrawerViews[state] ?: return
        val shouldOpen = target.body.visibility != View.VISIBLE

        TransitionManager.beginDelayedTransition(
            pagesContainer,
            AutoTransition().apply { duration = 220L }
        )

        summaryDrawerViews.forEach { (drawerState, ui) ->
            if (drawerState != state || !shouldOpen) {
                ui.body.visibility = View.GONE
                ui.arrow.text = "›"
            }
        }

        if (shouldOpen) {
            if (!target.rendered) renderSummaryDrawerPage(target)
            target.body.visibility = View.VISIBLE
            target.arrow.text = "⌄"
            target.body.alpha = 0.35f
            target.body.translationY = -resources.displayMetrics.density * 8f
            target.body.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(180L)
                .start()
            expandedSummaryState = state
        } else {
            expandedSummaryState = null
        }
    }

    private fun summaryDrawerTotalPages(state: SummaryMemoryState): Int {
        val total = ChatSummaryStorage(this).loadSummariesByState(friendId, state).size
        return if (total == 0) 1 else Math.ceil(total.toDouble() / pageSize).toInt()
    }

    private fun renderSummaryDrawerPage(ui: SummaryDrawerUi, animateCards: Boolean = false) {
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }
        val items = buildSummaryArchiveItems(ui.state)
        val total = items.size
        val totalPages = if (total == 0) 1 else Math.ceil(total.toDouble() / pageSize).toInt()
        val safePage = (summaryDrawerPages[ui.state] ?: 0).coerceIn(0, totalPages - 1)
        summaryDrawerPages[ui.state] = safePage

        ui.cardsContainer.removeAllViews()

        if (total == 0) {
            val empty = TextView(this).apply {
                text = ui.emptyText
                textSize = 13f
                setTextColor(c.textHint)
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(30), dp(12), dp(30))
            }
            ui.cardsContainer.addView(empty)
            ui.pager.visibility = View.GONE
        } else {
            val start = safePage * pageSize
            val end = minOf(start + pageSize, total)
            items.subList(start, end).forEach { item ->
                ui.cardsContainer.addView(buildArchivePaper(item, total, dp))
            }
            ui.pager.visibility = View.VISIBLE
            ui.pageInfo.text = "${safePage + 1} / $totalPages"
            ui.prev.setTextColor(if (safePage > 0) c.accent else c.textHint)
            ui.next.setTextColor(if (safePage < totalPages - 1) c.accent else c.textHint)
        }

        ui.rendered = true
        if (animateCards) {
            ui.cardsContainer.alpha = 0.2f
            ui.cardsContainer.translationX = resources.displayMetrics.density * 10f
            ui.cardsContainer.animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(160L)
                .start()
        }
    }

    private fun showSummaryActions(item: ArchiveItem) {
        val wakeLabel = if (item.summaryState == SummaryMemoryState.CLEAR) {
            "加强这段记忆"
        } else {
            "让它重新清晰"
        }

        android.app.AlertDialog.Builder(this)
            .setTitle("这段聊天记忆要怎么处理？")
            .setItems(arrayOf(wakeLabel, "复制原始总结", "永久删除总结")) { _, which ->
                when (which) {
                    0 -> {
                        val reinforced = ChatSummaryStorage(this).reinforceById(friendId, item.id)
                        if (reinforced) {
                            Toast.makeText(this, "这段记忆已经重新清晰", Toast.LENGTH_SHORT).show()
                            if (folderType == "summary") {
                                expandedSummaryState = SummaryMemoryState.CLEAR
                                summaryDrawerPages[SummaryMemoryState.CLEAR] = 0
                                renderSummaryDrawerHub()
                            } else {
                                renderPage()
                            }
                        }
                    }
                    1 -> copyToClipboard(item.rawContent)
                    2 -> confirmDeleteSummary(item)
                }
            }
            .show()
    }

    private fun confirmDeleteSummary(item: ArchiveItem) {
        android.app.AlertDialog.Builder(this)
            .setTitle("确定删除这条总结？")
            .setMessage("这里只会删除自动生成的聊天总结；留声里的原始聊天不会被删除。")
            .setPositiveButton("删除") { _, _ ->
                val deleted = ChatSummaryStorage(this).deleteSummary(friendId, item.id)
                if (deleted) {
                    Toast.makeText(this, "聊天总结已删除", Toast.LENGTH_SHORT).show()
                    if (folderType == "summary") {
                        renderSummaryDrawerHub()
                    } else {
                        renderPage()
                    }
                }
            }
            .setNegativeButton("保留", null)
            .show()
    }

    private fun copyToClipboard(content: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("archive", content))
        Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
    }

    /**
     * 构建一张档案纸
     */
    private fun buildArchivePaper(item: ArchiveItem, total: Int, dp: (Int) -> Int): LinearLayout {
        // 档案纸背景
        val paperBg = GradientDrawable().apply {
            setColor(c.paperBg)
            cornerRadius = dp(3).toFloat()
            setStroke(1, c.paperBorder)
        }

        val paper = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = paperBg
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }

            // 长按操作
            setOnLongClickListener {
                if (folderType == "trash") {
                    // 废纸篓：恢复或永久删除
                    android.app.AlertDialog.Builder(this@ArchiveFolderActivity)
                        .setTitle("这条要怎么处理？")
                        .setItems(arrayOf("恢复", "永久删除", "复制内容")) { _, which ->
                            when (which) {
                                0 -> {
                                    val restored = if (item.id.startsWith("MEM")) {
                                        MemoryStorage(this@ArchiveFolderActivity).restoreFromTrash(friendId, item.id)
                                    } else {
                                        DiaryStorage(this@ArchiveFolderActivity).restoreFromTrash(friendId, item.id)
                                    }
                                    if (restored) {
                                        Toast.makeText(this@ArchiveFolderActivity, "已恢复", Toast.LENGTH_SHORT).show()
                                        renderPage()
                                    }
                                }
                                1 -> {
                                    android.app.AlertDialog.Builder(this@ArchiveFolderActivity)
                                        .setTitle("确定永久删除？")
                                        .setMessage("删了就真没了。")
                                        .setPositiveButton("删除") { _, _ ->
                                            val deleted = if (item.id.startsWith("MEM")) {
                                                MemoryStorage(this@ArchiveFolderActivity).permanentDelete(friendId, item.id)
                                            } else {
                                                DiaryStorage(this@ArchiveFolderActivity).permanentDelete(friendId, item.id)
                                            }
                                            if (deleted) {
                                                Toast.makeText(this@ArchiveFolderActivity, "已永久删除", Toast.LENGTH_SHORT).show()
                                                renderPage()
                                            }
                                        }
                                        .setNegativeButton("算了", null)
                                        .show()
                                }
                                2 -> {
                                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("archive", item.content))
                                    Toast.makeText(this@ArchiveFolderActivity, "已复制", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        .show()
                } else if (isSummaryFolder()) {
                    showSummaryActions(item)
                } else {
                    copyToClipboard(item.rawContent)
                }
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
                setStroke(1, c.paperBorder)
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
            setTextColor(c.paperMeta)
            typeface = android.graphics.Typeface.MONOSPACE
            letterSpacing = 0.08f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val dateView = TextView(this).apply {
            text = item.date
            textSize = 9f
            setTextColor(c.paperMeta)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        headerRow.addView(numberView)
        headerRow.addView(dateView)
        contentColumn.addView(headerRow)

        // 虚线分隔
        val dashedLine = TextView(this).apply {
            text = "─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─"
            textSize = 6f
            setTextColor(c.paperBorder)
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
            setTextColor(c.paperText)
            setLineSpacing(0f, 1.5f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = if (item.metadata.isBlank()) dp(8) else dp(4) }
        }
        contentColumn.addView(contentView)

        // 范围、关键词等系统元信息：独立小字区，不混进记忆正文
        if (item.metadata.isNotBlank()) {
            val metadataView = TextView(this).apply {
                text = item.metadata
                textSize = 9f
                setTextColor(c.paperMeta)
                setLineSpacing(0f, 1.3f)
                typeface = android.graphics.Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            }
            contentColumn.addView(metadataView)
        }

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
            setTextColor(c.paperBorder)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        // Haven 印章
        val stampBg = GradientDrawable().apply {
            setColor(0x00000000.toInt())
            cornerRadius = dp(2).toFloat()
            setStroke(1, c.stampColor)
        }
        val stamp = TextView(this).apply {
            text = "Haven"
            textSize = 7f
            setTextColor(c.stampColor)
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
            setBackgroundColor(c.paperBorder)
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
        val globalIndex: Int,   // 全局编号（用于显示 1/5）
        val rawContent: String = content,
        val metadata: String = "",
        val summaryState: SummaryMemoryState? = null
    )

    private data class SummaryDrawerUi(
        val state: SummaryMemoryState,
        val body: LinearLayout,
        val cardsContainer: LinearLayout,
        val pager: LinearLayout,
        val pageInfo: TextView,
        val prev: TextView,
        val next: TextView,
        val arrow: TextView,
        val emptyText: String,
        var rendered: Boolean = false
    )
}
