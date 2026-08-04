package com.haven.guiqi

import android.app.AlertDialog
import android.net.Uri
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    private lateinit var inputApiUrl: EditText
    private lateinit var inputApiKey: EditText
    private lateinit var inputModel: EditText
    private lateinit var btnLanguage: TextView
    private lateinit var btnTheme: TextView
    private lateinit var btnExport: TextView
    private lateinit var btnImport: TextView
    private lateinit var btnSave: TextView
    private lateinit var btnBack: TextView
    private lateinit var btnFetchModels: TextView
    private lateinit var btnTestConnection: TextView
    private lateinit var tvConnectionStatus: TextView
    private lateinit var btnPreset: TextView
    private lateinit var btnSavePreset: TextView
    private lateinit var btnDeletePreset: TextView

    private val mainHandler = Handler(Looper.getMainLooper())

    private val languages = arrayOf(
        "简体中文", "English", "日本語", "Русский", "한국어"
    )
    private var currentLanguageIndex = 0
    private var currentPresetName = "默认"

    private lateinit var fullBackupManager: FullBackupManager
    private var busyDialog: AlertDialog? = null

    private val fullExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        if (uri != null) performFullExport(uri)
    }

    private val fullImportLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) performFullImport(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 沉浸式全屏
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        // 允许内容画到挖孔/刘海区域
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        setContentView(R.layout.activity_settings)

        // 只隐藏底部导航栏，保留顶部状态栏
        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        insetsController.hide(WindowInsetsCompat.Type.navigationBars())
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController.isAppearanceLightStatusBars = false

        // ===== 适配状态栏高度 =====
        val contentView = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(contentView) { view, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.setPadding(0, top, 0, 0)
            insets
        }

        // ===== 绑定元素 =====
        inputApiUrl = findViewById(R.id.inputApiUrl)
        inputApiKey = findViewById(R.id.inputApiKey)
        inputModel = findViewById(R.id.inputModel)
        btnLanguage = findViewById(R.id.btnLanguage)
        btnTheme = findViewById(R.id.btnTheme)
        btnExport = findViewById(R.id.btnExport)
        btnImport = findViewById(R.id.btnImport)
        btnSave = findViewById(R.id.btnSave)
        btnBack = findViewById(R.id.btnBack)
        btnFetchModels = findViewById(R.id.btnFetchModels)
        btnTestConnection = findViewById(R.id.btnTestConnection)
        tvConnectionStatus = findViewById(R.id.tvConnectionStatus)
        btnPreset = findViewById(R.id.btnPreset)
        btnSavePreset = findViewById(R.id.btnSavePreset)
        btnDeletePreset = findViewById(R.id.btnDeletePreset)
        fullBackupManager = FullBackupManager(this)

        loadSettings()

        // 显示当前主题
        val themeNames = arrayOf("跟随系统", "始终深色", "始终浅色（Claude 配色）", "跟随日出日落")
        btnTheme.text = themeNames.getOrElse(ThemeHelper.getMode(this)) { themeNames[0] }

        // ===== 返回 =====
        btnBack.setOnClickListener { finish() }

        // ===== 保存设置 =====
        btnSave.setOnClickListener {
            saveSettings()
            Toast.makeText(this, "设置已保存 ✓", Toast.LENGTH_SHORT).show()
        }

        // ===== 主题切换 =====
        btnTheme.setOnClickListener {
            val options = arrayOf("跟随系统", "始终深色", "始终浅色（Claude 配色）", "跟随日出日落")
            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
                .setTitle("选择主题")
                .setItems(options) { _, which ->
                    ThemeHelper.setMode(this, which)
                    btnTheme.text = options[which]
                    Toast.makeText(this, "主题已切换", Toast.LENGTH_SHORT).show()
                    recreate()
                }
                .show()
        }

        // ===== 搜索组 =====
        findViewById<TextView>(R.id.btnSearchGroup).setOnClickListener {
            startActivity(android.content.Intent(this, SearchGroupActivity::class.java))
        }

        // ===== 小助手 API =====
        findViewById<TextView>(R.id.btnHelperApi).setOnClickListener {
            showHelperApiDialog()
        }

        // ===== 语言选择 =====
        btnLanguage.setOnClickListener {
            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
                .setTitle("选择语言")
                .setItems(languages) { _, which ->
                    currentLanguageIndex = which
                    btnLanguage.text = languages[which]
                    Toast.makeText(this, "语言已切换为 ${languages[which]}（功能开发中）", Toast.LENGTH_SHORT).show()
                }
                .show()
        }

        // ===== 拉取模型列表 =====
        btnFetchModels.setOnClickListener {
            val url = inputApiUrl.text.toString().trim()
            val key = inputApiKey.text.toString().trim()
            if (url.isEmpty() || key.isEmpty()) {
                Toast.makeText(this, "请先填写 API 地址和密钥", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            fetchModels(url, key)
        }

        // ===== 测试连接 =====
        btnTestConnection.setOnClickListener {
            val url = inputApiUrl.text.toString().trim()
            val key = inputApiKey.text.toString().trim()
            val model = inputModel.text.toString().trim()
            if (url.isEmpty() || key.isEmpty() || model.isEmpty()) {
                Toast.makeText(this, "请先填写 API 地址、密钥和模型", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            testConnection(url, key, model)
        }

        // ===== 选择预设 =====
        btnPreset.setOnClickListener {
            showPresetSelector()
        }

        // ===== 保存预设 =====
        btnSavePreset.setOnClickListener {
            showSavePresetDialog()
        }

        // ===== 删除预设 =====
        btnDeletePreset.setOnClickListener {
            deleteCurrentPreset()
        }

        // ===== 完整导出/导入 =====
        btnExport.setOnClickListener { confirmAndStartFullExport() }
        btnImport.setOnClickListener { confirmAndStartFullImport() }

        mainHandler.post { maybeOfferRestoreSafety() }
    }


    private fun confirmAndStartFullExport() {
        AlertDialog.Builder(this)
            .setTitle("导出全部数据")
            .setMessage(
                "完整备份会包含聊天、画匣图片、头像、书架、记忆、日记、梦境、设置和 API 密钥。\n\n" +
                    "备份文件没有加密，请只保存在你信任的位置。"
            )
            .setPositiveButton("选择保存位置") { _, _ ->
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                fullExportLauncher.launch("归栖完整备份_$stamp.guiqi.zip")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun performFullExport(uri: Uri) {
        setBackupButtonsEnabled(false)
        showBusy("正在导出全部数据", "聊天、画匣和住户资料正在装进备份……")

        Thread {
            try {
                val result = fullBackupManager.exportTo(uri)
                mainHandler.post {
                    clearBusy()
                    setBackupButtonsEnabled(true)
                    AlertDialog.Builder(this)
                        .setTitle("导出完成 ✓")
                        .setMessage(
                            "已备份 ${result.fileCount} 个文件，共 ${formatBytes(result.totalBytes)}。\n\n" +
                                "这个 ZIP 包含私密聊天和 API 密钥，不要发给别人。"
                        )
                        .setPositiveButton("好", null)
                        .show()
                }
            } catch (e: Exception) {
                mainHandler.post {
                    clearBusy()
                    setBackupButtonsEnabled(true)
                    Toast.makeText(this, "导出失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun confirmAndStartFullImport() {
        if (fullBackupManager.hasRestoreSafety()) {
            showRestoreSafetyDialog()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("导入全部数据")
            .setMessage(
                "导入会替换当前归栖里的聊天、画匣、设置和全部住户资料。\n\n" +
                    "导入前的数据会先保留为一份本地保护副本，可以撤销一次。"
            )
            .setPositiveButton("选择备份文件") { _, _ ->
                fullImportLauncher.launch(
                    arrayOf(
                        "application/zip",
                        "application/octet-stream",
                        "application/x-zip-compressed"
                    )
                )
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun performFullImport(uri: Uri) {
        setBackupButtonsEnabled(false)
        showBusy("正在恢复全部数据", "正在校验备份并保护当前数据，请不要退出……")

        Thread {
            try {
                val result = fullBackupManager.restoreFrom(uri)
                mainHandler.post {
                    clearBusy()
                    setBackupButtonsEnabled(true)
                    showRestartRequired(
                        title = "恢复完成 ✓",
                        message = "已恢复 ${result.fileCount} 个文件，共 ${formatBytes(result.totalBytes)}。\n\n" +
                            "导入前的数据已经保留为本地保护副本。现在需要退出归栖；重新打开后，新数据才会完全生效。"
                    )
                }
            } catch (e: Exception) {
                mainHandler.post {
                    clearBusy()
                    setBackupButtonsEnabled(true)
                    Toast.makeText(this, "导入失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun maybeOfferRestoreSafety() {
        if (!isFinishing && fullBackupManager.hasRestoreSafety()) {
            showRestoreSafetyDialog()
        }
    }

    private fun showRestoreSafetyDialog() {
        if (isFinishing) return
        val createdAt = fullBackupManager.restoreSafetyCreatedAt()
        val timeText = if (createdAt > 0L) {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(createdAt))
        } else {
            "上次导入前"
        }

        AlertDialog.Builder(this)
            .setTitle("导入前的保护副本")
            .setMessage(
                "$timeText 的原数据还保留在本机缓存中。\n\n" +
                    "新数据确认正常后可以删除；不满意也可以撤销上次完整导入。"
            )
            .setPositiveButton("撤销导入") { _, _ -> confirmRollbackRestore() }
            .setNegativeButton("删除副本") { _, _ -> confirmDiscardRestoreSafety() }
            .setNeutralButton("稍后", null)
            .show()
    }

    private fun confirmRollbackRestore() {
        AlertDialog.Builder(this)
            .setTitle("撤销上次导入？")
            .setMessage("当前恢复进来的数据会被替换回导入前的状态。")
            .setPositiveButton("撤销") { _, _ -> performRollbackRestore() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun performRollbackRestore() {
        setBackupButtonsEnabled(false)
        showBusy("正在撤销导入", "正在换回导入前的数据，请不要退出……")
        Thread {
            try {
                val result = fullBackupManager.rollbackLastRestore()
                mainHandler.post {
                    clearBusy()
                    setBackupButtonsEnabled(true)
                    showRestartRequired(
                        title = "已经撤销 ✓",
                        message = "已换回导入前的数据（${result.fileCount} 个文件）。\n\n" +
                            "现在需要退出归栖；重新打开后才会完全生效。"
                    )
                }
            } catch (e: Exception) {
                mainHandler.post {
                    clearBusy()
                    setBackupButtonsEnabled(true)
                    Toast.makeText(this, "撤销失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun confirmDiscardRestoreSafety() {
        AlertDialog.Builder(this)
            .setTitle("删除保护副本？")
            .setMessage("删除后将不能再撤销上次完整导入；当前正在使用的数据不会受影响。")
            .setPositiveButton("删除") { _, _ ->
                fullBackupManager.discardRestoreSafety()
                Toast.makeText(this, "保护副本已删除", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showBusy(title: String, message: String) {
        clearBusy()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(18), dp(24), dp(12))
            addView(android.widget.ProgressBar(this@SettingsActivity).apply {
                isIndeterminate = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { gravity = android.view.Gravity.CENTER_HORIZONTAL }
            })
            addView(TextView(this@SettingsActivity).apply {
                text = message
                textSize = 14f
                gravity = android.view.Gravity.CENTER
                setPadding(0, dp(14), 0, 0)
            })
        }
        busyDialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(content)
            .setCancelable(false)
            .create()
        busyDialog?.show()
    }

    private fun clearBusy() {
        busyDialog?.dismiss()
        busyDialog = null
    }

    private fun setBackupButtonsEnabled(enabled: Boolean) {
        btnExport.isEnabled = enabled
        btnImport.isEnabled = enabled
        btnExport.alpha = if (enabled) 1f else 0.5f
        btnImport.alpha = if (enabled) 1f else 0.5f
    }

    private fun showRestartRequired(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("退出归栖") { _, _ ->
                HavenService.stopForDataRestore(this)
                finishAffinity()
                mainHandler.postDelayed({
                    Process.killProcess(Process.myPid())
                }, 150L)
            }
            .show()
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = -1
        do {
            value /= 1024.0
            unit++
        } while (value >= 1024.0 && unit < units.lastIndex)
        return String.format(Locale.getDefault(), "%.1f %s", value, units[unit])
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    // ===== 拉取模型列表 =====
    private fun fetchModels(apiUrl: String, apiKey: String) {
        btnFetchModels.text = "拉取中..."
        tvConnectionStatus.text = "正在获取模型列表..."
        tvConnectionStatus.setTextColor(Color.parseColor("#66FFFFFF"))

        Thread {
            try {
                // 拼接 /models 端点
                val modelsUrl = if (apiUrl.endsWith("/")) {
                    "${apiUrl}models"
                } else {
                    "$apiUrl/models"
                }

                val connection = URL(modelsUrl).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("Authorization", "Bearer $apiKey")
                connection.setRequestProperty("Content-Type", "application/json")
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                val responseCode = connection.responseCode
                if (responseCode == 200) {
                    val reader = BufferedReader(InputStreamReader(connection.inputStream))
                    val response = reader.readText()
                    reader.close()

                    // 解析模型列表
                    val json = JSONObject(response)
                    val data = json.getJSONArray("data")
                    val modelNames = mutableListOf<String>()
                    for (i in 0 until data.length()) {
                        val modelObj = data.getJSONObject(i)
                        modelNames.add(modelObj.getString("id"))
                    }
                    // 按字母排序
                    modelNames.sort()

                    mainHandler.post {
                        btnFetchModels.text = "拉取"
                        tvConnectionStatus.text = "获取到 ${modelNames.size} 个模型"
                        tvConnectionStatus.setTextColor(Color.parseColor("#66FF66"))

                        // 弹出选择框
                        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
                            .setTitle("选择模型 (${modelNames.size})")
                            .setItems(modelNames.toTypedArray()) { _, which ->
                                inputModel.setText(modelNames[which])
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                } else {
                    val errorStream = connection.errorStream
                    val errorMsg = if (errorStream != null) {
                        BufferedReader(InputStreamReader(errorStream)).readText()
                    } else {
                        "HTTP $responseCode"
                    }
                    mainHandler.post {
                        btnFetchModels.text = "拉取"
                        tvConnectionStatus.text = "拉取失败: $responseCode"
                        tvConnectionStatus.setTextColor(Color.parseColor("#FF6666"))
                    }
                }
                connection.disconnect()
            } catch (e: Exception) {
                mainHandler.post {
                    btnFetchModels.text = "拉取"
                    tvConnectionStatus.text = "连接失败: ${e.message}"
                    tvConnectionStatus.setTextColor(Color.parseColor("#FF6666"))
                }
            }
        }.start()
    }

    // ===== 测试连接 =====
    private fun testConnection(apiUrl: String, apiKey: String, model: String) {
        btnTestConnection.text = "测试中..."
        tvConnectionStatus.text = "正在测试连接..."
        tvConnectionStatus.setTextColor(Color.parseColor("#66FFFFFF"))

        Thread {
            try {
                // 发送一个简单的聊天请求
                val chatUrl = if (apiUrl.endsWith("/")) {
                    "${apiUrl}chat/completions"
                } else {
                    "$apiUrl/chat/completions"
                }

                val connection = URL(chatUrl).openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Authorization", "Bearer $apiKey")
                connection.setRequestProperty("Content-Type", "application/json")
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.doOutput = true

                // 构建最简单的请求体
                val body = JSONObject().apply {
                    put("model", model)
                    put("max_tokens", 10)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", "hi")
                        })
                    })
                }

                val writer = OutputStreamWriter(connection.outputStream)
                writer.write(body.toString())
                writer.flush()
                writer.close()

                val responseCode = connection.responseCode
                if (responseCode == 200) {
                    val reader = BufferedReader(InputStreamReader(connection.inputStream))
                    val response = reader.readText()
                    reader.close()

                    // 尝试读取回复内容
                    val json = JSONObject(response)
                    val reply = json.getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")

                    mainHandler.post {
                        btnTestConnection.text = "测试连接"
                        tvConnectionStatus.text = "连接成功 ✓ AI回复: $reply"
                        tvConnectionStatus.setTextColor(Color.parseColor("#66FF66"))
                    }
                } else {
                    val errorStream = connection.errorStream
                    val errorMsg = if (errorStream != null) {
                        try {
                            val errJson = JSONObject(
                                BufferedReader(InputStreamReader(errorStream)).readText()
                            )
                            errJson.optJSONObject("error")?.optString("message")
                                ?: "HTTP $responseCode"
                        } catch (e: Exception) {
                            "HTTP $responseCode"
                        }
                    } else {
                        "HTTP $responseCode"
                    }
                    mainHandler.post {
                        btnTestConnection.text = "测试连接"
                        tvConnectionStatus.text = "连接失败: $errorMsg"
                        tvConnectionStatus.setTextColor(Color.parseColor("#FF6666"))
                    }
                }
                connection.disconnect()
            } catch (e: Exception) {
                mainHandler.post {
                    btnTestConnection.text = "测试连接"
                    tvConnectionStatus.text = "连接失败: ${e.message}"
                    tvConnectionStatus.setTextColor(Color.parseColor("#FF6666"))
                }
            }
        }.start()
    }

    // ===== 预设管理 =====

    // 获取所有预设名称
    private fun getPresetNames(): MutableList<String> {
        val prefs = getSharedPreferences("haven_presets", MODE_PRIVATE)
        val namesStr = prefs.getString("preset_names", "默认") ?: "默认"
        return namesStr.split("|||").toMutableList()
    }

    // 保存预设名称列表
    private fun savePresetNames(names: List<String>) {
        val prefs = getSharedPreferences("haven_presets", MODE_PRIVATE)
        prefs.edit().putString("preset_names", names.joinToString("|||")).apply()
    }

    // 显示预设选择器
    private fun showPresetSelector() {
        val names = getPresetNames()
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
            .setTitle("选择 API 预设")
            .setItems(names.toTypedArray()) { _, which ->
                val selectedName = names[which]
                loadPreset(selectedName)
                currentPresetName = selectedName
                btnPreset.text = selectedName
                Toast.makeText(this, "已切换到预设: $selectedName", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // 保存当前配置为预设
    private fun showSavePresetDialog() {
        val editText = EditText(this).apply {
            hint = "输入预设名称"
            setText(currentPresetName)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
            .setTitle("保存为预设")
            .setView(editText)
            .setPositiveButton("保存") { _, _ ->
                val name = editText.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, "名称不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                savePreset(name)
                currentPresetName = name
                btnPreset.text = name

                // 把名字加到列表里
                val names = getPresetNames()
                if (!names.contains(name)) {
                    names.add(name)
                    savePresetNames(names)
                }

                Toast.makeText(this, "预设 \"$name\" 已保存 ✓", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // 保存一个预设
    private fun savePreset(name: String) {
        val prefs = getSharedPreferences("haven_presets", MODE_PRIVATE)
        prefs.edit().apply {
            putString("preset_${name}_url", inputApiUrl.text.toString().trim())
            putString("preset_${name}_key", inputApiKey.text.toString().trim())
            putString("preset_${name}_model", inputModel.text.toString().trim())
            apply()
        }
    }

    // 加载一个预设
    private fun loadPreset(name: String) {
        val prefs = getSharedPreferences("haven_presets", MODE_PRIVATE)
        val url = prefs.getString("preset_${name}_url", "") ?: ""
        val key = prefs.getString("preset_${name}_key", "") ?: ""
        val model = prefs.getString("preset_${name}_model", "") ?: ""

        inputApiUrl.setText(url)
        inputApiKey.setText(key)
        inputModel.setText(model)
    }

    // 删除当前预设
    private fun deleteCurrentPreset() {
        if (currentPresetName == "默认") {
            Toast.makeText(this, "默认预设不能删除", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
            .setTitle("删除预设")
            .setMessage("确定要删除预设 \"$currentPresetName\" 吗？")
            .setPositiveButton("删除") { _, _ ->
                val prefs = getSharedPreferences("haven_presets", MODE_PRIVATE)
                prefs.edit().apply {
                    remove("preset_${currentPresetName}_url")
                    remove("preset_${currentPresetName}_key")
                    remove("preset_${currentPresetName}_model")
                    apply()
                }

                val names = getPresetNames()
                names.remove(currentPresetName)
                savePresetNames(names)

                Toast.makeText(this, "预设已删除", Toast.LENGTH_SHORT).show()

                // 切回默认
                currentPresetName = "默认"
                btnPreset.text = "默认"
                loadPreset("默认")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ===== 小助手 API 配置 =====
    private fun showHelperApiDialog() {
        val storage = LockScreenStorage(this)
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }
        val inputUrl = EditText(this).apply {
            hint = "API 地址"
            setText(storage.getHelperApiUrl())
            textSize = 13f
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val inputKey = EditText(this).apply {
            hint = "API 密钥"
            setText(storage.getHelperApiKey())
            textSize = 13f
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val modelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val inputModel = EditText(this).apply {
            hint = "模型名称"
            setText(storage.getHelperApiModel())
            textSize = 13f
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnFetch = TextView(this).apply {
            text = "拉取"
            textSize = 12f; setTextColor(Color.parseColor("#4A90D9"))
            setPadding(dp(12), dp(8), dp(4), dp(8))
            setOnClickListener {
                val url = inputUrl.text.toString().trim()
                val key = inputKey.text.toString().trim()
                if (url.isEmpty() || key.isEmpty()) {
                    Toast.makeText(this@SettingsActivity, "请先填写地址和密钥", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                text = "拉取中..."
                fetchHelperModels(url, key, inputModel) { text = "拉取" }
            }
        }
        modelRow.addView(inputModel)
        modelRow.addView(btnFetch)
        layout.addView(inputUrl)
        layout.addView(inputKey)
        layout.addView(modelRow)

        AlertDialog.Builder(this)
            .setTitle("小助手 API")
            .setMessage("用于开屏文案、天气卡片等小功能。\n可以用便宜的小模型，不需要很聪明。")
            .setView(layout as View)
            .setPositiveButton("保存") { _, _ ->
                storage.saveHelperApi(
                    inputUrl.text.toString().trim(),
                    inputKey.text.toString().trim(),
                    inputModel.text.toString().trim()
                )
                Toast.makeText(this, "小助手 API 已保存 ✓", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 拉取小助手 API 的模型列表 */
    private fun fetchHelperModels(apiUrl: String, apiKey: String, inputModel: EditText, onDone: () -> Unit) {
        Thread {
            try {
                val modelsUrl = if (apiUrl.endsWith("/")) "${apiUrl}models" else "$apiUrl/models"
                val conn = URL(modelsUrl).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Authorization", "Bearer $apiKey")
                conn.connectTimeout = 10000; conn.readTimeout = 10000
                if (conn.responseCode == 200) {
                    val json = JSONObject(BufferedReader(InputStreamReader(conn.inputStream)).readText())
                    val data = json.getJSONArray("data")
                    val names = (0 until data.length()).map { data.getJSONObject(it).getString("id") }.sorted()
                    mainHandler.post {
                        onDone()
                        AlertDialog.Builder(this)
                            .setTitle("选择模型 (${names.size})")
                            .setItems(names.toTypedArray()) { _, i -> inputModel.setText(names[i]) }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                } else {
                    mainHandler.post { onDone(); Toast.makeText(this, "拉取失败: ${conn.responseCode}", Toast.LENGTH_SHORT).show() }
                }
                conn.disconnect()
            } catch (e: Exception) {
                mainHandler.post { onDone(); Toast.makeText(this, "连接失败: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }

    // ===== 保存/读取当前设置 =====
    private fun saveSettings() {
        val prefs = getSharedPreferences("haven_prefs", MODE_PRIVATE)
        prefs.edit().apply {
            putString("api_url", inputApiUrl.text.toString().trim())
            putString("api_key", inputApiKey.text.toString().trim())
            putString("api_model", inputModel.text.toString().trim())
            putInt("language_index", currentLanguageIndex)
            putString("current_preset", currentPresetName)
            apply()
        }
        // 同时更新当前预设的内容
        savePreset(currentPresetName)
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("haven_prefs", MODE_PRIVATE)

        val url = prefs.getString("api_url", "") ?: ""
        val key = prefs.getString("api_key", "") ?: ""
        val model = prefs.getString("api_model", "") ?: ""
        currentLanguageIndex = prefs.getInt("language_index", 0)
        currentPresetName = prefs.getString("current_preset", "默认") ?: "默认"

        if (url.isNotEmpty()) inputApiUrl.setText(url)
        if (key.isNotEmpty()) inputApiKey.setText(key)
        if (model.isNotEmpty()) inputModel.setText(model)
        btnLanguage.text = languages[currentLanguageIndex]
        btnPreset.text = currentPresetName
    }
}