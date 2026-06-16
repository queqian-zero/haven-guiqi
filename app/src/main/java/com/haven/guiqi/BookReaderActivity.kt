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
    private var book: BookStorage.Book? = null
    private var currentChapter = 0

    private lateinit var rootLayout: LinearLayout
    private lateinit var tvBookTitle: TextView
    private lateinit var tvChapterTitle: TextView
    private lateinit var tvChapterInfo: TextView
    private lateinit var tvContent: TextView
    private lateinit var contentScroll: ScrollView
    private lateinit var btnPrev: TextView
    private lateinit var btnNext: TextView
    private lateinit var btnList: TextView
    private lateinit var btnFont: TextView
    private lateinit var btnTheme: TextView
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
            val bookId = intent.getStringExtra("book_id")
            if (bookId == null) { finish(); return }
            book = bookStorage.getBook(bookId)
            if (book == null) { finish(); return }

            currentChapter = book!!.lastChapter.coerceIn(0, book!!.chapters.size - 1)

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
            bottomBar.addView(btnList)
            bottomBar.addView(btnTheme)
            bottomBar.addView(btnNext)
            rootLayout.addView(bottomBar)

            setContentView(rootLayout)
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
        btnTheme.text = if (isDarkMode) "🌙" else "☀"
        btnTheme.setTextColor(hint)

        // 顶部返回按钮
        (topBar.getChildAt(0) as? TextView)?.setTextColor(hint)
    }

    private fun saveAndFinish() {
        book?.let { bookStorage.updateProgress(it.id, currentChapter, 0) }
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        saveAndFinish()
    }
}