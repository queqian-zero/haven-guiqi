package com.haven.guiqi

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/**
 * BubbleRenderer — 聊天气泡渲染器
 *
 * 从 ChatConversationActivity 拆出来的。
 * 负责所有气泡的创建：用户气泡、AI气泡、图片气泡、引用、系统提示、错误提示、
 * 思维链、已读不回、分隔线、流式动画、分条渲染等。
 *
 * 使用方式：
 *   val renderer = BubbleRenderer(activity, messagesContainer, chatScrollView)
 *   renderer.friendName = "闺闺"
 *   renderer.friendIcon = "🐱"
 *   renderer.addUserBubble("你好", "10:30:00")
 */
class BubbleRenderer(
    private val activity: Activity,
    private val messagesContainer: LinearLayout,
    private val scrollView: ScrollView
) {
    // 这两个可能随时变（AI可以改名改头像）
    var friendName: String = ""
    var friendIcon: String = "🤖"

    // 主题颜色跟着全局走
    private val c get() = ThemeHelper.getColors(activity)

    // dp 转 px
    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()

    // 滚动到底部
    fun scrollToBottom() {
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    // ===== 系统提示（居中灰色小字） =====
    fun addSystemTip(msg: String) {
        val tip = TextView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4); bottomMargin = dp(10) }
            gravity = Gravity.CENTER
            this.text = msg
            textSize = 11f
            setTextColor(c.textHint)
            setLineSpacing(0f, 1.35f)
            setPadding(dp(20), 0, dp(20), 0)
        }
        messagesContainer.addView(tip)
        scrollToBottom()
    }

    // ===== 错误气泡（浅红色圆角，可选重试） =====
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
            val gd = android.graphics.drawable.GradientDrawable().apply {
                setColor(c.errorBg); cornerRadius = dp(12).toFloat()
            }
            background = gd
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

    // ===== 已读不回标记 =====
    fun addSeenIndicator() {
        val seen = TextView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(2)
                bottomMargin = dp(8)
            }
            gravity = Gravity.END
            text = "已读"
            textSize = 10f
            setTextColor(c.accent)
            setPadding(0, 0, dp(12), 0)
        }
        messagesContainer.addView(seen)
        scrollToBottom()
    }

    // ===== 换天分隔线 =====
    fun addDaySeparator(timestamp: Long) {
        val dateStr = java.text.SimpleDateFormat("yyyy年M月d日 EEEE", java.util.Locale.CHINESE)
            .format(java.util.Date(timestamp))
        val separator = TextView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16); bottomMargin = dp(16) }
            gravity = Gravity.CENTER
            text = "—— $dateStr ——"
            textSize = 11f
            setTextColor(c.dateLabel)
        }
        messagesContainer.addView(separator)
    }

    // ===== 时间间隔标记 =====
    fun addGapMarker(text: String) {
        val marker = TextView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8); bottomMargin = dp(8) }
            gravity = Gravity.CENTER
            this.text = text
            textSize = 10f
            setTextColor(c.textHint)
        }
        messagesContainer.addView(marker)
    }

    // ===== 长按消息菜单（复制 / 引用） =====
    fun showMessageMenu(content: String, author: String, onQuote: (String, String) -> Unit) {
        val options = arrayOf("复制", "引用回复")
        android.app.AlertDialog.Builder(activity)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("chat", content))
                        android.widget.Toast.makeText(activity, "已复制", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    1 -> onQuote(author, content)
                }
            }.show()
    }
}