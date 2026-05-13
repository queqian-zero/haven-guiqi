package com.haven.guiqi

import android.app.AlertDialog
import android.content.Intent
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
import java.text.SimpleDateFormat
import java.util.*

class ChatActivity : AppCompatActivity() {

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

    // ===== 数据 =====
    private lateinit var friendStorage: FriendStorage
    private lateinit var chatStorage: ChatStorage

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

        // ===== 初始化数据 =====
        friendStorage = FriendStorage(this)
        chatStorage = ChatStorage(this)

        // ===== 标签切换 =====
        btnTabMessages.setOnClickListener { switchTab(0) }
        btnTabFriends.setOnClickListener { switchTab(1) }
        btnTabFootprints.setOnClickListener { switchTab(2) }
        btnTabProfile.setOnClickListener { switchTab(3) }

        // ===== 来信页加号按钮 =====
        findViewById<TextView>(R.id.btnAddFromMessages).setOnClickListener {
            showAddFriendDialog()
        }
    }

    // 每次回到这个页面都刷新列表（从聊天回来后显示最新消息）
    override fun onResume() {
        super.onResume()
        refreshMessagesList()
        refreshFriendsList()
        refreshProfile()
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

        infoLayout.addView(tvName)
        infoLayout.addView(detailRow)

        val arrow = TextView(this).apply {
            this.text = "›"
            textSize = 18f
            setTextColor(0x1AFFFFFF.toInt())
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

        addProfileSectionTitle("数据管理")

        addProfileItem("导出数据", "", "把好友和聊天记录导出备份") {
            Toast.makeText(this, "导出功能开发中 ♡", Toast.LENGTH_SHORT).show()
        }

        addProfileItem("导入数据", "", "从备份文件恢复好友和聊天记录") {
            Toast.makeText(this, "导入功能开发中 ♡", Toast.LENGTH_SHORT).show()
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