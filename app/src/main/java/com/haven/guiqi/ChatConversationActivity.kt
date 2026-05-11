package com.haven.guiqi

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.text.SimpleDateFormat
import java.util.*

class ChatConversationActivity : AppCompatActivity() {

    private lateinit var tvFriendName: TextView
    private lateinit var tvStatus: TextView
    private lateinit var messagesContainer: LinearLayout
    private lateinit var scrollMessages: ScrollView
    private lateinit var inputMessage: EditText
    private lateinit var btnSend: TextView
    private lateinit var btnBack: TextView
    private lateinit var btnMenu: TextView

    private val handler = Handler(Looper.getMainLooper())

    private var friendId = ""
    private var friendName = "好友"
    private var friendIcon = "★"

    private var apiUrl = ""
    private var apiKey = ""
    private var apiModel = ""

    // 完整聊天历史
    private val chatHistory = mutableListOf<ChatMessage>()

    // 滑动窗口大小：只把最近 N 条发给 API
    private val maxContextMessages = 30

    private lateinit var chatStorage: ChatStorage

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        setContentView(R.layout.activity_chat_conversation)

        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        insetsController.hide(WindowInsetsCompat.Type.navigationBars())
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController.isAppearanceLightStatusBars = false

        val contentView = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(contentView) { view, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.setPadding(0, top, 0, 0)
            insets
        }

        tvFriendName = findViewById(R.id.tvFriendName)
        tvStatus = findViewById(R.id.tvStatus)
        messagesContainer = findViewById(R.id.messagesContainer)
        scrollMessages = findViewById(R.id.scrollMessages)
        inputMessage = findViewById(R.id.inputMessage)
        btnSend = findViewById(R.id.btnSend)
        btnBack = findViewById(R.id.btnBack)
        btnMenu = findViewById(R.id.btnMenu)

        friendId = intent.getStringExtra("friend_id") ?: ""
        friendName = intent.getStringExtra("friend_name") ?: "好友"
        friendIcon = intent.getStringExtra("friend_icon") ?: "★"
        tvFriendName.text = friendName
        chatStorage = ChatStorage(this)
        loadApiConfig()

        btnBack.setOnClickListener { finish() }
        btnMenu.setOnClickListener {
            Toast.makeText(this, "聊天设置开发中 ♡", Toast.LENGTH_SHORT).show()
        }
        btnSend.setOnClickListener { sendMessage() }

        initChat()
    }

    private fun loadApiConfig() {
        val prefs = getSharedPreferences("haven_prefs", MODE_PRIVATE)
        apiUrl = prefs.getString("api_url", "") ?: ""
        apiKey = prefs.getString("api_key", "") ?: ""
        apiModel = prefs.getString("api_model", "") ?: ""
    }

    // 构建滑动窗口：system 消息 + 最近 N 条对话
    private fun buildContextWindow(): List<ChatMessage> {
        val systemMsgs = chatHistory.filter { it.role == "system" }
        val nonSystemMsgs = chatHistory.filter { it.role != "system" }
        val recentMsgs = if (nonSystemMsgs.size > maxContextMessages) {
            nonSystemMsgs.takeLast(maxContextMessages)
        } else {
            nonSystemMsgs
        }
        return systemMsgs + recentMsgs
    }

    private fun initChat() {
        val timeInfo = SimpleDateFormat("yyyy年M月d日 EEEE HH:mm", Locale.CHINESE)
            .format(Date())
        chatHistory.add(ChatMessage("system", "当前时间: $timeInfo"))

        val savedMessages = chatStorage.loadMessages(friendId)

        if (savedMessages.isEmpty()) {
            addTimeLabel("—— 今天 ——")
            if (apiUrl.isEmpty() || apiKey.isEmpty() || apiModel.isEmpty()) {
                addSystemTip("还没有配置 API 哦~\n请先去桌面 → 设置 → 填写 API 地址、密钥和模型名称")
                tvStatus.text = "未配置"
                tvStatus.setTextColor(0x4DFF6B6B.toInt())
            } else {
                addSystemTip("API 已就绪，开始聊天吧 ♡")
            }
        } else {
            var lastDateStr = ""
            for (msg in savedMessages) {
                val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                    .format(Date(msg.timestamp))
                if (dateStr != lastDateStr) {
                    addTimeLabel("—— ${formatDateLabel(msg.timestamp)} ——")
                    lastDateStr = dateStr
                }
                val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    .format(Date(msg.timestamp))
                when (msg.role) {
                    "user" -> addUserBubble(msg.content, timeStr)
                    "assistant" -> addAiBubble(msg.content, timeStr)
                }
                chatHistory.add(ChatMessage(msg.role, msg.content))
            }
            if (apiUrl.isEmpty() || apiKey.isEmpty() || apiModel.isEmpty()) {
                tvStatus.text = "未配置"
                tvStatus.setTextColor(0x4DFF6B6B.toInt())
            }
        }
    }

    private fun sendMessage() {
        val msg = inputMessage.text.toString().trim()
        if (msg.isEmpty()) return
        if (apiUrl.isEmpty() || apiKey.isEmpty() || apiModel.isEmpty()) {
            Toast.makeText(this, "请先去设置页配置 API", Toast.LENGTH_SHORT).show()
            return
        }

        inputMessage.text.clear()
        val now = System.currentTimeMillis()
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(now))

        val userBubbleWrapper = addUserBubble(msg, timeStr)
        chatStorage.appendMessage(friendId, StoredMessage("user", msg, now))
        chatHistory.add(ChatMessage("user", "[$timeStr] $msg"))

        showTypingIndicator()
        Thread {
            try {
                val api = ApiHelper(apiUrl, apiKey, apiModel)
                // 用滑动窗口而不是完整历史
                val reply = api.sendChat(buildContextWindow())
                val replyTime = System.currentTimeMillis()
                val replyTimeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    .format(Date(replyTime))

                chatStorage.appendMessage(friendId, StoredMessage("assistant", reply, replyTime))
                chatHistory.add(ChatMessage("assistant", reply))

                handler.post {
                    removeTypingIndicator()
                    addAiBubble(reply, replyTimeStr)
                }
            } catch (e: Exception) {
                handler.post {
                    removeTypingIndicator()
                    messagesContainer.removeView(userBubbleWrapper)
                    if (chatHistory.isNotEmpty()) chatHistory.removeAt(chatHistory.size - 1)
                    val saved = chatStorage.loadMessages(friendId).toMutableList()
                    if (saved.isNotEmpty()) {
                        saved.removeAt(saved.size - 1)
                        chatStorage.saveMessages(friendId, saved)
                    }
                    inputMessage.setText(msg)
                    Toast.makeText(this, "发送失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    // ===== 复制到剪贴板 =====
    private fun copyToClipboard(content: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("chat_message", content))
        Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
    }

    // ===== 用户气泡（右侧） =====
    private fun addUserBubble(msg: String, timeStr: String): View {
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }

        val wrapper = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
            gravity = Gravity.END
        }

        val bubble = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            maxWidth = (resources.displayMetrics.widthPixels * 0.75).toInt()
            this.text = msg
            setTextColor(0xB3FFFFFF.toInt())
            textSize = 14f
            setLineSpacing(0f, 1.4f)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            setBackgroundResource(R.drawable.chat_bubble_user)
            // 长按复制
            setOnLongClickListener { copyToClipboard(msg); true }
        }

        val time = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(3) }
            gravity = Gravity.END
            this.text = timeStr
            textSize = 9f
            setTextColor(0x1AFFFFFF.toInt())
            setPadding(0, 0, dp(4), 0)
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        column.addView(bubble)
        column.addView(time)
        wrapper.addView(column)
        messagesContainer.addView(wrapper)
        scrollToBottom()
        return wrapper
    }

    // ===== AI 气泡（左侧） =====
    private fun addAiBubble(msg: String, timeStr: String) {
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }

        val wrapper = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
        }

        val avatar = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply {
                marginEnd = dp(8); topMargin = dp(2)
            }
            gravity = Gravity.CENTER
            this.text = friendIcon
            textSize = 13f
            setTextColor(0x80B3A0FF.toInt())
            setBackgroundResource(R.drawable.icon_bg)
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val bubble = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            maxWidth = (resources.displayMetrics.widthPixels * 0.72).toInt()
            this.text = msg
            setTextColor(0xB3FFFFFF.toInt())
            textSize = 14f
            setLineSpacing(0f, 1.4f)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            setBackgroundResource(R.drawable.chat_bubble_ai)
            // 长按复制
            setOnLongClickListener { copyToClipboard(msg); true }
        }

        val time = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(3) }
            this.text = timeStr
            textSize = 9f
            setTextColor(0x1AFFFFFF.toInt())
            setPadding(dp(4), 0, 0, 0)
        }

        column.addView(bubble)
        column.addView(time)
        wrapper.addView(avatar)
        wrapper.addView(column)
        messagesContainer.addView(wrapper)
        scrollToBottom()
    }

    private fun addSystemTip(msg: String) {
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }
        val tip = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4); bottomMargin = dp(12) }
            gravity = Gravity.CENTER
            this.text = msg
            textSize = 11f
            setTextColor(0x33FFFFFF.toInt())
            setLineSpacing(0f, 1.4f)
            setPadding(dp(20), 0, dp(20), 0)
        }
        messagesContainer.addView(tip)
        scrollToBottom()
    }

    private fun addTimeLabel(labelText: String) {
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }
        val label = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8); bottomMargin = dp(12) }
            gravity = Gravity.CENTER
            this.text = labelText
            textSize = 10f
            setTextColor(0x1AFFFFFF.toInt())
        }
        messagesContainer.addView(label)
    }

    private fun formatDateLabel(timestamp: Long): String {
        val msgDate = Calendar.getInstance().apply { timeInMillis = timestamp }
        val today = Calendar.getInstance()
        return when {
            msgDate.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
            msgDate.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) -> "今天"
            msgDate.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
            msgDate.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) - 1 -> "昨天"
            msgDate.get(Calendar.YEAR) == today.get(Calendar.YEAR) ->
                SimpleDateFormat("M月d日", Locale.CHINESE).format(Date(timestamp))
            else ->
                SimpleDateFormat("yyyy年M月d日", Locale.CHINESE).format(Date(timestamp))
        }
    }

    private var typingView: LinearLayout? = null

    private fun showTypingIndicator() {
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }
        val wrapper = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
        }
        val avatar = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply {
                marginEnd = dp(8); topMargin = dp(2)
            }
            gravity = Gravity.CENTER
            this.text = friendIcon
            textSize = 13f
            setTextColor(0x80B3A0FF.toInt())
            setBackgroundResource(R.drawable.icon_bg)
        }
        val bubble = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            this.text = "$friendName 正在输入..."
            textSize = 12f
            setTextColor(0x4DB3A0FF.toInt())
            setPadding(dp(12), dp(9), dp(12), dp(9))
            setBackgroundResource(R.drawable.chat_bubble_ai)
        }
        wrapper.addView(avatar)
        wrapper.addView(bubble)
        typingView = wrapper
        messagesContainer.addView(wrapper)
        scrollToBottom()
    }

    private fun removeTypingIndicator() {
        typingView?.let { messagesContainer.removeView(it); typingView = null }
    }

    private fun scrollToBottom() {
        scrollMessages.post { scrollMessages.fullScroll(View.FOCUS_DOWN) }
    }
}