package com.haven.guiqi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import java.util.LinkedHashMap
import kotlin.math.roundToInt

/**
 * v11.8 双方同框的聊天场景预览。
 *
 * 同时预览普通颜色、保真画框与手动九宫格图片气泡，并读取当前聊天背景、双方头像、
 * 各自头像框与变换参数，让真实聊天应用前后的效果保持一致。
 */
class BubbleStylePreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val density = resources.displayMetrics.density
    private val colors = ThemeHelper.getColors(context)
    private val appearanceStorage = ChatAppearanceStorage(context)
    private val bubbleStyleStorage = BubbleStyleStorage(context)

    private var friendId = ""
    private var friendName = "住户"
    private var activeTarget = BubbleStyleStorage.Target.FRIEND
    private var friendStyle = bubbleStyleStorage.defaultStyle(BubbleStyleStorage.Target.FRIEND)
    private var userStyle = bubbleStyleStorage.defaultStyle(BubbleStyleStorage.Target.USER)

    private var avatarDisplayMode = ChatAppearanceStorage.AvatarDisplayMode.AI_ONLY
    private var friendAvatarView: View? = null
    private var userAvatarView: View? = null
    private var sceneBackground: Drawable? = null
    private var backgroundGeneration = 0

    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1).toFloat()
        pathEffect = DashPathEffect(floatArrayOf(dp(5).toFloat(), dp(4).toFloat()), 0f)
    }
    private val path = Path()
    private val shadowPath = Path()
    private val shadowRect = RectF()
    private val shadowRadii = FloatArray(8)
    private val textLayoutCache = object : LinkedHashMap<String, StaticLayout>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, StaticLayout>?
        ): Boolean = size > 16
    }
    private var longMessagePreview = true

    private val avatarSizeDp = 30
    private val sceneHeightDp = 420
    private val sideInsetDp = 12
    private val avatarGapDp = 8
    private val friendRowTopDp = 48
    private val userRowTopDp = 225

    init {
        setWillNotDraw(false)
        clipChildren = true
        clipToPadding = true
        minimumHeight = dp(sceneHeightDp)
        contentDescription = "住户和我的聊天气泡、头像与头像框同框预览"
    }

    fun bindConversation(friendId: String, friendName: String) {
        val changed = this.friendId != friendId
        this.friendId = friendId
        this.friendName = friendName.ifBlank { "住户" }
        val latestDisplayMode = appearanceStorage.getAvatarDisplayMode(friendId)
        val displayModeChanged = latestDisplayMode != avatarDisplayMode
        avatarDisplayMode = latestDisplayMode
        if (changed || displayModeChanged ||
            (avatarDisplayMode.showsFriendAvatar && friendAvatarView == null) ||
            (avatarDisplayMode.showsUserAvatar && userAvatarView == null)
        ) {
            rebuildAvatarViews()
            requestBackgroundReload()
        }
        invalidate()
    }

    fun setPreview(
        friendStyle: BubbleStyleStorage.BubbleStyle,
        userStyle: BubbleStyleStorage.BubbleStyle,
        activeTarget: BubbleStyleStorage.Target
    ) {
        this.friendStyle = friendStyle
        this.userStyle = userStyle
        this.activeTarget = activeTarget
        postInvalidateOnAnimation()
    }

    /** 在短消息与长消息实装尺寸之间切换，默认展示更容易暴露拉伸问题的长消息。 */
    fun setLongMessagePreview(enabled: Boolean) {
        if (longMessagePreview == enabled) return
        longMessagePreview = enabled
        clearTextLayoutCache()
        postInvalidateOnAnimation()
    }

    /** 头像或头像框在别处改变后，可重新读取当前聊天的实际配置。 */
    fun refreshConversationAssets() {
        avatarDisplayMode = appearanceStorage.getAvatarDisplayMode(friendId)
        rebuildAvatarViews()
        requestBackgroundReload()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0 && (w != oldw || h != oldh)) requestBackgroundReload()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val desiredHeight = dp(sceneHeightDp)
        if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.EXACTLY) {
            setMeasuredDimension(
                measuredWidth,
                resolveSize(desiredHeight, heightMeasureSpec)
            )
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val contentWidth = right - left
        friendAvatarView?.let { avatar ->
            val avatarLeft = dp(sideInsetDp)
            val avatarTop = dp(friendRowTopDp)
            avatar.layout(
                avatarLeft,
                avatarTop,
                avatarLeft + avatar.measuredWidth,
                avatarTop + avatar.measuredHeight
            )
        }
        userAvatarView?.let { avatar ->
            val avatarLeft = contentWidth - dp(sideInsetDp) - avatar.measuredWidth
            val avatarTop = dp(userRowTopDp)
            avatar.layout(
                avatarLeft,
                avatarTop,
                avatarLeft + avatar.measuredWidth,
                avatarTop + avatar.measuredHeight
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawSceneBackground(canvas)
        drawHeader(canvas)

        val friendSample = if (longMessagePreview && activeTarget == BubbleStyleStorage.Target.FRIEND) {
            "$friendName：这是一段较长的实装预览。角花、边框和侧边装饰应该保持原样，只有中间空白区域负责向下伸长。"
        } else {
            "$friendName：短消息预览。"
        }
        drawBubbleSafely(
            canvas = canvas,
            target = BubbleStyleStorage.Target.FRIEND,
            sourceStyle = friendStyle,
            sample = friendSample,
            time = "19:38"
        )

        val userSample = if (longMessagePreview && activeTarget == BubbleStyleStorage.Target.USER) {
            "这是一段较长的实装预览。用它检查图片气泡变高以后，顶部、底部与两侧装饰会不会被拉坏。"
        } else {
            "短消息预览。"
        }
        drawBubbleSafely(
            canvas = canvas,
            target = BubbleStyleStorage.Target.USER,
            sourceStyle = userStyle,
            sample = userSample,
            time = "19:39"
        )
    }

    private fun drawBubbleSafely(
        canvas: Canvas,
        target: BubbleStyleStorage.Target,
        sourceStyle: BubbleStyleStorage.BubbleStyle,
        sample: String,
        time: String
    ) {
        val firstAttempt = runCatching {
            val rect = if (target == BubbleStyleStorage.Target.FRIEND) {
                friendBubbleRect(sourceStyle, sample)
            } else {
                userBubbleRect(sourceStyle, sample)
            }
            drawBubble(canvas, rect, target, sourceStyle, sample, time)
        }
        if (firstAttempt.isSuccess) return

        android.util.Log.e(
            "BubbleStylePreview",
            "Code bubble preview failed; falling back to the previous safe renderer",
            firstAttempt.exceptionOrNull()
        )
        val fallbackMode = if (sourceStyle.fillMode.usesImage) {
            BubbleStyleStorage.FillMode.IMAGE
        } else {
            BubbleStyleStorage.FillMode.BASIC
        }
        val fallback = sourceStyle.copy(fillMode = fallbackMode, codeCss = "")
        runCatching {
            val rect = if (target == BubbleStyleStorage.Target.FRIEND) {
                friendBubbleRect(fallback, sample)
            } else {
                userBubbleRect(fallback, sample)
            }
            drawBubble(canvas, rect, target, fallback, sample, time)
        }.onFailure { fallbackError ->
            android.util.Log.e(
                "BubbleStylePreview",
                "Fallback bubble preview also failed",
                fallbackError
            )
        }
    }

    override fun onDetachedFromWindow() {
        backgroundGeneration += 1
        appearanceStorage.releaseDrawable(sceneBackground)
        sceneBackground = null
        super.onDetachedFromWindow()
    }

    private fun drawSceneBackground(canvas: Canvas) {
        val drawable = sceneBackground
        if (drawable == null) {
            canvas.drawColor(colors.backgroundSecondary)
        } else {
            drawable.bounds = android.graphics.Rect(0, 0, width, height)
            drawable.draw(canvas)
        }
    }

    private fun drawHeader(canvas: Canvas) {
        val headerRect = RectF(
            dp(10).toFloat(),
            dp(9).toFloat(),
            width - dp(10).toFloat(),
            dp(37).toFloat()
        )
        headerPaint.style = Paint.Style.FILL
        headerPaint.color = if (ThemeHelper.isDark(context)) {
            Color.argb(142, 15, 18, 28)
        } else {
            Color.argb(158, 255, 255, 255)
        }
        canvas.drawRoundRect(headerRect, dp(12).toFloat(), dp(12).toFloat(), headerPaint)

        labelPaint.color = colors.textPrimary
        labelPaint.textSize = sp(11.5f)
        labelPaint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        val targetLabel = if (activeTarget == BubbleStyleStorage.Target.FRIEND) "住户" else "我"
        val lengthLabel = if (longMessagePreview) "长消息实装尺寸" else "短消息实装尺寸"
        canvas.drawText(
            "聊天实装预览 · $lengthLabel · 编辑：$targetLabel",
            dp(20).toFloat(),
            dp(28).toFloat(),
            labelPaint
        )
    }

    private fun friendBubbleRect(
        style: BubbleStyleStorage.BubbleStyle,
        sample: String
    ): RectF {
        val hasAvatar = avatarDisplayMode.showsFriendAvatar && friendAvatarView != null
        val baseLeft = if (hasAvatar) {
            dp(sideInsetDp) + friendAvatarView!!.measuredWidth + dp(avatarGapDp)
        } else {
            dp(sideInsetDp)
        }
        val relativeOffset = if (style.fillMode.usesImage) {
            dp(style.imageAvatarOffsetDp)
        } else {
            0
        }
        val left = baseLeft + relativeOffset
        val availableRight = width - dp(14)
        val maxWidth = minOf(
            (width * 0.80f).roundToInt(),
            availableRight - left
        ).coerceAtLeast(dp(48))
        val (bubbleWidth, bubbleHeight) = measureBubble(
            BubbleStyleStorage.Target.FRIEND,
            style,
            sample,
            maxWidth
        )
        return RectF(
            left.toFloat(),
            (dp(friendRowTopDp) + imageVerticalOffsetPx(style)).toFloat(),
            (left + bubbleWidth).toFloat(),
            (dp(friendRowTopDp) + imageVerticalOffsetPx(style) + bubbleHeight).toFloat()
        )
    }

    private fun userBubbleRect(
        style: BubbleStyleStorage.BubbleStyle,
        sample: String
    ): RectF {
        val hasAvatar = avatarDisplayMode.showsUserAvatar && userAvatarView != null
        val baseRight = if (hasAvatar) {
            width - dp(sideInsetDp) - userAvatarView!!.measuredWidth - dp(avatarGapDp)
        } else {
            width - dp(sideInsetDp)
        }
        val relativeOffset = if (style.fillMode.usesImage) {
            dp(style.imageAvatarOffsetDp)
        } else {
            0
        }
        val right = baseRight - relativeOffset
        val availableLeft = dp(3)
        val maxWidth = minOf(
            (width * 0.78f).roundToInt(),
            right - availableLeft
        ).coerceAtLeast(dp(48))
        val (bubbleWidth, bubbleHeight) = measureBubble(
            BubbleStyleStorage.Target.USER,
            style,
            sample,
            maxWidth
        )
        return RectF(
            (right - bubbleWidth).toFloat(),
            (dp(userRowTopDp) + imageVerticalOffsetPx(style)).toFloat(),
            right.toFloat(),
            (dp(userRowTopDp) + imageVerticalOffsetPx(style) + bubbleHeight).toFloat()
        )
    }

    private fun measureBubble(
        target: BubbleStyleStorage.Target,
        sourceStyle: BubbleStyleStorage.BubbleStyle,
        sample: String,
        maxBubbleWidth: Int
    ): Pair<Int, Int> {
        val runtime = SafeBubbleCss.resolve(sourceStyle, target)
        val style = styleWithCodePadding(runtime)
        val imageBitmap = if (style.fillMode.usesImage) {
            BubbleImageBitmapCache.get(style.imagePath)
        } else {
            null
        }
        var paddings = basePaddings(target, style, imageBitmap != null, runtime.padding)
        var measuredWidth = maxBubbleWidth
        var measuredHeight = dp(96)

        repeat(4) {
            val maxTextWidth = (maxBubbleWidth - paddings[0] - paddings[2]).coerceAtLeast(1)
            val layout = sampleTextLayout(target, sample, maxTextWidth, runtime)
            var longestLine = 0f
            for (lineIndex in 0 until layout.lineCount) {
                longestLine = maxOf(longestLine, layout.getLineWidth(lineIndex))
            }
            val userTightExtra = if (
                target == BubbleStyleStorage.Target.USER && layout.lineCount > 1
            ) dp(3) else 0
            measuredWidth = (
                kotlin.math.ceil(longestLine.toDouble()).toInt() +
                    paddings[0] + paddings[2] + userTightExtra
                ).coerceIn(dp(48), maxBubbleWidth)
            measuredHeight = (layout.height + paddings[1] + paddings[3]).coerceAtLeast(dp(48))

            if (imageBitmap != null &&
                style.imageRenderMode == BubbleStyleStorage.ImageRenderMode.SMART_FRAME
            ) {
                val natural = SmartFrameBubbleRenderer.naturalSizePx(imageBitmap, density)
                measuredWidth = maxOf(measuredWidth, natural.widthPx).coerceAtMost(maxBubbleWidth)
                measuredHeight = maxOf(measuredHeight, natural.heightPx)
            }

            if (imageBitmap == null ||
                style.imageRenderMode != BubbleStyleStorage.ImageRenderMode.SMART_FRAME
            ) {
                return measuredWidth to measuredHeight
            }
            val resolved = resolvedPaddingsForRect(
                target = target,
                style = style,
                imageBitmap = imageBitmap,
                rect = RectF(0f, 0f, measuredWidth.toFloat(), measuredHeight.toFloat()),
                codePadding = runtime.padding
            )
            if (resolved.contentEquals(paddings)) {
                return measuredWidth to measuredHeight
            }
            paddings = resolved
        }
        return measuredWidth to measuredHeight
    }

    private fun styleWithCodePadding(
        runtime: SafeBubbleCss.RuntimeStyle
    ): BubbleStyleStorage.BubbleStyle {
        val padding = runtime.padding
        return if (padding != null && runtime.style.fillMode.usesImage) {
            runtime.style.copy(
                imagePaddingLeftDp = padding.left,
                imagePaddingTopDp = padding.top,
                imagePaddingRightDp = padding.right,
                imagePaddingBottomDp = padding.bottom
            )
        } else {
            runtime.style
        }
    }

    private fun basePaddings(
        target: BubbleStyleStorage.Target,
        style: BubbleStyleStorage.BubbleStyle,
        imageMode: Boolean,
        codePadding: SafeBubbleCss.InsetsDp? = null
    ): IntArray {
        if (!imageMode && codePadding != null) {
            return intArrayOf(
                dp(codePadding.left),
                dp(codePadding.top),
                dp(codePadding.right),
                dp(codePadding.bottom)
            )
        }
        if (imageMode) {
            return intArrayOf(
                dp(style.imagePaddingLeftDp),
                dp(style.imagePaddingTopDp),
                dp(style.imagePaddingRightDp),
                dp(style.imagePaddingBottomDp)
            )
        }
        return if (target == BubbleStyleStorage.Target.FRIEND) {
            intArrayOf(dp(11), dp(8), dp(11), dp(8))
        } else {
            intArrayOf(dp(9), dp(8), dp(7), dp(8))
        }
    }

    private fun resolvedPaddingsForRect(
        target: BubbleStyleStorage.Target,
        style: BubbleStyleStorage.BubbleStyle,
        imageBitmap: android.graphics.Bitmap?,
        rect: RectF,
        codePadding: SafeBubbleCss.InsetsDp? = null
    ): IntArray {
        val manual = basePaddings(target, style, imageBitmap != null, codePadding)
        if (imageBitmap == null ||
            style.imageRenderMode != BubbleStyleStorage.ImageRenderMode.SMART_FRAME
        ) {
            return manual
        }
        val auto = SmartFrameBubbleRenderer.contentInsets(imageBitmap, rect, density)
        val horizontalLimit = (rect.width() * 0.42f).roundToInt().coerceAtLeast(0)
        val verticalLimit = (rect.height() * 0.42f).roundToInt().coerceAtLeast(0)
        return intArrayOf(
            maxOf(manual[0], auto.left.roundToInt()).coerceAtMost(horizontalLimit),
            maxOf(manual[1], auto.top.roundToInt()).coerceAtMost(verticalLimit),
            maxOf(manual[2], auto.right.roundToInt()).coerceAtMost(horizontalLimit),
            maxOf(manual[3], auto.bottom.roundToInt()).coerceAtMost(verticalLimit)
        )
    }

    private fun drawBubble(
        canvas: Canvas,
        rect: RectF,
        target: BubbleStyleStorage.Target,
        sourceStyle: BubbleStyleStorage.BubbleStyle,
        sample: String,
        time: String
    ) {
        if (rect.width() <= dp(42) || rect.height() <= dp(30)) return

        val runtime = SafeBubbleCss.resolve(sourceStyle, target)
        val style = styleWithCodePadding(runtime)
        val mainRadius = dp(style.cornerRadiusDp).toFloat()
        val anchorRadius = dp(style.anchorCornerRadiusDp).toFloat()
        val radii = if (target == BubbleStyleStorage.Target.FRIEND) {
            floatArrayOf(
                anchorRadius, anchorRadius,
                mainRadius, mainRadius,
                mainRadius, mainRadius,
                mainRadius, mainRadius
            )
        } else {
            floatArrayOf(
                mainRadius, mainRadius,
                anchorRadius, anchorRadius,
                mainRadius, mainRadius,
                mainRadius, mainRadius
            )
        }

        path.reset()
        path.addRoundRect(rect, radii, Path.Direction.CW)

        drawLightweightShadow(canvas, rect, radii, style)

        val imageBitmap = if (style.fillMode.usesImage) {
            BubbleImageBitmapCache.get(style.imagePath)
        } else {
            null
        }
        if (imageBitmap != null) {
            val saveCount = canvas.save()
            val preserveSourceSilhouette =
                style.imageRenderMode == BubbleStyleStorage.ImageRenderMode.SMART_FRAME &&
                    SmartFrameGeometryCache.hasTransparentOuterCorners(imageBitmap)
            if (!preserveSourceSilhouette) {
                canvas.clipPath(path)
            }
            bubblePaint.style = Paint.Style.FILL
            bubblePaint.alpha = (255f * style.imageOpacityPercent.coerceIn(0, 100) / 100f)
                .roundToInt()
                .coerceIn(0, 255)
            when (style.imageRenderMode) {
                BubbleStyleStorage.ImageRenderMode.SMART_FRAME -> {
                    SmartFrameBubbleRenderer.draw(
                        canvas = canvas,
                        bitmap = imageBitmap,
                        destination = rect,
                        density = density,
                        paint = bubblePaint
                    )
                }

                BubbleStyleStorage.ImageRenderMode.NINE_SLICE -> {
                    NineSliceBubbleRenderer.draw(
                        canvas = canvas,
                        bitmap = imageBitmap,
                        destination = rect,
                        density = density,
                        leftPercent = style.imageFixedLeftPercent,
                        topPercent = style.imageFixedTopPercent,
                        rightPercent = style.imageFixedRightPercent,
                        bottomPercent = style.imageFixedBottomPercent,
                        paint = bubblePaint
                    )
                }
            }
            canvas.restoreToCount(saveCount)
            bubblePaint.alpha = 255
        } else {
            bubblePaint.style = Paint.Style.FILL
            bubblePaint.alpha = 255
            bubblePaint.color = style.backgroundColor.withAlphaPercent(style.backgroundOpacityPercent)
            canvas.drawPath(path, bubblePaint)
        }

        if (style.borderWidthDp > 0 && Color.alpha(style.borderColor) > 0) {
            borderPaint.strokeWidth = dp(style.borderWidthDp).toFloat()
            borderPaint.color = style.borderColor
            canvas.drawPath(path, borderPaint)
        }

        val paddings = resolvedPaddingsForRect(
            target,
            style,
            imageBitmap,
            rect,
            runtime.padding
        )
        val leftPadding = paddings[0]
        val topPadding = paddings[1]
        val rightPadding = paddings[2]
        val textWidth = (rect.width() - leftPadding - rightPadding).roundToInt().coerceAtLeast(1)
        val layout = sampleTextLayout(target, sample, textWidth, runtime)
        layout.paint.color = style.textColor
        canvas.save()
        canvas.translate(rect.left + leftPadding, rect.top + topPadding)
        layout.draw(canvas)
        canvas.restore()

        if (imageBitmap != null && activeTarget == target) {
            drawImageGuides(canvas, rect, style)
        }

        timePaint.color = colors.timeText
        timePaint.textSize = sp(9.5f)
        timePaint.setShadowLayer(dp(1).toFloat(), 0f, dp(1).toFloat(), 0x55000000)
        val timeWidth = timePaint.measureText(time)
        val timeX = if (target == BubbleStyleStorage.Target.FRIEND) {
            rect.left + dp(4)
        } else {
            rect.right - timeWidth - dp(4)
        }
        canvas.drawText(time, timeX, rect.bottom + dp(15), timePaint)
        timePaint.clearShadowLayer()
    }


    /** 图片模式编辑时，用两种虚线提示九宫格中心伸缩区和文字安全区。 */
    private fun drawImageGuides(
        canvas: Canvas,
        rect: RectF,
        style: BubbleStyleStorage.BubbleStyle
    ) {
        val bitmap = BubbleImageBitmapCache.get(style.imagePath) ?: return
        guidePaint.color = Color.argb(205, 255, 183, 77)
        if (style.imageRenderMode == BubbleStyleStorage.ImageRenderMode.SMART_FRAME) {
            val stretchRect = SmartFrameBubbleRenderer.stretchDestinationRect(bitmap, rect, density)
            canvas.drawRect(stretchRect, guidePaint)
        } else {
            val referencePx = dp(96).toFloat()
            val left = (referencePx * style.imageFixedLeftPercent / 100f)
                .coerceAtMost(rect.width() * 0.45f)
            val top = (referencePx * style.imageFixedTopPercent / 100f)
                .coerceAtMost(rect.height() * 0.45f)
            val right = (referencePx * style.imageFixedRightPercent / 100f)
                .coerceAtMost(rect.width() * 0.45f)
            val bottom = (referencePx * style.imageFixedBottomPercent / 100f)
                .coerceAtMost(rect.height() * 0.45f)
            canvas.drawRect(
                rect.left + left,
                rect.top + top,
                rect.right - right,
                rect.bottom - bottom,
                guidePaint
            )
        }

        val paddings = resolvedPaddingsForRect(activeTarget, style, bitmap, rect)
        guidePaint.color = Color.argb(220, 95, 210, 255)
        canvas.drawRect(
            rect.left + paddings[0],
            rect.top + paddings[1],
            rect.right - paddings[2],
            rect.bottom - paddings[3],
            guidePaint
        )
    }

    /**
     * 预览区只需要两枚气泡，不值得为了模糊阴影把整个 View 切到软件绘制。
     * 这里用三层低透明度、轻微外扩的圆角路径近似柔和阴影；全部走普通
     * Canvas 绘制，拖动“阴影浓度”时不会反复做昂贵的软件模糊。
     */
    private fun drawLightweightShadow(
        canvas: Canvas,
        rect: RectF,
        radii: FloatArray,
        style: BubbleStyleStorage.BubbleStyle
    ) {
        if (style.shadowRadiusDp <= 0 || style.shadowOpacityPercent <= 0) return

        val maxSpread = dp(style.shadowRadiusDp).toFloat() * 0.38f
        val baseAlpha = (255f * style.shadowOpacityPercent.coerceIn(0, 100) / 100f)
        val layerWeights = floatArrayOf(0.12f, 0.20f, 0.34f)

        for (index in layerWeights.indices) {
            val progress = (layerWeights.size - index).toFloat() / layerWeights.size.toFloat()
            val spread = maxSpread * progress
            val offsetY = dp(1).toFloat() + spread * 0.28f

            shadowRect.set(
                rect.left - spread,
                rect.top - spread + offsetY,
                rect.right + spread,
                rect.bottom + spread + offsetY
            )
            for (radiusIndex in radii.indices) {
                shadowRadii[radiusIndex] = radii[radiusIndex] + spread
            }
            shadowPath.reset()
            shadowPath.addRoundRect(shadowRect, shadowRadii, Path.Direction.CW)
            shadowPaint.color = Color.argb(
                (baseAlpha * layerWeights[index]).roundToInt().coerceIn(0, 255),
                0,
                0,
                0
            )
            canvas.drawPath(shadowPath, shadowPaint)
        }
    }

    private fun sampleTextLayout(
        target: BubbleStyleStorage.Target,
        sample: String,
        width: Int,
        runtime: SafeBubbleCss.RuntimeStyle
    ): StaticLayout {
        val maxLines = if (longMessagePreview && activeTarget == target) 7 else 3
        val fontSize = runtime.fontSizeSp ?: 14f
        val lineHeight = runtime.lineHeightMultiplier ?: 1.35f
        val fontWeight = runtime.fontWeight ?: 400
        val letterSpacing = runtime.letterSpacingEm ?: 0f
        val cacheKey = buildString {
            append(target.name).append('|')
            append(width).append('|').append(maxLines).append('|')
            append(fontSize).append('|').append(lineHeight).append('|')
            append(fontWeight).append('|').append(letterSpacing).append('|')
            append(sample)
        }
        textLayoutCache[cacheKey]?.let { return it }

        val layoutPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = sp(fontSize)
            typeface = Typeface.create(
                Typeface.DEFAULT,
                if (fontWeight >= 600) Typeface.BOLD else Typeface.NORMAL
            )
            this.letterSpacing = letterSpacing
        }
        val created = StaticLayout.Builder.obtain(sample, 0, sample.length, layoutPaint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setMaxLines(maxLines)
            .setLineSpacing(0f, lineHeight)
            .build()
        textLayoutCache[cacheKey] = created
        return created
    }

    private fun clearTextLayoutCache() {
        textLayoutCache.clear()
    }

    private fun rebuildAvatarViews() {
        removeAllViews()
        friendAvatarView = null
        userAvatarView = null

        val friend = FriendStorage(context).getFriend(friendId)
        val friendFrameFile = appearanceStorage.getAvatarFrameFile(
            friendId,
            ChatAppearanceStorage.AvatarTarget.FRIEND
        )
        val friendTransform = appearanceStorage.getAvatarFrameTransform(
            friendId,
            ChatAppearanceStorage.AvatarTarget.FRIEND
        )
        if (avatarDisplayMode.showsFriendAvatar) {
            val friendAvatar = FriendAvatarHelper.create(
                context = context,
                avatarPath = friend?.avatarPath.orEmpty(),
                icon = friend?.icon ?: "★",
                sizeDp = avatarSizeDp,
                framePath = friendFrameFile?.absolutePath.orEmpty(),
                frameScalePercent = friendTransform.scalePercent,
                frameOffsetXPercent = friendTransform.offsetXPercent,
                frameOffsetYPercent = friendTransform.offsetYPercent,
                avatarShape = appearanceStorage.getAvatarShape(
                    friendId,
                    ChatAppearanceStorage.AvatarTarget.FRIEND
                )
            )
            friendAvatarView = friendAvatar
            addAvatarChild(friendAvatar)
        }

        val userFrameFile = appearanceStorage.getAvatarFrameFile(
            friendId,
            ChatAppearanceStorage.AvatarTarget.USER
        )
        val userTransform = appearanceStorage.getAvatarFrameTransform(
            friendId,
            ChatAppearanceStorage.AvatarTarget.USER
        )
        if (avatarDisplayMode.showsUserAvatar) {
            val userAvatar = FriendAvatarHelper.createUserAvatar(
                context = context,
                sizeDp = avatarSizeDp,
                framePath = userFrameFile?.absolutePath.orEmpty(),
                frameScalePercent = userTransform.scalePercent,
                frameOffsetXPercent = userTransform.offsetXPercent,
                frameOffsetYPercent = userTransform.offsetYPercent,
                avatarShape = appearanceStorage.getAvatarShape(
                    friendId,
                    ChatAppearanceStorage.AvatarTarget.USER
                )
            )
            userAvatarView = userAvatar
            addAvatarChild(userAvatar)
        }

        requestLayout()
        invalidate()
    }

    private fun addAvatarChild(avatar: View) {
        val original = avatar.layoutParams
        val width = original?.width?.takeIf { it > 0 } ?: dp(avatarSizeDp)
        val height = original?.height?.takeIf { it > 0 } ?: dp(avatarSizeDp)
        avatar.isClickable = false
        avatar.isFocusable = false
        avatar.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(avatar, FrameLayout.LayoutParams(width, height))
    }

    private fun requestBackgroundReload() {
        val currentFriendId = friendId
        val targetWidth = width
        val targetHeight = height
        val generation = ++backgroundGeneration
        if (currentFriendId.isEmpty() || targetWidth <= 0 || targetHeight <= 0) {
            appearanceStorage.releaseDrawable(sceneBackground)
            sceneBackground = null
            invalidate()
            return
        }

        Thread {
            val drawable = appearanceStorage.loadBackgroundDrawable(
                currentFriendId,
                targetWidth,
                targetHeight
            )
            post {
                if (generation != backgroundGeneration || currentFriendId != friendId) {
                    appearanceStorage.releaseDrawable(drawable)
                    return@post
                }
                appearanceStorage.releaseDrawable(sceneBackground)
                sceneBackground = drawable
                invalidate()
            }
        }.start()
    }

    private fun imageVerticalOffsetPx(style: BubbleStyleStorage.BubbleStyle): Int =
        if (style.fillMode.usesImage) {
            dp(style.imageVerticalOffsetDp)
        } else {
            0
        }

    private fun Int.withAlphaPercent(percent: Int): Int = Color.argb(
        (Color.alpha(this) * percent.coerceIn(0, 100) / 100f).roundToInt(),
        Color.red(this),
        Color.green(this),
        Color.blue(this)
    )

    private fun dp(value: Int): Int = (value * density).roundToInt()
    private fun sp(value: Float): Float = value * resources.displayMetrics.scaledDensity
}
