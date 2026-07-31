package com.haven.guiqi

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Outline
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import kotlin.math.roundToInt


/**
 * 用户侧消息的手动微调参数。
 *
 * 这些数值集中放在这里，方便实机观察后直接微调：
 * - ROW_START_INSET_DP：用户消息整体距离屏幕左边的最小安全边距。
 * - ROW_END_INSET_DP：用户头像框距离屏幕右边的安全边距。
 * - AVATAR_GAP_DP：用户气泡/图片与用户头像框之间的横向间距。
 * - TEXT_MAX_WIDTH_FRACTION：普通用户文字气泡最多占屏幕宽度的比例。
 * - BUBBLE_START_PADDING_DP：文字到气泡起始边缘的内边距。
 * - BUBBLE_END_PADDING_DP：文字到气泡结束边缘的内边距。
 * - BUBBLE_TIGHT_WIDTH_EXTRA_DP：多行文字按最长行收紧后额外保留的安全宽度。
 */
private object UserBubbleLayoutTuning {
    const val ROW_START_INSET_DP = 3
    const val ROW_END_INSET_DP = 1
    const val AVATAR_GAP_DP = 8
    const val AVATAR_TOP_MARGIN_DP = 2
    const val TEXT_MAX_WIDTH_FRACTION = 0.78f
    const val BUBBLE_START_PADDING_DP = 9
    const val BUBBLE_END_PADDING_DP = 7
    const val BUBBLE_TIGHT_WIDTH_EXTRA_DP = 3
    const val BUBBLE_VERTICAL_PADDING_DP = 8
    const val TIME_TOP_MARGIN_DP = 2
    const val TIME_EDGE_PADDING_DP = 4
    const val MESSAGE_BOTTOM_MARGIN_DP = 8
}

/**
 * UserBubbleRenderer — 用户侧气泡渲染
 *
 * 负责：纯文字气泡、单图、表情包、多图网格、引用回复、历史占位。
 * 表情包与普通单图分开渲染：表情包不套气泡，普通图片按原比例显示为圆角卡片。
 */
class UserBubbleRenderer(
    private val activity: Activity,
    private val messagesContainer: LinearLayout,
    private val scrollView: ScrollView
) {
    /** 长按菜单回调（内容, 作者） */
    var onMessageMenu: ((content: String, author: String) -> Unit)? = null

    var showUserAvatar: Boolean = false
    var avatarShape: ChatAppearanceStorage.AvatarShape =
        ChatAppearanceStorage.AvatarShape.CIRCLE
    var avatarFramePath: String = ""
    var avatarFrameScalePercent: Int =
        ChatAppearanceStorage.DEFAULT_AVATAR_FRAME_SCALE_PERCENT
    var avatarFrameOffsetXPercent: Int =
        ChatAppearanceStorage.DEFAULT_AVATAR_FRAME_OFFSET_PERCENT
    var avatarFrameOffsetYPercent: Int =
        ChatAppearanceStorage.DEFAULT_AVATAR_FRAME_OFFSET_PERCENT

    private val c get() = ThemeHelper.getColors(activity)
    private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()
    private val screenWidth get() = activity.resources.displayMetrics.widthPixels

    /** 用户头像舞台会随头像框变大；内容宽度必须主动为它留位。 */
    private fun userAvatarReservedWidthPx(): Int {
        if (!showUserAvatar) return 0
        val avatarSize = dp(30).coerceAtLeast(1)
        val frameExists = avatarFramePath.isNotEmpty() && File(avatarFramePath).isFile
        val stageWidth = if (frameExists) {
            FriendAvatarHelper.calculateStageGeometry(
                avatarSize,
                ChatAppearanceStorage.AvatarFrameTransform(
                    avatarFrameScalePercent,
                    avatarFrameOffsetXPercent,
                    avatarFrameOffsetYPercent
                )
            ).widthPx
        } else {
            avatarSize
        }
        return stageWidth + dp(UserBubbleLayoutTuning.AVATAR_GAP_DP)
    }

    private fun maxUserContentWidth(fraction: Float): Int = minOf(
        (screenWidth * fraction).toInt(),
        (
            screenWidth -
                userAvatarReservedWidthPx() -
                dp(UserBubbleLayoutTuning.ROW_START_INSET_DP + UserBubbleLayoutTuning.ROW_END_INSET_DP)
            ).coerceAtLeast(dp(120))
    )

    /**
     * Android 的多行 TextView 在受 maxWidth 约束时，测量宽度可能保留整块可用宽度，
     * 即使每一行真正使用的文字宽度都更短。首次排版后按最长一行二次收紧，
     * 让左右可见留白由独立 padding 控制，而不是被未使用的排版宽度放大。
     */
    private fun tightenMultilineBubbleWidth(textView: TextView, maxWidthPx: Int) {
        textView.post {
            val textLayout = textView.layout ?: return@post
            if (textLayout.lineCount <= 1 || textView.width <= 0) return@post

            var longestLineWidth = 0f
            for (lineIndex in 0 until textLayout.lineCount) {
                longestLineWidth = maxOf(
                    longestLineWidth,
                    textLayout.getLineWidth(lineIndex)
                )
            }

            val targetWidth = (
                kotlin.math.ceil(longestLineWidth.toDouble()).toInt() +
                    textView.paddingStart +
                    textView.paddingEnd +
                    dp(UserBubbleLayoutTuning.BUBBLE_TIGHT_WIDTH_EXTRA_DP)
                )
                .coerceAtMost(maxWidthPx)
                .coerceAtLeast(dp(48))

            if (targetWidth < textView.width) {
                textView.layoutParams = textView.layoutParams.apply { width = targetWidth }
                textView.requestLayout()
            }
        }
    }

    private fun scrollToBottom() {
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    private fun makeTimeView(timeStr: String, align: Int): TextView {
        val isRight = align == Gravity.END
        return TextView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(UserBubbleLayoutTuning.TIME_TOP_MARGIN_DP) }
            gravity = align
            text = timeStr
            textSize = 9f
            setTextColor(c.timeText)
            setPadding(
                if (isRight) 0 else dp(UserBubbleLayoutTuning.TIME_EDGE_PADDING_DP),
                0,
                if (isRight) dp(UserBubbleLayoutTuning.TIME_EDGE_PADDING_DP) else 0,
                0
            )
        }
    }

    private fun readImageSize(path: String): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, options)
        return if (options.outWidth > 0 && options.outHeight > 0) {
            options.outWidth to options.outHeight
        } else null
    }

    private fun fitSize(
        originalWidth: Int,
        originalHeight: Int,
        maxWidth: Int,
        maxHeight: Int
    ): Pair<Int, Int> {
        val scale = minOf(
            maxWidth.toFloat() / originalWidth.toFloat(),
            maxHeight.toFloat() / originalHeight.toFloat(),
            1f.takeIf { originalWidth <= maxWidth && originalHeight <= maxHeight } ?: Float.MAX_VALUE
        )
        val safeScale = if (scale.isFinite() && scale > 0f) scale else 1f
        return (originalWidth * safeScale).roundToInt().coerceAtLeast(1) to
            (originalHeight * safeScale).roundToInt().coerceAtLeast(1)
    }

    private fun decodeSampled(path: String, targetWidth: Int, targetHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetWidth * 2 &&
            bounds.outHeight / (sample * 2) >= targetHeight * 2) {
            sample *= 2
        }
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })
    }

    private fun roundCorners(view: View, radiusDp: Int) {
        view.clipToOutline = true
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(target: View, outline: Outline) {
                outline.setRoundRect(0, 0, target.width, target.height, dp(radiusDp).toFloat())
            }
        }
    }

    private fun addToConversation(wrapper: View) {
        messagesContainer.addView(wrapper)
        scrollToBottom()
    }

    private fun createUserAvatar(): View? {
        if (!showUserAvatar) return null
        val avatar = FriendAvatarHelper.createUserAvatar(
            context = activity,
            sizeDp = 30,
            framePath = avatarFramePath,
            frameScalePercent = avatarFrameScalePercent,
            frameOffsetXPercent = avatarFrameOffsetXPercent,
            frameOffsetYPercent = avatarFrameOffsetYPercent,
            avatarShape = avatarShape
        )
        val current = avatar.layoutParams
        avatar.layoutParams = LinearLayout.LayoutParams(
            current?.width ?: LinearLayout.LayoutParams.WRAP_CONTENT,
            current?.height ?: LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            marginStart = dp(UserBubbleLayoutTuning.AVATAR_GAP_DP)
            topMargin = dp(UserBubbleLayoutTuning.AVATAR_TOP_MARGIN_DP)
        }
        return avatar
    }

    private fun attachRightAlignedContent(wrapper: LinearLayout, content: View) {
        wrapper.orientation = LinearLayout.HORIZONTAL
        wrapper.clipChildren = false
        wrapper.clipToPadding = false
        wrapper.setPadding(
            dp(UserBubbleLayoutTuning.ROW_START_INSET_DP),
            0,
            dp(UserBubbleLayoutTuning.ROW_END_INSET_DP),
            0
        )
        wrapper.addView(content)
        createUserAvatar()?.let(wrapper::addView)
    }

    /** 普通用户文字气泡 */
    fun addUserBubble(msg: String, timeStr: String): View {
        val wrapper = LinearLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(UserBubbleLayoutTuning.MESSAGE_BOTTOM_MARGIN_DP) }
            gravity = Gravity.END
        }
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val bubbleMaxWidth = maxUserContentWidth(UserBubbleLayoutTuning.TEXT_MAX_WIDTH_FRACTION)
        val bubble = TextView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            maxWidth = bubbleMaxWidth
            text = MarkdownRenderer.render(msg)
            setTextColor(c.textOnAccent)
            textSize = 14f
            setLineSpacing(0f, 1.35f)
            setPaddingRelative(
                dp(UserBubbleLayoutTuning.BUBBLE_START_PADDING_DP),
                dp(UserBubbleLayoutTuning.BUBBLE_VERTICAL_PADDING_DP),
                dp(UserBubbleLayoutTuning.BUBBLE_END_PADDING_DP),
                dp(UserBubbleLayoutTuning.BUBBLE_VERTICAL_PADDING_DP)
            )
            setBackgroundResource(R.drawable.chat_bubble_user)
            setOnLongClickListener { onMessageMenu?.invoke(msg, "我"); true }
        }
        column.addView(bubble)
        column.addView(makeTimeView(timeStr, Gravity.END))
        attachRightAlignedContent(wrapper, column)
        addToConversation(wrapper)
        tightenMultilineBubbleWidth(bubble, bubbleMaxWidth)
        return wrapper
    }

    /** 用户普通单张图片：不套彩色气泡，按原比例显示为圆角图片卡片。 */
    fun addImageBubble(imagePath: String, timeStr: String, caption: String = "") {
        val wrapper = LinearLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(UserBubbleLayoutTuning.MESSAGE_BOTTOM_MARGIN_DP) }
            gravity = Gravity.END
        }
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val (sourceWidth, sourceHeight) = readImageSize(imagePath) ?: (dp(220) to dp(220))
        val ratio = sourceWidth.toFloat() / sourceHeight.toFloat()
        val maxDisplayWidth = when {
            ratio > 1.15f -> minOf(maxUserContentWidth(0.72f), dp(280))
            else -> minOf(maxUserContentWidth(0.64f), dp(220))
        }
        val maxDisplayHeight = when {
            ratio < 0.85f -> dp(320)
            ratio > 1.15f -> dp(220)
            else -> dp(220)
        }
        val (displayWidth, displayHeight) = fitSize(
            sourceWidth,
            sourceHeight,
            maxDisplayWidth,
            maxDisplayHeight
        )

        val imageView = ImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(displayWidth, displayHeight)
            scaleType = ImageView.ScaleType.FIT_CENTER
            if (File(imagePath).isFile) {
                decodeSampled(imagePath, displayWidth, displayHeight)?.let(::setImageBitmap)
            }
            roundCorners(this, 10)
            setOnClickListener { ImageHelper.showFullImage(activity, imagePath) }
        }
        column.addView(imageView)

        if (caption.isNotEmpty()) {
            column.addView(TextView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) }
                maxWidth = displayWidth.coerceAtLeast(dp(140))
                text = MarkdownRenderer.render(caption)
                setTextColor(c.textOnAccent)
                textSize = 13f
                setLineSpacing(0f, 1.3f)
                setPadding(dp(10), dp(6), dp(10), dp(6))
                setBackgroundResource(R.drawable.chat_bubble_user)
            })
        }
        column.addView(makeTimeView(timeStr, Gravity.END))
        attachRightAlignedContent(wrapper, column)
        addToConversation(wrapper)
    }

    /** 用户表情包：保留透明通道，不套气泡、不加白底。 */
    fun addStickerBubble(imagePath: String, timeStr: String, caption: String = "") {
        val wrapper = LinearLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(UserBubbleLayoutTuning.MESSAGE_BOTTOM_MARGIN_DP) }
            gravity = Gravity.END
        }
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val (sourceWidth, sourceHeight) = readImageSize(imagePath) ?: (dp(156) to dp(156))
        val maxSide = minOf(maxUserContentWidth(0.46f), dp(156))
        val (displayWidth, displayHeight) = fitSize(sourceWidth, sourceHeight, maxSide, maxSide)
        val imageView = ImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(displayWidth, displayHeight)
            scaleType = ImageView.ScaleType.FIT_CENTER
            if (File(imagePath).isFile) {
                decodeSampled(imagePath, displayWidth, displayHeight)?.let(::setImageBitmap)
            }
            setOnClickListener { ImageHelper.showFullImage(activity, imagePath) }
        }
        column.addView(imageView)
        if (caption.isNotEmpty()) {
            column.addView(TextView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) }
                maxWidth = minOf(maxUserContentWidth(0.72f), dp(260))
                text = MarkdownRenderer.render(caption)
                setTextColor(c.textOnAccent)
                textSize = 13f
                setLineSpacing(0f, 1.3f)
                setPadding(dp(10), dp(6), dp(10), dp(6))
                setBackgroundResource(R.drawable.chat_bubble_user)
            })
        }
        column.addView(makeTimeView(timeStr, Gravity.END))
        attachRightAlignedContent(wrapper, column)
        addToConversation(wrapper)
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
            ).apply { bottomMargin = dp(UserBubbleLayoutTuning.MESSAGE_BOTTOM_MARGIN_DP) }
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
                    width = thumbSize
                    height = thumbSize
                    setMargins(
                        if (index % columns != 0) gap else 0,
                        if (index >= columns) gap else 0,
                        0,
                        0
                    )
                }
                scaleType = ImageView.ScaleType.CENTER_CROP
                if (File(path).exists()) {
                    decodeSampled(path, thumbSize, thumbSize)?.let(::setImageBitmap)
                }
                roundCorners(this, 6)
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
                setTextColor(c.textOnAccent)
                textSize = 13f
                setLineSpacing(0f, 1.3f)
                setPadding(dp(10), dp(6), dp(10), dp(6))
                setBackgroundResource(R.drawable.chat_bubble_user)
            })
        }
        column.addView(makeTimeView(timeStr, Gravity.END))
        attachRightAlignedContent(wrapper, column)
        addToConversation(wrapper)
    }

    /** 历史加载时在指定位置插入图片占位气泡 */
    fun addImageBubbleAt(imagePath: String, timeStr: String, caption: String, index: Int): View {
        val wrapper = LinearLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(UserBubbleLayoutTuning.MESSAGE_BOTTOM_MARGIN_DP) }
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val bubble = TextView(activity).apply {
            text = "[图片]${if (caption.isNotEmpty()) " $caption" else ""}"
            setTextColor(c.textSecondary)
            textSize = 13f
            setPaddingRelative(
                dp(UserBubbleLayoutTuning.BUBBLE_START_PADDING_DP),
                dp(UserBubbleLayoutTuning.BUBBLE_VERTICAL_PADDING_DP),
                dp(UserBubbleLayoutTuning.BUBBLE_END_PADDING_DP),
                dp(UserBubbleLayoutTuning.BUBBLE_VERTICAL_PADDING_DP)
            )
            setBackgroundResource(R.drawable.chat_bubble_user)
        }
        attachRightAlignedContent(wrapper, bubble)
        messagesContainer.addView(wrapper, index)
        return wrapper
    }

    /** 带引用的用户气泡 */
    fun addQuoteBubble(quoteAuthor: String, quoteContent: String, msg: String, timeStr: String): View {
        val wrapper = LinearLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(UserBubbleLayoutTuning.MESSAGE_BOTTOM_MARGIN_DP) }
            gravity = Gravity.END
        }
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
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
            text = quoteAuthor
            textSize = 10f
            setTextColor(c.accentStrong)
        })
        val shortContent = if (quoteContent.length > 40) quoteContent.substring(0, 40) + "..." else quoteContent
        quoteText.addView(TextView(activity).apply {
            text = shortContent
            textSize = 11f
            setTextColor(c.textSecondary)
            maxLines = 2
            maxWidth = maxUserContentWidth(0.65f)
        })
        quoteBlock.addView(bar)
        quoteBlock.addView(quoteText)
        column.addView(quoteBlock)
        val messageBubbleMaxWidth = maxUserContentWidth(UserBubbleLayoutTuning.TEXT_MAX_WIDTH_FRACTION)
        val messageBubble = TextView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(3) }
            maxWidth = messageBubbleMaxWidth
            text = MarkdownRenderer.render(msg)
            setTextColor(c.textOnAccent)
            textSize = 14f
            setLineSpacing(0f, 1.35f)
            setPaddingRelative(
                dp(UserBubbleLayoutTuning.BUBBLE_START_PADDING_DP),
                dp(UserBubbleLayoutTuning.BUBBLE_VERTICAL_PADDING_DP),
                dp(UserBubbleLayoutTuning.BUBBLE_END_PADDING_DP),
                dp(UserBubbleLayoutTuning.BUBBLE_VERTICAL_PADDING_DP)
            )
            setBackgroundResource(R.drawable.chat_bubble_user)
            setOnLongClickListener { onMessageMenu?.invoke(msg, "我"); true }
        }
        column.addView(messageBubble)
        column.addView(makeTimeView(timeStr, Gravity.END))
        attachRightAlignedContent(wrapper, column)
        addToConversation(wrapper)
        tightenMultilineBubbleWidth(messageBubble, messageBubbleMaxWidth)
        return wrapper
    }
}
