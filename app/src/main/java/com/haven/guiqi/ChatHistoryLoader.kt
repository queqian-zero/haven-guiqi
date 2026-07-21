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
    private var totalMessageCount = 0        // 存档里的总条数（只数行，很快）
    private var fullHistoryLoaded = false    // 完整历史是否已加载（点"加载更早"才加载）

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
        // 重置状态（页面刷新会重复调用 initChat）
        loadedMessageCount = 0
        allSavedMessages = emptyList()
        fullHistoryLoaded = false
        lastMessageDate = ""
        lastMessageTimestamp = 0L

        val timeInfo = SimpleDateFormat("yyyy年M月d日 EEEE HH:mm", Locale.CHINESE).format(Date())
        val userName = activity.getSharedPreferences("haven_prefs", android.content.Context.MODE_PRIVATE)
            .getString("user_name", "") ?: ""
        val userInfo = if (userName.isNotEmpty()) "\n用户名称: $userName" else ""
        chatHistory.add(ChatMessage("system", "当前时间: $timeInfo$userInfo"))

        // ★ 关键优化：不再把整个仓库搬进来
        // 只解析真正需要的部分：AI 上下文条数 和 首屏渲染条数，取大者
        val ctxCount = activity.getSharedPreferences("haven_chat_prefs", android.content.Context.MODE_PRIVATE)
            .getInt("context_$friendId", 30)
        totalMessageCount = chatStorage.getMessageCount(friendId)
        val recentMessages = chatStorage.loadRecentMessages(friendId, maxOf(messagesPerPage, ctxCount))

        if (totalMessageCount == 0) {
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
            // 给 API 的上下文：只需要用户设置的条数
            buildChatHistoryFromSaved(recentMessages.takeLast(ctxCount))

            // 只渲染最近 50 条消息的气泡（性能优化）
            val toRender = if (totalMessageCount > messagesPerPage) {
                bubbleRenderer.addLoadMoreButton()
                recentMessages.takeLast(messagesPerPage)
            } else {
                recentMessages
            }
            loadedMessageCount = toRender.size

            // ★ 批量渲染期间关掉逐条滚动，渲染完后统一滚一次
            bubbleRenderer.suppressScroll = true
            renderMessages(toRender)
            bubbleRenderer.suppressScroll = false
            bubbleRenderer.scrollToBottom()

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
    private fun buildChatHistoryFromSaved(saved: List<StoredMessage>) {
        var prevTimestamp = 0L
        var prevDateStr = ""
        for (msg in saved) {
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
            renderOneMessage(msg, timeStr, skipThinking = false)
        }
    }

    /**
     * 统一渲染一条消息——所有消息类型的渲染逻辑只写在这一个地方。
     * renderMessages 和 doLoadEarlier 共用，避免加新类型时漏一边。
     *
     * @param skipThinking 加载更早的历史时跳过思维链（太多会卡）
     */
    private fun renderOneMessage(msg: StoredMessage, timeStr: String, skipThinking: Boolean = false) {
        when (msg.role) {
            "user" -> {
                if (msg.type == "weather") {
                    renderWeatherCard(isUser = true, timeStr = timeStr, fallbackContent = msg.content, extras = msg.extras)
                } else if (msg.type == "quote" && msg.extras.isNotEmpty()) {
                    try {
                        val q = JSONObject(msg.extras)
                        val qAuthor = q.optString("quote_author", "")
                        val qContent = q.optString("quote_content", "")
                        if (qAuthor.isNotEmpty()) {
                            bubbleRenderer.addQuoteBubble(qAuthor, qContent, msg.content, timeStr)
                        } else {
                            bubbleRenderer.addUserBubble(msg.content, timeStr)
                        }
                    } catch (_: Exception) {
                        bubbleRenderer.addUserBubble(msg.content, timeStr)
                    }
                } else if (msg.imagePath.isNotEmpty()) {
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
                    // 旧数据兼容：之前存的「回复 xxx: yyy」格式
                    val oldQuoteRegex = Regex("^「回复 (.+?): (.+?)」\n(.+)$", RegexOption.DOT_MATCHES_ALL)
                    val oldMatch = oldQuoteRegex.matchEntire(msg.content)
                    if (oldMatch != null) {
                        val (oAuthor, oQuote, oReply) = oldMatch.destructured
                        bubbleRenderer.addQuoteBubble(oAuthor, oQuote, oReply, timeStr)
                    } else {
                        bubbleRenderer.addUserBubble(msg.content, timeStr)
                    }
                }
            }
            "assistant" -> {
                if (msg.content.trim() == "[SEEN]") {
                    if (!skipThinking) bubbleRenderer.addSeenIndicator()
                } else if (msg.type == "weather") {
                    if (!skipThinking && msg.thinking.isNotEmpty()) bubbleRenderer.addThinkingBlock(msg.thinking)
                    renderWeatherCard(isUser = false, timeStr = timeStr, fallbackContent = msg.content, extras = msg.extras)
                    val text = msg.content.trim()
                    if (text.isNotEmpty()) bubbleRenderer.addAiBubble(text, timeStr)
                } else {
                    if (!skipThinking && msg.thinking.isNotEmpty()) bubbleRenderer.addThinkingBlock(msg.thinking)
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

    /**
     * 加载更早的消息（分页，每次 50 条）
     * 完整历史只在第一次点击时才去加载，而且放在后台线程，不卡界面
     */
    fun loadEarlierMessages() {
        if (fullHistoryLoaded) {
            doLoadEarlier()
            return
        }
        Toast.makeText(activity, "加载中…", Toast.LENGTH_SHORT).show()
        Thread {
            val full = chatStorage.loadMessages(friendId)
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                allSavedMessages = full
                fullHistoryLoaded = true
                doLoadEarlier()
            }
        }.start()
    }

    private fun doLoadEarlier() {
        val total = allSavedMessages.size
        val alreadyLoaded = loadedMessageCount
        val remaining = total - alreadyLoaded

        if (remaining <= 0) return

        // ★ 加载更早的消息时也关掉逐条滚动（加载完不滚到底，留在原位）
        bubbleRenderer.suppressScroll = true

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
            // 日期分隔线
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

            // ★ 快照差值法：renderOneMessage 会把 View 追加到容器末尾，
            //   我们记录前后的 childCount 差值，再把新增的 View 移到正确位置。
            //   这样所有消息类型的渲染逻辑只维护 renderOneMessage 一处。
            val before = messagesContainer.childCount
            renderOneMessage(msg, timeStr, skipThinking = true)
            val addedCount = messagesContainer.childCount - before

            if (addedCount > 0) {
                // 收集末尾新增的 View
                val newViews = (0 until addedCount).map { i ->
                    messagesContainer.getChildAt(before + i)
                }
                // 从末尾移除（倒序避免索引偏移）
                for (v in newViews.reversed()) messagesContainer.removeView(v)
                // 插入到正确位置
                for ((i, v) in newViews.withIndex()) {
                    messagesContainer.addView(v, insertIndex + i)
                }
                insertIndex += addedCount
            }
        }

        loadedMessageCount += loadCount

        // 如果还有更早的消息，重新加按钮
        if (startIndex > 0) {
            bubbleRenderer.addLoadMoreButton()
        }

        Toast.makeText(activity, "加载了 $loadCount 条消息", Toast.LENGTH_SHORT).show()
        bubbleRenderer.suppressScroll = false
        // 加载更早消息后不滚到底——用户想看的是刚加载出来的历史
    }

    /** 渲染天气卡片，优先从 extras 读快照，其次从缓存读，都没有就降级纯文字 */
    private fun renderWeatherCard(isUser: Boolean, timeStr: String, fallbackContent: String, extras: String) {
        val snapshot = WeatherStorage.fromExtras(extras)
        if (snapshot != null) {
            bubbleRenderer.addWeatherCard(snapshot.first, snapshot.second, isUser = isUser, timeStr = timeStr)
        } else {
            val ws = WeatherStorage(activity)
            val data = ws.getCachedWeather(); val city = ws.getCity()
            if (data != null && city.isNotEmpty()) {
                bubbleRenderer.addWeatherCard(data, city, isUser = isUser, timeStr = timeStr)
            } else if (isUser) {
                bubbleRenderer.addUserBubble(fallbackContent, timeStr)
            } else {
                bubbleRenderer.addAiBubble(fallbackContent, timeStr)
            }
        }
    }
}