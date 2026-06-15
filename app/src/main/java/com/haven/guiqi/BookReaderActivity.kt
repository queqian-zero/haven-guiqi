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

    private lateinit var tvBookTitle: TextView
    private lateinit var tvChapterTitle: TextView
    private lateinit var tvChapterInfo: TextView
    private lateinit var tvContent: TextView
    private lateinit var contentScroll: ScrollView
    private lateinit var btnPrev: TextView
    private lateinit var btnNext: TextView

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

            // 纯代码构建 UI，不依赖 XML
            val root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(0xFFFDF5E6.toInt())
            }

            // 顶部栏
            val topBar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(8))
            }
            val btnBack = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
                gravity = Gravity.CENTER
                text = "←"; textSize = 18f; setTextColor(0xFF8B7355.toInt())
                setOnClickListener { saveAndFinish() }
            }
            tvBookTitle = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                gravity = Gravity.CENTER
                textSize = 15f; setTextColor(0xFF5D4037.toInt())
            }
            tvChapterInfo = TextView(this).apply {
                textSize = 11f; setTextColor(0xFFA08060.toInt())
            }
            topBar.addView(btnBack)
            topBar.addView(tvBookTitle)
            topBar.addView(tvChapterInfo)
            root.addView(topBar)

            // 章节标题
            tvChapterTitle = TextView(this).apply {
                gravity = Gravity.CENTER
                setPadding(dp(20), 0, dp(20), dp(12))
                textSize = 16f; setTextColor(0xFF6D4C41.toInt())
            }
            root.addView(tvChapterTitle)

            // 正文
            contentScroll = ScrollView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                )
                isVerticalScrollBarEnabled = false
            }
            tvContent = TextView(this).apply {
                setPadding(dp(20), dp(8), dp(20), dp(40))
                textSize = 15f; setTextColor(0xFF4E342E.toInt())
                setLineSpacing(0f, 1.8f)
            }
            contentScroll.addView(tvContent)
            root.addView(contentScroll)

            // 底部翻页栏
            val bottomBar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(dp(20), dp(10), dp(20), dp(10))
            }
            btnPrev = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                gravity = Gravity.CENTER
                text = "‹ 上一章"; textSize = 13f; setTextColor(0xFF8B7355.toInt())
                setPadding(0, dp(8), 0, dp(8))
                setOnClickListener { prevChapter() }
            }
            val btnList = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                gravity = Gravity.CENTER
                text = "目录"; textSize = 13f; setTextColor(0xFF8B7355.toInt())
                setPadding(0, dp(8), 0, dp(8))
                setOnClickListener { showChapterList() }
            }
            btnNext = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                gravity = Gravity.CENTER
                text = "下一章 ›"; textSize = 13f; setTextColor(0xFF8B7355.toInt())
                setPadding(0, dp(8), 0, dp(8))
                setOnClickListener { nextChapter() }
            }
            bottomBar.addView(btnPrev)
            bottomBar.addView(btnList)
            bottomBar.addView(btnNext)
            root.addView(bottomBar)

            setContentView(root)
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

    private fun saveAndFinish() {
        book?.let { bookStorage.updateProgress(it.id, currentChapter, 0) }
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        saveAndFinish()
    }
}