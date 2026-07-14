package com.haven.guiqi

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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

class FriendDetailActivity : AppCompatActivity() {

    private val c get() = ThemeHelper.getColors(this)

    private lateinit var detailContainer: LinearLayout
    private lateinit var friendStorage: FriendStorage
    private lateinit var chatStorage: ChatStorage
    private var friendId = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        setContentView(R.layout.activity_friend_detail)

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

        detailContainer = findViewById(R.id.detailContainer)
        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }

        friendId = intent.getStringExtra("friend_id") ?: ""
        friendStorage = FriendStorage(this)
        chatStorage = ChatStorage(this)

        buildDetail()
    }

    override fun onResume() {
        super.onResume()
        buildDetail()
    }

    private fun buildDetail() {
        detailContainer.removeAllViews()
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }

        val friend = friendStorage.getFriend(friendId)
        if (friend == null) {
            Toast.makeText(this, "好友不存在", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // ===== 头像区 =====
        val avatarSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, dp(20))
        }

        val avatarCircle = FriendAvatarHelper.create(this, friend, 72)
        avatarCircle.setOnClickListener { showEditIconDialog(friend) }

        val nameText = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
            this.text = friend.name
            textSize = 20f
            setTextColor(c.textPrimary)
        }

        val codeText = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
            this.text = friend.visibleCode
            textSize = 12f
            setTextColor(c.textHint)
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("code", friend.visibleCode))
                Toast.makeText(this@FriendDetailActivity, "编码已复制", Toast.LENGTH_SHORT).show()
            }
        }

        // 续火花
        val messages = chatStorage.loadMessages(friend.id)
        val streak = calculateStreak(friend.id)
        val streakText = if (streak > 0) {
            TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) }
                this.text = "🔥 连续聊天 $streak 天"
                textSize = 12f
                setTextColor(c.warning)
            }
        } else null

        avatarSection.addView(avatarCircle)
        avatarSection.addView(nameText)
        avatarSection.addView(codeText)
        if (streakText != null) avatarSection.addView(streakText)
        detailContainer.addView(avatarSection)

        // ===== 统计卡片 =====
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
                    setTextColor(c.textPrimary)
                    gravity = Gravity.CENTER
                })
                layout.addView(TextView(this).apply {
                    this.text = label
                    textSize = 10f
                    setTextColor(c.textHint)
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(3) }
                })
            }
        }

        // 统计第一次聊天至今的天数
        val daysKnown = if (friend.createdAt > 0) {
            val diff = System.currentTimeMillis() - friend.createdAt
            (diff / 86400000).toInt() + 1
        } else 0

        statsCard.addView(statItem("${messages.size}", "消息"))
        statsCard.addView(statItem("$daysKnown", "相识(天)"))
        statsCard.addView(statItem("$streak", "续火花"))
        detailContainer.addView(statsCard)

        // ===== 基本信息 =====
        addSection("基本信息")

        addEditableItem("名称", friend.name, "点击修改名字") {
            showEditDialog("修改名称", friend.name) { newValue ->
                friendStorage.updateFriend(friend.copy(name = newValue))
                buildDetail()
            }
        }

        addEditableItem("分组", friend.group, "点击修改分组") {
            showEditDialog("修改分组", friend.group) { newValue ->
                friendStorage.updateFriend(friend.copy(group = newValue))
                buildDetail()
            }
        }

        addEditableItem("头像", friend.icon, "点击更换头像字符") {
            showEditIconDialog(friend)
        }

        addEditableItem("编码", friend.visibleCode, "好友的编码，点击复制") {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("code", friend.visibleCode))
            Toast.makeText(this, "编码已复制: ${friend.visibleCode}", Toast.LENGTH_SHORT).show()
        }

        // ===== AI 简介 =====
        addSection("AI 简介")

        val bioText = if (friend.bio.isNotEmpty()) friend.bio else "还没有简介，点击添加"
        addEditableItem("自我认识", bioText, "AI 对自己的认识") {
            showEditDialog("AI 简介", friend.bio, multiLine = true) { newValue ->
                friendStorage.updateFriend(friend.copy(bio = newValue))
                buildDetail()
            }
        }

        // ===== API 配置 =====
        addSection("API 配置")

        if (friend.apiUrl.isNotEmpty()) {
            addInfoItem("类型", when (friend.apiType) {
                "claude" -> "Claude 原生"
                "gemini" -> "Gemini 原生"
                else -> "OpenAI 格式"
            })
            addInfoItem("模型", friend.apiModel)
            addInfoItem("地址", friend.apiUrl)
        } else {
            addInfoItem("当前", "使用全局 API 配置")
        }

        // ===== 操作 =====
        addSection("操作")

        addActionItem("💬 发起聊天", "跳转到对话界面") {
            val intent = Intent(this, ChatConversationActivity::class.java)
            intent.putExtra("friend_id", friend.id)
            intent.putExtra("friend_name", friend.name)
            intent.putExtra("friend_icon", friend.icon)
            startActivity(intent)
            finish()
        }

        addActionItem("🗑 删除好友", "删除好友及所有聊天记录") {
            AlertDialog.Builder(this)
                .setTitle("删除好友")
                .setMessage("确定要删除「${friend.name}」吗？\n聊天记录也会一起删除，无法恢复。")
                .setPositiveButton("删除") { _, _ ->
                    friendStorage.deleteFriend(friend.id)
                    Toast.makeText(this, "已删除「${friend.name}」", Toast.LENGTH_SHORT).show()
                    finish()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // ===== 创建时间 =====
        val createDate = SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.CHINESE)
            .format(Date(friend.createdAt))
        val footerText = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(20) }
            gravity = Gravity.CENTER
            this.text = "相识于 $createDate"
            textSize = 11f
            setTextColor(c.timeText)
        }
        detailContainer.addView(footerText)
    }

    // ===== 计算续火花 =====
    private fun calculateStreak(friendId: String): Int {
        val messages = chatStorage.loadMessages(friendId)
        if (messages.isEmpty()) return 0

        val chatDays = messages.map { msg ->
            val cal = Calendar.getInstance().apply { timeInMillis = msg.timestamp }
            "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.DAY_OF_YEAR)}"
        }.distinct().sortedDescending()

        if (chatDays.isEmpty()) return 0

        val today = Calendar.getInstance()
        val todayKey = "${today.get(Calendar.YEAR)}-${today.get(Calendar.DAY_OF_YEAR)}"
        val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        val yesterdayKey = "${yesterday.get(Calendar.YEAR)}-${yesterday.get(Calendar.DAY_OF_YEAR)}"

        val startDay = if (chatDays.contains(todayKey)) {
            today.clone() as Calendar
        } else if (chatDays.contains(yesterdayKey)) {
            yesterday.clone() as Calendar
        } else {
            return 0
        }

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

    // ===== 编辑头像字符 =====
    private fun showEditIconDialog(friend: Friend) {
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }

        val icons = arrayOf("★", "♡", "☆", "♪", "✦", "◆", "○", "△", "☀", "☁", "🌙", "🔥", "🌸", "🍀", "⚡", "🎵")

        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        // 每行4个
        for (row in icons.indices step 4) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            }

            for (col in 0 until 4) {
                val idx = row + col
                if (idx >= icons.size) break

                val iconBtn = TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                        marginStart = dp(6); marginEnd = dp(6)
                    }
                    gravity = Gravity.CENTER
                    this.text = icons[idx]
                    textSize = 20f
                    setTextColor(if (icons[idx] == friend.icon) c.highlightColor else c.textSecondary)
                    setBackgroundResource(R.drawable.icon_bg)
                }
                rowLayout.addView(iconBtn)
            }
            grid.addView(rowLayout)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("选择头像")
            .setView(grid)
            .setNegativeButton("取消", null)
            .create()

        // 给每个图标按钮加点击事件
        fun setupClicks(layout: LinearLayout) {
            for (i in 0 until layout.childCount) {
                val rowLayout = layout.getChildAt(i) as? LinearLayout ?: continue
                for (j in 0 until rowLayout.childCount) {
                    val btn = rowLayout.getChildAt(j) as? TextView ?: continue
                    btn.setOnClickListener {
                        val icon = btn.text.toString()
                        friendStorage.updateFriend(friend.copy(icon = icon))
                        Toast.makeText(this, "头像已更换 ♡", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                        buildDetail()
                    }
                }
            }
        }
        setupClicks(grid)
        dialog.show()
    }

    // ===== 通用编辑对话框 =====
    private fun showEditDialog(title: String, currentValue: String, multiLine: Boolean = false, onSave: (String) -> Unit) {
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }

        val input = EditText(this).apply {
            setText(currentValue)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            textSize = 14f
            if (multiLine) {
                minLines = 3
                gravity = Gravity.TOP
            }
        }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val value = input.text.toString().trim()
                if (value.isNotEmpty()) {
                    onSave(value)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ===== 分区标题 =====
    private fun addSection(title: String) {
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }
        val tv = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16); bottomMargin = dp(8) }
            this.text = title
            textSize = 12f
            setTextColor(c.accent)
            setPadding(dp(4), 0, 0, 0)
            letterSpacing = 0.1f
        }
        detailContainer.addView(tv)
    }

    // ===== 可编辑项 =====
    private fun addEditableItem(label: String, value: String, desc: String, onClick: () -> Unit) {
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.chat_card_bg)
            setPadding(dp(14), dp(11), dp(14), dp(11))
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

        topRow.addView(TextView(this).apply {
            this.text = label
            textSize = 13f
            setTextColor(c.textSecondary)
            layoutParams = LinearLayout.LayoutParams(dp(60), LinearLayout.LayoutParams.WRAP_CONTENT)
        })

        topRow.addView(TextView(this).apply {
            this.text = value
            textSize = 13f
            setTextColor(c.textPrimary)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            maxLines = 2
        })

        topRow.addView(TextView(this).apply {
            this.text = "›"
            textSize = 16f
            setTextColor(c.timeText)
        })

        card.addView(topRow)

        val tvDesc = TextView(this).apply {
            this.text = desc
            textSize = 10f
            setTextColor(c.textHint)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(3) }
        }
        card.addView(tvDesc)

        detailContainer.addView(card)
    }

    // ===== 信息展示项（只读） =====
    private fun addInfoItem(label: String, value: String) {
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }

        row.addView(TextView(this).apply {
            this.text = label
            textSize = 13f
            setTextColor(c.textSecondary)
            layoutParams = LinearLayout.LayoutParams(dp(60), LinearLayout.LayoutParams.WRAP_CONTENT)
        })

        row.addView(TextView(this).apply {
            this.text = value
            textSize = 13f
            setTextColor(c.textOnAccent)
        })

        detailContainer.addView(row)
    }

    // ===== 操作按钮 =====
    private fun addActionItem(title: String, desc: String, onClick: () -> Unit) {
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

        card.addView(TextView(this).apply {
            this.text = title
            textSize = 14f
            setTextColor(c.textPrimary)
        })

        card.addView(TextView(this).apply {
            this.text = desc
            textSize = 10f
            setTextColor(c.textHint)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(3) }
        })

        detailContainer.addView(card)
    }
}