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
        insetsController.isAppearanceLightStatusBars = false

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

        // ===== 标签切换 =====
        btnTabMessages.setOnClickListener { switchTab(0) }
        btnTabFriends.setOnClickListener { switchTab(1) }
        btnTabFootprints.setOnClickListener { switchTab(2) }
        btnTabProfile.setOnClickListener { switchTab(3) }

        // ===== 来信页加号按钮 =====
        findViewById<TextView>(R.id.btnAddFromMessages).setOnClickListener {
            showAddFriendDialog()
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
        refreshFriendsList()
        refreshProfile()
        refreshFootprints()
    }

    // ===== 切换标签页 =====
    private fun switchTab(index: Int) {
        tabMessages.visibility = View.GONE
        tabFriends.visibility = View.GONE
        tabFootprints.visibility = View.GONE
        tabProfile.visibility = View.GONE

        val dimColor = 0x33FFFFFF.toInt()
        iconTabMessages.setTextColor(dimColor)
        labelTabMessages.setTextColor(dimColor)
        iconTabFriends.setTextColor(dimColor)
        labelTabFriends.setTextColor(dimColor)
        iconTabFootprints.setTextColor(dimColor)
        labelTabFootprints.setTextColor(dimColor)
        iconTabProfile.setTextColor(dimColor)
        labelTabProfile.setTextColor(dimColor)

        val activeColor = 0xFFB3A0FF.toInt()
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

    // ===== 刷新好友列表 =====
    private fun refreshFriendsList() {
        friendsList.removeAllViews()
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }
        val friends = friendStorage.loadFriends()

        // 按分组显示
        val groups = friends.groupBy { it.group }
        for ((groupName, groupFriends) in groups) {
            // 分组标题
            val groupTitle = TextView(this).apply {
                this.text = "- $groupName -"
                textSize = 10f
                setTextColor(0x26FFFFFF.toInt())
                setPadding(dp(4), dp(8), dp(4), dp(8))
                letterSpacing = 0.2f
            }
            friendsList.addView(groupTitle)

            for (f in groupFriends) {
                addFriendCard(f)
            }
        }

        if (friends.isEmpty()) {
            addEmptyHint(friendsList, "点下面的按钮添加第一个好友吧~")
        }

        // 添加好友按钮
        val addBtn = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(16)
            }
            gravity = Gravity.CENTER
            this.text = "＋ 添加新好友"
            textSize = 11f
            setTextColor(0x40B3A0FF.toInt())
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = getDrawable(R.drawable.chat_card_bg)
        }
        addBtn.setOnClickListener { showAddFriendDialog() }
        friendsList.addView(addBtn)
    }

    // ===== 来信卡片 =====
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
            setTextColor(0x99B3A0FF.toInt())
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
            setTextColor(0xD9FFFFFF.toInt())
        }

        val tvTime = TextView(this).apply {
            this.text = time
            textSize = 10f
            setTextColor(0x26FFFFFF.toInt())
        }

        topRow.addView(tvName)

        // 模型标签（如果有单独配置的话显示）
        if (friend.apiModel.isNotEmpty()) {
            val modelName = friend.apiModel.split("/").last()  // 去掉前缀只显示模型名
            val shortName = if (modelName.length > 12) modelName.substring(0, 12) + ".." else modelName
            val tvModel = TextView(this).apply {
                this.text = shortName
                textSize = 8f
                setTextColor(0x4DB3A0FF.toInt())
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
            setTextColor(0x4DFFFFFF.toInt())
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        infoLayout.addView(topRow)
        infoLayout.addView(tvLastMsg)

        // 续火花
        val streak = calculateStreak(friend.id)
        if (streak > 0) {
            val streakView = TextView(this).apply {
                this.text = "🔥 $streak 天"
                textSize = 10f
                setTextColor(0x59FFB066.toInt())
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

    // ===== 好友卡片 =====
    private fun addFriendCard(friend: Friend) {
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = getDrawable(R.drawable.chat_card_bg)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
        }

        val avatar = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply {
                marginEnd = dp(12)
            }
            gravity = Gravity.CENTER
            this.text = friend.icon
            textSize = 16f
            setTextColor(0x99B3A0FF.toInt())
            setBackgroundResource(R.drawable.icon_bg)
        }

        val infoLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }

        val tvName = TextView(this).apply {
            this.text = friend.name
            textSize = 14f
            setTextColor(0xD9FFFFFF.toInt())
        }

        val detailRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(4)
            }
        }

        val tvGroup = TextView(this).apply {
            this.text = friend.group
            textSize = 9f
            setTextColor(0x73B3A0FF.toInt())
            setPadding(dp(8), dp(2), dp(8), dp(2))
            background = getDrawable(R.drawable.chat_card_bg)
        }

        val tvCode = TextView(this).apply {
            this.text = friend.id
            textSize = 9f
            setTextColor(0x26FFFFFF.toInt())
            setPadding(dp(8), 0, 0, 0)
        }

        detailRow.addView(tvGroup)
        detailRow.addView(tvCode)

        // 续火花
        val streak = calculateStreak(friend.id)
        if (streak > 0) {
            val tvStreak = TextView(this).apply {
                this.text = "🔥$streak"
                textSize = 9f
                setTextColor(0x59FFB066.toInt())
                setPadding(dp(8), 0, 0, 0)
            }
            detailRow.addView(tvStreak)
        }

        infoLayout.addView(tvName)
        infoLayout.addView(detailRow)

        val arrow = TextView(this).apply {
            this.text = "›"
            textSize = 18f
            setTextColor(0x1AFFFFFF.toInt())
            setPadding(dp(8), dp(8), dp(4), dp(8))
            // 点击箭头进入好友详情
            setOnClickListener {
                val intent = Intent(this@ChatActivity, FriendDetailActivity::class.java)
                intent.putExtra("friend_id", friend.id)
                startActivity(intent)
            }
        }

        card.addView(avatar)
        card.addView(infoLayout)
        card.addView(arrow)

        // 点击进入聊天
        card.setOnClickListener {
            val intent = Intent(this, ChatConversationActivity::class.java)
            intent.putExtra("friend_id", friend.id)
            intent.putExtra("friend_name", friend.name)
            intent.putExtra("friend_icon", friend.icon)
            startActivity(intent)
        }

        // 长按弹出编辑/删除菜单
        card.setOnLongClickListener {
            showFriendOptions(friend)
            true
        }

        friendsList.addView(card)
    }

    // ===== 添加好友对话框 =====
    private fun showAddFriendDialog() {
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }

        val input = EditText(this).apply {
            hint = "输入好友名称"
            setPadding(dp(16), dp(12), dp(16), dp(12))
            textSize = 15f
        }

        AlertDialog.Builder(this)
            .setTitle("添加新好友")
            .setView(input)
            .setPositiveButton("添加") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    val friend = friendStorage.addFriend(name)
                    Toast.makeText(this, "已添加「${friend.name}」\n编码: ${friend.id}", Toast.LENGTH_SHORT).show()
                    refreshMessagesList()
                    refreshFriendsList()
                } else {
                    Toast.makeText(this, "名称不能为空", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ===== 好友选项菜单（长按触发） =====
    private fun showFriendOptions(friend: Friend) {
        val options = arrayOf("编辑名称", "修改分组", "配置 API", "删除好友")
        AlertDialog.Builder(this)
            .setTitle(friend.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showEditNameDialog(friend)
                    1 -> showEditGroupDialog(friend)
                    2 -> showApiConfigDialog(friend)
                    3 -> showDeleteConfirm(friend)
                }
            }
            .show()
    }

    // ===== 编辑名称 =====
    private fun showEditNameDialog(friend: Friend) {
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }

        val input = EditText(this).apply {
            setText(friend.name)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            textSize = 15f
        }

        AlertDialog.Builder(this)
            .setTitle("编辑名称")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    friendStorage.updateFriend(friend.copy(name = newName))
                    refreshMessagesList()
                    refreshFriendsList()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ===== 编辑分组 =====
    private fun showEditGroupDialog(friend: Friend) {
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }

        val input = EditText(this).apply {
            setText(friend.group)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            textSize = 15f
        }

        AlertDialog.Builder(this)
            .setTitle("修改分组")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val newGroup = input.text.toString().trim()
                if (newGroup.isNotEmpty()) {
                    friendStorage.updateFriend(friend.copy(group = newGroup))
                    refreshMessagesList()
                    refreshFriendsList()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ===== 配置好友专属 API =====
    private fun showApiConfigDialog(friend: Friend) {
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }

        // 创建表单布局
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }

        // API 类型选择按钮
        var selectedType = friend.apiType
        val typeNames = arrayOf("OpenAI 格式（GPT/DeepSeek/中转站）", "Claude 原生", "Gemini 原生")
        val typeValues = arrayOf("openai", "claude", "gemini")
        val currentTypeIndex = typeValues.indexOf(selectedType).coerceAtLeast(0)

        val typeBtn = TextView(this).apply {
            this.text = "API 类型: ${typeNames[currentTypeIndex]}"
            textSize = 14f
            setPadding(0, 0, 0, dp(12))
        }
        typeBtn.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("选择 API 类型")
                .setItems(typeNames) { _, which ->
                    selectedType = typeValues[which]
                    typeBtn.text = "API 类型: ${typeNames[which]}"
                }
                .show()
        }
        layout.addView(typeBtn)

        // 提示文字
        val hint = TextView(this).apply {
            this.text = "留空则使用全局配置（设置页的配置）"
            textSize = 11f
            setTextColor(0xFF888888.toInt())
            setPadding(0, 0, 0, dp(12))
        }
        layout.addView(hint)

        // API 地址
        val inputUrl = EditText(this).apply {
            this.hint = "API 地址"
            setText(friend.apiUrl)
            textSize = 14f
        }
        layout.addView(inputUrl)

        // API 密钥
        val inputKey = EditText(this).apply {
            this.hint = "API 密钥"
            setText(friend.apiKey)
            textSize = 14f
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(inputKey)

        // 模型名
        val inputModel = EditText(this).apply {
            this.hint = "模型名称"
            setText(friend.apiModel)
            textSize = 14f
        }
        layout.addView(inputModel)

        AlertDialog.Builder(this)
            .setTitle("${friend.name} 的 API 配置")
            .setView(layout)
            .setPositiveButton("保存") { _, _ ->
                friendStorage.updateFriend(friend.copy(
                    apiUrl = inputUrl.text.toString().trim(),
                    apiKey = inputKey.text.toString().trim(),
                    apiModel = inputModel.text.toString().trim(),
                    apiType = selectedType
                ))
                Toast.makeText(this, "API 配置已保存 ♡", Toast.LENGTH_SHORT).show()
                refreshFriendsList()
            }
            .setNeutralButton("清除配置") { _, _ ->
                friendStorage.updateFriend(friend.copy(
                    apiUrl = "", apiKey = "", apiModel = "", apiType = "openai"
                ))
                Toast.makeText(this, "已清除，将使用全局配置", Toast.LENGTH_SHORT).show()
                refreshFriendsList()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ===== 删除确认 =====
    private fun showDeleteConfirm(friend: Friend) {
        AlertDialog.Builder(this)
            .setTitle("删除好友")
            .setMessage("确定要删除「${friend.name}」吗？\n聊天记录也会一起删除，无法恢复。")
            .setPositiveButton("删除") { _, _ ->
                friendStorage.deleteFriend(friend.id)
                Toast.makeText(this, "已删除「${friend.name}」", Toast.LENGTH_SHORT).show()
                refreshMessagesList()
                refreshFriendsList()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ===== 刷新"我的"页面 =====
    private fun refreshProfile() {
        profileContainer.removeAllViews()
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }
        val prefs = getSharedPreferences("haven_prefs", MODE_PRIVATE)
        val userName = prefs.getString("user_name", "") ?: ""

        // ===== 头像区 =====
        val avatarSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, dp(20))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val avatarCircle = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(64), dp(64))
            gravity = Gravity.CENTER
            this.text = if (userName.isNotEmpty()) userName.first().toString() else "?"
            textSize = 26f
            setTextColor(0x80B3A0FF.toInt())
            setBackgroundResource(R.drawable.icon_bg)
        }

        val nameText = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
            this.text = if (userName.isNotEmpty()) userName else "点击设置名字"
            textSize = 18f
            setTextColor(if (userName.isNotEmpty()) 0xD9FFFFFF.toInt() else 0x4DFFFFFF.toInt())
        }

        val subtitle = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
            this.text = "这里是你的归栖"
            textSize = 11f
            setTextColor(0x26FFFFFF.toInt())
        }

        avatarSection.addView(avatarCircle)
        avatarSection.addView(nameText)
        avatarSection.addView(subtitle)
        avatarSection.setOnClickListener { showEditUserNameDialog() }
        profileContainer.addView(avatarSection)

        // ===== 统计卡片 =====
        val friends = friendStorage.loadFriends()
        var totalMessages = 0
        for (f in friends) {
            totalMessages += chatStorage.loadMessages(f.id).size
        }

        val statsCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = getDrawable(R.drawable.chat_card_bg)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }
        }

        fun statItem(number: String, label: String): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }.also { layout ->
                layout.addView(TextView(this).apply {
                    this.text = number
                    textSize = 20f
                    setTextColor(0xD9FFFFFF.toInt())
                    gravity = Gravity.CENTER
                })
                layout.addView(TextView(this).apply {
                    this.text = label
                    textSize = 10f
                    setTextColor(0x4DFFFFFF.toInt())
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(3) }
                })
            }
        }

        statsCard.addView(statItem("${friends.size}", "好友"))
        statsCard.addView(statItem("$totalMessages", "消息"))
        statsCard.addView(statItem("${friends.count { it.apiUrl.isNotEmpty() }}", "专属API"))
        profileContainer.addView(statsCard)

        // ===== 设置列表 =====
        addProfileSectionTitle("个人设置")

        addProfileItem("用户名称", if (userName.isNotEmpty()) userName else "未设置",
            "AI 能看到的你的名字") {
            showEditUserNameDialog()
        }

        // 用户的自我描述
        val userBioPrefs = getSharedPreferences("haven_user", MODE_PRIVATE)
        val myBio = userBioPrefs.getString("my_bio", "") ?: ""
        addProfileItem("我眼中的自己",
            if (myBio.isNotEmpty()) myBio.take(20) + (if (myBio.length > 20) "..." else "") else "还没有写",
            "你的自我描述，AI 好奇时可以翻看（不会主动塞给 AI）") {
            showEditMyBioDialog()
        }

        addProfileSectionTitle("数据管理")

        addProfileItem("导出数据", "", "把好友和聊天记录导出备份") {
            startExport()
        }

        addProfileItem("导入数据", "", "从备份文件恢复好友和聊天记录") {
            startImport()
        }

        addProfileSectionTitle("关于")

        addProfileItem("归栖 Haven", "v0.1.0", "一个属于你的地方") { }
    }

    // ===== 编辑用户名 =====
    private fun showEditUserNameDialog() {
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }
        val prefs = getSharedPreferences("haven_prefs", MODE_PRIVATE)
        val currentName = prefs.getString("user_name", "") ?: ""

        val input = EditText(this).apply {
            setText(currentName)
            this.hint = "你希望 AI 怎么称呼你"
            setPadding(dp(16), dp(12), dp(16), dp(12))
            textSize = 15f
        }

        AlertDialog.Builder(this)
            .setTitle("设置名字")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val name = input.text.toString().trim()
                prefs.edit().putString("user_name", name).apply()
                refreshProfile()
                if (name.isNotEmpty()) {
                    Toast.makeText(this, "以后 AI 就叫你「$name」了 ♡", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ===== 编辑用户自我描述 =====
    private fun showEditMyBioDialog() {
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }
        val prefs = getSharedPreferences("haven_user", MODE_PRIVATE)
        val currentBio = prefs.getString("my_bio", "") ?: ""

        val input = EditText(this).apply {
            setText(currentBio)
            this.hint = "写写你眼中的自己吧...\n\nAI 不会每次都看到这些，只有它好奇的时候才会主动翻看"
            setPadding(dp(16), dp(12), dp(16), dp(12))
            textSize = 14f
            minLines = 5
            gravity = Gravity.TOP
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }

        AlertDialog.Builder(this)
            .setTitle("我眼中的自己")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val bio = input.text.toString().trim()
                prefs.edit().putString("my_bio", bio).apply()
                refreshProfile()
                if (bio.isNotEmpty()) {
                    Toast.makeText(this, "已保存 ♡", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ===== "我的"页面辅助方法 =====
    private fun addProfileSectionTitle(title: String) {
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }
        val tv = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12); bottomMargin = dp(8) }
            this.text = title
            textSize = 12f
            setTextColor(0x66B3A0FF.toInt())
            setPadding(dp(4), 0, 0, 0)
            letterSpacing = 0.1f
        }
        profileContainer.addView(tv)
    }

    private fun addProfileItem(title: String, value: String, desc: String, onClick: () -> Unit) {
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.chat_card_bg)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
            setOnClickListener { onClick() }
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val tvTitle = TextView(this).apply {
            this.text = title
            textSize = 14f
            setTextColor(0xD9FFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        topRow.addView(tvTitle)

        if (value.isNotEmpty()) {
            val tvValue = TextView(this).apply {
                this.text = value
                textSize = 12f
                setTextColor(0x4DB3A0FF.toInt())
            }
            topRow.addView(tvValue)
        }

        card.addView(topRow)

        if (desc.isNotEmpty()) {
            val tvDesc = TextView(this).apply {
                this.text = desc
                textSize = 11f
                setTextColor(0x4DFFFFFF.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(3) }
            }
            card.addView(tvDesc)
        }

        profileContainer.addView(card)
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
                setTextColor(0x26FFFFFF.toInt())
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
            setTextColor(if (isUser) 0x80FFFFFF.toInt() else 0x80B3A0FF.toInt())
            setBackgroundResource(R.drawable.icon_bg)
        }

        val name = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            this.text = fp.authorName
            textSize = 13f
            setTextColor(0x99FFFFFF.toInt())
        }

        val timeAgo = TextView(this).apply {
            this.text = formatTimeAgo(fp.timestamp)
            textSize = 10f
            setTextColor(0x26FFFFFF.toInt())
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
                setTextColor(0xB3FFFFFF.toInt())
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
                setBackgroundColor(0x08FFFFFF.toInt())
                setPadding(dp(10), dp(6), dp(10), dp(6))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) }
            }

            for (c in fp.comments) {
                val commentView = TextView(this).apply {
                    this.text = "${c.authorName}: ${c.content}"
                    textSize = 12f
                    setTextColor(0x80FFFFFF.toInt())
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
            setTextColor(0x4DFFFFFF.toInt())
            setPadding(0, dp(4), dp(16), dp(4))
            setOnClickListener { showCommentDialog(fp) }
        }
        actions.addView(btnComment)

        // 只有自己发的才能删
        if (fp.authorId == "user") {
            val btnDelete = TextView(this).apply {
                this.text = "🗑 删除"
                textSize = 11f
                setTextColor(0x33FF6B6B.toInt())
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
                    })
                }

                friendsArray.put(JSONObject().apply {
                    put("id", f.id)
                    put("name", f.name)
                    put("group", f.group)
                    put("icon", f.icon)
                    put("bio", f.bio)
                    put("api_url", f.apiUrl)
                    put("api_key", f.apiKey)
                    put("api_model", f.apiModel)
                    put("api_type", f.apiType)
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
                    apiUrl = obj.optString("api_url", ""),
                    apiKey = obj.optString("api_key", ""),
                    apiModel = obj.optString("api_model", ""),
                    apiType = obj.optString("api_type", "openai"),
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
                            thinking = msgObj.optString("thinking", "")
                        ))
                    }
                    chatStorage.saveMessages(friend.id, messages)
                }
            }

            friendStorage.saveFriends(friends)

            // 刷新界面
            refreshMessagesList()
            refreshFriendsList()
            refreshProfile()

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

    // ===== 计算续火花（连续聊天天数） =====
    private fun calculateStreak(friendId: String): Int {
        val messages = chatStorage.loadMessages(friendId)
        if (messages.isEmpty()) return 0

        // 收集所有有消息的日期（去重）
        val chatDays = messages.map { msg ->
            val cal = Calendar.getInstance().apply { timeInMillis = msg.timestamp }
            "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.DAY_OF_YEAR)}"
        }.distinct().sortedDescending()

        if (chatDays.isEmpty()) return 0

        // 从今天开始往回数连续天数
        val today = Calendar.getInstance()
        val todayKey = "${today.get(Calendar.YEAR)}-${today.get(Calendar.DAY_OF_YEAR)}"

        // 如果今天没聊过，检查昨天
        val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        val yesterdayKey = "${yesterday.get(Calendar.YEAR)}-${yesterday.get(Calendar.DAY_OF_YEAR)}"

        // 起点必须是今天或昨天，否则火花已断
        val startDay = if (chatDays.contains(todayKey)) {
            today.clone() as Calendar
        } else if (chatDays.contains(yesterdayKey)) {
            yesterday.clone() as Calendar
        } else {
            return 0
        }

        // 从起点往回数连续天
        var streak = 0
        val checkDay = startDay.clone() as Calendar
        while (true) {
            val dayKey = "${checkDay.get(Calendar.YEAR)}-${checkDay.get(Calendar.DAY_OF_YEAR)}"
            if (chatDays.contains(dayKey)) {
                streak++
                checkDay.add(Calendar.DAY_OF_YEAR, -1)
            } else {
                break
            }
        }
        return streak
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
            setTextColor(0x26FFFFFF.toInt())
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