package com.haven.guiqi

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * ArchiveActivity - 馆藏主页
 *
 * 两个标签页：
 * - 书城：暂时占位
 * - 档案馆：档案柜风格，每个 AI 是一个抽屉
 *
 * 档案柜的视觉设计：
 * - 外壳是深色金属质感的圆角矩形
 * - 顶部有一小条深色的"柜顶"
 * - 每个好友是一个"抽屉"，有拉手和标签
 * - 按分组分成不同的柜子
 */
class ArchiveActivity : AppCompatActivity() {

    private lateinit var tabLibrary: TextView
    private lateinit var tabArchive: TextView
    private lateinit var libraryPage: LinearLayout
    private lateinit var archivePage: View
    private lateinit var cabinetContainer: LinearLayout

    /** 当前主题色 */
    private val c get() = ThemeHelper.getColors(this)
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 读取文件并自动处理编码 */
    private fun readFileContent(uri: Uri): String {
        val inputStream = contentResolver.openInputStream(uri) ?: return ""
        val rawBytes = inputStream.readBytes()
        inputStream.close()
        val content = try {
            val text = rawBytes.toString(Charsets.UTF_8)
            if (text.count { it == '\uFFFD' } > text.length / 10) {
                rawBytes.toString(charset("GBK"))
            } else { text }
        } catch (_: Exception) {
            try { rawBytes.toString(charset("GBK")) }
            catch (_: Exception) { rawBytes.toString(Charsets.UTF_8) }
        }
        return content.trimStart('\uFEFF')
    }

    /** 从 URI 提取文件名 */
    private fun getFileName(uri: Uri): String {
        return uri.lastPathSegment?.substringAfterLast("/")?.removeSuffix(".txt") ?: "未命名"
    }

    /** 多文件选择器 */
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@registerForActivityResult
        try {
            if (uris.size == 1) {
                // 单个文件直接导入
                val content = readFileContent(uris[0])
                val fileName = getFileName(uris[0])
                val book = BookStorage(this).importTxt(fileName, content)
                Toast.makeText(this, "导入成功：${book.title}（${book.chapters.size}章）", Toast.LENGTH_SHORT).show()
                loadBookShelf()
            } else {
                // 多个文件，问合并还是分开
                android.app.AlertDialog.Builder(this)
                    .setTitle("选了 ${uris.size} 个文件")
                    .setItems(arrayOf(
                        "合并成一本书（每个文件变一章）",
                        "分开导入（每个文件一本书）"
                    )) { _, which ->
                        when (which) {
                            0 -> mergeImport(uris)
                            1 -> separateImport(uris)
                        }
                    }.show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "导入失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** 合并导入：弹窗输入书名，多文件合成一本 */
    private fun mergeImport(uris: List<Uri>) {
        val input = android.widget.EditText(this).apply {
            hint = "输入书名"
            setPadding(48, 32, 48, 32)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("合并成一本书")
            .setView(input)
            .setPositiveButton("导入") { _, _ ->
                try {
                    val title = input.text.toString().trim().ifEmpty { "合集" }
                    val files = uris.sortedBy { getFileName(it) }.map { uri ->
                        getFileName(uri) to readFileContent(uri)
                    }
                    val book = BookStorage(this).importMultipleTxt(title, files)
                    Toast.makeText(this, "导入成功：${book.title}（${book.chapters.size}章）", Toast.LENGTH_SHORT).show()
                    loadBookShelf()
                } catch (e: Exception) {
                    Toast.makeText(this, "导入失败：${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 分开导入：每个文件一本书 */
    private fun separateImport(uris: List<Uri>) {
        var count = 0
        for (uri in uris) {
            try {
                val content = readFileContent(uri)
                val fileName = getFileName(uri)
                BookStorage(this).importTxt(fileName, content)
                count++
            } catch (_: Exception) { }
        }
        Toast.makeText(this, "导入了 $count 本书", Toast.LENGTH_SHORT).show()
        loadBookShelf()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        setContentView(R.layout.activity_archive)

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

        tabLibrary = findViewById(R.id.tabLibrary)
        tabArchive = findViewById(R.id.tabArchive)
        libraryPage = findViewById(R.id.libraryPage)
        archivePage = findViewById(R.id.archivePage)
        cabinetContainer = findViewById(R.id.cabinetContainer)

        findViewById<TextView>(R.id.btnBack).setOnClickListener {
            startActivity(Intent(this, DesktopActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            })
            finish()
        }

        tabLibrary.setOnClickListener { switchTab(false) }
        tabArchive.setOnClickListener { switchTab(true) }

        switchTab(false)
    }

    override fun onResume() {
        super.onResume()
        loadCabinets()
        if (libraryPage.visibility == View.VISIBLE) loadBookShelf()
    }

    private fun switchTab(isArchive: Boolean) {
        if (isArchive) {
            tabArchive.setTextColor(c.textPrimary)
            tabLibrary.setTextColor(c.textHint)
            archivePage.visibility = View.VISIBLE
            libraryPage.visibility = View.GONE
            loadCabinets()
        } else {
            tabLibrary.setTextColor(c.textPrimary)
            tabArchive.setTextColor(c.textHint)
            libraryPage.visibility = View.VISIBLE
            archivePage.visibility = View.GONE
            loadBookShelf()
        }
    }

    /**
     * 加载档案柜
     * 按好友分组来分柜子，每个组一个柜子
     */
    private fun loadCabinets() {
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }
        cabinetContainer.removeAllViews()

        val friends = FriendStorage(this).loadFriends()

        if (friends.isEmpty()) {
            val tip = TextView(this).apply {
                text = "还没有好友的档案\n先去聊天 App 里添加好友吧"
                textSize = 13f
                setTextColor(c.textHint)
                gravity = Gravity.CENTER
                setLineSpacing(0f, 1.4f)
                setPadding(dp(20), dp(60), dp(20), dp(60))
            }
            cabinetContainer.addView(tip)
            return
        }

        // 按分组分柜子
        val grouped = friends.groupBy { it.group.ifEmpty { "好友" } }

        for ((group, groupFriends) in grouped) {
            val cabinet = buildCabinet(group, groupFriends, dp)
            cabinetContainer.addView(cabinet)
        }
    }

    /**
     * 构建一个档案柜（一个分组）
     */
    private fun buildCabinet(group: String, friends: List<Friend>, dp: (Int) -> Int): LinearLayout {
        // 柜子外壳
        val cabinetBg = GradientDrawable().apply {
            setColor(c.cabinetBg)
            cornerRadius = dp(10).toFloat()
            setStroke(dp(1), c.cabinetBorder)
        }

        val cabinet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cabinetBg
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }

        // 柜顶（深色窄条，模拟金属柜子顶部）
        val topBar = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(8)
            )
            val topBg = GradientDrawable().apply {
                setColor(c.cabinetTop)
                cornerRadii = floatArrayOf(
                    dp(10).toFloat(), dp(10).toFloat(), // 左上
                    dp(10).toFloat(), dp(10).toFloat(), // 右上
                    0f, 0f, 0f, 0f  // 下方不圆角
                )
            }
            background = topBg
        }
        cabinet.addView(topBar)

        // 分组标签
        val groupLabel = TextView(this).apply {
            text = group
            textSize = 11f
            setTextColor(c.textSecondary)
            setPadding(dp(14), dp(8), dp(14), dp(6))
            letterSpacing = 0.05f
        }
        cabinet.addView(groupLabel)

        // 分隔线
        val sep = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
            ).apply { marginStart = dp(12); marginEnd = dp(12) }
            setBackgroundColor(c.divider)
        }
        cabinet.addView(sep)

        // 每个好友一个抽屉
        for ((index, friend) in friends.withIndex()) {
            val drawer = buildDrawer(friend, dp)
            cabinet.addView(drawer)

            // 抽屉之间的分隔线（最后一个不加）
            if (index < friends.size - 1) {
                val drawerSep = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
                    ).apply { marginStart = dp(52); marginEnd = dp(12) }
                    setBackgroundColor(c.border)
                }
                cabinet.addView(drawerSep)
            }
        }

        return cabinet
    }

    /**
     * 构建一个抽屉（一个好友）
     */
    private fun buildDrawer(friend: Friend, dp: (Int) -> Int): LinearLayout {
        val memoryCount = MemoryStorage(this).count(friend.id)
        val diaryCount = DiaryStorage(this).count(friend.id)
        val hasImpression = ImpressionStorage(this).getImpression(friend.id).isNotEmpty()

        // 统计文字
        val stats = mutableListOf<String>()
        if (memoryCount > 0) stats.add("${memoryCount}条记忆")
        if (diaryCount > 0) stats.add("${diaryCount}篇日记")
        if (hasImpression) stats.add("有印象")
        val statsText = if (stats.isEmpty()) "还没有档案" else stats.joinToString(" · ")

        val drawer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(13), dp(14), dp(13))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

            // 点击进入详情页
            setOnClickListener {
                val intent = Intent(this@ArchiveActivity, ArchiveDetailActivity::class.java)
                intent.putExtra("friend_id", friend.id)
                intent.putExtra("friend_name", friend.name)
                intent.putExtra("friend_icon", friend.icon)
                startActivity(intent)
            }
        }

        // 拉手（小矩形，模拟抽屉把手）
        val handleBg = GradientDrawable().apply {
            setColor(c.drawerHandle)
            cornerRadius = dp(3).toFloat()
            setStroke(1, c.borderMedium)
        }
        val handle = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(10)).apply {
                marginEnd = dp(12)
            }
            background = handleBg
        }

        // 名字 + 统计（竖排）
        val labelColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val nameView = TextView(this).apply {
            text = friend.name
            textSize = 14f
            setTextColor(c.textPrimary)
        }
        val statView = TextView(this).apply {
            text = statsText
            textSize = 10f
            setTextColor(c.accent)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2) }
        }
        labelColumn.addView(nameView)
        labelColumn.addView(statView)

        // 头像
        val avatar = TextView(this).apply {
            text = friend.icon
            textSize = 22f
            gravity = Gravity.CENTER
        }

        drawer.addView(handle)
        drawer.addView(labelColumn)
        drawer.addView(avatar)

        return drawer
    }

    // ===== 书城：书架 UI =====

    private fun loadBookShelf() {
        libraryPage.removeAllViews()
        libraryPage.gravity = Gravity.CENTER

        // 加载提示
        val loadingText = TextView(this).apply {
            text = "📚 书架整理中..."
            textSize = 14f
            setTextColor(c.textHint)
            gravity = Gravity.CENTER
        }
        libraryPage.addView(loadingText)

        // 后台加载，不卡 UI
        Thread {
            val books = BookStorage(this).loadBooksMeta()
            runOnUiThread { buildShelfUI(books) }
        }.start()
    }

    private fun buildShelfUI(books: List<BookStorage.Book>) {
        libraryPage.removeAllViews()
        libraryPage.gravity = Gravity.NO_GRAVITY

        // 顶部：导入按钮
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val importBtn = TextView(this).apply {
            text = "＋ 导入"
            textSize = 13f
            setTextColor(c.accent)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setStroke(1, c.accent)
            }
            setOnClickListener {
                filePickerLauncher.launch(arrayOf("text/plain"))
            }
        }
        topBar.addView(importBtn)
        libraryPage.addView(topBar)

        if (books.isEmpty()) {
            libraryPage.addView(TextView(this).apply {
                text = "书架是空的\n点右上角「＋ 导入」添加书籍"
                textSize = 13f
                setTextColor(c.textHint)
                gravity = Gravity.CENTER
                setPadding(dp(20), dp(80), dp(20), dp(80))
                setLineSpacing(0f, 1.5f)
            })
            return
        }

        // 按宽度动态填满每排，不固定几本
        val screenWidth = resources.displayMetrics.widthPixels
        val shelfPadding = dp(32)  // 左右各 16dp
        val maxShelfWidth = screenWidth - shelfPadding

        val shelves = mutableListOf<MutableList<BookStorage.Book>>()
        var currentShelf = mutableListOf<BookStorage.Book>()
        var currentWidth = 0

        for (book in books) {
            val chapterCount = book.chapters.size
            val thickness = (18 + Math.sqrt(chapterCount.toDouble()) * 1.2).toInt().coerceIn(18, 56)
            val bookWidth = dp(thickness) + dp(2)  // 书宽 + 间距

            if (currentWidth + bookWidth > maxShelfWidth && currentShelf.isNotEmpty()) {
                shelves.add(currentShelf)
                currentShelf = mutableListOf()
                currentWidth = 0
            }
            currentShelf.add(book)
            currentWidth += bookWidth
        }
        if (currentShelf.isNotEmpty()) shelves.add(currentShelf)

        for (shelfBooks in shelves) {
            // 书架层板
            val shelfRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.BOTTOM
                setPadding(dp(16), dp(8), dp(16), 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            for (book in shelfBooks) {
                shelfRow.addView(buildBookSpine(book))
            }

            libraryPage.addView(shelfRow)

            // 木头层板
            libraryPage.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(4)
                ).apply { marginStart = dp(12); marginEnd = dp(12) }
                background = GradientDrawable().apply {
                    setColor(c.folderDiary) // 棕色
                    cornerRadius = dp(2).toFloat()
                }
            })
        }
    }

    private fun buildBookSpine(book: BookStorage.Book): View {
        val chapterCount = book.chapters.size
        // 厚度用 sqrt 但系数小、上限高，让 300 章和 900 章明显不同
        val thickness = (18 + Math.sqrt(chapterCount.toDouble()) * 1.2).toInt().coerceIn(18, 56)
        // 高度稍微随机
        val baseHeight = 85 + (book.id.hashCode() % 12)
        val height = baseHeight.coerceIn(80, 98)

        val spine = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(thickness), dp(height)).apply {
                marginEnd = dp(2)
            }
            background = GradientDrawable().apply {
                setColor(book.spineColor)
                cornerRadius = dp(3).toFloat()
            }
            setPadding(dp(3), dp(6), dp(3), dp(6))
            setOnClickListener { showBookDetail(book) }
            setOnLongClickListener {
                android.app.AlertDialog.Builder(this@ArchiveActivity)
                    .setTitle("删除「${book.title}」？")
                    .setPositiveButton("删除") { _, _ ->
                        BookStorage(this@ArchiveActivity).deleteBook(book.id)
                        loadBookShelf()
                    }
                    .setNegativeButton("取消", null)
                    .show()
                true
            }
        }

        // 书名竖排
        val title = book.title
        val displayTitle = if (title.length > 6) title.substring(0, 6) + "…" else title
        val titleView = TextView(this).apply {
            text = displayTitle.toList().joinToString("\n")
            textSize = 9f
            setTextColor(0xDDFFFFFF.toInt())
            gravity = Gravity.CENTER
            setLineSpacing(0f, 0.85f)
        }
        spine.addView(titleView)

        return spine
    }

    private fun showBookDetail(book: BookStorage.Book) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(16))
        }

        // 书的封面（用书脊颜色做一个矩形）
        val cover = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(120), dp(160)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(16)
            }
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(book.spineColor)
                cornerRadius = dp(6).toFloat()
            }
            setPadding(dp(10), dp(12), dp(10), dp(12))
        }
        val coverTitle = TextView(this).apply {
            text = book.title
            textSize = 14f
            setTextColor(0xDDFFFFFF.toInt())
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.3f)
        }
        cover.addView(coverTitle)
        layout.addView(cover)

        // 书名
        layout.addView(TextView(this).apply {
            text = book.title
            textSize = 16f
            setTextColor(c.textPrimary)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(4))
        })

        // 作者
        if (book.author.isNotEmpty()) {
            layout.addView(TextView(this).apply {
                text = book.author
                textSize = 12f
                setTextColor(c.textSecondary)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(8))
            })
        }

        // 信息
        val dateStr = java.text.SimpleDateFormat("yyyy/MM/dd", java.util.Locale.getDefault())
            .format(java.util.Date(book.createdAt))
        val progress = if (book.chapters.isNotEmpty())
            "读到第 ${book.lastChapter + 1}/${book.chapters.size} 章" else "还没开始读"

        layout.addView(TextView(this).apply {
            text = "共 ${book.chapters.size} 章 · 导入于 $dateStr\n$progress"
            textSize = 11f
            setTextColor(c.textHint)
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.4f)
            setPadding(0, 0, 0, dp(12))
        })

        android.app.AlertDialog.Builder(this)
            .setView(layout)
            .setPositiveButton("开始阅读") { _, _ ->
                val intent = Intent(this, BookReaderActivity::class.java)
                intent.putExtra("book_id", book.id)
                startActivity(intent)
            }
            .setNeutralButton("编辑") { _, _ -> showEditBookDialog(book) }
            .setNegativeButton("放回去", null)
            .show()
    }

    private fun showEditBookDialog(book: BookStorage.Book) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(12))
        }

        val inputTitle = android.widget.EditText(this).apply {
            setText(book.title)
            hint = "书名"
            textSize = 14f
            setTextColor(c.textPrimary)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        layout.addView(inputTitle)

        val inputAuthor = android.widget.EditText(this).apply {
            setText(book.author)
            hint = "作者（可留空）"
            textSize = 14f
            setTextColor(c.textPrimary)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        layout.addView(inputAuthor)

        // 书脊颜色选择
        layout.addView(TextView(this).apply {
            text = "书脊颜色"
            textSize = 12f
            setTextColor(c.textSecondary)
            setPadding(dp(12), dp(16), 0, dp(6))
        })

        val colors = intArrayOf(
            0xFF8B4513.toInt(), 0xFFA0522D.toInt(), 0xFF6B3A2A.toInt(),
            0xFF2F4F4F.toInt(), 0xFF483D8B.toInt(), 0xFF556B2F.toInt(),
            0xFF8B0000.toInt(), 0xFF4A3728.toInt(), 0xFF2E4057.toInt(),
            0xFF5D4037.toInt(), 0xFF795548.toInt(), 0xFF4E342E.toInt()
        )
        var selectedColor = book.spineColor

        val colorRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), 0, dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        fun refreshColorRow() {
            colorRow.removeAllViews()
            for (color in colors) {
                val dot = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).apply {
                        marginEnd = dp(6); bottomMargin = dp(4)
                    }
                    background = GradientDrawable().apply {
                        setColor(color)
                        cornerRadius = dp(13).toFloat()
                        if (color == selectedColor) setStroke(dp(3), c.accent)
                    }
                    setOnClickListener {
                        selectedColor = color
                        refreshColorRow()
                    }
                }
                colorRow.addView(dot)
            }
        }
        refreshColorRow()
        layout.addView(colorRow)

        android.app.AlertDialog.Builder(this)
            .setTitle("编辑书籍信息")
            .setView(layout)
            .setPositiveButton("保存") { _, _ ->
                val newTitle = inputTitle.text.toString().trim().ifEmpty { book.title }
                val newAuthor = inputAuthor.text.toString().trim()
                // 从存储重新读完整数据再改，防止覆盖章节内容
                val fullBook = BookStorage(this).getBook(book.id) ?: book
                val updated = fullBook.copy(title = newTitle, author = newAuthor, spineColor = selectedColor)
                BookStorage(this).saveBook(updated)
                loadBookShelf()
                Toast.makeText(this, "已更新", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}