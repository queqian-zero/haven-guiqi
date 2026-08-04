package com.haven.guiqi

import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.graphics.Typeface
import android.util.TypedValue
import android.os.Build
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.TextView
import java.util.WeakHashMap
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 把气泡编辑器保存的样式应用到真实聊天 TextView。
 *
 * 这里只处理普通文字气泡；图片消息、表情包、卡片、思考区和系统提示继续使用
 * 各自原有的渲染方式。图片气泡素材共用采样缓存，不会为每条消息重复解码。
 */
internal object BubbleStyleApplier {

    private const val MIN_LIVE_SHADOW_ELEVATION_DP = 0.5f
    private const val MAX_LIVE_SHADOW_ELEVATION_DP = 2.5f
    private val smartPaddingListeners = WeakHashMap<TextView, View.OnLayoutChangeListener>()

    fun apply(
        view: TextView,
        style: BubbleStyleStorage.BubbleStyle,
        target: BubbleStyleStorage.Target
    ) {
        val runtime = SafeBubbleCss.resolve(style, target)
        val codePadding = runtime.padding
        val resolvedStyle = if (codePadding != null && runtime.style.fillMode.usesImage) {
            runtime.style.copy(
                imagePaddingLeftDp = codePadding.left,
                imagePaddingTopDp = codePadding.top,
                imagePaddingRightDp = codePadding.right,
                imagePaddingBottomDp = codePadding.bottom
            )
        } else {
            runtime.style
        }

        val imageApplied = if (resolvedStyle.fillMode.usesImage) {
            applyImageBackground(view, resolvedStyle, target)
        } else {
            false
        }
        if (!imageApplied) {
            applyBasicBackground(view, resolvedStyle, target)
            codePadding?.let { padding ->
                view.setPadding(
                    view.dp(padding.left),
                    view.dp(padding.top),
                    view.dp(padding.right),
                    view.dp(padding.bottom)
                )
            }
        }

        view.setTextColor(resolvedStyle.textColor)
        runtime.fontSizeSp?.let { size ->
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        }
        runtime.fontWeight?.let { weight ->
            view.setTypeface(view.typeface, if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)
        }
        runtime.lineHeightMultiplier?.let { multiplier ->
            view.setLineSpacing(0f, multiplier)
        }
        runtime.letterSpacingEm?.let { spacing ->
            view.letterSpacing = spacing
        }
        applyShadow(view, resolvedStyle)
    }

    private fun applyImageBackground(
        view: TextView,
        style: BubbleStyleStorage.BubbleStyle,
        target: BubbleStyleStorage.Target
    ): Boolean {
        val bitmap = BubbleImageBitmapCache.get(style.imagePath) ?: return false
        val mainRadius = view.dp(style.cornerRadiusDp).toFloat()
        val anchorRadius = view.dp(style.anchorCornerRadiusDp).toFloat()
        val radii = bubbleCornerRadii(target, mainRadius, anchorRadius)

        when (style.imageRenderMode) {
            BubbleStyleStorage.ImageRenderMode.SMART_FRAME -> {
                val image = SmartFrameBubbleDrawable(
                    bitmap = bitmap,
                    density = view.resources.displayMetrics.density,
                    cornerRadiiPx = radii
                ).apply {
                    setAlpha(style.imageOpacityPercent.toDrawableAlpha())
                }
                view.background = withOptionalBorder(view, image, style, radii)
                installSmartPadding(view, bitmap, style)
            }

            BubbleStyleStorage.ImageRenderMode.NINE_SLICE -> {
                clearSmartPadding(view)
                val image = NineSliceBubbleDrawable(
                    bitmap = bitmap,
                    density = view.resources.displayMetrics.density,
                    leftPercent = style.imageFixedLeftPercent,
                    topPercent = style.imageFixedTopPercent,
                    rightPercent = style.imageFixedRightPercent,
                    bottomPercent = style.imageFixedBottomPercent,
                    cornerRadiiPx = radii
                ).apply {
                    setAlpha(style.imageOpacityPercent.toDrawableAlpha())
                }
                view.background = withOptionalBorder(view, image, style, radii)
                view.setPadding(
                    view.dp(style.imagePaddingLeftDp),
                    view.dp(style.imagePaddingTopDp),
                    view.dp(style.imagePaddingRightDp),
                    view.dp(style.imagePaddingBottomDp)
                )
            }
        }
        return true
    }

    private fun withOptionalBorder(
        view: TextView,
        image: Drawable,
        style: BubbleStyleStorage.BubbleStyle,
        radii: FloatArray
    ): Drawable {
        if (style.borderWidthDp <= 0 || Color.alpha(style.borderColor) <= 0) return image
        val border = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.TRANSPARENT)
            cornerRadii = radii
            setStroke(view.dp(style.borderWidthDp), style.borderColor)
        }
        return LayerDrawable(arrayOf(image, border))
    }

    /**
     * 保真画框模式会从素材自动推断中心文字框。TextView 的最终高宽只有布局后
     * 才知道，因此在布局回调里把源图安全区映射成真实 padding；手动滑杆作为
     * 最小额外留白，永远不会把自动安全区缩小。
     */
    private fun installSmartPadding(
        view: TextView,
        bitmap: android.graphics.Bitmap,
        style: BubbleStyleStorage.BubbleStyle
    ) {
        clearSmartPadding(view)
        val manualLeft = view.dp(style.imagePaddingLeftDp)
        val manualTop = view.dp(style.imagePaddingTopDp)
        val manualRight = view.dp(style.imagePaddingRightDp)
        val manualBottom = view.dp(style.imagePaddingBottomDp)
        view.setPadding(manualLeft, manualTop, manualRight, manualBottom)

        val density = view.resources.displayMetrics.density
        val naturalSize = SmartFrameBubbleRenderer.naturalSizePx(bitmap, density)
        view.minWidth = naturalSize.widthPx
        view.minHeight = naturalSize.heightPx

        val geometry = SmartFrameGeometryCache.get(bitmap)
        val listener = object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                targetView: View,
                left: Int,
                top: Int,
                right: Int,
                bottom: Int,
                oldLeft: Int,
                oldTop: Int,
                oldRight: Int,
                oldBottom: Int
            ) {
                val textView = targetView as? TextView ?: return
                val width = right - left
                val height = bottom - top
                if (width <= 1 || height <= 1) return

                val auto = SmartFrameBubbleRenderer.contentInsets(
                    bitmap = bitmap,
                    destination = RectF(0f, 0f, width.toFloat(), height.toFloat()),
                    density = density,
                    geometry = geometry
                )
                val horizontalLimit = (width * 0.42f).roundToInt().coerceAtLeast(0)
                val verticalLimit = (height * 0.42f).roundToInt().coerceAtLeast(0)
                val resolvedLeft = max(manualLeft, auto.left.roundToInt())
                    .coerceAtMost(horizontalLimit)
                val resolvedTop = max(manualTop, auto.top.roundToInt())
                    .coerceAtMost(verticalLimit)
                val resolvedRight = max(manualRight, auto.right.roundToInt())
                    .coerceAtMost(horizontalLimit)
                val resolvedBottom = max(manualBottom, auto.bottom.roundToInt())
                    .coerceAtMost(verticalLimit)

                if (textView.paddingLeft != resolvedLeft ||
                    textView.paddingTop != resolvedTop ||
                    textView.paddingRight != resolvedRight ||
                    textView.paddingBottom != resolvedBottom
                ) {
                    textView.setPadding(resolvedLeft, resolvedTop, resolvedRight, resolvedBottom)
                }
            }
        }
        smartPaddingListeners[view] = listener
        view.addOnLayoutChangeListener(listener)
        view.requestLayout()
    }

    private fun clearSmartPadding(view: TextView) {
        smartPaddingListeners.remove(view)?.let(view::removeOnLayoutChangeListener)
        view.minWidth = 0
        view.minHeight = 0
    }

    private fun applyBasicBackground(
        view: TextView,
        style: BubbleStyleStorage.BubbleStyle,
        target: BubbleStyleStorage.Target
    ) {
        clearSmartPadding(view)
        val mainRadius = view.dp(style.cornerRadiusDp).toFloat()
        val anchorRadius = view.dp(style.anchorCornerRadiusDp).toFloat()
        val radii = bubbleCornerRadii(target, mainRadius, anchorRadius)

        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = radii
            setColor(style.backgroundColor.withAlphaPercent(style.backgroundOpacityPercent))
            if (style.borderWidthDp > 0 && Color.alpha(style.borderColor) > 0) {
                setStroke(view.dp(style.borderWidthDp), style.borderColor)
            }
        }
    }

    private fun bubbleCornerRadii(
        target: BubbleStyleStorage.Target,
        mainRadius: Float,
        anchorRadius: Float
    ): FloatArray = when (target) {
        BubbleStyleStorage.Target.FRIEND -> floatArrayOf(
            anchorRadius, anchorRadius,
            mainRadius, mainRadius,
            mainRadius, mainRadius,
            mainRadius, mainRadius
        )

        BubbleStyleStorage.Target.USER -> floatArrayOf(
            mainRadius, mainRadius,
            anchorRadius, anchorRadius,
            mainRadius, mainRadius,
            mainRadius, mainRadius
        )
    }

    private fun applyShadow(view: TextView, style: BubbleStyleStorage.BubbleStyle) {
        val hasShadow = style.shadowRadiusDp > 0 && style.shadowOpacityPercent > 0
        view.clipToOutline = false
        view.outlineProvider = ViewOutlineProvider.BACKGROUND
        if (hasShadow) {
            val radiusRatio = style.shadowRadiusDp.toFloat() /
                BubbleStyleStorage.MAX_SHADOW_RADIUS_DP.toFloat()
            val safeElevationDp = MIN_LIVE_SHADOW_ELEVATION_DP +
                radiusRatio * (MAX_LIVE_SHADOW_ELEVATION_DP - MIN_LIVE_SHADOW_ELEVATION_DP)
            view.elevation = view.dpFloat(safeElevationDp)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val shadowColor = Color.BLACK.withAlphaPercent(style.shadowOpacityPercent)
                view.outlineAmbientShadowColor = shadowColor
                view.outlineSpotShadowColor = shadowColor
            }
        } else {
            view.elevation = 0f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                view.outlineAmbientShadowColor = Color.TRANSPARENT
                view.outlineSpotShadowColor = Color.TRANSPARENT
            }
        }
    }

    private fun TextView.dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt().coerceAtLeast(0)

    private fun TextView.dpFloat(value: Float): Float =
        (value * resources.displayMetrics.density).coerceAtLeast(0f)

    private fun Int.toDrawableAlpha(): Int =
        (255f * coerceIn(0, 100) / 100f).roundToInt().coerceIn(0, 255)

    private fun Int.withAlphaPercent(percent: Int): Int = Color.argb(
        (Color.alpha(this) * percent.coerceIn(0, 100) / 100f).roundToInt(),
        Color.red(this),
        Color.green(this),
        Color.blue(this)
    )
}
