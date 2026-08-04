package com.haven.guiqi

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Outline
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.PathInterpolator
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
    const val IMAGE_THUMB_MAX_SIZE_DP = 84
    const val IMAGE_THUMB_GAP_DP = 4
    const val IMAGE_CAPTION_TOP_MARGIN_DP = 4
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
 * 表情包与普通图片分开渲染：表情包保持原比例，普通图片在聊天流中显示为可放大的方形附件缩略图。
 */
class UserBubbleRenderer(
    private val activity: Activity,
    private val messagesContainer: LinearLayout,
    private val scrollView: ScrollView
) {
    companion object {
        /** 已确认的“安静版”连续消息间隔。 */
        const val QUIET_MULTI_SEND_STAGGER_MS = 260L
    }

    private object QuietSendAnimationTuning {
        const val MESSAGE_DURATION_MS = 230L
        const val MESSAGE_START_OFFSET_DP = 10
        const val MESSAGE_START_SCALE = 0.96f
        const val AVATAR_START_SCALE = 0.90f
        const val AVATAR_DURATION_MS = 207L
        const val TIME_DELAY_MS = 125L
        const val TIME_DURATION_MS = 150L
        const val TIME_START_OFFSET_DP = 3

        const val MEDIA_DURATION_MS = 300L
        const val MEDIA_START_OFFSET_DP = 18
        const val MEDIA_START_SCALE = 0.72f
        const val MEDIA_ITEM_STAGGER_MS = 38L
        const val CAPTION_DELAY_MS = 90L
        const val PURE_MEDIA_AVATAR_DELAY_MS = 20L
    }

    private data class PendingNewMessageAnimation(
        val startDelayMs: Long,
        val showAvatar: Boolean
    )

    private var pendingNewMessageAnimation: PendingNewMessageAnimation? = null
    private val quietInterpolator = PathInterpolator(0.20f, 0.78f, 0.20f, 1.00f)
    /**
     * 只给下一条“刚发送”的用户消息安排入场动画。
     * 历史加载不会调用它，因此旧消息不会在进入聊天时重新晃一遍。
     */
    fun prepareNextMessageAnimation(
        startDelayMs: Long = 0L,
        showAvatar: Boolean = true
    ) {
        pendingNewMessageAnimation = PendingNewMessageAnimation(
            startDelayMs = startDelayMs.coerceAtLeast(0L),
            showAvatar = showAvatar
        )
    }

    private fun consumePendingMessageAnimation(): PendingNewMessageAnimation? =
        pendingNewMessageAnimation.also { pendingNewMessageAnimation = null }

    /**
     * 估算一条“安静版”用户消息从开始动画到最后一个可见元素落稳所需的时间。
     * Activity 用它把“对方正在输入中”排在整批用户消息之后，而不是抢在动画中间出现。
     */
    fun estimateQuietMessageVisualTailMs(
        mediaItemCount: Int = 0,
        hasCaption: Boolean = false
    ): Long {
        if (mediaItemCount <= 0) {
            return maxOf(
                QuietSendAnimationTuning.MESSAGE_DURATION_MS,
                QuietSendAnimationTuning.AVATAR_DURATION_MS,
                QuietSendAnimationTuning.TIME_DELAY_MS + QuietSendAnimationTuning.TIME_DURATION_MS
            )
        }

        val mediaTailDelay =
            (mediaItemCount - 1).coerceAtLeast(0) * QuietSendAnimationTuning.MEDIA_ITEM_STAGGER_MS
        val mediaEnd = mediaTailDelay + QuietSendAnimationTuning.MEDIA_DURATION_MS
        val textAndTimeEnd = if (hasCaption) {
            mediaTailDelay +
                QuietSendAnimationTuning.CAPTION_DELAY_MS +
                QuietSendAnimationTuning.TIME_DELAY_MS +
                QuietSendAnimationTuning.TIME_DURATION_MS
        } else {
            mediaTailDelay +
                QuietSendAnimationTuning.TIME_DELAY_MS +
                QuietSendAnimationTuning.TIME_DURATION_MS
        }
        val avatarEnd = if (hasCaption) {
            mediaTailDelay +
                QuietSendAnimationTuning.CAPTION_DELAY_MS +
                QuietSendAnimationTuning.AVATAR_DURATION_MS
        } else {
            QuietSendAnimationTuning.PURE_MEDIA_AVATAR_DELAY_MS +
                QuietSendAnimationTuning.AVATAR_DURATION_MS
        }
        return maxOf(mediaEnd, textAndTimeEnd, avatarEnd)
    }

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

    var bubbleStyle: BubbleStyleStorage.BubbleStyle =
        BubbleStyleStorage(activity).defaultStyle(BubbleStyleStorage.Target.USER)

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

    private fun maxUserContentWidth(
        fraction: Float,
        includeBubbleOffset: Boolean = false
    ): Int {
        val outwardOffset = if (includeBubbleOffset &&
            bubbleStyle.fillMode.usesImage
        ) {
            dp(bubbleStyle.imageAvatarOffsetDp.coerceAtLeast(0))
        } else {
            0
        }
        return minOf(
            (screenWidth * fraction).toInt(),
            (
                screenWidth -
                    userAvatarReservedWidthPx() -
                    dp(UserBubbleLayoutTuning.ROW_START_INSET_DP + UserBubbleLayoutTuning.ROW_END_INSET_DP) -
                    outwardOffset
                ).coerceAtLeast(dp(120))
        )
    }

    /**
     * 聊天流里的图片只作为可点击附件预览：目标边长 84dp，窄屏时自动缩小。
     * 这里计算的是 UI 缩略图尺寸，不改动原图文件，也不影响发送给住户的图片数据。
     */
    private fun imageThumbnailSizePx(columns: Int): Int {
        val safeColumns = columns.coerceIn(1, 3)
        val gap = dp(UserBubbleLayoutTuning.IMAGE_THUMB_GAP_DP)
        val available = (
            screenWidth -
                userAvatarReservedWidthPx() -
                dp(UserBubbleLayoutTuning.ROW_START_INSET_DP + UserBubbleLayoutTuning.ROW_END_INSET_DP) -
                gap * (safeColumns - 1)
            ).coerceAtLeast(safeColumns)
        return minOf(
            dp(UserBubbleLayoutTuning.IMAGE_THUMB_MAX_SIZE_DP),
            available / safeColumns
        ).coerceAtLeast(1)
    }

    private fun imageGridHeightPx(itemCount: Int, columns: Int, thumbSize: Int): Int {
        if (itemCount <= 0) return 0
        val rows = (itemCount + columns - 1) / columns
        return rows * thumbSize +
            (rows - 1).coerceAtLeast(0) * dp(UserBubbleLayoutTuning.IMAGE_THUMB_GAP_DP)
    }

    /** 用户头像位于右侧，因此“远离头像”的正值需要向左移动。 */
    private fun applyUserImageBubbleOffset(column: View) {
        val style = bubbleStyle
        val params = column.layoutParams as? LinearLayout.LayoutParams ?: return
        if (style.fillMode.usesImage) {
            // 用户头像位于右侧，正值通过 marginEnd 真正把整列向左推远。
            params.marginEnd = dp(style.imageAvatarOffsetDp)
            column.translationY = dp(style.imageVerticalOffsetDp).toFloat()
        } else {
            params.marginEnd = 0
            column.translationY = 0f
        }
        column.translationX = 0f
        column.layoutParams = params
    }

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

    /**
     * 图片说明使用普通文字气泡自己的宽度上限，不继承同组图片／网格的宽度。
     * 这样横图再宽，也不会把一小句说明强行拉成同样宽的气泡。
     */
    private fun createImageCaption(caption: String, maxWidthPx: Int): TextView =
        TextView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(UserBubbleLayoutTuning.IMAGE_CAPTION_TOP_MARGIN_DP) }
            maxWidth = maxWidthPx
            text = MarkdownRenderer.render(caption)
            setTextColor(c.textOnAccent)
            textSize = 13f
            setLineSpacing(0f, 1.3f)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setBackgroundResource(R.drawable.chat_bubble_user)
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

    private fun createUserAvatar(topMarginPx: Int = dp(UserBubbleLayoutTuning.AVATAR_TOP_MARGIN_DP)): View? {
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
            topMargin = topMarginPx.coerceAtLeast(0)
        }
        return avatar
    }

    private fun attachRightAlignedContent(
        wrapper: LinearLayout,
        content: View,
        avatarTopMarginPx: Int = dp(UserBubbleLayoutTuning.AVATAR_TOP_MARGIN_DP),
        includeAvatar: Boolean = true
    ): View? {
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

        if (!showUserAvatar) return null
        if (includeAvatar) {
            return createUserAvatar(avatarTopMarginPx)?.also { wrapper.addView(it) }
        }

        // 连续发送时只让最后一条显示头像，但前面的消息仍保留同样的头像槽位，
        // 避免气泡忽左忽右。
        wrapper.addView(View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                userAvatarReservedWidthPx().coerceAtLeast(1),
                1
            )
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        return null
    }

    private fun animateQuietEntry(
        view: View,
        startDelayMs: Long,
        startOffsetDp: Int = QuietSendAnimationTuning.MESSAGE_START_OFFSET_DP,
        startScale: Float = QuietSendAnimationTuning.MESSAGE_START_SCALE,
        durationMs: Long = QuietSendAnimationTuning.MESSAGE_DURATION_MS
    ) {
        view.animate().cancel()
        view.alpha = 0f
        view.translationY = dp(startOffsetDp).toFloat()
        view.scaleX = startScale
        view.scaleY = startScale
        view.post {
            if (!view.isAttachedToWindow) {
                view.alpha = 1f
                view.translationY = 0f
                view.scaleX = 1f
                view.scaleY = 1f
                return@post
            }
            view.pivotX = view.width.toFloat()
            view.pivotY = view.height.toFloat()
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setStartDelay(startDelayMs.coerceAtLeast(0L))
                .setDuration(durationMs)
                .setInterpolator(quietInterpolator)
                .withLayer()
                .start()
        }
    }

    private fun animateQuietAvatar(view: View?, startDelayMs: Long) {
        if (view == null) return
        animateQuietEntry(
            view = view,
            startDelayMs = startDelayMs,
            startOffsetDp = 0,
            startScale = QuietSendAnimationTuning.AVATAR_START_SCALE,
            durationMs = QuietSendAnimationTuning.AVATAR_DURATION_MS
        )
    }

    private fun animateQuietTime(view: View, startDelayMs: Long) {
        view.animate().cancel()
        view.alpha = 0f
        view.translationY = dp(QuietSendAnimationTuning.TIME_START_OFFSET_DP).toFloat()
        view.post {
            if (!view.isAttachedToWindow) {
                view.alpha = 1f
                view.translationY = 0f
                return@post
            }
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(startDelayMs.coerceAtLeast(0L))
                .setDuration(QuietSendAnimationTuning.TIME_DURATION_MS)
                .setInterpolator(quietInterpolator)
                .withLayer()
                .start()
        }
    }

    private fun animateQuietMedia(view: View, startDelayMs: Long) {
        animateQuietEntry(
            view = view,
            startDelayMs = startDelayMs,
            startOffsetDp = QuietSendAnimationTuning.MEDIA_START_OFFSET_DP,
            startScale = QuietSendAnimationTuning.MEDIA_START_SCALE,
            durationMs = QuietSendAnimationTuning.MEDIA_DURATION_MS
        )
    }

    /** 普通用户文字气泡 */
    fun addUserBubble(msg: String, timeStr: String): View {
        val animation = consumePendingMessageAnimation()
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
        applyUserImageBubbleOffset(column)
        val bubbleMaxWidth = maxUserContentWidth(
            UserBubbleLayoutTuning.TEXT_MAX_WIDTH_FRACTION,
            includeBubbleOffset = true
        )
        val bubble = TextView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            maxWidth = bubbleMaxWidth
            text = MarkdownRenderer.render(msg)
            textSize = 14f
            setLineSpacing(0f, 1.35f)
            setPaddingRelative(
                dp(UserBubbleLayoutTuning.BUBBLE_START_PADDING_DP),
                dp(UserBubbleLayoutTuning.BUBBLE_VERTICAL_PADDING_DP),
                dp(UserBubbleLayoutTuning.BUBBLE_END_PADDING_DP),
                dp(UserBubbleLayoutTuning.BUBBLE_VERTICAL_PADDING_DP)
            )
            BubbleStyleApplier.apply(
                this,
                bubbleStyle,
                BubbleStyleStorage.Target.USER
            )
            setOnLongClickListener { onMessageMenu?.invoke(msg, "我"); true }
        }
        val timeView = makeTimeView(timeStr, Gravity.END)
        column.addView(bubble)
        column.addView(timeView)
        val avatar = attachRightAlignedContent(
            wrapper,
            column,
            includeAvatar = animation?.showAvatar ?: true
        )
        addToConversation(wrapper)
        animation?.let { spec ->
            animateQuietEntry(bubble, spec.startDelayMs)
            animateQuietTime(
                timeView,
                spec.startDelayMs + QuietSendAnimationTuning.TIME_DELAY_MS
            )
            animateQuietAvatar(avatar, spec.startDelayMs)
        }
        tightenMultilineBubbleWidth(bubble, bubbleMaxWidth)
        return wrapper
    }

    /**
     * 用户单图附件：聊天流中固定为不超过 84dp 的正方形缩略图，点击仍查看原图。
     * 有说明文字时头像回到文字气泡顶部；纯图片时头像保持在附件顶部。
     */
    fun addImageBubble(imagePath: String, timeStr: String, caption: String = "") {
        val animation = consumePendingMessageAnimation()
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

        val thumbSize = imageThumbnailSizePx(columns = 1)
        val imageView = ImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(thumbSize, thumbSize)
            scaleType = ImageView.ScaleType.CENTER_CROP
            if (File(imagePath).isFile) {
                decodeSampled(imagePath, thumbSize, thumbSize)?.let(::setImageBitmap)
            }
            roundCorners(this, 10)
            contentDescription = "查看原图"
            setOnClickListener { ImageHelper.showFullImage(activity, imagePath) }
        }
        column.addView(imageView)

        val captionMaxWidth = maxUserContentWidth(UserBubbleLayoutTuning.TEXT_MAX_WIDTH_FRACTION)
        val captionView = caption.takeIf { it.isNotEmpty() }?.let {
            createImageCaption(it, captionMaxWidth).also { view -> column.addView(view) }
        }
        val timeView = makeTimeView(timeStr, Gravity.END)
        column.addView(timeView)

        val avatarTop = if (captionView != null) {
            thumbSize +
                dp(UserBubbleLayoutTuning.IMAGE_CAPTION_TOP_MARGIN_DP) +
                dp(UserBubbleLayoutTuning.AVATAR_TOP_MARGIN_DP)
        } else {
            dp(UserBubbleLayoutTuning.AVATAR_TOP_MARGIN_DP)
        }
        val avatar = attachRightAlignedContent(
            wrapper,
            column,
            avatarTopMarginPx = avatarTop,
            includeAvatar = animation?.showAvatar ?: true
        )
        addToConversation(wrapper)
        animation?.let { spec ->
            animateQuietMedia(imageView, spec.startDelayMs)
            val captionDelay = if (captionView != null) {
                QuietSendAnimationTuning.CAPTION_DELAY_MS
            } else {
                0L
            }
            captionView?.let {
                animateQuietEntry(it, spec.startDelayMs + captionDelay)
            }
            animateQuietTime(
                timeView,
                spec.startDelayMs + captionDelay + QuietSendAnimationTuning.TIME_DELAY_MS
            )
            animateQuietAvatar(
                avatar,
                spec.startDelayMs + if (captionView != null) {
                    QuietSendAnimationTuning.CAPTION_DELAY_MS
                } else {
                    QuietSendAnimationTuning.PURE_MEDIA_AVATAR_DELAY_MS
                }
            )
        }
        captionView?.let { tightenMultilineBubbleWidth(it, captionMaxWidth) }
    }

    /** 用户表情包：保留透明通道，不套气泡、不加白底。 */
    fun addStickerBubble(imagePath: String, timeStr: String, caption: String = "") {
        val animation = consumePendingMessageAnimation()
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
        val captionMaxWidth = maxUserContentWidth(UserBubbleLayoutTuning.TEXT_MAX_WIDTH_FRACTION)
        val captionView = caption.takeIf { it.isNotEmpty() }?.let {
            createImageCaption(it, captionMaxWidth).also { view -> column.addView(view) }
        }
        val timeView = makeTimeView(timeStr, Gravity.END)
        column.addView(timeView)
        val avatar = attachRightAlignedContent(
            wrapper,
            column,
            includeAvatar = animation?.showAvatar ?: true
        )
        addToConversation(wrapper)
        animation?.let { spec ->
            animateQuietMedia(imageView, spec.startDelayMs)
            val captionDelay = if (captionView != null) {
                QuietSendAnimationTuning.CAPTION_DELAY_MS
            } else {
                0L
            }
            captionView?.let { animateQuietEntry(it, spec.startDelayMs + captionDelay) }
            animateQuietTime(
                timeView,
                spec.startDelayMs + captionDelay + QuietSendAnimationTuning.TIME_DELAY_MS
            )
            animateQuietAvatar(
                avatar,
                spec.startDelayMs + if (captionView != null) {
                    QuietSendAnimationTuning.CAPTION_DELAY_MS
                } else {
                    QuietSendAnimationTuning.PURE_MEDIA_AVATAR_DELAY_MS
                }
            )
        }
        captionView?.let { tightenMultilineBubbleWidth(it, captionMaxWidth) }
    }

    /**
     * 用户多图附件：每行最多三张，所有缩略图同尺寸，最大 84dp；窄屏自动缩小。
     * 有说明文字时头像只与文字气泡对齐，纯图片时仍与附件网格顶部对齐。
     */
    fun addMultiImageBubble(imagePaths: List<String>, timeStr: String, caption: String = "") {
        if (imagePaths.isEmpty()) return
        val animation = consumePendingMessageAnimation()
        val columns = minOf(imagePaths.size, 3)
        val thumbSize = imageThumbnailSizePx(columns)
        val gap = dp(UserBubbleLayoutTuning.IMAGE_THUMB_GAP_DP)

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
        val grid = GridLayout(activity).apply {
            columnCount = columns
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val imageViews = mutableListOf<ImageView>()
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
                if (File(path).isFile) {
                    decodeSampled(path, thumbSize, thumbSize)?.let(::setImageBitmap)
                }
                roundCorners(this, 10)
                contentDescription = "查看第 ${index + 1} 张原图"
                setOnClickListener { ImageHelper.showFullImage(activity, path) }
            }
            imageViews.add(iv)
            grid.addView(iv)
        }
        column.addView(grid)

        val captionMaxWidth = maxUserContentWidth(UserBubbleLayoutTuning.TEXT_MAX_WIDTH_FRACTION)
        val captionView = caption.takeIf { it.isNotEmpty() }?.let {
            createImageCaption(it, captionMaxWidth).also { view -> column.addView(view) }
        }
        val timeView = makeTimeView(timeStr, Gravity.END)
        column.addView(timeView)

        val avatarTop = if (captionView != null) {
            imageGridHeightPx(imagePaths.size, columns, thumbSize) +
                dp(UserBubbleLayoutTuning.IMAGE_CAPTION_TOP_MARGIN_DP) +
                dp(UserBubbleLayoutTuning.AVATAR_TOP_MARGIN_DP)
        } else {
            dp(UserBubbleLayoutTuning.AVATAR_TOP_MARGIN_DP)
        }
        val avatar = attachRightAlignedContent(
            wrapper,
            column,
            avatarTopMarginPx = avatarTop,
            includeAvatar = animation?.showAvatar ?: true
        )
        addToConversation(wrapper)
        animation?.let { spec ->
            imageViews.forEachIndexed { index, view ->
                animateQuietMedia(
                    view,
                    spec.startDelayMs + index * QuietSendAnimationTuning.MEDIA_ITEM_STAGGER_MS
                )
            }
            val mediaTailDelay =
                (imageViews.size - 1).coerceAtLeast(0) * QuietSendAnimationTuning.MEDIA_ITEM_STAGGER_MS
            val captionDelay = if (captionView != null) {
                mediaTailDelay + QuietSendAnimationTuning.CAPTION_DELAY_MS
            } else {
                mediaTailDelay
            }
            captionView?.let { animateQuietEntry(it, spec.startDelayMs + captionDelay) }
            animateQuietTime(
                timeView,
                spec.startDelayMs + captionDelay + QuietSendAnimationTuning.TIME_DELAY_MS
            )
            animateQuietAvatar(
                avatar,
                spec.startDelayMs + if (captionView != null) {
                    captionDelay
                } else {
                    QuietSendAnimationTuning.PURE_MEDIA_AVATAR_DELAY_MS
                }
            )
        }
        captionView?.let { tightenMultilineBubbleWidth(it, captionMaxWidth) }
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
        val animation = consumePendingMessageAnimation()
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
        val timeView = makeTimeView(timeStr, Gravity.END)
        column.addView(messageBubble)
        column.addView(timeView)
        val avatar = attachRightAlignedContent(
            wrapper,
            column,
            includeAvatar = animation?.showAvatar ?: true
        )
        addToConversation(wrapper)
        animation?.let { spec ->
            animateQuietEntry(quoteBlock, spec.startDelayMs)
            animateQuietEntry(messageBubble, spec.startDelayMs + 24L)
            animateQuietTime(
                timeView,
                spec.startDelayMs + QuietSendAnimationTuning.TIME_DELAY_MS
            )
            animateQuietAvatar(avatar, spec.startDelayMs)
        }
        tightenMultilineBubbleWidth(messageBubble, messageBubbleMaxWidth)
        return wrapper
    }
}
