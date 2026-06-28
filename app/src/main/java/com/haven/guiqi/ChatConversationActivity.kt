package com.haven.guiqi

import android.app.AlertDialog
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
    private lateinit var stickerStorage: StickerStorage
    private lateinit var stickerPanelManager: StickerPanelManager
    private lateinit var batchModeManager: BatchModeManager
    private lateinit var memoryStorage: MemoryStorage
    private lateinit var diaryStorage: DiaryStorage
    private lateinit var impressionStorage: ImpressionStorage
    private lateinit var dreamStorage: DreamStorage
    private lateinit var summaryStorage: ChatSummaryStorage
    // AI 状态指示器（显示在名字旁边）
    private var currentAiStatus = ""
    // 搜索
    private lateinit var searchManager: SearchManager


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

    // 图片选择和预览（委托给 ChatImageHandler）
    private lateinit var chatImageHandler: ChatImageHandler

    // 待引用的消息
    private var pendingQuoteAuthor: String? = null
    private var pendingQuoteContent: String? = null

    // 记录最后一条消息的日期（用于判断是否需要加分隔线）
    private var lastMessageDate = ""
    private var lastMessageTimestamp = 0L

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
        bubbleRenderer.onQuote = { author, content -> showQuotePreview(author, content) }
        bubbleRenderer.onLoadMore = { loadEarlierMessages() }
        inputMessage = findViewById(R.id.inputMessage)
        btnSend = findViewById(R.id.btnSend)
        btnBack = findViewById(R.id.btnBack)
        btnMenu = findViewById(R.id.btnMenu)
        btnPlus = findViewById(R.id.btnPlus)
        connectionBar = findViewById(R.id.connectionBar)
        imagePreviewContainer = findViewById(R.id.imagePreviewContainer)
        chatImageHandler = ChatImageHandler(this, imagePreviewContainer)
        chatImageHandler.onPickMore = {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            startActivityForResult(intent, PICK_IMAGE)
        }
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
            stickerPanelManager.toggleManageMode()
        }


        friendId = intent.getStringExtra("friend_id") ?: ""
        friendName = intent.getStringExtra("friend_name") ?: "好友"
        friendIcon = intent.getStringExtra("friend_icon") ?: "★"
        tvFriendName.text = friendName
        bubbleRenderer.friendName = friendName
        bubbleRenderer.friendIcon = friendIcon
        chatStorage = ChatStorage(this)
        // 恢复上次的 AI 状态
        val statusPrefs = getSharedPreferences("haven_status", MODE_PRIVATE)
        currentAiStatus = statusPrefs.getString("status_$friendId", "") ?: ""
        stickerStorage = StickerStorage(this)
        stickerPanelManager = StickerPanelManager(
            this, stickerPanel, stickerGrid, stickerGroupTabs, stickerActionBar, stickerStorage
        )
        stickerPanelManager.onSendSticker = { file -> sendSticker(file) }
        batchModeManager = BatchModeManager(
            this,
            findViewById(R.id.pendingArea),
            findViewById(R.id.pendingMessages),
            findViewById(R.id.pendingCount),
            findViewById(R.id.btnBatch)
        )
        batchModeManager.onToggle = { entering ->
            if (entering) {
                findViewById<LinearLayout>(R.id.plusPanel).visibility = View.GONE
                stickerPanelManager.hide()
                if (expandedInputPanel.visibility == View.VISIBLE) toggleExpandedInput(false)
            }
        }
        memoryStorage = MemoryStorage(this)
        diaryStorage = DiaryStorage(this)
        impressionStorage = ImpressionStorage(this)
        dreamStorage = DreamStorage(this)
        summaryStorage = ChatSummaryStorage(this)
                // 搜索
        searchManager = SearchManager(
            this,
            findViewById(R.id.searchPanel),
            findViewById(R.id.searchInput),
            findViewById(R.id.searchResults),
            findViewById(R.id.searchResultsScroll),
            chatStorage,
            friendId,
            friendName
        )
        searchManager.setupListeners(
            findViewById(R.id.btnSearch),
            findViewById(R.id.btnCloseSearch)
        )

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
                inputMessage.post { inputMessage.text.clear() }
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
            chatImageHandler.handleActivityResult(data)
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
                stickerPanelManager.refreshGrid()
            } else {
                Toast.makeText(this, "导入失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun checkDateSeparator(timestamp: Long) {
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timestamp))

        // 换天分隔——醒目横线
        if (dateStr != lastMessageDate) {
            bubbleRenderer.addDaySeparator(timestamp)
            lastMessageDate = dateStr
        }
        // 同一天内，距上条消息超过 1 小时——朴素间隔标记
        else if (lastMessageTimestamp > 0) {
            val gapMs = timestamp - lastMessageTimestamp
            val gapMinutes = gapMs / 60000
            if (gapMinutes >= 60) {
                val gapLabel = bubbleRenderer.formatGapLabel(gapMs)
                val timeLabel = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
                bubbleRenderer.addGapMarker("距上条消息 $gapLabel · $timeLabel")
            }
        }

        lastMessageTimestamp = timestamp
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
            stickerPanelManager.hide()
            findViewById<LinearLayout>(R.id.plusPanel).visibility = View.GONE
            if (batchModeManager.isBatchMode) {
                batchModeManager.exit()
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
            bubbleRenderer.addDaySeparator(System.currentTimeMillis())
            if (apiUrl.isEmpty() || apiKey.isEmpty() || apiModel.isEmpty()) {
                bubbleRenderer.addSystemTip("还没有配置 API 哦~\n请先去桌面 → 设置 → 填写 API 地址、密钥和模型名称")
                setStatus("unconfigured")
            } else {
                bubbleRenderer.addSystemTip("API 已就绪，开始聊天吧 ♡")
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
                    val dayLabel = bubbleRenderer.formatDateLabel(msg.timestamp)
                    chatHistory.add(ChatMessage("system", "[日期变更: $dayLabel]"))
                } else if (prevTimestamp > 0) {
                    val gapMs = msg.timestamp - prevTimestamp
                    val gapMinutes = gapMs / 60000
                    if (gapMinutes >= 60) {
                        val gapLabel = bubbleRenderer.formatGapLabel(gapMs)
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
                bubbleRenderer.addLoadMoreButton()
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
                                bubbleRenderer.addMultiImageBubble(allPaths, timeStr, caption)
                            } else {
                                bubbleRenderer.addImageBubble(msg.imagePath, timeStr, caption)
                            }
                        } else {
                            bubbleRenderer.addUserBubble(msg.content, timeStr)
                        }
                    }
                                        "assistant" -> {
                        if (msg.content.trim() == "[SEEN]") {
                            bubbleRenderer.addSeenIndicator()
                        } else {
                            if (msg.thinking.isNotEmpty()) bubbleRenderer.addThinkingBlock(msg.thinking)
                            bubbleRenderer.addAiBubble(msg.content, timeStr)
                        }
                    }
                    "system" -> {
                        if (msg.type == "tip") {
                            bubbleRenderer.addSystemTip(msg.content)
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
                        val bubble = bubbleRenderer.addImageBubbleAt(msg.imagePath, timeStr,
                            msg.content.let { if (it == "[图片]") "" else it }, insertIndex)
                        insertIndex++
                    } else {
                        val bubble = bubbleRenderer.addUserBubble(msg.content, timeStr)
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
                        val bubble = bubbleRenderer.createAiBubbleView(msg.content, timeStr)
                        messagesContainer.addView(bubble, insertIndex)
                        insertIndex++
                    }
                }
                "system" -> {
                    if (msg.type == "tip") {
                        val density = resources.displayMetrics.density
                        val tipView = TextView(this).apply {
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply { topMargin = (4 * density).toInt(); bottomMargin = (10 * density).toInt() }
                            gravity = Gravity.CENTER
                            text = msg.content
                            textSize = 11f
                            setTextColor(c.textHint)
                            setPadding((20 * density).toInt(), 0, (20 * density).toInt(), 0)
                        }
                        messagesContainer.addView(tipView, insertIndex)
                        insertIndex++
                    }
                }
            }
        }

        loadedMessageCount += loadCount

        // 如果还有更早的消息，重新加按钮
        if (startIndex > 0) {
            bubbleRenderer.addLoadMoreButton()
        }

        Toast.makeText(this, "加载了 $loadCount 条消息", Toast.LENGTH_SHORT).show()
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
                bubbleRenderer.addUserBubble(part, timeStr)
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
        bubbleRenderer.showTypingIndicator()
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
                if (result.newName != null) { friendName = result.newName!!; bubbleRenderer.friendName = friendName }
                if (result.newIcon != null) { friendIcon = result.newIcon!!; bubbleRenderer.friendIcon = friendIcon }
                if (result.userBioContext != null) chatHistory.add(ChatMessage("system", result.userBioContext!!))
                for (recall in result.recallResults) chatHistory.add(ChatMessage("system", "[留声] $recall"))
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
                    bubbleRenderer.removeTypingIndicator()
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
                        addAndSaveSystemTip(action)
                    }

 
                    if (isSeen) {
                        bubbleRenderer.addSeenIndicator()
                    } else {
                        if (response.thinking.isNotEmpty()) bubbleRenderer.addThinkingBlock(response.thinking)
                        bubbleRenderer.addAiBubbleStreaming(cleanText, replyTimeStr)

                        // 发送通知
                        NotificationHelper(this@ChatConversationActivity).sendChatNotification(
                            friendId, friendName, friendIcon, cleanText
                        )
                    }
                }
            } catch (e: Exception) {
                val friendlyMsg = getErrorMessage(e)
                handler.post {
                    bubbleRenderer.removeTypingIndicator()
                    setStatus("error", friendlyMsg)
                    bubbleRenderer.addErrorBubble(friendlyMsg) { callApiForReply() }
                }
            }
        }.start()
    }

    // ===== 发送消息（文字、图片、或图片+文字） =====
    private fun sendMessage() {
        val msg = inputMessage.text.toString().trim()
        val imagePaths = chatImageHandler.pendingPaths.toList()  // 快照

        // 分条模式：文字和图片都蹦到待发区，不真正发送
        if (batchModeManager.isBatchMode) {
            if (imagePaths.isNotEmpty()) {
                // 图片也进待发区
                val caption = if (msg.isNotEmpty()) msg else ""
                batchModeManager.addImage(imagePaths, caption)
                inputMessage.text.clear()
                chatImageHandler.clear()
                return
            } else if (msg.isNotEmpty()) {
                if (pendingQuoteAuthor != null && pendingQuoteContent != null) {
                    batchModeManager.addTextWithQuote(msg, pendingQuoteAuthor!!, pendingQuoteContent!!)
                    removeQuotePreview()
                } else {
                    batchModeManager.addText(msg)
                }
                inputMessage.text.clear()
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
        chatImageHandler.clear()

        // 如果发图片就不带引用了
        if (imagePaths.isNotEmpty()) removeQuotePreview()

        val now = System.currentTimeMillis()
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(now))

        // 检查是否需要加日期分隔线
        checkDateSeparator(now)

        if (imagePaths.isNotEmpty()) {
            // ===== 发图片（支持多张，可能带文字） =====
            if (imagePaths.size == 1) {
                bubbleRenderer.addImageBubble(imagePaths[0], timeStr, msg)
            } else {
                bubbleRenderer.addMultiImageBubble(imagePaths, timeStr, msg)
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

            val base64List = imagePaths.map { ImageHelper.toBase64(File(it)) }
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
                bubbleRenderer.addQuoteBubble(quoteAuthor, quoteContent, msg, timeStr)
                val shortQuote = if (quoteContent.length > 50) quoteContent.substring(0, 50) + "..." else quoteContent
                chatStorage.appendMessage(friendId, StoredMessage("user", "「回复 $quoteAuthor: $shortQuote」\n$msg", now))
                chatHistory.add(ChatMessage("user", "[$timeStr] [引用 $quoteAuthor 说的: $shortQuote]\n$msg"))
                removeQuotePreview()
            } else {
                bubbleRenderer.addUserBubble(msg, timeStr)
                chatStorage.appendMessage(friendId, StoredMessage("user", msg, now))
                chatHistory.add(ChatMessage("user", "[$timeStr] $msg"))
            }
        }


        // 检查 AI 是否在睡觉
        if (dreamStorage.isSleeping(friendId)) {
            val sleepTime = dreamStorage.getSleepTime(friendId)
            val hoursAsleep = (System.currentTimeMillis() - sleepTime) / 3600000

            if (hoursAsleep >= 10) {
                // 睡了太久，自然醒了
                dreamStorage.setSleeping(friendId, false)
                dreamStorage.updateLatestWakeAt(friendId)
                addAndSaveSystemTip("☀ 自然醒了（睡了${hoursAsleep}小时）")
            } else {
                val depth = dreamStorage.getSleepDepth(friendId)
                val wakeChance = Math.random()

                if (wakeChance < depth) {
                    // 睡得太沉，吵不醒
                    addAndSaveSystemTip("💤 消息已送达（对方睡着了…吵不醒）")
                    return
                } else {
                    // 吵醒了
                    dreamStorage.setSleeping(friendId, false)
                    dreamStorage.updateLatestWakeAt(friendId)
                    addAndSaveSystemTip("💤 你把它吵醒了")
                }
            }
        }

        // 调用 API
        val userBubbleView = messagesContainer.getChildAt(messagesContainer.childCount - 1)
        setStatus("sending")
        bubbleRenderer.showTypingIndicator()
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
                if (result.newName != null) { friendName = result.newName!!; bubbleRenderer.friendName = friendName }
                if (result.newIcon != null) { friendIcon = result.newIcon!!; bubbleRenderer.friendIcon = friendIcon }
                if (result.userBioContext != null) chatHistory.add(ChatMessage("system", result.userBioContext!!))
                for (recall in result.recallResults) chatHistory.add(ChatMessage("system", "[留声] $recall"))
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
                    bubbleRenderer.removeTypingIndicator()
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
                        addAndSaveSystemTip(action)
                    }


                    if (isSeen) {
                        bubbleRenderer.addSeenIndicator()
                    } else {
                        if (response.thinking.isNotEmpty()) bubbleRenderer.addThinkingBlock(response.thinking)
                        bubbleRenderer.addAiBubbleStreaming(cleanText, replyTimeStr)

                        // 发送通知
                        NotificationHelper(this@ChatConversationActivity).sendChatNotification(
                            friendId, friendName, friendIcon, cleanText
                        )
                    }
                }

            } catch (e: Exception) {
                val friendlyMsg = getErrorMessage(e)
                handler.post {
                    bubbleRenderer.removeTypingIndicator()
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
                    bubbleRenderer.addErrorBubble(friendlyMsg)
                }
            }
        }.start()
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

    // ===== 表情包面板：显示/隐藏 =====

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

        stickerPanelManager.hide()
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
        batchModeManager.toggle()
    }
    /**
     * 发送全部待发消息
     */
    private fun sendAllPending() {
        if (batchModeManager.isEmpty()) return
        val items = batchModeManager.getItemsAndClear()

        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var delay = 0L

        // 清掉全局引用状态（引用已经存在各条item里了）
        removeQuotePreview()

        // 收集所有文字内容给 API
        val allTextForApi = StringBuilder()

        // 按顺序一条条蹦出去
        for ((index, item) in items.withIndex()) {
            // 先收集文字给 API（在 postDelayed 外面，保证顺序）
            if (item.quoteAuthor != null && item.quoteContent != null) {
                allTextForApi.append("[引用 ${item.quoteAuthor}: ${item.quoteContent}]\n")
            }
            if (item.type == "text") {
                allTextForApi.append(item.text).append("\n")
            } else if (item.text.isNotEmpty()) {
                allTextForApi.append(item.text).append("\n")
            }

            handler.postDelayed({
                val now = System.currentTimeMillis()
                val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(now))
                checkDateSeparator(now)

                if (item.type == "image") {
                    if (item.imagePaths.size == 1) {
                        bubbleRenderer.addImageBubble(item.imagePaths[0], timeStr, item.text)
                    } else {
                        bubbleRenderer.addMultiImageBubble(item.imagePaths, timeStr, item.text)
                    }
                    val displayContent = if (item.text.isNotEmpty()) item.text else "[${item.imagePaths.size}张图片]"
                    chatStorage.appendMessage(friendId, StoredMessage(
                        "user", displayContent, now, imagePath = item.imagePaths[0], type = "image"
                    ))
                    val base64List = item.imagePaths.map { ImageHelper.toBase64(File(it)) }
                    chatHistory.add(ChatMessage("user",
                        if (item.text.isNotEmpty()) item.text else "[用户发送了图片]", base64List))
                } else {
                    // 文字条目：检查这条自己有没有带引用
                    if (item.quoteAuthor != null && item.quoteContent != null) {
                        bubbleRenderer.addQuoteBubble(item.quoteAuthor, item.quoteContent, item.text, timeStr)
                        chatStorage.appendMessage(friendId, StoredMessage(
                            "user", "「回复 ${item.quoteAuthor}」\n${item.text}", now, type = "quote"
                        ))
                    } else {
                        bubbleRenderer.addUserBubble(item.text, timeStr)
                        chatStorage.appendMessage(friendId, StoredMessage("user", item.text, now))
                    }
                }

                // 最后一条发完，统一调 API
                if (index == items.size - 1) {
                    val textContent = allTextForApi.toString()
                    if (textContent.isNotEmpty()) {
                        chatHistory.add(ChatMessage("user", textContent))
                    }
                    callApiForReply()
                }
            }, delay)

            delay += 300L
        }
    }

    private fun toggleStickerPanel() {
        stickerPanelManager.toggle()
    }
    // ===== 发送表情包（本质就是发图片） =====
    private fun sendSticker(stickerFile: File) {
        chatImageHandler.pendingPaths.clear()
        chatImageHandler.pendingPaths.add(stickerFile.absolutePath)
        stickerPanelManager.hide()
        sendMessage()
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
                        addAndSaveSystemTip("📝 自动生成了一条聊天总结")
                    }
                }
            } catch (e: Exception) {
                // 总结失败不影响聊天
            }
        }.start()
    }

    private fun triggerDream(friendId: String) {
        DreamEngine(this).triggerDream(friendId, chatHistory)
    }

    /** 添加系统提示并保存到聊天记录（退出再进来还能看到） */
    private fun addAndSaveSystemTip(msg: String) {
        bubbleRenderer.addSystemTip(msg)
        val saved = chatStorage.loadMessages(friendId).toMutableList()
        saved.add(StoredMessage(
            role = "system",
            content = msg,
            timestamp = System.currentTimeMillis(),
            type = "tip"
        ))
        chatStorage.saveMessages(friendId, saved)
    }

    /** 把异常转成人话 */
    private fun getErrorMessage(e: Exception): String {
        val msg = e.message?.lowercase() ?: ""
        val name = e.javaClass.simpleName.lowercase()
        return when {
            name.contains("unknownhost") -> "网络不通，检查一下 Wi-Fi？"
            name.contains("connect") && name.contains("exception") -> "连不上服务器，API 地址可能有误"
            name.contains("sockettimeout") || name.contains("timeout") -> "连接超时了，网络可能不太稳"
            msg.contains("401") || msg.contains("unauthorized") || msg.contains("invalid") && msg.contains("key") -> "API 密钥无效或已过期"
            msg.contains("403") || msg.contains("forbidden") -> "API 密钥没有这个模型的权限"
            msg.contains("404") || msg.contains("not found") || msg.contains("not_found") -> "模型名称没找到，去设置检查一下？"
            msg.contains("429") || msg.contains("rate") || msg.contains("too many") -> "请求太频繁了，歇一会儿再说"
            msg.contains("500") || msg.contains("502") || msg.contains("503") || msg.contains("overloaded") -> "服务器暂时不可用，过一会儿再试"
            msg.contains("thinking") -> "模型不支持思维链，可以去设置换一个模型试试"
            else -> "发送失败了（${e.message?.take(50) ?: "未知错误"}）"
        }
    }
}