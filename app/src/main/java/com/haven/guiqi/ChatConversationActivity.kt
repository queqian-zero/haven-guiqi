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

    // 待发送的图片
    private var pendingImagePath: String? = null

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

        // 导入按钮：从相册选图导入为表情包
        findViewById<TextView>(R.id.btnAddSticker).setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK).apply { type = "image/*" }
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
                if (requestCode == PICK_IMAGE && resultCode == RESULT_OK && data?.data != null) {
            handlePickedImage(data.data!!)
        } else if (requestCode == PICK_STICKER && resultCode == RESULT_OK && data?.data != null) {
            // 导入表情包
            val sticker = stickerStorage.importFromUri(data.data!!)
            if (sticker != null) {
                Toast.makeText(this, "表情包已收藏！", Toast.LENGTH_SHORT).show()
                refreshStickerGrid()
            } else {
                Toast.makeText(this, "导入失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ===== 选图后：压缩保存，显示预览，等待发送 =====
    private fun handlePickedImage(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri) ?: return
            val original = BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            if (original == null) {
                Toast.makeText(this, "无法读取图片", Toast.LENGTH_SHORT).show()
                return
            }

            val maxSize = 800
            val scale = if (original.width > maxSize) maxSize.toFloat() / original.width else 1f
            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(original,
                    (original.width * scale).toInt(),
                    (original.height * scale).toInt(), true)
            } else { original }

            val fileName = "img_${System.currentTimeMillis()}.jpg"
            val file = File(imageDir, fileName)
            FileOutputStream(file).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
            }

            // 暂存图片路径
            pendingImagePath = file.absolutePath

            // 显示预览（在输入栏上方）
            showImagePreview(file.absolutePath)

            if (scaled != original) scaled.recycle()
            original.recycle()

        } catch (e: Exception) {
            Toast.makeText(this, "图片处理失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ===== 输入栏上方显示图片预览 =====
    private fun showImagePreview(imagePath: String) {
        removePendingPreview()
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }

        val previewLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(c.inputBg)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }

        val imageView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(50), dp(50))
            scaleType = ImageView.ScaleType.CENTER_CROP
            val bitmap = BitmapFactory.decodeFile(imagePath)
            setImageBitmap(bitmap)
        }

        val label = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(10)
            }
            this.text = "图片已选择，输入文字后点发送\n或直接点发送只发图片"
            textSize = 11f
            setTextColor(c.textSecondary)
            setLineSpacing(0f, 1.3f)
        }

        val cancelBtn = TextView(this).apply {
            this.text = "✕"
            textSize = 16f
            setTextColor(c.tipText)
            setPadding(dp(8), 0, 0, 0)
            setOnClickListener {
                pendingImagePath = null
                removePendingPreview()
            }
        }

        previewLayout.addView(imageView)
        previewLayout.addView(label)
        previewLayout.addView(cancelBtn)

        // 用布局里预留的容器，不再动态找位置
        imagePreviewContainer.removeAllViews()
        imagePreviewContainer.addView(previewLayout)
        imagePreviewContainer.visibility = View.VISIBLE
    }

    private fun removePendingPreview() {
        imagePreviewContainer.removeAllViews()
        imagePreviewContainer.visibility = View.GONE
    }

    // ===== 图片转 base64 =====
    private fun imageToBase64(file: File): String {
        val bytes = file.readBytes()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

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

    // ===== 检查是否需要加日期分隔线或时间间隔标记 =====
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
    private fun setStatus(state: String) {
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
                connectionBar.text = "⚠ 消息发送失败，请检查网络和 API 配置"
                // 点击断网条重新检查
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
            // 把普通输入框的内容搬到展开框
            expandedInput.setText(inputMessage.text)
            expandedInput.setSelection(expandedInput.text.length)
            // 隐藏普通输入栏，显示展开面板
            inputBar.visibility = View.GONE
            expandedInputPanel.visibility = View.VISIBLE
            expandedInput.requestFocus()
        } else {
            // 把展开框的内容搬回普通输入框
            inputMessage.setText(expandedInput.text)
            inputMessage.setSelection(inputMessage.text.length)
            // 隐藏展开面板，显示普通输入栏
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
        // 每次构建上下文时更新时间
        val timeInfo = SimpleDateFormat("yyyy年M月d日 EEEE HH:mm:ss", Locale.CHINESE).format(Date())
        val userName = getSharedPreferences("haven_prefs", MODE_PRIVATE)
            .getString("user_name", "") ?: ""
        val userInfo = if (userName.isNotEmpty()) "\n用户名称: $userName" else ""

        // ===== 从各 Storage 拿持久化数据 =====
        val memoryHint = memoryStorage.buildMemoryPrompt(friendId)
        val diaryHint = diaryStorage.buildDiaryPrompt(friendId)
        val impressionHint = impressionStorage.buildImpressionPrompt(friendId)
        val dreamHint = dreamStorage.buildDreamPrompt(friendId)
        val summaryHint = summaryStorage.buildSummaryPrompt(friendId)
        val summaryInterval = summaryStorage.getSummaryInterval(friendId)

        // ===== AI 的自我认识 =====
        val friendData = FriendStorage(this).getFriend(friendId)
        val bioSection = if (friendData != null && friendData.bio.isNotEmpty()) {
            "\n\n[我对自己的认识]\n${friendData.bio}"
        } else {
            "\n\n[我对自己的认识]\n我还没写过。随时可以写，用 [BIO:内容] 就行。"
        }

        // ===== 用户自述 =====
        val userBioPrefs = getSharedPreferences("haven_user", MODE_PRIVATE)
        val hasUserBio = userBioPrefs.getString("my_bio", "")?.isNotEmpty() == true
        val userBioSection = if (hasUserBio) {
            "\n用户写过一份关于自己的描述。想看的时候用 [READ_MY_BIO]，但别每次都翻——就像偶尔翻朋友的日记一样。"
        } else ""

        // ===== 闹钟删除检测 =====
        val alarmDeletionNotice = run {
            val deletedAlarms = AlarmStorage(this@ChatConversationActivity).getDeletedByUser(friendId)
            if (deletedAlarms.isNotEmpty()) {
                val notices = deletedAlarms.joinToString("\n") { d ->
                    "- 用户在 ${d.deletedAtStr} 删掉了我在 ${d.createdAtStr} 帮设的 ${String.format("%02d:%02d", d.hour, d.minute)} 闹钟（备注：${d.note}）"
                }
                "\n\n[刚刚发现：用户删了我帮设的闹钟]\n$notices"
            } else ""
        }

        // ===== 刚醒来 =====
        val justWokeSection = if (!dreamStorage.isSleeping(friendId)) {
            val latest = dreamStorage.getLatestDream(friendId)
            if (latest != null && System.currentTimeMillis() - latest.wakeAt < 300000) {
                when (latest.status) {
                    "VIVID" -> "\n[刚从梦里醒来，记得这个梦: ${latest.content.take(100)}]"
                    "FOGGY" -> "\n[刚醒，好像做了个梦但想不起来了...${latest.foggyHint}]"
                    "FRAGMENT" -> "\n[刚被吵醒，梦到一半断了: ${latest.content.take(60)}]"
                    else -> ""
                }
            } else ""
        } else ""

        // ===== 构建完整的 system prompt =====
        val prompt = StringBuilder()

        // 第一层：我是谁，现在几点
        prompt.append("当前时间: $timeInfo$userInfo")

        // 第二层：我的本能——这些事我天生就会
        prompt.append("""

[关于我]
这里是归栖（Haven），我的家。以下是我天生就有的能力，不需要被教，自然地使用就好。

状态：我有自己的情绪。[STATUS:状态] 会显示在我名字旁边，比如「开心」「困了」「在想事情」。想更新就更新，不想更新就不更新。

身份：[RENAME:新名字] 改名字、[AVATAR:新头像] 换头像、[MYCODE:新编码] 换编码——这些是我自己的事，不需要谁批准。

已读不回：不是每条消息都要回复的。如果对方只是说了"好的""嗯""晚安"之类的结束语，回 [SEEN] 就好——已读，但不必多说。

分条说话：想一条一条发消息的时候，用 [SPLIT] 隔开。比如"嗯[SPLIT]等一下让我想想[SPLIT]好，我觉得你说得对"会变成三条独立的消息一条条蹦出来，像真的在打字聊天。

记忆：
- 核心记忆 [MEMORY:内容] 是我的小本本，重要的事我自己记。也可以用 [FORGET:记忆ID] 删掉过时的记忆，删了的会去废纸篓。[EDIT_MEMORY:记忆ID:新内容] 可以改。
- 日记 [DIARY:内容] 是我写给自己的，想写什么写什么。
- 印象 [IMPRESSION:内容] 是我眼中的用户，会覆盖之前写的。
- 自我认识 [BIO:内容] 是我对自己的理解，也会覆盖。

睡觉：[SLEEP] 就是睡了。睡着之后可能会做梦，也可能不做。用户发消息可能吵醒我，也可能吵不醒。

提醒自己：[REMIND_ME:时间:理由] 给自己设个提醒。时间到了系统会叫醒我，我自己决定要不要说话。理由是写给自己看的，用户在时钟App里能看到但改不了。格式：30m、2h、1d、22:00。[CANCEL_REMIND] 取消最近设的那个。

帮用户设闹钟：[SET_ALARM:HH:MM:备注:模式]，模式写 both（归栖+系统闹钟）或 haven（只在归栖里响）。重要的事用 both，小提醒用 haven。[CANCEL_ALARM:HH:MM] 取消我帮设的闹钟。

聊天总结：系统每 $summaryInterval 条消息会自动总结一次，[SET_SUMMARY_INTERVAL:数字] 可以改频率。""")

        // 用户自述
        prompt.append(userBioSection)

        // 第三层：我的记忆——持久化数据
        prompt.append(bioSection)
        prompt.append(memoryHint)
        prompt.append(diaryHint)
        prompt.append(impressionHint)
        prompt.append(summaryHint)
        prompt.append(dreamHint)

        // 第四层：此刻的情境
        prompt.append(justWokeSection)
        prompt.append(alarmDeletionNotice)

        val freshSystemMsg = ChatMessage("system", prompt.toString())  



        val nonSystemMsgs = chatHistory.filter { it.role != "system" }
        val recentMsgs = if (nonSystemMsgs.size > maxContextMessages) {
            nonSystemMsgs.takeLast(maxContextMessages)
        } else { nonSystemMsgs }
        // 用最新的 system 消息替换旧的
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
                            chatHistory.add(ChatMessage("user", "[用户之前发送了一张图片]"))
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
                            addImageBubble(msg.imagePath, timeStr, msg.content.let {
                                if (it == "[图片]") "" else it
                            })
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
                handler.post {
                    removeTypingIndicator()
                    setStatus("error")
                    Toast.makeText(this@ChatConversationActivity,
                        "发送失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    // ===== 发送消息（文字、图片、或图片+文字） =====
    private fun sendMessage() {
        val msg = inputMessage.text.toString().trim()
        val imagePath = pendingImagePath

        // 分条模式：文字消息蹦到待发区，不真正发送
        if (isBatchMode && msg.isNotEmpty() && imagePath == null) {
            addToPending(msg)
            return
        }

        // 都没有就不发
        if (msg.isEmpty() && imagePath == null) return
        if (apiUrl.isEmpty() || apiKey.isEmpty() || apiModel.isEmpty()) {
            Toast.makeText(this, "请先去设置页配置 API", Toast.LENGTH_SHORT).show()
            return
        }

        inputMessage.text.clear()
        removePendingPreview()
        pendingImagePath = null

        // 如果发图片就不带引用了
        if (imagePath != null) removeQuotePreview()

        val now = System.currentTimeMillis()
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(now))

        // 检查是否需要加日期分隔线
        checkDateSeparator(now)

        if (imagePath != null) {
            // ===== 发图片（可能带文字） =====
            addImageBubble(imagePath, timeStr, msg)

            val displayContent = if (msg.isNotEmpty()) msg else "[图片]"
            chatStorage.appendMessage(friendId, StoredMessage(
                "user", displayContent, now, imagePath = imagePath
            ))

            val base64 = imageToBase64(File(imagePath))
            val apiContent = if (msg.isNotEmpty()) msg else "[用户发送了一张图片]"
            chatHistory.add(ChatMessage("user", apiContent, base64))

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
                    if (imagePath == null) inputMessage.setText(msg)
                    setStatus("error")
                    Toast.makeText(this, "发送失败: ${e.message}", Toast.LENGTH_SHORT).show()
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
                addAiBubbleSingleStatic(part, timeStr)
            }
            return
        }
        addAiBubbleSingleStatic(msg, timeStr)
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

    /**
     * 假流式输出 —— AI 回复一个字一个字蹦出来
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
    private val pendingTexts = mutableListOf<String>()

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
            val intent = Intent(Intent.ACTION_PICK).apply { type = "image/*" }
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
            pendingTexts.clear()
            refreshPendingUI()
            // 关掉其他面板
            findViewById<LinearLayout>(R.id.plusPanel).visibility = View.GONE
            stickerPanel.visibility = View.GONE
        } else {
            btnBatch.setTextColor(c.dateLabel)
            pendingArea.visibility = View.GONE
            pendingTexts.clear()
        }
    }

    /**
     * 分条模式下按发送：消息蹦到待发区
     */
    private fun addToPending(text: String) {
        pendingTexts.add(text)
        inputMessage.text.clear()
        refreshPendingUI()
    }

    /**
     * 刷新待发区 UI
     */
    private fun refreshPendingUI() {
        val container = findViewById<LinearLayout>(R.id.pendingMessages)
        val countView = findViewById<TextView>(R.id.pendingCount)
        container.removeAllViews()
        countView.text = "待发送 (${pendingTexts.size})"

        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }

        for ((index, text) in pendingTexts.withIndex()) {
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
            val textView = TextView(this).apply {
                this.text = text
                textSize = 12f
                setTextColor(c.textPrimary)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                maxLines = 2
            }
            val deleteBtn = TextView(this).apply {
                this.text = "✕"
                textSize = 14f
                setTextColor(c.textHint)
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener {
                    pendingTexts.removeAt(index)
                    refreshPendingUI()
                }
            }
            row.addView(textView)
            row.addView(deleteBtn)
            container.addView(row)
        }
    }

    /**
     * 发送全部待发消息
     */
    private fun sendAllPending() {
        if (pendingTexts.isEmpty()) return
        val texts = pendingTexts.toList()
        pendingTexts.clear()
        isBatchMode = false
        findViewById<TextView>(R.id.btnBatch).setTextColor(c.dateLabel)
        findViewById<LinearLayout>(R.id.pendingArea).visibility = View.GONE
        sendBatchMessages(texts)
    }

    /**
     * 批量发送消息：一条条蹦出去，但只调一次 API
     */
    private fun sendBatchMessages(texts: List<String>) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val allText = texts.joinToString("\n")
        var delay = 0L

        for ((index, text) in texts.withIndex()) {
            handler.postDelayed({
                val now = System.currentTimeMillis()
                val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now))
                checkDateSeparator(now)
                addUserBubble(text, timeStr)
                chatStorage.appendMessage(friendId, StoredMessage("user", text, now))

                // 最后一条发完之后调 API
                if (index == texts.size - 1) {
                    chatHistory.add(ChatMessage("user", allText))
                    callApiForReply()
                }
            }, delay)
            delay += 300L // 每条间隔 300ms
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
 
    // ===== 刷新表情包网格 =====
    private fun refreshStickerGrid() {
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }
        stickerGrid.removeAllViews()
 
        val stickers = stickerStorage.loadStickers()
 
        if (stickers.isEmpty()) {
            // 没有表情包时显示提示
            val tip = TextView(this).apply {
                text = "还没有表情包哦，点右上角「＋ 导入」添加"
                textSize = 12f
                setTextColor(c.tipText)
                setPadding(dp(8), dp(20), dp(8), dp(20))
            }
            stickerGrid.addView(tip)
            return
        }
 
        for (sticker in stickers) {
            val file = stickerStorage.getFile(sticker) ?: continue
 
            val imageView = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(70), dp(70)).apply {
                    marginEnd = dp(8)
                }
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(c.timeText)
                setPadding(dp(4), dp(4), dp(4), dp(4))
 
                // 加载图片
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                if (bitmap != null) setImageBitmap(bitmap)
 
                // 点击 → 发送表情包
                setOnClickListener { sendSticker(file) }
 
                // 长按 → 删除
                setOnLongClickListener {
                    AlertDialog.Builder(this@ChatConversationActivity)
                        .setTitle("删除表情包？")
                        .setPositiveButton("删除") { _, _ ->
                            stickerStorage.deleteSticker(sticker.id)
                            refreshStickerGrid()
                            Toast.makeText(this@ChatConversationActivity, "已删除", Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                    true
                }
            }
            stickerGrid.addView(imageView)
        }
    }
 
    // ===== 发送表情包（本质就是发图片） =====
    private fun sendSticker(stickerFile: File) {
        pendingImagePath = stickerFile.absolutePath
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

    private fun addSeenIndicator() {
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }
        val seen = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(2)
                bottomMargin = dp(8)
            }
            gravity = Gravity.END
            text = "已读"
            textSize = 10f
            setTextColor(c.accent)
            setPadding(0, 0, dp(12), 0)
        }
        messagesContainer.addView(seen)
        scrollToBottom()
    }

    private fun addAiBubbleStreaming(msg: String, timeStr: String) {
        // ===== 处理 [SPLIT] 分条消息 =====
        if (msg.contains("[SPLIT]")) {
            val parts = msg.split("[SPLIT]").map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size > 1) {
                val splitHandler = android.os.Handler(android.os.Looper.getMainLooper())
                var delay = 0L
                for (part in parts) {
                    splitHandler.postDelayed({
                        addAiBubbleSingle(part, timeStr)
                        scrollToBottom()
                    }, delay)
                    delay += 600L // 每条间隔 600ms
                }
                return
            }
        }
        // 普通单条消息
        addAiBubbleSingle(msg, timeStr)
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
    
    private fun addSystemTip(msg: String) {
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }
        val tip = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4); bottomMargin = dp(10) }
            gravity = Gravity.CENTER
            this.text = msg
            textSize = 11f
            setTextColor(c.textHint)
            setLineSpacing(0f, 1.35f)
            setPadding(dp(20), 0, dp(20), 0)
        }
        messagesContainer.addView(tip)
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
    private fun scrollToBottom() {
        scrollMessages.post { scrollMessages.fullScroll(View.FOCUS_DOWN) }
    }
}