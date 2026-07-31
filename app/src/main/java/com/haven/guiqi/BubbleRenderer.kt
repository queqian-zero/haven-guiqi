package com.haven.guiqi

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.graphics.Outline
import android.graphics.Rect
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * BubbleRenderer — 聊天气泡渲染器
 *
 * 从 ChatConversationActivity 拆出来。
 * 负责所有气泡的创建：用户气泡、AI气泡、图片气泡、多图网格、引用、
 * 系统提示、错误提示、思维链、已读不回、分隔线、流式打字动画、
 * 分条渲染、表情包内联、输入指示器等。
 *
 * Activity 只管调度，这里只管"画"。
 *
 * 使用方式：
 *   val renderer = BubbleRenderer(activity, messagesContainer, chatScrollView)
 *   renderer.friendName = "闺闺"
 *   renderer.friendIcon = "🐱"
 *   renderer.onQuote = { author, content -> showQuotePreview(author, content) }
 *   renderer.addUserBubble("你好", "10:30:00")
 */
class BubbleRenderer(
    private val activity: Activity,
    private val messagesContainer: LinearLayout,
    private val scrollView: ScrollView
) {
    // ——— 外部可设属性 ———
    var friendName: String = ""
    var friendIcon: String = "🤖"
    var friendAvatarPath: String = ""
    var friendAvatarFramePath: String = ""
    var friendAvatarFrameScalePercent: Int =
        ChatAppearanceStorage.DEFAULT_AVATAR_FRAME_SCALE_PERCENT
    var friendAvatarFrameOffsetXPercent: Int =
        ChatAppearanceStorage.DEFAULT_AVATAR_FRAME_OFFSET_PERCENT
    var friendAvatarFrameOffsetYPercent: Int =
        ChatAppearanceStorage.DEFAULT_AVATAR_FRAME_OFFSET_PERCENT
    var userAvatarFramePath: String = ""
    var userAvatarFrameScalePercent: Int =
        ChatAppearanceStorage.DEFAULT_AVATAR_FRAME_SCALE_PERCENT
    var userAvatarFrameOffsetXPercent: Int =
        ChatAppearanceStorage.DEFAULT_AVATAR_FRAME_OFFSET_PERCENT
    var userAvatarFrameOffsetYPercent: Int =
        ChatAppearanceStorage.DEFAULT_AVATAR_FRAME_OFFSET_PERCENT
    var avatarDisplayMode: ChatAppearanceStorage.AvatarDisplayMode =
        ChatAppearanceStorage.AvatarDisplayMode.AI_ONLY
    var friendAvatarShape: ChatAppearanceStorage.AvatarShape =
        ChatAppearanceStorage.AvatarShape.CIRCLE
    var userAvatarShape: ChatAppearanceStorage.AvatarShape =
        ChatAppearanceStorage.AvatarShape.CIRCLE
    var useCustomChatBackground: Boolean = false

    /**
     * AI 消息与中间提示的手动微调参数。
     *
     * 这里只控制纵向节奏与 AI 头像舞台，不触碰 UserBubbleRenderer 里
     * 已经由用户实机调好的用户气泡参数。
     */
    private object ChatMessageRhythmTuning {
        const val AI_ROW_BOTTOM_MARGIN_DP = 8
        const val AI_AVATAR_GAP_DP = 7
        const val AI_AVATAR_TOP_MARGIN_DP = 2

        // 透明头像框超出头像本体的下半部分，可借用消息行原本的底部间距显示。
        // 这样短消息不会被整张头像框舞台额外撑高，同时也不会压到下一条消息。
        const val AI_AVATAR_BOTTOM_OVERHANG_DP = 8

        const val AI_TIME_TOP_MARGIN_DP = 2
        const val AI_TIME_EDGE_PADDING_DP = 4

        const val SYSTEM_TIP_TOP_MARGIN_DP = 3
        const val SYSTEM_TIP_BOTTOM_MARGIN_DP = 7
        const val TIME_LABEL_TOP_MARGIN_DP = 5
        const val TIME_LABEL_BOTTOM_MARGIN_DP = 8
        const val GAP_MARKER_TOP_MARGIN_DP = 5
        const val GAP_MARKER_BOTTOM_MARGIN_DP = 7
        const val DAY_SEPARATOR_TOP_MARGIN_DP = 10
        const val DAY_SEPARATOR_BOTTOM_MARGIN_DP = 10
        const val THINKING_TOP_MARGIN_DP = 2
        const val THINKING_BOTTOM_MARGIN_DP = 8
    }

    init {
        // 允许透明头像框在消息行底部间距中完整绘制。
        messagesContainer.clipChildren = false
        messagesContainer.clipToPadding = false
        scrollView.clipChildren = false
        scrollView.clipToPadding = false
    }

    /** 创建住户头像；“仅我”模式下返回 null。 */
    fun createAvatar(): View? {
        if (!avatarDisplayMode.showsFriendAvatar) return null
        val view = FriendAvatarHelper.create(
            context = activity,
            avatarPath = friendAvatarPath,
            icon = friendIcon,
            sizeDp = 30,
            framePath = friendAvatarFramePath,
            frameScalePercent = friendAvatarFrameScalePercent,
            frameOffsetXPercent = friendAvatarFrameOffsetXPercent,
            frameOffsetYPercent = friendAvatarFrameOffsetYPercent,
            avatarShape = friendAvatarShape
        )
        val current = view.layoutParams
        view.layoutParams = LinearLayout.LayoutParams(
            current?.width ?: LinearLayout.LayoutParams.WRAP_CONTENT,
            current?.height ?: LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            marginEnd = dp(ChatMessageRhythmTuning.AI_AVATAR_GAP_DP)
            topMargin = dp(ChatMessageRhythmTuning.AI_AVATAR_TOP_MARGIN_DP)
        }
        return view
    }

    /**
     * AI 文字消息专用头像。
     *
     * FriendAvatarHelper 返回的是能完整包住头像框的“舞台”；当消息只有一两行时，
     * 舞台下沿会把整条消息无意义地撑高。这里仅让头像框的底部装饰借用消息行
     * 自带的 bottomMargin 显示，头像、头像框的位置和横向占位都不改变。
     */
    private fun createCompactAiTextAvatar(): View? {
        if (!avatarDisplayMode.showsFriendAvatar) return null

        val avatarSize = dp(30).coerceAtLeast(1)
        val frameFile = friendAvatarFramePath
            .takeIf { it.isNotEmpty() }
            ?.let(::File)
            ?.takeIf { it.isFile }

        val stage = FriendAvatarHelper.create(
            context = activity,
            avatarPath = friendAvatarPath,
            icon = friendIcon,
            sizeDp = 30,
            framePath = friendAvatarFramePath,
            frameScalePercent = friendAvatarFrameScalePercent,
            frameOffsetXPercent = friendAvatarFrameOffsetXPercent,
            frameOffsetYPercent = friendAvatarFrameOffsetYPercent,
            avatarShape = friendAvatarShape
        )

        if (frameFile == null) {
            val current = stage.layoutParams
            stage.layoutParams = LinearLayout.LayoutParams(
                current?.width ?: avatarSize,
                current?.height ?: avatarSize
            ).apply {
                marginEnd = dp(ChatMessageRhythmTuning.AI_AVATAR_GAP_DP)
                topMargin = dp(ChatMessageRhythmTuning.AI_AVATAR_TOP_MARGIN_DP)
            }
            return stage
        }

        val geometry = FriendAvatarHelper.calculateStageGeometry(
            avatarSize,
            ChatAppearanceStorage.AvatarFrameTransform(
                friendAvatarFrameScalePercent,
                friendAvatarFrameOffsetXPercent,
                friendAvatarFrameOffsetYPercent
            )
        )
        val avatarBottom = geometry.avatarTopPx + avatarSize
        val bottomDecoration = (geometry.heightPx - avatarBottom).coerceAtLeast(0)
        val borrowedBottomSpace = minOf(
            bottomDecoration,
            dp(ChatMessageRhythmTuning.AI_AVATAR_BOTTOM_OVERHANG_DP)
        )
        val measuredHeight = (geometry.heightPx - borrowedBottomSpace)
            .coerceAtLeast(avatarBottom)

        return FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(geometry.widthPx, measuredHeight).apply {
                marginEnd = dp(ChatMessageRhythmTuning.AI_AVATAR_GAP_DP)
                topMargin = dp(ChatMessageRhythmTuning.AI_AVATAR_TOP_MARGIN_DP)
            }
            clipChildren = false
            clipToPadding = false
            addView(stage, FrameLayout.LayoutParams(geometry.widthPx, geometry.heightPx))
        }
    }

    /** 创建用户头像；“仅住户”模式下返回 null。 */
    private fun createUserAvatar(): View? {
        if (!avatarDisplayMode.showsUserAvatar) return null
        val view = FriendAvatarHelper.createUserAvatar(
            context = activity,
            sizeDp = 30,
            framePath = userAvatarFramePath,
            frameScalePercent = userAvatarFrameScalePercent,
            frameOffsetXPercent = userAvatarFrameOffsetXPercent,
            frameOffsetYPercent = userAvatarFrameOffsetYPercent,
            avatarShape = userAvatarShape
        )
        val current = view.layoutParams
        view.layoutParams = LinearLayout.LayoutParams(
            current?.width ?: LinearLayout.LayoutParams.WRAP_CONTENT,
            current?.height ?: LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            marginStart = dp(7)
            topMargin = dp(2)
        }
        return view
    }
    /** 长按→引用回复 的回调 */
    var onQuote: ((author: String, content: String) -> Unit)? = null
    /** "加载更多"按钮的回调 */
    var onLoadMore: (() -> Unit)? = null

    // ——— 内部工具 ———
    private val c get() = ThemeHelper.getColors(activity)
    private val handler = Handler(Looper.getMainLooper())
    private var typingView: LinearLayout? = null

    /** 当前展开的思考卡片。用于点聊天空白处/返回键时统一收起。 */
    private val expandedThinkingPanels = linkedMapOf<View, () -> Unit>()

    fun hasExpandedThinkingBlock(): Boolean = expandedThinkingPanels.isNotEmpty()

    fun isTouchInsideExpandedThinking(rawX: Int, rawY: Int): Boolean {
        return expandedThinkingPanels.keys.any { panel ->
            if (panel.visibility != View.VISIBLE) return@any false
            val rect = Rect()
            panel.getGlobalVisibleRect(rect) && rect.contains(rawX, rawY)
        }
    }

    fun collapseAllThinkingBlocks() {
        expandedThinkingPanels.values.toList().forEach { collapse -> collapse() }
    }

    private fun dp(v: Int): Int =
        (v * activity.resources.displayMetrics.density).toInt()

    private val screenWidth get() = activity.resources.displayMetrics.widthPixels

    /** 当前住户头像（含移动/缩放后的头像框）在横向布局里实际占用的宽度。 */
    private fun friendAvatarReservedWidthPx(): Int {
        if (!avatarDisplayMode.showsFriendAvatar) return 0
        val avatarSize = dp(30).coerceAtLeast(1)
        val frameExists = friendAvatarFramePath.isNotEmpty() && File(friendAvatarFramePath).isFile
        val stageWidth = if (frameExists) {
            FriendAvatarHelper.calculateStageGeometry(
                avatarSize,
                ChatAppearanceStorage.AvatarFrameTransform(
                    friendAvatarFrameScalePercent,
                    friendAvatarFrameOffsetXPercent,
                    friendAvatarFrameOffsetYPercent
                )
            ).widthPx
        } else {
            avatarSize
        }
        return stageWidth + dp(7)
    }

    private fun maxAiContentWidth(fraction: Float): Int = minOf(
        (screenWidth * fraction).toInt(),
        (screenWidth - friendAvatarReservedWidthPx() - dp(16)).coerceAtLeast(dp(120))
    )

    private data class ThinkingPalette(
        val header: Int,
        val panel: Int,
        val border: Int,
        val accent: Int,
        val title: Int,
        val body: Int,
        val hint: Int
    )

    /**
     * 使用自定义聊天背景时改为半透明中性色，让背景自然透出，
     * 不再固定成浅色主题的奶白与金棕组合。
     */
    private fun thinkingPalette(): ThinkingPalette {
        if (!useCustomChatBackground) {
            return ThinkingPalette(
                header = c.aiBubbleBg,
                panel = c.backgroundSecondary,
                border = c.borderMedium,
                accent = c.accentStrong,
                title = c.textSecondary,
                body = c.textSecondary,
                hint = c.textHint
            )
        }

        val dark = ThemeHelper.isDark(activity)
        val neutralBase = if (dark) c.backgroundSecondary else Color.WHITE
        return ThinkingPalette(
            header = withAlpha(neutralBase, if (dark) 196 else 166),
            panel = withAlpha(neutralBase, if (dark) 220 else 198),
            border = withAlpha(c.textPrimary, if (dark) 48 else 36),
            accent = c.textPrimary,
            title = c.textPrimary,
            body = c.textSecondary,
            hint = c.textSecondary
        )
    }

    private fun withAlpha(color: Int, alpha: Int): Int = Color.argb(
        alpha.coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color)
    )

    /**
     * 批量渲染时置 true，scrollToBottom() 变成空操作。
     * renderMessages 全跑完后置回 false 再手动调一次 scrollToBottom()。
     * 这样 50 条消息不会排 50 个 fullScroll 导致界面跳动。
     */
    var suppressScroll = false

    fun scrollToBottom() {
        if (suppressScroll) return
        // 双 post：第一帧等 layout 算完高度，第二帧再滚，
        // 避免图片 adjustViewBounds 导致的 layout 滞后。
        scrollView.post { scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) } }
    }

    fun addSystemTip(msg: String) {
        val tip = TextView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(ChatMessageRhythmTuning.SYSTEM_TIP_TOP_MARGIN_DP)
                bottomMargin = dp(ChatMessageRhythmTuning.SYSTEM_TIP_BOTTOM_MARGIN_DP)
            }
            gravity = Gravity.CENTER
            text = msg
            textSize = 11f
            setTextColor(c.textHint)
            setLineSpacing(0f, 1.35f)
            setPadding(dp(20), 0, dp(20), 0)
        }
        messagesContainer.addView(tip)
        scrollToBottom()
    }

    fun addSeenIndicator() {
        val seen = TextView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2); bottomMargin = dp(8) }
            gravity = Gravity.END
            text = "已读"
            textSize = 10f
            setTextColor(c.accent)
            setPadding(0, 0, dp(12), 0)
        }
        messagesContainer.addView(seen)
        scrollToBottom()
    }

    fun addTimeLabel(labelText: String) {
        val label = TextView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(ChatMessageRhythmTuning.TIME_LABEL_TOP_MARGIN_DP)
                bottomMargin = dp(ChatMessageRhythmTuning.TIME_LABEL_BOTTOM_MARGIN_DP)
            }
            gravity = Gravity.CENTER
            text = labelText
            textSize = 10f
            setTextColor(c.timeText)
        }
        messagesContainer.addView(label)
    }

    fun addGapMarker(text: String) {
        val label = TextView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(ChatMessageRhythmTuning.GAP_MARKER_TOP_MARGIN_DP)
                bottomMargin = dp(ChatMessageRhythmTuning.GAP_MARKER_BOTTOM_MARGIN_DP)
            }
            gravity = Gravity.CENTER
            this.text = text
            textSize = 9f
            setTextColor(c.textHint)
        }
        messagesContainer.addView(label)
    }

    fun addDaySeparator(timestamp: Long) {
        val dateLabel = formatDateLabel(timestamp)
        val wrapper = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(ChatMessageRhythmTuning.DAY_SEPARATOR_TOP_MARGIN_DP)
                bottomMargin = dp(ChatMessageRhythmTuning.DAY_SEPARATOR_BOTTOM_MARGIN_DP)
            }
        }
        val lineColor = c.borderMedium
        val leftLine = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(1), 1f).apply { marginEnd = dp(10) }
            setBackgroundColor(lineColor)
        }
        val label = TextView(activity).apply {
            text = dateLabel; textSize = 11f; setTextColor(c.textSecondary)
        }
        val rightLine = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(1), 1f).apply { marginStart = dp(10) }
            setBackgroundColor(lineColor)
        }
        wrapper.addView(leftLine)
        wrapper.addView(label)
        wrapper.addView(rightLine)
        messagesContainer.addView(wrapper)
    }

    fun formatDateLabel(timestamp: Long): String {
        val msgDate = Calendar.getInstance().apply { timeInMillis = timestamp }
        val today = Calendar.getInstance()
        val sameYear = msgDate.get(Calendar.YEAR) == today.get(Calendar.YEAR)
        val sameDay = sameYear && msgDate.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
        val yesterday = sameYear && msgDate.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) - 1
        return when {
            sameDay -> "今天"; yesterday -> "昨天"
            sameYear -> SimpleDateFormat("M月d日", Locale.CHINESE).format(Date(timestamp))
            else -> SimpleDateFormat("yyyy年M月d日", Locale.CHINESE).format(Date(timestamp))
        }
    }

    fun formatGapLabel(gapMs: Long): String {
        val hours = gapMs / 3600000; val days = hours / 24; val rem = hours % 24
        return when {
            days > 0 && rem > 0 -> "${days}天${rem}小时"; days > 0 -> "${days}天"; else -> "${hours}小时"
        }
    }

    fun addErrorBubble(errorMsg: String, retryAction: (() -> Unit)? = null) {
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(4); bottomMargin = dp(10)
                marginStart = dp(40); marginEnd = dp(40)
            }
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(c.errorBg); cornerRadius = dp(12).toFloat()
            }
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        container.addView(TextView(activity).apply {
            text = errorMsg; textSize = 12f; setTextColor(c.errorText)
            gravity = Gravity.CENTER; setLineSpacing(0f, 1.3f)
        })
        if (retryAction != null) {
            container.addView(TextView(activity).apply {
                text = "点击重试"; textSize = 12f; setTextColor(c.accent)
                gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0)
            })
            container.setOnClickListener {
                messagesContainer.removeView(container)
                retryAction()
            }
        }
        messagesContainer.addView(container)
        scrollToBottom()
    }

    fun showMessageMenu(content: String, author: String) {
        val options = arrayOf("复制", "引用回复")
        AlertDialog.Builder(activity)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("chat", content))
                        Toast.makeText(activity, "已复制", Toast.LENGTH_SHORT).show()
                    }
                    1 -> onQuote?.invoke(author, content)
                }
            }.show()
    }

    fun addLoadMoreButton() {
        val btn = TextView(activity).apply {
            text = "↑ 加载更早的消息"
            textSize = 12f; setTextColor(c.accent)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
            tag = "load_more_btn"
            setOnClickListener { onLoadMore?.invoke() }
        }
        messagesContainer.addView(btn, 0)
    }

    //  用户气泡（委托给 UserBubbleRenderer）

    private val userRenderer = UserBubbleRenderer(activity, messagesContainer, scrollView).also {
        it.onMessageMenu = { content, author -> showMessageMenu(content, author) }
    }

    /** 把当前聊天的用户头像设置同步给用户侧气泡渲染器。 */
    fun syncAvatarAppearance() {
        userRenderer.showUserAvatar = avatarDisplayMode.showsUserAvatar
        userRenderer.avatarShape = userAvatarShape
        userRenderer.avatarFramePath = userAvatarFramePath
        userRenderer.avatarFrameScalePercent = userAvatarFrameScalePercent
        userRenderer.avatarFrameOffsetXPercent = userAvatarFrameOffsetXPercent
        userRenderer.avatarFrameOffsetYPercent = userAvatarFrameOffsetYPercent
    }

    fun addUserBubble(msg: String, timeStr: String): View = userRenderer.addUserBubble(msg, timeStr)
    fun addImageBubble(imagePath: String, timeStr: String, caption: String = "") = userRenderer.addImageBubble(imagePath, timeStr, caption)
    fun addStickerBubble(imagePath: String, timeStr: String, caption: String = "") = userRenderer.addStickerBubble(imagePath, timeStr, caption)
    fun addMultiImageBubble(imagePaths: List<String>, timeStr: String, caption: String = "") = userRenderer.addMultiImageBubble(imagePaths, timeStr, caption)
    fun addImageBubbleAt(imagePath: String, timeStr: String, caption: String, index: Int): View = userRenderer.addImageBubbleAt(imagePath, timeStr, caption, index)
    fun addQuoteBubble(quoteAuthor: String, quoteContent: String, msg: String, timeStr: String): View = userRenderer.addQuoteBubble(quoteAuthor, quoteContent, msg, timeStr)

    //  AI 气泡

    /**
     * 内部共享：创建一个 AI 气泡的基本结构（头像 + 气泡 + 时间）。
     * 返回 Triple(wrapper, bubbleTextView, timeTextView)。
     * 不会自动添加到 messagesContainer。
     */
    private fun buildAiBubbleStructure(
        initialText: CharSequence,
        timeStr: String,
        fullMsgForMenu: String
    ): Triple<LinearLayout, TextView, TextView> {
        val wrapper = LinearLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ChatMessageRhythmTuning.AI_ROW_BOTTOM_MARGIN_DP) }
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
            clipChildren = false
            clipToPadding = false
        }
        val avatar = createCompactAiTextAvatar()
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val bubble = TextView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            maxWidth = maxAiContentWidth(0.80f)
            text = initialText
            setTextColor(c.textOnAccent); textSize = 14f
            setLineSpacing(0f, 1.35f)
            setPadding(dp(11), dp(8), dp(11), dp(8))
            setBackgroundResource(R.drawable.chat_bubble_ai)
            setOnLongClickListener { showMessageMenu(fullMsgForMenu, friendName); true }
        }
        val time = makeTimeView(timeStr, Gravity.START)
        column.addView(bubble)
        column.addView(time)
        avatar?.let(wrapper::addView)
        wrapper.addView(column)
        return Triple(wrapper, bubble, time)
    }

    /** 创建 AI 气泡 View 但不添加到容器（用于 loadEarlierMessages 插入到指定位置） */
    fun createAiBubbleView(msg: String, timeStr: String): View {
        val (wrapper, _, _) = buildAiBubbleStructure(MarkdownRenderer.render(msg), timeStr, msg)
        return wrapper
    }

    // ----- 静态渲染（加载历史用） -----

    /** 静态 AI 气泡入口：处理 [SPLIT] 分条 */
    fun addAiBubble(msg: String, timeStr: String) {
        if (msg.isBlank()) return
        if (msg.contains("[SPLIT]")) {
            msg.split("[SPLIT]").map { it.trim() }.filter { it.isNotEmpty() }
                .forEach { renderAiSegmentStatic(it, timeStr) }
            return
        }
        renderAiSegmentStatic(msg, timeStr)
    }

    /** 静态渲染一段——拆分享卡片和表情包标记 */
    private fun renderAiSegmentStatic(segment: String, timeStr: String) {
        // 先处理 [SHARE_BOOK:书名|内容]
        val bookPattern = Regex("\\[SHARE_BOOK:([^|]+)\\|([^]]+)]")
        val bookMatch = bookPattern.find(segment)
        if (bookMatch != null) {
            // 卡片前面的文字
            val before = segment.substring(0, bookMatch.range.first).trim()
            if (before.isNotEmpty()) renderAiSegmentStatic(before, timeStr)
            // 渲染书籍分享卡片
            addBookShareCard(bookMatch.groupValues[1].trim(), bookMatch.groupValues[2].trim(), timeStr)
            // 卡片后面的文字
            val after = segment.substring(bookMatch.range.last + 1).trim()
            if (after.isNotEmpty()) renderAiSegmentStatic(after, timeStr)
            return
        }
        if (!segment.contains("[STICKER_IMG:")) {
            addAiBubbleSingleStatic(segment, timeStr); return
        }
        renderStickerMixed(segment, timeStr) { text, t -> addAiBubbleSingleStatic(text, t) }
    }

    /** 静态单条 AI 文字气泡 */
    private fun addAiBubbleSingleStatic(msg: String, timeStr: String) {
        val (wrapper, _, _) = buildAiBubbleStructure(MarkdownRenderer.render(msg), timeStr, msg)
        messagesContainer.addView(wrapper)
        scrollToBottom()
    }

    /** 书籍分享卡片 */
    private fun addBookShareCard(bookName: String, quote: String, timeStr: String) {
        val wrapper = LinearLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.START
        }
        val avatar = createAvatar()
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // ── 书型卡片 ──
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(c.accentBg)
                cornerRadius = dp(4).toFloat()
                setStroke(dp(1), c.accent)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // 书脊
        val spine = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(8), LinearLayout.LayoutParams.MATCH_PARENT)
            setBackgroundColor(c.accent)
        }

        // 封面内容
        val cover = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val titleView = TextView(activity).apply {
            text = "📖 $bookName"
            textSize = 13f
            setTextColor(c.accentStrong)
            maxWidth = (screenWidth * 0.5).toInt()
        }
        val quoteView = TextView(activity).apply {
            text = quote
            textSize = 11f
            setTextColor(c.textSecondary)
            setLineSpacing(0f, 1.3f)
            maxWidth = (screenWidth * 0.5).toInt()
            maxLines = 3
            setPadding(0, dp(4), 0, 0)
        }
        val hintView = TextView(activity).apply {
            text = "tap to read ›"
            textSize = 10f
            setTextColor(c.timeText)
            gravity = Gravity.END
            setPadding(0, dp(4), 0, 0)
        }

        cover.addView(titleView)
        cover.addView(quoteView)
        cover.addView(hintView)
        card.addView(spine)
        card.addView(cover)

        // ── 点击：书翻开 → 跳转 ──
        card.setOnClickListener {
            // 翻开效果：书脊变宽，内容切换
            spine.layoutParams = LinearLayout.LayoutParams(dp(12), LinearLayout.LayoutParams.MATCH_PARENT)
            spine.requestLayout()
            titleView.text = "📖 翻开中…"
            quoteView.text = quote
            quoteView.maxLines = 6
            hintView.visibility = View.GONE

            // 延一帧再跳转，让卡片变化先画出来
            card.postDelayed({
                val bookStorage = BookStorage(activity)
                val book = bookStorage.loadBooksMeta().find { it.title == bookName }
                if (book != null) {
                    val intent = android.content.Intent(activity, BookReaderActivity::class.java)
                    intent.putExtra("book_id", book.id)
                    activity.startActivity(intent)
                } else {
                    android.widget.Toast.makeText(activity, "书架上没找到《$bookName》", android.widget.Toast.LENGTH_SHORT).show()
                }
            }, 50)

            // 回来之后恢复初始状态
            card.postDelayed({
                spine.layoutParams = LinearLayout.LayoutParams(dp(8), LinearLayout.LayoutParams.MATCH_PARENT)
                spine.requestLayout()
                titleView.text = "📖 $bookName"
                quoteView.maxLines = 3
                hintView.visibility = View.VISIBLE
            }, 2000)
        }

        card.setOnLongClickListener {
            showMessageMenu("📖 $bookName\n$quote", friendName); true
        }

        column.addView(card)
        column.addView(makeTimeView(timeStr, Gravity.START))
        avatar?.let(wrapper::addView)
        wrapper.addView(column)
        messagesContainer.addView(wrapper)
        scrollToBottom()
    }

    // ----- 流式渲染（实时消息用） -----

    /** 流式 AI 气泡入口：处理 [SPLIT] 分条（每条间隔 600ms） */
    fun addAiBubbleStreaming(msg: String, timeStr: String) {
        if (msg.isBlank()) return  // 空消息不渲染气泡
        if (msg.contains("[SPLIT]")) {
            val parts = msg.split("[SPLIT]").map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size > 1) {
                // 分条：每条静态渲染，不打字，只做延迟出现
                var delay = 0L
                for (part in parts) {
                    handler.postDelayed({
                        renderAiSegmentStatic(part, timeStr)
                        scrollToBottom()
                    }, delay)
                    delay += 600L
                }
                return
            }
        }
        // 单条：打字效果
        renderAiSegment(msg, timeStr)
    }

    /** 流式渲染一段——拆表情包标记 */
    private fun renderAiSegment(segment: String, timeStr: String) {
        if (!segment.contains("[STICKER_IMG:")) {
            addAiBubbleSingle(segment, timeStr); return
        }
        renderStickerMixed(segment, timeStr) { text, t -> addAiBubbleSingle(text, t) }
    }

    /**
     * 流式单条 AI 文字气泡——逐字打字效果
     * 每次蹦 2 个字符，间隔 30ms，一秒约 66 字
     */
    private fun addAiBubbleSingle(msg: String, timeStr: String) {
        val (wrapper, bubble, time) = buildAiBubbleStructure("", timeStr, msg)
        time.visibility = View.GONE
        messagesContainer.addView(wrapper)
        scrollToBottom()

        var currentIndex = 0
        val chunkSize = 2
        val delay = 30L
        val typingRunnable = object : Runnable {
            override fun run() {
                if (currentIndex < msg.length) {
                    currentIndex = minOf(currentIndex + chunkSize, msg.length)
                    bubble.text = msg.substring(0, currentIndex)
                    scrollToBottom()
                    handler.postDelayed(this, delay)
                } else {
                    bubble.text = MarkdownRenderer.render(msg)
                    time.visibility = View.VISIBLE
                    scrollToBottom()
                }
            }
        }
        handler.post(typingRunnable)
    }

    // ----- AI 表情包（左侧，带头像） -----

    private fun addAiImageBubble(imagePath: String, timeStr: String) {
        val file = File(imagePath)
        if (!file.exists()) return
        val wrapper = LinearLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.START
        }
        val avatar = createAvatar()
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val imageView = ImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(120), dp(120))
            scaleType = ImageView.ScaleType.CENTER_CROP
            val bitmap = BitmapFactory.decodeFile(imagePath)
            if (bitmap != null) setImageBitmap(bitmap)
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(10).toFloat())
                }
            }
            setOnClickListener { ImageHelper.showFullImage(activity, imagePath) }
        }
        column.addView(imageView)
        column.addView(makeTimeView(timeStr, Gravity.START))
        avatar?.let(wrapper::addView)
        wrapper.addView(column)
        messagesContainer.addView(wrapper)
        scrollToBottom()
    }

    /** 共享：拆 [STICKER_IMG:path] 标记，交替渲染文字和图片 */
    private fun renderStickerMixed(
        segment: String, timeStr: String,
        textBubbleAdder: (String, String) -> Unit
    ) {
        val pattern = Regex("\\[STICKER_IMG:(.+?)]")
        var remaining = segment
        var match = pattern.find(remaining)
        while (match != null) {
            val textBefore = remaining.substring(0, match.range.first).trim()
            if (textBefore.isNotEmpty()) textBubbleAdder(textBefore, timeStr)
            addAiImageBubble(match.groupValues[1], timeStr)
            remaining = remaining.substring(match.range.last + 1)
            match = pattern.find(remaining)
        }
        val textAfter = remaining.trim()
        if (textAfter.isNotEmpty()) textBubbleAdder(textAfter, timeStr)
    }

    fun addThinkingBlock(thinking: String) {
        if (thinking.isBlank()) return

        // 这里只修显示：保留现有思考内容来源，不改接口和提取方式。
        // Markdown 标题正常渲染，[SPLIT] 等控制标记不直接露在界面上。
        val cleanedThinking = thinking
            .replace(Regex("\\[SPLIT\\]", RegexOption.IGNORE_CASE), "\n\n")
            .replace(Regex("(?i)</?think(?:ing)?>"), "")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()

        val palette = thinkingPalette()

        val wrapper = LinearLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(ChatMessageRhythmTuning.THINKING_TOP_MARGIN_DP)
                bottomMargin = dp(ChatMessageRhythmTuning.THINKING_BOTTOM_MARGIN_DP)
            }
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
        }

        // 和 AI 正文左边缘对齐，但不重复显示头像。
        val spacer = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(friendAvatarReservedWidthPx(), dp(1))
        }

        val thinkingLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                (screenWidth * 0.78f).toInt(),
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(36)
            )
            setPadding(dp(12), 0, dp(8), 0)
            background = GradientDrawable().apply {
                setColor(palette.header)
                cornerRadius = dp(13).toFloat()
                setStroke(dp(1), palette.border)
            }
            isClickable = true
            isFocusable = true
            contentDescription = "展开思考过程"
        }

        val dot = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(6), dp(6)).apply {
                marginEnd = dp(8)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(palette.accent)
            }
        }

        val title = TextView(activity).apply {
            text = "思考过程"
            textSize = 11.5f
            setTextColor(palette.title)
            includeFontPadding = false
        }

        val flexibleSpace = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(1), 1f)
        }

        val chevron = ImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
            setImageResource(R.drawable.ic_thinking_chevron)
            setColorFilter(palette.hint)
            alpha = 0.85f
            contentDescription = null
        }

        header.addView(dot)
        header.addView(title)
        header.addView(flexibleSpace)
        header.addView(chevron)

        val contentPanel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
            background = GradientDrawable().apply {
                setColor(palette.panel)
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), palette.border)
            }
            visibility = View.GONE
            clipToOutline = true
        }

        val bodyRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val accentLine = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(2), LinearLayout.LayoutParams.MATCH_PARENT)
            setBackgroundColor(palette.accent)
        }

        val contentView = TextView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            text = MarkdownRenderer.render(cleanedThinking)
            setTextColor(palette.body)
            textSize = 12.5f
            setLineSpacing(0f, 1.45f)
            setPadding(dp(12), dp(10), dp(12), dp(10))

            // 单击展开时完整铺开，不在卡片内部再套一层滚动。
            // 想单独滚动查看时，长按标题打开悬浮阅读窗。
        }

        bodyRow.addView(accentLine)
        bodyRow.addView(contentView)

        val footerDivider = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
            ).apply {
                marginStart = dp(12)
                marginEnd = dp(12)
            }
            setBackgroundColor(palette.border)
            alpha = 0.55f
        }

        val collapseFooter = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(38)
            )
            isClickable = true
            isFocusable = true
            contentDescription = "收起思考过程"
        }

        val footerChevron = ImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(17), dp(17)).apply {
                marginEnd = dp(4)
            }
            setImageResource(R.drawable.ic_thinking_chevron)
            setColorFilter(palette.hint)
            rotation = 180f
            alpha = 0.85f
            contentDescription = null
        }

        val footerText = TextView(activity).apply {
            text = "收起"
            textSize = 11f
            setTextColor(palette.hint)
            includeFontPadding = false
        }

        collapseFooter.addView(footerChevron)
        collapseFooter.addView(footerText)
        contentPanel.addView(bodyRow)
        contentPanel.addView(footerDivider)
        contentPanel.addView(collapseFooter)

        lateinit var collapsePanel: () -> Unit
        collapsePanel = {
            if (contentPanel.visibility == View.VISIBLE) {
                expandedThinkingPanels.remove(thinkingLayout)
                contentPanel.animate().cancel()
                chevron.animate().cancel()
                contentPanel.animate()
                    .alpha(0f)
                    .translationY(-dp(4).toFloat())
                    .setDuration(120L)
                    .withEndAction {
                        TransitionManager.beginDelayedTransition(
                            thinkingLayout,
                            AutoTransition().apply { duration = 140L }
                        )
                        contentPanel.visibility = View.GONE
                        contentPanel.alpha = 1f
                        contentPanel.translationY = 0f
                    }
                    .start()
                chevron.animate().rotation(0f).setDuration(160L).start()
                header.contentDescription = "展开思考过程"
            }
        }

        val openPanel = {
            // 同一时间只展开一段，避免聊天页里叠出多张长卡片。
            collapseAllThinkingBlocks()
            contentView.scrollTo(0, 0)
            TransitionManager.beginDelayedTransition(
                thinkingLayout,
                AutoTransition().apply { duration = 180L }
            )
            contentPanel.visibility = View.VISIBLE
            contentPanel.alpha = 0f
            contentPanel.translationY = -dp(4).toFloat()
            contentPanel.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(180L)
                .start()
            chevron.animate().rotation(180f).setDuration(180L).start()
            header.contentDescription = "收起思考过程"
            expandedThinkingPanels[thinkingLayout] = collapsePanel
        }

        header.setOnClickListener {
            if (contentPanel.visibility == View.GONE) openPanel() else collapsePanel()
        }
        header.setOnLongClickListener {
            showThinkingDialog(cleanedThinking)
            true
        }
        collapseFooter.setOnClickListener { collapsePanel() }

        contentPanel.setOnLongClickListener {
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("thinking", cleanedThinking))
            Toast.makeText(activity, "思考内容已复制", Toast.LENGTH_SHORT).show()
            true
        }

        thinkingLayout.addView(header)
        thinkingLayout.addView(contentPanel)
        wrapper.addView(spacer)
        wrapper.addView(thinkingLayout)
        messagesContainer.addView(wrapper)
    }

    private fun showThinkingDialog(content: String) {
        val dialog = Dialog(activity)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setCancelable(true)

        val palette = thinkingPalette()

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(palette.panel)
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), palette.border)
            }
            setPadding(dp(16), dp(8), dp(16), dp(14))
        }

        val dialogHeader = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(46)
            )
        }

        val dialogDot = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(7), dp(7)).apply {
                marginEnd = dp(9)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(palette.accent)
            }
        }

        val dialogTitle = TextView(activity).apply {
            text = "思考过程"
            textSize = 13f
            setTextColor(palette.title)
            includeFontPadding = false
        }

        val dialogSpace = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(1), 1f)
        }

        val close = TextView(activity).apply {
            text = "关闭"
            textSize = 12f
            setTextColor(palette.accent)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(8), dp(4), dp(8))
            isClickable = true
            isFocusable = true
            setOnClickListener { dialog.dismiss() }
        }

        dialogHeader.addView(dialogDot)
        dialogHeader.addView(dialogTitle)
        dialogHeader.addView(dialogSpace)
        dialogHeader.addView(close)

        val divider = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
            )
            setBackgroundColor(palette.border)
            alpha = 0.65f
        }

        val dialogText = TextView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            text = MarkdownRenderer.render(content)
            setTextColor(palette.body)
            textSize = 13f
            setLineSpacing(0f, 1.5f)
            setPadding(dp(4), dp(14), dp(4), dp(20))
            setTextIsSelectable(true)
        }

        val readingScroll = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            isFillViewport = false
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(dialogText)
        }

        card.addView(dialogHeader)
        card.addView(divider)
        card.addView(readingScroll)

        // 某些手机会在“小尺寸 Dialog 窗口”底部额外画出一条导航栏背景，
        // 看起来就像卡片下面粘着一块白色板砖。
        // 这里把 Dialog 窗口改成全屏透明层，圆角卡片只作为居中的子 View：
        // 系统栏不再挤在卡片下面，点卡片外仍可关闭。
        val dialogRoot = FrameLayout(activity).apply {
            setBackgroundColor(Color.TRANSPARENT)
            clipToPadding = false
            isClickable = true
            setOnClickListener { dialog.dismiss() }
        }

        card.apply {
            isClickable = true
            isFocusable = true
            // 吃掉卡片内部点击，避免冒泡到透明层后误关闭。
            setOnClickListener { }
        }

        dialogRoot.addView(
            card,
            FrameLayout.LayoutParams(
                (screenWidth * 0.90f).toInt(),
                (activity.resources.displayMetrics.heightPixels * 0.76f).toInt(),
                Gravity.CENTER
            )
        )

        dialog.setContentView(dialogRoot)

        dialog.setOnShowListener {
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                decorView.setBackgroundColor(Color.TRANSPARENT)
                decorView.setPadding(0, 0, 0, 0)
                addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                navigationBarColor = Color.TRANSPARENT
                statusBarColor = Color.TRANSPARENT
                attributes = attributes.apply { dimAmount = 0.32f }
                setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT
                )
                setGravity(Gravity.CENTER)
            }
        }

        dialog.show()
    }

    fun showTypingIndicator() {
        val wrapper = LinearLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.START
        }
        val avatar = createAvatar()
        val bubble = TextView(activity).apply {
            text = "$friendName 正在输入..."
            textSize = 12f; setTextColor(c.accent)
            setPadding(dp(11), dp(8), dp(11), dp(8))
            setBackgroundResource(R.drawable.chat_bubble_ai)
        }
        avatar?.let(wrapper::addView)
        wrapper.addView(bubble)
        typingView = wrapper
        messagesContainer.addView(wrapper)
        scrollToBottom()
    }

    fun updateTypingIndicator(message: String) {
        val wrapper = typingView as? LinearLayout ?: return
        val bubble = (0 until wrapper.childCount)
            .mapNotNull { index -> wrapper.getChildAt(index) as? TextView }
            .firstOrNull() ?: return
        bubble.text = message
        scrollToBottom()
    }

    fun removeTypingIndicator() {
        typingView?.let { messagesContainer.removeView(it); typingView = null }
    }

    /** 创建时间 TextView（复用） */
    private fun makeTimeView(timeStr: String, align: Int): TextView {
        val isRight = align == Gravity.END
        return TextView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(ChatMessageRhythmTuning.AI_TIME_TOP_MARGIN_DP) }
            gravity = align
            text = timeStr; textSize = 9f
            setTextColor(c.timeText)
            setPadding(
                if (isRight) 0 else dp(ChatMessageRhythmTuning.AI_TIME_EDGE_PADDING_DP),
                0,
                if (isRight) dp(ChatMessageRhythmTuning.AI_TIME_EDGE_PADDING_DP) else 0,
                0
            )
        }
    }

    fun addWeatherCard(data: WeatherData, city: String, isUser: Boolean, timeStr: String) {
        val card = WeatherCardRenderer.buildCard(activity, data, city)
        val wrapper = LinearLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            orientation = LinearLayout.HORIZONTAL
            gravity = if (isUser) Gravity.END else Gravity.START
            setPadding(dp(1), 0, dp(8), 0)
        }
        if (!isUser) {
            val avatar = createAvatar()
            avatar?.let(wrapper::addView)
        }
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        col.addView(card)
        col.addView(makeTimeView(timeStr, if (isUser) Gravity.END else Gravity.START))
        wrapper.addView(col)
        if (isUser) {
            createUserAvatar()?.let(wrapper::addView)
        }
        messagesContainer.addView(wrapper)
        scrollToBottom()
    }

    /**
     * 构建引用预览条的 View（显示在输入框上方）
     * @param onCancel 点击 ✕ 时的回调
     */
    fun buildQuotePreview(author: String, content: String, onCancel: () -> Unit): View {
        val previewLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(c.accentBg)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val bar = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(3), LinearLayout.LayoutParams.MATCH_PARENT).apply {
                marginEnd = dp(8)
            }
            setBackgroundColor(c.accent)
        }
        val textLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val authorView = TextView(activity).apply {
            this.text = "回复 $author"
            textSize = 11f
            setTextColor(c.accentStrong)
        }
        val shortContent = if (content.length > 30) content.substring(0, 30) + "..." else content
        val contentView = TextView(activity).apply {
            this.text = shortContent
            textSize = 11f
            setTextColor(c.textSecondary)
            maxLines = 1
        }
        textLayout.addView(authorView)
        textLayout.addView(contentView)
        val cancelBtn = TextView(activity).apply {
            this.text = "✕"
            textSize = 16f
            setTextColor(c.tipText)
            setPadding(dp(8), 0, 0, 0)
            setOnClickListener { onCancel() }
        }
        previewLayout.addView(bar)
        previewLayout.addView(textLayout)
        previewLayout.addView(cancelBtn)
        return previewLayout
    }
}