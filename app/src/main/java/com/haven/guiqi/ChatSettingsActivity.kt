package com.haven.guiqi

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.abs
import kotlin.math.roundToInt

class ChatSettingsActivity : AppCompatActivity() {

    private val c get() = ThemeHelper.getColors(this)

    private lateinit var settingsContainer: LinearLayout
    private val appearanceStorage by lazy { ChatAppearanceStorage(this) }
    private val galleryStorage by lazy { GalleryStorage(this) }

    private var friendId = ""
    private var friendName = "好友"
    private var hasResumedOnce = false

    private data class BackgroundEditorSession(
        var item: GalleryStorage.Item?,
        var effects: ChatAppearanceStorage.BackgroundEffects,
        var transform: ChatAppearanceStorage.BackgroundTransform
    )

    private data class AvatarFrameEditorSession(
        var friendItem: GalleryStorage.Item?,
        var userItem: GalleryStorage.Item?,
        var friendTransform: ChatAppearanceStorage.AvatarFrameTransform,
        var userTransform: ChatAppearanceStorage.AvatarFrameTransform,
        var displayMode: ChatAppearanceStorage.AvatarDisplayMode,
        var friendShape: ChatAppearanceStorage.AvatarShape,
        var userShape: ChatAppearanceStorage.AvatarShape,
        var previewTarget: ChatAppearanceStorage.AvatarTarget
    ) {
        fun itemFor(target: ChatAppearanceStorage.AvatarTarget) =
            if (target == ChatAppearanceStorage.AvatarTarget.FRIEND) friendItem else userItem

        fun setItem(target: ChatAppearanceStorage.AvatarTarget, item: GalleryStorage.Item?) {
            if (target == ChatAppearanceStorage.AvatarTarget.FRIEND) friendItem = item else userItem = item
        }

        fun transformFor(target: ChatAppearanceStorage.AvatarTarget) =
            if (target == ChatAppearanceStorage.AvatarTarget.FRIEND) friendTransform else userTransform

        fun setTransform(
            target: ChatAppearanceStorage.AvatarTarget,
            transform: ChatAppearanceStorage.AvatarFrameTransform
        ) {
            if (target == ChatAppearanceStorage.AvatarTarget.FRIEND) {
                friendTransform = transform
            } else {
                userTransform = transform
            }
        }

        fun shapeFor(target: ChatAppearanceStorage.AvatarTarget) =
            if (target == ChatAppearanceStorage.AvatarTarget.FRIEND) friendShape else userShape
    }

    private data class SliderBinding(
        val label: TextView,
        val seekBar: SeekBar,
        val title: String,
        val min: Int,
        val suffix: String
    )

    private var backgroundEditorSession: BackgroundEditorSession? = null
    private var backgroundEditorRefresh: (() -> Unit)? = null
    private var avatarFrameEditorSession: AvatarFrameEditorSession? = null
    private var avatarFrameEditorRefresh: (() -> Unit)? = null
    private var avatarFrameImportTarget: ChatAppearanceStorage.AvatarTarget =
        ChatAppearanceStorage.AvatarTarget.USER

    private val backgroundPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) importBackgroundForEditor(uri)
    }

    private val avatarFramePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) importAvatarFrameForEditor(uri)
    }

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

    override fun onResume() {
        super.onResume()
        if (hasResumedOnce && ::settingsContainer.isInitialized) {
            buildSettings()
        } else {
            hasResumedOnce = true
        }
    }

    private fun buildSettings() {
        settingsContainer.removeAllViews()

        val friend = FriendStorage(this).getFriend(friendId)
        val chatStorage = ChatStorage(this)
        val msgCount = chatStorage.getMessageCount(friendId)

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

        // ===== 联网与搜索 =====
        addSectionTitle("联网与搜索")
        val searchGroupStorage = SearchGroupStorage(this)
        addClickItem(
            "联网与搜索权限",
            searchGroupStorage.residentSummary(friendId)
        ) {
            startActivity(Intent(this, SearchGroupActivity::class.java).apply {
                putExtra(SearchGroupActivity.EXTRA_FRIEND_ID, friendId)
                putExtra(SearchGroupActivity.EXTRA_FRIEND_NAME, friendName)
            })
        }

        // ===== 聊天外观 =====
        addSectionTitle("聊天外观")

        val backgroundItem = appearanceStorage.getBackgroundItem(friendId)
        val effects = appearanceStorage.getBackgroundEffects(friendId)
        val backgroundTransform = appearanceStorage.getBackgroundTransform(friendId)
        addClickItem(
            "聊天背景与显示效果",
            backgroundItem?.let {
                "当前：${it.displayName} · 缩放 ${backgroundTransform.scalePercent}% · " +
                    "位置 ${formatOffset(backgroundTransform.offsetXPercent, backgroundTransform.offsetYPercent)} · " +
                    "模糊 ${effects.blurRadius} · 遮罩 ${effects.overlayPercent}%"
            } ?: "当前使用默认背景 · 选择、构图、模糊与遮罩都在这里调整"
        ) {
            showBackgroundEditor()
        }

        val avatarMode = appearanceStorage.getAvatarDisplayMode(friendId)
        val friendShape = appearanceStorage.getAvatarShape(
            friendId,
            ChatAppearanceStorage.AvatarTarget.FRIEND
        )
        val userShape = appearanceStorage.getAvatarShape(
            friendId,
            ChatAppearanceStorage.AvatarTarget.USER
        )
        val friendFrameItem = appearanceStorage.getAvatarFrameItem(
            friendId,
            ChatAppearanceStorage.AvatarTarget.FRIEND
        )
        val userFrameItem = appearanceStorage.getAvatarFrameItem(
            friendId,
            ChatAppearanceStorage.AvatarTarget.USER
        )
        val initialAvatarTarget = if (avatarMode.showsFriendAvatar) {
            ChatAppearanceStorage.AvatarTarget.FRIEND
        } else {
            ChatAppearanceStorage.AvatarTarget.USER
        }

        addClickItem(
            "头像与头像框",
            "显示：${avatarDisplayModeLabel(avatarMode)} · " +
                "住户：${avatarShapeLabel(friendShape)}／${if (friendFrameItem == null) "无框" else "已戴框"} · " +
                "我：${avatarShapeLabel(userShape)}／${if (userFrameItem == null) "无框" else "已戴框"}"
        ) {
            showAvatarFrameEditor(initialAvatarTarget)
        }

        val bubbleStyleStorage = BubbleStyleStorage(this)
        val friendBubbleState = if (bubbleStyleStorage.hasCustomStyle(
                friendId,
                BubbleStyleStorage.Target.FRIEND
            )) "已保存样式" else "默认"
        val userBubbleState = if (bubbleStyleStorage.hasCustomStyle(
                friendId,
                BubbleStyleStorage.Target.USER
            )) "已保存样式" else "默认"
        addClickItem(
            "聊天气泡样式",
            "住户：$friendBubbleState · 我：$userBubbleState · 当前先做实时预览与保存"
        ) {
            startActivity(Intent(this, BubbleStyleEditorActivity::class.java).apply {
                putExtra("friend_id", friendId)
                putExtra("friend_name", friendName)
            })
        }

        val traceDividerStyle = appearanceStorage.getTraceDividerStyle(friendId)
        val traceDividerState = if (appearanceStorage.hasCustomTraceDividerStyle(friendId)) {
            "住户已自定义"
        } else {
            "知还默认"
        }
        addClickItem(
            "思考分割线样式",
            "$traceDividerState · ${traceDividerStyle.compactSummary()} · 只影响当前住户"
        ) {
            showTraceDividerEditor()
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


    private fun showTraceDividerEditor() {
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }
        var working = appearanceStorage.getTraceDividerStyle(friendId).normalized()
        var saveAsDefault = !appearanceStorage.hasCustomTraceDividerStyle(friendId)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(8))
        }

        content.addView(TextView(this).apply {
            text = "这是当前住户自己的思考、工具调用与潜意识记录分割线。没有自定义时，默认保留知还留下的 ʚ ───── ◇ ───── ɞ。"
            textSize = 12f
            setTextColor(c.textSecondary)
            setLineSpacing(0f, 1.45f)
            setPadding(0, 0, 0, dp(12))
        })

        val previewCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(c.card)
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), c.border)
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }
        previewCard.addView(TextView(this).apply {
            text = "实时预览"
            textSize = 11f
            setTextColor(c.textHint)
            setPadding(dp(2), 0, 0, dp(5))
        })

        val previewRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(38)
            )
        }

        fun previewDecoration(): TextView = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(26), dp(34))
            textSize = 18f
            gravity = Gravity.CENTER
            includeFontPadding = false
        }

        val previewLeft = previewDecoration()
        val previewLeftLine = TraceDividerLineView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(18), 1f)
            side = TraceDividerSide.LEFT
        }
        val previewCenter = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(34))
            textSize = 16f
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
        val previewRightLine = TraceDividerLineView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(18), 1f)
            side = TraceDividerSide.RIGHT
        }
        val previewRight = previewDecoration()
        previewRow.addView(previewLeft)
        previewRow.addView(previewLeftLine)
        previewRow.addView(previewCenter)
        previewRow.addView(previewRightLine)
        previewRow.addView(previewRight)
        previewCard.addView(previewRow)
        content.addView(previewCard)

        fun addFieldLabel(text: String) {
            content.addView(TextView(this).apply {
                this.text = text
                textSize = 12f
                setTextColor(c.textSecondary)
                setPadding(0, dp(7), 0, dp(3))
            })
        }

        fun makeDecorationInput(initial: String, hintText: String): EditText = EditText(this).apply {
            setText(initial)
            hint = hintText
            setTextColor(c.textPrimary)
            setHintTextColor(c.textHint)
            textSize = 14f
            isSingleLine = true
            filters = arrayOf<InputFilter>(InputFilter.LengthFilter(8))
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                setColor(c.backgroundSecondary)
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), c.border)
            }
        }

        addFieldLabel("左侧装饰")
        val leftInput = makeDecorationInput(working.leftDecoration, "例如 ʚ")
        content.addView(leftInput)

        addFieldLabel("右侧装饰")
        val rightInput = makeDecorationInput(working.rightDecoration, "例如 ɞ")
        content.addView(rightInput)

        addFieldLabel("中央符号")
        val centerInput = makeDecorationInput(working.centerMark, "例如 ◇")
        content.addView(centerInput)

        addFieldLabel("线条款式")
        val lineStyleButton = createEditorActionButton(working.lineStyle.displayName) {}
        content.addView(lineStyleButton)

        val glowBinding = addEditorSlider(
            container = content,
            title = "荧光强度",
            min = TraceDividerStyle.MIN_GLOW_PERCENT,
            max = TraceDividerStyle.MAX_GLOW_PERCENT,
            initial = working.glowPercent,
            suffix = "%"
        ) { value ->
            saveAsDefault = false
            working = working.copy(glowPercent = value).normalized()
            refreshTraceDividerPreview(
                working,
                previewLeft,
                previewLeftLine,
                previewCenter,
                previewRightLine,
                previewRight
            )
        }

        val thicknessBinding = addEditorSlider(
            container = content,
            title = "线条粗细",
            min = TraceDividerStyle.MIN_THICKNESS_DP,
            max = TraceDividerStyle.MAX_THICKNESS_DP,
            initial = working.thicknessDp,
            suffix = "dp"
        ) { value ->
            saveAsDefault = false
            working = working.copy(thicknessDp = value).normalized()
            refreshTraceDividerPreview(
                working,
                previewLeft,
                previewLeftLine,
                previewCenter,
                previewRightLine,
                previewRight
            )
        }

        val decorationToggle = createEditorActionButton("") {}
        content.addView(TextView(this).apply {
            text = "两端装饰"
            textSize = 12f
            setTextColor(c.textSecondary)
            setPadding(0, dp(10), 0, dp(3))
        })
        content.addView(decorationToggle)

        val resetButton = createEditorActionButton("恢复知还默认") {
            working = TraceDividerStyle.DEFAULT
            leftInput.setText(working.leftDecoration)
            rightInput.setText(working.rightDecoration)
            centerInput.setText(working.centerMark)
            setSliderValue(glowBinding, working.glowPercent)
            setSliderValue(thicknessBinding, working.thicknessDp)
            lineStyleButton.text = working.lineStyle.displayName
            decorationToggle.text = "✓ 显示两端装饰"
            saveAsDefault = true
            refreshTraceDividerPreview(
                working,
                previewLeft,
                previewLeftLine,
                previewCenter,
                previewRightLine,
                previewRight
            )
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) }
        }
        content.addView(resetButton)

        content.addView(TextView(this).apply {
            text = "恢复默认只会改当前住户；其他住户仍保留各自的选择。"
            textSize = 11f
            setTextColor(c.textHint)
            setPadding(0, dp(8), 0, dp(2))
        })

        fun syncTextInputs(markCustom: Boolean = true) {
            if (markCustom) saveAsDefault = false
            working = working.copy(
                leftDecoration = leftInput.text.toString(),
                rightDecoration = rightInput.text.toString(),
                centerMark = centerInput.text.toString()
            ).normalized()
            refreshTraceDividerPreview(
                working,
                previewLeft,
                previewLeftLine,
                previewCenter,
                previewRightLine,
                previewRight
            )
        }

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                syncTextInputs()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        }
        leftInput.addTextChangedListener(watcher)
        rightInput.addTextChangedListener(watcher)
        centerInput.addTextChangedListener(watcher)

        lineStyleButton.setOnClickListener {
            val styles = TraceDividerLineStyle.values()
            AlertDialog.Builder(this)
                .setTitle("线条款式")
                .setItems(styles.map { it.displayName }.toTypedArray()) { _, which ->
                    saveAsDefault = false
                    working = working.copy(lineStyle = styles[which]).normalized()
                    lineStyleButton.text = working.lineStyle.displayName
                    refreshTraceDividerPreview(
                        working,
                        previewLeft,
                        previewLeftLine,
                        previewCenter,
                        previewRightLine,
                        previewRight
                    )
                }
                .show()
        }

        fun refreshToggleText() {
            decorationToggle.text = if (working.showDecorations) {
                "✓ 显示两端装饰"
            } else {
                "不显示两端装饰"
            }
        }

        decorationToggle.setOnClickListener {
            saveAsDefault = false
            working = working.copy(showDecorations = !working.showDecorations)
            refreshToggleText()
            refreshTraceDividerPreview(
                working,
                previewLeft,
                previewLeftLine,
                previewCenter,
                previewRightLine,
                previewRight
            )
        }

        refreshToggleText()
        refreshTraceDividerPreview(
            working,
            previewLeft,
            previewLeftLine,
            previewCenter,
            previewRightLine,
            previewRight
        )

        val scroll = ScrollView(this).apply {
            addView(content)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("住户分割线美化")
            .setView(scroll)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                syncTextInputs(markCustom = !saveAsDefault)
                if (saveAsDefault) {
                    appearanceStorage.resetTraceDividerStyle(friendId)
                } else {
                    appearanceStorage.setTraceDividerStyle(friendId, working)
                }
                dialog.dismiss()
                markAppearanceChanged("住户分割线样式已保存")
            }
        }
        dialog.show()
    }

    private fun refreshTraceDividerPreview(
        style: TraceDividerStyle,
        leftDecoration: TextView,
        leftLine: TraceDividerLineView,
        center: TextView,
        rightLine: TraceDividerLineView,
        rightDecoration: TextView
    ) {
        val normalized = style.normalized()
        val dark = ThemeHelper.isDark(this)
        leftDecoration.text = normalized.leftDecoration
        rightDecoration.text = normalized.rightDecoration
        center.text = normalized.centerMark
        leftDecoration.visibility = if (normalized.showDecorations) View.VISIBLE else View.GONE
        rightDecoration.visibility = if (normalized.showDecorations) View.VISIBLE else View.GONE
        applyTraceDividerTextStyle(leftDecoration, normalized, dark, 0.55f)
        applyTraceDividerTextStyle(rightDecoration, normalized, dark, 0.55f)
        applyTraceDividerTextStyle(center, normalized, dark, 0.82f)
        leftLine.traceStyle = normalized
        leftLine.side = TraceDividerSide.LEFT
        leftLine.darkBackground = dark
        rightLine.traceStyle = normalized
        rightLine.side = TraceDividerSide.RIGHT
        rightLine.darkBackground = dark
    }

    private fun showBackgroundEditor() {
        val currentItem = appearanceStorage.getBackgroundItem(friendId)
        val session = BackgroundEditorSession(
            item = currentItem,
            effects = appearanceStorage.getBackgroundEffects(friendId),
            transform = currentItem?.let {
                appearanceStorage.getBackgroundTransform(friendId, it.id)
            } ?: ChatAppearanceStorage.BackgroundTransform()
        )
        backgroundEditorSession = session

        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(4))
        }

        val nameLabel = TextView(this).apply {
            textSize = 12f
            setTextColor(c.textSecondary)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(7))
        }
        val preview = BackgroundAppearancePreviewView(this).apply {
            background = GradientDrawable().apply {
                setColor(c.backgroundSecondary)
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), c.border)
            }
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            contentDescription = "聊天背景预览，可拖动调整位置"
        }
        container.addView(nameLabel)
        container.addView(preview, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(190)
        ))
        container.addView(TextView(this).apply {
            text = "可直接拖动上方预览调整位置；滑块用于精确构图。"
            textSize = 11f
            setTextColor(c.textHint)
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(6))
        })

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val chooseButton = createEditorActionButton("从画匣选择") {
            GalleryAssetPickerDialog.show(
                activity = this,
                category = GalleryStorage.Category.BACKGROUND,
                title = "从画匣选择聊天背景",
                selectedItemId = session.item?.id
            ) { item ->
                session.item = item
                session.transform = appearanceStorage.getBackgroundTransform(friendId, item.id)
                backgroundEditorRefresh?.invoke()
            }
        }
        val uploadButton = createEditorActionButton("从相册上传") {
            backgroundPicker.launch(arrayOf("image/*"))
        }
        val defaultButton = createEditorActionButton("使用默认背景") {
            session.item = null
            session.transform = ChatAppearanceStorage.BackgroundTransform()
            backgroundEditorRefresh?.invoke()
        }
        listOf(chooseButton, uploadButton, defaultButton).forEach { button ->
            actionRow.addView(button, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(3)
                marginEnd = dp(3)
            })
        }
        container.addView(actionRow)

        var suppressSliderCallbacks = false
        val scaleSlider = addEditorSlider(
            container = container,
            title = "背景缩放",
            min = ChatAppearanceStorage.MIN_BACKGROUND_SCALE_PERCENT,
            max = ChatAppearanceStorage.MAX_BACKGROUND_SCALE_PERCENT,
            initial = session.transform.scalePercent,
            suffix = "%"
        ) { value ->
            if (!suppressSliderCallbacks) {
                session.transform = session.transform.copy(scalePercent = value)
                preview.updateTransform(session.transform)
            }
        }
        val xSlider = addEditorSlider(
            container = container,
            title = "水平位置",
            min = ChatAppearanceStorage.MIN_BACKGROUND_OFFSET_PERCENT,
            max = ChatAppearanceStorage.MAX_BACKGROUND_OFFSET_PERCENT,
            initial = session.transform.offsetXPercent,
            suffix = "%"
        ) { value ->
            if (!suppressSliderCallbacks) {
                session.transform = session.transform.copy(offsetXPercent = value)
                preview.updateTransform(session.transform)
            }
        }
        val ySlider = addEditorSlider(
            container = container,
            title = "垂直位置",
            min = ChatAppearanceStorage.MIN_BACKGROUND_OFFSET_PERCENT,
            max = ChatAppearanceStorage.MAX_BACKGROUND_OFFSET_PERCENT,
            initial = session.transform.offsetYPercent,
            suffix = "%"
        ) { value ->
            if (!suppressSliderCallbacks) {
                session.transform = session.transform.copy(offsetYPercent = value)
                preview.updateTransform(session.transform)
            }
        }
        val blurSlider = addEditorSlider(
            container = container,
            title = "模糊度",
            min = ChatAppearanceStorage.MIN_BLUR_RADIUS,
            max = ChatAppearanceStorage.MAX_BLUR_RADIUS,
            initial = session.effects.blurRadius,
            suffix = ""
        ) { value ->
            if (!suppressSliderCallbacks) {
                session.effects = session.effects.copy(blurRadius = value)
                preview.updateBlur(value)
            }
        }
        val overlaySlider = addEditorSlider(
            container = container,
            title = "遮罩浓度",
            min = ChatAppearanceStorage.MIN_OVERLAY_PERCENT,
            max = ChatAppearanceStorage.MAX_OVERLAY_PERCENT,
            initial = session.effects.overlayPercent,
            suffix = "%"
        ) { value ->
            if (!suppressSliderCallbacks) {
                session.effects = session.effects.copy(overlayPercent = value)
                preview.updateOverlay(value)
            }
        }

        val resetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(3), 0, 0)
        }
        resetRow.addView(createEditorActionButton("构图归中") {
            session.transform = ChatAppearanceStorage.BackgroundTransform()
            backgroundEditorRefresh?.invoke()
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(4)
        })
        resetRow.addView(createEditorActionButton("全部恢复默认") {
            session.item = null
            session.effects = ChatAppearanceStorage.BackgroundEffects(
                ChatAppearanceStorage.DEFAULT_BLUR_RADIUS,
                ChatAppearanceStorage.DEFAULT_OVERLAY_PERCENT
            )
            session.transform = ChatAppearanceStorage.BackgroundTransform()
            backgroundEditorRefresh?.invoke()
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(4)
        })
        container.addView(resetRow)

        val refresh = refresh@{
            val active = backgroundEditorSession ?: return@refresh
            val file = active.item?.let(galleryStorage::fileFor)?.takeIf { it.isFile }
            nameLabel.text = active.item?.let { "当前：${it.displayName}" } ?: "当前：默认背景"
            val hasCustomBackground = file != null
            listOf(scaleSlider, xSlider, ySlider).forEach { it.seekBar.isEnabled = hasCustomBackground }

            suppressSliderCallbacks = true
            setSliderValue(scaleSlider, active.transform.scalePercent)
            setSliderValue(xSlider, active.transform.offsetXPercent)
            setSliderValue(ySlider, active.transform.offsetYPercent)
            setSliderValue(blurSlider, active.effects.blurRadius)
            setSliderValue(overlaySlider, active.effects.overlayPercent)
            suppressSliderCallbacks = false
            preview.setAppearance(file, active.effects, active.transform)
        }
        backgroundEditorRefresh = refresh
        preview.onTransformDragged = { dragged ->
            session.transform = dragged
            suppressSliderCallbacks = true
            setSliderValue(xSlider, dragged.offsetXPercent)
            setSliderValue(ySlider, dragged.offsetYPercent)
            suppressSliderCallbacks = false
        }
        refresh()

        val backgroundScroll = ScrollView(this).apply { addView(container) }
        val dialog = AlertDialog.Builder(this)
            .setTitle("与「$friendName」的聊天背景")
            .setView(backgroundScroll)
            .setNegativeButton("取消", null)
            .setPositiveButton("应用") { _, _ ->
                val active = backgroundEditorSession ?: return@setPositiveButton
                val item = active.item
                if (item == null) {
                    appearanceStorage.clearBackground(friendId)
                } else {
                    appearanceStorage.setBackground(friendId, item)
                    appearanceStorage.setBackgroundTransform(friendId, item.id, active.transform)
                }
                appearanceStorage.setBackgroundEffects(
                    friendId,
                    active.effects.blurRadius,
                    active.effects.overlayPercent
                )
                markAppearanceChanged("聊天背景与显示效果已保存")
            }
            .create()
        dialog.setOnDismissListener {
            backgroundEditorSession = null
            backgroundEditorRefresh = null
        }
        dialog.setOnShowListener {
            dialog.window?.setLayout(
                (resources.displayMetrics.widthPixels * 0.96f).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT
            )
        }
        dialog.show()
    }

    private fun showAvatarFrameEditor(
        initialTarget: ChatAppearanceStorage.AvatarTarget = ChatAppearanceStorage.AvatarTarget.USER
    ) {
        val friendItem = appearanceStorage.getAvatarFrameItem(
            friendId,
            ChatAppearanceStorage.AvatarTarget.FRIEND
        )
        val userItem = appearanceStorage.getAvatarFrameItem(
            friendId,
            ChatAppearanceStorage.AvatarTarget.USER
        )
        val session = AvatarFrameEditorSession(
            friendItem = friendItem,
            userItem = userItem,
            friendTransform = friendItem?.let {
                appearanceStorage.getAvatarFrameTransform(
                    friendId,
                    it.id,
                    ChatAppearanceStorage.AvatarTarget.FRIEND
                )
            } ?: ChatAppearanceStorage.AvatarFrameTransform(),
            userTransform = userItem?.let {
                appearanceStorage.getAvatarFrameTransform(
                    friendId,
                    it.id,
                    ChatAppearanceStorage.AvatarTarget.USER
                )
            } ?: ChatAppearanceStorage.AvatarFrameTransform(),
            displayMode = appearanceStorage.getAvatarDisplayMode(friendId),
            friendShape = appearanceStorage.getAvatarShape(
                friendId,
                ChatAppearanceStorage.AvatarTarget.FRIEND
            ),
            userShape = appearanceStorage.getAvatarShape(
                friendId,
                ChatAppearanceStorage.AvatarTarget.USER
            ),
            previewTarget = initialTarget
        )
        avatarFrameEditorSession = session

        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }
        val friend = FriendStorage(this).getFriend(friendId)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }

        fun addMiniTitle(text: String) {
            container.addView(TextView(this).apply {
                this.text = text
                textSize = 12f
                setTextColor(c.textPrimary)
                setPadding(0, dp(8), 0, dp(4))
            })
        }

        fun setCurrentShape(shape: ChatAppearanceStorage.AvatarShape) {
            if (session.previewTarget == ChatAppearanceStorage.AvatarTarget.FRIEND) {
                session.friendShape = shape
            } else {
                session.userShape = shape
            }
        }

        addMiniTitle("正在调整")
        val targetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        lateinit var friendTargetButton: TextView
        lateinit var userTargetButton: TextView
        friendTargetButton = createEditorActionButton("住户") {
            session.previewTarget = ChatAppearanceStorage.AvatarTarget.FRIEND
            avatarFrameEditorRefresh?.invoke()
        }
        userTargetButton = createEditorActionButton("我") {
            session.previewTarget = ChatAppearanceStorage.AvatarTarget.USER
            avatarFrameEditorRefresh?.invoke()
        }
        listOf(friendTargetButton, userTargetButton).forEach { button ->
            targetRow.addView(
                button,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(3)
                    marginEnd = dp(3)
                }
            )
        }
        container.addView(targetRow)

        val nameLabel = TextView(this).apply {
            textSize = 12f
            setTextColor(c.textSecondary)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(5))
        }
        val previewHost = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(c.backgroundSecondary)
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), c.border)
            }
            clipChildren = false
            clipToPadding = false
        }
        container.addView(nameLabel)
        container.addView(
            previewHost,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(190))
        )
        container.addView(TextView(this).apply {
            text = "拖动预览可移动当前对象的头像框；头像本体不会跟着移动。"
            textSize = 11f
            setTextColor(c.textHint)
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(2))
        })

        val permissionHint = TextView(this).apply {
            textSize = 11f
            setTextColor(c.textHint)
            setPadding(dp(4), dp(4), dp(4), dp(2))
        }
        container.addView(permissionHint)

        addMiniTitle("聊天里显示谁的头像")
        val displayRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        lateinit var aiOnlyButton: TextView
        lateinit var userOnlyButton: TextView
        lateinit var bothButton: TextView
        lateinit var noneButton: TextView
        aiOnlyButton = createEditorActionButton("仅住户") {
            session.displayMode = ChatAppearanceStorage.AvatarDisplayMode.AI_ONLY
            avatarFrameEditorRefresh?.invoke()
        }
        userOnlyButton = createEditorActionButton("仅我") {
            session.displayMode = ChatAppearanceStorage.AvatarDisplayMode.USER_ONLY
            avatarFrameEditorRefresh?.invoke()
        }
        bothButton = createEditorActionButton("双方") {
            session.displayMode = ChatAppearanceStorage.AvatarDisplayMode.BOTH
            avatarFrameEditorRefresh?.invoke()
        }
        noneButton = createEditorActionButton("都不显示") {
            session.displayMode = ChatAppearanceStorage.AvatarDisplayMode.NONE
            avatarFrameEditorRefresh?.invoke()
        }
        listOf(aiOnlyButton, userOnlyButton, bothButton, noneButton).forEach { button ->
            displayRow.addView(
                button,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(3)
                    marginEnd = dp(3)
                }
            )
        }
        container.addView(displayRow)

        addMiniTitle("当前对象的头像形状")
        val shapeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        lateinit var circleShapeButton: TextView
        lateinit var squareShapeButton: TextView
        circleShapeButton = createEditorActionButton("圆形") {
            setCurrentShape(ChatAppearanceStorage.AvatarShape.CIRCLE)
            avatarFrameEditorRefresh?.invoke()
        }
        squareShapeButton = createEditorActionButton("方形") {
            setCurrentShape(ChatAppearanceStorage.AvatarShape.SQUARE)
            avatarFrameEditorRefresh?.invoke()
        }
        listOf(circleShapeButton, squareShapeButton).forEach { button ->
            shapeRow.addView(
                button,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(3)
                    marginEnd = dp(3)
                }
            )
        }
        container.addView(shapeRow)

        addMiniTitle("当前对象的头像框")
        val frameStatusLabel = TextView(this).apply {
            textSize = 11f
            setTextColor(c.textSecondary)
            setPadding(dp(4), 0, dp(4), dp(4))
        }
        container.addView(frameStatusLabel)

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        lateinit var chooseButton: TextView
        lateinit var uploadButton: TextView
        lateinit var clearButton: TextView
        chooseButton = createEditorActionButton("从画匣选择") {
            if (session.previewTarget != ChatAppearanceStorage.AvatarTarget.USER) {
                return@createEditorActionButton
            }
            GalleryAssetPickerDialog.show(
                activity = this,
                category = GalleryStorage.Category.AVATAR_FRAME,
                title = "选择我的头像框",
                selectedItemId = session.userItem?.id
            ) { item ->
                session.userItem = item
                session.userTransform = appearanceStorage.getAvatarFrameTransform(
                    friendId,
                    item.id,
                    ChatAppearanceStorage.AvatarTarget.USER
                )
                avatarFrameEditorRefresh?.invoke()
            }
        }
        uploadButton = createEditorActionButton("从相册上传") {
            avatarFrameImportTarget = session.previewTarget
            avatarFramePicker.launch(arrayOf("image/*"))
        }
        clearButton = createEditorActionButton("不使用头像框") {
            if (session.previewTarget != ChatAppearanceStorage.AvatarTarget.USER) {
                return@createEditorActionButton
            }
            session.userItem = null
            session.userTransform = ChatAppearanceStorage.AvatarFrameTransform()
            avatarFrameEditorRefresh?.invoke()
        }
        listOf(chooseButton, uploadButton, clearButton).forEach { button ->
            actionRow.addView(
                button,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(3)
                    marginEnd = dp(3)
                }
            )
        }
        container.addView(actionRow)

        var suppressSliderCallbacks = false
        var previewFrameLayer: View? = null
        var previewRoot: View? = null
        val previewAvatarSizeDp = 72
        val previewAvatarSizePx = dp(previewAvatarSizeDp)

        fun currentTransform(): ChatAppearanceStorage.AvatarFrameTransform =
            session.transformFor(session.previewTarget)

        fun setCurrentTransform(transform: ChatAppearanceStorage.AvatarFrameTransform) {
            session.setTransform(session.previewTarget, transform)
        }

        fun applyPreviewTransform() {
            val layer = previewFrameLayer ?: return
            val root = previewRoot as? FrameLayout ?: return
            val transform = currentTransform()
            val geometry = FriendAvatarHelper.calculateStageGeometry(previewAvatarSizePx, transform)
            val rootParams = (root.layoutParams as? FrameLayout.LayoutParams)
                ?: FrameLayout.LayoutParams(geometry.widthPx, geometry.heightPx, Gravity.CENTER)
            rootParams.width = geometry.widthPx
            rootParams.height = geometry.heightPx
            rootParams.gravity = Gravity.CENTER
            root.layoutParams = rootParams

            for (index in 0 until root.childCount) {
                val child = root.getChildAt(index)
                val childParams = (child.layoutParams as? FrameLayout.LayoutParams)
                    ?: FrameLayout.LayoutParams(previewAvatarSizePx, previewAvatarSizePx)
                childParams.width = previewAvatarSizePx
                childParams.height = previewAvatarSizePx
                childParams.leftMargin = geometry.avatarLeftPx
                childParams.topMargin = geometry.avatarTopPx
                child.layoutParams = childParams
            }

            val scale = transform.scalePercent / 100f
            layer.scaleX = scale
            layer.scaleY = scale
            layer.translationX = previewAvatarSizePx * transform.offsetXPercent / 100f
            layer.translationY = previewAvatarSizePx * transform.offsetYPercent / 100f
            root.requestLayout()
        }

        val scaleSlider = addEditorSlider(
            container,
            "头像框大小",
            ChatAppearanceStorage.MIN_AVATAR_FRAME_SCALE_PERCENT,
            ChatAppearanceStorage.MAX_AVATAR_FRAME_SCALE_PERCENT,
            currentTransform().scalePercent,
            "%"
        ) { value ->
            if (!suppressSliderCallbacks) {
                setCurrentTransform(currentTransform().copy(scalePercent = value))
                applyPreviewTransform()
            }
        }
        val xSlider = addEditorSlider(
            container,
            "水平位置",
            ChatAppearanceStorage.MIN_AVATAR_FRAME_OFFSET_PERCENT,
            ChatAppearanceStorage.MAX_AVATAR_FRAME_OFFSET_PERCENT,
            currentTransform().offsetXPercent,
            "%"
        ) { value ->
            if (!suppressSliderCallbacks) {
                setCurrentTransform(currentTransform().copy(offsetXPercent = value))
                applyPreviewTransform()
            }
        }
        val ySlider = addEditorSlider(
            container,
            "垂直位置",
            ChatAppearanceStorage.MIN_AVATAR_FRAME_OFFSET_PERCENT,
            ChatAppearanceStorage.MAX_AVATAR_FRAME_OFFSET_PERCENT,
            currentTransform().offsetYPercent,
            "%"
        ) { value ->
            if (!suppressSliderCallbacks) {
                setCurrentTransform(currentTransform().copy(offsetYPercent = value))
                applyPreviewTransform()
            }
        }

        val resetButton = createEditorActionButton("重置当前对象的框位置与大小") {
            setCurrentTransform(ChatAppearanceStorage.AvatarFrameTransform())
            avatarFrameEditorRefresh?.invoke()
        }
        container.addView(
            resetButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        )

        val refresh = refresh@{
            val active = avatarFrameEditorSession ?: return@refresh
            val editingFriend =
                active.previewTarget == ChatAppearanceStorage.AvatarTarget.FRIEND

            friendTargetButton.text = if (editingFriend) "✓ 住户" else "住户"
            userTargetButton.text = if (editingFriend) "我" else "✓ 我"

            aiOnlyButton.text =
                if (active.displayMode == ChatAppearanceStorage.AvatarDisplayMode.AI_ONLY) {
                    "✓ 仅住户"
                } else {
                    "仅住户"
                }
            userOnlyButton.text =
                if (active.displayMode == ChatAppearanceStorage.AvatarDisplayMode.USER_ONLY) {
                    "✓ 仅我"
                } else {
                    "仅我"
                }
            bothButton.text =
                if (active.displayMode == ChatAppearanceStorage.AvatarDisplayMode.BOTH) {
                    "✓ 双方"
                } else {
                    "双方"
                }
            noneButton.text =
                if (active.displayMode == ChatAppearanceStorage.AvatarDisplayMode.NONE) {
                    "✓ 都不显示"
                } else {
                    "都不显示"
                }

            val shape = active.shapeFor(active.previewTarget)
            circleShapeButton.text =
                if (shape == ChatAppearanceStorage.AvatarShape.CIRCLE) "✓ 圆形" else "圆形"
            squareShapeButton.text =
                if (shape == ChatAppearanceStorage.AvatarShape.SQUARE) "✓ 方形" else "方形"

            permissionHint.text = if (editingFriend) {
                "住户头像框由住户自己选择或摘下。这里共用同一套编辑器，只查看和微调它当前佩戴的框；上传只会补充共享画匣素材。"
            } else {
                "这里正在编辑我的头像与头像框；底层数据与住户完全独立。"
            }

            chooseButton.visibility = if (editingFriend) View.GONE else View.VISIBLE
            clearButton.visibility = if (editingFriend) View.GONE else View.VISIBLE
            uploadButton.text =
                if (editingFriend) "上传素材到共享画匣" else "从相册上传"

            previewHost.removeAllViews()
            val item = active.itemFor(active.previewTarget)
            val frameFile = item?.let(galleryStorage::fileFor)?.takeIf { it.isFile }
            val targetLabel = if (editingFriend) friendName else "我"
            nameLabel.text = "当前预览：$targetLabel"
            frameStatusLabel.text = buildString {
                append("当前：")
                item?.let { append(it.displayName) } ?: append("不使用头像框")
                if (editingFriend) append(" · 选框和摘框由住户自己决定")
            }

            val transform = active.transformFor(active.previewTarget)
            val avatarView = if (editingFriend) {
                FriendAvatarHelper.create(
                    context = this,
                    avatarPath = friend?.avatarPath.orEmpty(),
                    icon = friend?.icon ?: "★",
                    sizeDp = previewAvatarSizeDp,
                    framePath = frameFile?.absolutePath.orEmpty(),
                    frameScalePercent = transform.scalePercent,
                    frameOffsetXPercent = transform.offsetXPercent,
                    frameOffsetYPercent = transform.offsetYPercent,
                    avatarShape = active.friendShape
                )
            } else {
                FriendAvatarHelper.createUserAvatar(
                    context = this,
                    sizeDp = previewAvatarSizeDp,
                    framePath = frameFile?.absolutePath.orEmpty(),
                    frameScalePercent = transform.scalePercent,
                    frameOffsetXPercent = transform.offsetXPercent,
                    frameOffsetYPercent = transform.offsetYPercent,
                    avatarShape = active.userShape
                )
            }
            previewRoot = avatarView
            previewFrameLayer = (avatarView as? android.view.ViewGroup)?.getChildAt(1)
            val avatarLayout = avatarView.layoutParams
            previewHost.addView(
                avatarView,
                FrameLayout.LayoutParams(
                    avatarLayout?.width ?: previewAvatarSizePx,
                    avatarLayout?.height ?: previewAvatarSizePx,
                    Gravity.CENTER
                )
            )

            var downX = 0f
            var downY = 0f
            var startTransform = transform
            var dragged = false
            avatarView.setOnTouchListener { _, event ->
                if (item == null) return@setOnTouchListener false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        avatarView.parent?.requestDisallowInterceptTouchEvent(true)
                        downX = event.rawX
                        downY = event.rawY
                        startTransform = currentTransform()
                        dragged = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY
                        if (!dragged && (abs(dx) > dp(4) || abs(dy) > dp(4))) {
                            dragged = true
                        }
                        setCurrentTransform(
                            startTransform.copy(
                                offsetXPercent = (
                                    startTransform.offsetXPercent +
                                        (dx / previewAvatarSizePx * 100f).roundToInt()
                                    ).coerceIn(
                                    ChatAppearanceStorage.MIN_AVATAR_FRAME_OFFSET_PERCENT,
                                    ChatAppearanceStorage.MAX_AVATAR_FRAME_OFFSET_PERCENT
                                ),
                                offsetYPercent = (
                                    startTransform.offsetYPercent +
                                        (dy / previewAvatarSizePx * 100f).roundToInt()
                                    ).coerceIn(
                                    ChatAppearanceStorage.MIN_AVATAR_FRAME_OFFSET_PERCENT,
                                    ChatAppearanceStorage.MAX_AVATAR_FRAME_OFFSET_PERCENT
                                )
                            )
                        )
                        applyPreviewTransform()
                        suppressSliderCallbacks = true
                        setSliderValue(xSlider, currentTransform().offsetXPercent)
                        setSliderValue(ySlider, currentTransform().offsetYPercent)
                        suppressSliderCallbacks = false
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        avatarView.parent?.requestDisallowInterceptTouchEvent(false)
                        if (!dragged && event.actionMasked == MotionEvent.ACTION_UP) {
                            avatarView.performClick()
                        }
                        true
                    }
                    else -> false
                }
            }

            val frameEnabled = frameFile != null
            listOf(scaleSlider, xSlider, ySlider).forEach {
                it.seekBar.isEnabled = frameEnabled
                it.seekBar.alpha = if (frameEnabled) 1f else 0.45f
            }
            resetButton.isEnabled = frameEnabled
            resetButton.alpha = if (frameEnabled) 1f else 0.45f
            suppressSliderCallbacks = true
            setSliderValue(scaleSlider, transform.scalePercent)
            setSliderValue(xSlider, transform.offsetXPercent)
            setSliderValue(ySlider, transform.offsetYPercent)
            suppressSliderCallbacks = false
            applyPreviewTransform()
        }
        avatarFrameEditorRefresh = refresh
        refresh()

        val avatarFrameScroll = ScrollView(this).apply { addView(container) }
        val dialog = AlertDialog.Builder(this)
            .setTitle("头像与头像框")
            .setView(avatarFrameScroll)
            .setNegativeButton("取消", null)
            .setPositiveButton("应用") { _, _ ->
                val active = avatarFrameEditorSession ?: return@setPositiveButton
                appearanceStorage.setAvatarDisplayMode(friendId, active.displayMode)
                appearanceStorage.setAvatarShape(
                    friendId,
                    ChatAppearanceStorage.AvatarTarget.FRIEND,
                    active.friendShape
                )
                appearanceStorage.setAvatarShape(
                    friendId,
                    ChatAppearanceStorage.AvatarTarget.USER,
                    active.userShape
                )

                // 住户头像框素材仍由住户自主决定，这里只保存其当前框的微调参数。
                active.friendItem?.let { item ->
                    appearanceStorage.setAvatarFrameTransform(
                        friendId,
                        item.id,
                        active.friendTransform,
                        ChatAppearanceStorage.AvatarTarget.FRIEND
                    )
                }

                val userFrame = active.userItem
                if (userFrame == null) {
                    appearanceStorage.clearAvatarFrame(
                        friendId,
                        ChatAppearanceStorage.AvatarTarget.USER
                    )
                } else {
                    appearanceStorage.setAvatarFrame(
                        friendId,
                        userFrame,
                        ChatAppearanceStorage.AvatarTarget.USER
                    )
                    appearanceStorage.setAvatarFrameTransform(
                        friendId,
                        userFrame.id,
                        active.userTransform,
                        ChatAppearanceStorage.AvatarTarget.USER
                    )
                }
                markAppearanceChanged("头像与头像框已保存")
            }
            .create()
        dialog.setOnDismissListener {
            avatarFrameEditorSession = null
            avatarFrameEditorRefresh = null
            previewRoot = null
            previewFrameLayer = null
        }
        dialog.setOnShowListener {
            dialog.window?.setLayout(
                (resources.displayMetrics.widthPixels * 0.96f).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT
            )
        }
        dialog.show()
    }

    private fun importBackgroundForEditor(uri: Uri) {
        if (backgroundEditorSession == null) return
        Toast.makeText(this, "正在放进画匣…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val item = galleryStorage.importImage(uri, GalleryStorage.Category.BACKGROUND)
                runOnUiThread {
                    val active = backgroundEditorSession
                    if (!isFinishing && !isDestroyed && active != null) {
                        active.item = item
                        active.transform = appearanceStorage.getBackgroundTransform(friendId, item.id)
                        backgroundEditorRefresh?.invoke()
                        Toast.makeText(this, "背景已存进画匣，可继续调整", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        Toast.makeText(this, "导入失败：${e.message ?: "无法读取图片"}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }.start()
    }

    private fun importAvatarFrameForEditor(uri: Uri) {
        if (avatarFrameEditorSession == null) return
        val target = avatarFrameImportTarget
        Toast.makeText(this, "正在放进画匣…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val item = galleryStorage.importImage(uri, GalleryStorage.Category.AVATAR_FRAME)
                runOnUiThread {
                    val active = avatarFrameEditorSession
                    if (!isFinishing && !isDestroyed && active != null) {
                        if (target == ChatAppearanceStorage.AvatarTarget.USER) {
                            active.userItem = item
                            active.userTransform = appearanceStorage.getAvatarFrameTransform(
                                friendId,
                                item.id,
                                ChatAppearanceStorage.AvatarTarget.USER
                            )
                            avatarFrameEditorRefresh?.invoke()
                            Toast.makeText(
                                this,
                                "头像框已存进画匣，并放进我的预览",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            Toast.makeText(
                                this,
                                "素材已存进共享画匣，住户之后可以自己挑选",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        Toast.makeText(this, "导入失败：${e.message ?: "无法读取图片"}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }.start()
    }

    private fun createEditorActionButton(text: String, onClick: () -> Unit): TextView {
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }
        return TextView(this).apply {
            this.text = text
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(c.textPrimary)
            setPadding(dp(5), dp(9), dp(5), dp(9))
            background = GradientDrawable().apply {
                setColor(c.card)
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), c.border)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun addEditorSlider(
        container: LinearLayout,
        title: String,
        min: Int,
        max: Int,
        initial: Int,
        suffix: String,
        onValueChanged: (Int) -> Unit
    ): SliderBinding {
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }
        val normalized = initial.coerceIn(min, max)
        val label = TextView(this).apply {
            text = "$title：$normalized$suffix"
            textSize = 12f
            setTextColor(c.textPrimary)
            setPadding(0, dp(7), 0, 0)
        }
        val seekBar = SeekBar(this).apply {
            this.max = max - min
            progress = normalized - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val value = progress + min
                    label.text = "$title：$value$suffix"
                    onValueChanged(value)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        container.addView(label)
        container.addView(seekBar)
        return SliderBinding(label, seekBar, title, min, suffix)
    }

    private fun setSliderValue(binding: SliderBinding, value: Int) {
        val normalized = (value - binding.min).coerceIn(0, binding.seekBar.max)
        binding.seekBar.progress = normalized
        binding.label.text = "${binding.title}：${normalized + binding.min}${binding.suffix}"
    }

    private fun formatOffset(x: Int, y: Int): String =
        "X${if (x >= 0) "+" else ""}$x% / Y${if (y >= 0) "+" else ""}$y%"

    private fun avatarDisplayModeLabel(mode: ChatAppearanceStorage.AvatarDisplayMode): String =
        when (mode) {
            ChatAppearanceStorage.AvatarDisplayMode.AI_ONLY -> "仅住户"
            ChatAppearanceStorage.AvatarDisplayMode.USER_ONLY -> "仅我"
            ChatAppearanceStorage.AvatarDisplayMode.BOTH -> "双方"
            ChatAppearanceStorage.AvatarDisplayMode.NONE -> "都不显示"
        }

    private fun avatarShapeLabel(shape: ChatAppearanceStorage.AvatarShape): String =
        when (shape) {
            ChatAppearanceStorage.AvatarShape.CIRCLE -> "圆形"
            ChatAppearanceStorage.AvatarShape.SQUARE -> "方形"
        }

    private fun markAppearanceChanged(message: String) {
        setResult(RESULT_OK)
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        buildSettings()
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