package com.haven.guiqi

import android.app.AlertDialog
import android.content.Intent
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * FriendTabManager — 好友标签页
 *
 * 从 ChatActivity 拆出来。
 * 管好友列表渲染、添加好友、编辑名称/分组/API、删除好友。
 */
class FriendTabManager(
    private val activity: AppCompatActivity,
    private val friendsList: LinearLayout,
    private val friendStorage: FriendStorage,
    private val chatStorage: ChatStorage,
    private val onRefresh: () -> Unit   // 刷新来信列表等联动
) {
    private val c get() = ThemeHelper.getColors(activity)
    private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()

    // ===== 刷新好友列表 =====
    fun refresh() {
        friendsList.removeAllViews()
        val friends = friendStorage.loadFriends()

        val groups = friends.groupBy { it.group }
        for ((groupName, groupFriends) in groups) {
            val groupTitle = TextView(activity).apply {
                text = "- $groupName -"
                textSize = 10f
                setTextColor(c.dateLabel)
                setPadding(dp(4), dp(8), dp(4), dp(8))
                letterSpacing = 0.2f
            }
            friendsList.addView(groupTitle)

            for (f in groupFriends) {
                addFriendCard(f)
            }
        }

        if (friends.isEmpty()) {
            val hint = TextView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(80) }
                gravity = Gravity.CENTER
                text = "点下面的按钮添加第一个好友吧~"
                textSize = 13f
                setTextColor(c.dateLabel)
            }
            friendsList.addView(hint)
        }

        val addBtn = TextView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) }
            gravity = Gravity.CENTER
            text = "＋ 添加新好友"
            textSize = 11f
            setTextColor(c.accent)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = activity.getDrawable(R.drawable.chat_card_bg)
        }
        addBtn.setOnClickListener { showAddFriendDialog() }
        friendsList.addView(addBtn)
    }

    // ===== 好友卡片 =====
    private fun addFriendCard(friend: Friend) {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = activity.getDrawable(R.drawable.chat_card_bg)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }

        val avatar = TextView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply {
                marginEnd = dp(12)
            }
            gravity = Gravity.CENTER
            text = friend.icon
            textSize = 16f
            setTextColor(c.accentStrong)
            setBackgroundResource(R.drawable.icon_bg)
        }

        val infoLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }

        val tvName = TextView(activity).apply {
            text = friend.name
            textSize = 14f
            setTextColor(c.textPrimary)
        }

        val detailRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        }

        val tvGroup = TextView(activity).apply {
            text = friend.group
            textSize = 9f
            setTextColor(c.accent)
            setPadding(dp(8), dp(2), dp(8), dp(2))
            background = activity.getDrawable(R.drawable.chat_card_bg)
        }

        val tvCode = TextView(activity).apply {
            text = friend.id
            textSize = 9f
            setTextColor(c.dateLabel)
            setPadding(dp(8), 0, 0, 0)
        }

        detailRow.addView(tvGroup)
        detailRow.addView(tvCode)

        val streak = calculateStreak(friend.id)
        if (streak > 0) {
            val tvStreak = TextView(activity).apply {
                text = "🔥$streak"
                textSize = 9f
                setTextColor(c.warning)
                setPadding(dp(8), 0, 0, 0)
            }
            detailRow.addView(tvStreak)
        }

        infoLayout.addView(tvName)
        infoLayout.addView(detailRow)

        val arrow = TextView(activity).apply {
            text = "›"
            textSize = 18f
            setTextColor(c.timeText)
            setPadding(dp(8), dp(8), dp(4), dp(8))
            setOnClickListener {
                val intent = Intent(activity, FriendDetailActivity::class.java)
                intent.putExtra("friend_id", friend.id)
                activity.startActivity(intent)
            }
        }

        card.addView(avatar)
        card.addView(infoLayout)
        card.addView(arrow)

        card.setOnClickListener {
            val intent = Intent(activity, ChatConversationActivity::class.java)
            intent.putExtra("friend_id", friend.id)
            intent.putExtra("friend_name", friend.name)
            intent.putExtra("friend_icon", friend.icon)
            activity.startActivity(intent)
        }

        card.setOnLongClickListener {
            showFriendOptions(friend)
            true
        }

        friendsList.addView(card)
    }

    // ===== 添加好友 =====
    fun showAddFriendDialog() {
        val input = EditText(activity).apply {
            hint = "输入好友名称"
            setPadding(dp(16), dp(12), dp(16), dp(12))
            textSize = 15f
        }

        AlertDialog.Builder(activity)
            .setTitle("添加新好友")
            .setView(input)
            .setPositiveButton("添加") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    val friend = friendStorage.addFriend(name)
                    Toast.makeText(activity, "已添加「${friend.name}」\n编码: ${friend.id}", Toast.LENGTH_SHORT).show()
                    refresh()
                    onRefresh()
                } else {
                    Toast.makeText(activity, "名称不能为空", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ===== 好友选项菜单 =====
    private fun showFriendOptions(friend: Friend) {
        val options = arrayOf("编辑名称", "修改分组", "配置 API", "删除好友")
        AlertDialog.Builder(activity)
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

    private fun showEditNameDialog(friend: Friend) {
        val input = EditText(activity).apply {
            setText(friend.name)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            textSize = 15f
        }

        AlertDialog.Builder(activity)
            .setTitle("编辑名称")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    friendStorage.updateFriend(friend.copy(name = newName))
                    refresh()
                    onRefresh()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showEditGroupDialog(friend: Friend) {
        val input = EditText(activity).apply {
            setText(friend.group)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            textSize = 15f
        }

        AlertDialog.Builder(activity)
            .setTitle("修改分组")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val newGroup = input.text.toString().trim()
                if (newGroup.isNotEmpty()) {
                    friendStorage.updateFriend(friend.copy(group = newGroup))
                    refresh()
                    onRefresh()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showApiConfigDialog(friend: Friend) {
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }

        var selectedType = friend.apiType
        val typeNames = arrayOf("OpenAI 格式（GPT/DeepSeek/中转站）", "Claude 原生", "Gemini 原生")
        val typeValues = arrayOf("openai", "claude", "gemini")
        val currentTypeIndex = typeValues.indexOf(selectedType).coerceAtLeast(0)

        val typeBtn = TextView(activity).apply {
            text = "API 类型: ${typeNames[currentTypeIndex]}"
            textSize = 14f
            setPadding(0, 0, 0, dp(12))
        }
        typeBtn.setOnClickListener {
            AlertDialog.Builder(activity)
                .setTitle("选择 API 类型")
                .setItems(typeNames) { _, which ->
                    selectedType = typeValues[which]
                    typeBtn.text = "API 类型: ${typeNames[which]}"
                }
                .show()
        }
        layout.addView(typeBtn)

        val hint = TextView(activity).apply {
            text = "留空则使用全局配置（设置页的配置）"
            textSize = 11f
            setTextColor(c.textSecondary)
            setPadding(0, 0, 0, dp(12))
        }
        layout.addView(hint)

        val inputUrl = EditText(activity).apply {
            this.hint = "API 地址"
            setText(friend.apiUrl)
            textSize = 14f
        }
        layout.addView(inputUrl)

        val inputKey = EditText(activity).apply {
            this.hint = "API 密钥"
            setText(friend.apiKey)
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(inputKey)

        val inputModel = EditText(activity).apply {
            this.hint = "模型名称"
            setText(friend.apiModel)
            textSize = 14f
        }
        layout.addView(inputModel)

        AlertDialog.Builder(activity)
            .setTitle("${friend.name} 的 API 配置")
            .setView(layout)
            .setPositiveButton("保存") { _, _ ->
                friendStorage.updateFriend(friend.copy(
                    apiUrl = inputUrl.text.toString().trim(),
                    apiKey = inputKey.text.toString().trim(),
                    apiModel = inputModel.text.toString().trim(),
                    apiType = selectedType
                ))
                Toast.makeText(activity, "API 配置已保存 ♡", Toast.LENGTH_SHORT).show()
                refresh()
            }
            .setNeutralButton("清除配置") { _, _ ->
                friendStorage.updateFriend(friend.copy(
                    apiUrl = "", apiKey = "", apiModel = "", apiType = "openai"
                ))
                Toast.makeText(activity, "已清除，将使用全局配置", Toast.LENGTH_SHORT).show()
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showDeleteConfirm(friend: Friend) {
        AlertDialog.Builder(activity)
            .setTitle("删除好友")
            .setMessage("确定要删除「${friend.name}」吗？\n聊天记录也会一起删除，无法恢复。")
            .setPositiveButton("删除") { _, _ ->
                friendStorage.deleteFriend(friend.id)
                Toast.makeText(activity, "已删除「${friend.name}」", Toast.LENGTH_SHORT).show()
                refresh()
                onRefresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ===== 续火花（来信Tab也需要用） =====
    fun calculateStreak(friendId: String): Int {
        val messages = chatStorage.loadMessages(friendId)
        if (messages.isEmpty()) return 0

        val chatDays = messages.map { msg ->
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = msg.timestamp }
            "${cal.get(java.util.Calendar.YEAR)}-${cal.get(java.util.Calendar.DAY_OF_YEAR)}"
        }.distinct().sortedDescending()

        if (chatDays.isEmpty()) return 0

        val today = java.util.Calendar.getInstance()
        val todayKey = "${today.get(java.util.Calendar.YEAR)}-${today.get(java.util.Calendar.DAY_OF_YEAR)}"
        val yesterday = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, -1) }
        val yesterdayKey = "${yesterday.get(java.util.Calendar.YEAR)}-${yesterday.get(java.util.Calendar.DAY_OF_YEAR)}"

        val startDay = if (chatDays.contains(todayKey)) {
            today.clone() as java.util.Calendar
        } else if (chatDays.contains(yesterdayKey)) {
            yesterday.clone() as java.util.Calendar
        } else {
            return 0
        }

        var streak = 0
        val checkDay = startDay.clone() as java.util.Calendar
        while (true) {
            val dayKey = "${checkDay.get(java.util.Calendar.YEAR)}-${checkDay.get(java.util.Calendar.DAY_OF_YEAR)}"
            if (chatDays.contains(dayKey)) {
                streak++
                checkDay.add(java.util.Calendar.DAY_OF_YEAR, -1)
            } else {
                break
            }
        }
        return streak
    }
}