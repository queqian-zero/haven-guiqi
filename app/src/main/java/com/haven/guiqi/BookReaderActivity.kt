package com.haven.guiqi

import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.graphics.drawable.GradientDrawable
import androidx.appcompat.app.AppCompatActivity

class BookReaderActivity : AppCompatActivity() {

    private lateinit var bookStorage: BookStorage
    private lateinit var socialStorage: BookSocialStorage
    private var book: BookStorage.Book? = null
    private var currentChapter = 0

    private lateinit var rootLayout: LinearLayout
    private lateinit var presenceBar: LinearLayout
    private lateinit var tvBookTitle: TextView
    private lateinit var tvChapterTitle: TextView
    private lateinit var tvChapterInfo: TextView
    private lateinit var tvContent: TextView
    private lateinit var contentScroll: ScrollView
    private lateinit var annotationsLayout: LinearLayout
    private lateinit var btnPrev: TextView
    private lateinit var btnNext: TextView
    private lateinit var btnList: TextView
    private lateinit var btnFont: TextView
    private lateinit var btnTheme: TextView
    private lateinit var btnAnnotate: TextView
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout

    private var fontSize = 15f
    private var isDarkMode = false

    // 配色
    private val lightBg = 0xFFFDF5E6.toInt()
    private val lightText = 0xFF4E342E.toInt()
    private val lightTitle = 0xFF5D4037.toInt()
    private val lightChapter = 0xFF6D4C41.toInt()
    private val lightHint = 0xFF8B7355.toInt()
    private val lightInfo = 0xFFA08060.toInt()

    private val darkBg = 0xFF1A1A2E.toInt()
    private val darkText = 0xFFCCCCCC.toInt()
    private val darkTitle = 0xFFD8D8D8.toInt()
    private val darkChapter = 0xFFB0B0C0.toInt()
    private val darkHint = 0xFF7A7A8C.toInt()
    private val darkInfo = 0xFF6A6A7A.toInt()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            bookStorage = BookStorage(this)
            socialStorage = BookSocialStorage(this)
            val bookId = intent.getStringExtra("book_id")
            if (bookId == null) { finish(); return }
            book = bookStorage.getBook(bookId)
            if (book == null) { finish(); return }

            currentChapter = book!!.lastChapter.coerceIn(0, book!!.chapters.size - 1)

            // 用户自己的进度优先
            val myProgress = socialStorage.getProgress(book!!.id, "user")
            if (myProgress != null) {
                currentChapter = myProgress.chapter.coerceIn(0, book!!.chapters.size - 1)
            }

            rootLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(lightBg)
            }

            // 顶部栏
            topBar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(8))
            }
            val btnBack = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
                gravity = Gravity.CENTER
                text = "←"; textSize = 18f; setTextColor(lightHint)
                setOnClickListener { saveAndFinish() }
            }
            tvBookTitle = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                gravity = Gravity.CENTER
                textSize = 15f; setTextColor(lightTitle)
            }
            tvChapterInfo = TextView(this).apply {
                textSize = 11f; setTextColor(lightInfo)
            }
            topBar.addView(btnBack)
            topBar.addView(tvBookTitle)
            topBar.addView(tvChapterInfo)
            rootLayout.addView(topBar)

            // 在场状态栏
            presenceBar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(2), dp(14), dp(2))
                visibility = View.GONE
            }
            rootLayout.addView(presenceBar)

            // 章节标题
            tvChapterTitle = TextView(this).apply {
                gravity = Gravity.CENTER
                setPadding(dp(20), 0, dp(20), dp(12))
                textSize = 16f; setTextColor(lightChapter)
            }
            rootLayout.addView(tvChapterTitle)

            // 正文
            contentScroll = ScrollView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                )
                isVerticalScrollBarEnabled = false
            }
            tvContent = TextView(this).apply {
                setPadding(dp(20), dp(8), dp(20), dp(40))
                textSize = fontSize; setTextColor(lightText)
                setLineSpacing(0f, 1.8f)
            }
            contentScroll.addView(tvContent)
            rootLayout.addView(contentScroll)

            // 批注区域（在正文下方）
            annotationsLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(4), dp(16), dp(4))
            }
            rootLayout.addView(annotationsLayout)

            // 底部栏
            bottomBar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(8), dp(12), dp(8))
            }
            btnPrev = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                gravity = Gravity.CENTER
                text = "‹ 上一章"; textSize = 13f; setTextColor(lightHint)
                setPadding(0, dp(8), 0, dp(8))
                setOnClickListener { prevChapter() }
            }
            btnFont = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                gravity = Gravity.CENTER
                text = "Aa"; textSize = 13f; setTextColor(lightHint)
                setPadding(0, dp(8), 0, dp(8))
                setOnClickListener { showFontSizeDialog() }
            }
            btnAnnotate = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                gravity = Gravity.CENTER
                text = "✎"; textSize = 15f; setTextColor(lightHint)
                setPadding(0, dp(8), 0, dp(8))
                setOnClickListener { showAnnotateDialog() }
            }
            btnList = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                gravity = Gravity.CENTER
                text = "目录"; textSize = 13f; setTextColor(lightHint)
                setPadding(0, dp(8), 0, dp(8))
                setOnClickListener { showChapterList() }
            }
            btnTheme = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                gravity = Gravity.CENTER
                text = "☀"; textSize = 15f; setTextColor(lightHint)
                setPadding(0, dp(8), 0, dp(8))
                setOnClickListener { toggleDarkMode() }
            }
            btnNext = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                gravity = Gravity.CENTER
                text = "下一章 ›"; textSize = 13f; setTextColor(lightHint)
                setPadding(0, dp(8), 0, dp(8))
                setOnClickListener { nextChapter() }
            }
            bottomBar.addView(btnPrev)
            bottomBar.addView(btnFont)
            bottomBar.addView(btnAnnotate)
            bottomBar.addView(btnList)
            bottomBar.addView(btnTheme)
            bottomBar.addView(btnNext)
            rootLayout.addView(bottomBar)

            setContentView(rootLayout)

            // 设置在场状态
            val userName = getSharedPreferences("haven_prefs", MODE_PRIVATE)
                .getString("user_name", "我") ?: "我"
            socialStorage.setPresence("user", userName, book!!.id, book!!.title, currentChapter)

            loadChapter()

        } catch (e: Exception) {
            val errorView = TextView(this).apply {
                text = "阅读器加载失败：${e.message}"
                textSize = 14f; setTextColor(0xFFFF0000.toInt())
                gravity = Gravity.CENTER
                setPadding(40, 100, 40, 100)
                setOnClickListener { finish() }
            }
            setContentView(errorView)
        }
    }

    private fun loadChapter() {
        val b = book ?: return
        val chapter = b.chapters.getOrNull(currentChapter) ?: return

        tvBookTitle.text = b.title
        tvChapterTitle.text = chapter.title
        tvChapterInfo.text = "${currentChapter + 1}/${b.chapters.size}"
        tvContent.text = chapter.content
        tvContent.textSize = fontSize
        contentScroll.scrollTo(0, 0)

        btnPrev.alpha = if (currentChapter > 0) 1f else 0.3f
        btnPrev.isEnabled = currentChapter > 0
        btnNext.alpha = if (currentChapter < b.chapters.size - 1) 1f else 0.3f
        btnNext.isEnabled = currentChapter < b.chapters.size - 1

        // 更新在场状态和个人进度
        socialStorage.updatePresenceChapter("user", currentChapter)
        socialStorage.saveProgress(b.id, "user", currentChapter)

        // 刷新在场栏
        refreshPresenceBar()
        // 刷新批注
        refreshAnnotations()
    }

    private fun prevChapter() {
        if (currentChapter > 0) { currentChapter--; loadChapter() }
    }

    private fun nextChapter() {
        val b = book ?: return
        if (currentChapter < b.chapters.size - 1) { currentChapter++; loadChapter() }
    }

    private fun showChapterList() {
        val b = book ?: return
        val titles = b.chapters.mapIndexed { i, ch ->
            val marker = if (i == currentChapter) "▸ " else "  "
            "$marker${ch.title}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("目录（${b.chapters.size}章）")
            .setItems(titles) { _, which -> currentChapter = which; loadChapter() }
            .show()
    }

    private fun showFontSizeDialog() {
        val sizes = arrayOf("小 (12)", "较小 (14)", "正常 (15)", "较大 (17)", "大 (19)", "特大 (22)")
        val values = floatArrayOf(12f, 14f, 15f, 17f, 19f, 22f)
        val current = values.indexOfFirst { it == fontSize }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("字号")
            .setSingleChoiceItems(sizes, current) { dialog, which ->
                fontSize = values[which]
                tvContent.textSize = fontSize
                dialog.dismiss()
            }
            .show()
    }

    private fun toggleDarkMode() {
        isDarkMode = !isDarkMode
        applyTheme()
    }

    private fun applyTheme() {
        val bg = if (isDarkMode) darkBg else lightBg
        val text = if (isDarkMode) darkText else lightText
        val title = if (isDarkMode) darkTitle else lightTitle
        val chapter = if (isDarkMode) darkChapter else lightChapter
        val hint = if (isDarkMode) darkHint else lightHint
        val info = if (isDarkMode) darkInfo else lightInfo

        rootLayout.setBackgroundColor(bg)
        tvContent.setTextColor(text)
        tvBookTitle.setTextColor(title)
        tvChapterTitle.setTextColor(chapter)
        tvChapterInfo.setTextColor(info)

        btnPrev.setTextColor(hint)
        btnNext.setTextColor(hint)
        btnList.setTextColor(hint)
        btnFont.setTextColor(hint)
        btnAnnotate.setTextColor(hint)
        btnTheme.text = if (isDarkMode) "🌙" else "☀"
        btnTheme.setTextColor(hint)

        // 顶部返回按钮
        (topBar.getChildAt(0) as? TextView)?.setTextColor(hint)
    }

    private fun saveAndFinish() {
        book?.let {
            bookStorage.updateProgress(it.id, currentChapter, 0)
            socialStorage.clearPresence("user")
        }
        finish()
    }

    // ===== 在场状态栏 =====
    private fun refreshPresenceBar() {
        presenceBar.removeAllViews()
        val b = book ?: return
        val readers = socialStorage.getBookReaders(b.id).filter { it.readerId != "user" }

        if (readers.isEmpty()) {
            presenceBar.visibility = View.GONE
            return
        }

        presenceBar.visibility = View.VISIBLE
        val hintColor = if (isDarkMode) darkHint else lightHint

        val icon = TextView(this).apply {
            text = "📖 "; textSize = 10f
        }
        presenceBar.addView(icon)

        for ((i, reader) in readers.withIndex()) {
            val color = socialStorage.getReaderColor(reader.readerId)
            val tv = TextView(this).apply {
                text = reader.readerName + if (reader.chapter == currentChapter) " (同页)" else " (第${reader.chapter + 1}章)"
                textSize = 10f
                setTextColor(color)
                setPadding(0, 0, dp(8), 0)
                setOnClickListener {
                    if (reader.chapter != currentChapter) {
                        android.app.AlertDialog.Builder(this@BookReaderActivity)
                            .setMessage("${reader.readerName} 在第 ${reader.chapter + 1} 章，要跳过去吗？")
                            .setPositiveButton("跳过去") { _, _ ->
                                currentChapter = reader.chapter
                                loadChapter()
                            }
                            .setNegativeButton("不了", null)
                            .show()
                    }
                }
            }
            presenceBar.addView(tv)
        }
    }

    // ===== 批注 =====
    private fun refreshAnnotations() {
        annotationsLayout.removeAllViews()
        val b = book ?: return
        val annotations = socialStorage.getChapterAnnotations(b.id, currentChapter)

        if (annotations.isEmpty()) return

        // 分隔线
        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
            ).apply { topMargin = dp(8); bottomMargin = dp(8) }
            setBackgroundColor(if (isDarkMode) 0xFF333344.toInt() else 0xFFE0D5C5.toInt())
        }
        annotationsLayout.addView(divider)

        val title = TextView(this).apply {
            text = "批注 (${annotations.size})"
            textSize = 11f
            setTextColor(if (isDarkMode) darkHint else lightHint)
            setPadding(0, 0, 0, dp(6))
        }
        annotationsLayout.addView(title)

        for (ann in annotations.sortedBy { it.timestamp }) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(6), dp(10), dp(6))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(4) }
            }

            // 彩色左边条 + 内容
            val contentRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            val colorBar = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(3), LinearLayout.LayoutParams.MATCH_PARENT).apply {
                    marginEnd = dp(8)
                }
                setBackgroundColor(ann.color)
            }
            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            textCol.addView(TextView(this).apply {
                text = ann.authorName
                textSize = 10f; setTextColor(ann.color)
            })
            textCol.addView(TextView(this).apply {
                text = ann.content
                textSize = 13f; setTextColor(if (isDarkMode) darkText else lightText)
                setPadding(0, dp(2), 0, 0)
            })

            contentRow.addView(colorBar)
            contentRow.addView(textCol)
            card.addView(contentRow)

            // 长按删除自己的批注
            if (ann.authorId == "user") {
                card.setOnLongClickListener {
                    android.app.AlertDialog.Builder(this)
                        .setMessage("删除这条批注？")
                        .setPositiveButton("删除") { _, _ ->
                            socialStorage.deleteAnnotation(book!!.id, ann.id)
                            refreshAnnotations()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                    true
                }
            }

            annotationsLayout.addView(card)
        }
    }

    // ===== 写批注 =====
    private fun showAnnotateDialog() {
        val b = book ?: return
        val userName = getSharedPreferences("haven_prefs", MODE_PRIVATE)
            .getString("user_name", "我") ?: "我"

        val input = android.widget.EditText(this).apply {
            hint = "写点什么..."
            setPadding(dp(16), dp(12), dp(16), dp(12))
            textSize = 14f
            minLines = 2
            gravity = Gravity.TOP
        }

        android.app.AlertDialog.Builder(this)
            .setTitle("在第 ${currentChapter + 1} 章留个批注")
            .setView(input)
            .setPositiveButton("留下") { _, _ ->
                val content = input.text.toString().trim()
                if (content.isNotEmpty()) {
                    socialStorage.addAnnotation(b.id, currentChapter, "user", userName, content)
                    refreshAnnotations()
                    android.widget.Toast.makeText(this, "批注已留下 ♡", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("算了", null)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        saveAndFinish()
    }
}