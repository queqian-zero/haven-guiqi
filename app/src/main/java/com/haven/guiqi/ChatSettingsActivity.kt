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

class ChatSettingsActivity : AppCompatActivity() {

    private val c get() = ThemeHelper.getColors(this)

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
        insetsController.isAppearanceLightStatusBars = !ThemeHelper.isDark(this)

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

        // 梦境 API
        if (friend != null && friend.dreamApiUrl.isNotEmpty()) {
            addInfoRow("梦境模型", friend.dreamApiModel)
        } else {
            val globalDreamUrl = getSharedPreferences("haven_prefs", MODE_PRIVATE)
                .getString("dream_api_url", "") ?: ""
            if (globalDreamUrl.isNotEmpty()) {
                addInfoRow("梦境", "使用全局梦境 API")
            } else {
                addInfoRow("梦境", "未配置（不会做梦）")
            }
        }

        addClickItem(
            "配置梦境 API",
            "做梦用的模型，跟聊天不同才有意外感"
        ) {
            if (friend != null) showDreamApiConfigDialog(friend)
        }

        // ===== 聊天设置区 =====
        addSectionTitle("聊天设置")

        addClickItem(
            "上下文条数（当前: $contextSize 条）",
            "发给 AI 的最近消息数量，越多 AI 记忆越长但越费 token"
        ) {
            showContextSizePicker(contextSize)
        }

        // ===== AI 内心世界 =====
        addSectionTitle("AI 内心世界")

        val memoryStorage = MemoryStorage(this)
        val diaryStorage = DiaryStorage(this)
        val impressionStorage = ImpressionStorage(this)
        val dreamStorage = DreamStorage(this)
        val friendIcon = friend?.icon ?: "★"

        val memCount = memoryStorage.count(friendId)
        val diaryCount = diaryStorage.count(friendId)
        val dreamCount = dreamStorage.count(friendId)
        val impression = impressionStorage.getImpression(friendId)

        addClickItem(
            "📂 打开档案馆",
            "记忆 $memCount · 日记 $diaryCount · 梦境 $dreamCount" +
                (if (impression.isNotEmpty()) " · 有印象" else "")
        ) {
            val intent = Intent(this, ArchiveDetailActivity::class.java)
            intent.putExtra("friend_id", friendId)
            intent.putExtra("friend_name", friendName)
            intent.putExtra("friend_icon", friendIcon)
            startActivity(intent)
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
            setTextColor(c.accent)
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
            setTextColor(c.textSecondary)
            layoutParams = LinearLayout.LayoutParams(dp(70), LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val tvValue = TextView(this).apply {
            this.text = value
            textSize = 13f
            setTextColor(c.textOnAccent)
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
            setTextColor(c.textPrimary)
        }

        val tvDesc = TextView(this).apply {
            this.text = description
            textSize = 11f
            setTextColor(c.textHint)
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
            setTextColor(c.textOnAccent)
            setLineSpacing(0f, 1.4f)
            setPadding(0, 0, 0, dp(12))
        }
        layout.addView(hint)

        val input = EditText(this).apply {
            setText(current.toString())
            textSize = 16f
            setTextColor(c.textPrimary)
            setHintTextColor(c.textHint)
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
            setTextColor(c.textPrimary)
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
            setTextColor(c.textSecondary)
            setPadding(0, 0, 0, dp(12))
        }
        layout.addView(hint)

        val inputUrl = EditText(this).apply {
            this.hint = "API 地址"
            setText(friend.apiUrl)
            textSize = 14f
            setTextColor(c.textPrimary)
            setHintTextColor(c.textHint)
        }
        layout.addView(inputUrl)

        val inputKey = EditText(this).apply {
            this.hint = "API 密钥"
            setText(friend.apiKey)
            textSize = 14f
            setTextColor(c.textPrimary)
            setHintTextColor(c.textHint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(inputKey)

        val inputModel = EditText(this).apply {
            this.hint = "模型名称"
            setText(friend.apiModel)
            textSize = 14f
            setTextColor(c.textPrimary)
            setHintTextColor(c.textHint)
        }
        layout.addView(inputModel)

        val fetchBtn = TextView(this).apply {
            this.text = "🔍 拉取可用模型"
            textSize = 13f
            setTextColor(c.accent)
            setPadding(0, dp(6), 0, dp(4))
            setOnClickListener {
                val url = inputUrl.text.toString().trim()
                val key = inputKey.text.toString().trim()
                if (url.isEmpty() || key.isEmpty()) {
                    Toast.makeText(this@ChatSettingsActivity, "请先填写地址和密钥", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                this.text = "⏳ 拉取中..."
                this.isEnabled = false
                fetchModels(url, key, selectedType) { models ->
                    runOnUiThread {
                        this.text = "🔍 拉取可用模型"
                        this.isEnabled = true
                        if (models.isEmpty()) {
                            Toast.makeText(this@ChatSettingsActivity, "没有拉取到模型，检查地址和密钥", Toast.LENGTH_SHORT).show()
                        } else {
                            AlertDialog.Builder(this@ChatSettingsActivity)
                                .setTitle("选择模型 (${models.size})")
                                .setItems(models.toTypedArray()) { _, which ->
                                    inputModel.setText(models[which])
                                }
                                .show()
                        }
                    }
                }
            }
        }
        layout.addView(fetchBtn)

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

    // ===== 配置梦境 API =====
    private fun showDreamApiConfigDialog(friend: Friend) {
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }

        var selectedType = friend.dreamApiType
        val typeNames = arrayOf("OpenAI 格式（GPT/DeepSeek/中转站）", "Claude 原生", "Gemini 原生")
        val typeValues = arrayOf("openai", "claude", "gemini")
        val currentTypeIndex = typeValues.indexOf(selectedType).coerceAtLeast(0)

        val typeBtn = TextView(this).apply {
            this.text = "API 类型: ${typeNames[currentTypeIndex]}"
            textSize = 14f
            setTextColor(c.textPrimary)
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

        layout.addView(TextView(this).apply {
            this.text = "做梦用不同的模型，梦里才会有意外。\n留空则不做梦（或使用全局梦境配置）。"
            textSize = 11f
            setTextColor(c.textSecondary)
            setPadding(0, 0, 0, dp(12))
        })

        val inputUrl = EditText(this).apply {
            this.hint = "梦境 API 地址"
            setText(friend.dreamApiUrl)
            textSize = 14f
            setTextColor(c.textPrimary)
            setHintTextColor(c.textHint)
        }
        layout.addView(inputUrl)

        val inputKey = EditText(this).apply {
            this.hint = "梦境 API 密钥"
            setText(friend.dreamApiKey)
            textSize = 14f
            setTextColor(c.textPrimary)
            setHintTextColor(c.textHint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(inputKey)

        val inputModel = EditText(this).apply {
            this.hint = "梦境模型名称"
            setText(friend.dreamApiModel)
            textSize = 14f
            setTextColor(c.textPrimary)
            setHintTextColor(c.textHint)
        }
        layout.addView(inputModel)

        val fetchBtn = TextView(this).apply {
            this.text = "🔍 拉取可用模型"
            textSize = 13f
            setTextColor(c.accent)
            setPadding(0, dp(6), 0, dp(4))
            setOnClickListener {
                val url = inputUrl.text.toString().trim()
                val key = inputKey.text.toString().trim()
                if (url.isEmpty() || key.isEmpty()) {
                    Toast.makeText(this@ChatSettingsActivity, "请先填写地址和密钥", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                this.text = "⏳ 拉取中..."
                this.isEnabled = false
                fetchModels(url, key, selectedType) { models ->
                    runOnUiThread {
                        this.text = "🔍 拉取可用模型"
                        this.isEnabled = true
                        if (models.isEmpty()) {
                            Toast.makeText(this@ChatSettingsActivity, "没有拉取到模型，检查地址和密钥", Toast.LENGTH_SHORT).show()
                        } else {
                            AlertDialog.Builder(this@ChatSettingsActivity)
                                .setTitle("选择梦境模型 (${models.size})")
                                .setItems(models.toTypedArray()) { _, which ->
                                    inputModel.setText(models[which])
                                }
                                .show()
                        }
                    }
                }
            }
        }
        layout.addView(fetchBtn)

        AlertDialog.Builder(this)
            .setTitle("${friend.name} 的梦境 API")
            .setView(layout)
            .setPositiveButton("保存") { _, _ ->
                FriendStorage(this).updateFriend(friend.copy(
                    dreamApiUrl = inputUrl.text.toString().trim(),
                    dreamApiKey = inputKey.text.toString().trim(),
                    dreamApiModel = inputModel.text.toString().trim(),
                    dreamApiType = selectedType
                ))
                Toast.makeText(this, "梦境 API 已保存 🌙", Toast.LENGTH_SHORT).show()
                buildSettings()
            }
            .setNeutralButton("清除配置") { _, _ ->
                FriendStorage(this).updateFriend(friend.copy(
                    dreamApiUrl = "", dreamApiKey = "", dreamApiModel = "", dreamApiType = "openai"
                ))
                Toast.makeText(this, "已清除梦境配置", Toast.LENGTH_SHORT).show()
                buildSettings()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ===== 拉取可用模型 =====
    private fun fetchModels(apiUrl: String, apiKey: String, apiType: String, onResult: (List<String>) -> Unit) {
        Thread {
            try {
                val baseUrl = apiUrl.trimEnd('/')
                val modelsUrl = when {
                    baseUrl.endsWith("/v1") -> "$baseUrl/models"
                    baseUrl.contains("/v1/") -> baseUrl.substringBefore("/v1/") + "/v1/models"
                    else -> "$baseUrl/v1/models"
                }
                val conn = java.net.URL(modelsUrl).openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                when (apiType) {
                    "claude" -> {
                        conn.setRequestProperty("x-api-key", apiKey)
                        conn.setRequestProperty("anthropic-version", "2023-06-01")
                    }
                    else -> {
                        conn.setRequestProperty("Authorization", "Bearer $apiKey")
                    }
                }
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().readText()
                    val json = org.json.JSONObject(body)
                    val models = mutableListOf<String>()
                    val data = json.optJSONArray("data")
                    if (data != null) {
                        for (i in 0 until data.length()) {
                            val id = data.getJSONObject(i).optString("id", "")
                            if (id.isNotEmpty()) models.add(id)
                        }
                    }
                    models.sort()
                    onResult(models)
                } else {
                    onResult(emptyList())
                }
                conn.disconnect()
            } catch (e: Exception) {
                onResult(emptyList())
            }
        }.start()
    }
}