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
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.concurrent.Executors

/**
 * 书阁独立页面。
 *
 * 独立成 Activity 后，书阁与漫廊、画匣、放映室使用同一套系统页面过渡，
 * 不再出现三个房间柔和进入、书阁却在同页突然切换的情况。
 */
class BookRoomActivity : AppCompatActivity() {

    private lateinit var libraryPage: LinearLayout
    private var skipInitialResumeRefresh = true
    private var shelfLoadVersion = 0
    private val shelfExecutor = Executors.newSingleThreadExecutor()

    private val c get() = ThemeHelper.getColors(this)
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun readFileContent(uri: Uri): String {
        val inputStream = contentResolver.openInputStream(uri) ?: return ""
        val rawBytes = inputStream.readBytes()
        inputStream.close()
        val content = try {
            val text = rawBytes.toString(Charsets.UTF_8)
            if (text.count { it == '\uFFFD' } > text.length / 10) {
                rawBytes.toString(charset("GBK"))
            } else {
                text
            }
        } catch (_: Exception) {
            try {
                rawBytes.toString(charset("GBK"))
            } catch (_: Exception) {
                rawBytes.toString(Charsets.UTF_8)
            }
        }
        return content.trimStart('\uFEFF')
    }

    private fun getFileName(uri: Uri): String {
        return uri.lastPathSegment?.substringAfterLast("/")?.removeSuffix(".txt") ?: "未命名"
    }

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@registerForActivityResult
        try {
            if (uris.size == 1) {
                val content = readFileContent(uris[0])
                val fileName = getFileName(uris[0])
                val book = BookStorage(this).importTxt(fileName, content)
                Toast.makeText(
                    this,
                    "导入成功：${book.title}（${book.chapters.size}章）",
                    Toast.LENGTH_SHORT
                ).show()
                loadBookShelf()
            } else {
                android.app.AlertDialog.Builder(this)
                    .setTitle("选了 ${uris.size} 个文件")
                    .setItems(
                        arrayOf(
                            "合并成一本书（每个文件变一章）",
                            "分开导入（每个文件一本书）"
                        )
                    ) { _, which ->
                        when (which) {
                            0 -> mergeImport(uris)
                            1 -> separateImport(uris)
                        }
                    }
                    .show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "导入失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
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

        setContentView(R.layout.activity_book_room)

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

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        libraryPage = findViewById(R.id.libraryPage)
        loadBookShelf()
    }

    override fun onResume() {
        super.onResume()
        if (skipInitialResumeRefresh) {
            skipInitialResumeRefresh = false
            return
        }
        if (::libraryPage.isInitialized) loadBookShelf()
    }

    override fun onDestroy() {
        shelfLoadVersion++
        shelfExecutor.shutdownNow()
        super.onDestroy()
    }

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
                    Toast.makeText(
                        this,
                        "导入成功：${book.title}（${book.chapters.size}章）",
                        Toast.LENGTH_SHORT
                    ).show()
                    loadBookShelf()
                } catch (e: Exception) {
                    Toast.makeText(this, "导入失败：${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun separateImport(uris: List<Uri>) {
        var count = 0
        for (uri in uris) {
            try {
                val content = readFileContent(uri)
                val fileName = getFileName(uri)
                BookStorage(this).importTxt(fileName, content)
                count++
            } catch (_: Exception) {
                // 单个文件失败不阻断其余文件导入。
            }
        }
        Toast.makeText(this, "导入了 $count 本书", Toast.LENGTH_SHORT).show()
        loadBookShelf()
    }

    private fun loadBookShelf() {
        val version = ++shelfLoadVersion
        libraryPage.removeAllViews()
        libraryPage.gravity = Gravity.CENTER

        val loadingText = TextView(this).apply {
            text = "📚 书架整理中..."
            textSize = 14f
            setTextColor(c.textHint)
            gravity = Gravity.CENTER
        }
        libraryPage.addView(loadingText)

        shelfExecutor.execute {
            val books = BookStorage(applicationContext).loadBooksMeta()
            runOnUiThread {
                if (!isFinishing && !isDestroyed && version == shelfLoadVersion) {
                    buildShelfUI(books)
                }
            }
        }
    }

    private fun buildShelfUI(books: List<BookStorage.Book>) {
        libraryPage.removeAllViews()
        libraryPage.gravity = Gravity.NO_GRAVITY

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

        val screenWidth = resources.displayMetrics.widthPixels
        val shelfPadding = dp(32)
        val maxShelfWidth = screenWidth - shelfPadding

        val shelves = mutableListOf<MutableList<BookStorage.Book>>()
        var currentShelf = mutableListOf<BookStorage.Book>()
        var currentWidth = 0

        for (book in books) {
            val chapterCount = book.chapters.size
            val thickness = (18 + Math.sqrt(chapterCount.toDouble()) * 1.2)
                .toInt()
                .coerceIn(18, 56)
            val bookWidth = dp(thickness) + dp(2)

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
            libraryPage.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(4)
                ).apply {
                    marginStart = dp(12)
                    marginEnd = dp(12)
                }
                background = GradientDrawable().apply {
                    setColor(c.folderDiary)
                    cornerRadius = dp(2).toFloat()
                }
            })
        }
    }

    private fun buildBookSpine(book: BookStorage.Book): View {
        val chapterCount = book.chapters.size
        val thickness = (18 + Math.sqrt(chapterCount.toDouble()) * 1.2)
            .toInt()
            .coerceIn(18, 56)
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
                android.app.AlertDialog.Builder(this@BookRoomActivity)
                    .setTitle("删除「${book.title}」？")
                    .setPositiveButton("删除") { _, _ ->
                        BookStorage(this@BookRoomActivity).deleteBook(book.id)
                        loadBookShelf()
                    }
                    .setNegativeButton("取消", null)
                    .show()
                true
            }
        }

        val title = book.title
        val displayTitle = if (title.length > 6) title.substring(0, 6) + "…" else title
        spine.addView(TextView(this).apply {
            text = displayTitle.toList().joinToString("\n")
            textSize = 9f
            setTextColor(0xDDFFFFFF.toInt())
            gravity = Gravity.CENTER
            setLineSpacing(0f, 0.85f)
        })

        return spine
    }

    private fun showBookDetail(book: BookStorage.Book) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(16))
        }

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
        cover.addView(TextView(this).apply {
            text = book.title
            textSize = 14f
            setTextColor(0xDDFFFFFF.toInt())
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.3f)
        })
        layout.addView(cover)

        layout.addView(TextView(this).apply {
            text = book.title
            textSize = 16f
            setTextColor(c.textPrimary)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(4))
        })

        if (book.author.isNotEmpty()) {
            layout.addView(TextView(this).apply {
                text = book.author
                textSize = 12f
                setTextColor(c.textSecondary)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(8))
            })
        }

        val dateStr = java.text.SimpleDateFormat("yyyy/MM/dd", java.util.Locale.getDefault())
            .format(java.util.Date(book.createdAt))
        val progress = if (book.chapters.isNotEmpty()) {
            "读到第 ${book.lastChapter + 1}/${book.chapters.size} 章"
        } else {
            "还没开始读"
        }

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
                colorRow.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).apply {
                        marginEnd = dp(6)
                        bottomMargin = dp(4)
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
                })
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
                val fullBook = BookStorage(this).getBook(book.id) ?: book
                val updated = fullBook.copy(
                    title = newTitle,
                    author = newAuthor,
                    spineColor = selectedColor
                )
                BookStorage(this).saveBook(updated)
                loadBookShelf()
                Toast.makeText(this, "已更新", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
