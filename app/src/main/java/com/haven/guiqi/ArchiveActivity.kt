package com.haven.guiqi

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
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

    private lateinit var backButton: TextView
    private lateinit var pageTitle: TextView
    private lateinit var tabsContainer: View
    private lateinit var tabsDivider: View
    private lateinit var tabCollection: TextView
    private lateinit var tabArchive: TextView
    private lateinit var collectionPage: View
    private lateinit var collectionContainer: LinearLayout
    private lateinit var libraryPage: LinearLayout
    private lateinit var archivePage: View
    private lateinit var cabinetContainer: LinearLayout

    private enum class PageMode { COLLECTION, ARCHIVE, BOOKS }
    private var pageMode: PageMode = PageMode.COLLECTION
    private var isPageAnimating = false
    private val roomTransitionDuration = 220L

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

        backButton = findViewById(R.id.btnBack)
        pageTitle = findViewById(R.id.pageTitle)
        tabsContainer = findViewById(R.id.tabsContainer)
        tabsDivider = findViewById(R.id.tabsDivider)
        tabCollection = findViewById(R.id.tabCollection)
        tabArchive = findViewById(R.id.tabArchive)
        collectionPage = findViewById(R.id.collectionPage)
        collectionContainer = findViewById(R.id.collectionContainer)
        libraryPage = findViewById(R.id.libraryPage)
        archivePage = findViewById(R.id.archivePage)
        cabinetContainer = findViewById(R.id.cabinetContainer)

        backButton.setOnClickListener { handleBack() }

        tabCollection.setOnClickListener { switchTab(false) }
        tabArchive.setOnClickListener { switchTab(true) }

        buildCollectionHub()
        switchTab(false)
    }

    override fun onResume() {
        super.onResume()
        when (pageMode) {
            PageMode.ARCHIVE -> loadCabinets()
            PageMode.BOOKS -> loadBookShelf()
            PageMode.COLLECTION -> buildCollectionHub()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (pageMode == PageMode.BOOKS) {
            closeBookRoom()
        } else {
            super.onBackPressed()
        }
    }

    private fun handleBack() {
        if (pageMode == PageMode.BOOKS) {
            closeBookRoom()
            return
        }
        startActivity(Intent(this, DesktopActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        })
        finish()
    }

    private fun switchTab(isArchive: Boolean) {
        isPageAnimating = false
        collectionPage.animate().cancel()
        libraryPage.animate().cancel()
        collectionPage.alpha = 1f
        collectionPage.translationX = 0f
        libraryPage.alpha = 1f
        libraryPage.translationX = 0f

        backButton.text = "🏠"
        pageTitle.text = "馆藏"
        tabsContainer.visibility = View.VISIBLE
        tabsDivider.visibility = View.VISIBLE
        libraryPage.visibility = View.GONE

        if (isArchive) {
            pageMode = PageMode.ARCHIVE
            tabArchive.setTextColor(c.textPrimary)
            tabCollection.setTextColor(c.textHint)
            archivePage.visibility = View.VISIBLE
            collectionPage.visibility = View.GONE
            loadCabinets()
        } else {
            pageMode = PageMode.COLLECTION
            tabCollection.setTextColor(c.textPrimary)
            tabArchive.setTextColor(c.textHint)
            collectionPage.visibility = View.VISIBLE
            archivePage.visibility = View.GONE
        }
    }

    private fun openBookRoom() {
        // 书阁也作为独立房间打开，和漫廊、画匣、放映室走完全相同的
        // Activity 进入/返回过渡，不再使用同页动画。
        startActivity(Intent(this, BookRoomActivity::class.java))
    }

    private fun closeBookRoom() {
        if (pageMode != PageMode.BOOKS || isPageAnimating) return

        isPageAnimating = true
        pageMode = PageMode.COLLECTION
        backButton.text = "🏠"
        pageTitle.text = "馆藏"
        tabsContainer.visibility = View.VISIBLE
        tabsDivider.visibility = View.VISIBLE
        archivePage.visibility = View.GONE

        tabCollection.setTextColor(c.textPrimary)
        tabArchive.setTextColor(c.textHint)

        val distance = resources.displayMetrics.widthPixels * 0.10f
        collectionPage.apply {
            visibility = View.VISIBLE
            alpha = 0f
            translationX = -distance * 0.35f
        }
        libraryPage.apply {
            visibility = View.VISIBLE
            alpha = 1f
            translationX = 0f
        }

        libraryPage.animate()
            .alpha(0f)
            .translationX(distance)
            .setDuration(roomTransitionDuration - 40L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                libraryPage.visibility = View.GONE
                libraryPage.alpha = 1f
                libraryPage.translationX = 0f
            }
            .start()

        collectionPage.animate()
            .alpha(1f)
            .translationX(0f)
            .setDuration(roomTransitionDuration)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { isPageAnimating = false }
            .start()
    }

    private fun buildCollectionHub() {
        collectionContainer.removeAllViews()

        val bookCount = BookStorage(this).loadBooksMeta().size
        val rooms = listOf(
            RoomCard(RoomIcon.BOOK, "书阁", "电子书 · 小说", "${bookCount} 本", c.folderDiary) { openBookRoom() },
            RoomCard(RoomIcon.COMIC, "漫廊", "漫画 · 绘本", "空着", c.folderDream) { openCollectionRoom("comic") },
            RoomCard(
                RoomIcon.GALLERY,
                "画匣",
                "图片 · 头像 · 背景",
                GalleryStorage(this).count().let { if (it == 0) "空着" else "$it 张" },
                c.folderMemory
            ) { startActivity(Intent(this, GalleryActivity::class.java)) },
            RoomCard(RoomIcon.VIDEO, "放映室", "影片 · 视频", "空着", c.folderSummary) { openCollectionRoom("screening") }
        )

        rooms.forEachIndexed { index, room ->
            collectionContainer.addView(
                buildRoomCard(room),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(82)
                ).apply {
                    if (index < rooms.lastIndex) bottomMargin = dp(12)
                }
            )
        }
    }

    private enum class RoomIcon { BOOK, COMIC, GALLERY, VIDEO }

    private data class RoomCard(
        val icon: RoomIcon,
        val title: String,
        val subtitle: String,
        val status: String,
        val tint: Int,
        val onClick: () -> Unit
    )

    private fun buildRoomCard(card: RoomCard): LinearLayout {
        val cardBg = GradientDrawable().apply {
            setColor(withAlpha(card.tint, if (ThemeHelper.isDark(this@ArchiveActivity)) 34 else 18))
            cornerRadius = dp(16).toFloat()
            setStroke(dp(1), withAlpha(card.tint, if (ThemeHelper.isDark(this@ArchiveActivity)) 76 else 52))
        }

        val iconBg = GradientDrawable().apply {
            setColor(withAlpha(card.tint, if (ThemeHelper.isDark(this@ArchiveActivity)) 52 else 26))
            cornerRadius = dp(13).toFloat()
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(11), dp(16), dp(11))
            background = cardBg
            isClickable = true
            isFocusable = true
            setOnClickListener { card.onClick() }

            addView(RoomIconView(this@ArchiveActivity, card.icon, card.tint).apply {
                background = iconBg
            }, LinearLayout.LayoutParams(dp(48), dp(48)))

            addView(LinearLayout(this@ArchiveActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL

                addView(TextView(this@ArchiveActivity).apply {
                    text = card.title
                    textSize = 16f
                    setTextColor(c.textPrimary)
                    maxLines = 1
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ))

                addView(TextView(this@ArchiveActivity).apply {
                    text = card.subtitle
                    textSize = 11f
                    setTextColor(c.textSecondary)
                    maxLines = 2
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(14)
            })

            addView(LinearLayout(this@ArchiveActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL

                addView(TextView(this@ArchiveActivity).apply {
                    text = card.status
                    textSize = 10.5f
                    setTextColor(withAlpha(card.tint, if (ThemeHelper.isDark(this@ArchiveActivity)) 220 else 190))
                    gravity = Gravity.CENTER
                    setPadding(dp(10), dp(5), dp(10), dp(5))
                    background = GradientDrawable().apply {
                        setColor(withAlpha(card.tint, if (ThemeHelper.isDark(this@ArchiveActivity)) 42 else 22))
                        cornerRadius = dp(12).toFloat()
                    }
                    maxLines = 1
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ))

                addView(RoomChevronView(this@ArchiveActivity, card.tint), LinearLayout.LayoutParams(
                    dp(20), dp(32)
                ).apply { marginStart = dp(8) })
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            ))
        }
    }


    /** 卡片右侧的纯代码箭头。 */
    private class RoomChevronView(
        context: Context,
        tint: Int
    ) : View(context) {

        private val density = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = tint
            style = Paint.Style.STROKE
            strokeWidth = 1.7f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            alpha = 150
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width * 0.42f
            val cy = height * 0.5f
            val dx = 4.2f * density
            val dy = 6.2f * density
            val path = Path().apply {
                moveTo(cx - dx, cy - dy)
                lineTo(cx + dx, cy)
                lineTo(cx - dx, cy + dy)
            }
            canvas.drawPath(path, paint)
        }
    }

    /** 纯 Canvas 线条图标，不依赖图片素材或图标字体。 */
    private class RoomIconView(
        context: Context,
        private val icon: RoomIcon,
        private val tint: Int
    ) : View(context) {

        private val density = resources.displayMetrics.density
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = tint
            style = Paint.Style.STROKE
            strokeWidth = 1.8f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = tint
            style = Paint.Style.FILL
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val left = w * 0.24f
            val top = h * 0.27f
            val right = w * 0.76f
            val bottom = h * 0.73f

            when (icon) {
                RoomIcon.BOOK -> drawBook(canvas, left, top, right, bottom)
                RoomIcon.COMIC -> drawComic(canvas, left, top, right, bottom)
                RoomIcon.GALLERY -> drawGallery(canvas, left, top, right, bottom)
                RoomIcon.VIDEO -> drawVideo(canvas, left, top, right, bottom)
            }
        }

        private fun drawBook(canvas: Canvas, l: Float, t: Float, r: Float, b: Float) {
            val mid = (l + r) / 2f
            val path = Path().apply {
                moveTo(mid, t + dpF(2f))
                cubicTo(mid - dpF(4f), t, l + dpF(2f), t + dpF(1f), l, t + dpF(4f))
                lineTo(l, b - dpF(2f))
                cubicTo(l + dpF(7f), b - dpF(5f), mid - dpF(3f), b - dpF(3f), mid, b)
                cubicTo(mid + dpF(3f), b - dpF(3f), r - dpF(7f), b - dpF(5f), r, b - dpF(2f))
                lineTo(r, t + dpF(4f))
                cubicTo(r - dpF(2f), t + dpF(1f), mid + dpF(4f), t, mid, t + dpF(2f))
                close()
            }
            canvas.drawPath(path, stroke)
            canvas.drawLine(mid, t + dpF(2f), mid, b, stroke)
        }

        private fun drawComic(canvas: Canvas, l: Float, t: Float, r: Float, b: Float) {
            val rect = RectF(l, t, r, b)
            canvas.drawRoundRect(rect, dpF(3f), dpF(3f), stroke)
            val splitX = l + (r - l) * 0.58f
            val splitY = t + (b - t) * 0.52f
            canvas.drawLine(splitX, t, splitX, b, stroke)
            canvas.drawLine(l, splitY, splitX, splitY, stroke)
            val bubble = RectF(splitX + dpF(3f), t + dpF(4f), r - dpF(3f), splitY - dpF(3f))
            canvas.drawRoundRect(bubble, dpF(3f), dpF(3f), stroke)
            val tail = Path().apply {
                moveTo(bubble.left + dpF(4f), bubble.bottom)
                lineTo(bubble.left + dpF(2f), bubble.bottom + dpF(3f))
                lineTo(bubble.left + dpF(7f), bubble.bottom)
            }
            canvas.drawPath(tail, stroke)
        }

        private fun drawGallery(canvas: Canvas, l: Float, t: Float, r: Float, b: Float) {
            val rect = RectF(l, t, r, b)
            canvas.drawRoundRect(rect, dpF(3f), dpF(3f), stroke)
            canvas.drawCircle(r - dpF(6f), t + dpF(6f), dpF(2f), fill)
            val mountain = Path().apply {
                moveTo(l + dpF(3f), b - dpF(4f))
                lineTo(l + dpF(9f), t + dpF(10f))
                lineTo(l + dpF(14f), b - dpF(9f))
                lineTo(l + dpF(18f), t + dpF(12f))
                lineTo(r - dpF(3f), b - dpF(4f))
            }
            canvas.drawPath(mountain, stroke)
        }

        private fun drawVideo(canvas: Canvas, l: Float, t: Float, r: Float, b: Float) {
            val rect = RectF(l, t, r, b)
            canvas.drawRoundRect(rect, dpF(4f), dpF(4f), stroke)
            val cx = (l + r) / 2f + dpF(1f)
            val cy = (t + b) / 2f
            val triangle = Path().apply {
                moveTo(cx - dpF(4f), cy - dpF(6f))
                lineTo(cx + dpF(6f), cy)
                lineTo(cx - dpF(4f), cy + dpF(6f))
                close()
            }
            canvas.drawPath(triangle, fill)
        }

        private fun dpF(value: Float): Float = value * density
    }

    private fun withAlpha(color: Int, alpha: Int): Int {
        return (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
    }

    private fun openCollectionRoom(room: String) {
        startActivity(Intent(this, CollectionRoomActivity::class.java).apply {
            putExtra(CollectionRoomActivity.EXTRA_ROOM, room)
        })
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
            textSize = 11f
            setTextColor(c.accent)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2) }
        }
        labelColumn.addView(nameView)
        labelColumn.addView(statView)

        // 头像
        val avatar = FriendAvatarHelper.create(this, friend, 36)

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