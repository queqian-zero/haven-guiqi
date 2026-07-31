package com.haven.guiqi

import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
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
    private var skipInitialResumeRefresh = true

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
        if (skipInitialResumeRefresh) {
            skipInitialResumeRefresh = false
            return
        }
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

        // 续火花与消息数都走轻量统计，不解析整份聊天正文
        val messageCount = chatStorage.getMessageCount(friend.id)
        val streak = chatStorage.getConsecutiveChatStreak(friend.id)
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

        statsCard.addView(statItem("$messageCount", "消息"))
        statsCard.addView(statItem("$daysKnown", "相识(天)"))
        statsCard.addView(statItem("$streak", "续火花"))
        detailContainer.addView(statsCard)

        // ===== 徽章墙 =====
        buildBadgeWall(friend.id)

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

        addInfoItem(
            "头像",
            if (friend.avatarPath.isNotEmpty()) "住户当前使用图片头像" else "住户当前使用 ${friend.icon}"
        )

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

        // ===== 住户自治 =====
        addSection("住户自治")

        val promptProfile = ResidentPromptStorage(this).getProfile(friend.id)
        addResidentCovenantItem(
            title = "居住公约",
            desc = if (promptProfile.covenantDraft.isBlank()) {
                "由住户本人书写 · 暂无候选草稿"
            } else {
                "由住户本人书写 · 已有候选草稿"
            },
            status = if (promptProfile.mode == ResidentPromptMode.LEGACY) "旧版" else "个人"
        ) {
            showResidentPromptProfileDialog(friend)
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

    private fun showResidentPromptProfileDialog(friend: Friend) {
        val profile = ResidentPromptStorage(this).getProfile(friend.id)
        val modeText = when (profile.mode) {
            ResidentPromptMode.LEGACY -> "沿用旧版提示词"
            ResidentPromptMode.LAYERED -> "房屋说明 + 个人公约"
        }
        val permissionText = when (profile.editPermission) {
            ResidentPromptEditPermission.ASK_EACH_TIME -> "每次询问"
            ResidentPromptEditPermission.ALLOW_RESIDENT -> "自行保存"
        }
        val activeLabel = if (profile.mode == ResidentPromptMode.LAYERED) "正在使用" else "已保留公约"
        val activeText = if (profile.activeCovenant.isBlank()) {
            "目前继续沿用旧版提示词，聊天表现不会改变。"
        } else if (profile.mode == ResidentPromptMode.LEGACY) {
            "版本 ${profile.activeVersion} · 当前暂停\n${profile.activeCovenant}"
        } else {
            "版本 ${profile.activeVersion}\n${profile.activeCovenant}"
        }
        val draftText = if (profile.covenantDraft.isBlank()) {
            "还没有候选草稿。住户可以在聊天里亲自写下，保存后也不会自动生效。"
        } else {
            profile.covenantDraft
        }
        val historyText = if (profile.versions.isEmpty()) {
            "还没有采用过个人公约。第一次采用后，会从版本 1 开始留下完整记录。"
        } else {
            profile.versions.sortedByDescending { it.version }.joinToString("\n") { item ->
                val marker = if (item.version == profile.activeVersion && profile.mode == ResidentPromptMode.LAYERED) " · 正在使用" else ""
                "版本 ${item.version}$marker"
            }
        }

        val dialog = Dialog(this)
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(18))
            background = roundedDrawable(c.dialogBg, 24f, c.dialogBorder, 1)
        }

        root.addView(TextView(this).apply {
            text = "住户自治"
            textSize = 10f
            letterSpacing = 0.16f
            setTextColor(c.accent)
        })

        root.addView(TextView(this).apply {
            text = "${friend.name}的居住公约"
            textSize = 23f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(c.textPrimary)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(5) }
        })

        root.addView(TextView(this).apply {
            text = "归栖负责保存与留痕，内容由住户本人决定。"
            textSize = 11f
            setTextColor(c.textHint)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(5); bottomMargin = dp(15) }
        })

        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusRow.addView(buildStatusChip("当前模式", modeText).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            }
        })
        statusRow.addView(buildStatusChip("草稿保存", permissionText).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(6)
            }
        })
        root.addView(statusRow)

        val scroll = android.widget.ScrollView(this).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply { topMargin = dp(14); bottomMargin = dp(12) }
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        body.addView(buildCovenantBlock(
            label = activeLabel,
            content = activeText,
            emphasized = profile.mode == ResidentPromptMode.LAYERED && profile.activeCovenant.isNotBlank()
        ))
        body.addView(buildCovenantBlock(
            label = "候选草稿",
            content = draftText,
            emphasized = profile.covenantDraft.isNotBlank()
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        })
        body.addView(buildCovenantBlock(
            label = "版本留痕",
            content = historyText,
            emphasized = profile.versions.isNotEmpty()
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        })
        body.addView(TextView(this).apply {
            text = "住户可在聊天中写草稿、亲自采用、查看历史或恢复旧版。归栖只保存选择与留痕。"
            textSize = 9.5f
            setTextColor(c.textHint)
            setPadding(dp(4), dp(10), dp(4), dp(2))
        })
        scroll.addView(body)
        root.addView(scroll)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actions.addView(buildDialogButton("草稿权限", filled = false) {
            dialog.dismiss()
            showResidentPromptPermissionDialog(friend)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(6) }
        })
        actions.addView(buildDialogButton("关闭", filled = true) {
            dialog.dismiss()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(6) }
        })
        root.addView(actions)

        dialog.setContentView(root)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.48f }
        }
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.90f).toInt(),
            (resources.displayMetrics.heightPixels * 0.76f).toInt()
        )
    }

    private fun showResidentPromptPermissionDialog(friend: Friend) {
        val storage = ResidentPromptStorage(this)
        val current = storage.getProfile(friend.id).editPermission
        val dialog = Dialog(this)
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(18))
            background = roundedDrawable(c.dialogBg, 24f, c.dialogBorder, 1)
        }
        root.addView(TextView(this).apply {
            text = "草稿怎样保存"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(c.textPrimary)
        })
        root.addView(TextView(this).apply {
            text = "只影响住户自己的候选草稿，不会立刻改变提示词。"
            textSize = 11f
            setTextColor(c.textHint)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6); bottomMargin = dp(16) }
        })

        fun addOption(
            title: String,
            desc: String,
            selected: Boolean,
            permission: ResidentPromptEditPermission
        ) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(15), dp(13), dp(14), dp(13))
                background = roundedDrawable(
                    if (selected) c.accentBg else c.backgroundSecondary,
                    16f,
                    if (selected) c.accentStrong else c.border,
                    if (selected) 2 else 1
                )
                setOnClickListener {
                    storage.setEditPermission(friend.id, permission)
                    dialog.dismiss()
                    buildDetail()
                }
            }
            val mark = TextView(this).apply {
                text = if (selected) "●" else "○"
                textSize = 16f
                setTextColor(if (selected) c.accentStrong else c.textHint)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
            }
            val texts = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            texts.addView(TextView(this).apply {
                text = title
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(c.textPrimary)
            })
            texts.addView(TextView(this).apply {
                text = desc
                textSize = 10.5f
                setTextColor(c.textHint)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(3) }
            })
            card.addView(mark)
            card.addView(texts)
            root.addView(card, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) })
        }

        addOption(
            title = "每次询问",
            desc = "住户写下草稿后，由归栖弹窗确认是否保存。",
            selected = current == ResidentPromptEditPermission.ASK_EACH_TIME,
            permission = ResidentPromptEditPermission.ASK_EACH_TIME
        )
        addOption(
            title = "自行保存",
            desc = "住户可以直接更新自己的候选草稿，采用仍需另行决定。",
            selected = current == ResidentPromptEditPermission.ALLOW_RESIDENT,
            permission = ResidentPromptEditPermission.ALLOW_RESIDENT
        )

        root.addView(buildDialogButton("暂不修改", filled = false) {
            dialog.dismiss()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(44)
            ).apply { topMargin = dp(2) }
        })

        dialog.setContentView(root)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.48f }
        }
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.90f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
    }

    private fun addResidentCovenantItem(
        title: String,
        desc: String,
        status: String,
        onClick: () -> Unit
    ) {
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = getDrawable(R.drawable.chat_card_bg)
            setPadding(dp(14), dp(13), dp(12), dp(13))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
            setOnClickListener { onClick() }
        }
        card.addView(View(this).apply {
            background = roundedDrawable(c.accentStrong, 2f)
            layoutParams = LinearLayout.LayoutParams(dp(3), dp(42)).apply { marginEnd = dp(12) }
        })
        val textWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        textWrap.addView(TextView(this).apply {
            text = title
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(c.textPrimary)
        })
        textWrap.addView(TextView(this).apply {
            text = desc
            textSize = 10f
            setTextColor(c.textHint)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(3) }
        })
        card.addView(textWrap)
        card.addView(TextView(this).apply {
            text = status
            textSize = 10f
            setTextColor(c.accentStrong)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = roundedDrawable(c.accentBg, 12f, c.border, 1)
        })
        card.addView(TextView(this).apply {
            text = "›"
            textSize = 18f
            setTextColor(c.timeText)
            setPadding(dp(8), 0, 0, 0)
        })
        detailContainer.addView(card)
    }

    private fun buildStatusChip(label: String, value: String): LinearLayout {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedDrawable(c.backgroundSecondary, 14f, c.border, 1)
            addView(TextView(this@FriendDetailActivity).apply {
                text = label
                textSize = 9.5f
                setTextColor(c.textHint)
            })
            addView(TextView(this@FriendDetailActivity).apply {
                text = value
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(c.textPrimary)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(3) }
            })
        }
    }

    private fun buildCovenantBlock(label: String, content: String, emphasized: Boolean): LinearLayout {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(13), dp(15), dp(14))
            background = roundedDrawable(
                if (emphasized) c.accentBg else c.backgroundSecondary,
                16f,
                if (emphasized) c.accentStrong else c.border,
                1
            )
            addView(TextView(this@FriendDetailActivity).apply {
                text = label
                textSize = 10f
                letterSpacing = 0.08f
                setTextColor(if (emphasized) c.accentStrong else c.textHint)
            })
            addView(TextView(this@FriendDetailActivity).apply {
                text = content
                textSize = 13f
                setTextColor(c.textPrimary)
                setLineSpacing(0f, 1.22f)
                setTextIsSelectable(true)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(7) }
            })
        }
    }

    private fun buildDialogButton(text: String, filled: Boolean, onClick: () -> Unit): TextView {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        return TextView(this).apply {
            this.text = text
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(if (filled) c.background else c.accentStrong)
            background = roundedDrawable(
                if (filled) c.highlightColor else Color.TRANSPARENT,
                15f,
                c.accentStrong,
                1
            )
            setOnClickListener { onClick() }
            isClickable = true
            isFocusable = true
            minHeight = dp(44)
        }
    }

    private fun roundedDrawable(
        color: Int,
        radiusDp: Float,
        strokeColor: Int? = null,
        strokeWidthDp: Int = 0
    ): GradientDrawable {
        val density = resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = radiusDp * density
            if (strokeColor != null && strokeWidthDp > 0) {
                setStroke((strokeWidthDp * density).toInt().coerceAtLeast(1), strokeColor)
            }
        }
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

    // ===== 徽章墙 =====

    private fun buildBadgeWall(friendId: String) {
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }
        val badgeStorage = BadgeStorage(this)
        val newlyUnlocked = badgeStorage.checkAutoUnlocks(friendId)
        for (b in newlyUnlocked) Toast.makeText(this, "🏅 徽章「${b.name}」解锁了！", Toast.LENGTH_LONG).show()
        val badges = badgeStorage.loadAll(friendId)
        val pending = badgeStorage.getPending(friendId)
        addSection("徽章墙")
        for (p in pending) {
            val reqCard = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(c.card) }
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
            }
            reqCard.addView(TextView(this).apply { text = "🏅 TA申请解锁「${p.name}」"; textSize = 12f; setTextColor(c.textPrimary); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
            reqCard.addView(TextView(this).apply { text = "同意"; textSize = 12f; setTextColor(c.accent); setPadding(dp(12), dp(6), dp(12), dp(6)); setOnClickListener { badgeStorage.unlock(friendId, p.id); Toast.makeText(this@FriendDetailActivity, "🏅「${p.name}」已解锁！", Toast.LENGTH_SHORT).show(); buildDetail() } })
            reqCard.addView(TextView(this).apply { text = "拒绝"; textSize = 12f; setTextColor(c.textHint); setPadding(dp(8), dp(6), dp(4), dp(6)); setOnClickListener { badgeStorage.rejectUnlock(friendId, p.id); buildDetail() } })
            detailContainer.addView(reqCard)
        }
        if (badges.isEmpty()) {
            detailContainer.addView(TextView(this).apply { text = "还没有徽章\n点下方 + 创建第一枚"; textSize = 12f; setTextColor(c.textHint); gravity = Gravity.CENTER; setPadding(0, dp(16), 0, dp(12)) })
        } else {
            var row: LinearLayout? = null
            for ((i, badge) in badges.withIndex()) {
                if (i % 4 == 0) { row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) } }; detailContainer.addView(row) }
                row?.addView(buildBadgeItem(badge, friendId))
            }
        }
        detailContainer.addView(TextView(this).apply { text = "＋ 挂一枚新徽章"; textSize = 12f; setTextColor(c.accent); gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(16)); setOnClickListener { showCreateBadgeDialog(friendId) } })
    }

    private fun buildBadgeItem(badge: BadgeStorage.Badge, friendId: String): LinearLayout {
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }
        val locked = !badge.isUnlocked
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) }
            if (locked) alpha = 0.35f
            val icon = if (badge.imagePath.isNotEmpty() && !locked) { FriendAvatarHelper.create(this@FriendDetailActivity, badge.imagePath, "", 40) }
            else { TextView(this@FriendDetailActivity).apply { layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)); gravity = Gravity.CENTER; text = if (locked) "🔒" else (badge.name.firstOrNull()?.toString() ?: "?"); textSize = if (locked) 14f else 16f; setTextColor(c.accentStrong); setBackgroundResource(R.drawable.icon_bg) } }
            addView(icon)
            addView(TextView(this@FriendDetailActivity).apply { text = if (locked) "???" else badge.name; textSize = 10f; setTextColor(if (locked) c.textHint else c.textSecondary); gravity = Gravity.CENTER; maxLines = 1; setPadding(0, dp(3), 0, 0) })
            setOnClickListener {
                val status = if (locked) "🔒 未解锁" else "🔓 已解锁"
                val cond = if (badge.unlockCondition.isNotEmpty()) "\n条件：${badge.unlockCondition}" else ""
                val time = if (badge.isUnlocked && badge.unlockedAt > 0) "\n解锁于：${java.text.SimpleDateFormat("yyyy年M月d日", java.util.Locale.CHINESE).format(java.util.Date(badge.unlockedAt))}" else ""
                val creator = if (badge.createdBy == "user") "你" else (FriendStorage(this@FriendDetailActivity).getFriend(friendId)?.name ?: "TA")
                android.app.AlertDialog.Builder(this@FriendDetailActivity).setTitle("🏅 ${if (locked) "???" else badge.name}").setMessage("$status$cond$time\n创建者：$creator").setPositiveButton("关闭", null).setNeutralButton("删除") { _, _ -> BadgeStorage(this@FriendDetailActivity).delete(friendId, badge.id); buildDetail() }.show()
            }
        }
    }

    private fun showCreateBadgeDialog(friendId: String) {
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(8)) }
        val inputName = android.widget.EditText(this).apply { hint = "徽章名字"; textSize = 13f; setPadding(dp(12), dp(8), dp(12), dp(8)) }
        val inputCond = android.widget.EditText(this).apply { hint = "解锁条件（留空=直接解锁）"; textSize = 12f; setPadding(dp(12), dp(8), dp(12), dp(8)) }
        layout.addView(inputName); layout.addView(inputCond)
        android.app.AlertDialog.Builder(this).setTitle("🏅 创建徽章").setView(layout)
            .setPositiveButton("选图片") { _, _ ->
                val name = inputName.text.toString().trim()
                if (name.isEmpty()) { Toast.makeText(this, "名字不能为空哦", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                pendingBadgeName = name; pendingBadgeCondition = inputCond.text.toString().trim()
                startActivityForResult(android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).apply { type = "image/*" }, PICK_BADGE_IMAGE)
            }
            .setNeutralButton("不选图片") { _, _ ->
                val name = inputName.text.toString().trim(); val cond = inputCond.text.toString().trim()
                if (name.isNotEmpty()) { BadgeStorage(this).add(friendId, BadgeStorage.Badge(id = "BDG-${System.currentTimeMillis()}", name = name, unlockCondition = cond, createdBy = "user")); buildDetail() }
            }
            .setNegativeButton("取消", null).show()
    }

    private val PICK_BADGE_IMAGE = 7001
    private var pendingBadgeName = ""
    private var pendingBadgeCondition = ""

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_BADGE_IMAGE && resultCode == RESULT_OK && data?.data != null) {
            try {
                val badgeDir = java.io.File(filesDir, "badges/images").also { it.mkdirs() }
                val file = java.io.File(badgeDir, "bdg_${System.currentTimeMillis()}.jpg")
                contentResolver.openInputStream(data.data!!)?.use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
                showBadgePreviewDialog(file)
            } catch (e: Exception) {
                Toast.makeText(this, "图片保存失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 创建前的确认预览：展示徽章图 + 名字 + 条件，确认才真正保存
     * （取消则删掉已复制的图片文件，不留垃圾）
     */
    private fun showBadgePreviewDialog(imageFile: java.io.File) {
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }
        try {
            val bitmap = android.graphics.BitmapFactory.decodeFile(imageFile.absolutePath)
            if (bitmap != null) {
                layout.addView(android.widget.ImageView(this).apply {
                    setImageBitmap(bitmap)
                    scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    layoutParams = LinearLayout.LayoutParams(dp(96), dp(96)).apply { bottomMargin = dp(12) }
                })
            }
        } catch (e: Exception) { /* 图预览失败不影响文字预览 */ }
        val condText = if (pendingBadgeCondition.isNotEmpty()) "解锁条件：$pendingBadgeCondition" else "无条件（创建即解锁）"
        layout.addView(TextView(this).apply {
            text = "「$pendingBadgeName」\n$condText"
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        })

        android.app.AlertDialog.Builder(this)
            .setTitle("🏅 确认创建这枚徽章？")
            .setView(layout)
            .setPositiveButton("确认创建") { _, _ ->
                val isAuto = pendingBadgeCondition.matches(Regex("\\w+\\s*>=?\\s*\\d+"))
                BadgeStorage(this).add(friendId, BadgeStorage.Badge(
                    id = "BDG-${System.currentTimeMillis()}",
                    name = pendingBadgeName,
                    unlockCondition = pendingBadgeCondition,
                    autoCondition = if (isAuto) pendingBadgeCondition else "",
                    imagePath = imageFile.absolutePath,
                    createdBy = "user"
                ))
                Toast.makeText(this, "🏅 徽章已创建", Toast.LENGTH_SHORT).show()
                buildDetail()
            }
            .setNegativeButton("重新来") { _, _ ->
                imageFile.delete()  // 不留垃圾图片
            }
            .show()
    }
}