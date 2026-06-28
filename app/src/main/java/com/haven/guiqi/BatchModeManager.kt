package com.haven.guiqi

import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * BatchModeManager — 分条模式的 UI 状态管理
 *
 * 从 ChatConversationActivity 拆出来。
 * 负责分条模式的开关、待发区渲染、条目增删。
 * 发送逻辑仍在 Activity（涉及 chatStorage / chatHistory / API 调用）。
 */
class BatchModeManager(
    private val activity: Activity,
    private val pendingArea: LinearLayout,
    private val pendingMessages: LinearLayout,
    private val pendingCount: TextView,
    private val btnBatch: TextView
) {
    /** 待发条目 */
    data class PendingItem(
        val type: String,  // "text" 或 "image"
        val text: String = "",
        val imagePaths: List<String> = emptyList(),
        val quoteAuthor: String? = null,
        val quoteContent: String? = null
    )

    var isBatchMode = false
        private set
    private val items = mutableListOf<PendingItem>()
    private val c get() = ThemeHelper.getColors(activity)

    /** 切换前的准备：关其他面板等，由 Activity 传回调 */
    var onToggle: ((entering: Boolean) -> Unit)? = null

    private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()

    fun toggle() {
        isBatchMode = !isBatchMode
        if (isBatchMode) {
            btnBatch.setTextColor(c.accent)
            pendingArea.visibility = View.VISIBLE
            items.clear()
            refreshUI()
            onToggle?.invoke(true)
        } else {
            btnBatch.setTextColor(c.dateLabel)
            pendingArea.visibility = View.GONE
            items.clear()
        }
    }

    fun exit() {
        isBatchMode = false
        btnBatch.setTextColor(c.dateLabel)
        pendingArea.visibility = View.GONE
        items.clear()
    }

    fun addText(text: String) {
        items.add(PendingItem("text", text))
        refreshUI()
    }

    fun addTextWithQuote(text: String, quoteAuthor: String, quoteContent: String) {
        items.add(PendingItem("text", text, quoteAuthor = quoteAuthor, quoteContent = quoteContent))
        refreshUI()
    }

    fun addImage(paths: List<String>, caption: String) {
        items.add(PendingItem("image", caption, paths))
        refreshUI()
    }

    fun isEmpty(): Boolean = items.isEmpty()

    /** 取出所有待发条目并清空，退出分条模式 */
    fun getItemsAndClear(): List<PendingItem> {
        val result = items.toList()
        items.clear()
        exit()
        return result
    }

    fun refreshUI() {
        pendingMessages.removeAllViews()
        pendingCount.text = "待发送 (${items.size})"

        val innerLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        for ((index, item) in items.withIndex()) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    setColor(c.card); cornerRadius = dp(8).toFloat(); setStroke(1, c.border)
                }
                setPadding(dp(10), dp(6), dp(6), dp(6))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(4) }
            }
            if (item.type == "image") {
                val thumb = ImageView(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(8) }
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    val bitmap = BitmapFactory.decodeFile(item.imagePaths.firstOrNull() ?: "")
                    if (bitmap != null) setImageBitmap(bitmap)
                }
                row.addView(thumb)
                row.addView(TextView(activity).apply {
                    text = if (item.text.isNotEmpty()) "📷 ${item.text}" else "📷 ${item.imagePaths.size}张图片"
                    textSize = 12f; setTextColor(c.textPrimary)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    maxLines = 1
                })
            } else {
                val textContainer = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                if (item.quoteAuthor != null) {
                    textContainer.addView(TextView(activity).apply {
                        val short = item.quoteContent?.take(20) ?: ""
                        text = "↩ ${item.quoteAuthor}: $short"
                        textSize = 10f; setTextColor(c.accent); maxLines = 1
                    })
                }
                textContainer.addView(TextView(activity).apply {
                    text = item.text; textSize = 12f; setTextColor(c.textPrimary); maxLines = 2
                })
                row.addView(textContainer)
            }
            row.addView(TextView(activity).apply {
                text = "✕"; textSize = 14f; setTextColor(c.textHint)
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener { items.removeAt(index); refreshUI() }
            })
            innerLayout.addView(row)
        }

        // 超过3条包ScrollView，能滑动看全部
        if (items.size > 3) {
            val scroll = android.widget.ScrollView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(180)
                )
                isFillViewport = false
            }
            scroll.addView(innerLayout)
            pendingMessages.addView(scroll)
            scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) }
        } else {
            pendingMessages.addView(innerLayout)
        }
    }
}