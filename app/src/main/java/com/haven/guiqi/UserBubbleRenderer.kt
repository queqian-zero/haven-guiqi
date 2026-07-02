package com.haven.guiqi

import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/**
 * UserBubbleRenderer — 用户侧气泡渲染
 *
 * 从 BubbleRenderer 拆出来。
 * 负责：纯文字气泡、单图、多图网格、引用回复、历史占位。
 * BubbleRenderer 通过委托调用这里，ChatConversationActivity 不用改。
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
            text = timeStr; textSize = 9f
            setTextColor(c.timeText)
            setPadding(if (isRight) 0 else dp(4), 0, if (isRight) dp(4) else 0, 0)
        }
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
            setTextColor(c.textOnAccent); textSize = 14f
            setLineSpacing(0f, 1.35f)
            setPadding(dp(11), dp(8), dp(11), dp(8))
            setBackgroundResource(R.drawable.chat_bubble_user)
            setOnLongClickListener { onMessageMenu?.invoke(msg, "我"); true }
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
            setOnLongClickListener { onMessageMenu?.invoke(msg, "我"); true }
        })
        column.addView(makeTimeView(timeStr, Gravity.END))
        wrapper.addView(column)
        messagesContainer.addView(wrapper)
        scrollToBottom()
        return wrapper
    }
}