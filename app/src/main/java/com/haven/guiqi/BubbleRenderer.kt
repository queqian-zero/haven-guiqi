package com.haven.guiqi

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.graphics.Outline
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
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
    /** 长按→引用回复 的回调 */
    var onQuote: ((author: String, content: String) -> Unit)? = null
    /** "加载更多"按钮的回调 */
    var onLoadMore: (() -> Unit)? = null

    // ——— 内部工具 ———
    private val c get() = ThemeHelper.getColors(activity)
    private val handler = Handler(Looper.getMainLooper())
    private var typingView: LinearLayout? = null

    private fun dp(v: Int): Int =
        (v * activity.resources.displayMetrics.density).toInt()

    private val screenWidth get() = activity.resources.displayMetrics.widthPixels

    fun scrollToBottom() {
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    fun addSystemTip(msg: String) {
        val tip = TextView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4); bottomMargin = dp(10) }
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
            ).apply { topMargin = dp(6); bottomMargin = dp(10) }
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
            ).apply { topMargin = dp(6); bottomMargin = dp(8) }
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
            ).apply { topMargin = dp(12); bottomMargin = dp(12) }
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

    fun formatGapLabel(gapMs: Long): String {
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

    //  用户气泡

    /** 普通用户文字气泡 */
    fun addUserBubble(msg: String, timeStr: String): View {
        val wrapper = LinearLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            gravity = Gravity.END
        }
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
            maxWidth = (screenWidth * 0.82).toInt()
            text = MarkdownRenderer.render(msg)
            setTextColor(c.textOnAccent); textSize = 14f
            setLineSpacing(0f, 1.35f)
            setPadding(dp(11), dp(8), dp(11), dp(8))
            setBackgroundResource(R.drawable.chat_bubble_user)
            setOnLongClickListener { showMessageMenu(msg, "我"); true }
        }
        val time = makeTimeView(timeStr, Gravity.END)
        column.addView(bubble)
        column.addView(time)
        wrapper.addView(column)
        messagesContainer.addView(wrapper)
        scrollToBottom()
        return wrapper
    }

    /** 用户单张图片气泡 */
    fun addImageBubble(imagePath: String, timeStr: String, caption: String = "") {
        val wrapper = LinearLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            gravity = Gravity.END
        }
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val imageView = ImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(180), LinearLayout.LayoutParams.WRAP_CONTENT)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundResource(R.drawable.chat_bubble_user)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            val file = File(imagePath)
            if (file.exists()) setImageBitmap(BitmapFactory.decodeFile(imagePath))
        }
        column.addView(imageView)
        if (caption.isNotEmpty()) {
            column.addView(TextView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) }
                maxWidth = dp(180)
                text = MarkdownRenderer.render(caption)
                setTextColor(c.textOnAccent); textSize = 13f
                setLineSpacing(0f, 1.3f)
                setPadding(dp(10), dp(6), dp(10), dp(6))
                setBackgroundResource(R.drawable.chat_bubble_user)
            })
        }
        column.addView(makeTimeView(timeStr, Gravity.END))
        wrapper.addView(column)
        messagesContainer.addView(wrapper)
        scrollToBottom()
    }

    /** 用户多图网格气泡 */
    fun addMultiImageBubble(imagePaths: List<String>, timeStr: String, caption: String = "") {
        val thumbSize = dp(90)
        val gap = dp(4)
        val columns = if (imagePaths.size == 2) 2 else 3

        val wrapper = LinearLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            gravity = Gravity.END
        }
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val grid = GridLayout(activity).apply {
            columnCount = columns
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        for ((index, path) in imagePaths.withIndex()) {
            val iv = ImageView(activity).apply {
                layoutParams = GridLayout.LayoutParams().apply {
                    width = thumbSize; height = thumbSize
                    setMargins(
                        if (index % columns != 0) gap else 0,
                        if (index >= columns) gap else 0, 0, 0
                    )
                }
                scaleType = ImageView.ScaleType.CENTER_CROP
                if (File(path).exists()) setImageBitmap(BitmapFactory.decodeFile(path))
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, dp(6).toFloat())
                    }
                }
                setOnClickListener { ImageHelper.showFullImage(activity, path) }
            }
            grid.addView(iv)
        }
        column.addView(grid)
        if (caption.isNotEmpty()) {
            column.addView(TextView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) }
                maxWidth = columns * thumbSize + (columns - 1) * gap
                text = MarkdownRenderer.render(caption)
                setTextColor(c.textOnAccent); textSize = 13f
                setLineSpacing(0f, 1.3f)
                setPadding(dp(10), dp(6), dp(10), dp(6))
                setBackgroundResource(R.drawable.chat_bubble_user)
            })
        }
        column.addView(makeTimeView(timeStr, Gravity.END))
        wrapper.addView(column)
        messagesContainer.addView(wrapper)
        scrollToBottom()
    }

    /** 历史加载时在指定位置插入图片占位气泡 */
    fun addImageBubbleAt(imagePath: String, timeStr: String, caption: String, index: Int): View {
        val wrapper = LinearLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END
        }
        val bubble = TextView(activity).apply {
            text = "[图片]${if (caption.isNotEmpty()) " $caption" else ""}"
            setTextColor(c.textSecondary); textSize = 13f
            setPadding(dp(11), dp(8), dp(11), dp(8))
            setBackgroundResource(R.drawable.chat_bubble_user)
        }
        wrapper.addView(bubble)
        messagesContainer.addView(wrapper, index)
        return wrapper
    }

    /** 带引用的用户气泡 */
    fun addQuoteBubble(quoteAuthor: String, quoteContent: String, msg: String, timeStr: String): View {
        val wrapper = LinearLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            gravity = Gravity.END
        }
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        // 引用块
        val quoteBlock = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(c.accentBg)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val bar = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(2), LinearLayout.LayoutParams.MATCH_PARENT)
                .apply { marginEnd = dp(6) }
            setBackgroundColor(c.accent)
        }
        val quoteText = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        quoteText.addView(TextView(activity).apply {
            text = quoteAuthor; textSize = 10f; setTextColor(c.accentStrong)
        })
        val shortContent = if (quoteContent.length > 40) quoteContent.substring(0, 40) + "..." else quoteContent
        quoteText.addView(TextView(activity).apply {
            text = shortContent; textSize = 11f; setTextColor(c.textSecondary)
            maxLines = 2; maxWidth = (screenWidth * 0.65).toInt()
        })
        quoteBlock.addView(bar)
        quoteBlock.addView(quoteText)
        column.addView(quoteBlock)
        // 正文
        column.addView(TextView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(3) }
            maxWidth = (screenWidth * 0.82).toInt()
            text = MarkdownRenderer.render(msg)
            setTextColor(c.textOnAccent); textSize = 14f
            setLineSpacing(0f, 1.35f)
            setPadding(dp(11), dp(8), dp(11), dp(8))
            setBackgroundResource(R.drawable.chat_bubble_user)
            setOnLongClickListener { showMessageMenu(msg, "我"); true }
        })
        column.addView(makeTimeView(timeStr, Gravity.END))
        wrapper.addView(column)
        messagesContainer.addView(wrapper)
        scrollToBottom()
        return wrapper
    }

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
            ).apply { bottomMargin = dp(8) }
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.START
        }
        val avatar = TextView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(30))
                .apply { marginEnd = dp(7); topMargin = dp(2) }
            gravity = Gravity.CENTER
            text = friendIcon; textSize = 12f
            setTextColor(c.accentStrong)
            setBackgroundResource(R.drawable.icon_bg)
        }
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
            maxWidth = (screenWidth * 0.80).toInt()
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
        wrapper.addView(avatar)
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
        if (msg.contains("[SPLIT]")) {
            msg.split("[SPLIT]").map { it.trim() }.filter { it.isNotEmpty() }
                .forEach { renderAiSegmentStatic(it, timeStr) }
            return
        }
        renderAiSegmentStatic(msg, timeStr)
    }

    /** 静态渲染一段——拆表情包标记 */
    private fun renderAiSegmentStatic(segment: String, timeStr: String) {
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

    // ----- 流式渲染（实时消息用） -----

    /** 流式 AI 气泡入口：处理 [SPLIT] 分条（每条间隔 600ms） */
    fun addAiBubbleStreaming(msg: String, timeStr: String) {
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
        val avatar = TextView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(30))
                .apply { marginEnd = dp(7); topMargin = dp(2) }
            gravity = Gravity.CENTER
            text = friendIcon; textSize = 12f
            setTextColor(c.accentStrong)
            setBackgroundResource(R.drawable.icon_bg)
        }
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
        wrapper.addView(avatar)
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
        val wrapper = LinearLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) }
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.START
        }
        val spacer = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(37), dp(1))
        }
        val thinkingLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val contentView = TextView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            maxWidth = (screenWidth * 0.75).toInt()
            text = thinking
            setTextColor(c.accent); textSize = 12f
            setLineSpacing(0f, 1.3f)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setBackgroundResource(R.drawable.chat_thinking_bg)
            visibility = View.GONE
        }
        val toggleView = TextView(activity).apply {
            text = "💭 思考过程 ▸"
            textSize = 11f; setTextColor(c.accent)
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
        contentView.setOnLongClickListener {
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("thinking", thinking))
            Toast.makeText(activity, "已复制", Toast.LENGTH_SHORT).show()
            true
        }
        thinkingLayout.addView(toggleView)
        thinkingLayout.addView(contentView)
        wrapper.addView(spacer)
        wrapper.addView(thinkingLayout)
        messagesContainer.addView(wrapper)
    }

    fun showTypingIndicator() {
        val wrapper = LinearLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.START
        }
        val avatar = TextView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(30))
                .apply { marginEnd = dp(7); topMargin = dp(2) }
            gravity = Gravity.CENTER
            text = friendIcon; textSize = 12f
            setTextColor(c.accentStrong)
            setBackgroundResource(R.drawable.icon_bg)
        }
        val bubble = TextView(activity).apply {
            text = "$friendName 正在输入..."
            textSize = 12f; setTextColor(c.accent)
            setPadding(dp(11), dp(8), dp(11), dp(8))
            setBackgroundResource(R.drawable.chat_bubble_ai)
        }
        wrapper.addView(avatar)
        wrapper.addView(bubble)
        typingView = wrapper
        messagesContainer.addView(wrapper)
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
            ).apply { topMargin = dp(2) }
            gravity = align
            text = timeStr; textSize = 9f
            setTextColor(c.timeText)
            setPadding(if (isRight) 0 else dp(4), 0, if (isRight) dp(4) else 0, 0)
        }
    }
}