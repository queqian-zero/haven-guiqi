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

    private val c get() = ThemeHelper.getColors(activity)
    private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()
    private val screenWidth get() = activity.resources.displayMetrics.widthPixels

    private fun scrollToBottom() {
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    private fun makeTimeView(timeStr: String, align: Int): TextView {
        val isRight = align == Gravity.END
        return TextView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2) }
            gravity = align
            text = timeStr
            textSize = 9f
            setTextColor(c.timeText)
            setPadding(if (isRight) 0 else dp(4), 0, if (isRight) dp(4) else 0, 0)
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
            setTextColor(c.textOnAccent)
            textSize = 14f
            setLineSpacing(0f, 1.35f)
            setPadding(dp(11), dp(8), dp(11), dp(8))
            setBackgroundResource(R.drawable.chat_bubble_user)
            setOnLongClickListener { onMessageMenu?.invoke(msg, "我"); true }
        }
        column.addView(bubble)
        column.addView(makeTimeView(timeStr, Gravity.END))
        wrapper.addView(column)
        addToConversation(wrapper)
        return wrapper
    }

    /** 用户普通单张图片：不套彩色气泡，按原比例显示为圆角图片卡片。 */
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
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val (sourceWidth, sourceHeight) = readImageSize(imagePath) ?: (dp(220) to dp(220))
        val ratio = sourceWidth.toFloat() / sourceHeight.toFloat()
        val maxDisplayWidth = when {
            ratio > 1.15f -> minOf((screenWidth * 0.72f).toInt(), dp(280))
            else -> minOf((screenWidth * 0.64f).toInt(), dp(220))
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
        wrapper.addView(column)
        addToConversation(wrapper)
    }

    /** 用户表情包：保留透明通道，不套气泡、不加白底。 */
    fun addStickerBubble(imagePath: String, timeStr: String, caption: String = "") {
        val wrapper = LinearLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
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
        val maxSide = minOf((screenWidth * 0.46f).toInt(), dp(156))
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
                maxWidth = minOf((screenWidth * 0.72f).toInt(), dp(260))
                text = MarkdownRenderer.render(caption)
                setTextColor(c.textOnAccent)
                textSize = 13f
                setLineSpacing(0f, 1.3f)
                setPadding(dp(10), dp(6), dp(10), dp(6))
                setBackgroundResource(R.drawable.chat_bubble_user)
            })
        }
        column.addView(makeTimeView(timeStr, Gravity.END))
        wrapper.addView(column)
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
        wrapper.addView(column)
        addToConversation(wrapper)
    }

    /** 历史加载时在指定位置插入图片占位气泡 */
    fun addImageBubbleAt(imagePath: String, timeStr: String, caption: String, index: Int): View {
        val wrapper = LinearLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val bubble = TextView(activity).apply {
            text = "[图片]${if (caption.isNotEmpty()) " $caption" else ""}"
            setTextColor(c.textSecondary)
            textSize = 13f
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
            maxWidth = (screenWidth * 0.65).toInt()
        })
        quoteBlock.addView(bar)
        quoteBlock.addView(quoteText)
        column.addView(quoteBlock)
        column.addView(TextView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(3) }
            maxWidth = (screenWidth * 0.82).toInt()
            text = MarkdownRenderer.render(msg)
            setTextColor(c.textOnAccent)
            textSize = 14f
            setLineSpacing(0f, 1.35f)
            setPadding(dp(11), dp(8), dp(11), dp(8))
            setBackgroundResource(R.drawable.chat_bubble_user)
            setOnLongClickListener { onMessageMenu?.invoke(msg, "我"); true }
        })
        column.addView(makeTimeView(timeStr, Gravity.END))
        wrapper.addView(column)
        addToConversation(wrapper)
        return wrapper
    }
}
