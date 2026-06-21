package com.haven.guiqi

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class ChatActivity : AppCompatActivity() {

    /** 当前主题色 */
    private val c get() = ThemeHelper.getColors(this)

    // 文件操作请求码
    private val EXPORT_REQUEST = 2001
    private val IMPORT_REQUEST = 2002

    // ===== 四个标签页 =====
    private lateinit var tabMessages: LinearLayout
    private lateinit var tabFriends: LinearLayout
    private lateinit var tabFootprints: LinearLayout
    private lateinit var tabProfile: LinearLayout

    // ===== 底部标签按钮 =====
    private lateinit var btnTabMessages: LinearLayout
    private lateinit var btnTabFriends: LinearLayout
    private lateinit var btnTabFootprints: LinearLayout
    private lateinit var btnTabProfile: LinearLayout

    // ===== 标签图标和文字 =====
    private lateinit var iconTabMessages: TextView
    private lateinit var labelTabMessages: TextView
    private lateinit var iconTabFriends: TextView
    private lateinit var labelTabFriends: TextView
    private lateinit var iconTabFootprints: TextView
    private lateinit var labelTabFootprints: TextView
    private lateinit var iconTabProfile: TextView
    private lateinit var labelTabProfile: TextView

    // ===== 列表容器 =====
    private lateinit var messagesList: LinearLayout
    private lateinit var friendsList: LinearLayout
    private lateinit var profileContainer: LinearLayout
    private lateinit var footprintsList: LinearLayout

    // ===== 数据 =====
    private lateinit var friendStorage: FriendStorage
    private lateinit var chatStorage: ChatStorage
    private lateinit var footprintStorage: FootprintStorage
    private lateinit var friendTabManager: FriendTabManager
    private lateinit var profileTabManager: ProfileTabManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 沉浸式全屏
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        setContentView(R.layout.activity_chat)

        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        insetsController.hide(WindowInsetsCompat.Type.navigationBars())
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController.isAppearanceLightStatusBars = !ThemeHelper.isDark(this)

        val contentView = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(contentView) { view, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.setPadding(0, top, 0, 0)
            insets
        }

        // ===== 绑定视图 =====
        tabMessages = findViewById(R.id.tabMessages)
        tabFriends = findViewById(R.id.tabFriends)
        tabFootprints = findViewById(R.id.tabFootprints)
        tabProfile = findViewById(R.id.tabProfile)

        btnTabMessages = findViewById(R.id.btnTabMessages)
        btnTabFriends = findViewById(R.id.btnTabFriends)
        btnTabFootprints = findViewById(R.id.btnTabFootprints)
        btnTabProfile = findViewById(R.id.btnTabProfile)

        iconTabMessages = findViewById(R.id.iconTabMessages)
        labelTabMessages = findViewById(R.id.labelTabMessages)
        iconTabFriends = findViewById(R.id.iconTabFriends)
        labelTabFriends = findViewById(R.id.labelTabFriends)
        iconTabFootprints = findViewById(R.id.iconTabFootprints)
        labelTabFootprints = findViewById(R.id.labelTabFootprints)
        iconTabProfile = findViewById(R.id.iconTabProfile)
        labelTabProfile = findViewById(R.id.labelTabProfile)

        messagesList = findViewById(R.id.messagesList)
        friendsList = findViewById(R.id.friendsList)
        profileContainer = findViewById(R.id.profileContainer)
        footprintsList = findViewById(R.id.footprintsList)

        // ===== 初始化数据 =====
        friendStorage = FriendStorage(this)
        chatStorage = ChatStorage(this)
        footprintStorage = FootprintStorage(this)
        friendTabManager = FriendTabManager(this, friendsList, friendStorage, chatStorage) {
            refreshMessagesList()
        }
        profileTabManager = ProfileTabManager(this, profileContainer, friendStorage, chatStorage,
            onExport = { startExport() },
            onImport = { startImport() }
        )

        // ===== 标签切换 =====
        btnTabMessages.setOnClickListener { switchTab(0) }
        btnTabFriends.setOnClickListener { switchTab(1) }
        btnTabFootprints.setOnClickListener { switchTab(2) }
        btnTabProfile.setOnClickListener { switchTab(3) }

        // 返回桌面按钮
        findViewById<TextView>(R.id.btnHome).setOnClickListener {
            startActivity(Intent(this, DesktopActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            })
            finish()
        }

        // ===== 来信页加号按钮 =====
        findViewById<TextView>(R.id.btnAddFromMessages).setOnClickListener {
            friendTabManager.showAddFriendDialog()
        }

        // ===== 足迹发布按钮 =====
        findViewById<TextView>(R.id.btnPostFootprint).setOnClickListener {
            showPostFootprintDialog()
        }
    }

    // 每次回到这个页面都刷新列表（从聊天回来后显示最新消息）
    override fun onResume() {
        super.onResume()
        refreshMessagesList()
        friendTabManager.refresh()
        profileTabManager.refresh()
        refreshFootprints()
    }

    // ===== 切换标签页 =====
    private fun switchTab(index: Int) {
        tabMessages.visibility = View.GONE
        tabFriends.visibility = View.GONE
        tabFootprints.visibility = View.GONE
        tabProfile.visibility = View.GONE

        val dimColor = c.textHint
        iconTabMessages.setTextColor(dimColor)
        labelTabMessages.setTextColor(dimColor)
        iconTabFriends.setTextColor(dimColor)
        labelTabFriends.setTextColor(dimColor)
        iconTabFootprints.setTextColor(dimColor)
        labelTabFootprints.setTextColor(dimColor)
        iconTabProfile.setTextColor(dimColor)
        labelTabProfile.setTextColor(dimColor)

        val activeColor = c.highlightColor
        when (index) {
            0 -> {
                tabMessages.visibility = View.VISIBLE
                iconTabMessages.setTextColor(activeColor)
                labelTabMessages.setTextColor(activeColor)
            }
            1 -> {
                tabFriends.visibility = View.VISIBLE
                iconTabFriends.setTextColor(activeColor)
                labelTabFriends.setTextColor(activeColor)
            }
            2 -> {
                tabFootprints.visibility = View.VISIBLE
                iconTabFootprints.setTextColor(activeColor)
                labelTabFootprints.setTextColor(activeColor)
            }
            3 -> {
                tabProfile.visibility = View.VISIBLE
                iconTabProfile.setTextColor(activeColor)
                labelTabProfile.setTextColor(activeColor)
            }
        }
    }

    // ===== 刷新来信列表 =====
    private fun refreshMessagesList() {
        messagesList.removeAllViews()
        val friends = friendStorage.loadFriends()

        if (friends.isEmpty()) {
            addEmptyHint(messagesList, "还没有好友，去好友页添加一个吧 ♡")
            return
        }

        // 按最后一条消息的时间排序（最近的在前面）
        data class FriendWithLastMsg(
            val friend: Friend,
            val lastMsg: StoredMessage?
        )

        val list = friends.map { f ->
            val msgs = chatStorage.loadMessages(f.id)
            FriendWithLastMsg(f, msgs.lastOrNull())
        }.sortedByDescending { it.lastMsg?.timestamp ?: it.friend.createdAt }

        for (item in list) {
            val lastText = item.lastMsg?.content ?: "还没有聊过天~"
            val lastTime = if (item.lastMsg != null) {
                formatTimeShort(item.lastMsg.timestamp)
            } else {
                ""
            }
            addMessageCard(item.friend, lastText, lastTime)
        }
    }

    // ===== 刷新"我的"页面 =====
    private fun addMessageCard(friend: Friend, lastMessage: String, time: String) {
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = getDrawable(R.drawable.chat_card_bg)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
        }

        val avatar = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)).apply {
                marginEnd = dp(12)
            }
            gravity = Gravity.CENTER
            this.text = friend.icon
            textSize = 18f
            setTextColor(c.accentStrong)
            setBackgroundResource(R.drawable.icon_bg)
        }

        val infoLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val tvName = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
            this.text = friend.name
            textSize = 14f
            setTextColor(c.textPrimary)
        }

        val tvTime = TextView(this).apply {
            this.text = time
            textSize = 10f
            setTextColor(c.dateLabel)
        }

        topRow.addView(tvName)

        // 模型标签（如果有单独配置的话显示）
        if (friend.apiModel.isNotEmpty()) {
            val modelName = friend.apiModel.split("/").last()  // 去掉前缀只显示模型名
            val shortName = if (modelName.length > 12) modelName.substring(0, 12) + ".." else modelName
            val tvModel = TextView(this).apply {
                this.text = shortName
                textSize = 8f
                setTextColor(c.accent)
                setPadding(dp(6), dp(1), dp(6), dp(1))
                background = getDrawable(R.drawable.chat_card_bg)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(6) }
            }
            topRow.addView(tvModel)
        }

        topRow.addView(tvTime)

        val tvLastMsg = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(3)
            }
            this.text = lastMessage
            textSize = 12f
            setTextColor(c.tipText)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        infoLayout.addView(topRow)
        infoLayout.addView(tvLastMsg)

        // 续火花
        val streak = friendTabManager.calculateStreak(friend.id)
        if (streak > 0) {
            val streakView = TextView(this).apply {
                this.text = "🔥 $streak 天"
                textSize = 10f
                setTextColor(c.warning)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(3) }
            }
            infoLayout.addView(streakView)
        }

        card.addView(avatar)
        card.addView(infoLayout)

        card.setOnClickListener {
            val intent = Intent(this, ChatConversationActivity::class.java)
            intent.putExtra("friend_id", friend.id)
            intent.putExtra("friend_name", friend.name)
            intent.putExtra("friend_icon", friend.icon)
            startActivity(intent)
        }

        messagesList.addView(card)
    }

    // ===== 刷新足迹列表 =====
    private fun refreshFootprints() {
        footprintsList.removeAllViews()
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }

        val footprints = footprintStorage.loadFootprints()

        if (footprints.isEmpty()) {
            val hint = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(80) }
                gravity = Gravity.CENTER
                this.text = "还没有足迹~\n点右上角 ✎ 发布第一条吧"
                textSize = 13f
                setTextColor(c.dateLabel)
                setLineSpacing(0f, 1.4f)
            }
            footprintsList.addView(hint)
            return
        }

        for (fp in footprints) {
            addFootprintCard(fp)
        }
    }

    // ===== 足迹卡片 =====
    private fun addFootprintCard(fp: Footprint) {
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.chat_card_bg)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }

        // 头部：头像 + 名字 + 时间
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }

        val isUser = fp.authorId == "user"
        val avatar = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply {
                marginEnd = dp(8)
            }
            gravity = Gravity.CENTER
            this.text = if (isUser) "♡" else "★"
            textSize = 12f
            setTextColor(if (isUser) c.textSecondary else c.accentStrong)
            setBackgroundResource(R.drawable.icon_bg)
        }

        val name = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            this.text = fp.authorName
            textSize = 13f
            setTextColor(c.textSecondary)
        }

        val timeAgo = TextView(this).apply {
            this.text = formatTimeAgo(fp.timestamp)
            textSize = 10f
            setTextColor(c.dateLabel)
        }

        header.addView(avatar)
        header.addView(name)
        header.addView(timeAgo)
        card.addView(header)

        // 正文
        if (fp.content.isNotEmpty()) {
            val content = TextView(this).apply {
                this.text = fp.content
                textSize = 14f
                setTextColor(c.textOnAccent)
                setLineSpacing(0f, 1.4f)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) }
            }
            card.addView(content)
        }

        // 图片
        if (fp.imagePath.isNotEmpty()) {
            val file = java.io.File(fp.imagePath)
            if (file.exists()) {
                val imageView = android.widget.ImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(6) }
                    adjustViewBounds = true
                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                    val bitmap = android.graphics.BitmapFactory.decodeFile(fp.imagePath)
                    setImageBitmap(bitmap)
                }
                card.addView(imageView)
            }
        }

        // 评论区
        if (fp.comments.isNotEmpty()) {
            val commentBg = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(c.border)
                setPadding(dp(10), dp(6), dp(10), dp(6))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) }
            }

            for (comment in fp.comments) {
                val commentView = TextView(this).apply {
                    this.text = "${comment.authorName}: ${comment.content}"
                    textSize = 12f
                    setTextColor(c.textSecondary)
                    setLineSpacing(0f, 1.3f)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(2) }
                }
                commentBg.addView(commentView)
            }
            card.addView(commentBg)
        }

        // 底部操作栏：评论 + 删除
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val btnComment = TextView(this).apply {
            this.text = "💬 评论"
            textSize = 11f
            setTextColor(c.tipText)
            setPadding(0, dp(4), dp(16), dp(4))
            setOnClickListener { showCommentDialog(fp) }
        }
        actions.addView(btnComment)

        // 只有自己发的才能删
        if (fp.authorId == "user") {
            val btnDelete = TextView(this).apply {
                this.text = "🗑 删除"
                textSize = 11f
                setTextColor(c.errorBg)
                setPadding(0, dp(4), 0, dp(4))
                setOnClickListener {
                    AlertDialog.Builder(this@ChatActivity)
                        .setTitle("删除足迹")
                        .setMessage("确定删除这条足迹吗？")
                        .setPositiveButton("删除") { _, _ ->
                            footprintStorage.deleteFootprint(fp.id)
                            refreshFootprints()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
            actions.addView(btnDelete)
        }

        card.addView(actions)
        footprintsList.addView(card)
    }

    // ===== 发布足迹对话框 =====
    private fun showPostFootprintDialog() {
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }
        val prefs = getSharedPreferences("haven_prefs", MODE_PRIVATE)
        val userName = prefs.getString("user_name", "") ?: ""

        if (userName.isEmpty()) {
            Toast.makeText(this, "请先在「我的」页面设置名字", Toast.LENGTH_SHORT).show()
            return
        }

        val input = EditText(this).apply {
            this.hint = "此刻你在想什么..."
            setPadding(dp(16), dp(12), dp(16), dp(12))
            textSize = 14f
            minLines = 3
            gravity = Gravity.TOP
        }

        AlertDialog.Builder(this)
            .setTitle("发布足迹")
            .setView(input)
            .setPositiveButton("发布") { _, _ ->
                val content = input.text.toString().trim()
                if (content.isNotEmpty()) {
                    footprintStorage.addFootprint("user", userName, content)
                    refreshFootprints()
                    Toast.makeText(this, "已发布 ♡", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ===== 评论对话框 =====
    private fun showCommentDialog(fp: Footprint) {
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }
        val prefs = getSharedPreferences("haven_prefs", MODE_PRIVATE)
        val userName = prefs.getString("user_name", "") ?: "我"

        val input = EditText(this).apply {
            this.hint = "写点评论..."
            setPadding(dp(16), dp(12), dp(16), dp(12))
            textSize = 14f
        }

        AlertDialog.Builder(this)
            .setTitle("评论")
            .setView(input)
            .setPositiveButton("发送") { _, _ ->
                val content = input.text.toString().trim()
                if (content.isNotEmpty()) {
                    footprintStorage.addComment(fp.id, "user", userName, content)
                    refreshFootprints()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ===== 时间格式化（几分钟前/几小时前） =====
    private fun formatTimeAgo(timestamp: Long): String {
        val diff = System.currentTimeMillis() - timestamp
        val minutes = diff / 60000
        val hours = minutes / 60
        val days = hours / 24

        return when {
            minutes < 1 -> "刚刚"
            minutes < 60 -> "${minutes}分钟前"
            hours < 24 -> "${hours}小时前"
            days < 7 -> "${days}天前"
            else -> SimpleDateFormat("M月d日", Locale.CHINESE).format(Date(timestamp))
        }
    }

    // ===== 导出数据 =====
    private fun startExport() {
        val dateStr = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "haven_backup_$dateStr.json")
        }
        startActivityForResult(intent, EXPORT_REQUEST)
    }

    private fun doExport(uri: Uri) {
        try {
            val friends = friendStorage.loadFriends()

            // 构建导出数据
            val friendsArray = JSONArray()
            for (f in friends) {
                val chatMessages = chatStorage.loadMessages(f.id)
                val msgsArray = JSONArray()
                for (msg in chatMessages) {
                    msgsArray.put(JSONObject().apply {
                        put("role", msg.role)
                        put("content", msg.content)
                        put("timestamp", msg.timestamp)
                        if (msg.thinking.isNotEmpty()) put("thinking", msg.thinking)
                        if (msg.imagePath.isNotEmpty()) put("image_path", msg.imagePath)
                        if (msg.type != "text") put("type", msg.type)
                        if (msg.extras.isNotEmpty()) put("extras", msg.extras)
                    })
                }

                friendsArray.put(JSONObject().apply {
                    put("id", f.id)
                    put("name", f.name)
                    put("group", f.group)
                    put("icon", f.icon)
                    put("bio", f.bio)
                    put("display_code", f.displayCode)
                    put("api_url", f.apiUrl)
                    put("api_key", f.apiKey)
                    put("api_model", f.apiModel)
                    put("api_type", f.apiType)
                    put("dream_api_url", f.dreamApiUrl)
                    put("dream_api_key", f.dreamApiKey)
                    put("dream_api_model", f.dreamApiModel)
                    put("dream_api_type", f.dreamApiType)
                    put("created_at", f.createdAt)
                    put("messages", msgsArray)
                })
            }

            val prefs = getSharedPreferences("haven_prefs", MODE_PRIVATE)
            val exportData = JSONObject().apply {
                put("app", "haven_guiqi")
                put("version", "0.1.0")
                put("export_time", System.currentTimeMillis())
                put("user_name", prefs.getString("user_name", "") ?: "")
                put("friends", friendsArray)
            }

            // 写入文件
            contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(exportData.toString(2).toByteArray())
            }

            val totalMsgs = friends.sumOf { chatStorage.loadMessages(it.id).size }
            Toast.makeText(this,
                "导出成功 ♡\n${friends.size} 个好友，$totalMsgs 条消息",
                Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ===== 导入数据 =====
    private fun startImport() {
        AlertDialog.Builder(this)
            .setTitle("导入数据")
            .setMessage("导入会覆盖当前的好友和聊天记录，确定继续吗？\n建议先导出一份当前数据备份。")
            .setPositiveButton("选择文件") { _, _ ->
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/json"
                }
                startActivityForResult(intent, IMPORT_REQUEST)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doImport(uri: Uri) {
        try {
            val jsonStr = contentResolver.openInputStream(uri)?.use { stream ->
                stream.bufferedReader().readText()
            } ?: throw Exception("无法读取文件")

            val data = JSONObject(jsonStr)

            // 验证是归栖的备份文件
            if (data.optString("app") != "haven_guiqi") {
                Toast.makeText(this, "这不是归栖的备份文件", Toast.LENGTH_SHORT).show()
                return
            }

            // 恢复用户名
            val userName = data.optString("user_name", "")
            if (userName.isNotEmpty()) {
                getSharedPreferences("haven_prefs", MODE_PRIVATE)
                    .edit().putString("user_name", userName).apply()
            }

            // 恢复好友和聊天记录
            val friendsArray = data.getJSONArray("friends")
            val friends = mutableListOf<Friend>()

            for (i in 0 until friendsArray.length()) {
                val obj = friendsArray.getJSONObject(i)
                val friend = Friend(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    group = obj.optString("group", "好友"),
                    icon = obj.optString("icon", "★"),
                    bio = obj.optString("bio", ""),
                    displayCode = obj.optString("display_code", ""),
                    apiUrl = obj.optString("api_url", ""),
                    apiKey = obj.optString("api_key", ""),
                    apiModel = obj.optString("api_model", ""),
                    apiType = obj.optString("api_type", "openai"),
                    dreamApiUrl = obj.optString("dream_api_url", ""),
                    dreamApiKey = obj.optString("dream_api_key", ""),
                    dreamApiModel = obj.optString("dream_api_model", ""),
                    dreamApiType = obj.optString("dream_api_type", "openai"),
                    createdAt = obj.optLong("created_at", System.currentTimeMillis())
                )
                friends.add(friend)

                // 恢复聊天记录
                val msgsArray = obj.optJSONArray("messages")
                if (msgsArray != null && msgsArray.length() > 0) {
                    val messages = mutableListOf<StoredMessage>()
                    for (j in 0 until msgsArray.length()) {
                        val msgObj = msgsArray.getJSONObject(j)
                        messages.add(StoredMessage(
                            role = msgObj.getString("role"),
                            content = msgObj.getString("content"),
                            timestamp = msgObj.optLong("timestamp", 0L),
                            thinking = msgObj.optString("thinking", ""),
                            imagePath = msgObj.optString("image_path", ""),
                            type = msgObj.optString("type", "text"),
                            extras = msgObj.optString("extras", "")
                        ))
                    }
                    chatStorage.saveMessages(friend.id, messages)
                }
            }

            friendStorage.saveFriends(friends)

            // 刷新界面
            refreshMessagesList()
            friendTabManager.refresh()
            profileTabManager.refresh()

            Toast.makeText(this,
                "导入成功 ♡\n${friends.size} 个好友已恢复",
                Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "导入失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ===== 处理文件选择结果 =====
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return

        when (requestCode) {
            EXPORT_REQUEST -> doExport(data.data!!)
            IMPORT_REQUEST -> doImport(data.data!!)
        }
    }

    // ===== 空状态提示 =====
    private fun addEmptyHint(container: LinearLayout, msg: String) {
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }
        val hint = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(80)
            }
            gravity = Gravity.CENTER
            this.text = msg
            textSize = 13f
            setTextColor(c.dateLabel)
        }
        container.addView(hint)
    }

    // ===== 格式化时间（来信列表用） =====
    private fun formatTimeShort(timestamp: Long): String {
        val msgDate = Calendar.getInstance().apply { timeInMillis = timestamp }
        val today = Calendar.getInstance()

        return when {
            msgDate.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
            msgDate.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) ->
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
            msgDate.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
            msgDate.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) - 1 ->
                "昨天"
            else ->
                SimpleDateFormat("M/d", Locale.getDefault()).format(Date(timestamp))
        }
    }
}