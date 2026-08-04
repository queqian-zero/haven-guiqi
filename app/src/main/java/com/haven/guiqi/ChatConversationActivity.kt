package com.haven.guiqi

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
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
import java.util.concurrent.Executors

class ChatConversationActivity : AppCompatActivity() {
    /** 当前主题色 */
    private val c get() = ThemeHelper.getColors(this)
    private lateinit var chatRoot: View
    /** 覆盖完整窗口（含状态栏与手势导航区）的背景承载层。 */
    private lateinit var chatBackgroundHost: View
    private lateinit var appearanceStorage: ChatAppearanceStorage
    private val appearanceExecutor = Executors.newSingleThreadExecutor()
    private var appliedAppearanceRevision = Long.MIN_VALUE
    private var appearanceGeneration = 0L
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
    private lateinit var composerDock: FrameLayout
    private lateinit var composerCollapsedButton: TextView
    private lateinit var expandedInputPanel: LinearLayout
    private lateinit var expandedInput: EditText
    private lateinit var composerPinIndicator: TextView
    private var isComposerExpanded = false
    private var isComposerPinned = false
    private var composerQuickMenu: PopupWindow? = null

    // 分条输入期间，输入区进入临时保持展开状态。
    // 这不是持久“锁定”，退出分条后会恢复进入前的展开/收起状态。
    private var batchComposerHoldActive = false
    private var composerExpandedBeforeBatch = false
    private var largeInputWasOpenBeforeBatch = false
    private val composerEasing = PathInterpolator(0.22f, 1f, 0.36f, 1f)
        // 表情包相关
    private lateinit var stickerPanel: LinearLayout
    private lateinit var stickerGrid: LinearLayout
    private lateinit var stickerGroupTabs: LinearLayout
    private lateinit var stickerActionBar: LinearLayout
    private lateinit var stickerStorage: StickerStorage
    private lateinit var stickerPanelManager: StickerPanelManager
    private var pendingStickerImportGroup = StickerStorage.DEFAULT_GROUP
    /** 当前待发送表情包的本地透明快照与 API JPEG 快照。 */
    private var pendingStickerDisplayPath: String? = null
    private var pendingStickerApiPath: String? = null
    private lateinit var batchModeManager: BatchModeManager
    private lateinit var memoryStorage: MemoryStorage
    private lateinit var diaryStorage: DiaryStorage
    private lateinit var impressionStorage: ImpressionStorage
    private lateinit var dreamStorage: DreamStorage
    private lateinit var sleepMessageStorage: SleepMessageStorage
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

    /** 一轮 AI 回复（含全部工具往返）的可取消会话。 */
    private class ReplySession(val id: Long) {
        @Volatile var cancelled: Boolean = false
        @Volatile var api: ApiHelper? = null
        @Volatile var searchCoordinator: SearchCoordinator? = null
        @Volatile var worker: Thread? = null
        @Volatile var assistantPersisted: Boolean = false
        @Volatile var replyProcessed: Boolean = false
        var showTypingRunnable: Runnable? = null
        var pendingUiRunnable: Runnable? = null
        var interruptFinalizer: ((String) -> Unit)? = null
    }

    @Volatile private var activeReplySession: ReplySession? = null
    private var nextReplySessionId = 0L
    private var inputLocked = false
    private var replyInProgress = false
    private var stopReplyDialogShowing = false

    private val chatHistory = mutableListOf<ChatMessage>()
    private var maxContextMessages = 30
    private lateinit var chatStorage: ChatStorage
    private lateinit var bubbleRenderer: BubbleRenderer
    private val bubbleStyleStorage by lazy { BubbleStyleStorage(this) }

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
        // 系统栏必须透明，完整聊天背景由 android.R.id.content 承载并延伸到栏下方。
        // 不能再使用主题背景色，否则浅色模式会出现白条、深色模式会出现深色条。
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        setContentView(R.layout.activity_chat_conversation)
        chatBackgroundHost = findViewById(android.R.id.content)
        chatRoot = findViewById(R.id.chatRoot)
        // 背景统一画在全窗口承载层；聊天内容层保持透明，避免遮住系统栏下方的背景。
        chatRoot.setBackgroundColor(Color.TRANSPARENT)
        chatBackgroundHost.setBackgroundResource(R.drawable.chat_bg)
        appearanceStorage = ChatAppearanceStorage(this)

        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        val useDarkSystemIcons = !ThemeHelper.isDark(this)
        insetsController.isAppearanceLightStatusBars = useDarkSystemIcons
        insetsController.isAppearanceLightNavigationBars = useDarkSystemIcons

        ViewCompat.setOnApplyWindowInsetsListener(chatBackgroundHost) { view, insets ->
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
        composerDock = findViewById(R.id.composerDock)
        composerCollapsedButton = findViewById(R.id.composerCollapsedButton)
        expandedInputPanel = findViewById(R.id.expandedInputPanel)
        expandedInput = findViewById(R.id.expandedInput)
        composerPinIndicator = findViewById(R.id.composerPinIndicator)
        stickerPanel = findViewById(R.id.stickerPanel)
        stickerGrid = findViewById(R.id.stickerGrid)
        stickerGroupTabs = findViewById(R.id.stickerGroupTabs)
        stickerActionBar = findViewById(R.id.stickerActionBar)

        // 管理按钮
        findViewById<TextView>(R.id.btnManageSticker).setOnClickListener {
            stickerPanelManager.toggleManageMode()
        }

        friendId = intent.getStringExtra("friend_id") ?: ""
        isComposerPinned = getSharedPreferences("haven_composer", MODE_PRIVATE)
            .getBoolean("pinned_$friendId", false)
        friendName = intent.getStringExtra("friend_name") ?: "好友"
        friendIcon = intent.getStringExtra("friend_icon") ?: "★"
        val friend = FriendStorage(this).getFriend(friendId)
        val avatarPath = friend?.avatarPath ?: ""
        tvFriendName.text = friendName
        bubbleRenderer.friendName = friendName
        bubbleRenderer.friendIcon = friendIcon
        bubbleRenderer.friendAvatarPath = avatarPath
        applyBubbleStyles()
        applyChatAppearance()
        chatStorage = ChatStorage(this)
        chatHistoryLoader = ChatHistoryLoader(
            this,
            chatStorage,
            bubbleRenderer,
            chatHistory,
            messagesContainer,
            scrollMessages,
            friendId
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
            null
        )
        batchModeManager.onToggle = { entering ->
            if (entering) {
                composerExpandedBeforeBatch = isComposerExpanded
                largeInputWasOpenBeforeBatch = expandedInputPanel.visibility == View.VISIBLE
                batchComposerHoldActive = true

                plusMenuManager.hide(false)
                stickerPanelManager.hide()
                if (largeInputWasOpenBeforeBatch) {
                    toggleExpandedInput(false)
                }
                // 分条模式需要持续查看和滚动待发列表，因此临时保持普通输入栏展开。
                expandComposer(showKeyboard = true)
            } else {
                batchComposerHoldActive = false

                when {
                    isComposerPinned -> expandComposer(showKeyboard = false)
                    largeInputWasOpenBeforeBatch -> toggleExpandedInput(true)
                    composerExpandedBeforeBatch -> expandComposer(showKeyboard = false)
                    else -> collapseComposer(force = true)
                }

                composerExpandedBeforeBatch = false
                largeInputWasOpenBeforeBatch = false
            }
        }
        memoryStorage = MemoryStorage(this)
        diaryStorage = DiaryStorage(this)
        impressionStorage = ImpressionStorage(this)
        dreamStorage = DreamStorage(this)
        sleepMessageStorage = SleepMessageStorage(this)
        summaryStorage = ChatSummaryStorage(this)
                // 搜索
        searchManager = SearchManager(
            this,
            findViewById(R.id.searchPanel),
            findViewById(R.id.searchInput),
            findViewById(R.id.searchNavigation),
            findViewById(R.id.searchStatus),
            findViewById(R.id.btnSearchPrev),
            findViewById(R.id.btnSearchNext),
            findViewById(R.id.searchResults),
            findViewById(R.id.searchResultsScroll),
            chatStorage,
            friendId,
            friendName,
            onSearchOpened = { chatHistoryLoader.beginSearchSession() },
            onSearchClosed = { chatHistoryLoader.endSearchSession() },
            onJumpToResult = { messages, index ->
                chatHistoryLoader.jumpToSearchResult(messages, index)
            }
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
        btnSend.setOnClickListener {
            if (replyInProgress) {
                Toast.makeText(this, "长按停止键，再在确认面板里停止本轮回复", Toast.LENGTH_SHORT).show()
            } else {
                sendMessage()
            }
        }
        btnSend.setOnLongClickListener {
            when {
                replyInProgress -> {
                    showStopReplyDialog()
                    true
                }
                dreamStorage.isSleeping(friendId) &&
                    !batchModeManager.isBatchMode &&
                    (inputMessage.text.isNotBlank() || chatImageHandler.pendingPaths.isNotEmpty()) -> {
                    showEmergencyWakeDialog { sendMessage(emergencyWake = true) }
                    true
                }
                else -> false
            }
        }

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

        btnPlus.setOnClickListener {
            dismissComposerQuickMenu()
            plusMenuManager.toggle()
        }
        composerCollapsedButton.setOnClickListener { expandComposer(showKeyboard = true) }

        // 长按小星星或展开状态的加号：打开输入快捷菜单。
        // 输入框自己的长按仍交给系统，用于选词、复制和粘贴。
        val showQuickMenu = View.OnLongClickListener {
            showComposerQuickMenu()
            true
        }
        composerCollapsedButton.setOnLongClickListener(showQuickMenu)
        btnPlus.setOnLongClickListener(showQuickMenu)
        inputBar.setOnLongClickListener(showQuickMenu)
        expandedInputPanel.setOnLongClickListener(showQuickMenu)
        // 空输入框时长按打开快捷菜单；已有文字时保留系统选词/复制粘贴。
        inputMessage.setOnLongClickListener {
            if (inputMessage.text.isNullOrEmpty()) {
                showComposerQuickMenu()
                true
            } else {
                false
            }
        }
        expandedInput.setOnLongClickListener {
            if (expandedInput.text.isNullOrEmpty()) {
                showComposerQuickMenu()
                true
            } else {
                false
            }
        }

        // 发送全部
        findViewById<TextView>(R.id.btnSendAll).apply {
            setOnClickListener { sendAllPending() }
            setOnLongClickListener {
                if (dreamStorage.isSleeping(friendId) && !batchModeManager.isEmpty()) {
                    showEmergencyWakeDialog { sendAllPending(emergencyWake = true) }
                    true
                } else {
                    false
                }
            }
        }

        // 导入按钮：先选画匣内部分类，再从相册多选；只保存一份到共享画匣。
        findViewById<TextView>(R.id.btnAddSticker).setOnClickListener {
            showStickerImportGroupDialog()
        }

        findViewById<TextView>(R.id.btnCollapse).setOnClickListener { toggleExpandedInput(false) }
        findViewById<TextView>(R.id.btnExpandSend).apply {
            setOnClickListener { sendExpandedInput() }
            setOnLongClickListener {
                if (dreamStorage.isSleeping(friendId) && expandedInput.text.isNotBlank()) {
                    showEmergencyWakeDialog { sendExpandedInput(emergencyWake = true) }
                    true
                } else {
                    false
                }
            }
        }

        setupCollapsibleComposer()
        initChat()
    }

    // 离开页面时只记聊天文件的轻量版本标记；不再为了比较变化而全文件数行。
    private var pausedChatRevision: ChatFileRevision? = null

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
        applyChatAppearance()
        val bubbleStyleChanged = applyBubbleStyles()

        val pausedRevision = pausedChatRevision
        val chatFileChanged = pausedRevision != null &&
            chatStorage.getFileRevision(friendId) != pausedRevision
        if (bubbleStyleChanged || chatFileChanged) {
            messagesContainer.removeAllViews()
            chatHistory.clear()
            initChat()
        }
        pausedChatRevision = null
    }

    /**
     * 读取当前住户的双方普通文字气泡样式。
     * 从设置页返回时若样式变了，onResume 会重绘现有历史消息，使修改立即生效。
     */
    private fun applyBubbleStyles(): Boolean {
        if (friendId.isBlank() || !::bubbleRenderer.isInitialized) return false
        return bubbleRenderer.updateBubbleStyles(
            bubbleStyleStorage.getStyle(friendId, BubbleStyleStorage.Target.FRIEND),
            bubbleStyleStorage.getStyle(friendId, BubbleStyleStorage.Target.USER)
        )
    }

    override fun onPause() {
        super.onPause()
        pausedChatRevision = chatStorage.getFileRevision(friendId)
    }

    override fun onDestroy() {
        appearanceGeneration++
        appearanceExecutor.shutdownNow()
        if (::chatBackgroundHost.isInitialized) {
            appearanceStorage.releaseBackground(chatBackgroundHost)
        }
        if (::searchManager.isInitialized) searchManager.destroy()
        networkMonitor.stop()
        super.onDestroy()
    }

    private fun applyChatAppearance(force: Boolean = false) {
        if (friendId.isBlank() || !::chatRoot.isInitialized || !::bubbleRenderer.isInitialized) return
        val revision = appearanceStorage.getRevision(friendId)
        if (!force && appliedAppearanceRevision == revision) return
        appliedAppearanceRevision = revision
        val generation = ++appearanceGeneration
        val customBackgroundFile = appearanceStorage.getBackgroundFile(friendId)
        val hasCustomBackground = customBackgroundFile != null
        val themeIsDark = ThemeHelper.isDark(this)
        if (!hasCustomBackground) {
            bubbleRenderer.chatBackgroundIsDark = themeIsDark
        }
        bubbleRenderer.friendAvatarFramePath = appearanceStorage.getAvatarFrameFile(
            friendId,
            ChatAppearanceStorage.AvatarTarget.FRIEND
        )?.absolutePath.orEmpty()
        bubbleRenderer.userAvatarFramePath = appearanceStorage.getAvatarFrameFile(
            friendId,
            ChatAppearanceStorage.AvatarTarget.USER
        )?.absolutePath.orEmpty()

        val friendFrameTransform = appearanceStorage.getAvatarFrameTransform(
            friendId,
            ChatAppearanceStorage.AvatarTarget.FRIEND
        )
        bubbleRenderer.friendAvatarFrameScalePercent = friendFrameTransform.scalePercent
        bubbleRenderer.friendAvatarFrameOffsetXPercent = friendFrameTransform.offsetXPercent
        bubbleRenderer.friendAvatarFrameOffsetYPercent = friendFrameTransform.offsetYPercent

        val userFrameTransform = appearanceStorage.getAvatarFrameTransform(
            friendId,
            ChatAppearanceStorage.AvatarTarget.USER
        )
        bubbleRenderer.userAvatarFrameScalePercent = userFrameTransform.scalePercent
        bubbleRenderer.userAvatarFrameOffsetXPercent = userFrameTransform.offsetXPercent
        bubbleRenderer.userAvatarFrameOffsetYPercent = userFrameTransform.offsetYPercent

        bubbleRenderer.avatarDisplayMode = appearanceStorage.getAvatarDisplayMode(friendId)
        bubbleRenderer.friendAvatarShape = appearanceStorage.getAvatarShape(
            friendId,
            ChatAppearanceStorage.AvatarTarget.FRIEND
        )
        bubbleRenderer.userAvatarShape = appearanceStorage.getAvatarShape(
            friendId,
            ChatAppearanceStorage.AvatarTarget.USER
        )
        bubbleRenderer.syncAvatarAppearance()
        bubbleRenderer.traceDividerStyle = appearanceStorage.getTraceDividerStyle(friendId)
        bubbleRenderer.useCustomChatBackground = hasCustomBackground
        applyCollapsedComposerAppearance(hasCustomBackground)

        val targetWidth = chatBackgroundHost.width.takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels
        val targetHeight = chatBackgroundHost.height.takeIf { it > 0 }
            ?: resources.displayMetrics.heightPixels
        appearanceExecutor.execute {
            val effects = appearanceStorage.getBackgroundEffects(friendId)
            val sampledBackgroundIsDark = customBackgroundFile?.let { file ->
                estimateChatBackgroundIsDark(
                    file = file,
                    overlayPercent = effects.overlayPercent,
                    themeIsDark = themeIsDark
                )
            } ?: themeIsDark
            val drawable = appearanceStorage.loadBackgroundDrawable(
                friendId,
                targetWidth,
                targetHeight
            )
            val posted = chatBackgroundHost.post {
                if (!isFinishing && !isDestroyed &&
                    generation == appearanceGeneration &&
                    appliedAppearanceRevision == revision) {
                    bubbleRenderer.chatBackgroundIsDark = sampledBackgroundIsDark
                    appearanceStorage.applyBackground(chatBackgroundHost, drawable)
                } else {
                    appearanceStorage.releaseDrawable(drawable)
                }
            }
            if (!posted) appearanceStorage.releaseDrawable(drawable)
        }
    }

    private fun applyCollapsedComposerAppearance(hasCustomBackground: Boolean) {
        if (!::composerCollapsedButton.isInitialized) return
        val dark = ThemeHelper.isDark(this)
        val surface = if (hasCustomBackground) {
            withAlpha(if (dark) c.card else Color.WHITE, if (dark) 198 else 176)
        } else {
            c.card
        }
        val border = if (hasCustomBackground) {
            withAlpha(c.textPrimary, if (dark) 48 else 38)
        } else {
            c.borderMedium
        }
        composerCollapsedButton.background = GradientDrawable().apply {
            setColor(surface)
            cornerRadius = dp(17).toFloat()
            setStroke(dp(1), border)
        }
        composerCollapsedButton.setTextColor(
            if (hasCustomBackground) c.textPrimary else c.accent
        )
    }

    private fun withAlpha(color: Int, alpha: Int): Int = Color.argb(
        alpha.coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color)
    )

    /**
     * 用很小的缩略图估算聊天背景的实际明暗，只服务于“浮在线上的标题”黑白切换。
     * 不解码原图尺寸，也不在主线程做像素遍历。
     */
    private fun estimateChatBackgroundIsDark(
        file: File,
        overlayPercent: Int,
        themeIsDark: Boolean
    ): Boolean {
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching themeIsDark

            var sample = 1
            while (bounds.outWidth / sample > 96 || bounds.outHeight / sample > 96) {
                sample *= 2
            }
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
            }
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, options)
                ?: return@runCatching themeIsDark
            try {
                // 取中央 80%，比整图平均更接近 centerCrop 后真正落在聊天区里的画面。
                val left = (bitmap.width * 0.10f).toInt().coerceIn(0, bitmap.width - 1)
                val top = (bitmap.height * 0.10f).toInt().coerceIn(0, bitmap.height - 1)
                val right = (bitmap.width * 0.90f).toInt().coerceIn(left + 1, bitmap.width)
                val bottom = (bitmap.height * 0.90f).toInt().coerceIn(top + 1, bitmap.height)
                val stepX = ((right - left) / 28).coerceAtLeast(1)
                val stepY = ((bottom - top) / 28).coerceAtLeast(1)

                var luminanceSum = 0.0
                var weightSum = 0.0
                var y = top
                while (y < bottom) {
                    var x = left
                    while (x < right) {
                        val pixel = bitmap.getPixel(x, y)
                        val alpha = Color.alpha(pixel) / 255.0
                        if (alpha > 0.05) {
                            val r = Color.red(pixel) / 255.0
                            val g = Color.green(pixel) / 255.0
                            val b = Color.blue(pixel) / 255.0
                            luminanceSum += (0.2126 * r + 0.7152 * g + 0.0722 * b) * alpha
                            weightSum += alpha
                        }
                        x += stepX
                    }
                    y += stepY
                }
                if (weightSum <= 0.0) return@runCatching themeIsDark

                val imageLuminance = luminanceSum / weightSum
                val overlay = overlayPercent.coerceIn(0, 100) / 100.0
                val overlayLuminance = if (themeIsDark) 0.0 else 1.0
                val effectiveLuminance =
                    imageLuminance * (1.0 - overlay) + overlayLuminance * overlay
                effectiveLuminance < 0.53
            } finally {
                bitmap.recycle()
            }
        }.getOrDefault(themeIsDark)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 4001 && resultCode == RESULT_OK) {
            bubbleRenderer.friendAvatarPath = FriendStorage(this).getFriend(friendId)?.avatarPath ?: ""
            applyChatAppearance(force = true)
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

            val targetGroup = pendingStickerImportGroup
            Toast.makeText(this, "正在收藏 ${uris.size} 张到「$targetGroup」…", Toast.LENGTH_SHORT).show()
            Thread {
                var successCount = 0
                for (uri in uris) {
                    if (stickerStorage.importFromUri(uri, targetGroup) != null) successCount++
                }

                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (successCount > 0) {
                        val countText = if (successCount == 1) "1 张" else "$successCount 张"
                        Toast.makeText(
                            this,
                            "已收藏 $countText 到「$targetGroup」",
                            Toast.LENGTH_SHORT
                        ).show()
                        stickerPanelManager.refreshGrid()
                    } else {
                        Toast.makeText(this, "导入失败", Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
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
    // 等 AI 回复期间仍保留同一个按钮，但切成停止键；只有确认面板的第二次点击才会真正停止。
    private fun setInputLocked(locked: Boolean) {
        inputLocked = locked
        refreshComposerEnabledState()
    }

    private fun setReplyInProgress(running: Boolean) {
        replyInProgress = running
        refreshComposerEnabledState()
    }

    private fun refreshComposerEnabledState() {
        if (!::btnSend.isInitialized) return
        btnSend.text = if (replyInProgress) "■" else "➤"
        btnSend.contentDescription = if (replyInProgress) "长按并确认，停止本轮回复" else "发送"
        btnSend.isEnabled = replyInProgress || !inputLocked
        btnSend.alpha = if (replyInProgress || !inputLocked) 1f else 0.4f
        inputMessage.isEnabled = !inputLocked
        findViewById<TextView>(R.id.btnSendAll)?.isEnabled = !inputLocked
        findViewById<TextView>(R.id.btnExpandSend)?.isEnabled = !inputLocked
    }

    private fun showStopReplyDialog() {
        val session = activeReplySession ?: return
        if (session.cancelled || stopReplyDialogShowing) return
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("停止本轮回复？")
            .setMessage("已经显示出来的内容会保留；尚未生成的内容，以及正在进行的思考、搜索和工具调用会停止。")
            .setNegativeButton("继续等待", null)
            .setPositiveButton("停止本轮") { _, _ -> stopReplySession(session) }
            .create()
        stopReplyDialogShowing = true
        dialog.setOnDismissListener { stopReplyDialogShowing = false }
        dialog.show()
    }

    private fun stopReplySession(session: ReplySession) {
        if (activeReplySession !== session || session.cancelled) return
        session.cancelled = true
        session.showTypingRunnable?.let(handler::removeCallbacks)
        session.pendingUiRunnable?.let(handler::removeCallbacks)
        session.api?.cancel()
        session.searchCoordinator?.cancel()
        session.worker?.interrupt()

        val visibleText = if (::bubbleRenderer.isInitialized) {
            bubbleRenderer.cancelAiBubbleStreaming()
        } else {
            ""
        }
        if (visibleText.isNotBlank()) {
            session.interruptFinalizer?.invoke(visibleText)
        }
        session.interruptFinalizer = null

        if (::bubbleRenderer.isInitialized) bubbleRenderer.removeTypingIndicator()
        if (!isFinishing && !isDestroyed) {
            setStatus("online")
            if (currentAiStatus.isNotEmpty()) {
                tvStatus.text = currentAiStatus
                tvStatus.setTextColor(c.accent)
            }
            Toast.makeText(this, "已停止本轮回复", Toast.LENGTH_SHORT).show()
        }
        finishReplySession(session)
    }

    private fun finishReplySession(session: ReplySession) {
        if (activeReplySession !== session) return
        session.showTypingRunnable?.let(handler::removeCallbacks)
        session.pendingUiRunnable?.let(handler::removeCallbacks)
        session.interruptFinalizer = null
        activeReplySession = null
        if (!isFinishing && !isDestroyed) {
            setReplyInProgress(false)
            setInputLocked(false)
        }
    }

    private fun ensureReplySessionActive(session: ReplySession) {
        if (activeReplySession !== session || session.cancelled || Thread.currentThread().isInterrupted) {
            throw ApiRequestCancelledException()
        }
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
            plusMenuManager.hide(false)
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
            isComposerExpanded = true
            composerCollapsedButton.visibility = View.INVISIBLE
            inputBar.visibility = View.VISIBLE
            inputBar.alpha = 1f
            inputBar.scaleX = 1f
            inputBar.scaleY = 1f
            batchModeManager.setComposerVisible(true)
        }
    }

    // ===== 可收放输入栏 =====
    private fun setupCollapsibleComposer() {
        updateComposerPinIndicator()
        if (isComposerPinned) {
            isComposerExpanded = true
            inputBar.visibility = View.VISIBLE
            inputBar.alpha = 1f
            inputBar.scaleX = 1f
            inputBar.scaleY = 1f
            composerCollapsedButton.visibility = View.INVISIBLE
            composerCollapsedButton.alpha = 0f
            batchModeManager.setComposerVisible(true)
        } else {
            isComposerExpanded = false
            inputBar.visibility = View.GONE
            inputBar.alpha = 0f
            inputBar.scaleX = 0.18f
            inputBar.scaleY = 0.86f
            composerCollapsedButton.visibility = View.VISIBLE
            composerCollapsedButton.alpha = 1f
            composerCollapsedButton.scaleX = 1f
            composerCollapsedButton.scaleY = 1f
            batchModeManager.setComposerVisible(false)
        }
    }

    private fun expandComposer(showKeyboard: Boolean) {
        if (expandedInputPanel.visibility == View.VISIBLE) return
        if (isComposerExpanded) {
            batchModeManager.setComposerVisible(true)
            if (showKeyboard) {
                inputMessage.requestFocus()
                showKeyboard(inputMessage)
            }
            return
        }

        isComposerExpanded = true
        batchModeManager.setComposerVisible(true)
        composerCollapsedButton.animate().cancel()
        inputBar.animate().cancel()

        composerCollapsedButton.animate()
            .alpha(0f)
            .scaleX(0.72f)
            .scaleY(0.72f)
            .setDuration(120L)
            .setInterpolator(composerEasing)
            .withEndAction { composerCollapsedButton.visibility = View.INVISIBLE }
            .start()

        inputBar.visibility = View.VISIBLE
        inputBar.alpha = 0f
        inputBar.scaleX = 0.18f
        inputBar.scaleY = 0.86f
        inputBar.post {
            inputBar.pivotX = inputBar.width / 2f
            inputBar.pivotY = inputBar.height / 2f
            inputBar.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(270L)
                .setInterpolator(composerEasing)
                .start()
        }

        if (showKeyboard) {
            inputMessage.requestFocus()
            inputMessage.postDelayed({ showKeyboard(inputMessage) }, 90L)
        }
    }

    private fun collapseComposer(force: Boolean = false) {
        if (batchComposerHoldActive && !force) {
            // 分条模式中，点击聊天区只收键盘，不把输入栏和待发列表一起卷走。
            inputMessage.clearFocus()
            hideKeyboard()
            return
        }
        if (isComposerPinned && !force) return
        if (!isComposerExpanded || expandedInputPanel.visibility == View.VISIBLE) return
        isComposerExpanded = false
        batchModeManager.setComposerVisible(false)
        plusMenuManager.hide()
        stickerPanelManager.hide()
        inputMessage.clearFocus()
        hideKeyboard()

        inputBar.animate().cancel()
        composerCollapsedButton.animate().cancel()
        inputBar.animate()
            .alpha(0f)
            .scaleX(0.18f)
            .scaleY(0.86f)
            .setDuration(170L)
            .setInterpolator(composerEasing)
            .withEndAction { inputBar.visibility = View.GONE }
            .start()

        composerCollapsedButton.visibility = View.VISIBLE
        composerCollapsedButton.alpha = 0f
        composerCollapsedButton.scaleX = 0.72f
        composerCollapsedButton.scaleY = 0.72f
        composerCollapsedButton.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setStartDelay(45L)
            .setDuration(190L)
            .setInterpolator(composerEasing)
            .start()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            if (::bubbleRenderer.isInitialized) {
                bubbleRenderer.hideRevealedTraceTitlesOutside(
                    event.rawX.toInt(),
                    event.rawY.toInt()
                )
            }

            if (isComposerExpanded && expandedInputPanel.visibility != View.VISIBLE) {
                val insideComposer = isTouchInside(composerDock, event)
                val insidePlusPanel = isTouchInside(findViewById(R.id.plusPanel), event)
                val insideStickerPanel = isTouchInside(stickerPanel, event)
                val insidePendingArea = isTouchInside(findViewById(R.id.pendingArea), event)

                if (!insideComposer && !insidePlusPanel && !insideStickerPanel && !insidePendingArea) {
                    if (batchModeManager.isBatchMode) {
                        // 分条模式下，滚消息或点聊天空白只关闭键盘，输入栏和待发区保持原位。
                        inputMessage.clearFocus()
                        hideKeyboard()
                        dismissComposerQuickMenu()
                        plusMenuManager.hide(false)
                        stickerPanelManager.hide()
                    } else {
                        collapseComposer()
                    }
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun showComposerQuickMenu() {
        dismissComposerQuickMenu()
        plusMenuManager.hide(false)
        stickerPanelManager.hide()

        val menuWidth = dp(238)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                setColor(c.card)
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), c.border)
            }
        }

        fun addAction(title: String, subtitle: String, symbol: String, action: () -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(9), dp(10), dp(9))
                isClickable = true
                isFocusable = true
                background = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    cornerRadius = dp(12).toFloat()
                }
                setOnClickListener {
                    dismissComposerQuickMenu()
                    action()
                }
            }
            row.addView(TextView(this@ChatConversationActivity).apply {
                text = symbol
                gravity = Gravity.CENTER
                textSize = 18f
                setTextColor(c.accent)
                layoutParams = LinearLayout.LayoutParams(dp(34), dp(38)).apply {
                    marginEnd = dp(8)
                }
            })
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@ChatConversationActivity).apply {
                    text = title
                    textSize = 14f
                    setTextColor(c.textPrimary)
                })
                addView(TextView(this@ChatConversationActivity).apply {
                    text = subtitle
                    textSize = 10f
                    setTextColor(c.textHint)
                    setPadding(0, dp(2), 0, 0)
                })
            })
            container.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        addAction(
            if (batchModeManager.isBatchMode) "退出分条输入" else "分条输入",
            if (batchModeManager.isBatchMode) "结束待发模式，清空尚未发送的内容" else "把多段内容暂存后一次发送",
            "≡"
        ) {
            // 由分条状态回调负责展开和恢复，避免先展开后丢失“进入前是收起状态”的信息。
            batchModeManager.toggle()
        }

        val largeInputOpen = expandedInputPanel.visibility == View.VISIBLE
        addAction(
            if (largeInputOpen) "收起输入栏" else "展开输入栏",
            if (largeInputOpen) "回到普通输入栏" else "打开适合长文的大输入框",
            "↕"
        ) {
            toggleExpandedInput(!largeInputOpen)
        }

        addAction(
            if (isComposerPinned) "解除锁定" else "锁定输入栏",
            if (isComposerPinned) "恢复点击聊天区后自动收起" else "保持普通输入栏展开，方便连续聊天",
            if (isComposerPinned) "○" else "●"
        ) {
            setComposerPinned(!isComposerPinned)
        }

        container.measure(
            View.MeasureSpec.makeMeasureSpec(menuWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val popup = PopupWindow(
            container,
            menuWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            isOutsideTouchable = true
            isFocusable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = dp(10).toFloat()
            setOnDismissListener { composerQuickMenu = null }
        }
        composerQuickMenu = popup

        composerDock.post {
            val xOffset = ((composerDock.width - menuWidth) / 2).coerceAtLeast(0)
            val yOffset = -(composerDock.height + container.measuredHeight + dp(4))
            popup.showAsDropDown(composerDock, xOffset, yOffset)
        }
    }

    private fun dismissComposerQuickMenu() {
        composerQuickMenu?.dismiss()
        composerQuickMenu = null
    }

    private fun setComposerPinned(pinned: Boolean) {
        isComposerPinned = pinned
        getSharedPreferences("haven_composer", MODE_PRIVATE)
            .edit()
            .putBoolean("pinned_$friendId", pinned)
            .apply()
        updateComposerPinIndicator()
        if (pinned) {
            if (expandedInputPanel.visibility == View.VISIBLE) {
                toggleExpandedInput(false)
            }
            expandComposer(showKeyboard = false)
            Toast.makeText(this, "输入栏已锁定展开", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "已恢复自动收起", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateComposerPinIndicator() {
        composerPinIndicator.visibility = if (isComposerPinned) View.VISIBLE else View.GONE
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun isTouchInside(view: View, event: MotionEvent): Boolean {
        if (view.visibility != View.VISIBLE) return false
        val rect = Rect()
        view.getGlobalVisibleRect(rect)
        return rect.contains(event.rawX.toInt(), event.rawY.toInt())
    }

    private fun showKeyboard(view: View) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(window.decorView.windowToken, 0)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            composerQuickMenu?.isShowing == true -> dismissComposerQuickMenu()
            expandedInputPanel.visibility == View.VISIBLE -> toggleExpandedInput(false)
            plusMenuManager.isVisible() -> plusMenuManager.hide()
            stickerPanel.visibility == View.VISIBLE -> stickerPanelManager.hide()
            bubbleRenderer.hasExpandedThinkingBlock() -> bubbleRenderer.collapseAllThinkingBlocks()
            isComposerExpanded && (isComposerPinned || batchModeManager.isBatchMode) && inputMessage.hasFocus() -> {
                inputMessage.clearFocus()
                hideKeyboard()
            }
            isComposerExpanded && batchModeManager.isBatchMode -> {
                inputMessage.clearFocus()
                hideKeyboard()
            }
            // 锁定输入栏只代表“不要自动收起”，不能吞掉系统返回键。
            // 键盘已经在上一个分支关闭后，再按返回应正常离开聊天页。
            isComposerExpanded && isComposerPinned -> super.onBackPressed()
            isComposerExpanded -> collapseComposer()
            else -> super.onBackPressed()
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
        val systemPrompt = SystemPromptBuilder(this).build(
            friendId = friendId,
            includeSearchTools = true
        )
        val freshSystemMsg = ChatMessage("system", systemPrompt)

        val nonSystemMsgs = chatHistory.filter { it.role != "system" }
        val recentMsgs = if (nonSystemMsgs.size > maxContextMessages) {
            nonSystemMsgs.takeLast(maxContextMessages)
        } else { nonSystemMsgs }

        // 紧急唤醒或自然醒失败后的下一次发送，要把床边留言明确交给模型。
        // 这样即使留言数量超过普通上下文条数，也不会只看到最后几条。
        val sleepRecap = sleepMessageStorage.buildWakeRecap(friendId)
        val pendingContext = if (sleepRecap.isNotBlank()) {
            listOf(ChatMessage("system", "[床边留言]\n$sleepRecap"))
        } else {
            emptyList()
        }
        return listOf(freshSystemMsg) + pendingContext + recentMsgs
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

    /** 分条发送 —— 使用安静版 260ms 节奏逐条显示，最后统一调 API */
    private fun sendExpandedInput(emergencyWake: Boolean = false) {
        val text = expandedInput.text.toString().trim()
        if (text.isEmpty()) return
        val sleepingAtSend = dreamStorage.isSleeping(friendId)
        if ((!sleepingAtSend || emergencyWake) && !hasApiConfig()) {
            Toast.makeText(this, "请先去设置页配置 API", Toast.LENGTH_SHORT).show()
            return
        }
        expandedInput.text.clear()
        toggleExpandedInput(false)

        val parts = text.split(Regex("-{3,}")).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size <= 1) {
            inputMessage.setText(text)
            sendMessage(emergencyWake = emergencyWake)
        } else {
            sendMultiMessages(parts, emergencyWake = emergencyWake)
        }
    }

    private fun sendMultiMessages(parts: List<String>, emergencyWake: Boolean = false) {
        val sleepingAtSend = dreamStorage.isSleeping(friendId)
        if ((!sleepingAtSend || emergencyWake) && !hasApiConfig()) {
            Toast.makeText(this, "请先去设置页配置 API", Toast.LENGTH_SHORT).show()
            return
        }
        setInputLocked(true)

        // 睡眠期间先一次性完整落盘，避免用户马上离开页面时后半段还没来得及保存。
        if (sleepingAtSend) {
            val messages = mutableListOf<Pair<String, Long>>()
            val baseTime = System.currentTimeMillis()
            var visualReplyNotBeforeUptimeMs = 0L
            for ((index, part) in parts.withIndex()) {
                val now = baseTime + index
                val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(now))
                checkDateSeparator(now)
                val startDelayMs = index * bubbleRenderer.quietUserMessageStaggerMs
                bubbleRenderer.prepareNextUserMessageAnimation(
                    startDelayMs = startDelayMs,
                    showAvatar = true
                )
                visualReplyNotBeforeUptimeMs = maxOf(
                    visualReplyNotBeforeUptimeMs,
                    SystemClock.uptimeMillis() +
                        startDelayMs +
                        bubbleRenderer.quietUserMessageVisualTailMs() +
                        24L
                )
                bubbleRenderer.addUserBubble(part, timeStr)
                chatStorage.appendMessage(friendId, StoredMessage("user", part, now))
                chatHistory.add(ChatMessage("user", "[$timeStr] $part"))
                messages.add(part to now)
            }
            handleSleepingDelivery(messages, emergencyWake) {
                callApiForReply(
                    clearSleepInboxOnSuccess = true,
                    visualReplyNotBeforeUptimeMs = visualReplyNotBeforeUptimeMs
                )
            }
            return
        }

        var index = 0
        val sendNext = object : Runnable {
            override fun run() {
                if (isFinishing || isDestroyed) return
                if (index >= parts.size) {
                    callApiForReply(
                        clearSleepInboxOnSuccess = sleepMessageStorage.hasPending(friendId)
                    )
                    return
                }
                val part = parts[index]
                val now = System.currentTimeMillis()
                val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(now))
                checkDateSeparator(now)
                bubbleRenderer.prepareNextUserMessageAnimation(showAvatar = true)
                bubbleRenderer.addUserBubble(part, timeStr)
                chatStorage.appendMessage(friendId, StoredMessage("user", part, now))
                chatHistory.add(ChatMessage("user", "[$timeStr] $part"))
                index++
                val nextDelayMs = if (index >= parts.size) {
                    bubbleRenderer.quietUserMessageVisualTailMs() + 24L
                } else {
                    bubbleRenderer.quietUserMessageStaggerMs
                }
                handler.postDelayed(this, nextDelayMs)
            }
        }
        handler.post(sendNext)
    }
 
    /**
     * 表情包视觉浏览工具。
     *
     * 平时完全不读取图片；只有住户明确输出 [BROWSE_STICKERS:分组名] 时才运行。
     * 每次只生成当前一页（4×5，共 20 张），住户可以继续请求任意页，直到自己决定结束。
     * 中间工具调用不会写入聊天正文，但会作为工具轨迹显示在思考区域。
     */
    private fun resolveVisualStickerBrowse(
        api: ApiHelper,
        baseContext: List<ChatMessage>,
        firstResponse: ApiResponse
    ): ApiResponse {
        var currentResponse = firstResponse
        val thinkingParts = mutableListOf<String>()
        val numberToId = linkedMapOf<Int, String>()
        var activeGroup: String? = null

        fun addThinking(text: String) {
            val cleaned = text.trim()
            if (cleaned.isNotEmpty()) thinkingParts.add(cleaned)
        }

        addThinking(firstResponse.thinking)

        while (true) {
            val request = StickerBrowseSelection.findBrowseRequest(currentResponse.text)
            if (request == null) {
                if (numberToId.isNotEmpty()) {
                    updateStickerBrowseStatus("$friendName 正在输入…")
                }
                return currentResponse.copy(
                    thinking = thinkingParts.joinToString("\n\n"),
                    text = StickerBrowseSelection.resolve(currentResponse.text, numberToId)
                )
            }

            updateStickerBrowseStatus("$friendName 正在查看「${request.group}」表情包…")

            // 只用 applicationContext 读取和生成临时预览，避免把页面对象交给图片处理层。
            val preview = StickerBrowsePreview.buildPage(
                applicationContext,
                request.group,
                request.page
            )
            if (!activeGroup.equals(preview.requestedGroup, ignoreCase = true)) {
                numberToId.clear()
                activeGroup = preview.requestedGroup
            }
            if (preview.imageBase64 != null) {
                numberToId.putAll(preview.numberToId)
            }

            val pageLabel = if (preview.totalPages > 0) {
                "第 ${preview.page}/${preview.totalPages} 页"
            } else {
                "空分类"
            }
            addThinking("🔧 调用表情包工具：查看「${preview.requestedGroup}」$pageLabel")
            updateStickerBrowseStatus(
                if (preview.totalPages > 0) {
                    "$friendName 正在查看「${preview.requestedGroup}」· $pageLabel"
                } else {
                    "$friendName 正在查看「${preview.requestedGroup}」表情包"
                }
            )

            val toolResult = buildStickerToolResult(preview)
            val images = preview.imageBase64?.let { listOf(it) } ?: emptyList()

            // 每一次请求只携带当前页，不把已经看过的所有拼图反复塞回网络请求。
            // 住户想回看某一页时，可以再次调用那一页。
            val followUp = baseContext.toMutableList().apply {
                add(ChatMessage("assistant", currentResponse.text))
                add(ChatMessage("user", toolResult, images))
            }

            currentResponse = api.sendChat(followUp)
            addThinking(currentResponse.thinking)
        }
    }

    private fun buildStickerToolResult(preview: StickerBrowsePreview.Result): String {
        if (preview.totalCount == 0) {
            return """[表情包工具结果]
分类「${preview.requestedGroup}」目前没有图片。
接下来由你自己决定：可以查看其他分类，或自然地完成本轮回复。不要向用户解释后台工具协议。"""
        }

        if (!preview.pageInRange) {
            return """[表情包工具结果]
分类「${preview.requestedGroup}」共有 ${preview.totalPages} 页，你请求的第 ${preview.requestedPage} 页不存在。
接下来由你自己决定：可以调用 [BROWSE_STICKERS:${preview.requestedGroup}:有效页码]，也可以自然地完成本轮回复。不要向用户解释后台工具协议。"""
        }

        if (preview.imageBase64 == null) {
            return """[表情包工具结果]
「${preview.requestedGroup}」第 ${preview.page}/${preview.totalPages} 页的预览生成失败，当前没有可供你查看的图片。
接下来由你自己决定：可以重试这一页、查看其他页或分类，也可以自然地完成本轮回复。不要向用户解释后台工具协议。"""
        }

        val range = "${preview.firstNumber}-${preview.lastNumber}"
        return """[表情包工具结果]
你正在查看「${preview.requestedGroup}」第 ${preview.page}/${preview.totalPages} 页，本页编号为 $range，共 ${preview.totalCount} 张。
这是一项按需工具，接下来完全由你决定：
- 继续查看任意页：[BROWSE_STICKERS:${preview.requestedGroup}:页码]
- 查看其他分类：[BROWSE_STICKERS:分类名]
- 发送当前或已经看过的任意一张或多张：[STICKER_PICK:编号] 或 [STICKER_PICK:编号,编号,...]
- 不需要表情包时，直接自然地完成本轮回复。
不要输出真实图片 ID，也不要向用户解释编号、预览拼图或后台选择过程。"""
    }


    /**
     * 画匣头像视觉浏览工具。
     *
     * 用户不能替住户选头像；只有住户自己输出 [BROWSE_AVATARS] 时才按需打开画匣头像，
     * 再由住户用 [AVATAR_PICK:编号] 选择已经亲眼看过的图片。
     */
    private fun resolveVisualAvatarBrowse(
        api: ApiHelper,
        baseContext: List<ChatMessage>,
        firstResponse: ApiResponse
    ): ApiResponse {
        var currentResponse = firstResponse
        val thinkingParts = mutableListOf<String>()
        val numberToId = linkedMapOf<Int, String>()
        var activeAlbum: String? = null

        fun addThinking(text: String) {
            val cleaned = text.trim()
            if (cleaned.isNotEmpty()) thinkingParts.add(cleaned)
        }

        addThinking(firstResponse.thinking)

        while (true) {
            val request = AvatarBrowseSelection.findBrowseRequest(currentResponse.text)
            if (request == null) {
                if (numberToId.isNotEmpty()) updateStickerBrowseStatus("$friendName 正在输入…")
                return currentResponse.copy(
                    thinking = thinkingParts.joinToString("\n\n"),
                    text = AvatarBrowseSelection.resolve(currentResponse.text, numberToId)
                )
            }

            updateStickerBrowseStatus("$friendName 正在画匣里挑头像…")
            val preview = AvatarBrowsePreview.buildPage(
                applicationContext,
                request.album,
                request.page
            )
            if (!activeAlbum.equals(preview.requestedAlbum, ignoreCase = true)) {
                numberToId.clear()
                activeAlbum = preview.requestedAlbum
            }
            if (preview.imageBase64 != null) numberToId.putAll(preview.numberToId)

            val pageLabel = if (preview.totalPages > 0) {
                "第 ${preview.page}/${preview.totalPages} 页"
            } else {
                "空分类"
            }
            addThinking("🔧 打开画匣头像：${preview.requestedAlbum} · $pageLabel")
            updateStickerBrowseStatus(
                if (preview.totalPages > 0) {
                    "$friendName 正在挑头像 · $pageLabel"
                } else {
                    "$friendName 正在查看画匣头像"
                }
            )

            val toolResult = buildAvatarToolResult(preview)
            val images = preview.imageBase64?.let { listOf(it) } ?: emptyList()
            val followUp = baseContext.toMutableList().apply {
                add(ChatMessage("assistant", currentResponse.text))
                add(ChatMessage("user", toolResult, images))
            }
            currentResponse = api.sendChat(followUp)
            addThinking(currentResponse.thinking)
        }
    }

    private fun buildAvatarToolResult(preview: AvatarBrowsePreview.Result): String {
        val albumList = preview.availableAlbums.takeIf { it.isNotEmpty() }
            ?.joinToString("、")
            ?: "暂无内部分类"

        if (!preview.albumExists) {
            return """[画匣头像工具结果]
没有找到头像分类「${preview.requestedAlbum}」。现有内部分类：$albumList。
接下来由你自己决定：可以调用 [BROWSE_AVATARS] 查看全部头像，查看其他分类，或自然地完成本轮回复。不要向用户解释后台工具协议。"""
        }

        if (preview.totalCount == 0) {
            return """[画匣头像工具结果]
头像分类「${preview.requestedAlbum}」目前没有图片。现有内部分类：$albumList。
接下来由你自己决定：可以查看其他分类，继续使用当前头像，或自然地完成本轮回复。不要向用户解释后台工具协议。"""
        }

        if (!preview.pageInRange) {
            return """[画匣头像工具结果]
头像分类「${preview.requestedAlbum}」共有 ${preview.totalPages} 页，你请求的第 ${preview.requestedPage} 页不存在。
可以调用 [BROWSE_AVATARS:${preview.requestedAlbum}:有效页码]，也可以自然地完成本轮回复。不要向用户解释后台工具协议。"""
        }

        if (preview.imageBase64 == null) {
            return """[画匣头像工具结果]
「${preview.requestedAlbum}」第 ${preview.page}/${preview.totalPages} 页预览生成失败。
可以重试、查看其他页，或继续使用当前头像。不要向用户解释后台工具协议。"""
        }

        val range = "${preview.firstNumber}-${preview.lastNumber}"
        return """[画匣头像工具结果]
你正在查看头像「${preview.requestedAlbum}」第 ${preview.page}/${preview.totalPages} 页，本页编号为 $range，共 ${preview.totalCount} 张。
接下来完全由你决定：
- 继续查看任意页：[BROWSE_AVATARS:${preview.requestedAlbum}:页码]
- 查看全部头像：[BROWSE_AVATARS]
- 查看其他内部分类：[BROWSE_AVATARS:分类名]
- 选择已经看过的一张作为自己的头像：[AVATAR_PICK:编号]
- 不换头像时，直接自然地完成本轮回复。
不要输出真实图片 ID，也不要向用户解释编号、拼图或后台选择过程。"""
    }

    /**
     * 画匣头像框视觉浏览工具。
     *
     * 住户只能替自己挑选或摘下头像框；用户头像框使用完全独立的存储键，
     * 这里的临时编号永远不会映射到用户侧设置。
     */
    private fun resolveVisualAvatarFrameBrowse(
        api: ApiHelper,
        baseContext: List<ChatMessage>,
        firstResponse: ApiResponse
    ): ApiResponse {
        var currentResponse = firstResponse
        val thinkingParts = mutableListOf<String>()
        val numberToId = linkedMapOf<Int, String>()
        var activeAlbum: String? = null

        fun addThinking(text: String) {
            val cleaned = text.trim()
            if (cleaned.isNotEmpty()) thinkingParts.add(cleaned)
        }

        addThinking(firstResponse.thinking)

        while (true) {
            val request = AvatarFrameBrowseSelection.findBrowseRequest(currentResponse.text)
            if (request == null) {
                if (numberToId.isNotEmpty()) updateStickerBrowseStatus("$friendName 正在输入…")
                return currentResponse.copy(
                    thinking = thinkingParts.joinToString("\n\n"),
                    text = AvatarFrameBrowseSelection.resolve(currentResponse.text, numberToId)
                )
            }

            updateStickerBrowseStatus("$friendName 正在画匣里挑头像框…")
            val preview = AvatarFrameBrowsePreview.buildPage(
                applicationContext,
                request.album,
                request.page
            )
            if (!activeAlbum.equals(preview.requestedAlbum, ignoreCase = true)) {
                numberToId.clear()
                activeAlbum = preview.requestedAlbum
            }
            if (preview.imageBase64 != null) numberToId.putAll(preview.numberToId)

            val pageLabel = if (preview.totalPages > 0) {
                "第 ${preview.page}/${preview.totalPages} 页"
            } else {
                "空分类"
            }
            addThinking("🔧 打开画匣头像框：${preview.requestedAlbum} · $pageLabel")
            updateStickerBrowseStatus(
                if (preview.totalPages > 0) {
                    "$friendName 正在挑头像框 · $pageLabel"
                } else {
                    "$friendName 正在查看画匣头像框"
                }
            )

            val toolResult = buildAvatarFrameToolResult(preview)
            val images = preview.imageBase64?.let { listOf(it) } ?: emptyList()
            val followUp = baseContext.toMutableList().apply {
                add(ChatMessage("assistant", currentResponse.text))
                add(ChatMessage("user", toolResult, images))
            }
            currentResponse = api.sendChat(followUp)
            addThinking(currentResponse.thinking)
        }
    }

    private fun buildAvatarFrameToolResult(preview: AvatarFrameBrowsePreview.Result): String {
        val albumList = preview.availableAlbums.takeIf { it.isNotEmpty() }
            ?.joinToString("、")
            ?: "暂无内部分类"

        if (!preview.albumExists) {
            return """[画匣头像框工具结果]
没有找到头像框分类「${preview.requestedAlbum}」。现有内部分类：$albumList。
接下来由你自己决定：可以调用 [BROWSE_AVATAR_FRAMES] 查看全部头像框，查看其他分类，或自然地完成本轮回复。不要向用户解释后台工具协议。"""
        }

        if (preview.totalCount == 0) {
            return """[画匣头像框工具结果]
头像框分类「${preview.requestedAlbum}」目前没有图片。现有内部分类：$albumList。
接下来由你自己决定：可以查看其他分类、继续佩戴当前头像框、摘掉头像框，或自然地完成本轮回复。不要向用户解释后台工具协议。"""
        }

        if (!preview.pageInRange) {
            return """[画匣头像框工具结果]
头像框分类「${preview.requestedAlbum}」共有 ${preview.totalPages} 页，你请求的第 ${preview.requestedPage} 页不存在。
可以调用 [BROWSE_AVATAR_FRAMES:${preview.requestedAlbum}:有效页码]，也可以自然地完成本轮回复。不要向用户解释后台工具协议。"""
        }

        if (preview.imageBase64 == null) {
            return """[画匣头像框工具结果]
「${preview.requestedAlbum}」第 ${preview.page}/${preview.totalPages} 页预览生成失败。
可以重试、查看其他页，或继续佩戴当前头像框。不要向用户解释后台工具协议。"""
        }

        val range = "${preview.firstNumber}-${preview.lastNumber}"
        return """[画匣头像框工具结果]
你正在查看头像框「${preview.requestedAlbum}」第 ${preview.page}/${preview.totalPages} 页，本页编号为 $range，共 ${preview.totalCount} 张。拼图中的星形头像只是帮助你观察透明边框，不是实际头像。
接下来完全由你决定：
- 继续查看任意页：[BROWSE_AVATAR_FRAMES:${preview.requestedAlbum}:页码]
- 查看全部头像框：[BROWSE_AVATAR_FRAMES]
- 查看其他内部分类：[BROWSE_AVATAR_FRAMES:分类名]
- 佩戴已经看过的一张：[AVATAR_FRAME_PICK:编号]
- 摘掉当前头像框：[CLEAR_AVATAR_FRAME]
- 不换头像框时，直接自然地完成本轮回复。
不要输出真实图片 ID，也不要向用户解释编号、拼图或后台选择过程。"""
    }

    /**
     * 画匣背景视觉浏览工具。
     *
     * 住户可以自行查看画匣“背景”分类，并用临时编号选择当前聊天背景。
     * 真实画匣 ID 不会暴露给模型或用户。
     */
    private fun resolveVisualBackgroundBrowse(
        api: ApiHelper,
        baseContext: List<ChatMessage>,
        firstResponse: ApiResponse
    ): ApiResponse {
        var currentResponse = firstResponse
        val thinkingParts = mutableListOf<String>()
        val numberToId = linkedMapOf<Int, String>()
        var activeAlbum: String? = null

        fun addThinking(text: String) {
            val cleaned = text.trim()
            if (cleaned.isNotEmpty()) thinkingParts.add(cleaned)
        }

        addThinking(firstResponse.thinking)

        while (true) {
            val request = BackgroundBrowseSelection.findBrowseRequest(currentResponse.text)
            if (request == null) {
                if (numberToId.isNotEmpty()) updateStickerBrowseStatus("$friendName 正在输入…")
                return currentResponse.copy(
                    thinking = thinkingParts.joinToString("\n\n"),
                    text = BackgroundBrowseSelection.resolve(currentResponse.text, numberToId)
                )
            }

            updateStickerBrowseStatus("$friendName 正在画匣里挑聊天背景…")
            val preview = BackgroundBrowsePreview.buildPage(
                applicationContext,
                request.album,
                request.page
            )
            if (!activeAlbum.equals(preview.requestedAlbum, ignoreCase = true)) {
                numberToId.clear()
                activeAlbum = preview.requestedAlbum
            }
            if (preview.imageBase64 != null) numberToId.putAll(preview.numberToId)

            val pageLabel = if (preview.totalPages > 0) {
                "第 ${preview.page}/${preview.totalPages} 页"
            } else {
                "空分类"
            }
            addThinking("🔧 打开画匣背景：${preview.requestedAlbum} · $pageLabel")
            updateStickerBrowseStatus(
                if (preview.totalPages > 0) {
                    "$friendName 正在挑聊天背景 · $pageLabel"
                } else {
                    "$friendName 正在查看画匣背景"
                }
            )

            val toolResult = buildBackgroundToolResult(preview)
            val images = preview.imageBase64?.let { listOf(it) } ?: emptyList()
            val followUp = baseContext.toMutableList().apply {
                add(ChatMessage("assistant", currentResponse.text))
                add(ChatMessage("user", toolResult, images))
            }
            currentResponse = api.sendChat(followUp)
            addThinking(currentResponse.thinking)
        }
    }

    private fun buildBackgroundToolResult(preview: BackgroundBrowsePreview.Result): String {
        val albumList = preview.availableAlbums.takeIf { it.isNotEmpty() }
            ?.joinToString("、")
            ?: "暂无内部分类"

        if (!preview.albumExists) {
            return """[画匣背景工具结果]
没有找到背景分类「${preview.requestedAlbum}」。现有内部分类：$albumList。
接下来由你自己决定：可以调用 [BROWSE_BACKGROUNDS] 查看全部背景，查看其他分类，或自然地完成本轮回复。不要向用户解释后台工具协议。"""
        }

        if (preview.totalCount == 0) {
            return """[画匣背景工具结果]
背景分类「${preview.requestedAlbum}」目前没有图片。现有内部分类：$albumList。
接下来由你自己决定：可以查看其他分类，继续使用当前背景，或自然地完成本轮回复。不要向用户解释后台工具协议。"""
        }

        if (!preview.pageInRange) {
            return """[画匣背景工具结果]
背景分类「${preview.requestedAlbum}」共有 ${preview.totalPages} 页，你请求的第 ${preview.requestedPage} 页不存在。
可以调用 [BROWSE_BACKGROUNDS:${preview.requestedAlbum}:有效页码]，也可以自然地完成本轮回复。不要向用户解释后台工具协议。"""
        }

        if (preview.imageBase64 == null) {
            return """[画匣背景工具结果]
「${preview.requestedAlbum}」第 ${preview.page}/${preview.totalPages} 页预览生成失败。
可以重试、查看其他页，或继续使用当前背景。不要向用户解释后台工具协议。"""
        }

        val range = "${preview.firstNumber}-${preview.lastNumber}"
        return """[画匣背景工具结果]
你正在查看背景「${preview.requestedAlbum}」第 ${preview.page}/${preview.totalPages} 页，本页编号为 $range，共 ${preview.totalCount} 张。
接下来完全由你决定：
- 继续查看任意页：[BROWSE_BACKGROUNDS:${preview.requestedAlbum}:页码]
- 查看全部背景：[BROWSE_BACKGROUNDS]
- 查看其他内部分类：[BROWSE_BACKGROUNDS:分类名]
- 选择已经看过的一张作为当前聊天背景：[BACKGROUND_PICK:编号]
- 恢复默认背景：[CLEAR_BACKGROUND]
- 不换背景时，直接自然地完成本轮回复。
不要输出真实图片 ID，也不要向用户解释编号、拼图或后台选择过程。"""
    }

    private fun updateStickerBrowseStatus(message: String) {
        handler.post {
            if (!isFinishing && !isDestroyed) {
                bubbleRenderer.updateTypingIndicator(message)
            }
        }
    }

    /**
     * 调用 API 获取 AI 回复（统一入口）
     *
     * sendMessage、sendMultiMessages、sendAllPending 全走这里。
     * 传了 rollback 参数就在失败时撤回用户消息，没传就只显示错误气泡。
     */
    private fun callApiForReply(
        rollbackView: View? = null,
        rollbackText: String? = null,
        clearSleepInboxOnSuccess: Boolean = false,
        visualReplyNotBeforeUptimeMs: Long = 0L
    ) {
        if (activeReplySession != null) return

        val session = ReplySession(++nextReplySessionId)
        activeReplySession = session
        setReplyInProgress(true)
        setStatus("sending")
        setInputLocked(true)

        val visualDeadlineUptimeMs = maxOf(
            visualReplyNotBeforeUptimeMs,
            SystemClock.uptimeMillis()
        )
        val showTypingRunnable = Runnable {
            if (activeReplySession === session && !session.cancelled && !isFinishing && !isDestroyed) {
                bubbleRenderer.showTypingIndicator()
            }
        }
        session.showTypingRunnable = showTypingRunnable
        val typingDelayMs = (visualDeadlineUptimeMs - SystemClock.uptimeMillis()).coerceAtLeast(0L)
        if (typingDelayMs == 0L) {
            showTypingRunnable.run()
        } else {
            handler.postDelayed(showTypingRunnable, typingDelayMs)
        }

        fun postUiAfterVisualSequence(block: () -> Unit) {
            handler.removeCallbacks(showTypingRunnable)
            val runnable = Runnable {
                session.pendingUiRunnable = null
                if (activeReplySession === session && !session.cancelled) block()
            }
            session.pendingUiRunnable = runnable
            val remainingMs =
                (visualDeadlineUptimeMs - SystemClock.uptimeMillis()).coerceAtLeast(0L)
            if (remainingMs == 0L) handler.post(runnable)
            else handler.postDelayed(runnable, remainingMs)
        }

        val worker = Thread {
            try {
                ensureReplySessionActive(session)
                val api = ApiHelper(apiUrl, apiKey, apiModel, apiType)
                session.api = api
                ensureReplySessionActive(session)

                val contextWindow = buildContextWindow()
                val searchCoordinator = SearchCoordinator(applicationContext, friendId)
                session.searchCoordinator = searchCoordinator

                // 用户本轮直接附上网页链接时，归栖先读取正文并把纯资料接在消息后面。
                // 不向住户追加使用要求，也不规定住户如何评价或处理网页内容。
                val directLinkResolution = DirectLinkReadSession.resolve(
                    friendName = friendName,
                    baseContext = contextWindow,
                    coordinator = searchCoordinator,
                    onStatus = { status ->
                        ensureReplySessionActive(session)
                        updateStickerBrowseStatus(status)
                    }
                )
                ensureReplySessionActive(session)
                val contextWithDirectLinks = contextWindow + directLinkResolution.contextMessages
                val firstResponse = api.sendChat(contextWithDirectLinks)
                ensureReplySessionActive(session)

                // 文字型同轮工具允许互相接力：例如先翻看“我眼中的自己”，再决定上网搜索；
                // 或搜索后发现还需要读取用户自述。最多循环三轮，避免不同工具之间形成死循环。
                val textToolContinuation = mutableListOf<ChatMessage>()
                val textToolTraceEvents = mutableListOf<Pair<String, String>>().apply {
                    directLinkResolution.traceEvents.forEach { event ->
                        add(event.kind to event.content)
                    }
                }
                var textToolResponse = firstResponse

                var textToolCycle = 0
                while (textToolCycle < 3) {
                    textToolCycle++
                    val beforeCount = textToolContinuation.size

                    val userLifeResolution = UserLifeReadToolSession.resolve(
                        context = applicationContext,
                        friendName = friendName,
                        baseContext = contextWithDirectLinks + textToolContinuation,
                        firstResponse = textToolResponse,
                        sendChat = { messages ->
                            ensureReplySessionActive(session)
                            api.sendChat(messages).also { ensureReplySessionActive(session) }
                        },
                        onReading = {
                            ensureReplySessionActive(session)
                            updateStickerBrowseStatus("$friendName 正在翻看「我眼中的自己」…")
                        }
                    )
                    ensureReplySessionActive(session)
                    textToolResponse = userLifeResolution.response
                    textToolContinuation += userLifeResolution.continuationMessages
                    userLifeResolution.traceEvents.forEach { event ->
                        textToolTraceEvents.add(event.kind to event.content)
                    }

                    val webResolution = WebSearchToolSession.resolve(
                        friendName = friendName,
                        baseContext = contextWithDirectLinks + textToolContinuation,
                        firstResponse = textToolResponse,
                        coordinator = searchCoordinator,
                        sendChat = { messages ->
                            ensureReplySessionActive(session)
                            api.sendChat(messages).also { ensureReplySessionActive(session) }
                        },
                        onStatus = { status ->
                            ensureReplySessionActive(session)
                            updateStickerBrowseStatus(status)
                        }
                    )
                    ensureReplySessionActive(session)
                    textToolResponse = webResolution.response
                    textToolContinuation += webResolution.continuationMessages
                    webResolution.traceEvents.forEach { event ->
                        textToolTraceEvents.add(event.kind to event.content)
                    }

                    if (textToolContinuation.size == beforeCount) break
                }

                val contextAfterTextTools = contextWithDirectLinks + textToolContinuation
                val stickerResolved = resolveVisualStickerBrowse(
                    api,
                    contextAfterTextTools,
                    textToolResponse
                )
                ensureReplySessionActive(session)
                val avatarResolved = resolveVisualAvatarBrowse(api, contextAfterTextTools, stickerResolved)
                ensureReplySessionActive(session)
                val avatarFrameResolved = resolveVisualAvatarFrameBrowse(api, contextAfterTextTools, avatarResolved)
                ensureReplySessionActive(session)
                val backgroundResolved = resolveVisualBackgroundBrowse(api, contextAfterTextTools, avatarFrameResolved)
                ensureReplySessionActive(session)

                val bubbleToolResolution = BubbleStyleToolSession.resolve(
                    friendName = friendName,
                    friendId = friendId,
                    storage = bubbleStyleStorage,
                    baseContext = contextAfterTextTools,
                    firstResponse = backgroundResolved,
                    sendChat = { messages ->
                        ensureReplySessionActive(session)
                        api.sendChat(messages).also { ensureReplySessionActive(session) }
                    }
                )
                ensureReplySessionActive(session)

                val response = bubbleToolResolution.response
                val replyTime = System.currentTimeMillis()
                val replyTimeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    .format(Date(replyTime))

                // ===== 统一指令解析 =====
                val result = InstructionProcessor(this@ChatConversationActivity).process(friendId, response.text)
                ensureReplySessionActive(session)
                val cleanText = result.cleanText
                val isSeen = result.isSeen

                // 应用状态变更
                if (result.newStatus != null) currentAiStatus = result.newStatus!!
                if (result.newName != null) {
                    friendName = result.newName!!
                    bubbleRenderer.friendName = friendName
                }
                if (result.newIcon != null) {
                    friendIcon = result.newIcon!!
                    bubbleRenderer.friendIcon = friendIcon
                }
                bubbleRenderer.friendAvatarPath = FriendStorage(this@ChatConversationActivity)
                    .getFriend(friendId)?.avatarPath ?: ""
                if (result.userBioContext != null) {
                    chatHistory.add(ChatMessage("system", result.userBioContext!!))
                }
                result.userBioPhotos.forEach { photo ->
                    ensureReplySessionActive(session)
                    val file = File(photo.path)
                    if (file.isFile) {
                        try {
                            chatHistory.add(
                                ChatMessage(
                                    role = "user",
                                    content = "[用户在「我眼中的自己」里保存的照片：${photo.label}]",
                                    imageBase64List = listOf(ImageHelper.toBase64(file))
                                )
                            )
                        } catch (_: Exception) {
                            // 单张照片读取失败不影响其余自述和生活记录。
                        }
                    }
                }
                for (recall in result.recallResults) {
                    chatHistory.add(ChatMessage("system", "[留声] $recall"))
                }
                if (result.shouldDream) triggerDream(friendId)
                ensureReplySessionActive(session)

                // ★ 工具会话按真实顺序保存：可见思考片段 → 工具调用 → 可见思考片段。
                val combinedToolTraceEvents = buildList {
                    addAll(textToolTraceEvents)
                    bubbleToolResolution.traceEvents.forEach { event ->
                        val kind = when (event.kind) {
                            BubbleStyleToolSession.TraceKind.THINKING -> "思考"
                            BubbleStyleToolSession.TraceKind.BUBBLE_TOOL -> "代码气泡"
                        }
                        add(kind to event.content)
                    }
                    if (
                        textToolTraceEvents.isNotEmpty() &&
                        bubbleToolResolution.traceEvents.isEmpty() &&
                        response.thinking.isNotBlank()
                    ) {
                        add("思考" to response.thinking)
                    }
                }
                val toolTraceRecords = combinedToolTraceEvents.mapIndexed { index, event ->
                    "[工具轨迹·${event.first}·${index + 1}/${combinedToolTraceEvents.size}]\n${event.second.trim()}"
                }

                ensureReplySessionActive(session)
                toolTraceRecords.forEachIndexed { index, record ->
                    chatStorage.appendMessage(
                        friendId,
                        StoredMessage("system", record, replyTime + index, type = "tip")
                    )
                }
                for ((index, action) in result.actions.withIndex()) {
                    chatStorage.appendMessage(
                        friendId,
                        StoredMessage(
                            "system",
                            action,
                            replyTime + toolTraceRecords.size + index,
                            type = "tip"
                        )
                    )
                }

                val msgType = if (result.weatherCard) "weather" else "text"
                val msgExtras = if (result.weatherCard) {
                    val ws = WeatherStorage(this@ChatConversationActivity)
                    val wd = ws.getCachedWeather()
                    val city = ws.getCity()
                    if (wd != null) WeatherStorage.toExtras(wd, city) else ""
                } else {
                    ""
                }
                val assistantTimestamp = replyTime + toolTraceRecords.size + result.actions.size

                fun markReplyProcessed() {
                    synchronized(session) {
                        if (session.replyProcessed) return
                        session.replyProcessed = true
                        if (clearSleepInboxOnSuccess) sleepMessageStorage.clear(friendId)

                        val msgCount = chatStorage.getMessageCount(friendId)
                        if (summaryStorage.shouldTriggerSummary(friendId, msgCount)) {
                            triggerChatSummary(friendId, msgCount)
                        }
                    }
                }

                fun persistAssistant(visibleText: String) {
                    if (visibleText.isNotBlank()) {
                        synchronized(session) {
                            if (!session.assistantPersisted) {
                                session.assistantPersisted = true
                                chatStorage.appendMessage(
                                    friendId,
                                    StoredMessage(
                                        "assistant",
                                        visibleText,
                                        assistantTimestamp,
                                        response.thinking,
                                        type = msgType,
                                        extras = msgExtras
                                    )
                                )
                                chatHistory.add(ChatMessage("assistant", visibleText))
                            }
                        }
                    }
                    markReplyProcessed()
                }

                postUiAfterVisualSequence uiResult@ {
                    if (isFinishing || isDestroyed) {
                        persistAssistant(cleanText)
                        if (!isSeen && cleanText.isNotBlank()) {
                            NotificationHelper(applicationContext).sendChatNotification(
                                friendId, friendName, friendIcon, cleanText
                            )
                        }
                        activeReplySession = null
                        return@uiResult
                    }

                    bubbleRenderer.removeTypingIndicator()
                    setStatus("online")

                    if (currentAiStatus.isNotEmpty()) {
                        tvStatus.text = currentAiStatus
                        tvStatus.setTextColor(c.accent)
                        getSharedPreferences("haven_status", MODE_PRIVATE)
                            .edit().putString("status_$friendId", currentAiStatus).apply()
                    }
                    tvFriendName.text = friendName
                    if (result.chatAppearanceChanged) applyChatAppearance(force = true)

                    for (record in toolTraceRecords) bubbleRenderer.addToolTraceRecord(record)
                    for (action in result.actions) bubbleRenderer.addSystemTip(action)
                    result.pendingBadge?.let { badgeUnlockDialog.show(it) }

                    if (isSeen) {
                        markReplyProcessed()
                        bubbleRenderer.addSeenIndicator()
                        result.pendingCovenantDraft?.let { draft ->
                            showCovenantDraftRequest(draft, result.pendingCovenantAdopt)
                        }
                        finishReplySession(session)
                        return@uiResult
                    }

                    if (toolTraceRecords.isEmpty() && response.thinking.isNotEmpty()) {
                        bubbleRenderer.addThinkingBlock(response.thinking)
                    }
                    if (result.weatherCard) {
                        val ws = WeatherStorage(this@ChatConversationActivity)
                        val wd = ws.getCachedWeather()
                        val city = ws.getCity()
                        if (wd != null && city.isNotEmpty()) {
                            bubbleRenderer.addWeatherCard(wd, city, isUser = false, timeStr = replyTimeStr)
                        }
                    }

                    session.interruptFinalizer = { partialText ->
                        persistAssistant(partialText)
                    }
                    bubbleRenderer.addAiBubbleStreaming(cleanText, replyTimeStr) { completedText ->
                        if (activeReplySession !== session || session.cancelled) return@addAiBubbleStreaming
                        session.interruptFinalizer = null
                        persistAssistant(completedText)
                        if (completedText.isNotBlank()) {
                            NotificationHelper(this@ChatConversationActivity).sendChatNotification(
                                friendId, friendName, friendIcon, completedText
                            )
                        }
                        result.pendingCovenantDraft?.let { draft ->
                            showCovenantDraftRequest(draft, result.pendingCovenantAdopt)
                        }
                        finishReplySession(session)
                    }
                }
            } catch (e: Exception) {
                if (session.cancelled || e is ApiRequestCancelledException) {
                    handler.post {
                        if (activeReplySession === session) finishReplySession(session)
                    }
                    return@Thread
                }

                val friendlyMsg = getErrorMessage(e)
                postUiAfterVisualSequence uiError@ {
                    if (isFinishing || isDestroyed) {
                        activeReplySession = null
                        return@uiError
                    }
                    if (activeReplySession !== session || session.cancelled) return@uiError

                    bubbleRenderer.removeTypingIndicator()
                    finishReplySession(session)
                    if (rollbackView != null) {
                        messagesContainer.removeView(rollbackView)
                        if (chatHistory.isNotEmpty()) chatHistory.removeAt(chatHistory.size - 1)
                        chatStorage.removeLastMessage(friendId)
                        if (rollbackText != null) inputMessage.setText(rollbackText)
                    }
                    setStatus("error", friendlyMsg)
                    bubbleRenderer.addErrorBubble(friendlyMsg) { callApiForReply() }
                }
            }
        }
        session.worker = worker
        worker.start()
    }

    private fun hasApiConfig(): Boolean {
        return apiUrl.isNotEmpty() && apiKey.isNotEmpty() && apiModel.isNotEmpty()
    }

    private fun isSleepDndActive(): Boolean {
        val settings = ResidentPromptStorage(this)
            .getProfile(friendId)
            .runtimeSettings
        return settings.dndMode != ResidentDndMode.OFF ||
            settings.sleepMessagePolicy == ResidentSleepMessagePolicy.HOLD
    }

    private fun isEmergencyWakeAllowed(): Boolean {
        return ResidentPromptStorage(this)
            .getProfile(friendId)
            .runtimeSettings
            .emergencyCanWake
    }

    private fun showEmergencyWakeDialog(onConfirm: () -> Unit) {
        if (!isEmergencyWakeAllowed()) {
            Toast.makeText(this, "$friendName 关闭了紧急唤醒", Toast.LENGTH_SHORT).show()
            return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("紧急唤醒 $friendName？")
            .setMessage("会立刻结束这次睡眠，并把床边暂存的消息一起交给他。")
            .setNegativeButton("算了", null)
            .setPositiveButton("唤醒并发送") { _, _ -> onConfirm() }
            .show()
    }

    private fun holdMessagesForSleepingResident(
        messages: List<Pair<String, Long>>,
        firstTip: String? = null
    ) {
        if (messages.isEmpty()) return
        val sleepAt = dreamStorage.getSleepTime(friendId)
        try {
            val hadPending = sleepMessageStorage.hasPending(friendId)
            var count = 0
            for ((content, timestamp) in messages) {
                count = sleepMessageStorage.add(friendId, sleepAt, content, timestamp)
            }
            if (!hadPending && firstTip != null) {
                addAndSaveSystemTip(firstTip)
            }
            Toast.makeText(this, "已留在床边（$count 条）", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            // 正文已经先写入 ChatStorage；即使轻量索引失败，也不能让消息或页面一起崩掉。
            Toast.makeText(this, "消息已保存，但床边留言计数暂时失败", Toast.LENGTH_SHORT).show()
        } finally {
            setInputLocked(false)
        }
    }

    /** 处理“消息发出时住户仍在睡觉”的分支。 */
    private fun handleSleepingDelivery(
        messages: List<Pair<String, Long>>,
        emergencyWake: Boolean,
        onWake: () -> Unit
    ) {
        // 自然醒闹钟可能恰好在发送瞬间触发；这时直接按醒着处理，不能把消息卡住。
        if (!dreamStorage.isSleeping(friendId)) {
            onWake()
            return
        }

        if (emergencyWake) {
            if (!isEmergencyWakeAllowed()) {
                holdMessagesForSleepingResident(
                    messages,
                    "💤 $friendName 仍在休息，消息已留在床边"
                )
                return
            }
            dreamStorage.forceWake(friendId)
            addAndSaveSystemTip("⚠️ 紧急唤醒了 $friendName，床边留言会一起交给他")
            onWake()
            return
        }

        if (isSleepDndActive()) {
            holdMessagesForSleepingResident(
                messages,
                "🔕 $friendName 这次睡眠开启了免打扰，普通消息已留在床边；需要马上处理时可长按发送键紧急唤醒"
            )
            return
        }

        // 普通睡眠仍走原来的睡眠深度判断：可能被叫醒，也可能暂时叫不醒。
        // 没有 API 时不把人叫醒后晾着，先把消息稳稳留在床边。
        if (!hasApiConfig()) {
            holdMessagesForSleepingResident(
                messages,
                "💤 $friendName 正在休息，消息已留在床边"
            )
            return
        }

        val (wakeResult, wakeTip) = dreamStorage.tryWake(friendId)
        if (wakeTip != null) addAndSaveSystemTip(wakeTip)
        if (wakeResult == "too_deep") {
            holdMessagesForSleepingResident(messages)
        } else {
            onWake()
        }
    }

    // ===== 发送消息（文字、图片、或图片+文字） =====
    private fun sendMessage(emergencyWake: Boolean = false) {
        val msg = inputMessage.text.toString().trim()
        val imagePaths = chatImageHandler.pendingPaths.toList()  // API 侧图片快照
        val stickerDisplayPath = pendingStickerDisplayPath?.takeIf {
            pendingStickerApiPath == imagePaths.singleOrNull() && File(it).isFile
        }
        val isStickerSend = stickerDisplayPath != null

        // 分条模式：文字和图片都蹦到待发区，不真正发送。
        // 紧急唤醒整批消息请长按“发送全部”，避免只发出输入框里这一小段。
        if (batchModeManager.isBatchMode) {
            if (emergencyWake) {
                Toast.makeText(this, "分条模式下请长按“发送全部”紧急唤醒", Toast.LENGTH_SHORT).show()
                return
            }
            if (imagePaths.isNotEmpty()) {
                val caption = if (msg.isNotEmpty()) msg else ""
                batchModeManager.addImage(
                    paths = imagePaths,
                    caption = caption,
                    displayPaths = if (isStickerSend) listOf(stickerDisplayPath!!) else imagePaths,
                    isSticker = isStickerSend
                )
                inputMessage.text.clear()
                chatImageHandler.clear()
                pendingStickerDisplayPath = null
                pendingStickerApiPath = null
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

        if (msg.isEmpty() && imagePaths.isEmpty()) return

        val sleepingAtSend = dreamStorage.isSleeping(friendId)
        // 睡着时的普通消息可以先保存；真正要唤醒/回复才必须有 API。
        if ((!sleepingAtSend || emergencyWake) && !hasApiConfig()) {
            Toast.makeText(this, "请先去设置页配置 API", Toast.LENGTH_SHORT).show()
            return
        }

        inputMessage.text.clear()
        chatImageHandler.clear()
        pendingStickerDisplayPath = null
        pendingStickerApiPath = null

        // 如果发图片就不带引用了
        if (imagePaths.isNotEmpty()) removeQuotePreview()

        val now = System.currentTimeMillis()
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(now))
        checkDateSeparator(now)
        bubbleRenderer.prepareNextUserMessageAnimation()

        var heldContent = msg

        if (imagePaths.isNotEmpty()) {
            if (isStickerSend) {
                bubbleRenderer.addStickerBubble(stickerDisplayPath!!, timeStr, msg)
                chatStorage.appendMessage(
                    friendId,
                    StoredMessage(
                        "user",
                        if (msg.isNotEmpty()) msg else "[表情包]",
                        now,
                        imagePath = stickerDisplayPath,
                        type = "sticker"
                    )
                )
            } else {
                if (imagePaths.size == 1) {
                    bubbleRenderer.addImageBubble(imagePaths[0], timeStr, msg)
                } else {
                    bubbleRenderer.addMultiImageBubble(imagePaths, timeStr, msg)
                }

                val displayContent = if (msg.isNotEmpty()) msg else {
                    if (imagePaths.size == 1) "[图片]" else "[${imagePaths.size}张图片]"
                }
                val extrasJson = if (imagePaths.size > 1) {
                    JSONObject().apply { put("paths", JSONArray(imagePaths)) }.toString()
                } else ""
                chatStorage.appendMessage(friendId, StoredMessage(
                    "user", displayContent, now, imagePath = imagePaths[0],
                    type = "image", extras = extrasJson
                ))
            }

            val base64List = imagePaths.map { ImageHelper.toBase64(File(it)) }
            // AI 侧保持原有图片消息语义；表情包只在本地改显示方式。
            val apiContent = if (msg.isNotEmpty()) msg else {
                "[用户发送了${if (imagePaths.size > 1) "${imagePaths.size}张" else "一张"}图片]"
            }
            chatHistory.add(ChatMessage("user", apiContent, base64List))
            heldContent = if (msg.isNotEmpty()) {
                "$msg\n[同时发送了${if (imagePaths.size > 1) "${imagePaths.size}张" else "一张"}图片]"
            } else {
                "[发送了${if (imagePaths.size > 1) "${imagePaths.size}张" else "一张"}图片]"
            }

        } else {
            val quoteAuthor = pendingQuoteAuthor
            val quoteContent = pendingQuoteContent

            if (quoteAuthor != null && quoteContent != null) {
                bubbleRenderer.addQuoteBubble(quoteAuthor, quoteContent, msg, timeStr)
                val shortQuote = if (quoteContent.length > 50) quoteContent.substring(0, 50) + "..." else quoteContent
                val quoteExtras = JSONObject().apply {
                    put("quote_author", quoteAuthor)
                    put("quote_content", shortQuote)
                }.toString()
                chatStorage.appendMessage(friendId, StoredMessage("user", msg, now, type = "quote", extras = quoteExtras))
                chatHistory.add(ChatMessage("user", "[$timeStr] [引用 $quoteAuthor 说的: $shortQuote]\n$msg"))
                heldContent = "回复 $quoteAuthor「$shortQuote」：$msg"
                removeQuotePreview()
            } else {
                bubbleRenderer.addUserBubble(msg, timeStr)
                chatStorage.appendMessage(friendId, StoredMessage("user", msg, now))
                chatHistory.add(ChatMessage("user", "[$timeStr] $msg"))
                heldContent = msg
            }
        }

        // 必须在插入任何系统提示前抓住用户气泡，否则 API 失败回滚时会删错对象。
        val userBubbleView = messagesContainer.getChildAt(messagesContainer.childCount - 1)
        val rollbackText = if (imagePaths.isEmpty()) msg else null

        if (sleepingAtSend) {
            handleSleepingDelivery(
                messages = listOf(heldContent to now),
                emergencyWake = emergencyWake
            ) {
                callApiForReply(
                    rollbackView = userBubbleView,
                    rollbackText = rollbackText,
                    clearSleepInboxOnSuccess = true
                )
            }
            return
        }

        callApiForReply(
            rollbackView = userBubbleView,
            rollbackText = rollbackText,
            clearSleepInboxOnSuccess = sleepMessageStorage.hasPending(friendId)
        )
    }

    // ===== 显示引用预览条 =====
    private fun showQuotePreview(author: String, content: String) {
        removeQuotePreview()
        pendingQuoteAuthor = author
        pendingQuoteContent = content
        val preview = bubbleRenderer.buildQuotePreview(author, content) { removeQuotePreview() }
        quotePreviewContainer.addView(preview)
        quotePreviewContainer.visibility = View.VISIBLE
        expandComposer(showKeyboard = true)
    }

    private fun removeQuotePreview() {
        pendingQuoteAuthor = null
        pendingQuoteContent = null
        quotePreviewContainer.removeAllViews()
        quotePreviewContainer.visibility = View.GONE
    }

    /** 加号菜单 */
    /**
     * 发送全部待发消息。
     *
     * v8.2：点击“发送全部”后，先把整批消息完整上屏、落盘并写入上下文，
     * 再请求回复。入场动画只延迟视觉出现，不延迟落盘和上下文写入，
     * 所以即使马上离开当前聊天页，也不会漏发后半段。
     */
    private fun sendAllPending(emergencyWake: Boolean = false) {
        if (batchModeManager.isEmpty()) return

        val sleepingAtSend = dreamStorage.isSleeping(friendId)
        if ((!sleepingAtSend || emergencyWake) && !hasApiConfig()) {
            Toast.makeText(this, "请先去设置页配置 API", Toast.LENGTH_SHORT).show()
            return
        }

        setInputLocked(true)
        val items = batchModeManager.getItemsAndClear()
        removeQuotePreview()

        var animatableItemIndex = 0
        var visualReplyNotBeforeUptimeMs = 0L
        fun preparePendingItemAnimation(
            mediaItemCount: Int = 0,
            hasCaption: Boolean = false
        ) {
            val startDelayMs = animatableItemIndex * bubbleRenderer.quietUserMessageStaggerMs
            bubbleRenderer.prepareNextUserMessageAnimation(
                startDelayMs = startDelayMs,
                showAvatar = true
            )
            visualReplyNotBeforeUptimeMs = maxOf(
                visualReplyNotBeforeUptimeMs,
                SystemClock.uptimeMillis() +
                    startDelayMs +
                    bubbleRenderer.quietUserMessageVisualTailMs(mediaItemCount, hasCaption) +
                    24L
            )
            animatableItemIndex++
        }

        val allTextForApi = StringBuilder()
        val heldMessages = mutableListOf<Pair<String, Long>>()
        val baseTime = System.currentTimeMillis()
        var hasPayload = false

        for ((index, item) in items.withIndex()) {
            val now = baseTime + index
            val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(now))
            checkDateSeparator(now)

            if (item.quoteAuthor != null && item.quoteContent != null) {
                allTextForApi.append("[引用 ${item.quoteAuthor}: ${item.quoteContent}]\n")
            }

            when (item.type) {
                "image" -> {
                    if (item.imagePaths.isEmpty()) continue
                    val displayPaths = item.displayImagePaths.ifEmpty { item.imagePaths }
                    preparePendingItemAnimation(
                        mediaItemCount = displayPaths.size,
                        hasCaption = item.text.isNotEmpty()
                    )
                    if (item.isSticker && displayPaths.size == 1) {
                        bubbleRenderer.addStickerBubble(displayPaths[0], timeStr, item.text)
                        chatStorage.appendMessage(
                            friendId,
                            StoredMessage(
                                "user",
                                if (item.text.isNotEmpty()) item.text else "[表情包]",
                                now,
                                imagePath = displayPaths[0],
                                type = "sticker"
                            )
                        )
                    } else {
                        if (displayPaths.size == 1) {
                            bubbleRenderer.addImageBubble(displayPaths[0], timeStr, item.text)
                        } else {
                            bubbleRenderer.addMultiImageBubble(displayPaths, timeStr, item.text)
                        }
                        val display = if (item.text.isNotEmpty()) item.text else "[${displayPaths.size}张图片]"
                        val extras = if (displayPaths.size > 1) {
                            JSONObject().apply { put("paths", JSONArray(displayPaths)) }.toString()
                        } else ""
                        chatStorage.appendMessage(
                            friendId,
                            StoredMessage(
                                "user",
                                display,
                                now,
                                imagePath = displayPaths[0],
                                type = "image",
                                extras = extras
                            )
                        )
                    }
                    chatHistory.add(
                        ChatMessage(
                            "user",
                            if (item.text.isNotEmpty()) item.text else "[用户发送了图片]",
                            item.imagePaths.map { ImageHelper.toBase64(File(it)) }
                        )
                    )
                    if (item.text.isNotEmpty()) allTextForApi.append(item.text).append("\n")
                    val held = if (item.text.isNotEmpty()) {
                        "${item.text}\n[同时发送了${item.imagePaths.size}张图片]"
                    } else {
                        "[发送了${item.imagePaths.size}张图片]"
                    }
                    heldMessages.add(held to now)
                    hasPayload = true
                }

                "weather" -> {
                    val ws = WeatherStorage(this)
                    val summary = ws.buildWeatherSummary()
                    if (summary.isNotEmpty() && weatherCardManager.renderAndStore(now)) {
                        allTextForApi.append(summary).append("\n")
                        heldMessages.add(summary to now)
                        hasPayload = true
                    }
                }

                else -> {
                    if (item.text.isEmpty()) continue
                    preparePendingItemAnimation()
                    val held = if (item.quoteAuthor != null && item.quoteContent != null) {
                        bubbleRenderer.addQuoteBubble(item.quoteAuthor, item.quoteContent, item.text, timeStr)
                        chatStorage.appendMessage(
                            friendId,
                            StoredMessage(
                                "user",
                                "「回复 ${item.quoteAuthor}」\n${item.text}",
                                now,
                                type = "quote"
                            )
                        )
                        "回复 ${item.quoteAuthor}「${item.quoteContent}」：${item.text}"
                    } else {
                        bubbleRenderer.addUserBubble(item.text, timeStr)
                        chatStorage.appendMessage(friendId, StoredMessage("user", item.text, now))
                        item.text
                    }
                    allTextForApi.append(item.text).append("\n")
                    heldMessages.add(held to now)
                    hasPayload = true
                }
            }
        }

        if (!hasPayload) {
            setInputLocked(false)
            return
        }

        val textForApi = allTextForApi.toString().trim()
        if (textForApi.isNotEmpty()) {
            chatHistory.add(ChatMessage("user", textForApi))
        }

        if (sleepingAtSend) {
            handleSleepingDelivery(heldMessages, emergencyWake) {
                callApiForReply(
                    clearSleepInboxOnSuccess = true,
                    visualReplyNotBeforeUptimeMs = visualReplyNotBeforeUptimeMs
                )
            }
            return
        }

        callApiForReply(
            clearSleepInboxOnSuccess = sleepMessageStorage.hasPending(friendId),
            visualReplyNotBeforeUptimeMs = visualReplyNotBeforeUptimeMs
        )
    }

    /** 聊天面板导入也直接写入画匣，并允许一次选定内部分类。 */
    private fun showStickerImportGroupDialog() {
        val groups = stickerStorage.loadGroups().map { it.first }.distinct().toMutableList()
        groups.add("＋ 新建分类")

        android.app.AlertDialog.Builder(this)
            .setTitle("放进表情包的哪一组")
            .setItems(groups.toTypedArray()) { _, which ->
                if (which == groups.lastIndex) {
                    val input = EditText(this).apply {
                        hint = "分类名"
                        setPadding(48, 32, 48, 32)
                    }
                    android.app.AlertDialog.Builder(this)
                        .setTitle("新建表情包分类")
                        .setView(input)
                        .setNegativeButton("取消", null)
                        .setPositiveButton("新建并导入") { _, _ ->
                            val name = input.text.toString().trim()
                            when {
                                name.isEmpty() -> Toast.makeText(this, "分类名不能为空", Toast.LENGTH_SHORT).show()
                                stickerStorage.ensureGroup(name) -> {
                                    pendingStickerImportGroup = name
                                    launchStickerPicker()
                                }
                                else -> Toast.makeText(this, "这个分类名不能使用", Toast.LENGTH_SHORT).show()
                            }
                        }
                        .show()
                } else {
                    pendingStickerImportGroup = groups[which]
                    launchStickerPicker()
                }
            }
            .show()
    }

    private fun launchStickerPicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(intent, PICK_STICKER)
    }

    private fun sendSticker(stickerFile: File) {
        // 本地显示保留透明通道；送进 API 的仍是原有白底 JPEG。
        stickerPanelManager.hide()
        Thread {
            val snapshot = StickerSnapshot.createPair(this, stickerFile)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (snapshot == null) {
                    Toast.makeText(this, "表情包读取失败", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                pendingStickerDisplayPath = snapshot.displayFile.absolutePath
                pendingStickerApiPath = snapshot.apiFile.absolutePath
                chatImageHandler.pendingPaths.clear()
                chatImageHandler.pendingPaths.add(snapshot.apiFile.absolutePath)
                sendMessage()
            }
        }.start()
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
    /** 住户提交自己的公约草稿；长草稿放进可滚动正文，操作区始终固定在底部。 */
    private fun showCovenantDraftRequest(
        draft: String,
        adoptAfterSave: Boolean = false
    ) {
        if (isFinishing || isDestroyed) return

        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }
        val dialog = android.app.Dialog(this)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)

        fun roundedBackground(color: Int, radiusDp: Int, strokeColor: Int? = null): android.graphics.drawable.GradientDrawable {
            return android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                setColor(color)
                cornerRadius = dp(radiusDp).toFloat()
                if (strokeColor != null) setStroke(dp(1), strokeColor)
            }
        }

        fun actionButton(
            label: String,
            emphasized: Boolean = false,
            onClick: () -> Unit
        ): TextView {
            return TextView(this).apply {
                text = label
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(if (emphasized) c.textOnAccent else c.textPrimary)
                background = roundedBackground(
                    if (emphasized) c.accent else c.accentBg,
                    14,
                    if (emphasized) null else c.border
                )
                setPadding(dp(10), dp(12), dp(10), dp(12))
                setOnClickListener { onClick() }
            }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(18))
            background = roundedBackground(c.dialogBg, 24, c.dialogBorder)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        titleWrap.addView(TextView(this).apply {
            text = "${friendName}的候选公约"
            textSize = 20f
            setTextColor(c.textPrimary)
        })
        titleWrap.addView(TextView(this).apply {
            text = if (adoptAfterSave) {
                "由住户本人写下；允许保存后将立即采用并留下版本"
            } else {
                "由住户本人写下，只保存草稿，不会立刻改变提示词"
            }
            textSize = 12f
            setTextColor(c.textSecondary)
            setPadding(0, dp(5), 0, 0)
        })
        header.addView(titleWrap, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(this).apply {
            text = "×"
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(c.textSecondary)
            contentDescription = "关闭"
            setPadding(dp(12), dp(4), dp(2), dp(4))
            setOnClickListener { dialog.dismiss() }
        })
        root.addView(header)

        root.addView(View(this).apply { setBackgroundColor(c.divider) }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(1)
        ).apply { setMargins(0, dp(16), 0, dp(8)) })

        val draftText = TextView(this).apply {
            text = draft
            textSize = 14f
            setTextColor(c.textPrimary)
            setLineSpacing(0f, 1.25f)
            setPadding(dp(4), dp(10), dp(12), dp(18))
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(draftText, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        root.addView(View(this).apply { setBackgroundColor(c.divider) }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(1)
        ).apply { setMargins(0, dp(8), 0, dp(14)) })

        fun saveDraft(autoAllow: Boolean) {
            val storage = ResidentPromptStorage(this)
            val before = storage.getProfile(friendId)

            if (autoAllow) {
                storage.setEditPermission(friendId, ResidentPromptEditPermission.ALLOW_RESIDENT)
            }

            storage.saveCovenantDraft(friendId, draft)

            val tips = mutableListOf(
                if (autoAllow) {
                    "📜 保存了自己的居住公约草稿，今后可自行更新草稿"
                } else {
                    "📜 保存了自己的居住公约草稿"
                }
            )

            if (adoptAfterSave) {
                val after = storage.adoptCovenantDraft(friendId)
                val reused = before.activeVersion > 0 &&
                    before.activeCovenant.trim() == draft.trim()
                tips.add(
                    if (reused) {
                        "📜 重新启用了自己的居住公约（版本 ${after.activeVersion}）"
                    } else {
                        "📜 采用了自己的居住公约（版本 ${after.activeVersion}）"
                    }
                )
            }

            var now = System.currentTimeMillis()
            for (tip in tips) {
                chatStorage.appendMessage(friendId, StoredMessage("system", tip, now, type = "tip"))
                bubbleRenderer.addSystemTip(tip)
                now += 1
            }
            dialog.dismiss()
        }

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val primaryRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        primaryRow.addView(actionButton("不保存") { dialog.dismiss() }, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ).apply { setMargins(0, 0, dp(8), 0) })
        primaryRow.addView(actionButton(
            if (adoptAfterSave) "允许并采用" else "允许这次",
            emphasized = true
        ) { saveDraft(false) }, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ))
        actions.addView(primaryRow)
        actions.addView(actionButton(
            if (adoptAfterSave) "以后自行保存；本次保存并采用" else "以后由住户自行保存草稿"
        ) { saveDraft(true) }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, dp(9), 0, 0) })
        root.addView(actions)

        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.48f }
        }
        dialog.show()

        val metrics = resources.displayMetrics
        dialog.window?.setLayout(
            (metrics.widthPixels * 0.92f).toInt(),
            (metrics.heightPixels * 0.82f).toInt()
        )
    }

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