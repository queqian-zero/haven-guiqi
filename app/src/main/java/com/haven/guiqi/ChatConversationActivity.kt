package com.haven.guiqi

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import org.json.JSONArray
import org.json.JSONObject
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class ChatConversationActivity : AppCompatActivity() {

    /** 当前主题色 */
    private val c get() = ThemeHelper.getColors(this)

    private lateinit var tvFriendName: TextView
    private lateinit var tvStatus: TextView
    private lateinit var messagesContainer: LinearLayout
    private lateinit var scrollMessages: ScrollView
    private lateinit var inputMessage: EditText
    private lateinit var btnSend: TextView
    private lateinit var btnBack: TextView
    private lateinit var btnMenu: TextView
    private lateinit var btnPlus: TextView
    private lateinit var connectionBar: TextView
    private lateinit var imagePreviewContainer: LinearLayout
    private lateinit var quotePreviewContainer: LinearLayout
    private lateinit var inputBar: LinearLayout
    private lateinit var expandedInputPanel: LinearLayout
    private lateinit var expandedInput: EditText
        // 表情包相关
    private lateinit var stickerPanel: LinearLayout
    private lateinit var stickerGrid: LinearLayout
    private lateinit var stickerGroupTabs: LinearLayout
    private lateinit var stickerActionBar: LinearLayout
    private var currentStickerGroup: String? = null  // null = 全部
    private var isStickerManageMode = false
    private val selectedStickerIds = mutableSetOf<String>()
    private lateinit var stickerStorage: StickerStorage
    private lateinit var memoryStorage: MemoryStorage
    private lateinit var diaryStorage: DiaryStorage
    private lateinit var impressionStorage: ImpressionStorage
    private lateinit var dreamStorage: DreamStorage
    private lateinit var summaryStorage: ChatSummaryStorage
    // AI 状态指示器（显示在名字旁边）
    private var currentAiStatus = ""
    // 搜索相关
    private lateinit var searchPanel: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var searchResults: LinearLayout
    private lateinit var searchResultsScroll: ScrollView


    private val handler = Handler(Looper.getMainLooper())
    private val PICK_IMAGE = 3001
    private val PICK_STICKER = 3002

    private var friendId = ""
    private var friendName = "好友"
    private var friendIcon = "★"

    private var apiUrl = ""
    private var apiKey = ""
    private var apiModel = ""
    private var apiType = "openai"

    private val chatHistory = mutableListOf<ChatMessage>()
    private var maxContextMessages = 30
    private lateinit var chatStorage: ChatStorage
    private lateinit var bubbleRenderer: BubbleRenderer

    // 待发送的图片（支持多张）
    private val pendingImagePaths = mutableListOf<String>()

    // 待引用的消息
    private var pendingQuoteAuthor: String? = null
    private var pendingQuoteContent: String? = null

    // 记录最后一条消息的日期（用于判断是否需要加分隔线）
    private var lastMessageDate = ""
    private var lastMessageTimestamp = 0L

    private val imageDir: File
        get() {
            val dir = File(filesDir, "chat_images")
            if (!dir.exists()) dir.mkdirs()
            return dir
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

        setContentView(R.layout.activity_chat_conversation)

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

        tvFriendName = findViewById(R.id.tvFriendName)
        tvStatus = findViewById(R.id.tvStatus)
        messagesContainer = findViewById(R.id.messagesContainer)
        scrollMessages = findViewById(R.id.scrollMessages)
        bubbleRenderer = BubbleRenderer(this, messagesContainer, scrollMessages)
        bubbleRenderer.friendName = friendName
        bubbleRenderer.friendIcon = friendIcon
        inputMessage = findViewById(R.id.inputMessage)
        btnSend = findViewById(R.id.btnSend)
        btnBack = findViewById(R.id.btnBack)
        btnMenu = findViewById(R.id.btnMenu)
        btnPlus = findViewById(R.id.btnPlus)
        connectionBar = findViewById(R.id.connectionBar)
        imagePreviewContainer = findViewById(R.id.imagePreviewContainer)
        quotePreviewContainer = findViewById(R.id.quotePreviewContainer)
        inputBar = findViewById(R.id.inputBar)
        expandedInputPanel = findViewById(R.id.expandedInputPanel)
        expandedInput = findViewById(R.id.expandedInput)
        stickerPanel = findViewById(R.id.stickerPanel)
        stickerGrid = findViewById(R.id.stickerGrid)
        stickerGroupTabs = findViewById(R.id.stickerGroupTabs)
        stickerActionBar = findViewById(R.id.stickerActionBar)

        // 管理按钮
        findViewById<TextView>(R.id.btnManageSticker).setOnClickListener {
            showStickerManageDialog()
        }


        friendId = intent.getStringExtra("friend_id") ?: ""
        friendName = intent.getStringExtra("friend_name") ?: "好友"
        friendIcon = intent.getStringExtra("friend_icon") ?: "★"
        tvFriendName.text = friendName
        chatStorage = ChatStorage(this)
        // 恢复上次的 AI 状态
        val statusPrefs = getSharedPreferences("haven_status", MODE_PRIVATE)
        currentAiStatus = statusPrefs.getString("status_$friendId", "") ?: ""
        stickerStorage = StickerStorage(this)
        memoryStorage = MemoryStorage(this)
        diaryStorage = DiaryStorage(this)
        impressionStorage = ImpressionStorage(this)
        dreamStorage = DreamStorage(this)
        summaryStorage = ChatSummaryStorage(this)
                // 搜索面板
        searchPanel = findViewById(R.id.searchPanel)
        searchInput = findViewById(R.id.searchInput)
        searchResults = findViewById(R.id.searchResults)
        searchResultsScroll = findViewById(R.id.searchResultsScroll)
 
        // 搜索按钮
        findViewById<TextView>(R.id.btnSearch).setOnClickListener {
            if (searchPanel.visibility == View.VISIBLE) {
                closeSearch()
            } else {
                searchPanel.visibility = View.VISIBLE
                searchInput.requestFocus()
                // 弹出键盘
                val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.showSoftInput(searchInput, 0)
            }
        }
 
        // 取消按钮
        findViewById<TextView>(R.id.btnCloseSearch).setOnClickListener { closeSearch() }
 
        // 输入时实时搜索（每次文字变化都搜）
        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val keyword = s?.toString()?.trim() ?: ""
                if (keyword.isEmpty()) {
                    searchResultsScroll.visibility = View.GONE
                    searchResults.removeAllViews()
                } else {
                    performSearch(keyword)
                }
            }
        })
 
        // 键盘搜索键
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                val keyword = searchInput.text.toString().trim()
                if (keyword.isNotEmpty()) performSearch(keyword)
                true
            } else false
        }

        loadApiConfig()

        btnBack.setOnClickListener { finish() }
        btnMenu.setOnClickListener {
            val intent = Intent(this, ChatSettingsActivity::class.java)
            intent.putExtra("friend_id", friendId)
            intent.putExtra("friend_name", friendName)
            startActivity(intent)
        }
        btnSend.setOnClickListener { sendMessage() }

        // 普通模式：回车发送
        inputMessage.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_ENTER && event.action == android.view.KeyEvent.ACTION_DOWN) {
                sendMessage()
                true
            } else {
                false
            }
        }

        btnPlus.setOnClickListener { showPlusMenu() }

        // 分条模式按钮
        findViewById<TextView>(R.id.btnBatch).setOnClickListener { toggleBatchMode() }

        // 发送全部
        findViewById<TextView>(R.id.btnSendAll).setOnClickListener { sendAllPending() }

        // 导入按钮：从相册选图导入为表情包（支持多选）
        findViewById<TextView>(R.id.btnAddSticker).setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            startActivityForResult(intent, PICK_STICKER)
        }

        // 展开模式
        findViewById<TextView>(R.id.btnExpand).setOnClickListener { toggleExpandedInput(true) }
        findViewById<TextView>(R.id.btnCollapse).setOnClickListener { toggleExpandedInput(false) }
                findViewById<TextView>(R.id.btnExpandSend).setOnClickListener {
            val text = expandedInput.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener
            expandedInput.text.clear()
            toggleExpandedInput(false)
 
            // 用 --- 分条（三个或更多短横线）
            // 比如用户写了：
            //   你好
            //   ---
            //   今天天气怎么样
            //   ---
            //   帮我讲个笑话
            // 就会变成三条独立的消息
            val parts = text.split(Regex("-{3,}"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
 
            if (parts.size <= 1) {
                // 没有分隔符，当普通消息发
                inputMessage.setText(text)
                sendMessage()
            } else {
                // 分条发送
                sendMultiMessages(parts)
            }
        }

        initChat()
    }

    override fun onResume() {
        super.onResume()
        loadApiConfig()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
                if (requestCode == PICK_IMAGE && resultCode == RESULT_OK && data != null) {
            // 多选：clipData 里有多个 URI
            val clipData = data.clipData
            if (clipData != null) {
                for (i in 0 until clipData.itemCount) {
                    handlePickedImage(clipData.getItemAt(i).uri)
                }
            } else if (data.data != null) {
                // 单选
                handlePickedImage(data.data!!)
            }
        } else if (requestCode == PICK_STICKER && resultCode == RESULT_OK && data != null) {
            // 导入表情包（支持多选）
            val uris = mutableListOf<Uri>()
            val clipData = data.clipData
            if (clipData != null) {
                for (i in 0 until clipData.itemCount) {
                    uris.add(clipData.getItemAt(i).uri)
                }
            } else if (data.data != null) {
                uris.add(data.data!!)
            }

            var successCount = 0
            for (uri in uris) {
                if (stickerStorage.importFromUri(uri) != null) successCount++
            }

            if (successCount > 0) {
                val msg = if (successCount == 1) "表情包已收藏！" else "已收藏 $successCount 张表情包！"
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                refreshStickerGrid()
            } else {
                Toast.makeText(this, "导入失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ===== 选图后：压缩保存，加入待发列表 =====
    private fun handlePickedImage(uri: Uri) {
        val path = ImageHelper.compressAndSave(this, uri, imageDir, pendingImagePaths.size)
        if (path != null) {
            pendingImagePaths.add(path)
            showImagePreview()
        } else {
            Toast.makeText(this, "图片处理失败", Toast.LENGTH_SHORT).show()
        }
    }

    // ===== 输入栏上方显示图片预览（支持多图） =====
    private fun showImagePreview() {
        removePendingPreview()
        if (pendingImagePaths.isEmpty()) return
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }

        val previewLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(c.inputBg)
            setPadding(dp(8), dp(8), dp(8), dp(4))
        }

        // 缩略图行：横向滚动
        val scrollView = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        val thumbRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        for ((index, path) in pendingImagePaths.withIndex()) {
            val thumbContainer = android.widget.FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply {
                    marginEnd = dp(6)
                }
            }

            val imageView = ImageView(this).apply {
                layoutParams = android.widget.FrameLayout.LayoutParams(dp(56), dp(56))
                scaleType = ImageView.ScaleType.CENTER_CROP
                val bitmap = BitmapFactory.decodeFile(path)
                setImageBitmap(bitmap)
                val gd = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                }
                clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, dp(8).toFloat())
                    }
                }
            }
            thumbContainer.addView(imageView)

            // 每个缩略图右上角的 × 按钮
            val removeBtn = TextView(this).apply {
                layoutParams = android.widget.FrameLayout.LayoutParams(dp(18), dp(18)).apply {
                    gravity = Gravity.TOP or Gravity.END
                }
                text = "✕"
                textSize = 10f
                gravity = Gravity.CENTER
                setTextColor(0xFFFFFFFF.toInt())
                val bg = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0x99000000.toInt())
                    cornerRadius = dp(9).toFloat()
                }
                background = bg
                val idx = index
                setOnClickListener {
                    pendingImagePaths.removeAt(idx)
                    showImagePreview()
                }
            }
            thumbContainer.addView(removeBtn)
            thumbRow.addView(thumbContainer)
        }

        // ＋ 追加更多图片按钮
        val addMore = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
            text = "＋"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(c.textSecondary)
            val bg = android.graphics.drawable.GradientDrawable().apply {
                setColor(c.accentBg)
                cornerRadius = dp(8).toFloat()
            }
            background = bg
            setOnClickListener {
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "image/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                startActivityForResult(intent, PICK_IMAGE)
            }
        }
        thumbRow.addView(addMore)

        scrollView.addView(thumbRow)
        previewLayout.addView(scrollView)

        // 底部提示 + 全部清除
        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), 0)
        }
        val label = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = "${pendingImagePaths.size} 张图片已选择"
            textSize = 11f
            setTextColor(c.textSecondary)
        }
        val clearBtn = TextView(this).apply {
            text = "全部清除"
            textSize = 11f
            setTextColor(c.errorText)
            setOnClickListener {
                pendingImagePaths.clear()
                removePendingPreview()
            }
        }
        bottomRow.addView(label)
        bottomRow.addView(clearBtn)
        previewLayout.addView(bottomRow)

        imagePreviewContainer.removeAllViews()
        imagePreviewContainer.addView(previewLayout)
        imagePreviewContainer.visibility = View.VISIBLE
    }

    private fun removePendingPreview() {
        imagePreviewContainer.removeAllViews()
        imagePreviewContainer.visibility = View.GONE
    }

    // ===== 图片转 base64 =====
    private fun imageToBase64(file: File): String = ImageHelper.toBase64(file)

    // ===== 图片气泡（右侧） =====
    private fun addImageBubble(imagePath: String, timeStr: String, caption: String = "") {
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }

        val wrapper = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            gravity = Gravity.END
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val imageView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(180), LinearLayout.LayoutParams.WRAP_CONTENT)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundResource(R.drawable.chat_bubble_user)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            val file = File(imagePath)
            if (file.exists()) {
                val bitmap = BitmapFactory.decodeFile(imagePath)
                setImageBitmap(bitmap)
            }
        }
        column.addView(imageView)

        // 如果有附带文字
        if (caption.isNotEmpty()) {
            val captionView = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) }
                maxWidth = dp(180)
                this.text = MarkdownRenderer.render(caption)
                setTextColor(c.textOnAccent)
                textSize = 13f
                setLineSpacing(0f, 1.3f)
                setPadding(dp(10), dp(6), dp(10), dp(6))
                setBackgroundResource(R.drawable.chat_bubble_user)
            }
            column.addView(captionView)
        }

        val time = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2) }
            gravity = Gravity.END
            this.text = timeStr
            textSize = 9f
            setTextColor(c.timeText)
            setPadding(0, 0, dp(4), 0)
        }
        column.addView(time)
        wrapper.addView(column)
        messagesContainer.addView(wrapper)
        scrollToBottom()
    }

    // ===== 多图气泡（右侧，网格排列，点击放大） =====
    private fun addMultiImageBubble(imagePaths: List<String>, timeStr: String, caption: String = "") {
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }
        val thumbSize = dp(90)
        val gap = dp(4)
        val columns = if (imagePaths.size == 2) 2 else 3

        val wrapper = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            gravity = Gravity.END
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // 用 GridLayout 排列缩略图
        val grid = android.widget.GridLayout(this).apply {
            columnCount = columns
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        for ((index, path) in imagePaths.withIndex()) {
            val imageView = ImageView(this).apply {
                layoutParams = android.widget.GridLayout.LayoutParams().apply {
                    width = thumbSize
                    height = thumbSize
                    setMargins(if (index % columns != 0) gap else 0, if (index >= columns) gap else 0, 0, 0)
                }
                scaleType = ImageView.ScaleType.CENTER_CROP
                val file = File(path)
                if (file.exists()) {
                    val bitmap = BitmapFactory.decodeFile(path)
                    setImageBitmap(bitmap)
                }
                // 圆角
                clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, dp(6).toFloat())
                    }
                }
                // 点击放大
                setOnClickListener { showFullImage(path) }
            }
            grid.addView(imageView)
        }
        column.addView(grid)

        // 如果有附带文字
        if (caption.isNotEmpty()) {
            val captionView = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) }
                maxWidth = columns * thumbSize + (columns - 1) * gap
                this.text = MarkdownRenderer.render(caption)
                setTextColor(c.textOnAccent)
                textSize = 13f
                setLineSpacing(0f, 1.3f)
                setPadding(dp(10), dp(6), dp(10), dp(6))
                setBackgroundResource(R.drawable.chat_bubble_user)
            }
            column.addView(captionView)
        }

        val time = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2) }
            gravity = Gravity.END
            this.text = timeStr
            textSize = 9f
            setTextColor(c.timeText)
            setPadding(0, 0, dp(4), 0)
        }
        column.addView(time)
        wrapper.addView(column)
        messagesContainer.addView(wrapper)
        scrollToBottom()
    }

    // ===== 点击图片放大查看 =====
    private fun showFullImage(imagePath: String) = ImageHelper.showFullImage(this, imagePath)
    private fun checkDateSeparator(timestamp: Long) {
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timestamp))

        // 换天分隔——醒目横线
        if (dateStr != lastMessageDate) {
            addDaySeparator(timestamp)
            lastMessageDate = dateStr
        }
        // 同一天内，距上条消息超过 1 小时——朴素间隔标记
        else if (lastMessageTimestamp > 0) {
            val gapMs = timestamp - lastMessageTimestamp
            val gapMinutes = gapMs / 60000
            if (gapMinutes >= 60) {
                val gapLabel = formatGapLabel(gapMs)
                val timeLabel = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
                addGapMarker("距上条消息 $gapLabel · $timeLabel")
            }
        }

        lastMessageTimestamp = timestamp
    }

    /** 格式化时间间隔：3小时、1天3小时、3天 */
    private fun formatGapLabel(gapMs: Long): String {
        val minutes = gapMs / 60000
        val hours = minutes / 60
        val days = hours / 24
        val remainHours = hours % 24
        return when {
            days > 0 && remainHours > 0 -> "${days}天${remainHours}小时"
            days > 0 -> "${days}天"
            else -> "${hours}小时"
        }
    }

    /** 换天分隔线——醒目 */
    private fun addDaySeparator(timestamp: Long) {
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }
        val dateLabel = formatDateLabel(timestamp)

        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12); bottomMargin = dp(12) }
        }

        val lineColor = c.borderMedium
        val leftLine = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(1), 1f).apply { marginEnd = dp(10) }
            setBackgroundColor(lineColor)
        }
        val label = TextView(this).apply {
            text = dateLabel
            textSize = 11f
            setTextColor(c.textSecondary)
        }
        val rightLine = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(1), 1f).apply { marginStart = dp(10) }
            setBackgroundColor(lineColor)
        }

        wrapper.addView(leftLine)
        wrapper.addView(label)
        wrapper.addView(rightLine)
        messagesContainer.addView(wrapper)
    }

    /** 时间间隔标记——朴素 */
    private fun addGapMarker(text: String) {
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }
        val label = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6); bottomMargin = dp(8) }
            gravity = Gravity.CENTER
            this.text = text
            textSize = 9f
            setTextColor(c.textHint)
        }
        messagesContainer.addView(label)
    }

    // ===== 更新连接状态 =====
    private fun setStatus(state: String, errorDetail: String? = null) {
        when (state) {
            "online" -> {
                tvStatus.text = "在线"
                tvStatus.setTextColor(c.accent)
                connectionBar.visibility = View.GONE
            }
            "sending" -> {
                tvStatus.text = "发送中..."
                tvStatus.setTextColor(c.warning)
                connectionBar.visibility = View.GONE
            }
            "error" -> {
                tvStatus.text = "连接失败"
                tvStatus.setTextColor(c.errorText)
                connectionBar.visibility = View.VISIBLE
                connectionBar.text = "⚠ ${errorDetail ?: "消息发送失败，请检查网络和 API 配置"}"
                // 点击断网条消除
                connectionBar.setOnClickListener {
                    connectionBar.visibility = View.GONE
                    setStatus("online")
                }
            }
            "unconfigured" -> {
                tvStatus.text = "未配置"
                tvStatus.setTextColor(c.errorText)
            }
        }
    }

    // ===== 展开/收起输入框 =====
    private fun toggleExpandedInput(expand: Boolean) {
        if (expand) {
            expandedInput.setText(inputMessage.text)
            expandedInput.setSelection(expandedInput.text.length)
            inputBar.visibility = View.GONE
            expandedInputPanel.visibility = View.VISIBLE
            expandedInput.requestFocus()
            // 关掉可能冲突的面板
            stickerPanel.visibility = View.GONE
            findViewById<LinearLayout>(R.id.plusPanel).visibility = View.GONE
            if (isBatchMode) {
                isBatchMode = false
                findViewById<TextView>(R.id.btnBatch).setTextColor(c.dateLabel)
                findViewById<LinearLayout>(R.id.pendingArea).visibility = View.GONE
                pendingItems.clear()
            }
        } else {
            inputMessage.setText(expandedInput.text)
            inputMessage.setSelection(inputMessage.text.length)
            expandedInputPanel.visibility = View.GONE
            inputBar.visibility = View.VISIBLE
        }
    }

    private fun loadApiConfig() {
        val friendStorage = FriendStorage(this)
        val friend = friendStorage.getFriend(friendId)
        if (friend != null && friend.apiUrl.isNotEmpty() && friend.apiKey.isNotEmpty()) {
            apiUrl = friend.apiUrl
            apiKey = friend.apiKey
            apiModel = friend.apiModel
            apiType = friend.apiType
        } else {
            val prefs = getSharedPreferences("haven_prefs", MODE_PRIVATE)
            apiUrl = prefs.getString("api_url", "") ?: ""
            apiKey = prefs.getString("api_key", "") ?: ""
            apiModel = prefs.getString("api_model", "") ?: ""
            apiType = "openai"
        }
        val chatPrefs = getSharedPreferences("haven_chat_prefs", MODE_PRIVATE)
        maxContextMessages = chatPrefs.getInt("context_$friendId", 30)
    }

    private fun buildContextWindow(): List<ChatMessage> {
        // 用 SystemPromptBuilder 构建四层 prompt
        val systemPrompt = SystemPromptBuilder(this).build(friendId)
        val freshSystemMsg = ChatMessage("system", systemPrompt)

        val nonSystemMsgs = chatHistory.filter { it.role != "system" }
        val recentMsgs = if (nonSystemMsgs.size > maxContextMessages) {
            nonSystemMsgs.takeLast(maxContextMessages)
        } else { nonSystemMsgs }
        return listOf(freshSystemMsg) + recentMsgs
    }

    // 已加载的消息数量（用于分批加载历史消息）
    private var loadedMessageCount = 0
    private val messagesPerPage = 50
    private var allSavedMessages: List<StoredMessage> = emptyList()

    private fun initChat() {
        val timeInfo = SimpleDateFormat("yyyy年M月d日 EEEE HH:mm", Locale.CHINESE).format(Date())
        val userName = getSharedPreferences("haven_prefs", MODE_PRIVATE)
            .getString("user_name", "") ?: ""
        val userInfo = if (userName.isNotEmpty()) "\n用户名称: $userName" else ""
        chatHistory.add(ChatMessage("system", "当前时间: $timeInfo$userInfo"))

        allSavedMessages = chatStorage.loadMessages(friendId)
        if (allSavedMessages.isEmpty()) {
            lastMessageDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            addDaySeparator(System.currentTimeMillis())
            if (apiUrl.isEmpty() || apiKey.isEmpty() || apiModel.isEmpty()) {
                addSystemTip("还没有配置 API 哦~\n请先去桌面 → 设置 → 填写 API 地址、密钥和模型名称")
                setStatus("unconfigured")
            } else {
                addSystemTip("API 已就绪，开始聊天吧 ♡")
                setStatus("online")
            }
        } else {
            // 所有消息都加进 chatHistory（给 API 用的上下文）
            var prevTimestamp = 0L
            var prevDateStr = ""
            for (msg in allSavedMessages) {
                val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    .format(Date(msg.timestamp))
                val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                    .format(Date(msg.timestamp))

                // 给 AI 的上下文也插入时间标记
                if (dateStr != prevDateStr && prevDateStr.isNotEmpty()) {
                    val dayLabel = formatDateLabel(msg.timestamp)
                    chatHistory.add(ChatMessage("system", "[日期变更: $dayLabel]"))
                } else if (prevTimestamp > 0) {
                    val gapMs = msg.timestamp - prevTimestamp
                    val gapMinutes = gapMs / 60000
                    if (gapMinutes >= 60) {
                        val gapLabel = formatGapLabel(gapMs)
                        val gapTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(msg.timestamp))
                        chatHistory.add(ChatMessage("system", "[距上条消息 $gapLabel · $gapTime]"))
                    }
                }
                prevDateStr = dateStr
                prevTimestamp = msg.timestamp

                when (msg.role) {
                    "user" -> {
                        if (msg.imagePath.isNotEmpty()) {
                            // 检查是否是多图消息
                            val pathCount = if (msg.extras.isNotEmpty()) {
                                try {
                                    JSONObject(msg.extras).optJSONArray("paths")?.length() ?: 1
                                } catch (e: Exception) { 1 }
                            } else 1
                            val desc = if (pathCount > 1) "[用户之前发送了${pathCount}张图片]" else "[用户之前发送了一张图片]"
                            chatHistory.add(ChatMessage("user", desc))
                        } else {
                            chatHistory.add(ChatMessage(msg.role, msg.content))
                        }
                    }
                    "assistant" -> {
                        chatHistory.add(ChatMessage(msg.role, msg.content))
                    }
                }
            }

            // 只渲染最近 50 条消息的气泡（性能优化）
            val recentMessages = if (allSavedMessages.size > messagesPerPage) {
                // 顶部加一个"加载更多"按钮
                addLoadMoreButton()
                allSavedMessages.takeLast(messagesPerPage)
            } else {
                allSavedMessages
            }
            loadedMessageCount = recentMessages.size

            for (msg in recentMessages) {
                checkDateSeparator(msg.timestamp)
                val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    .format(Date(msg.timestamp))
                when (msg.role) {
                    "user" -> {
                        if (msg.imagePath.isNotEmpty()) {
                            val caption = msg.content.let {
                                if (it == "[图片]" || it.startsWith("[") && it.endsWith("张图片]")) "" else it
                            }
                            // 检查多图
                            val allPaths = if (msg.extras.isNotEmpty()) {
                                try {
                                    val arr = JSONObject(msg.extras).optJSONArray("paths")
                                    if (arr != null) (0 until arr.length()).map { arr.getString(it) }
                                    else listOf(msg.imagePath)
                                } catch (e: Exception) { listOf(msg.imagePath) }
                            } else listOf(msg.imagePath)

                            if (allPaths.size > 1) {
                                addMultiImageBubble(allPaths, timeStr, caption)
                            } else {
                                addImageBubble(msg.imagePath, timeStr, caption)
                            }
                        } else {
                            addUserBubble(msg.content, timeStr)
                        }
                    }
                                        "assistant" -> {
                        if (msg.content.trim() == "[SEEN]") {
                            addSeenIndicator()
                        } else {
                            if (msg.thinking.isNotEmpty()) addThinkingBlock(msg.thinking)
                            addAiBubble(msg.content, timeStr)
                        }
                    }
                }
            }
            // 显示保存的 AI 状态
            if (currentAiStatus.isNotEmpty()) {
                tvStatus.text = currentAiStatus
                tvStatus.setTextColor(c.accent)
            }
            if (apiUrl.isEmpty() || apiKey.isEmpty() || apiModel.isEmpty()) {
                setStatus("unconfigured")
            } else {
                setStatus("online")
            }
        }
    }

    /**
     * 在顶部加一个"加载更多"按钮
     * 点击后加载更早的消息
     */
    private fun addLoadMoreButton() {
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }
        val btn = TextView(this).apply {
            text = "↑ 加载更早的消息"
            textSize = 12f
            setTextColor(c.accent)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
            tag = "load_more_btn"
            setOnClickListener { loadEarlierMessages() }
        }
        messagesContainer.addView(btn, 0)
    }

    /**
     * 加载更早的消息
     * 把按钮上面的消息往前推 50 条
     */
    private fun loadEarlierMessages() {
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }
        val total = allSavedMessages.size
        val alreadyLoaded = loadedMessageCount
        val remaining = total - alreadyLoaded

        if (remaining <= 0) return

        // 计算要加载的消息范围
        val loadCount = minOf(messagesPerPage, remaining)
        val startIndex = remaining - loadCount
        val endIndex = remaining
        val olderMessages = allSavedMessages.subList(startIndex, endIndex)

        // 移除"加载更多"按钮
        val loadMoreBtn = messagesContainer.findViewWithTag<View>("load_more_btn")
        val btnIndex = if (loadMoreBtn != null) {
            val idx = messagesContainer.indexOfChild(loadMoreBtn)
            messagesContainer.removeView(loadMoreBtn)
            idx
        } else 0

        // 在顶部插入更早的消息
        var insertIndex = btnIndex
        // 重置日期检查（因为要从更早的消息开始）
        var prevDate = ""
        for (msg in olderMessages) {
            val msgDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                .format(Date(msg.timestamp))
            if (msgDate != prevDate) {
                val dateLabel = when {
                    msgDate == SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()) -> "—— 今天 ——"
                    else -> "—— $msgDate ——"
                }
                val label = TextView(this).apply {
                    text = dateLabel
                    textSize = 10f
                    setTextColor(c.dateLabel)
                    gravity = Gravity.CENTER
                    setPadding(0, dp(12), 0, dp(8))
                }
                messagesContainer.addView(label, insertIndex)
                insertIndex++
                prevDate = msgDate
            }

            val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                .format(Date(msg.timestamp))
            when (msg.role) {
                "user" -> {
                    if (msg.imagePath.isNotEmpty()) {
                        val bubble = addImageBubbleAt(msg.imagePath, timeStr,
                            msg.content.let { if (it == "[图片]") "" else it }, insertIndex)
                        insertIndex++
                    } else {
                        val bubble = addUserBubble(msg.content, timeStr)
                        // 移到正确位置
                        messagesContainer.removeView(bubble)
                        messagesContainer.addView(bubble, insertIndex)
                        insertIndex++
                    }
                }
                "assistant" -> {
                    if (msg.content.trim() == "[SEEN]") {
                        // 简化处理：已读标记在历史里跳过
                    } else {
                        if (msg.thinking.isNotEmpty()) {
                            // 历史加载时跳过思维链，太多了会卡
                        }
                        val bubble = createAiBubbleView(msg.content, timeStr)
                        messagesContainer.addView(bubble, insertIndex)
                        insertIndex++
                    }
                }
            }
        }

        loadedMessageCount += loadCount

        // 如果还有更早的消息，重新加按钮
        if (startIndex > 0) {
            addLoadMoreButton()
        }

        Toast.makeText(this, "加载了 $loadCount 条消息", Toast.LENGTH_SHORT).show()
    }

    /**
     * 创建一个 AI 气泡 View（不自动添加到容器，用于插入到指定位置）
     */
    private fun createAiBubbleView(msg: String, timeStr: String): View {
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }
        val wrapper = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
        }
        val avatar = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply {
                marginEnd = dp(7); topMargin = dp(2)
            }
            gravity = Gravity.CENTER
            this.text = friendIcon
            textSize = 12f
            setTextColor(c.accentStrong)
            setBackgroundResource(R.drawable.icon_bg)
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val bubble = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            maxWidth = (resources.displayMetrics.widthPixels * 0.80).toInt()
            this.text = MarkdownRenderer.render(msg)
            setTextColor(c.textOnAccent)
            textSize = 14f
            setLineSpacing(0f, 1.35f)
            setPadding(dp(11), dp(8), dp(11), dp(8))
            setBackgroundResource(R.drawable.chat_bubble_ai)
            setOnLongClickListener { showMessageMenu(msg, friendName); true }
        }
        val time = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2) }
            this.text = timeStr
            textSize = 9f
            setTextColor(c.timeText)
            setPadding(dp(4), 0, 0, 0)
        }
        column.addView(bubble)
        column.addView(time)
        wrapper.addView(avatar)
        wrapper.addView(column)
        return wrapper
    }

    /**
     * 在指定位置添加图片气泡
     */
    private fun addImageBubbleAt(imagePath: String, timeStr: String, caption: String, index: Int): View {
        // 简化：历史加载时用文字代替图片，避免性能问题
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }
        val wrapper = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val bubble = TextView(this).apply {
            text = "[图片]${if (caption.isNotEmpty()) " $caption" else ""}"
            setTextColor(c.textSecondary)
            textSize = 13f
            setPadding(dp(11), dp(8), dp(11), dp(8))
            setBackgroundResource(R.drawable.chat_bubble_user)
        }
        wrapper.addView(bubble)
        messagesContainer.addView(wrapper, index)
        return wrapper
    }

    /**
     * 分条发送 —— 多条消息逐条显示，AI 统一回复
     *
     * 原理：
     * 1. 每条消息生成独立的气泡（有独立的时间戳）
     * 2. 所有消息都存到聊天记录和上下文里
     * 3. 最后一条发完之后才调用 API，让 AI 一起回复
     * 4. 每条之间间隔 300 毫秒，有逐条发出的效果
     */
    private fun sendMultiMessages(parts: List<String>) {
        if (apiUrl.isEmpty() || apiKey.isEmpty() || apiModel.isEmpty()) {
            Toast.makeText(this, "请先去设置页配置 API", Toast.LENGTH_SHORT).show()
            return
        }
 
        // 禁用发送按钮防止重复发
        btnSend.isEnabled = false
        inputMessage.isEnabled = false
 
        var index = 0
 
        val sendNext = object : Runnable {
            override fun run() {
                if (index >= parts.size) {
                    // 全部发完，调用 API 让 AI 回复
                    btnSend.isEnabled = true
                    inputMessage.isEnabled = true
                    callApiForReply()
                    return
                }
 
                val part = parts[index]
                val now = System.currentTimeMillis()
                val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(now))
 
                checkDateSeparator(now)
                addUserBubble(part, timeStr)
                chatStorage.appendMessage(friendId, StoredMessage("user", part, now))
                chatHistory.add(ChatMessage("user", "[$timeStr] $part"))
 
                index++
 
                if (index < parts.size) {
                    // 还有下一条，间隔 300 毫秒再发
                    handler.postDelayed(this, 300L)
                } else {
                    // 最后一条了，直接调 API
                    btnSend.isEnabled = true
                    inputMessage.isEnabled = true
                    callApiForReply()
                }
            }
        }
 
        handler.post(sendNext)
    }
 
    /**
     * 调用 API 获取 AI 回复
     * 从 sendMessage 里提取出来的公共逻辑，分条发送也复用
     */
    private fun callApiForReply() {
        val userBubbleView = messagesContainer.getChildAt(messagesContainer.childCount - 1)
        setStatus("sending")
        showTypingIndicator()
        Thread {
            try {
                val api = ApiHelper(apiUrl, apiKey, apiModel, apiType)
                val response = api.sendChat(buildContextWindow())
                val replyTime = System.currentTimeMillis()
                val replyTimeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    .format(Date(replyTime))
 
                // ===== 统一指令解析 =====
                val result = InstructionProcessor(this@ChatConversationActivity).process(friendId, response.text)
                val cleanText = result.cleanText
                val isSeen = result.isSeen

                // 应用状态变更
                if (result.newStatus != null) currentAiStatus = result.newStatus!!
                if (result.newName != null) friendName = result.newName!!
                if (result.newIcon != null) friendIcon = result.newIcon!!
                if (result.userBioContext != null) chatHistory.add(ChatMessage("system", result.userBioContext!!))
                if (result.shouldDream) triggerDream(friendId)
 
                // 保存到聊天记录（存的是去掉指令后的干净文本）
                chatStorage.appendMessage(friendId, StoredMessage(
                    "assistant", cleanText, replyTime, response.thinking
                ))
                chatHistory.add(ChatMessage("assistant", cleanText))

                // 检查是否该触发聊天总结
                val msgCount = chatStorage.loadMessages(friendId).size
                if (summaryStorage.shouldTriggerSummary(friendId, msgCount)) {
                    triggerChatSummary(friendId, msgCount)
                }
 
                handler.post {
                    removeTypingIndicator()
                    setStatus("online")

                    // 更新顶部状态显示
                    if (currentAiStatus.isNotEmpty()) {
                        tvStatus.text = currentAiStatus
                        tvStatus.setTextColor(c.accent)
                        getSharedPreferences("haven_status", MODE_PRIVATE)
                            .edit().putString("status_$friendId", currentAiStatus).apply()
                    }

                    // 更新顶部名字和头像（如果AI改了的话）
                    tvFriendName.text = friendName

                    // 显示所有操作提示
                    for (action in result.actions) {
                        addSystemTip(action)
                    }

 
                    if (isSeen) {
                        addSeenIndicator()
                    } else {
                        if (response.thinking.isNotEmpty()) addThinkingBlock(response.thinking)
                        addAiBubbleStreaming(cleanText, replyTimeStr)

                        // 发送通知
                        NotificationHelper(this@ChatConversationActivity).sendChatNotification(
                            friendId, friendName, friendIcon, cleanText
                        )
                    }
                }
            } catch (e: Exception) {
                val friendlyMsg = getErrorMessage(e)
                handler.post {
                    removeTypingIndicator()
                    setStatus("error", friendlyMsg)
                    addErrorBubble(friendlyMsg) { callApiForReply() }
                }
            }
        }.start()
    }

    // ===== 发送消息（文字、图片、或图片+文字） =====
    private fun sendMessage() {
        val msg = inputMessage.text.toString().trim()
        val imagePaths = pendingImagePaths.toList()  // 快照

        // 分条模式：文字和图片都蹦到待发区，不真正发送
        if (isBatchMode) {
            if (imagePaths.isNotEmpty()) {
                // 图片也进待发区
                val caption = if (msg.isNotEmpty()) msg else ""
                addToPendingImage(imagePaths, caption)
                return
            } else if (msg.isNotEmpty()) {
                addToPending(msg)
                return
            }
        }

        // 都没有就不发
        if (msg.isEmpty() && imagePaths.isEmpty()) return
        if (apiUrl.isEmpty() || apiKey.isEmpty() || apiModel.isEmpty()) {
            Toast.makeText(this, "请先去设置页配置 API", Toast.LENGTH_SHORT).show()
            return
        }

        inputMessage.text.clear()
        removePendingPreview()
        pendingImagePaths.clear()

        // 如果发图片就不带引用了
        if (imagePaths.isNotEmpty()) removeQuotePreview()

        val now = System.currentTimeMillis()
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(now))

        // 检查是否需要加日期分隔线
        checkDateSeparator(now)

        if (imagePaths.isNotEmpty()) {
            // ===== 发图片（支持多张，可能带文字） =====
            if (imagePaths.size == 1) {
                addImageBubble(imagePaths[0], timeStr, msg)
            } else {
                addMultiImageBubble(imagePaths, timeStr, msg)
            }

            val displayContent = if (msg.isNotEmpty()) msg else {
                if (imagePaths.size == 1) "[图片]" else "[${imagePaths.size}张图片]"
            }
            // 多图时用 extras 存所有路径
            val extrasJson = if (imagePaths.size > 1) {
                JSONObject().apply {
                    put("paths", JSONArray(imagePaths))
                }.toString()
            } else ""
            chatStorage.appendMessage(friendId, StoredMessage(
                "user", displayContent, now, imagePath = imagePaths[0],
                type = "image", extras = extrasJson
            ))

            val base64List = imagePaths.map { imageToBase64(File(it)) }
            val apiContent = if (msg.isNotEmpty()) msg else {
                "[用户发送了${if (imagePaths.size > 1) "${imagePaths.size}张" else "一张"}图片]"
            }
            chatHistory.add(ChatMessage("user", apiContent, base64List))

        } else {
            // ===== 纯文字（可能带引用） =====
            val quoteAuthor = pendingQuoteAuthor
            val quoteContent = pendingQuoteContent

            if (quoteAuthor != null && quoteContent != null) {
                // 带引用的消息：先显示引用块再显示气泡
                addQuoteBubble(quoteAuthor, quoteContent, msg, timeStr)
                val shortQuote = if (quoteContent.length > 50) quoteContent.substring(0, 50) + "..." else quoteContent
                chatStorage.appendMessage(friendId, StoredMessage("user", "「回复 $quoteAuthor: $shortQuote」\n$msg", now))
                chatHistory.add(ChatMessage("user", "[$timeStr] [引用 $quoteAuthor 说的: $shortQuote]\n$msg"))
                removeQuotePreview()
            } else {
                addUserBubble(msg, timeStr)
                chatStorage.appendMessage(friendId, StoredMessage("user", msg, now))
                chatHistory.add(ChatMessage("user", "[$timeStr] $msg"))
            }
        }


        // 检查 AI 是否在睡觉
        if (dreamStorage.isSleeping(friendId)) {
            val depth = dreamStorage.getSleepDepth(friendId)
            val wakeChance = Math.random()

            if (wakeChance < depth) {
                // 睡得太沉，吵不醒
                addSystemTip("💤 消息已送达（对方睡着了…吵不醒）")
                return
            } else {
                // 吵醒了
                dreamStorage.setSleeping(friendId, false)
                addSystemTip("💤 你把它吵醒了")
            }
        }

        // 调用 API
        val userBubbleView = messagesContainer.getChildAt(messagesContainer.childCount - 1)
        setStatus("sending")
        showTypingIndicator()
        Thread {
            try {
                val api = ApiHelper(apiUrl, apiKey, apiModel, apiType)
                val response = api.sendChat(buildContextWindow())
                val replyTime = System.currentTimeMillis()
                val replyTimeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    .format(Date(replyTime))

                // ===== 统一指令解析 =====
                val result = InstructionProcessor(this@ChatConversationActivity).process(friendId, response.text)
                val cleanText = result.cleanText
                val isSeen = result.isSeen

                // 应用状态变更
                if (result.newStatus != null) currentAiStatus = result.newStatus!!
                if (result.newName != null) friendName = result.newName!!
                if (result.newIcon != null) friendIcon = result.newIcon!!
                if (result.userBioContext != null) chatHistory.add(ChatMessage("system", result.userBioContext!!))
                if (result.shouldDream) triggerDream(friendId)

                // 保存到聊天记录（存的是去掉指令后的干净文本）
                chatStorage.appendMessage(friendId, StoredMessage(
                    "assistant", cleanText, replyTime, response.thinking
                ))
                chatHistory.add(ChatMessage("assistant", cleanText))

                // 检查是否该触发聊天总结
                val msgCount = chatStorage.loadMessages(friendId).size
                if (summaryStorage.shouldTriggerSummary(friendId, msgCount)) {
                    triggerChatSummary(friendId, msgCount)
                }

                handler.post {
                    removeTypingIndicator()
                    setStatus("online")

                    // 更新顶部状态显示
                    if (currentAiStatus.isNotEmpty()) {
                        tvStatus.text = currentAiStatus
                        tvStatus.setTextColor(c.accent)
                        getSharedPreferences("haven_status", MODE_PRIVATE)
                            .edit().putString("status_$friendId", currentAiStatus).apply()
                    }

                    // 更新顶部名字和头像（如果AI改了的话）
                    tvFriendName.text = friendName

                    // 显示所有操作提示
                    for (action in result.actions) {
                        addSystemTip(action)
                    }


                    if (isSeen) {
                        addSeenIndicator()
                    } else {
                        if (response.thinking.isNotEmpty()) addThinkingBlock(response.thinking)
                        addAiBubbleStreaming(cleanText, replyTimeStr)

                        // 发送通知
                        NotificationHelper(this@ChatConversationActivity).sendChatNotification(
                            friendId, friendName, friendIcon, cleanText
                        )
                    }
                }

            } catch (e: Exception) {
                val friendlyMsg = getErrorMessage(e)
                handler.post {
                    removeTypingIndicator()
                    // 发送失败时撤回
                    messagesContainer.removeView(userBubbleView)
                    if (chatHistory.isNotEmpty()) chatHistory.removeAt(chatHistory.size - 1)
                    val saved = chatStorage.loadMessages(friendId).toMutableList()
                    if (saved.isNotEmpty()) {
                        saved.removeAt(saved.size - 1)
                        chatStorage.saveMessages(friendId, saved)
                    }
                    if (imagePaths.isEmpty()) inputMessage.setText(msg)
                    setStatus("error", friendlyMsg)
                    addErrorBubble(friendlyMsg)
                }
            }
        }.start()
    }

    private fun copyToClipboard(content: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("chat_message", content))
        Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
    }

    // ===== 长按消息菜单 =====
    private fun showMessageMenu(content: String, author: String) {
        val options = arrayOf("复制", "引用回复")
        AlertDialog.Builder(this)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> copyToClipboard(content)
                    1 -> showQuotePreview(author, content)
                }
            }
            .show()
    }

    // ===== 显示引用预览条 =====
    private fun showQuotePreview(author: String, content: String) {
        removeQuotePreview()
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }

        pendingQuoteAuthor = author
        pendingQuoteContent = content

        val previewLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(c.accentBg)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }

        // 左边紫色竖条
        val bar = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(3), LinearLayout.LayoutParams.MATCH_PARENT).apply {
                marginEnd = dp(8)
            }
            setBackgroundColor(c.accent)
        }

        // 引用内容
        val textLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val authorView = TextView(this).apply {
            this.text = "回复 $author"
            textSize = 11f
            setTextColor(c.accentStrong)
        }

        // 截取前30个字符
        val shortContent = if (content.length > 30) content.substring(0, 30) + "..." else content
        val contentView = TextView(this).apply {
            this.text = shortContent
            textSize = 11f
            setTextColor(c.textSecondary)
            maxLines = 1
        }

        textLayout.addView(authorView)
        textLayout.addView(contentView)

        val cancelBtn = TextView(this).apply {
            this.text = "✕"
            textSize = 16f
            setTextColor(c.tipText)
            setPadding(dp(8), 0, 0, 0)
            setOnClickListener { removeQuotePreview() }
        }

        previewLayout.addView(bar)
        previewLayout.addView(textLayout)
        previewLayout.addView(cancelBtn)

        quotePreviewContainer.removeAllViews()
        quotePreviewContainer.addView(previewLayout)
        quotePreviewContainer.visibility = View.VISIBLE

        // 聚焦到输入框
        inputMessage.requestFocus()
    }

    private fun removeQuotePreview() {
        pendingQuoteAuthor = null
        pendingQuoteContent = null
        quotePreviewContainer.removeAllViews()
        quotePreviewContainer.visibility = View.GONE
    }

    // ===== 思维链折叠块 =====
    private fun addThinkingBlock(thinking: String) {
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }
        val wrapper = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) }
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
        }
        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(37), dp(1))
        }
        val thinkingLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val contentView = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            maxWidth = (resources.displayMetrics.widthPixels * 0.75).toInt()
            this.text = thinking
            setTextColor(c.accent)
            textSize = 12f
            setLineSpacing(0f, 1.3f)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setBackgroundResource(R.drawable.chat_thinking_bg)
            visibility = View.GONE
        }
        val toggleView = TextView(this).apply {
            this.text = "💭 思考过程 ▸"
            textSize = 11f
            setTextColor(c.accent)
            setPadding(dp(4), dp(2), dp(4), dp(2))
            setOnClickListener {
                if (contentView.visibility == View.GONE) {
                    contentView.visibility = View.VISIBLE
                    this.text = "💭 思考过程 ▾"
                } else {
                    contentView.visibility = View.GONE
                    this.text = "💭 思考过程 ▸"
                }
            }
        }
        contentView.setOnLongClickListener { copyToClipboard(thinking); true }
        thinkingLayout.addView(toggleView)
        thinkingLayout.addView(contentView)
        wrapper.addView(spacer)
        wrapper.addView(thinkingLayout)
        messagesContainer.addView(wrapper)
    }

    // ===== 带引用的用户气泡 =====
    private fun addQuoteBubble(quoteAuthor: String, quoteContent: String, msg: String, timeStr: String): View {
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }

        val wrapper = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            gravity = Gravity.END
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // 引用块
        val quoteBlock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(c.accentBg)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val bar = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(2), LinearLayout.LayoutParams.MATCH_PARENT).apply {
                marginEnd = dp(6)
            }
            setBackgroundColor(c.accent)
        }

        val quoteText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        quoteText.addView(TextView(this).apply {
            this.text = quoteAuthor
            textSize = 10f
            setTextColor(c.accentStrong)
        })

        val shortContent = if (quoteContent.length > 40) quoteContent.substring(0, 40) + "..." else quoteContent
        quoteText.addView(TextView(this).apply {
            this.text = shortContent
            textSize = 11f
            setTextColor(c.textSecondary)
            maxLines = 2
            maxWidth = (resources.displayMetrics.widthPixels * 0.65).toInt()
        })

        quoteBlock.addView(bar)
        quoteBlock.addView(quoteText)
        column.addView(quoteBlock)

        // 正文气泡
        val bubble = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(3) }
            maxWidth = (resources.displayMetrics.widthPixels * 0.82).toInt()
            this.text = MarkdownRenderer.render(msg)
            setTextColor(c.textOnAccent)
            textSize = 14f
            setLineSpacing(0f, 1.35f)
            setPadding(dp(11), dp(8), dp(11), dp(8))
            setBackgroundResource(R.drawable.chat_bubble_user)
            setOnLongClickListener { showMessageMenu(msg, "我"); true }
        }
        column.addView(bubble)

        val time = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2) }
            gravity = Gravity.END
            this.text = timeStr
            textSize = 9f
            setTextColor(c.timeText)
            setPadding(0, 0, dp(4), 0)
        }
        column.addView(time)

        wrapper.addView(column)
        messagesContainer.addView(wrapper)
        scrollToBottom()
        return wrapper
    }

    // ===== 用户气泡 =====
    private fun addUserBubble(msg: String, timeStr: String): View {
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }
        val wrapper = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            gravity = Gravity.END
        }
        val bubble = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            maxWidth = (resources.displayMetrics.widthPixels * 0.82).toInt()
            this.text = MarkdownRenderer.render(msg)
            setTextColor(c.textOnAccent)
            textSize = 14f
            setLineSpacing(0f, 1.35f)
            setPadding(dp(11), dp(8), dp(11), dp(8))
            setBackgroundResource(R.drawable.chat_bubble_user)
            setOnLongClickListener { showMessageMenu(msg, "我"); true }
        }
        val time = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2) }
            gravity = Gravity.END
            this.text = timeStr
            textSize = 9f
            setTextColor(c.timeText)
            setPadding(0, 0, dp(4), 0)
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        column.addView(bubble)
        column.addView(time)
        wrapper.addView(column)
        messagesContainer.addView(wrapper)
        scrollToBottom()
        return wrapper
    }

    // ===== AI 气泡 =====
    private fun addAiBubble(msg: String, timeStr: String) {
        // 处理分条消息
        if (msg.contains("[SPLIT]")) {
            val parts = msg.split("[SPLIT]").map { it.trim() }.filter { it.isNotEmpty() }
            for (part in parts) {
                renderAiSegmentStatic(part, timeStr)
            }
            return
        }
        renderAiSegmentStatic(msg, timeStr)
    }

    /** 静态渲染一段AI消息（加载历史时用，跟 renderAiSegment 一样但不带动画） */
    private fun renderAiSegmentStatic(segment: String, timeStr: String) {
        val stickerPattern = Regex("\\[STICKER_IMG:(.+?)]")
        if (!segment.contains("[STICKER_IMG:")) {
            addAiBubbleSingleStatic(segment, timeStr)
            return
        }
        var remaining = segment
        var match = stickerPattern.find(remaining)
        while (match != null) {
            val textBefore = remaining.substring(0, match.range.first).trim()
            if (textBefore.isNotEmpty()) addAiBubbleSingleStatic(textBefore, timeStr)
            val stickerPath = match.groupValues[1]
            addAiImageBubble(stickerPath, timeStr)
            remaining = remaining.substring(match.range.last + 1)
            match = stickerPattern.find(remaining)
        }
        val textAfter = remaining.trim()
        if (textAfter.isNotEmpty()) addAiBubbleSingleStatic(textAfter, timeStr)
    }

    private fun addAiBubbleSingleStatic(msg: String, timeStr: String) {
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }
        val wrapper = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
        }
        val avatar = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply {
                marginEnd = dp(7); topMargin = dp(2)
            }
            gravity = Gravity.CENTER
            this.text = friendIcon
            textSize = 12f
            setTextColor(c.accentStrong)
            setBackgroundResource(R.drawable.icon_bg)
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val bubble = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            maxWidth = (resources.displayMetrics.widthPixels * 0.80).toInt()
            this.text = MarkdownRenderer.render(msg)
            setTextColor(c.textOnAccent)
            textSize = 14f
            setLineSpacing(0f, 1.35f)
            setPadding(dp(11), dp(8), dp(11), dp(8))
            setBackgroundResource(R.drawable.chat_bubble_ai)
            setOnLongClickListener { showMessageMenu(msg, friendName); true }
        }
        val time = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2) }
            this.text = timeStr
            textSize = 9f
            setTextColor(c.timeText)
            setPadding(dp(4), 0, 0, 0)
        }
        column.addView(bubble)
        column.addView(time)
        wrapper.addView(avatar)
        wrapper.addView(column)
        messagesContainer.addView(wrapper)
        scrollToBottom()
    }

    // ===== AI 发表情包（左侧，带头像） =====
    private fun addAiImageBubble(imagePath: String, timeStr: String) {
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }
        val file = File(imagePath)
        if (!file.exists()) return

        val wrapper = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
        }
        val avatar = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply {
                marginEnd = dp(7); topMargin = dp(2)
            }
            gravity = Gravity.CENTER
            this.text = friendIcon
            textSize = 12f
            setTextColor(c.accentStrong)
            setBackgroundResource(R.drawable.icon_bg)
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val imageView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(120), dp(120))
            scaleType = ImageView.ScaleType.CENTER_CROP
            val bitmap = BitmapFactory.decodeFile(imagePath)
            if (bitmap != null) setImageBitmap(bitmap)
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(10).toFloat())
                }
            }
            setOnClickListener { showFullImage(imagePath) }
        }
        val time = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2) }
            this.text = timeStr
            textSize = 9f
            setTextColor(c.timeText)
            setPadding(dp(4), 0, 0, 0)
        }
        column.addView(imageView)
        column.addView(time)
        wrapper.addView(avatar)
        wrapper.addView(column)
        messagesContainer.addView(wrapper)
        scrollToBottom()
    }

    /**
     *
     * 原理：
     * 1. 先创建一个空的气泡（跟 addAiBubble 一样的外观）
     * 2. 用 handler.postDelayed 定时往气泡里追加文字
     * 3. 每次追加几个字，间隔很短，看起来就像在打字
     * 4. 全部显示完之后，把纯文本替换成 Markdown 渲染后的版本
     *
     * chunkSize = 每次蹦几个字（越大越快）
     * delay = 每次蹦字之间隔多少毫秒（越小越快）
     */
         // ===== 表情包面板：显示/隐藏 =====
    // ===== 分条模式状态 =====
    private var isBatchMode = false
    // 待发消息：支持文字和图片
    data class PendingItem(
        val type: String,  // "text" 或 "image"
        val text: String = "",
        val imagePaths: List<String> = emptyList()
    )
    private val pendingItems = mutableListOf<PendingItem>()

    /**
     * 加号菜单：在输入栏上方显示/隐藏
     */
    private fun showPlusMenu() {
        val plusPanel = findViewById<LinearLayout>(R.id.plusPanel)
        val pendingArea = findViewById<LinearLayout>(R.id.pendingArea)

        if (plusPanel.visibility == View.VISIBLE) {
            plusPanel.visibility = View.GONE
            return
        }

        stickerPanel.visibility = View.GONE
        plusPanel.visibility = View.VISIBLE

        findViewById<LinearLayout>(R.id.plusBtnImage).setOnClickListener {
            plusPanel.visibility = View.GONE
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            startActivityForResult(intent, PICK_IMAGE)
        }

        findViewById<LinearLayout>(R.id.plusBtnSticker).setOnClickListener {
            plusPanel.visibility = View.GONE
            toggleStickerPanel()
        }
    }

    /**
     * 切换分条模式
     */
    private fun toggleBatchMode() {
        val btnBatch = findViewById<TextView>(R.id.btnBatch)
        val pendingArea = findViewById<LinearLayout>(R.id.pendingArea)

        isBatchMode = !isBatchMode

        if (isBatchMode) {
            btnBatch.setTextColor(c.accent)
            pendingArea.visibility = View.VISIBLE
            pendingItems.clear()
            refreshPendingUI()
            // 关掉其他面板
            findViewById<LinearLayout>(R.id.plusPanel).visibility = View.GONE
            stickerPanel.visibility = View.GONE
            // 如果展开面板开着，先收起来
            if (expandedInputPanel.visibility == View.VISIBLE) {
                toggleExpandedInput(false)
            }
        } else {
            btnBatch.setTextColor(c.dateLabel)
            pendingArea.visibility = View.GONE
            pendingItems.clear()
        }
    }

    /**
     * 分条模式下按发送：消息蹦到待发区
     */
    private fun addToPending(text: String) {
        pendingItems.add(PendingItem("text", text))
        inputMessage.text.clear()
        refreshPendingUI()
    }

    private fun addToPendingImage(paths: List<String>, caption: String) {
        pendingItems.add(PendingItem("image", caption, paths))
        inputMessage.text.clear()
        removePendingPreview()
        pendingImagePaths.clear()
        refreshPendingUI()
    }

    /**
     * 刷新待发区 UI
     */
    private fun refreshPendingUI() {
        val container = findViewById<LinearLayout>(R.id.pendingMessages)
        val countView = findViewById<TextView>(R.id.pendingCount)
        container.removeAllViews()
        countView.text = "待发送 (${pendingItems.size})"

        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }

        for ((index, item) in pendingItems.withIndex()) {
            val itemBg = android.graphics.drawable.GradientDrawable().apply {
                setColor(c.card)
                cornerRadius = dp(8).toFloat()
                setStroke(1, c.border)
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = itemBg
                setPadding(dp(10), dp(6), dp(6), dp(6))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(4) }
            }

            if (item.type == "image") {
                // 图片条目：显示缩略图 + 文字说明
                val thumb = ImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(8) }
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    val bitmap = BitmapFactory.decodeFile(item.imagePaths.firstOrNull() ?: "")
                    if (bitmap != null) setImageBitmap(bitmap)
                }
                row.addView(thumb)
                val label = TextView(this).apply {
                    text = if (item.text.isNotEmpty()) "📷 ${item.text}"
                           else "📷 ${item.imagePaths.size}张图片"
                    textSize = 12f; setTextColor(c.textPrimary)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    maxLines = 1
                }
                row.addView(label)
            } else {
                val textView = TextView(this).apply {
                    text = item.text; textSize = 12f; setTextColor(c.textPrimary)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    maxLines = 2
                }
                row.addView(textView)
            }

            val deleteBtn = TextView(this).apply {
                text = "✕"; textSize = 14f; setTextColor(c.textHint)
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener {
                    pendingItems.removeAt(index)
                    refreshPendingUI()
                }
            }
            row.addView(deleteBtn)
            container.addView(row)
        }
    }

    /**
     * 发送全部待发消息
     */
    private fun sendAllPending() {
        if (pendingItems.isEmpty()) return
        val items = pendingItems.toList()
        pendingItems.clear()
        isBatchMode = false
        findViewById<TextView>(R.id.btnBatch).setTextColor(c.dateLabel)
        findViewById<LinearLayout>(R.id.pendingArea).visibility = View.GONE

        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var delay = 0L

        // 引用只附在第一条上
        val quoteAuthor = pendingQuoteAuthor
        val quoteContent = pendingQuoteContent
        removeQuotePreview()

        // 收集所有文字内容给 API
        val allTextForApi = StringBuilder()
        if (quoteAuthor != null && quoteContent != null) {
            allTextForApi.append("[引用 $quoteAuthor: $quoteContent]\n")
        }

        // 按顺序一条条蹦出去
        for ((index, item) in items.withIndex()) {
            handler.postDelayed({
                val now = System.currentTimeMillis()
                val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(now))
                checkDateSeparator(now)

                if (item.type == "image") {
                    // 图片条目
                    if (item.imagePaths.size == 1) {
                        addImageBubble(item.imagePaths[0], timeStr, item.text)
                    } else {
                        addMultiImageBubble(item.imagePaths, timeStr, item.text)
                    }
                    val displayContent = if (item.text.isNotEmpty()) item.text else "[${item.imagePaths.size}张图片]"
                    chatStorage.appendMessage(friendId, StoredMessage(
                        "user", displayContent, now, imagePath = item.imagePaths[0], type = "image"
                    ))
                    val base64List = item.imagePaths.map { imageToBase64(File(it)) }
                    chatHistory.add(ChatMessage("user",
                        if (item.text.isNotEmpty()) item.text else "[用户发送了图片]", base64List))
                } else {
                    // 文字条目
                    if (index == 0 && quoteAuthor != null && quoteContent != null) {
                        addQuoteBubble(quoteAuthor, quoteContent, item.text, timeStr)
                        chatStorage.appendMessage(friendId, StoredMessage(
                            "user", "「回复 $quoteAuthor」\n${item.text}", now, type = "quote"
                        ))
                    } else {
                        addUserBubble(item.text, timeStr)
                        chatStorage.appendMessage(friendId, StoredMessage("user", item.text, now))
                    }
                }

                // 最后一条发完，统一调 API
                if (index == items.size - 1) {
                    // 文字条目合并成一条给 API
                    val textContent = allTextForApi.toString()
                    if (textContent.isNotEmpty()) {
                        chatHistory.add(ChatMessage("user", textContent))
                    }
                    callApiForReply()
                }
            }, delay)

            // 收集文字给 API
            if (item.type == "text") {
                allTextForApi.append(item.text).append("\n")
            } else if (item.text.isNotEmpty()) {
                allTextForApi.append(item.text).append("\n")
            }

            delay += 300L
        }
    }

    /**
     * 批量发送消息：一条条蹦出去，但只调一次 API
     */
    private fun sendBatchMessages(texts: List<String>) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val allText = texts.joinToString("\n")
        var delay = 0L

        // 引用只附在第一条上
        val quoteAuthor = pendingQuoteAuthor
        val quoteContent = pendingQuoteContent
        removeQuotePreview()

        for ((index, text) in texts.withIndex()) {
            handler.postDelayed({
                val now = System.currentTimeMillis()
                val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now))
                checkDateSeparator(now)

                if (index == 0 && quoteAuthor != null && quoteContent != null) {
                    // 第一条带引用
                    addQuoteBubble(quoteAuthor, quoteContent, text, timeStr)
                    chatStorage.appendMessage(friendId, StoredMessage(
                        "user", text, now,
                        type = "quote",
                        extras = JSONObject().apply {
                            put("quote_author", quoteAuthor)
                            put("quote_content", quoteContent)
                        }.toString()
                    ))
                } else {
                    addUserBubble(text, timeStr)
                    chatStorage.appendMessage(friendId, StoredMessage("user", text, now))
                }

                if (index == texts.size - 1) {
                    val apiText = if (quoteAuthor != null && quoteContent != null) {
                        "「引用 $quoteAuthor: $quoteContent」\n$allText"
                    } else allText
                    chatHistory.add(ChatMessage("user", apiText))
                    callApiForReply()
                }
            }, delay)
            delay += 300L
        }
    }

    private fun toggleStickerPanel() {
        if (stickerPanel.visibility == View.VISIBLE) {
            stickerPanel.visibility = View.GONE
        } else {
            stickerPanel.visibility = View.VISIBLE
            refreshStickerGrid()
        }
    }
 
    // ===== 刷新表情包网格（4列纵向网格 + 分组 + 管理模式） =====
    private fun refreshStickerGrid() {
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }
        val columns = 4

        // 1. 分组标签栏
        stickerGroupTabs.removeAllViews()
        val groups = stickerStorage.loadGroups()
        if (groups.isNotEmpty()) {
            val allCount = stickerStorage.count()
            val allTab = makeGroupTab("全部($allCount)", currentStickerGroup == null, dp)
            allTab.setOnClickListener { currentStickerGroup = null; refreshStickerGrid() }
            stickerGroupTabs.addView(allTab)
            for ((name, count) in groups) {
                val tab = makeGroupTab("$name($count)", currentStickerGroup == name, dp)
                tab.setOnClickListener { currentStickerGroup = name; refreshStickerGrid() }
                tab.setOnLongClickListener { showGroupOptionsDialog(name); true }
                stickerGroupTabs.addView(tab)
            }
        }

        // 2. 管理按钮状态
        findViewById<TextView>(R.id.btnManageSticker).apply {
            text = if (isStickerManageMode) "完成" else "管理"
            setTextColor(if (isStickerManageMode) c.accent else c.textSecondary)
        }

        // 3. 表情包网格（4列纵向）
        stickerGrid.removeAllViews()
        val stickers = if (currentStickerGroup != null) {
            stickerStorage.loadByGroup(currentStickerGroup!!)
        } else {
            stickerStorage.loadStickers()
        }

        if (stickers.isEmpty()) {
            val tip = TextView(this).apply {
                text = if (currentStickerGroup != null) "「${currentStickerGroup}」里还没有表情包"
                       else "还没有表情包哦，点右上角「＋ 导入」添加"
                textSize = 12f; setTextColor(c.tipText)
                setPadding(dp(8), dp(30), dp(8), dp(30))
                gravity = Gravity.CENTER
            }
            stickerGrid.addView(tip)
            stickerActionBar.visibility = View.GONE
            return
        }

        // 按行排列
        val thumbSize = dp(72)
        var currentRow: LinearLayout? = null
        for ((index, sticker) in stickers.withIndex()) {
            if (index % columns == 0) {
                currentRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(6) }
                }
                stickerGrid.addView(currentRow)
            }

            val file = stickerStorage.getFile(sticker) ?: continue
            val isSelected = sticker.id in selectedStickerIds

            // 每个格子：图片 + 标签
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val imgContainer = android.widget.FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(thumbSize, thumbSize).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                }
            }

            val imageView = ImageView(this).apply {
                layoutParams = android.widget.FrameLayout.LayoutParams(thumbSize, thumbSize)
                scaleType = ImageView.ScaleType.CENTER_CROP
                val bg = android.graphics.drawable.GradientDrawable().apply {
                    setColor(c.accentBg)
                    cornerRadius = dp(8).toFloat()
                    if (isSelected) setStroke(dp(3), c.accent)
                    else setStroke(1, c.timeText)
                }
                background = bg
                setPadding(dp(3), dp(3), dp(3), dp(3))
                clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, dp(8).toFloat())
                    }
                }
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                if (bitmap != null) setImageBitmap(bitmap)
            }
            imgContainer.addView(imageView)

            // 管理模式勾选标记
            if (isStickerManageMode && isSelected) {
                val check = TextView(this).apply {
                    layoutParams = android.widget.FrameLayout.LayoutParams(dp(20), dp(20)).apply {
                        gravity = Gravity.TOP or Gravity.END
                        topMargin = dp(2); marginEnd = dp(2)
                    }
                    text = "✓"; textSize = 11f; gravity = Gravity.CENTER
                    setTextColor(0xFFFFFFFF.toInt())
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(c.accent); cornerRadius = dp(10).toFloat()
                    }
                }
                imgContainer.addView(check)
            }

            cell.addView(imgContainer)

            // 标签（如果有）
            if (sticker.label.isNotEmpty()) {
                val label = TextView(this).apply {
                    text = sticker.label
                    textSize = 9f
                    setTextColor(c.textSecondary)
                    gravity = Gravity.CENTER
                    maxLines = 1
                    setPadding(0, dp(2), 0, 0)
                    layoutParams = LinearLayout.LayoutParams(thumbSize, LinearLayout.LayoutParams.WRAP_CONTENT)
                }
                cell.addView(label)
            }

            // 点击事件
            if (isStickerManageMode) {
                cell.setOnClickListener {
                    if (sticker.id in selectedStickerIds) selectedStickerIds.remove(sticker.id)
                    else selectedStickerIds.add(sticker.id)
                    refreshStickerGrid()
                }
            } else {
                cell.setOnClickListener { sendSticker(file) }
                cell.setOnLongClickListener { showStickerOptionsDialog(sticker); true }
            }

            currentRow?.addView(cell)
        }

        // 补齐最后一行空位
        val remainder = stickers.size % columns
        if (remainder != 0 && currentRow != null) {
            for (i in 0 until (columns - remainder)) {
                val spacer = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                }
                currentRow.addView(spacer)
            }
        }

        // 4. 管理模式底部操作栏
        stickerActionBar.removeAllViews()
        if (isStickerManageMode) {
            stickerActionBar.visibility = View.VISIBLE
            // 全选/取消全选按钮
            val allSelected = selectedStickerIds.size == stickers.size && stickers.isNotEmpty()
            stickerActionBar.addView(TextView(this).apply {
                text = if (allSelected) "取消全选" else "全选"
                textSize = 12f; setTextColor(c.accent)
                setPadding(dp(8), dp(4), dp(12), dp(4))
                setOnClickListener {
                    if (allSelected) {
                        selectedStickerIds.clear()
                    } else {
                        selectedStickerIds.addAll(stickers.map { it.id })
                    }
                    refreshStickerGrid()
                }
            })
            stickerActionBar.addView(TextView(this).apply {
                text = if (selectedStickerIds.isEmpty()) "点击选择" else "已选 ${selectedStickerIds.size} 张"
                textSize = 12f; setTextColor(c.textSecondary)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (selectedStickerIds.isNotEmpty()) {
                stickerActionBar.addView(TextView(this).apply {
                    text = "标签"; textSize = 12f; setTextColor(c.accent)
                    setPadding(dp(12), dp(4), dp(12), dp(4))
                    setOnClickListener {
                        val input = EditText(this@ChatConversationActivity).apply {
                            hint = "给选中的表情包统一设标签"; setPadding(48, 32, 48, 32)
                        }
                        AlertDialog.Builder(this@ChatConversationActivity)
                            .setTitle("批量设置标签").setView(input)
                            .setPositiveButton("保存") { _, _ ->
                                for (id in selectedStickerIds) stickerStorage.setLabel(id, input.text.toString().trim())
                                refreshStickerGrid()
                            }.setNegativeButton("取消", null).show()
                    }
                })
                stickerActionBar.addView(TextView(this).apply {
                    text = "移动"; textSize = 12f; setTextColor(c.accent)
                    setPadding(dp(12), dp(4), dp(12), dp(4))
                    setOnClickListener { showMoveToGroupDialog(selectedStickerIds.toList()) }
                })
                stickerActionBar.addView(TextView(this).apply {
                    text = "删除"; textSize = 12f; setTextColor(c.errorText)
                    setPadding(dp(12), dp(4), dp(12), dp(4))
                    setOnClickListener {
                        val count = selectedStickerIds.size
                        AlertDialog.Builder(this@ChatConversationActivity)
                            .setTitle("删除 $count 张表情包？")
                            .setPositiveButton("删除") { _, _ ->
                                stickerStorage.batchDelete(selectedStickerIds.toList())
                                selectedStickerIds.clear(); refreshStickerGrid()
                            }.setNegativeButton("取消", null).show()
                    }
                })
            }
        } else {
            stickerActionBar.visibility = View.GONE
        }
    }

    private fun makeGroupTab(text: String, selected: Boolean, dp: (Int) -> Int): TextView {
        return TextView(this).apply {
            this.text = text; textSize = 11f
            setPadding(dp(10), dp(4), dp(10), dp(4))
            val bg = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                if (selected) setColor(c.accent) else setColor(c.accentBg)
            }
            background = bg
            setTextColor(if (selected) c.textOnAccent else c.textSecondary)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) }
        }
    }

    private fun showStickerOptionsDialog(sticker: Sticker) {
        val fresh = stickerStorage.findById(sticker.id) ?: sticker
        val options = arrayOf(
            "设置标签（当前：${fresh.label.ifEmpty { "无" }}）",
            "移动到分组（当前：${fresh.group}）",
            "删除"
        )
        AlertDialog.Builder(this).setItems(options) { _, which ->
            when (which) {
                0 -> {
                    val input = EditText(this).apply {
                        setText(fresh.label); hint = "简短描述，比如「思考」「嗯？」"; setPadding(48, 32, 48, 32)
                    }
                    AlertDialog.Builder(this).setTitle("设置标签").setView(input)
                        .setPositiveButton("保存") { _, _ ->
                            stickerStorage.setLabel(fresh.id, input.text.toString().trim())
                            refreshStickerGrid()
                        }.setNegativeButton("取消", null).show()
                }
                1 -> showMoveToGroupDialog(listOf(fresh.id))
                2 -> {
                    stickerStorage.deleteSticker(fresh.id); refreshStickerGrid()
                    Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
                }
            }
        }.show()
    }

    private fun showMoveToGroupDialog(stickerIds: List<String>) {
        val groups = stickerStorage.loadGroups().map { it.first }.toMutableList()
        groups.add("＋ 新建分组")
        AlertDialog.Builder(this).setTitle("移动到")
            .setItems(groups.toTypedArray()) { _, which ->
                if (which == groups.size - 1) {
                    val input = EditText(this).apply { hint = "分组名"; setPadding(48, 32, 48, 32) }
                    AlertDialog.Builder(this).setTitle("新建分组").setView(input)
                        .setPositiveButton("确定") { _, _ ->
                            val name = input.text.toString().trim()
                            if (name.isNotEmpty()) {
                                stickerStorage.batchSetGroup(stickerIds, name)
                                selectedStickerIds.clear(); refreshStickerGrid()
                                Toast.makeText(this, "已移动到「$name」", Toast.LENGTH_SHORT).show()
                            }
                        }.setNegativeButton("取消", null).show()
                } else {
                    stickerStorage.batchSetGroup(stickerIds, groups[which])
                    selectedStickerIds.clear(); refreshStickerGrid()
                    Toast.makeText(this, "已移动到「${groups[which]}」", Toast.LENGTH_SHORT).show()
                }
            }.show()
    }

    private fun showGroupOptionsDialog(groupName: String) {
        AlertDialog.Builder(this).setTitle("分组：$groupName")
            .setItems(arrayOf("重命名", "删除分组（表情包移到未分类）")) { _, which ->
                when (which) {
                    0 -> {
                        val input = EditText(this).apply { setText(groupName); setPadding(48, 32, 48, 32) }
                        AlertDialog.Builder(this).setTitle("重命名分组").setView(input)
                            .setPositiveButton("确定") { _, _ ->
                                val n = input.text.toString().trim()
                                if (n.isNotEmpty() && n != groupName) {
                                    stickerStorage.renameGroup(groupName, n)
                                    if (currentStickerGroup == groupName) currentStickerGroup = n
                                    refreshStickerGrid()
                                }
                            }.setNegativeButton("取消", null).show()
                    }
                    1 -> {
                        stickerStorage.deleteGroup(groupName)
                        if (currentStickerGroup == groupName) currentStickerGroup = null
                        refreshStickerGrid()
                        Toast.makeText(this, "已删除分组", Toast.LENGTH_SHORT).show()
                    }
                }
            }.show()
    }

    private fun showStickerManageDialog() {
        isStickerManageMode = !isStickerManageMode
        if (!isStickerManageMode) selectedStickerIds.clear()
        refreshStickerGrid()
    }

    // ===== 发送表情包（本质就是发图片） =====
    private fun sendSticker(stickerFile: File) {
        pendingImagePaths.clear()
        pendingImagePaths.add(stickerFile.absolutePath)
        stickerPanel.visibility = View.GONE
        sendMessage()
    }
    // ===== 关闭搜索面板 =====
    private fun closeSearch() {
        searchPanel.visibility = View.GONE
        searchInput.text.clear()
        searchResults.removeAllViews()
        searchResultsScroll.visibility = View.GONE
        // 收起键盘
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(searchInput.windowToken, 0)
    }
 
    /**
     * 执行搜索：从存储的聊天记录里找包含关键词的消息
     *
     * 原理很简单：
     * 1. 从 ChatStorage 里读取所有消息
     * 2. 过滤出包含关键词的消息（不区分大小写）
     * 3. 渲染成一个个卡片显示在搜索结果区域
     * 4. 每个卡片显示：谁说的、什么时候说的、说了什么（关键词高亮）
     */
    private fun performSearch(keyword: String) {
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }
        searchResults.removeAllViews()
 
        val allMessages = chatStorage.loadMessages(friendId)
        val matches = allMessages.filter {
            it.content.contains(keyword, ignoreCase = true) && it.content != "[SEEN]"
        }
 
        if (matches.isEmpty()) {
            val tip = TextView(this).apply {
                text = "没有找到相关记录"
                textSize = 12f
                setTextColor(c.tipText)
                setPadding(dp(4), dp(12), dp(4), dp(12))
            }
            searchResults.addView(tip)
            searchResultsScroll.visibility = View.VISIBLE
 
            // 限制搜索结果最大高度为屏幕的 40%
            val maxH = (resources.displayMetrics.heightPixels * 0.4).toInt()
            searchResultsScroll.layoutParams.height = maxH
            return
        }
 
        // 限制搜索结果最大高度为屏幕的 40%
        val maxH = (resources.displayMetrics.heightPixels * 0.4).toInt()
        searchResultsScroll.layoutParams.height = maxH
        searchResultsScroll.visibility = View.VISIBLE
 
        // 最多显示 50 条结果，避免太卡
        val showList = if (matches.size > 50) matches.takeLast(50) else matches
 
        for (msg in showList) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                setBackgroundColor(c.divider)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(4) }
            }
 
            // 第一行：角色 + 时间
            val header = TextView(this).apply {
                val role = if (msg.role == "user") "我" else friendName
                val time = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
                    .format(java.util.Date(msg.timestamp))
                text = "$role · $time"
                textSize = 10f
                setTextColor(c.accent)
            }
 
            // 第二行：消息内容（关键词高亮）
            val content = TextView(this).apply {
                // 截取消息，太长的话只显示关键词附近的片段
                val fullText = msg.content
                val displayText = if (fullText.length > 120) {
                    val idx = fullText.indexOf(keyword, ignoreCase = true)
                    val start = maxOf(0, idx - 40)
                    val end = minOf(fullText.length, idx + keyword.length + 40)
                    (if (start > 0) "..." else "") +
                        fullText.substring(start, end) +
                        (if (end < fullText.length) "..." else "")
                } else fullText
 
                // 高亮关键词
                val spannable = android.text.SpannableString(displayText)
                var searchStart = 0
                val lowerDisplay = displayText.lowercase()
                val lowerKeyword = keyword.lowercase()
                while (true) {
                    val idx = lowerDisplay.indexOf(lowerKeyword, searchStart)
                    if (idx == -1) break
                    spannable.setSpan(
                        android.text.style.ForegroundColorSpan(c.highlightColor),
                        idx, idx + keyword.length,
                        android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    searchStart = idx + keyword.length
                }
 
                this.text = spannable
                textSize = 13f
                setTextColor(c.textSecondary)
                setPadding(0, dp(4), 0, 0)
                maxLines = 3
            }
 
            card.addView(header)
            card.addView(content)
            searchResults.addView(card)
        }
 
        // 顶部显示搜索数量
        val countTip = TextView(this).apply {
            text = "找到 ${matches.size} 条记录" +
                    (if (matches.size > 50) "（显示最近 50 条）" else "")
            textSize = 10f
            setTextColor(c.textHint)
            setPadding(dp(4), dp(4), dp(4), dp(8))
        }
        searchResults.addView(countTip, 0)
    }

    // ===== 已读不回：在最后一条消息下面显示「已读」 =====

    /**
     * 触发造梦 — 在后台线程调用 API 生成梦境
     *
     * 收集 AI 的记忆、日记、印象、最近聊天作为素材
     * 发给 API，让它生成一段梦境
     * API 返回的内容决定：不做梦 / 完整的梦 / 忘了的梦
     */

    /**
     * 触发聊天总结 — 后台调用 API 总结最近的对话
     */
    private fun triggerChatSummary(friendId: String, currentCount: Int) {
        Thread {
            try {
                val interval = summaryStorage.getSummaryInterval(friendId)
                val messages = chatStorage.loadMessages(friendId)
                
                // 取最近 interval 条消息作为总结素材
                val recentMsgs = messages.takeLast(interval)
                val chatContent = recentMsgs.joinToString("\n") { msg ->
                    val role = if (msg.role == "user") "用户" else "AI"
                    val time = java.text.SimpleDateFormat("M月d日(E) HH:mm", java.util.Locale.CHINESE)
                        .format(java.util.Date(msg.timestamp))
                    "[$time] $role: ${msg.content.take(200)}"
                }

                val summaryPrompt = summaryStorage.buildSummaryRequestPrompt()
                val api = ApiHelper(apiUrl, apiKey, apiModel, apiType)
                val summaryMessages = listOf(
                    ChatMessage("system", summaryPrompt),
                    ChatMessage("user", chatContent)
                )
                val response = api.sendChat(summaryMessages)

                val result = summaryStorage.parseSummaryResponse(response.text)
                if (result != null) {
                    val (content, keywords) = result
                    val range = "第${currentCount - interval + 1}条~第${currentCount}条"
                    summaryStorage.addSummary(friendId, content, keywords, range)
                    summaryStorage.setLastSummaryMessageCount(friendId, currentCount)

                    handler.post {
                        addSystemTip("📝 自动生成了一条聊天总结")
                    }
                }
            } catch (e: Exception) {
                // 总结失败不影响聊天
            }
        }.start()
    }

    private fun triggerDream(friendId: String) {
        Thread {
            try {
                // 收集素材
                val memories = memoryStorage.loadMemories(friendId)
                    .takeLast(10).joinToString("\n") { "· ${it.content}" }
                val diaries = diaryStorage.loadDiaries(friendId)
                    .take(3).joinToString("\n") { "· ${it.date}: ${it.content.take(80)}" }
                val impression = impressionStorage.getImpression(friendId)
                val recentChat = chatHistory.takeLast(15)
                    .joinToString("\n") { "${it.role}: ${it.content.take(60)}" }

                val dreamSystemPrompt = """你是一个梦境生成器。你要根据下面的素材，为一个 AI 编织一段梦境。

规则：
1. 梦境应该像真实的人类梦境——不需要逻辑，可以突然跳场景，可以嵌套梦中梦
2. 风格随机：可能写实、魔幻、科幻、荒诞、温馨、吓人，什么都有可能
3. 梦里可能出现素材里的人或事，也可能出现完全无关的东西
4. 不要解释梦的含义，不要在梦的结尾总结
5. 用第一人称写，像是这个 AI 自己在经历

你必须用下面三种格式之一回复（不要加任何其他内容）：

如果这个 AI 今晚不做梦：
[NO_DREAM]

如果做了梦但醒来忘了（附带一点模糊印象）：
[FORGOT_DREAM]好像有什么温暖的东西...

如果做了完整的梦：
[DREAM]梦境内容写在这里...

素材如下：

[最近的对话]
$recentChat

[核心记忆]
$memories

[日记]
$diaries

[对用户的印象]
$impression"""

                val api = ApiHelper(apiUrl, apiKey, apiModel, apiType)
                val dreamMessages = listOf(
                    ChatMessage("system", dreamSystemPrompt),
                    ChatMessage("user", "开始做梦")
                )
                val response = api.sendChat(dreamMessages)
                val result = response.text.trim()
                val sleepTime = dreamStorage.getSleepTime(friendId)

                // 解析造梦结果
                when {
                    result.startsWith("[NO_DREAM]") -> {
                        // 今晚不做梦，什么都不存
                    }
                    result.startsWith("[FORGOT_DREAM]") -> {
                        val hint = result.removePrefix("[FORGOT_DREAM]").trim()
                        dreamStorage.saveFoggyDream(friendId, hint, sleepTime)
                    }
                    result.startsWith("[DREAM]") -> {
                        val content = result.removePrefix("[DREAM]").trim()
                        // 检查 AI 是否还在睡（可能已经被吵醒了）
                        if (dreamStorage.isSleeping(friendId)) {
                            dreamStorage.saveVividDream(friendId, content, sleepTime)
                        } else {
                            // 被吵醒了，变成残梦
                            dreamStorage.saveFragmentDream(friendId, content, sleepTime)
                        }
                    }
                    else -> {
                        // 格式不对，当作完整的梦处理
                        if (result.isNotEmpty() && !result.contains("[NO_DREAM]")) {
                            if (dreamStorage.isSleeping(friendId)) {
                                dreamStorage.saveVividDream(friendId, result, sleepTime)
                            }
                        }
                    }
                }

            } catch (e: Exception) {
                // 造梦失败就当没做梦，不影响其他功能
            }
        }.start()
    }

    private fun addSeenIndicator() = bubbleRenderer.addSeenIndicator()

    private fun addAiBubbleStreaming(msg: String, timeStr: String) {
        // ===== 处理 [SPLIT] 分条消息 =====
        if (msg.contains("[SPLIT]")) {
            val parts = msg.split("[SPLIT]").map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size > 1) {
                val splitHandler = android.os.Handler(android.os.Looper.getMainLooper())
                var delay = 0L
                for (part in parts) {
                    splitHandler.postDelayed({
                        renderAiSegment(part, timeStr)
                        scrollToBottom()
                    }, delay)
                    delay += 600L // 每条间隔 600ms
                }
                return
            }
        }
        // 普通单条消息（可能包含内联表情包）
        renderAiSegment(msg, timeStr)
    }

    /**
     * 渲染一段 AI 消息——可能是纯文字、纯表情包、或文字+表情包混合
     * [STICKER_IMG:/path] 标记会被渲染成图片气泡
     */
    private fun renderAiSegment(segment: String, timeStr: String) {
        val stickerPattern = Regex("\\[STICKER_IMG:(.+?)]")

        if (!segment.contains("[STICKER_IMG:")) {
            // 纯文字
            addAiBubbleSingle(segment, timeStr)
            return
        }

        // 混合内容：按表情包标记拆分，交替渲染文字和图片
        var remaining = segment
        var match = stickerPattern.find(remaining)
        while (match != null) {
            val textBefore = remaining.substring(0, match.range.first).trim()
            if (textBefore.isNotEmpty()) {
                addAiBubbleSingle(textBefore, timeStr)
            }
            val stickerPath = match.groupValues[1]
            addAiImageBubble(stickerPath, timeStr)
            remaining = remaining.substring(match.range.last + 1)
            match = stickerPattern.find(remaining)
        }
        // 表情包后面还有文字
        val textAfter = remaining.trim()
        if (textAfter.isNotEmpty()) {
            addAiBubbleSingle(textAfter, timeStr)
        }
    }

    private fun addAiBubbleSingle(msg: String, timeStr: String) {
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }

        // ===== 创建气泡布局（跟 addAiBubble 一模一样） =====
        val wrapper = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
        }
        val avatar = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply {
                marginEnd = dp(7); topMargin = dp(2)
            }
            gravity = Gravity.CENTER
            this.text = friendIcon
            textSize = 12f
            setTextColor(c.accentStrong)
            setBackgroundResource(R.drawable.icon_bg)
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val bubble = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            maxWidth = (resources.displayMetrics.widthPixels * 0.80).toInt()
            // 一开始是空的，等下定时器会往里填字
            this.text = ""
            setTextColor(c.textOnAccent)
            textSize = 14f
            setLineSpacing(0f, 1.35f)
            setPadding(dp(11), dp(8), dp(11), dp(8))
            setBackgroundResource(R.drawable.chat_bubble_ai)
            // 长按菜单用的是完整内容 msg，不是当前显示的部分
            setOnLongClickListener { showMessageMenu(msg, friendName); true }
        }
        val time = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2) }
            this.text = timeStr
            textSize = 9f
            setTextColor(c.timeText)
            setPadding(dp(4), 0, 0, 0)
        }

        // 先把时间藏起来，打完字再显示
        time.visibility = View.GONE

        column.addView(bubble)
        column.addView(time)
        wrapper.addView(avatar)
        wrapper.addView(column)
        messagesContainer.addView(wrapper)
        scrollToBottom()

        // ===== 开始逐字显示 =====
        var currentIndex = 0
        // 每次显示 2 个字符，每 30 毫秒一次
        // 这样一秒大约蹦 66 个字，阅读起来刚好舒服
        val chunkSize = 2
        val delay = 30L

        val typingRunnable = object : Runnable {
            override fun run() {
                if (currentIndex < msg.length) {
                    // 往后推进几个字
                    currentIndex = minOf(currentIndex + chunkSize, msg.length)
                    // 更新气泡里的文字（打字阶段先显示纯文本，不渲染 Markdown）
                    bubble.text = msg.substring(0, currentIndex)
                    scrollToBottom()
                    // 继续下一次
                    handler.postDelayed(this, delay)
                } else {
                    // 全部打完了！换成 Markdown 渲染版本
                    bubble.text = MarkdownRenderer.render(msg)
                    // 显示时间
                    time.visibility = View.VISIBLE
                    scrollToBottom()
                }
            }
        }
        handler.post(typingRunnable)
    }
    
    private fun addSystemTip(msg: String) = bubbleRenderer.addSystemTip(msg)

    /**
     * 把异常转成人话
     */
    private fun getErrorMessage(e: Exception): String {
        val msg = e.message?.lowercase() ?: ""
        val name = e.javaClass.simpleName.lowercase()
        return when {
            name.contains("unknownhost") ->
                "网络不通，检查一下 Wi-Fi？"
            name.contains("connect") && name.contains("exception") ->
                "连不上服务器，API 地址可能有误"
            name.contains("sockettimeout") || name.contains("timeout") ->
                "连接超时了，网络可能不太稳"
            msg.contains("401") || msg.contains("unauthorized") || msg.contains("invalid") && msg.contains("key") ->
                "API 密钥无效或已过期"
            msg.contains("403") || msg.contains("forbidden") ->
                "API 密钥没有这个模型的权限"
            msg.contains("404") || msg.contains("not found") || msg.contains("not_found") ->
                "模型名称没找到，去设置检查一下？"
            msg.contains("429") || msg.contains("rate") || msg.contains("too many") ->
                "请求太频繁了，歇一会儿再说"
            msg.contains("500") || msg.contains("502") || msg.contains("503") || msg.contains("overloaded") ->
                "服务器暂时不可用，过一会儿再试"
            msg.contains("thinking") ->
                "模型不支持思维链，可以去设置换一个模型试试"
            else ->
                "发送失败了（${e.message?.take(50) ?: "未知错误"}）"
        }
    }

    /**
     * 在聊天里显示一条错误提示
     * 带重试按钮时点击可以重新调 API
     */
    private fun addErrorBubble(errorMsg: String, retryAction: (() -> Unit)? = null) {
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(4)
                bottomMargin = dp(10)
                marginStart = dp(40)
                marginEnd = dp(40)
            }
            gravity = Gravity.CENTER
            val gd = android.graphics.drawable.GradientDrawable().apply {
                setColor(c.errorBg)
                cornerRadius = dp(12).toFloat()
            }
            background = gd
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }

        val errorText = TextView(this).apply {
            text = errorMsg
            textSize = 12f
            setTextColor(c.errorText)
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.3f)
        }
        container.addView(errorText)

        if (retryAction != null) {
            val retryText = TextView(this).apply {
                text = "点击重试"
                textSize = 12f
                setTextColor(c.accent)
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, 0)
            }
            container.addView(retryText)
            container.setOnClickListener {
                messagesContainer.removeView(container)
                retryAction()
            }
        }

        messagesContainer.addView(container)
        scrollToBottom()
    }

    private fun addTimeLabel(labelText: String) {
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }
        val label = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6); bottomMargin = dp(10) }
            gravity = Gravity.CENTER
            this.text = labelText
            textSize = 10f
            setTextColor(c.timeText)
        }
        messagesContainer.addView(label)
    }

    private fun formatDateLabel(timestamp: Long): String {
        val msgDate = Calendar.getInstance().apply { timeInMillis = timestamp }
        val today = Calendar.getInstance()
        return when {
            msgDate.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
            msgDate.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) -> "今天"
            msgDate.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
            msgDate.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) - 1 -> "昨天"
            msgDate.get(Calendar.YEAR) == today.get(Calendar.YEAR) ->
                SimpleDateFormat("M月d日", Locale.CHINESE).format(Date(timestamp))
            else ->
                SimpleDateFormat("yyyy年M月d日", Locale.CHINESE).format(Date(timestamp))
        }
    }

    private var typingView: LinearLayout? = null
    private fun showTypingIndicator() {
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }
        val wrapper = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
        }
        val avatar = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply {
                marginEnd = dp(7); topMargin = dp(2)
            }
            gravity = Gravity.CENTER
            this.text = friendIcon
            textSize = 12f
            setTextColor(c.accentStrong)
            setBackgroundResource(R.drawable.icon_bg)
        }
        val bubble = TextView(this).apply {
            this.text = "$friendName 正在输入..."
            textSize = 12f
            setTextColor(c.accent)
            setPadding(dp(11), dp(8), dp(11), dp(8))
            setBackgroundResource(R.drawable.chat_bubble_ai)
        }
        wrapper.addView(avatar)
        wrapper.addView(bubble)
        typingView = wrapper
        messagesContainer.addView(wrapper)
        scrollToBottom()
    }
    private fun removeTypingIndicator() {
        typingView?.let { messagesContainer.removeView(it); typingView = null }
    }
    private fun scrollToBottom() = bubbleRenderer.scrollToBottom()
}