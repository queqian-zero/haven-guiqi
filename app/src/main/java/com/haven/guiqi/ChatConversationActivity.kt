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

    private val handler = Handler(Looper.getMainLooper())
    private val PICK_IMAGE = 3001

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
        insetsController.isAppearanceLightStatusBars = false

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

        friendId = intent.getStringExtra("friend_id") ?: ""
        friendName = intent.getStringExtra("friend_name") ?: "好友"
        friendIcon = intent.getStringExtra("friend_icon") ?: "★"
        tvFriendName.text = friendName
        chatStorage = ChatStorage(this)
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

        btnPlus.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK).apply { type = "image/*" }
            startActivityForResult(intent, PICK_IMAGE)
        }

        // 展开模式
        findViewById<TextView>(R.id.btnExpand).setOnClickListener { toggleExpandedInput(true) }
        findViewById<TextView>(R.id.btnCollapse).setOnClickListener { toggleExpandedInput(false) }
        findViewById<TextView>(R.id.btnExpandSend).setOnClickListener {
            // 把展开框的内容搬到普通输入框，然后收起，然后发送
            val text = expandedInput.text.toString()
            toggleExpandedInput(false)
            inputMessage.setText(text)
            sendMessage()
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
            setBackgroundColor(0x15FFFFFF.toInt())
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
            setTextColor(0x80FFFFFF.toInt())
            setLineSpacing(0f, 1.3f)
        }

        val cancelBtn = TextView(this).apply {
            this.text = "✕"
            textSize = 16f
            setTextColor(0x4DFFFFFF.toInt())
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
                setTextColor(0xB3FFFFFF.toInt())
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
            setTextColor(0x1AFFFFFF.toInt())
            setPadding(0, 0, dp(4), 0)
        }
        column.addView(time)
        wrapper.addView(column)
        messagesContainer.addView(wrapper)
        scrollToBottom()
    }

    // ===== 检查是否需要加日期分隔线 =====
    private fun checkDateSeparator(timestamp: Long) {
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timestamp))
        if (dateStr != lastMessageDate) {
            addTimeLabel("—— ${formatDateLabel(timestamp)} ——")
            lastMessageDate = dateStr
        }
    }

    // ===== 更新连接状态 =====
    private fun setStatus(state: String) {
        when (state) {
            "online" -> {
                tvStatus.text = "在线"
                tvStatus.setTextColor(0x4DB3A0FF.toInt())
                connectionBar.visibility = View.GONE
            }
            "sending" -> {
                tvStatus.text = "发送中..."
                tvStatus.setTextColor(0x80FFCC66.toInt())
                connectionBar.visibility = View.GONE
            }
            "error" -> {
                tvStatus.text = "连接失败"
                tvStatus.setTextColor(0x80FF6B6B.toInt())
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
                tvStatus.setTextColor(0x4DFF6B6B.toInt())
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
        // 每次构建上下文时更新时间，让 AI 知道"现在"是什么时候
        val timeInfo = SimpleDateFormat("yyyy年M月d日 EEEE HH:mm", Locale.CHINESE).format(Date())
        val userName = getSharedPreferences("haven_prefs", MODE_PRIVATE)
            .getString("user_name", "") ?: ""
        val userInfo = if (userName.isNotEmpty()) "\n用户名称: $userName" else ""
        val freshSystemMsg = ChatMessage("system", "当前时间: $timeInfo$userInfo")

        val nonSystemMsgs = chatHistory.filter { it.role != "system" }
        val recentMsgs = if (nonSystemMsgs.size > maxContextMessages) {
            nonSystemMsgs.takeLast(maxContextMessages)
        } else { nonSystemMsgs }
        // 用最新的 system 消息替换旧的
        return listOf(freshSystemMsg) + recentMsgs
    }

    private fun initChat() {
        val timeInfo = SimpleDateFormat("yyyy年M月d日 EEEE HH:mm", Locale.CHINESE).format(Date())
        val userName = getSharedPreferences("haven_prefs", MODE_PRIVATE)
            .getString("user_name", "") ?: ""
        val userInfo = if (userName.isNotEmpty()) "\n用户名称: $userName" else ""
        chatHistory.add(ChatMessage("system", "当前时间: $timeInfo$userInfo"))

        val savedMessages = chatStorage.loadMessages(friendId)
        if (savedMessages.isEmpty()) {
            lastMessageDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            addTimeLabel("—— 今天 ——")
            if (apiUrl.isEmpty() || apiKey.isEmpty() || apiModel.isEmpty()) {
                addSystemTip("还没有配置 API 哦~\n请先去桌面 → 设置 → 填写 API 地址、密钥和模型名称")
                setStatus("unconfigured")
            } else {
                addSystemTip("API 已就绪，开始聊天吧 ♡")
                setStatus("online")
            }
        } else {
            for (msg in savedMessages) {
                // 用统一的日期检查方法
                checkDateSeparator(msg.timestamp)

                val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    .format(Date(msg.timestamp))
                when (msg.role) {
                    "user" -> {
                        if (msg.imagePath.isNotEmpty()) {
                            addImageBubble(msg.imagePath, timeStr, msg.content.let {
                                if (it == "[图片]") "" else it
                            })
                            chatHistory.add(ChatMessage("user", "[用户之前发送了一张图片]"))
                        } else {
                            addUserBubble(msg.content, timeStr)
                            chatHistory.add(ChatMessage(msg.role, msg.content))
                        }
                    }
                    "assistant" -> {
                        if (msg.thinking.isNotEmpty()) addThinkingBlock(msg.thinking)
                        addAiBubble(msg.content, timeStr)
                        chatHistory.add(ChatMessage(msg.role, msg.content))
                    }
                }
            }
            if (apiUrl.isEmpty() || apiKey.isEmpty() || apiModel.isEmpty()) {
                setStatus("unconfigured")
            } else {
                setStatus("online")
            }
        }
    }

    // ===== 发送消息（文字、图片、或图片+文字） =====
    private fun sendMessage() {
        val msg = inputMessage.text.toString().trim()
        val imagePath = pendingImagePath

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

                chatStorage.appendMessage(friendId, StoredMessage(
                    "assistant", response.text, replyTime, response.thinking
                ))
                chatHistory.add(ChatMessage("assistant", response.text))

                handler.post {
                    removeTypingIndicator()
                    setStatus("online")
                    if (response.thinking.isNotEmpty()) addThinkingBlock(response.thinking)
                    addAiBubble(response.text, replyTimeStr)
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
            setBackgroundColor(0x10B3A0FF.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }

        // 左边紫色竖条
        val bar = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(3), LinearLayout.LayoutParams.MATCH_PARENT).apply {
                marginEnd = dp(8)
            }
            setBackgroundColor(0x66B3A0FF.toInt())
        }

        // 引用内容
        val textLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val authorView = TextView(this).apply {
            this.text = "回复 $author"
            textSize = 11f
            setTextColor(0x80B3A0FF.toInt())
        }

        // 截取前30个字符
        val shortContent = if (content.length > 30) content.substring(0, 30) + "..." else content
        val contentView = TextView(this).apply {
            this.text = shortContent
            textSize = 11f
            setTextColor(0x59FFFFFF.toInt())
            maxLines = 1
        }

        textLayout.addView(authorView)
        textLayout.addView(contentView)

        val cancelBtn = TextView(this).apply {
            this.text = "✕"
            textSize = 16f
            setTextColor(0x4DFFFFFF.toInt())
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
            setTextColor(0x66B3A0FF.toInt())
            textSize = 12f
            setLineSpacing(0f, 1.3f)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setBackgroundResource(R.drawable.chat_thinking_bg)
            visibility = View.GONE
        }
        val toggleView = TextView(this).apply {
            this.text = "💭 思考过程 ▸"
            textSize = 11f
            setTextColor(0x4DB3A0FF.toInt())
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
            setBackgroundColor(0x10B3A0FF.toInt())
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
            setBackgroundColor(0x66B3A0FF.toInt())
        }

        val quoteText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        quoteText.addView(TextView(this).apply {
            this.text = quoteAuthor
            textSize = 10f
            setTextColor(0x80B3A0FF.toInt())
        })

        val shortContent = if (quoteContent.length > 40) quoteContent.substring(0, 40) + "..." else quoteContent
        quoteText.addView(TextView(this).apply {
            this.text = shortContent
            textSize = 11f
            setTextColor(0x59FFFFFF.toInt())
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
            setTextColor(0xB3FFFFFF.toInt())
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
            setTextColor(0x1AFFFFFF.toInt())
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
            setTextColor(0xB3FFFFFF.toInt())
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
            setTextColor(0x1AFFFFFF.toInt())
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
            setTextColor(0x80B3A0FF.toInt())
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
            setTextColor(0xB3FFFFFF.toInt())
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
            setTextColor(0x1AFFFFFF.toInt())
            setPadding(dp(4), 0, 0, 0)
        }
        column.addView(bubble)
        column.addView(time)
        wrapper.addView(avatar)
        wrapper.addView(column)
        messagesContainer.addView(wrapper)
        scrollToBottom()
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
            setTextColor(0x33FFFFFF.toInt())
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
            setTextColor(0x1AFFFFFF.toInt())
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
            setTextColor(0x80B3A0FF.toInt())
            setBackgroundResource(R.drawable.icon_bg)
        }
        val bubble = TextView(this).apply {
            this.text = "$friendName 正在输入..."
            textSize = 12f
            setTextColor(0x4DB3A0FF.toInt())
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