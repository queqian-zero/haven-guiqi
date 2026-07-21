package com.haven.guiqi

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
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
import java.io.File
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
    private val TAKE_PHOTO = 3003
    private val CAMERA_PERMISSION = 3004

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
    // 拍照临时文件
    private var pendingCameraFile: java.io.File? = null

    // 待引用的消息
    private var pendingQuoteAuthor: String? = null
    private var pendingQuoteContent: String? = null

    // 聊天历史加载器（initChat、loadEarlierMessages、日期分隔线）
    private lateinit var chatHistoryLoader: ChatHistoryLoader
    private lateinit var networkMonitor: NetworkMonitor

    // ===== 拆分出去的管理器（懒加载：首次使用时才构造，保证字段已就绪）=====
    private val plusMenuManager by lazy {
        PlusMenuManager(this, stickerPanelManager,
            onTakePhoto = { launchCamera() },
            onPickImage = {
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "image/*"; putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                startActivityForResult(intent, PICK_IMAGE)
            },
            onInsertWeather = { weatherCardManager.insert() })
    }
    private val weatherCardManager by lazy {
        WeatherCardManager(this, bubbleRenderer, chatStorage, friendId, chatHistory,
            batchModeManager, ::checkDateSeparator) { callApiForReply() }
    }
    private val badgeUnlockDialog by lazy {
        BadgeUnlockDialog(this, friendId) { addAndSaveSystemTip(it) }
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
            // ★ 键盘弹起时加底部 padding，让输入框不被盖住
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val navBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val bottom = maxOf(imeBottom, navBottom)
            view.setPadding(0, top, 0, bottom)
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
        bubbleRenderer.onLoadMore = { chatHistoryLoader.loadEarlierMessages() }
        inputMessage = findViewById(R.id.inputMessage)
        btnSend = findViewById(R.id.btnSend)
        btnBack = findViewById(R.id.btnBack)
        btnMenu = findViewById(R.id.btnMenu)
        btnPlus = findViewById(R.id.btnPlus)
        connectionBar = findViewById(R.id.connectionBar)
        imagePreviewContainer = findViewById(R.id.imagePreviewContainer)
        chatImageHandler = ChatImageHandler(this, imagePreviewContainer)
        networkMonitor = NetworkMonitor(this, findViewById(R.id.networkBanner))
        networkMonitor.start()
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
        val friend = FriendStorage(this).getFriend(friendId)
        val avatarPath = friend?.avatarPath ?: ""
        tvFriendName.text = friendName
        bubbleRenderer.friendName = friendName
        bubbleRenderer.friendIcon = friendIcon
        bubbleRenderer.friendAvatarPath = avatarPath
        chatStorage = ChatStorage(this)
        chatHistoryLoader = ChatHistoryLoader(
            this, chatStorage, bubbleRenderer, chatHistory, messagesContainer, friendId
        )
        chatHistoryLoader.onSetStatus = { state -> setStatus(state) }
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
            findViewById(R.id.btnCloseSearch),
            findViewById(R.id.btnDatePicker)
        )

        loadApiConfig()

        btnBack.setOnClickListener { finish() }
        btnMenu.setOnClickListener {
            val intent = Intent(this, ChatSettingsActivity::class.java)
            intent.putExtra("friend_id", friendId)
            intent.putExtra("friend_name", friendName)
            startActivityForResult(intent, 4001)
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

        btnPlus.setOnClickListener { plusMenuManager.toggle() }

        // 分条模式按钮
        findViewById<TextView>(R.id.btnBatch).setOnClickListener { batchModeManager.toggle() }

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

            // 用 --- 分条
            val parts = text.split(Regex("-{3,}")).map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size <= 1) {
                inputMessage.setText(text)
                sendMessage()
            } else {
                sendMultiMessages(parts)
            }
        }

        initChat()
    }

    // 离开页面时记下消息条数；回来时如果对不上（比如 TA 在你不在时主动说话了），自动刷新
    private var pausedMessageCount = -1

    override fun onResume() {
        super.onResume()
        loadApiConfig()

        // ★ 从 FriendStorage 刷新名字/头像/头像图片
        //   AI 在后台（独处/提醒）可能通过指令改了名字或头像，
        //   而 onCreate 时用的是 intent 带进来的旧值，这里同步一下。
        val latestFriend = FriendStorage(this).getFriend(friendId)
        if (latestFriend != null) {
            friendName = latestFriend.name
            friendIcon = latestFriend.icon
            tvFriendName.text = friendName
            bubbleRenderer.friendName = friendName
            bubbleRenderer.friendIcon = friendIcon
            bubbleRenderer.friendAvatarPath = latestFriend.avatarPath
        }

        if (pausedMessageCount >= 0 && chatStorage.getMessageCount(friendId) != pausedMessageCount) {
            messagesContainer.removeAllViews()
            chatHistory.clear()
            initChat()
        }
        pausedMessageCount = -1
    }

    override fun onPause() {
        super.onPause()
        pausedMessageCount = chatStorage.getMessageCount(friendId)
    }

    override fun onDestroy() {
        super.onDestroy()
        networkMonitor.stop()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 4001 && resultCode == RESULT_OK) {
            bubbleRenderer.friendAvatarPath = FriendStorage(this).getFriend(friendId)?.avatarPath ?: ""
            messagesContainer.removeAllViews(); chatHistory.clear(); initChat(); return
        }
        if (requestCode == TAKE_PHOTO && resultCode == RESULT_OK) {
            // 拍照成功：把照片压缩存入 chat_images，交给预览流程
            val file = pendingCameraFile ?: return
            pendingCameraFile = null
            val uri = Uri.fromFile(file)
            chatImageHandler.handlePickedImage(uri)
            return
        }
        if (requestCode == PICK_IMAGE && resultCode == RESULT_OK && data != null) {
            chatImageHandler.handleActivityResult(data)
        } else if (requestCode == PICK_STICKER && resultCode == RESULT_OK && data != null) {
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
        chatHistoryLoader.checkDateSeparator(timestamp)
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

    // ===== 锁定/解锁输入区 =====
    // 等 AI 回复期间锁住发送，防止连发导致多个 API 请求同时在跑、上下文乱套
    private fun setInputLocked(locked: Boolean) {
        btnSend.isEnabled = !locked
        inputMessage.isEnabled = !locked
        btnSend.alpha = if (locked) 0.4f else 1f
        findViewById<TextView>(R.id.btnSendAll)?.isEnabled = !locked
        findViewById<TextView>(R.id.btnExpandSend)?.isEnabled = !locked
    }

    // ===== 展开/收起输入框 =====
    private fun toggleExpandedInput(expand: Boolean) {
        if (expand) {
            // 只在大输入框为空时才从小输入框搬文字过来
            // 否则保留上次收起时留下的内容
            if (expandedInput.text.isNullOrEmpty()) {
                expandedInput.setText(inputMessage.text)
            }
            expandedInput.setSelection(expandedInput.text.length)
            inputBar.visibility = View.GONE
            expandedInputPanel.visibility = View.VISIBLE
            expandedInput.requestFocus()
            stickerPanelManager.hide()
            findViewById<LinearLayout>(R.id.plusPanel).visibility = View.GONE
            if (batchModeManager.isBatchMode) {
                batchModeManager.exit()
            }
        } else {
            val text = expandedInput.text.toString()
            if (text.length <= 100) {
                inputMessage.setText(text)
                inputMessage.setSelection(inputMessage.text.length)
            }
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

    private fun initChat() {
        val apiConfigured = apiUrl.isNotEmpty() && apiKey.isNotEmpty() && apiModel.isNotEmpty()
        chatHistoryLoader.initChat(apiConfigured, currentAiStatus)
        // initChat 之后，如果有保存的 AI 状态，显示在顶栏
        if (currentAiStatus.isNotEmpty()) {
            tvStatus.text = currentAiStatus
            tvStatus.setTextColor(c.accent)
        }
    }

    /** 分条发送 —— 逐条显示（间隔300ms），最后统一调 API */
    private fun sendMultiMessages(parts: List<String>) {
        if (apiUrl.isEmpty() || apiKey.isEmpty() || apiModel.isEmpty()) {
            Toast.makeText(this, "请先去设置页配置 API", Toast.LENGTH_SHORT).show()
            return
        }
        setInputLocked(true)
        var index = 0

        val sendNext = object : Runnable {
            override fun run() {
                // 逐条上屏期间用户可能退出页面：直接停止，消息已边发边存不会丢
                if (isFinishing || isDestroyed) return
                if (index >= parts.size) {
                    // 不在这里解锁——callApiForReply 会保持锁定直到 AI 回复完成
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
                handler.postDelayed(this, 300L)
            }
        }
        handler.post(sendNext)
    }
 
    /**
     * 调用 API 获取 AI 回复（统一入口）
     *
     * sendMessage、sendMultiMessages、sendAllPending 全走这里。
     * 传了 rollback 参数就在失败时撤回用户消息，没传就只显示错误气泡。
     */
    private fun callApiForReply(
        rollbackView: View? = null,
        rollbackText: String? = null
    ) {
        setStatus("sending")
        setInputLocked(true)
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
                bubbleRenderer.friendAvatarPath = FriendStorage(this@ChatConversationActivity).getFriend(friendId)?.avatarPath ?: "" // 刷新图片头像
                if (result.userBioContext != null) chatHistory.add(ChatMessage("system", result.userBioContext!!))
                for (recall in result.recallResults) chatHistory.add(ChatMessage("system", "[留声] $recall"))
                if (result.shouldDream) triggerDream(friendId)

                // 徽章解锁弹窗
                if (result.pendingBadge != null) {
                    handler.post { badgeUnlockDialog.show(result.pendingBadge!!) }
                }

                // 保存到聊天记录
                // ★ 先存指令小字，再存 AI 正文，保证磁盘顺序 = 屏幕顺序
                //   屏幕上：小字在上、正文在下；磁盘里也必须如此，
                //   否则退出重进后 ChatHistoryLoader 按文件行序渲染会错位。
                for (action in result.actions) {
                    chatStorage.appendMessage(friendId,
                        StoredMessage("system", action, replyTime, type = "tip"))
                }
                val msgType = if (result.weatherCard) "weather" else "text"
                val msgExtras = if (result.weatherCard) {
                    val ws = WeatherStorage(this@ChatConversationActivity)
                    val wd = ws.getCachedWeather(); val ct = ws.getCity()
                    if (wd != null) WeatherStorage.toExtras(wd, ct) else ""
                } else ""
                chatStorage.appendMessage(friendId, StoredMessage(
                    "assistant", cleanText, replyTime, response.thinking, type = msgType, extras = msgExtras
                ))
                chatHistory.add(ChatMessage("assistant", cleanText))

                // 检查是否该触发聊天总结
                val msgCount = chatStorage.getMessageCount(friendId)  // 只数行数，不全量解析
                if (summaryStorage.shouldTriggerSummary(friendId, msgCount)) {
                    triggerChatSummary(friendId, msgCount)
                }

                handler.post {
                    // 页面已经关闭：不碰任何界面（防闪退），但通知照常发出去
                    // 数据在上面已经存好了，回来还能看到
                    if (isFinishing || isDestroyed) {
                        if (!isSeen) {
                            NotificationHelper(applicationContext).sendChatNotification(
                                friendId, friendName, friendIcon, cleanText
                            )
                        }
                        return@post
                    }

                    bubbleRenderer.removeTypingIndicator()
                    setStatus("online")
                    setInputLocked(false)

                    if (currentAiStatus.isNotEmpty()) {
                        tvStatus.text = currentAiStatus
                        tvStatus.setTextColor(c.accent)
                        getSharedPreferences("haven_status", MODE_PRIVATE)
                            .edit().putString("status_$friendId", currentAiStatus).apply()
                    }
                    tvFriendName.text = friendName

                    // 小字已在后台线程落盘，这里只上屏
                    for (action in result.actions) {
                        bubbleRenderer.addSystemTip(action)
                    }

                    if (isSeen) {
                        bubbleRenderer.addSeenIndicator()
                    } else {
                        if (response.thinking.isNotEmpty()) bubbleRenderer.addThinkingBlock(response.thinking)
                        if (result.weatherCard) {
                            val ws = WeatherStorage(this@ChatConversationActivity)
                            val wd = ws.getCachedWeather(); val city = ws.getCity()
                            if (wd != null && city.isNotEmpty())
                                bubbleRenderer.addWeatherCard(wd, city, isUser = false, timeStr = replyTimeStr)
                        }
                        bubbleRenderer.addAiBubbleStreaming(cleanText, replyTimeStr)
                        NotificationHelper(this@ChatConversationActivity).sendChatNotification(
                            friendId, friendName, friendIcon, cleanText
                        )
                    }
                }
            } catch (e: Exception) {
                val friendlyMsg = getErrorMessage(e)
                handler.post {
                    // 页面已经关闭：什么都不用做，防止操作已销毁的界面导致闪退
                    if (isFinishing || isDestroyed) return@post

                    bubbleRenderer.removeTypingIndicator()
                    setInputLocked(false)
                    if (rollbackView != null) {
                        // 单条发送失败：撤回用户消息，恢复输入框
                        // （发送期间输入已锁定，最后一条一定是这次发的，可以安全删除）
                        messagesContainer.removeView(rollbackView)
                        if (chatHistory.isNotEmpty()) chatHistory.removeAt(chatHistory.size - 1)
                        chatStorage.removeLastMessage(friendId)
                        if (rollbackText != null) inputMessage.setText(rollbackText)
                    }
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
                // ★ 引用元数据存进 extras，type 标记为 quote，content 只放回复正文
                val quoteExtras = JSONObject().apply {
                    put("quote_author", quoteAuthor)
                    put("quote_content", shortQuote)
                }.toString()
                chatStorage.appendMessage(friendId, StoredMessage("user", msg, now, type = "quote", extras = quoteExtras))
                chatHistory.add(ChatMessage("user", "[$timeStr] [引用 $quoteAuthor 说的: $shortQuote]\n$msg"))
                removeQuotePreview()
            } else {
                bubbleRenderer.addUserBubble(msg, timeStr)
                chatStorage.appendMessage(friendId, StoredMessage("user", msg, now))
                chatHistory.add(ChatMessage("user", "[$timeStr] $msg"))
            }
        }

        // 检查 AI 是否在睡觉
        val (wakeResult, wakeTip) = dreamStorage.tryWake(friendId)
        if (wakeTip != null) addAndSaveSystemTip(wakeTip)
        if (wakeResult == "too_deep") return

        // 调用 API（带撤回：失败时恢复用户消息和输入框）
        val userBubbleView = messagesContainer.getChildAt(messagesContainer.childCount - 1)
        val rollbackText = if (imagePaths.isEmpty()) msg else null
        callApiForReply(userBubbleView, rollbackText)
    }

    // ===== 显示引用预览条 =====
    private fun showQuotePreview(author: String, content: String) {
        removeQuotePreview()
        pendingQuoteAuthor = author
        pendingQuoteContent = content
        val preview = bubbleRenderer.buildQuotePreview(author, content) { removeQuotePreview() }
        quotePreviewContainer.addView(preview)
        quotePreviewContainer.visibility = View.VISIBLE
        inputMessage.requestFocus()
    }

    private fun removeQuotePreview() {
        pendingQuoteAuthor = null
        pendingQuoteContent = null
        quotePreviewContainer.removeAllViews()
        quotePreviewContainer.visibility = View.GONE
    }

    /** 加号菜单 */
    /** 发送全部待发消息 */
    private fun sendAllPending() {
        if (batchModeManager.isEmpty()) return
        setInputLocked(true)  // 逐条上屏+等回复期间锁住输入
        val items = batchModeManager.getItemsAndClear()
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var delay = 0L
        removeQuotePreview()
        val allTextForApi = StringBuilder()

        for ((index, item) in items.withIndex()) {
            if (item.quoteAuthor != null && item.quoteContent != null)
                allTextForApi.append("[引用 ${item.quoteAuthor}: ${item.quoteContent}]\n")
            if (item.type == "weather") {
                val ws = WeatherStorage(this)
                val summary = ws.buildWeatherSummary()
                if (summary.isNotEmpty()) allTextForApi.append(summary).append("\n")
            } else if (item.text.isNotEmpty()) {
                allTextForApi.append(item.text).append("\n")
            }

            handler.postDelayed({
                if (isFinishing || isDestroyed) return@postDelayed
                val now = System.currentTimeMillis()
                val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(now))
                checkDateSeparator(now)
                when (item.type) {
                    "image" -> {
                        if (item.imagePaths.size == 1) bubbleRenderer.addImageBubble(item.imagePaths[0], timeStr, item.text)
                        else bubbleRenderer.addMultiImageBubble(item.imagePaths, timeStr, item.text)
                        val display = if (item.text.isNotEmpty()) item.text else "[${item.imagePaths.size}张图片]"
                        chatStorage.appendMessage(friendId, StoredMessage("user", display, now, imagePath = item.imagePaths[0], type = "image"))
                        chatHistory.add(ChatMessage("user", if (item.text.isNotEmpty()) item.text else "[用户发送了图片]", item.imagePaths.map { ImageHelper.toBase64(File(it)) }))
                    }
                    "weather" -> weatherCardManager.renderAndStore(now)
                    else -> {
                        if (item.quoteAuthor != null && item.quoteContent != null) {
                            bubbleRenderer.addQuoteBubble(item.quoteAuthor, item.quoteContent, item.text, timeStr)
                            chatStorage.appendMessage(friendId, StoredMessage("user", "「回复 ${item.quoteAuthor}」\n${item.text}", now, type = "quote"))
                        } else {
                            bubbleRenderer.addUserBubble(item.text, timeStr)
                            chatStorage.appendMessage(friendId, StoredMessage("user", item.text, now))
                        }
                    }
                }
                if (index == items.size - 1) {
                    val text = allTextForApi.toString()
                    if (text.isNotEmpty()) chatHistory.add(ChatMessage("user", text))
                    callApiForReply()
                }
            }, delay)
            delay += 300L
        }
    }

    private fun sendSticker(stickerFile: File) {
        chatImageHandler.pendingPaths.clear()
        chatImageHandler.pendingPaths.add(stickerFile.absolutePath)
        stickerPanelManager.hide(); sendMessage()
    }
    private fun triggerChatSummary(friendId: String, currentCount: Int) {
        summaryStorage.triggerSummary(friendId, currentCount, chatStorage, apiUrl, apiKey, apiModel, apiType)
        { addAndSaveSystemTip("📝 自动生成了一条聊天总结") }
    }
    private fun triggerDream(friendId: String) { DreamEngine(this).triggerDream(friendId, chatHistory) }
    private fun addAndSaveSystemTip(msg: String) {
        // 先落盘（追加一行，不再整本重抄），再安全地画到界面上
        chatStorage.appendMessage(friendId, StoredMessage("system", msg, System.currentTimeMillis(), type = "tip"))
        runOnUiThread {
            if (!isFinishing && !isDestroyed) bubbleRenderer.addSystemTip(msg)
        }
    }

    /** 启动系统相机拍照 */
    private fun launchCamera() {
        // 运行时权限：Android 6+ 必须先申请 CAMERA
        if (checkSelfPermission(android.Manifest.permission.CAMERA)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), CAMERA_PERMISSION)
            return
        }
        val imageDir = File(filesDir, "chat_images").also { it.mkdirs() }
        val photoFile = File(imageDir, "camera_${System.currentTimeMillis()}.jpg")
        pendingCameraFile = photoFile
        val uri = androidx.core.content.FileProvider.getUriForFile(
            this, "$packageName.fileprovider", photoFile
        )
        val intent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        try {
            startActivityForResult(intent, TAKE_PHOTO)
        } catch (_: Exception) {
            Toast.makeText(this, "没有可用的相机应用", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                launchCamera()  // 权限通过，重新走一遍
            } else {
                Toast.makeText(this, "需要相机权限才能拍照", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 把异常转成人话 */
    private fun getErrorMessage(e: Exception): String {
        val msg = e.message?.lowercase() ?: ""; val name = e.javaClass.simpleName.lowercase()
        return when {
            name.contains("unknownhost") -> "网络不通，检查一下 Wi-Fi？"
            name.contains("connect") && name.contains("exception") -> "连不上服务器，API 地址可能有误"
            name.contains("timeout") -> "连接超时了，网络可能不太稳"
            msg.contains("401") || msg.contains("unauthorized") -> "API 密钥无效或已过期"
            msg.contains("403") || msg.contains("forbidden") -> "API 密钥没有权限"
            msg.contains("404") || msg.contains("not found") -> "模型名称没找到，去设置检查一下？"
            msg.contains("429") || msg.contains("rate") -> "请求太频繁了，歇一会儿"
            msg.contains("500") || msg.contains("502") || msg.contains("503") -> "服务器暂时不可用"
            msg.contains("thinking") -> "模型不支持思维链，换个模型试试"
            else -> "发送失败了（${e.message?.take(50) ?: "未知错误"}）"
        }
    }
}