package com.haven.guiqi

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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

class ChatSettingsActivity : AppCompatActivity() {

    private lateinit var settingsContainer: LinearLayout

    private var friendId = ""
    private var friendName = "好友"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        setContentView(R.layout.activity_chat_settings)

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

        settingsContainer = findViewById(R.id.settingsContainer)
        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }

        friendId = intent.getStringExtra("friend_id") ?: ""
        friendName = intent.getStringExtra("friend_name") ?: "好友"

        buildSettings()
    }

    private fun buildSettings() {
        settingsContainer.removeAllViews()

        val friend = FriendStorage(this).getFriend(friendId)
        val chatStorage = ChatStorage(this)
        val msgCount = chatStorage.loadMessages(friendId).size

        // 读取保存的上下文条数
        val prefs = getSharedPreferences("haven_chat_prefs", MODE_PRIVATE)
        val contextSize = prefs.getInt("context_$friendId", 30)

        // ===== 好友信息区 =====
        addSectionTitle("好友信息")

        addInfoRow("名称", friend?.name ?: friendName)
        addInfoRow("编码", friend?.id ?: friendId)
        addInfoRow("分组", friend?.group ?: "好友")
        addInfoRow("消息数", "$msgCount 条")

        // ===== API 配置区 =====
        addSectionTitle("API 配置")

        if (friend != null && friend.apiUrl.isNotEmpty()) {
            addInfoRow("类型", when (friend.apiType) {
                "claude" -> "Claude 原生"
                "gemini" -> "Gemini 原生"
                else -> "OpenAI 格式"
            })
            addInfoRow("模型", friend.apiModel)
            addInfoRow("地址", friend.apiUrl)
        } else {
            addInfoRow("当前", "使用全局 API 配置")
        }

        addClickItem(
            "配置专属 API",
            "给这个好友单独配置 API，不同好友可以用不同的模型"
        ) {
            if (friend != null) showApiConfigDialog(friend)
        }

        // ===== 聊天设置区 =====
        addSectionTitle("聊天设置")

        addClickItem(
            "上下文条数（当前: $contextSize 条）",
            "发给 AI 的最近消息数量，越多 AI 记忆越长但越费 token"
        ) {
            showContextSizePicker(contextSize)
        }

        // ===== 操作区 =====
        addSectionTitle("操作")

        addClickItem(
            "复制好友编码",
            "编码是好友的唯一标识，换设备恢复好友时会用到"
        ) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("friend_code", friendId))
            Toast.makeText(this, "编码已复制: $friendId", Toast.LENGTH_SHORT).show()
        }

        addClickItem(
            "清空聊天记录",
            "删除与「$friendName」的所有消息，无法恢复"
        ) {
            showClearConfirm()
        }
    }

    // ===== 分区标题 =====
    private fun addSectionTitle(title: String) {
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }
        val tv = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(20)
                bottomMargin = dp(8)
            }
            this.text = title
            textSize = 12f
            setTextColor(0x66B3A0FF.toInt())
            setPadding(dp(4), 0, 0, 0)
            letterSpacing = 0.1f
        }
        settingsContainer.addView(tv)
    }

    // ===== 信息行（只读，显示信息） =====
    private fun addInfoRow(label: String, value: String) {
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val tvLabel = TextView(this).apply {
            this.text = label
            textSize = 13f
            setTextColor(0x80FFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(dp(70), LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val tvValue = TextView(this).apply {
            this.text = value
            textSize = 13f
            setTextColor(0xB3FFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }

        row.addView(tvLabel)
        row.addView(tvValue)
        settingsContainer.addView(row)
    }

    // ===== 可点击项（标题 + 说明） =====
    private fun addClickItem(title: String, description: String, onClick: () -> Unit) {
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.chat_card_bg)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
            setOnClickListener { onClick() }
        }

        val tvTitle = TextView(this).apply {
            this.text = title
            textSize = 14f
            setTextColor(0xD9FFFFFF.toInt())
        }

        val tvDesc = TextView(this).apply {
            this.text = description
            textSize = 11f
            setTextColor(0x4DFFFFFF.toInt())
            setLineSpacing(0f, 1.3f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(4)
            }
        }

        card.addView(tvTitle)
        card.addView(tvDesc)
        settingsContainer.addView(card)
    }

    // ===== 上下文条数（自由输入） =====
    private fun showContextSizePicker(current: Int) {
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }

        val hint = TextView(this).apply {
            this.text = "短记忆: 10~15 条\n中记忆: 25~35 条（推荐）\n长记忆: 50~80 条（费 token）"
            textSize = 12f
            setTextColor(0xB3FFFFFF.toInt())
            setLineSpacing(0f, 1.4f)
            setPadding(0, 0, 0, dp(12))
        }
        layout.addView(hint)

        val input = EditText(this).apply {
            setText(current.toString())
            textSize = 16f
            setTextColor(0xD9FFFFFF.toInt())
            setHintTextColor(0x4DFFFFFF.toInt())
            this.hint = "输入条数"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        layout.addView(input)

        AlertDialog.Builder(this)
            .setTitle("上下文条数")
            .setView(layout)
            .setPositiveButton("保存") { _, _ ->
                val value = input.text.toString().toIntOrNull()
                if (value != null && value > 0) {
                    getSharedPreferences("haven_chat_prefs", MODE_PRIVATE)
                        .edit().putInt("context_$friendId", value).apply()
                    Toast.makeText(this, "已设为最近 $value 条", Toast.LENGTH_SHORT).show()
                    buildSettings()
                } else {
                    Toast.makeText(this, "请输入有效数字", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ===== 清空聊天记录 =====
    private fun showClearConfirm() {
        AlertDialog.Builder(this)
            .setTitle("清空聊天记录")
            .setMessage("确定要清空与「$friendName」的所有聊天记录吗？\n此操作无法恢复。")
            .setPositiveButton("清空") { _, _ ->
                ChatStorage(this).deleteMessages(friendId)
                Toast.makeText(this, "聊天记录已清空", Toast.LENGTH_SHORT).show()
                // 设置返回标记，让聊天页面知道要刷新
                setResult(RESULT_OK)
                finish()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ===== 配置专属 API =====
    private fun showApiConfigDialog(friend: Friend) {
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }

        var selectedType = friend.apiType
        val typeNames = arrayOf("OpenAI 格式（GPT/DeepSeek/中转站）", "Claude 原生", "Gemini 原生")
        val typeValues = arrayOf("openai", "claude", "gemini")
        val currentTypeIndex = typeValues.indexOf(selectedType).coerceAtLeast(0)

        val typeBtn = TextView(this).apply {
            this.text = "API 类型: ${typeNames[currentTypeIndex]}"
            textSize = 14f
            setTextColor(0xD9FFFFFF.toInt())
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

        val hint = TextView(this).apply {
            this.text = "留空则使用全局配置（设置页的配置）"
            textSize = 11f
            setTextColor(0x80FFFFFF.toInt())
            setPadding(0, 0, 0, dp(12))
        }
        layout.addView(hint)

        val inputUrl = EditText(this).apply {
            this.hint = "API 地址"
            setText(friend.apiUrl)
            textSize = 14f
            setTextColor(0xD9FFFFFF.toInt())
            setHintTextColor(0x4DFFFFFF.toInt())
        }
        layout.addView(inputUrl)

        val inputKey = EditText(this).apply {
            this.hint = "API 密钥"
            setText(friend.apiKey)
            textSize = 14f
            setTextColor(0xD9FFFFFF.toInt())
            setHintTextColor(0x4DFFFFFF.toInt())
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(inputKey)

        val inputModel = EditText(this).apply {
            this.hint = "模型名称"
            setText(friend.apiModel)
            textSize = 14f
            setTextColor(0xD9FFFFFF.toInt())
            setHintTextColor(0x4DFFFFFF.toInt())
        }
        layout.addView(inputModel)

        AlertDialog.Builder(this)
            .setTitle("${friend.name} 的 API 配置")
            .setView(layout)
            .setPositiveButton("保存") { _, _ ->
                FriendStorage(this).updateFriend(friend.copy(
                    apiUrl = inputUrl.text.toString().trim(),
                    apiKey = inputKey.text.toString().trim(),
                    apiModel = inputModel.text.toString().trim(),
                    apiType = selectedType
                ))
                Toast.makeText(this, "API 配置已保存 ♡", Toast.LENGTH_SHORT).show()
                buildSettings()
            }
            .setNeutralButton("清除配置") { _, _ ->
                FriendStorage(this).updateFriend(friend.copy(
                    apiUrl = "", apiKey = "", apiModel = "", apiType = "openai"
                ))
                Toast.makeText(this, "已清除，将使用全局配置", Toast.LENGTH_SHORT).show()
                buildSettings()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}