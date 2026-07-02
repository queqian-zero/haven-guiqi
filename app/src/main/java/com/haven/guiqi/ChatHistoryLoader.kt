package com.haven.guiqi

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/**
 * ChatHistoryLoader — 聊天历史加载与渲染
 *
 * 从 ChatConversationActivity 拆出来。
 * 职责：加载保存的聊天记录、渲染气泡、分页加载更早的消息、日期分隔线。
 * Activity 只管调度，细节全在这里。
 */
class ChatHistoryLoader(
    private val activity: androidx.appcompat.app.AppCompatActivity,
    private val chatStorage: ChatStorage,
    private val bubbleRenderer: BubbleRenderer,
    private val chatHistory: MutableList<ChatMessage>,
    private val messagesContainer: LinearLayout,
    private val friendId: String
) {

    /** 当前主题色 */
    private val c get() = ThemeHelper.getColors(activity)

    // 分页状态
    private var loadedMessageCount = 0
    private val messagesPerPage = 50
    private var allSavedMessages: List<StoredMessage> = emptyList()

    // 日期分隔线状态
    var lastMessageDate = ""
    var lastMessageTimestamp = 0L

    // 回调
    var onSetStatus: ((String) -> Unit)? = null

    /** dp 转 px */
    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()

    /**
     * 检查是否需要加日期分隔线或时间间隔标记
     */
    fun checkDateSeparator(timestamp: Long) {
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timestamp))

        // 换天分隔——醒目横线
        if (dateStr != lastMessageDate) {
            bubbleRenderer.addDaySeparator(timestamp)
            lastMessageDate = dateStr
        }
        // 同一天内，距上条消息超过 1 小时——朴素间隔标记
        else if (lastMessageTimestamp > 0) {
            val gapMs = timestamp - lastMessageTimestamp
            val gapMinutes = gapMs / 60000
            if (gapMinutes >= 60) {
                val gapLabel = bubbleRenderer.formatGapLabel(gapMs)
                val timeLabel = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
                bubbleRenderer.addGapMarker("距上条消息 $gapLabel · $timeLabel")
            }
        }

        lastMessageTimestamp = timestamp
    }

    /**
     * 初始化聊天界面：加载所有历史消息到 chatHistory（给 API 用），
     * 渲染最近 50 条消息的气泡（给用户看）
     */
    fun initChat(apiConfigured: Boolean, currentAiStatus: String) {
        val timeInfo = SimpleDateFormat("yyyy年M月d日 EEEE HH:mm", Locale.CHINESE).format(Date())
        val userName = activity.getSharedPreferences("haven_prefs", android.content.Context.MODE_PRIVATE)
            .getString("user_name", "") ?: ""
        val userInfo = if (userName.isNotEmpty()) "\n用户名称: $userName" else ""
        chatHistory.add(ChatMessage("system", "当前时间: $timeInfo$userInfo"))

        allSavedMessages = chatStorage.loadMessages(friendId)
        if (allSavedMessages.isEmpty()) {
            lastMessageDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            bubbleRenderer.addDaySeparator(System.currentTimeMillis())
            if (!apiConfigured) {
                bubbleRenderer.addSystemTip("还没有配置 API 哦~\n请先去桌面 → 设置 → 填写 API 地址、密钥和模型名称")
                onSetStatus?.invoke("unconfigured")
            } else {
                bubbleRenderer.addSystemTip("API 已就绪，开始聊天吧 ♡")
                onSetStatus?.invoke("online")
            }
        } else {
            // 所有消息都加进 chatHistory（给 API 用的上下文）
            buildChatHistoryFromSaved()

            // 只渲染最近 50 条消息的气泡（性能优化）
            val recentMessages = if (allSavedMessages.size > messagesPerPage) {
                bubbleRenderer.addLoadMoreButton()
                allSavedMessages.takeLast(messagesPerPage)
            } else {
                allSavedMessages
            }
            loadedMessageCount = recentMessages.size

            renderMessages(recentMessages)

            // 显示保存的 AI 状态
            if (currentAiStatus.isNotEmpty()) {
                // 状态由 Activity 处理
            }
            if (!apiConfigured) {
                onSetStatus?.invoke("unconfigured")
            } else {
                onSetStatus?.invoke("online")
            }
        }
    }

    /**
     * 从保存的消息构建 chatHistory（给 API 上下文用）
     */
    private fun buildChatHistoryFromSaved() {
        var prevTimestamp = 0L
        var prevDateStr = ""
        for (msg in allSavedMessages) {
            val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                .format(Date(msg.timestamp))
            val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                .format(Date(msg.timestamp))

            // 给 AI 的上下文也插入时间标记
            if (dateStr != prevDateStr && prevDateStr.isNotEmpty()) {
                val dayLabel = bubbleRenderer.formatDateLabel(msg.timestamp)
                chatHistory.add(ChatMessage("system", "[日期变更: $dayLabel]"))
            } else if (prevTimestamp > 0) {
                val gapMs = msg.timestamp - prevTimestamp
                val gapMinutes = gapMs / 60000
                if (gapMinutes >= 60) {
                    val gapLabel = bubbleRenderer.formatGapLabel(gapMs)
                    val gapTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(msg.timestamp))
                    chatHistory.add(ChatMessage("system", "[距上条消息 $gapLabel · $gapTime]"))
                }
            }
            prevDateStr = dateStr
            prevTimestamp = msg.timestamp

            when (msg.role) {
                "user" -> {
                    if (msg.imagePath.isNotEmpty()) {
                        val pathCount = if (msg.extras.isNotEmpty()) {
                            try {
                                JSONObject(msg.extras).optJSONArray("paths")?.length() ?: 1
                            } catch (e: Exception) { 1 }
                        } else 1
                        val desc = if (pathCount > 1) "[用户之前发送了${pathCount}张图片]" else "[用户之前发送了一张图片]"
                        chatHistory.add(ChatMessage("user", desc))
                    } else {
                        chatHistory.add(ChatMessage(msg.role, msg.content))
                    }
                }
                "assistant" -> {
                    chatHistory.add(ChatMessage(msg.role, msg.content))
                }
            }
        }
    }

    /**
     * 渲染一批消息的气泡
     */
    private fun renderMessages(messages: List<StoredMessage>) {
        for (msg in messages) {
            checkDateSeparator(msg.timestamp)
            val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                .format(Date(msg.timestamp))
            when (msg.role) {
                "user" -> {
                    if (msg.imagePath.isNotEmpty()) {
                        val caption = msg.content.let {
                            if (it == "[图片]" || it.startsWith("[") && it.endsWith("张图片]")) "" else it
                        }
                        val allPaths = if (msg.extras.isNotEmpty()) {
                            try {
                                val arr = JSONObject(msg.extras).optJSONArray("paths")
                                if (arr != null) (0 until arr.length()).map { arr.getString(it) }
                                else listOf(msg.imagePath)
                            } catch (e: Exception) { listOf(msg.imagePath) }
                        } else listOf(msg.imagePath)

                        if (allPaths.size > 1) {
                            bubbleRenderer.addMultiImageBubble(allPaths, timeStr, caption)
                        } else {
                            bubbleRenderer.addImageBubble(msg.imagePath, timeStr, caption)
                        }
                    } else {
                        bubbleRenderer.addUserBubble(msg.content, timeStr)
                    }
                }
                "assistant" -> {
                    if (msg.content.trim() == "[SEEN]") {
                        bubbleRenderer.addSeenIndicator()
                    } else {
                        if (msg.thinking.isNotEmpty()) bubbleRenderer.addThinkingBlock(msg.thinking)
                        bubbleRenderer.addAiBubble(msg.content, timeStr)
                    }
                }
                "system" -> {
                    if (msg.type == "tip") {
                        bubbleRenderer.addSystemTip(msg.content)
                    }
                }
            }
        }
    }

    /**
     * 加载更早的消息（分页，每次 50 条）
     */
    fun loadEarlierMessages() {
        val total = allSavedMessages.size
        val alreadyLoaded = loadedMessageCount
        val remaining = total - alreadyLoaded

        if (remaining <= 0) return

        // 计算要加载的消息范围
        val loadCount = minOf(messagesPerPage, remaining)
        val startIndex = remaining - loadCount
        val endIndex = remaining
        val olderMessages = allSavedMessages.subList(startIndex, endIndex)

        // 移除"加载更多"按钮
        val loadMoreBtn = messagesContainer.findViewWithTag<View>("load_more_btn")
        val btnIndex = if (loadMoreBtn != null) {
            val idx = messagesContainer.indexOfChild(loadMoreBtn)
            messagesContainer.removeView(loadMoreBtn)
            idx
        } else 0

        // 在顶部插入更早的消息
        var insertIndex = btnIndex
        var prevDate = ""
        for (msg in olderMessages) {
            val msgDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                .format(Date(msg.timestamp))
            if (msgDate != prevDate) {
                val dateLabel = when {
                    msgDate == SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()) -> "—— 今天 ——"
                    else -> "—— $msgDate ——"
                }
                val label = TextView(activity).apply {
                    text = dateLabel
                    textSize = 10f
                    setTextColor(c.dateLabel)
                    gravity = Gravity.CENTER
                    setPadding(0, dp(12), 0, dp(8))
                }
                messagesContainer.addView(label, insertIndex)
                insertIndex++
                prevDate = msgDate
            }

            val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                .format(Date(msg.timestamp))
            when (msg.role) {
                "user" -> {
                    if (msg.imagePath.isNotEmpty()) {
                        bubbleRenderer.addImageBubbleAt(msg.imagePath, timeStr,
                            msg.content.let { if (it == "[图片]") "" else it }, insertIndex)
                        insertIndex++
                    } else {
                        val bubble = bubbleRenderer.addUserBubble(msg.content, timeStr)
                        messagesContainer.removeView(bubble)
                        messagesContainer.addView(bubble, insertIndex)
                        insertIndex++
                    }
                }
                "assistant" -> {
                    if (msg.content.trim() == "[SEEN]") {
                        // 历史里跳过已读标记
                    } else {
                        if (msg.thinking.isNotEmpty()) {
                            // 历史加载时跳过思维链，太多了会卡
                        }
                        val bubble = bubbleRenderer.createAiBubbleView(msg.content, timeStr)
                        messagesContainer.addView(bubble, insertIndex)
                        insertIndex++
                    }
                }
                "system" -> {
                    if (msg.type == "tip") {
                        val tipView = TextView(activity).apply {
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply {
                                topMargin = dp(4)
                                bottomMargin = dp(10)
                            }
                            gravity = Gravity.CENTER
                            text = msg.content
                            textSize = 11f
                            setTextColor(c.textHint)
                            setPadding(dp(20), 0, dp(20), 0)
                        }
                        messagesContainer.addView(tipView, insertIndex)
                        insertIndex++
                    }
                }
            }
        }

        loadedMessageCount += loadCount

        // 如果还有更早的消息，重新加按钮
        if (startIndex > 0) {
            bubbleRenderer.addLoadMoreButton()
        }

        Toast.makeText(activity, "加载了 $loadCount 条消息", Toast.LENGTH_SHORT).show()
    }
}