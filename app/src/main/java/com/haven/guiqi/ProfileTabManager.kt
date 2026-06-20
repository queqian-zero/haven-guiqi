package com.haven.guiqi

import android.app.AlertDialog
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * ProfileTabManager — "我的"标签页
 *
 * 从 ChatActivity 拆出来。
 * 管个人资料、统计、用户名编辑、自我描述、导出导入入口。
 */
class ProfileTabManager(
    private val activity: AppCompatActivity,
    private val profileContainer: LinearLayout,
    private val friendStorage: FriendStorage,
    private val chatStorage: ChatStorage,
    private val onExport: () -> Unit,
    private val onImport: () -> Unit
) {
    private val c get() = ThemeHelper.getColors(activity)
    private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()

    fun refresh() {
        profileContainer.removeAllViews()
        val prefs = activity.getSharedPreferences("haven_prefs", AppCompatActivity.MODE_PRIVATE)
        val userName = prefs.getString("user_name", "") ?: ""

        // ===== 头像区 =====
        val avatarSection = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, dp(20))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val userAvatar = prefs.getString("user_avatar", "") ?: ""

        val avatarCircle = TextView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(64), dp(64))
            gravity = Gravity.CENTER
            text = if (userAvatar.isNotEmpty()) userAvatar
                   else if (userName.isNotEmpty()) userName.first().toString()
                   else "?"
            textSize = 26f
            setTextColor(c.accentStrong)
            setBackgroundResource(R.drawable.icon_bg)
            setOnClickListener { showAvatarDialog() }
        }

        val nameText = TextView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
            text = if (userName.isNotEmpty()) userName else "点击设置名字"
            textSize = 18f
            setTextColor(if (userName.isNotEmpty()) c.textPrimary else c.tipText)
        }

        val subtitle = TextView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
            text = "这里是你的归栖"
            textSize = 11f
            setTextColor(c.dateLabel)
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

        val statsCard = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            background = activity.getDrawable(R.drawable.chat_card_bg)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }
        }

        fun statItem(number: String, label: String): LinearLayout {
            return LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }.also { layout ->
                layout.addView(TextView(activity).apply {
                    text = number; textSize = 20f; setTextColor(c.textPrimary); gravity = Gravity.CENTER
                })
                layout.addView(TextView(activity).apply {
                    text = label; textSize = 10f; setTextColor(c.tipText); gravity = Gravity.CENTER
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
        addSectionTitle("个人设置")

        addItem("用户名称", if (userName.isNotEmpty()) userName else "未设置",
            "AI 能看到的你的名字") { showEditUserNameDialog() }

        val userBioPrefs = activity.getSharedPreferences("haven_user", AppCompatActivity.MODE_PRIVATE)
        val myBio = userBioPrefs.getString("my_bio", "") ?: ""
        addItem("我眼中的自己",
            if (myBio.isNotEmpty()) myBio.take(20) + (if (myBio.length > 20) "..." else "") else "还没有写",
            "你的自我描述，AI 好奇时可以翻看（不会主动塞给 AI）") { showEditMyBioDialog() }

        addSectionTitle("数据管理")
        addItem("导出数据", "", "把好友和聊天记录导出备份") { onExport() }
        addItem("导入数据", "", "从备份文件恢复好友和聊天记录") { onImport() }

        addSectionTitle("关于")
        addItem("归栖 Haven", "v0.1.0", "一个属于你的地方") { }
    }

    private fun showEditUserNameDialog() {
        val prefs = activity.getSharedPreferences("haven_prefs", AppCompatActivity.MODE_PRIVATE)
        val currentName = prefs.getString("user_name", "") ?: ""

        val input = EditText(activity).apply {
            setText(currentName)
            hint = "你希望 AI 怎么称呼你"
            setPadding(dp(16), dp(12), dp(16), dp(12))
            textSize = 15f
        }

        AlertDialog.Builder(activity)
            .setTitle("设置名字")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val name = input.text.toString().trim()
                prefs.edit().putString("user_name", name).apply()
                refresh()
                if (name.isNotEmpty()) {
                    Toast.makeText(activity, "以后 AI 就叫你「$name」了 ♡", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showAvatarDialog() {
        val prefs = activity.getSharedPreferences("haven_prefs", AppCompatActivity.MODE_PRIVATE)
        val input = EditText(activity).apply {
            hint = "输入一个emoji作为头像"
            setText(prefs.getString("user_avatar", ""))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            textSize = 24f
            gravity = android.view.Gravity.CENTER
        }

        AlertDialog.Builder(activity)
            .setTitle("换个头像")
            .setView(input)
            .setPositiveButton("换") { _, _ ->
                val avatar = input.text.toString().trim()
                prefs.edit().putString("user_avatar", avatar).apply()
                refresh()
            }
            .setNeutralButton("恢复默认") { _, _ ->
                prefs.edit().remove("user_avatar").apply()
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showEditMyBioDialog() {
        val prefs = activity.getSharedPreferences("haven_user", AppCompatActivity.MODE_PRIVATE)
        val currentBio = prefs.getString("my_bio", "") ?: ""

        val input = EditText(activity).apply {
            setText(currentBio)
            hint = "写写你眼中的自己吧...\n\nAI 不会每次都看到这些，只有它好奇的时候才会主动翻看"
            setPadding(dp(16), dp(12), dp(16), dp(12))
            textSize = 14f
            minLines = 5
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }

        AlertDialog.Builder(activity)
            .setTitle("我眼中的自己")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val bio = input.text.toString().trim()
                prefs.edit().putString("my_bio", bio).apply()
                refresh()
                if (bio.isNotEmpty()) {
                    Toast.makeText(activity, "已保存 ♡", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun addSectionTitle(title: String) {
        val tv = TextView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12); bottomMargin = dp(8) }
            text = title; textSize = 12f; setTextColor(c.accent)
            setPadding(dp(4), 0, 0, 0); letterSpacing = 0.1f
        }
        profileContainer.addView(tv)
    }

    private fun addItem(title: String, value: String, desc: String, onClick: () -> Unit) {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = activity.getDrawable(R.drawable.chat_card_bg)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
            setOnClickListener { onClick() }
        }

        val topRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        topRow.addView(TextView(activity).apply {
            text = title; textSize = 14f; setTextColor(c.textPrimary)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })

        if (value.isNotEmpty()) {
            topRow.addView(TextView(activity).apply {
                text = value; textSize = 12f; setTextColor(c.accent)
            })
        }

        card.addView(topRow)

        if (desc.isNotEmpty()) {
            card.addView(TextView(activity).apply {
                text = desc; textSize = 11f; setTextColor(c.tipText)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(3) }
            })
        }

        profileContainer.addView(card)
    }
}