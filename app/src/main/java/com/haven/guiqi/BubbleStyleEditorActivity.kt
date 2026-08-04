package com.haven.guiqi

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * v11.9 气泡样式编辑器。
 * 支持普通颜色、图片、安全代码与图片叠加代码，并直接接入真实聊天渲染。
 */
class BubbleStyleEditorActivity : AppCompatActivity() {

    private val c get() = ThemeHelper.getColors(this)
    private val storage by lazy { BubbleStyleStorage(this) }
    private val imageAssets by lazy { BubbleImageAssetStorage(this) }

    private var friendId = ""
    private var friendName = "住户"
    private var target = BubbleStyleStorage.Target.FRIEND
    private lateinit var friendStyle: BubbleStyleStorage.BubbleStyle
    private lateinit var userStyle: BubbleStyleStorage.BubbleStyle
    private lateinit var savedFriendStyle: BubbleStyleStorage.BubbleStyle
    private lateinit var savedUserStyle: BubbleStyleStorage.BubbleStyle
    private var imagePickerTarget: BubbleStyleStorage.Target? = null
    private var imagePickerRequestedMode = BubbleStyleStorage.FillMode.IMAGE
    private var friendDirty = false
    private var userDirty = false

    private lateinit var preview: BubbleStylePreviewView
    private lateinit var controlsContainer: LinearLayout
    private lateinit var friendTargetButton: TextView
    private lateinit var userTargetButton: TextView
    private lateinit var shortPreviewButton: TextView
    private lateinit var longPreviewButton: TextView
    private var longMessagePreview = true
    private lateinit var statusLabel: TextView

    private val imagePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val pickedTarget = imagePickerTarget
        imagePickerTarget = null
        if (uri == null || pickedTarget == null) return@registerForActivityResult

        runCatching { imageAssets.importImage(uri, friendId, pickedTarget) }
            .onSuccess { file ->
                val previous = styleFor(pickedTarget)
                deleteUnsavedImage(previous.imagePath, savedStyleFor(pickedTarget).imagePath)
                setStyleFor(
                    pickedTarget,
                    previous.copy(
                        fillMode = imagePickerRequestedMode,
                        imagePath = file.absolutePath,
                        // 图片＋代码模式先安全载入图片；示例代码只在用户主动
                        // 打开编辑器时出现，不在 Activity 重建过程中自动写入。
                        codeCss = previous.codeCss
                    )
                )
                markDirty(pickedTarget)
                renderTarget()
                Toast.makeText(this, "图片气泡素材已载入", Toast.LENGTH_SHORT).show()
            }
            .onFailure { error ->
                Toast.makeText(this, error.message ?: "图片气泡载入失败", Toast.LENGTH_LONG).show()
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        friendId = intent.getStringExtra("friend_id").orEmpty()
        friendName = intent.getStringExtra("friend_name") ?: "住户"
        friendStyle = storage.getStyle(friendId, BubbleStyleStorage.Target.FRIEND)
        userStyle = storage.getStyle(friendId, BubbleStyleStorage.Target.USER)
        savedFriendStyle = friendStyle
        savedUserStyle = userStyle

        configureWindow()
        setContentView(buildContent())
        renderTarget()
    }

    private fun configureWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars = !ThemeHelper.isDark(this)
        controller.isAppearanceLightNavigationBars = !ThemeHelper.isDark(this)
    }

    private fun buildContent(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(c.background)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        root.addView(buildTopBar(), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(58)
        ))

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(28))
        }
        scroll.addView(content, FrameLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        content.addView(TextView(this).apply {
            text = "普通、图片与安全代码气泡都会直接应用到真实聊天。代码只解析白名单样式，不会执行脚本、联网或读取文件；图片保真画框仍只拉伸中央空白带。"
            textSize = 12f
            setTextColor(c.textSecondary)
            setPadding(dp(13), dp(11), dp(13), dp(11))
            background = roundedBackground(c.accentBg, dp(12).toFloat(), c.border)
        })

        val targetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(10))
        }
        friendTargetButton = createTargetButton("住户") {
            target = BubbleStyleStorage.Target.FRIEND
            renderTarget()
        }
        userTargetButton = createTargetButton("我") {
            target = BubbleStyleStorage.Target.USER
            renderTarget()
        }
        targetRow.addView(friendTargetButton, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
            marginEnd = dp(5)
        })
        targetRow.addView(userTargetButton, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
            marginStart = dp(5)
        })
        content.addView(targetRow)

        preview = BubbleStylePreviewView(this).apply {
            background = roundedBackground(c.backgroundSecondary, dp(16).toFloat(), c.border)
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            bindConversation(friendId, friendName)
        }
        content.addView(preview, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(420)
        ))

        val previewLengthRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        shortPreviewButton = createTargetButton("短消息预览") {
            longMessagePreview = false
            preview.setLongMessagePreview(false)
            updatePreviewLengthButtons()
        }
        longPreviewButton = createTargetButton("长消息实装预览") {
            longMessagePreview = true
            preview.setLongMessagePreview(true)
            updatePreviewLengthButtons()
        }
        previewLengthRow.addView(shortPreviewButton, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
            marginEnd = dp(5)
        })
        previewLengthRow.addView(longPreviewButton, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
            marginStart = dp(5)
        })
        content.addView(previewLengthRow)
        updatePreviewLengthButtons()

        statusLabel = TextView(this).apply {
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(c.textHint)
            setPadding(0, dp(7), 0, dp(7))
        }
        content.addView(statusLabel)

        controlsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(controlsContainer)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, 0)
        }
        actions.addView(createActionButton("保存两边样式", primary = true) {
            if (validateCodeStylesForSave()) {
                persistTarget(BubbleStyleStorage.Target.FRIEND, friendStyle)
                persistTarget(BubbleStyleStorage.Target.USER, userStyle)
                friendDirty = false
                userDirty = false
                updateStatusLabel()
                Toast.makeText(this, "气泡样式已保存", Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(5) })
        actions.addView(createActionButton("两边恢复默认", primary = false) {
            AlertDialog.Builder(this)
                .setTitle("恢复两边默认样式？")
                .setMessage("住户和我的预览设置都会恢复，但不会影响头像、背景或气泡排版参数。")
                .setNegativeButton("取消", null)
                .setPositiveButton("恢复") { _, _ ->
                    deleteUnsavedImage(friendStyle.imagePath, savedFriendStyle.imagePath)
                    deleteUnsavedImage(userStyle.imagePath, savedUserStyle.imagePath)
                    storage.resetStyle(friendId, BubbleStyleStorage.Target.FRIEND)
                    storage.resetStyle(friendId, BubbleStyleStorage.Target.USER)
                    friendStyle = storage.defaultStyle(BubbleStyleStorage.Target.FRIEND)
                    userStyle = storage.defaultStyle(BubbleStyleStorage.Target.USER)
                    savedFriendStyle = friendStyle
                    savedUserStyle = userStyle
                    friendDirty = false
                    userDirty = false
                    renderTarget()
                    Toast.makeText(this, "已恢复默认样式", Toast.LENGTH_SHORT).show()
                }
                .show()
        }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(5) })
        content.addView(actions)

        return root
    }

    private fun buildTopBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(14), 0)
            setBackgroundColor(c.background)
        }
        val back = TextView(this).apply {
            text = "‹"
            textSize = 38f
            gravity = Gravity.CENTER
            setTextColor(c.textPrimary)
            setOnClickListener { requestClose() }
            contentDescription = "返回"
        }
        bar.addView(back, LinearLayout.LayoutParams(dp(48), LinearLayout.LayoutParams.MATCH_PARENT))
        val titleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleBox.addView(TextView(this).apply {
            text = "聊天气泡样式"
            textSize = 18f
            setTextColor(c.textPrimary)
        })
        titleBox.addView(TextView(this).apply {
            text = "${friendName} · v11.9 安全代码气泡"
            textSize = 11f
            setTextColor(c.textSecondary)
        })
        bar.addView(titleBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return bar
    }

    private fun renderTarget() {
        updateTargetButton(friendTargetButton, target == BubbleStyleStorage.Target.FRIEND)
        updateTargetButton(userTargetButton, target == BubbleStyleStorage.Target.USER)
        preview.setPreview(friendStyle, userStyle, target)
        preview.setLongMessagePreview(longMessagePreview)
        updatePreviewLengthButtons()
        updateStatusLabel()

        controlsContainer.removeAllViews()
        if (target == BubbleStyleStorage.Target.FRIEND) {
            addResidentDraftCard()
        }
        addSectionLabel("气泡类型")
        addModeSelector()

        when (currentStyle().fillMode) {
            BubbleStyleStorage.FillMode.BASIC -> addBasicModeControls()
            BubbleStyleStorage.FillMode.IMAGE -> addImageModeControls()
            BubbleStyleStorage.FillMode.CODE -> addCodeModeControls()
            BubbleStyleStorage.FillMode.IMAGE_CODE -> {
                addImageModeControls()
                addCodeModeControls()
            }
        }

        if (!currentStyle().fillMode.usesCode) {
            addSectionLabel("文字")
            addColorRow("文字颜色", "普通和图片气泡都会使用", currentStyle().textColor, true) { color ->
                updateCurrent { it.copy(textColor = color) }
            }

            addSectionLabel("阴影")
            addSlider(
                "阴影扩散",
                BubbleStyleStorage.MIN_SHADOW_RADIUS_DP,
                BubbleStyleStorage.MAX_SHADOW_RADIUS_DP,
                currentStyle().shadowRadiusDp,
                "dp"
            ) { value -> updateCurrent { it.copy(shadowRadiusDp = value) } }
            addSlider(
                "阴影浓度",
                BubbleStyleStorage.MIN_SHADOW_OPACITY_PERCENT,
                BubbleStyleStorage.MAX_SHADOW_OPACITY_PERCENT,
                currentStyle().shadowOpacityPercent,
                "%"
            ) { value -> updateCurrent { it.copy(shadowOpacityPercent = value) } }
        }

        val resetCurrent = createActionButton(
            if (target == BubbleStyleStorage.Target.FRIEND) "住户气泡恢复默认" else "我的气泡恢复默认",
            primary = false
        ) {
            val old = currentStyle()
            deleteUnsavedImage(old.imagePath, savedStyleFor(target).imagePath)
            setCurrentStyle(storage.defaultStyle(target))
            markCurrentDirty()
            renderTarget()
        }
        controlsContainer.addView(resetCurrent, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(44)
        ).apply { topMargin = dp(12) })
    }

    /**
     * 住户提交的代码只能先成为候选草稿。这里把它显示给人类，
     * 但不会为了预览而改动当前生效样式或当前编辑状态。
     */
    private fun addResidentDraftCard() {
        val draft = storage.getResidentCodeDraft(friendId) ?: return
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(13), dp(14), dp(13))
            background = roundedBackground(c.accentBg, dp(14).toFloat(), c.accentStrong)
        }
        card.addView(TextView(this).apply {
            text = "$friendName 提交了一份代码气泡草稿"
            textSize = 14f
            setTextColor(c.textPrimary)
        })
        card.addView(TextView(this).apply {
            val created = if (draft.createdAt > 0L) {
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    .format(Date(draft.createdAt))
            } else {
                "时间未知"
            }
            text = "待你预览确认 · $created\n当前气泡不会因为这份草稿自动改变。"
            textSize = 10.5f
            setTextColor(c.textSecondary)
            setPadding(0, dp(4), 0, dp(8))
        })
        card.addView(TextView(this).apply {
            val lines = draft.css.lineSequence().toList()
            val shown = lines.take(6).joinToString("\n")
            text = if (lines.size > 6) "$shown\n……" else shown
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(c.textPrimary)
            setPadding(dp(11), dp(10), dp(11), dp(10))
            background = roundedBackground(c.card, dp(10).toFloat(), c.border)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) })

        if (draft.warnings.isNotEmpty()) {
            card.addView(TextView(this).apply {
                text = "校验提醒：\n" + draft.warnings.joinToString("\n") { "• $it" }
                textSize = 10.5f
                setTextColor(0xFFE0A84F.toInt())
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = roundedBackground(c.backgroundSecondary, dp(10).toFloat(), c.border)
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) })
        }

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(createEditorActionButton("预览与确认", selected = true) {
            showResidentDraftReviewDialog(draft)
        }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(4) })
        actions.addView(createEditorActionButton("仅删除草稿", selected = false) {
            AlertDialog.Builder(this)
                .setTitle("删除这份候选草稿？")
                .setMessage("只会删除待确认草稿，不会修改当前气泡，也不会向 $friendName 发送驳回回执。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除") { _, _ ->
                    storage.clearResidentCodeDraft(friendId)
                    renderTarget()
                    Toast.makeText(this, "候选草稿已删除", Toast.LENGTH_SHORT).show()
                }
                .show()
        }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(4) })
        card.addView(actions)

        controlsContainer.addView(card, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(4) })
    }

    private fun showResidentDraftReviewDialog(draft: BubbleStyleStorage.ResidentCodeDraft) {
        val parsed = SafeBubbleCss.compile(draft.css, BubbleStyleStorage.Target.FRIEND)
        if (parsed.errors.isNotEmpty()) {
            Toast.makeText(
                this,
                "这份草稿已经无法通过检查：${parsed.errors.firstOrNull() ?: "请让住户重写"}",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val imageAvailable = friendStyle.imagePath.isNotBlank() && File(friendStyle.imagePath).isFile
        var selectedMode = if (
            friendStyle.fillMode == BubbleStyleStorage.FillMode.IMAGE_CODE && imageAvailable
        ) {
            BubbleStyleStorage.FillMode.IMAGE_CODE
        } else {
            BubbleStyleStorage.FillMode.CODE
        }

        val draftPreview = BubbleStylePreviewView(this).apply {
            background = roundedBackground(c.backgroundSecondary, dp(14).toFloat(), c.border)
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            bindConversation(friendId, friendName)
            setLongMessagePreview(true)
        }
        val modeHint = TextView(this).apply {
            textSize = 10.5f
            setTextColor(c.textSecondary)
            setPadding(dp(11), dp(8), dp(11), dp(8))
            background = roundedBackground(c.card, dp(10).toFloat(), c.border)
        }
        val codeButton = createTargetButton("代码气泡") {}
        val imageCodeButton = createTargetButton("图片＋代码") {}

        fun candidateStyle(): BubbleStyleStorage.BubbleStyle = friendStyle.copy(
            fillMode = selectedMode,
            codeCss = draft.css
        )

        fun refreshDraftPreview() {
            draftPreview.setPreview(candidateStyle(), userStyle, BubbleStyleStorage.Target.FRIEND)
            draftPreview.setLongMessagePreview(true)
            updateTargetButton(codeButton, selectedMode == BubbleStyleStorage.FillMode.CODE)
            updateTargetButton(
                imageCodeButton,
                selectedMode == BubbleStyleStorage.FillMode.IMAGE_CODE
            )
            imageCodeButton.alpha = if (imageAvailable) 1f else 0.45f
            modeHint.text = when {
                selectedMode == BubbleStyleStorage.FillMode.IMAGE_CODE ->
                    "预览：保留当前住户图片素材，并叠加这份安全代码。"
                imageAvailable ->
                    "预览：只使用代码外观。也可以切换到“图片＋代码”。"
                else ->
                    "预览：只使用代码外观。当前住户侧没有可用图片素材，因此不能选择“图片＋代码”。"
            }
        }
        codeButton.setOnClickListener {
            selectedMode = BubbleStyleStorage.FillMode.CODE
            refreshDraftPreview()
        }
        imageCodeButton.setOnClickListener {
            if (!imageAvailable) {
                Toast.makeText(this, "住户侧还没有图片素材", Toast.LENGTH_SHORT).show()
            } else {
                selectedMode = BubbleStyleStorage.FillMode.IMAGE_CODE
                refreshDraftPreview()
            }
        }

        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(codeButton, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                marginEnd = dp(4)
            })
            addView(imageCodeButton, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                marginStart = dp(4)
            })
        }
        val codeText = TextView(this).apply {
            text = draft.css
            typeface = Typeface.MONOSPACE
            textSize = 10.5f
            setTextColor(c.textPrimary)
            setTextIsSelectable(true)
            setPadding(dp(11), dp(10), dp(11), dp(10))
            background = roundedBackground(c.card, dp(10).toFloat(), c.border)
        }
        val note = TextView(this).apply {
            text = buildString {
                append("确认前不会改动真实聊天。确认后只更新 $friendName 的气泡代码与模式，不能修改你的气泡。")
                if (friendDirty) {
                    append("\n你当前对住户气泡还有未保存调整；确认时会连同这些正在预览的调整一起保存。")
                }
            }
            textSize = 10.5f
            setTextColor(if (friendDirty) 0xFFE0A84F.toInt() else c.textSecondary)
            setPadding(dp(11), dp(9), dp(11), dp(9))
            background = roundedBackground(c.accentBg, dp(10).toFloat(), c.border)
        }

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(10))
            addView(modeRow, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) })
            addView(modeHint, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) })
            addView(draftPreview, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(360)
            ).apply { bottomMargin = dp(8) })
            addView(note, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) })
            addView(TextView(this@BubbleStyleEditorActivity).apply {
                text = "住户提交的代码"
                textSize = 12f
                setTextColor(c.accentStrong)
                setPadding(0, dp(4), 0, dp(6))
            })
            addView(codeText, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        val scroll = ScrollView(this).apply {
            addView(body, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("预览 $friendName 的候选气泡")
            .setView(scroll)
            .setNegativeButton("关闭", null)
            .setNeutralButton("驳回并请重写", null)
            .setPositiveButton("确认应用", null)
            .create()
        dialog.setOnShowListener {
            refreshDraftPreview()
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                dialog.dismiss()
                showResidentDraftRewriteDialog(draft)
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val latest = storage.getResidentCodeDraft(friendId)
                if (latest == null || latest.css != draft.css || latest.createdAt != draft.createdAt) {
                    Toast.makeText(this, "草稿已经变化，请重新打开预览", Toast.LENGTH_LONG).show()
                    dialog.dismiss()
                    renderTarget()
                    return@setOnClickListener
                }
                if (
                    selectedMode == BubbleStyleStorage.FillMode.IMAGE_CODE &&
                    (friendStyle.imagePath.isBlank() || !File(friendStyle.imagePath).isFile)
                ) {
                    Toast.makeText(this, "当前图片素材已经不可用", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val check = SafeBubbleCss.compile(draft.css, BubbleStyleStorage.Target.FRIEND)
                if (check.errors.isNotEmpty()) {
                    Toast.makeText(
                        this,
                        check.errors.firstOrNull() ?: "草稿未通过安全检查",
                        Toast.LENGTH_LONG
                    ).show()
                    return@setOnClickListener
                }
                val applied = candidateStyle()
                persistTarget(BubbleStyleStorage.Target.FRIEND, applied)
                friendStyle = applied
                savedFriendStyle = applied
                friendDirty = false
                storage.clearResidentCodeDraft(friendId)
                storage.setResidentCodeDraftFeedback(
                    friendId,
                    "人类已经预览并确认了你的代码气泡草稿。当前生效模式：${residentDraftModeLabel(selectedMode)}。"
                )
                dialog.dismiss()
                target = BubbleStyleStorage.Target.FRIEND
                renderTarget()
                Toast.makeText(this, "$friendName 的代码气泡已应用", Toast.LENGTH_SHORT).show()
            }
        }
        dialog.show()
    }

    private fun showResidentDraftRewriteDialog(draft: BubbleStyleStorage.ResidentCodeDraft) {
        val noteInput = EditText(this).apply {
            hint = "可以留空，或写一句希望他怎样调整"
            minLines = 3
            maxLines = 6
            gravity = Gravity.TOP or Gravity.START
            setTextColor(c.textPrimary)
            setHintTextColor(c.textHint)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBackground(c.card, dp(10).toFloat(), c.border)
        }
        AlertDialog.Builder(this)
            .setTitle("驳回这份草稿并请他重写？")
            .setMessage("草稿会被删除；你的说明会在下一次提示词中只交给 $friendName 看一次。")
            .setView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(4), dp(18), 0)
                addView(noteInput, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ))
            })
            .setNegativeButton("取消", null)
            .setPositiveButton("请他重写") { _, _ ->
                val latest = storage.getResidentCodeDraft(friendId)
                if (latest == null || latest.css != draft.css || latest.createdAt != draft.createdAt) {
                    Toast.makeText(this, "草稿已经变化，没有执行驳回", Toast.LENGTH_LONG).show()
                    renderTarget()
                    return@setPositiveButton
                }
                val noteText = noteInput.text?.toString().orEmpty().trim()
                storage.clearResidentCodeDraft(friendId)
                storage.setResidentCodeDraftFeedback(
                    friendId,
                    buildString {
                        append("人类预览了你的代码气泡草稿，但没有应用，并请你重新设计。")
                        if (noteText.isNotEmpty()) append(" 人类的说明：").append(noteText)
                    }
                )
                renderTarget()
                Toast.makeText(this, "已请 $friendName 重新设计", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun residentDraftModeLabel(mode: BubbleStyleStorage.FillMode): String = when (mode) {
        BubbleStyleStorage.FillMode.CODE -> "代码气泡"
        BubbleStyleStorage.FillMode.IMAGE_CODE -> "图片＋代码"
        BubbleStyleStorage.FillMode.BASIC -> "普通颜色"
        BubbleStyleStorage.FillMode.IMAGE -> "图片气泡"
    }

    private fun addModeSelector() {
        fun selectMode(mode: BubbleStyleStorage.FillMode) {
            when {
                mode.usesImage && (
                    currentStyle().imagePath.isBlank() ||
                        !File(currentStyle().imagePath).isFile
                    ) -> launchImagePicker(target, mode)

                else -> {
                    // 只切换模式，不在点击回调里同时创建/写入大段代码。
                    // 代码编辑器由独立弹窗承载，避免重建当前控件树时把正在点击的
                    // View 连同父容器一起移除，部分设备上会直接让 Activity 崩溃。
                    updateCurrent { it.copy(fillMode = mode) }
                    controlsContainer.post { renderTarget() }
                }
            }
        }

        val firstRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        firstRow.addView(
            createEditorActionButton(
                "普通颜色",
                currentStyle().fillMode == BubbleStyleStorage.FillMode.BASIC
            ) { selectMode(BubbleStyleStorage.FillMode.BASIC) },
            LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(5) }
        )
        firstRow.addView(
            createEditorActionButton(
                "图片气泡",
                currentStyle().fillMode == BubbleStyleStorage.FillMode.IMAGE
            ) { selectMode(BubbleStyleStorage.FillMode.IMAGE) },
            LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(5) }
        )
        controlsContainer.addView(firstRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) })

        val secondRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        secondRow.addView(
            createEditorActionButton(
                "代码气泡",
                currentStyle().fillMode == BubbleStyleStorage.FillMode.CODE
            ) { selectMode(BubbleStyleStorage.FillMode.CODE) },
            LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(5) }
        )
        secondRow.addView(
            createEditorActionButton(
                "图片＋代码",
                currentStyle().fillMode == BubbleStyleStorage.FillMode.IMAGE_CODE
            ) { selectMode(BubbleStyleStorage.FillMode.IMAGE_CODE) },
            LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(5) }
        )
        controlsContainer.addView(secondRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) })
    }

    private fun addBasicModeControls() {
        addSectionLabel("颜色")
        addColorRow("背景颜色", "透明度由下一项单独控制", currentStyle().backgroundColor, false) { color ->
            updateCurrent { it.copy(backgroundColor = color.toOpaqueRgb()) }
        }
        addSlider(
            "背景透明度",
            BubbleStyleStorage.MIN_OPACITY_PERCENT,
            BubbleStyleStorage.MAX_OPACITY_PERCENT,
            currentStyle().backgroundOpacityPercent,
            "%"
        ) { value -> updateCurrent { it.copy(backgroundOpacityPercent = value) } }

        addSectionLabel("轮廓")
        addSlider(
            "整体圆角",
            BubbleStyleStorage.MIN_CORNER_RADIUS_DP,
            BubbleStyleStorage.MAX_CORNER_RADIUS_DP,
            currentStyle().cornerRadiusDp,
            "dp"
        ) { value -> updateCurrent { it.copy(cornerRadiusDp = value) } }
        addSlider(
            "靠头像的小圆角",
            BubbleStyleStorage.MIN_CORNER_RADIUS_DP,
            BubbleStyleStorage.MAX_CORNER_RADIUS_DP,
            currentStyle().anchorCornerRadiusDp,
            "dp"
        ) { value -> updateCurrent { it.copy(anchorCornerRadiusDp = value) } }
        addSlider(
            "边框粗细",
            BubbleStyleStorage.MIN_BORDER_WIDTH_DP,
            BubbleStyleStorage.MAX_BORDER_WIDTH_DP,
            currentStyle().borderWidthDp,
            "dp"
        ) { value -> updateCurrent { it.copy(borderWidthDp = value) } }
        addColorRow("边框颜色", "边框粗细为 0 时不会显示", currentStyle().borderColor, true) { color ->
            updateCurrent { it.copy(borderColor = color) }
        }
    }

    private fun addCodeModeControls() {
        addSectionLabel(if (currentStyle().fillMode.usesImage) "叠加安全代码" else "安全代码")

        controlsContainer.addView(TextView(this).apply {
            text = "代码只负责气泡外观，并且只认白名单属性。图片＋代码模式会保留当前图片素材，再用代码覆盖文字、圆角、边距、边框和阴影等样式。"
            textSize = 11f
            setTextColor(c.textSecondary)
            setPadding(dp(13), dp(10), dp(13), dp(10))
            background = roundedBackground(c.accentBg, dp(12).toFloat(), c.border)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) })

        val css = currentStyle().codeCss
        val result = SafeBubbleCss.compile(css, target)
        val status = TextView(this).apply {
            textSize = 11f
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = roundedBackground(c.card, dp(10).toFloat(), c.border)
            when {
                css.isBlank() -> {
                    setTextColor(c.textSecondary)
                    text = "尚未填写代码 · 当前先沿用原有安全样式"
                }
                result.errors.isNotEmpty() -> {
                    setTextColor(0xFFE57373.toInt())
                    text = "代码未应用：${result.errors.joinToString("；").take(260)}"
                }
                result.warnings.isNotEmpty() -> {
                    setTextColor(0xFFE0A84F.toInt())
                    text = "可以使用，但有提示：${result.warnings.joinToString("；").take(260)}"
                }
                else -> {
                    setTextColor(c.accentStrong)
                    text = "代码检查通过 · 预览与真实聊天共用同一解析器"
                }
            }
        }
        controlsContainer.addView(status, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) })

        val codePreview = TextView(this).apply {
            text = if (css.isBlank()) {
                "点击下方按钮打开代码编辑器。第一次打开会自动放入一份可直接修改的示例。"
            } else {
                css.lineSequence().take(7).joinToString("\n").let { shown ->
                    if (css.lineSequence().count() > 7) "$shown\n……" else shown
                }
            }
            typeface = Typeface.MONOSPACE
            textSize = 11.5f
            setTextColor(if (css.isBlank()) c.textHint else c.textPrimary)
            setPadding(dp(13), dp(12), dp(13), dp(12))
            background = roundedBackground(c.backgroundSecondary, dp(12).toFloat(), c.border)
        }
        controlsContainer.addView(codePreview, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) })

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(createEditorActionButton("打开代码编辑器", selected = false) {
            showCodeEditorDialog()
        }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(4) })
        actions.addView(createEditorActionButton("代码说明", selected = false) {
            showCodeBubbleGuide()
        }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(4) })
        controlsContainer.addView(actions, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
    }

    private fun showCodeEditorDialog() {
        val originalCss = currentStyle().codeCss
        val editor = EditText(this).apply {
            setText(originalCss.ifBlank { SafeBubbleCss.defaultTemplate(target) })
            setSelection(text.length)
            minLines = 13
            maxLines = 22
            gravity = Gravity.TOP or Gravity.START
            setHorizontallyScrolling(false)
            typeface = Typeface.MONOSPACE
            textSize = 12.5f
            setTextColor(c.textPrimary)
            setHintTextColor(c.textHint)
            filters = arrayOf(InputFilter.LengthFilter(BubbleStyleStorage.MAX_CODE_CSS_LENGTH))
            setPadding(dp(13), dp(12), dp(13), dp(12))
            background = roundedBackground(c.card, dp(12).toFloat(), c.border)
        }
        val status = TextView(this).apply {
            textSize = 11f
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }

        fun refreshStatus(css: String) {
            val result = SafeBubbleCss.compile(css, target)
            when {
                css.isBlank() -> {
                    status.setTextColor(c.textSecondary)
                    status.text = "代码为空时会沿用原有安全样式"
                }
                result.errors.isNotEmpty() -> {
                    status.setTextColor(0xFFE57373.toInt())
                    status.text = "暂时不能应用：${result.errors.joinToString("；").take(260)}"
                }
                result.warnings.isNotEmpty() -> {
                    status.setTextColor(0xFFE0A84F.toInt())
                    status.text = "可以应用，但有提示：${result.warnings.joinToString("；").take(260)}"
                }
                else -> {
                    status.setTextColor(c.accentStrong)
                    status.text = "代码检查通过"
                }
            }
            status.background = roundedBackground(c.backgroundSecondary, dp(10).toFloat(), c.border)
        }
        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(editable: Editable?) {
                refreshStatus(editable?.toString().orEmpty())
            }
        })
        refreshStatus(editor.text?.toString().orEmpty())

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(4), dp(18), 0)
            addView(editor, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(360)
            ).apply { bottomMargin = dp(8) })
            addView(status, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (currentStyle().fillMode.usesImage) "编辑图片气泡代码" else "编辑代码气泡")
            .setView(body)
            .setNegativeButton("取消", null)
            .setNeutralButton("清空", null)
            .setPositiveButton("应用到预览", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                editor.setText("")
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val css = editor.text?.toString().orEmpty()
                val result = SafeBubbleCss.compile(css, target)
                if (result.errors.isNotEmpty()) {
                    refreshStatus(css)
                    Toast.makeText(
                        this,
                        result.errors.firstOrNull() ?: "代码格式还没有通过检查",
                        Toast.LENGTH_LONG
                    ).show()
                    return@setOnClickListener
                }
                updateCurrent { it.copy(codeCss = css) }
                dialog.dismiss()
                controlsContainer.post { renderTarget() }
            }
        }
        dialog.show()
    }

    private fun showCodeBubbleGuide() {
        val guide = TextView(this).apply {
            text = buildString {
                appendLine("这不是网页 CSS，也不会加载 HTML。归栖只借用了容易阅读的 CSS 写法。")
                appendLine()
                appendLine(SafeBubbleCss.supportedSyntaxText())
                appendLine()
                appendLine("示例：")
                appendLine(SafeBubbleCss.defaultTemplate(target))
                appendLine()
                appendLine("数值边界会自动收紧：圆角最多 32dp，边框最多 4dp，阴影浓度最多 60%，字号 10sp～24sp，内边距最多 48dp。")
                appendLine("未知属性只会被忽略；花括号或声明格式错误时，真实聊天会退回原有安全样式，并阻止保存这份错误代码。")
                appendLine("当前第一版只作用于普通文字气泡；图片消息、表情包、引用卡片、天气、书籍、思考和系统提示保持原样。")
                appendLine("当前第一版只支持静态气泡样式。动画、任意图片路径、网络字体、脚本、定位和覆盖聊天页面都不会执行。")
            }
            textSize = 13f
            setTextColor(c.textPrimary)
            setLineSpacing(0f, 1.16f)
            setPadding(dp(20), dp(8), dp(20), dp(18))
        }
        AlertDialog.Builder(this)
            .setTitle("安全代码气泡说明")
            .setView(ScrollView(this).apply {
                addView(guide, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ))
            })
            .setPositiveButton("知道啦", null)
            .show()
    }

    private fun addImageModeControls() {
        val style = currentStyle()
        controlsContainer.addView(TextView(this).apply {
            val bitmap = BubbleImageBitmapCache.get(style.imagePath)
            val extension = File(style.imagePath).extension.uppercase(Locale.US).ifBlank { "图片" }
            val assetSummary = if (bitmap != null) {
                "${bitmap.width}×${bitmap.height} · $extension"
            } else {
                "尚未选择"
            }
            val renderName = if (style.imageRenderMode == BubbleStyleStorage.ImageRenderMode.SMART_FRAME) {
                "保真画框"
            } else {
                "手动九宫格"
            }
            text = "当前素材：$assetSummary · $renderName\n带小猫、药瓶、角花等完整装饰时优先用保真画框；专门为九宫格制作的边框才用手动模式。"
            textSize = 11f
            setTextColor(c.textSecondary)
            setPadding(dp(13), dp(11), dp(13), dp(11))
            background = roundedBackground(c.accentBg, dp(12).toFloat(), c.border)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) })

        controlsContainer.addView(
            createEditorActionButton("图片气泡指南", selected = false) {
                showImageBubbleGuide()
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(42)
            ).apply { bottomMargin = dp(8) }
        )

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(createEditorActionButton("更换图片", selected = true) {
            launchImagePicker(target, currentStyle().fillMode)
        }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(5) })
        actions.addView(createEditorActionButton("移除图片", selected = false) {
            val old = currentStyle()
            deleteUnsavedImage(old.imagePath, savedStyleFor(target).imagePath)
            val fallback = if (old.fillMode == BubbleStyleStorage.FillMode.IMAGE_CODE) {
                BubbleStyleStorage.FillMode.CODE
            } else {
                BubbleStyleStorage.FillMode.BASIC
            }
            updateCurrent { it.copy(fillMode = fallback, imagePath = "") }
            renderTarget()
        }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(5) })
        controlsContainer.addView(actions, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        addSectionLabel("图片显示")
        addSlider(
            "图片透明度",
            BubbleStyleStorage.MIN_IMAGE_OPACITY_PERCENT,
            BubbleStyleStorage.MAX_IMAGE_OPACITY_PERCENT,
            style.imageOpacityPercent,
            "%"
        ) { value -> updateCurrent { it.copy(imageOpacityPercent = value) } }

        addSectionLabel("相对头像位置 · 每一边独立保存")
        controlsContainer.addView(TextView(this).apply {
            text = "水平负数会靠近对应头像，正数会远离头像；垂直负数上移，正数下移。隐藏头像时则相对聊天边缘微调。"
            textSize = 11f
            setTextColor(c.textSecondary)
            setPadding(dp(13), dp(9), dp(13), dp(9))
            background = roundedBackground(c.card, dp(12).toFloat(), c.border)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(7) })
        addSlider(
            "水平位置",
            BubbleStyleStorage.MIN_IMAGE_AVATAR_OFFSET_DP,
            BubbleStyleStorage.MAX_IMAGE_AVATAR_OFFSET_DP,
            style.imageAvatarOffsetDp,
            "dp"
        ) { value -> updateCurrent { it.copy(imageAvatarOffsetDp = value) } }
        addSlider(
            "垂直位置",
            BubbleStyleStorage.MIN_IMAGE_VERTICAL_OFFSET_DP,
            BubbleStyleStorage.MAX_IMAGE_VERTICAL_OFFSET_DP,
            style.imageVerticalOffsetDp,
            "dp"
        ) { value -> updateCurrent { it.copy(imageVerticalOffsetDp = value) } }

        addSectionLabel("图片适配方式")
        val renderModeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        renderModeRow.addView(
            createEditorActionButton(
                "保真画框（推荐）",
                selected = style.imageRenderMode == BubbleStyleStorage.ImageRenderMode.SMART_FRAME
            ) {
                updateCurrent {
                    it.copy(imageRenderMode = BubbleStyleStorage.ImageRenderMode.SMART_FRAME)
                }
                renderTarget()
            },
            LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(5) }
        )
        renderModeRow.addView(
            createEditorActionButton(
                "手动九宫格",
                selected = style.imageRenderMode == BubbleStyleStorage.ImageRenderMode.NINE_SLICE
            ) {
                updateCurrent {
                    it.copy(imageRenderMode = BubbleStyleStorage.ImageRenderMode.NINE_SLICE)
                }
                renderTarget()
            },
            LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(5) }
        )
        controlsContainer.addView(
            renderModeRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        )
        controlsContainer.addView(TextView(this).apply {
            text = if (style.imageRenderMode == BubbleStyleStorage.ImageRenderMode.SMART_FRAME) {
                "系统会自动寻找最平静的一横一竖两条窄带，只拉伸空白部分；小猫、药瓶和角花会整体等比缩放。蓝线也会自动贴合图片中央文字框。"
            } else {
                "手动九宫格会按四边固定比例切图。只有装饰完整落在固定区、中心本来就可拉伸的专用素材才适合。"
            }
            textSize = 11f
            setTextColor(c.textSecondary)
            setPadding(dp(13), dp(10), dp(13), dp(10))
            background = roundedBackground(c.card, dp(12).toFloat(), c.border)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) })

        addSectionLabel("外轮廓 · 图片也会按圆角裁切")
        addSlider(
            "整体圆角",
            BubbleStyleStorage.MIN_CORNER_RADIUS_DP,
            BubbleStyleStorage.MAX_CORNER_RADIUS_DP,
            style.cornerRadiusDp,
            "dp"
        ) { value -> updateCurrent { it.copy(cornerRadiusDp = value) } }
        addSlider(
            "靠头像的小圆角",
            BubbleStyleStorage.MIN_CORNER_RADIUS_DP,
            BubbleStyleStorage.MAX_CORNER_RADIUS_DP,
            style.anchorCornerRadiusDp,
            "dp"
        ) { value -> updateCurrent { it.copy(anchorCornerRadiusDp = value) } }

        if (style.imageRenderMode == BubbleStyleStorage.ImageRenderMode.NINE_SLICE) {
            addSectionLabel("固定边缘 · 黄虚线内是伸缩区")
            addSlider("左侧固定", BubbleStyleStorage.MIN_IMAGE_FIXED_PERCENT, BubbleStyleStorage.MAX_IMAGE_FIXED_PERCENT, style.imageFixedLeftPercent, "%") { value ->
                updateCurrent { it.copy(imageFixedLeftPercent = value) }
            }
            addSlider("上侧固定", BubbleStyleStorage.MIN_IMAGE_FIXED_PERCENT, BubbleStyleStorage.MAX_IMAGE_FIXED_PERCENT, style.imageFixedTopPercent, "%") { value ->
                updateCurrent { it.copy(imageFixedTopPercent = value) }
            }
            addSlider("右侧固定", BubbleStyleStorage.MIN_IMAGE_FIXED_PERCENT, BubbleStyleStorage.MAX_IMAGE_FIXED_PERCENT, style.imageFixedRightPercent, "%") { value ->
                updateCurrent { it.copy(imageFixedRightPercent = value) }
            }
            addSlider("下侧固定", BubbleStyleStorage.MIN_IMAGE_FIXED_PERCENT, BubbleStyleStorage.MAX_IMAGE_FIXED_PERCENT, style.imageFixedBottomPercent, "%") { value ->
                updateCurrent { it.copy(imageFixedBottomPercent = value) }
            }
        }

        addSectionLabel(
            if (style.imageRenderMode == BubbleStyleStorage.ImageRenderMode.SMART_FRAME) {
                "自动文字安全区 · 蓝虚线（滑杆只会额外加宽）"
            } else {
                "文字安全区 · 蓝虚线"
            }
        )
        val paddingPrefix = if (style.imageRenderMode == BubbleStyleStorage.ImageRenderMode.SMART_FRAME) "额外" else "文字"
        addSlider("${paddingPrefix}左边距", BubbleStyleStorage.MIN_IMAGE_PADDING_DP, BubbleStyleStorage.MAX_IMAGE_PADDING_DP, style.imagePaddingLeftDp, "dp") { value ->
            updateCurrent { it.copy(imagePaddingLeftDp = value) }
        }
        addSlider("${paddingPrefix}上边距", BubbleStyleStorage.MIN_IMAGE_PADDING_DP, BubbleStyleStorage.MAX_IMAGE_PADDING_DP, style.imagePaddingTopDp, "dp") { value ->
            updateCurrent { it.copy(imagePaddingTopDp = value) }
        }
        addSlider("${paddingPrefix}右边距", BubbleStyleStorage.MIN_IMAGE_PADDING_DP, BubbleStyleStorage.MAX_IMAGE_PADDING_DP, style.imagePaddingRightDp, "dp") { value ->
            updateCurrent { it.copy(imagePaddingRightDp = value) }
        }
        addSlider("${paddingPrefix}下边距", BubbleStyleStorage.MIN_IMAGE_PADDING_DP, BubbleStyleStorage.MAX_IMAGE_PADDING_DP, style.imagePaddingBottomDp, "dp") { value ->
            updateCurrent { it.copy(imagePaddingBottomDp = value) }
        }
    }


    private fun showImageBubbleGuide() {
        val guideText = TextView(this).apply {
            text = """
                图片气泡现在有两种适配方式。

                【保真画框（推荐）】
                • 适合这类带小猫、药瓶、角花、尾巴和像素装饰的完整图片。
                • 系统会在图片中央自动寻找变化最小的一条竖窄带和横窄带，只拉伸这两条空白带。
                • 其余图案始终按同一个比例缩放，因此小猫不会被拉宽，瓶子也不会被拉长。
                • 蓝虚线会自动识别中央可写字区域；四个边距滑杆只负责在自动结果上继续增加留白。

                【手动九宫格】
                • 只适合专门按九宫格制作的边框素材：四角和装饰必须完整落在固定区，中央本来就应当允许拉伸。
                • 黄虚线里面是会被横向和纵向拉伸的区域。任何被黄线切到的猫、人物、文字或完整图案都会变形。
                • 普通照片、截图、海报以及装饰横跨中央的图片不要使用这个模式。

                【推荐格式】
                • 首选 PNG 或 WebP；需要透明边缘时不要使用 JPEG。
                • 建议使用 256×256～1024×1024 的图片，512×512 或 720×720 方图都可以。
                • 像素画请尽量使用清晰原图，避免先被聊天软件压缩多次。

                【透明度与位置】
                • 图片透明度只影响气泡素材，文字颜色保持不变。
                • 水平位置负数会靠近对应头像，正数会远离头像；住户与我的数值会分别保存。
                • 垂直位置负数上移，正数下移。幅度过大可能与上一条或下一条消息重叠，建议小步调整。
                • 隐藏头像后，水平位置会改为相对聊天左右边缘微调。

                【文字与圆角】
                • 蓝虚线表示真实文字的换行宽度和安全区域；保存前请同时查看长消息预览。
                • 文字仍压到装饰时，增加对应方向的额外边距。
                • 圆角会真正裁掉图片四角；素材本身已有漂亮外轮廓时，可以把整体圆角调小或设为 0。

                对带完整装饰的图片，直接选择“保真画框（推荐）”即可，不需要再猜四边固定比例。
            """.trimIndent()
            textSize = 13f
            setTextColor(c.textPrimary)
            setLineSpacing(0f, 1.14f)
            setPadding(dp(20), dp(8), dp(20), dp(18))
        }
        val scroll = ScrollView(this).apply {
            addView(guideText, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        AlertDialog.Builder(this)
            .setTitle("图片气泡指南")
            .setView(scroll)
            .setPositiveButton("知道啦", null)
            .show()
    }

    private fun createEditorActionButton(
        text: String,
        selected: Boolean,
        onClick: () -> Unit
    ): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        gravity = Gravity.CENTER
        setTextColor(if (selected) c.textPrimary else c.textSecondary)
        background = roundedBackground(
            if (selected) c.accent else c.card,
            dp(12).toFloat(),
            if (selected) c.accentStrong else c.border
        )
        setOnClickListener { onClick() }
    }

    private fun launchImagePicker(
        target: BubbleStyleStorage.Target,
        modeAfterImport: BubbleStyleStorage.FillMode = BubbleStyleStorage.FillMode.IMAGE
    ) {
        imagePickerTarget = target
        imagePickerRequestedMode = modeAfterImport
        imagePicker.launch(arrayOf("image/png", "image/webp", "image/jpeg"))
    }

    private fun addSectionLabel(text: String) {
        controlsContainer.addView(TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(c.accentStrong)
            setPadding(dp(2), dp(15), 0, dp(7))
        })
    }

    private fun addColorRow(
        title: String,
        description: String,
        color: Int,
        allowAlpha: Boolean,
        onChanged: (Int) -> Unit
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(13), dp(10), dp(13), dp(10))
            background = roundedBackground(c.card, dp(12).toFloat(), c.border)
            isClickable = true
            isFocusable = true
        }
        val textBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        textBox.addView(TextView(this).apply {
            text = title
            textSize = 14f
            setTextColor(c.textPrimary)
        })
        textBox.addView(TextView(this).apply {
            text = description
            textSize = 10f
            setTextColor(c.textHint)
        })
        row.addView(textBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val value = TextView(this).apply {
            text = formatColor(color, allowAlpha)
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(c.textSecondary)
        }
        row.addView(value, LinearLayout.LayoutParams(dp(82), dp(36)))
        val swatch = View(this).apply {
            background = roundedBackground(color, dp(10).toFloat(), c.borderMedium)
        }
        row.addView(swatch, LinearLayout.LayoutParams(dp(36), dp(36)))
        row.setOnClickListener {
            showColorDialog(title, color, allowAlpha) { selected ->
                onChanged(selected)
                renderTarget()
            }
        }
        controlsContainer.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(7) })
    }

    private fun addSlider(
        title: String,
        minValue: Int,
        maxValue: Int,
        initial: Int,
        suffix: String,
        onChanged: (Int) -> Unit
    ) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(9), dp(13), dp(8))
            background = roundedBackground(c.card, dp(12).toFloat(), c.border)
        }
        val label = TextView(this).apply {
            textSize = 13f
            setTextColor(c.textPrimary)
            text = "$title：$initial$suffix"
        }
        val seekBar = SeekBar(this).apply {
            this.max = maxValue - minValue
            progress = initial.coerceIn(minValue, maxValue) - minValue
        }
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val value = minValue + progress
                label.text = "$title：$value$suffix"
                onChanged(value)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        box.addView(label)
        box.addView(seekBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        controlsContainer.addView(box, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(7) })
    }

    private fun showColorDialog(
        title: String,
        currentColor: Int,
        allowAlpha: Boolean,
        onSelected: (Int) -> Unit
    ) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(6), dp(20), 0)
        }
        val input = EditText(this).apply {
            setSingleLine(true)
            setText(formatColor(currentColor, allowAlpha))
            setSelection(text.length)
            hint = if (allowAlpha) "#RRGGBB 或 #AARRGGBB" else "#RRGGBB"
        }
        container.addView(input)
        container.addView(TextView(this).apply {
            text = "常用颜色"
            textSize = 11f
            setTextColor(c.textSecondary)
            setPadding(0, dp(10), 0, dp(6))
        })

        val palette = listOf(
            0xFFB3A0FF.toInt(),
            0xFFFFFFFF.toInt(),
            0xFF111827.toInt(),
            0xFF8EC5FF.toInt(),
            0xFFFFA9C9.toInt(),
            0xFF9AD6B1.toInt(),
            0xFFFFD9A8.toInt(),
            0xFF9CA3AF.toInt()
        )
        val paletteRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        palette.forEach { color ->
            val chip = View(this).apply {
                background = roundedBackground(color, dp(10).toFloat(), c.borderMedium)
                setOnClickListener {
                    input.setText(formatColor(color, allowAlpha))
                    input.setSelection(input.text.length)
                }
            }
            paletteRow.addView(chip, LinearLayout.LayoutParams(dp(38), dp(38)).apply {
                marginEnd = dp(7)
            })
        }
        container.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(paletteRow)
        })

        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(container)
            .setNegativeButton("取消", null)
            .setPositiveButton("确定", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val parsed = parseColor(input.text.toString(), allowAlpha)
                if (parsed == null) {
                    input.error = "请输入 #RRGGBB 或 #AARRGGBB"
                } else {
                    onSelected(parsed)
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun parseColor(raw: String, allowAlpha: Boolean): Int? {
        val clean = raw.trim().removePrefix("#")
        val normalized = when (clean.length) {
            6 -> "#$clean"
            8 -> if (allowAlpha) "#$clean" else "#${clean.takeLast(6)}"
            else -> return null
        }
        return runCatching { Color.parseColor(normalized) }.getOrNull()
            ?.let { if (allowAlpha) it else it.toOpaqueRgb() }
    }

    private fun formatColor(color: Int, includeAlpha: Boolean): String {
        return if (includeAlpha && Color.alpha(color) != 255) {
            String.format(Locale.US, "#%08X", color)
        } else {
            String.format(Locale.US, "#%06X", color and 0xFFFFFF)
        }
    }

    private fun updateCurrent(transform: (BubbleStyleStorage.BubbleStyle) -> BubbleStyleStorage.BubbleStyle) {
        setCurrentStyle(transform(currentStyle()))
        markCurrentDirty()
        preview.setPreview(friendStyle, userStyle, target)
        preview.setLongMessagePreview(longMessagePreview)
        updatePreviewLengthButtons()
        updateStatusLabel()
    }

    private fun currentStyle(): BubbleStyleStorage.BubbleStyle = styleFor(target)

    private fun styleFor(target: BubbleStyleStorage.Target): BubbleStyleStorage.BubbleStyle =
        if (target == BubbleStyleStorage.Target.FRIEND) friendStyle else userStyle

    private fun savedStyleFor(target: BubbleStyleStorage.Target): BubbleStyleStorage.BubbleStyle =
        if (target == BubbleStyleStorage.Target.FRIEND) savedFriendStyle else savedUserStyle

    private fun setCurrentStyle(style: BubbleStyleStorage.BubbleStyle) {
        setStyleFor(target, style)
    }

    private fun setStyleFor(
        target: BubbleStyleStorage.Target,
        style: BubbleStyleStorage.BubbleStyle
    ) {
        if (target == BubbleStyleStorage.Target.FRIEND) friendStyle = style else userStyle = style
    }

    private fun setSavedStyleFor(
        target: BubbleStyleStorage.Target,
        style: BubbleStyleStorage.BubbleStyle
    ) {
        if (target == BubbleStyleStorage.Target.FRIEND) savedFriendStyle = style else savedUserStyle = style
    }

    private fun markCurrentDirty() = markDirty(target)

    private fun markDirty(target: BubbleStyleStorage.Target) {
        if (target == BubbleStyleStorage.Target.FRIEND) friendDirty = true else userDirty = true
    }

    private fun deleteUnsavedImage(path: String, savedPath: String) {
        if (path.isNotBlank() && path != savedPath) imageAssets.deleteManagedPath(path)
    }

    private fun currentDirty(): Boolean =
        if (target == BubbleStyleStorage.Target.FRIEND) friendDirty else userDirty

    private fun hasAnyDirty(): Boolean = friendDirty || userDirty

    private fun validateCodeStylesForSave(): Boolean {
        val candidates = listOf(
            BubbleStyleStorage.Target.FRIEND to friendStyle,
            BubbleStyleStorage.Target.USER to userStyle
        )
        val invalid = candidates.firstOrNull { (candidateTarget, style) ->
            style.fillMode.usesCode &&
                SafeBubbleCss.compile(style.codeCss, candidateTarget).errors.isNotEmpty()
        } ?: return true

        target = invalid.first
        renderTarget()
        val errors = SafeBubbleCss.compile(invalid.second.codeCss, invalid.first).errors
        val label = if (invalid.first == BubbleStyleStorage.Target.FRIEND) "住户" else "我"
        Toast.makeText(
            this,
            "$label 的代码还有错误：${errors.firstOrNull() ?: "请检查格式"}",
            Toast.LENGTH_LONG
        ).show()
        return false
    }

    private fun persistTarget(
        target: BubbleStyleStorage.Target,
        style: BubbleStyleStorage.BubbleStyle
    ) {
        val previous = savedStyleFor(target)
        val persisted = if (style == storage.defaultStyle(target)) {
            storage.resetStyle(friendId, target)
            storage.defaultStyle(target)
        } else {
            storage.saveStyle(friendId, target, style)
            style
        }
        if (previous.imagePath.isNotBlank() && previous.imagePath != persisted.imagePath) {
            imageAssets.deleteManagedPath(previous.imagePath)
        }
        setSavedStyleFor(target, persisted)
    }

    private fun updateStatusLabel() {
        val saved = storage.hasCustomStyle(friendId, target)
        val targetLabel = if (target == BubbleStyleStorage.Target.FRIEND) "住户" else "我"
        statusLabel.text = when {
            currentDirty() -> "$targetLabel：有尚未保存的调整"
            saved -> "$targetLabel：正在使用已保存的预览样式"
            else -> "$targetLabel：正在使用当前默认样式"
        }
    }

    private fun createTargetButton(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
    }

    private fun updateTargetButton(button: TextView, selected: Boolean) {
        button.setTextColor(if (selected) c.textPrimary else c.textSecondary)
        button.background = roundedBackground(
            if (selected) c.accent else c.card,
            dp(13).toFloat(),
            if (selected) c.accentStrong else c.border
        )
    }

    private fun updatePreviewLengthButtons() {
        if (!::shortPreviewButton.isInitialized || !::longPreviewButton.isInitialized) return
        updateTargetButton(shortPreviewButton, !longMessagePreview)
        updateTargetButton(longPreviewButton, longMessagePreview)
    }

    private fun createActionButton(text: String, primary: Boolean, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(if (primary) c.textPrimary else c.textSecondary)
            background = roundedBackground(
                if (primary) c.accent else c.card,
                dp(13).toFloat(),
                if (primary) c.accentStrong else c.border
            )
            setOnClickListener { onClick() }
        }

    private fun roundedBackground(fill: Int, radius: Float, stroke: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radius
            setStroke(dp(1), stroke)
        }

    private fun Int.toOpaqueRgb(): Int = Color.rgb(Color.red(this), Color.green(this), Color.blue(this))

    private fun requestClose() {
        if (!hasAnyDirty()) {
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("还有未保存的调整")
            .setMessage("离开后，本次预览中的修改不会保存。")
            .setNegativeButton("继续编辑", null)
            .setNeutralButton("放弃离开") { _, _ ->
                deleteUnsavedImage(friendStyle.imagePath, savedFriendStyle.imagePath)
                deleteUnsavedImage(userStyle.imagePath, savedUserStyle.imagePath)
                finish()
            }
            .setPositiveButton("保存并离开") { _, _ ->
                if (validateCodeStylesForSave()) {
                    persistTarget(BubbleStyleStorage.Target.FRIEND, friendStyle)
                    persistTarget(BubbleStyleStorage.Target.USER, userStyle)
                    friendDirty = false
                    userDirty = false
                    finish()
                }
            }
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = requestClose()

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()
}
