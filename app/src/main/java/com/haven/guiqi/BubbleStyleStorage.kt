package com.haven.guiqi

import android.content.Context
import android.graphics.Color
import java.io.File

/**
 * 普通文字气泡的独立样式存储。
 *
 * 每位住户、用户侧与住户侧分别保存，互不覆盖。支持普通颜色、图片、
 * 安全代码，以及图片叠加安全代码四种模式。
 */
class BubbleStyleStorage(context: Context) {

    enum class Target(val storageValue: String) {
        FRIEND("friend"),
        USER("user")
    }

    enum class FillMode(val storageValue: String) {
        BASIC("basic"),
        IMAGE("image"),
        CODE("code"),
        IMAGE_CODE("image_code");

        val usesImage: Boolean get() = this == IMAGE || this == IMAGE_CODE
        val usesCode: Boolean get() = this == CODE || this == IMAGE_CODE

        companion object {
            fun fromStorage(value: String?): FillMode =
                values().firstOrNull { it.storageValue == value } ?: BASIC
        }
    }

    /**
     * 图片素材的适配方式。
     *
     * SMART_FRAME 会自动寻找素材中央最平静的横、竖窄带，只拉伸这两条空白带；
     * 装饰和像素图案保持等比缩放。NINE_SLICE 保留原来的手动九宫格方式。
     */
    enum class ImageRenderMode(val storageValue: String) {
        SMART_FRAME("smart_frame"),
        NINE_SLICE("nine_slice");

        companion object {
            fun fromStorage(value: String?): ImageRenderMode =
                values().firstOrNull { it.storageValue == value } ?: SMART_FRAME
        }
    }

    data class BubbleStyle(
        /** 不含透明度的 RGB 颜色。 */
        val backgroundColor: Int,
        val backgroundOpacityPercent: Int,
        val textColor: Int,
        val cornerRadiusDp: Int,
        val anchorCornerRadiusDp: Int,
        val borderWidthDp: Int,
        val borderColor: Int,
        val shadowRadiusDp: Int,
        val shadowOpacityPercent: Int,
        val fillMode: FillMode = FillMode.BASIC,
        val imagePath: String = "",
        val imageRenderMode: ImageRenderMode = ImageRenderMode.SMART_FRAME,
        /** 图片本体透明度；只影响素材，不改变文字颜色。 */
        val imageOpacityPercent: Int = DEFAULT_IMAGE_OPACITY_PERCENT,
        /** 相对对应头像的水平位置：负数靠近头像，正数远离头像。 */
        val imageAvatarOffsetDp: Int = DEFAULT_IMAGE_AVATAR_OFFSET_DP,
        /** 垂直位置：负数上移，正数下移。 */
        val imageVerticalOffsetDp: Int = DEFAULT_IMAGE_VERTICAL_OFFSET_DP,
        /** 源图四边固定区；中间矩形是允许拉伸的区域。 */
        val imageFixedLeftPercent: Int = DEFAULT_IMAGE_FIXED_PERCENT,
        val imageFixedTopPercent: Int = DEFAULT_IMAGE_FIXED_PERCENT,
        val imageFixedRightPercent: Int = DEFAULT_IMAGE_FIXED_PERCENT,
        val imageFixedBottomPercent: Int = DEFAULT_IMAGE_FIXED_PERCENT,
        /** 文字安全区，直接映射成真实聊天 TextView 的四边内边距。 */
        val imagePaddingLeftDp: Int = DEFAULT_IMAGE_HORIZONTAL_PADDING_DP,
        val imagePaddingTopDp: Int = DEFAULT_IMAGE_VERTICAL_PADDING_DP,
        val imagePaddingRightDp: Int = DEFAULT_IMAGE_HORIZONTAL_PADDING_DP,
        val imagePaddingBottomDp: Int = DEFAULT_IMAGE_VERTICAL_PADDING_DP,
        /** 只会交给 SafeBubbleCss 解析，不会执行任意代码。 */
        val codeCss: String = ""
    )

    data class ResidentCodeDraft(
        val css: String,
        val createdAt: Long,
        val warnings: List<String>
    )

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val imageAssets by lazy { BubbleImageAssetStorage(appContext) }

    fun getStyle(friendId: String, target: Target): BubbleStyle {
        val defaults = defaultStyle(target)
        if (!hasCustomStyle(friendId, target)) return defaults
        val prefix = keyPrefix(friendId, target)
        val imagePath = prefs.getString(prefix + KEY_IMAGE_PATH, "").orEmpty()
        val requestedMode = FillMode.fromStorage(prefs.getString(prefix + KEY_FILL_MODE, null))
        val hasImage = File(imagePath).isFile
        val resolvedMode = when (requestedMode) {
            FillMode.IMAGE -> if (hasImage) FillMode.IMAGE else FillMode.BASIC
            FillMode.IMAGE_CODE -> if (hasImage) FillMode.IMAGE_CODE else FillMode.CODE
            else -> requestedMode
        }
        return BubbleStyle(
            backgroundColor = prefs.getInt(prefix + KEY_BACKGROUND_COLOR, defaults.backgroundColor)
                .withOpaqueAlpha(),
            backgroundOpacityPercent = prefs.getInt(
                prefix + KEY_BACKGROUND_OPACITY,
                defaults.backgroundOpacityPercent
            ).coerceIn(MIN_OPACITY_PERCENT, MAX_OPACITY_PERCENT),
            textColor = prefs.getInt(prefix + KEY_TEXT_COLOR, defaults.textColor),
            cornerRadiusDp = prefs.getInt(prefix + KEY_CORNER_RADIUS, defaults.cornerRadiusDp)
                .coerceIn(MIN_CORNER_RADIUS_DP, MAX_CORNER_RADIUS_DP),
            anchorCornerRadiusDp = prefs.getInt(
                prefix + KEY_ANCHOR_CORNER_RADIUS,
                defaults.anchorCornerRadiusDp
            ).coerceIn(MIN_CORNER_RADIUS_DP, MAX_CORNER_RADIUS_DP),
            borderWidthDp = prefs.getInt(prefix + KEY_BORDER_WIDTH, defaults.borderWidthDp)
                .coerceIn(MIN_BORDER_WIDTH_DP, MAX_BORDER_WIDTH_DP),
            borderColor = prefs.getInt(prefix + KEY_BORDER_COLOR, defaults.borderColor),
            shadowRadiusDp = prefs.getInt(prefix + KEY_SHADOW_RADIUS, defaults.shadowRadiusDp)
                .coerceIn(MIN_SHADOW_RADIUS_DP, MAX_SHADOW_RADIUS_DP),
            shadowOpacityPercent = prefs.getInt(
                prefix + KEY_SHADOW_OPACITY,
                defaults.shadowOpacityPercent
            ).coerceIn(MIN_SHADOW_OPACITY_PERCENT, MAX_SHADOW_OPACITY_PERCENT),
            fillMode = resolvedMode,
            imagePath = imagePath,
            imageRenderMode = ImageRenderMode.fromStorage(
                prefs.getString(prefix + KEY_IMAGE_RENDER_MODE, null)
            ),
            imageOpacityPercent = prefs.getInt(
                prefix + KEY_IMAGE_OPACITY,
                defaults.imageOpacityPercent
            ).coerceIn(MIN_IMAGE_OPACITY_PERCENT, MAX_IMAGE_OPACITY_PERCENT),
            imageAvatarOffsetDp = prefs.getInt(
                prefix + KEY_IMAGE_AVATAR_OFFSET,
                defaults.imageAvatarOffsetDp
            ).coerceIn(MIN_IMAGE_AVATAR_OFFSET_DP, MAX_IMAGE_AVATAR_OFFSET_DP),
            imageVerticalOffsetDp = prefs.getInt(
                prefix + KEY_IMAGE_VERTICAL_OFFSET,
                defaults.imageVerticalOffsetDp
            ).coerceIn(MIN_IMAGE_VERTICAL_OFFSET_DP, MAX_IMAGE_VERTICAL_OFFSET_DP),
            imageFixedLeftPercent = prefs.getInt(
                prefix + KEY_IMAGE_FIXED_LEFT,
                defaults.imageFixedLeftPercent
            ).coerceIn(MIN_IMAGE_FIXED_PERCENT, MAX_IMAGE_FIXED_PERCENT),
            imageFixedTopPercent = prefs.getInt(
                prefix + KEY_IMAGE_FIXED_TOP,
                defaults.imageFixedTopPercent
            ).coerceIn(MIN_IMAGE_FIXED_PERCENT, MAX_IMAGE_FIXED_PERCENT),
            imageFixedRightPercent = prefs.getInt(
                prefix + KEY_IMAGE_FIXED_RIGHT,
                defaults.imageFixedRightPercent
            ).coerceIn(MIN_IMAGE_FIXED_PERCENT, MAX_IMAGE_FIXED_PERCENT),
            imageFixedBottomPercent = prefs.getInt(
                prefix + KEY_IMAGE_FIXED_BOTTOM,
                defaults.imageFixedBottomPercent
            ).coerceIn(MIN_IMAGE_FIXED_PERCENT, MAX_IMAGE_FIXED_PERCENT),
            imagePaddingLeftDp = prefs.getInt(
                prefix + KEY_IMAGE_PADDING_LEFT,
                defaults.imagePaddingLeftDp
            ).coerceIn(MIN_IMAGE_PADDING_DP, MAX_IMAGE_PADDING_DP),
            imagePaddingTopDp = prefs.getInt(
                prefix + KEY_IMAGE_PADDING_TOP,
                defaults.imagePaddingTopDp
            ).coerceIn(MIN_IMAGE_PADDING_DP, MAX_IMAGE_PADDING_DP),
            imagePaddingRightDp = prefs.getInt(
                prefix + KEY_IMAGE_PADDING_RIGHT,
                defaults.imagePaddingRightDp
            ).coerceIn(MIN_IMAGE_PADDING_DP, MAX_IMAGE_PADDING_DP),
            imagePaddingBottomDp = prefs.getInt(
                prefix + KEY_IMAGE_PADDING_BOTTOM,
                defaults.imagePaddingBottomDp
            ).coerceIn(MIN_IMAGE_PADDING_DP, MAX_IMAGE_PADDING_DP),
            codeCss = prefs.getString(prefix + KEY_CODE_CSS, defaults.codeCss).orEmpty()
                .take(MAX_CODE_CSS_LENGTH)
        )
    }

    fun saveStyle(friendId: String, target: Target, style: BubbleStyle) {
        val prefix = keyPrefix(friendId, target)
        val hasImage = File(style.imagePath).isFile
        val resolvedMode = when (style.fillMode) {
            FillMode.IMAGE -> if (hasImage) FillMode.IMAGE else FillMode.BASIC
            FillMode.IMAGE_CODE -> if (hasImage) FillMode.IMAGE_CODE else FillMode.CODE
            else -> style.fillMode
        }
        prefs.edit()
            .putBoolean(prefix + KEY_CUSTOM, true)
            .putInt(prefix + KEY_BACKGROUND_COLOR, style.backgroundColor.withOpaqueAlpha())
            .putInt(
                prefix + KEY_BACKGROUND_OPACITY,
                style.backgroundOpacityPercent.coerceIn(MIN_OPACITY_PERCENT, MAX_OPACITY_PERCENT)
            )
            .putInt(prefix + KEY_TEXT_COLOR, style.textColor)
            .putInt(
                prefix + KEY_CORNER_RADIUS,
                style.cornerRadiusDp.coerceIn(MIN_CORNER_RADIUS_DP, MAX_CORNER_RADIUS_DP)
            )
            .putInt(
                prefix + KEY_ANCHOR_CORNER_RADIUS,
                style.anchorCornerRadiusDp.coerceIn(MIN_CORNER_RADIUS_DP, MAX_CORNER_RADIUS_DP)
            )
            .putInt(
                prefix + KEY_BORDER_WIDTH,
                style.borderWidthDp.coerceIn(MIN_BORDER_WIDTH_DP, MAX_BORDER_WIDTH_DP)
            )
            .putInt(prefix + KEY_BORDER_COLOR, style.borderColor)
            .putInt(
                prefix + KEY_SHADOW_RADIUS,
                style.shadowRadiusDp.coerceIn(MIN_SHADOW_RADIUS_DP, MAX_SHADOW_RADIUS_DP)
            )
            .putInt(
                prefix + KEY_SHADOW_OPACITY,
                style.shadowOpacityPercent.coerceIn(
                    MIN_SHADOW_OPACITY_PERCENT,
                    MAX_SHADOW_OPACITY_PERCENT
                )
            )
            .putString(prefix + KEY_FILL_MODE, resolvedMode.storageValue)
            .putString(prefix + KEY_IMAGE_PATH, style.imagePath)
            .putString(prefix + KEY_IMAGE_RENDER_MODE, style.imageRenderMode.storageValue)
            .putInt(
                prefix + KEY_IMAGE_OPACITY,
                style.imageOpacityPercent.coerceIn(
                    MIN_IMAGE_OPACITY_PERCENT,
                    MAX_IMAGE_OPACITY_PERCENT
                )
            )
            .putInt(
                prefix + KEY_IMAGE_AVATAR_OFFSET,
                style.imageAvatarOffsetDp.coerceIn(
                    MIN_IMAGE_AVATAR_OFFSET_DP,
                    MAX_IMAGE_AVATAR_OFFSET_DP
                )
            )
            .putInt(
                prefix + KEY_IMAGE_VERTICAL_OFFSET,
                style.imageVerticalOffsetDp.coerceIn(
                    MIN_IMAGE_VERTICAL_OFFSET_DP,
                    MAX_IMAGE_VERTICAL_OFFSET_DP
                )
            )
            .putInt(
                prefix + KEY_IMAGE_FIXED_LEFT,
                style.imageFixedLeftPercent.coerceIn(MIN_IMAGE_FIXED_PERCENT, MAX_IMAGE_FIXED_PERCENT)
            )
            .putInt(
                prefix + KEY_IMAGE_FIXED_TOP,
                style.imageFixedTopPercent.coerceIn(MIN_IMAGE_FIXED_PERCENT, MAX_IMAGE_FIXED_PERCENT)
            )
            .putInt(
                prefix + KEY_IMAGE_FIXED_RIGHT,
                style.imageFixedRightPercent.coerceIn(MIN_IMAGE_FIXED_PERCENT, MAX_IMAGE_FIXED_PERCENT)
            )
            .putInt(
                prefix + KEY_IMAGE_FIXED_BOTTOM,
                style.imageFixedBottomPercent.coerceIn(MIN_IMAGE_FIXED_PERCENT, MAX_IMAGE_FIXED_PERCENT)
            )
            .putInt(
                prefix + KEY_IMAGE_PADDING_LEFT,
                style.imagePaddingLeftDp.coerceIn(MIN_IMAGE_PADDING_DP, MAX_IMAGE_PADDING_DP)
            )
            .putInt(
                prefix + KEY_IMAGE_PADDING_TOP,
                style.imagePaddingTopDp.coerceIn(MIN_IMAGE_PADDING_DP, MAX_IMAGE_PADDING_DP)
            )
            .putInt(
                prefix + KEY_IMAGE_PADDING_RIGHT,
                style.imagePaddingRightDp.coerceIn(MIN_IMAGE_PADDING_DP, MAX_IMAGE_PADDING_DP)
            )
            .putInt(
                prefix + KEY_IMAGE_PADDING_BOTTOM,
                style.imagePaddingBottomDp.coerceIn(MIN_IMAGE_PADDING_DP, MAX_IMAGE_PADDING_DP)
            )
            .putString(prefix + KEY_CODE_CSS, style.codeCss.take(MAX_CODE_CSS_LENGTH))
            .apply()
    }

    fun resetStyle(friendId: String, target: Target) {
        val prefix = keyPrefix(friendId, target)
        val oldImagePath = prefs.getString(prefix + KEY_IMAGE_PATH, "").orEmpty()
        val editor = prefs.edit()
        listOf(
            KEY_CUSTOM,
            KEY_BACKGROUND_COLOR,
            KEY_BACKGROUND_OPACITY,
            KEY_TEXT_COLOR,
            KEY_CORNER_RADIUS,
            KEY_ANCHOR_CORNER_RADIUS,
            KEY_BORDER_WIDTH,
            KEY_BORDER_COLOR,
            KEY_SHADOW_RADIUS,
            KEY_SHADOW_OPACITY,
            KEY_FILL_MODE,
            KEY_IMAGE_PATH,
            KEY_IMAGE_RENDER_MODE,
            KEY_IMAGE_OPACITY,
            KEY_IMAGE_AVATAR_OFFSET,
            KEY_IMAGE_VERTICAL_OFFSET,
            KEY_IMAGE_FIXED_LEFT,
            KEY_IMAGE_FIXED_TOP,
            KEY_IMAGE_FIXED_RIGHT,
            KEY_IMAGE_FIXED_BOTTOM,
            KEY_IMAGE_PADDING_LEFT,
            KEY_IMAGE_PADDING_TOP,
            KEY_IMAGE_PADDING_RIGHT,
            KEY_IMAGE_PADDING_BOTTOM,
            KEY_CODE_CSS
        ).forEach { suffix -> editor.remove(prefix + suffix) }
        editor.apply()
        imageAssets.deleteManagedPath(oldImagePath)
    }

    fun hasCustomStyle(friendId: String, target: Target): Boolean =
        prefs.getBoolean(keyPrefix(friendId, target) + KEY_CUSTOM, false)

    /**
     * 住户自己提交的代码气泡只保存为候选草稿。
     * 这里永远不改当前生效样式，也不会接触用户侧气泡。
     */
    fun saveResidentCodeDraft(friendId: String, css: String, warnings: List<String>) {
        val prefix = keyPrefix(friendId, Target.FRIEND)
        prefs.edit()
            .putString(prefix + KEY_RESIDENT_DRAFT_CSS, css.trim().take(MAX_CODE_CSS_LENGTH))
            .putLong(prefix + KEY_RESIDENT_DRAFT_CREATED_AT, System.currentTimeMillis())
            .putString(
                prefix + KEY_RESIDENT_DRAFT_WARNINGS,
                warnings.distinct().joinToString(DRAFT_WARNING_SEPARATOR)
            )
            .apply()
    }

    fun getResidentCodeDraft(friendId: String): ResidentCodeDraft? {
        val prefix = keyPrefix(friendId, Target.FRIEND)
        val css = prefs.getString(prefix + KEY_RESIDENT_DRAFT_CSS, "").orEmpty().trim()
        if (css.isEmpty()) return null
        val warnings = prefs.getString(prefix + KEY_RESIDENT_DRAFT_WARNINGS, "")
            .orEmpty()
            .split(DRAFT_WARNING_SEPARATOR)
            .map(String::trim)
            .filter(String::isNotEmpty)
        return ResidentCodeDraft(
            css = css,
            createdAt = prefs.getLong(prefix + KEY_RESIDENT_DRAFT_CREATED_AT, 0L),
            warnings = warnings
        )
    }

    fun clearResidentCodeDraft(friendId: String) {
        val prefix = keyPrefix(friendId, Target.FRIEND)
        prefs.edit()
            .remove(prefix + KEY_RESIDENT_DRAFT_CSS)
            .remove(prefix + KEY_RESIDENT_DRAFT_CREATED_AT)
            .remove(prefix + KEY_RESIDENT_DRAFT_WARNINGS)
            .apply()
    }

    /** 下一次构建提示词时只读一次的校验回执。 */
    fun setResidentCodeDraftFeedback(friendId: String, feedback: String) {
        val key = keyPrefix(friendId, Target.FRIEND) + KEY_RESIDENT_DRAFT_FEEDBACK
        prefs.edit().putString(key, feedback.trim().take(MAX_DRAFT_FEEDBACK_LENGTH)).apply()
    }

    fun consumeResidentCodeDraftFeedback(friendId: String): String? {
        val key = keyPrefix(friendId, Target.FRIEND) + KEY_RESIDENT_DRAFT_FEEDBACK
        val feedback = prefs.getString(key, "").orEmpty().trim()
        if (feedback.isEmpty()) return null
        prefs.edit().remove(key).apply()
        return feedback
    }

    fun residentModeLabel(friendId: String): String = when (getStyle(friendId, Target.FRIEND).fillMode) {
        FillMode.BASIC -> "普通颜色"
        FillMode.IMAGE -> "图片气泡"
        FillMode.CODE -> "代码气泡"
        FillMode.IMAGE_CODE -> "图片＋代码"
    }

    /** [MY_BUBBLE_STYLE] 返回给住户查看的完整档案；只读。 */
    fun buildResidentCodeStyleInfo(friendId: String): String {
        val current = getStyle(friendId, Target.FRIEND)
        val activeCode = current.codeCss.trim()
        val draft = getResidentCodeDraft(friendId)
        val currentCodeText = if (activeCode.isEmpty()) {
            "（当前没有保存过代码）"
        } else {
            activeCode
        }
        val draftText = if (draft == null) {
            "（当前没有待确认草稿）"
        } else {
            buildString {
                append(draft.css)
                if (draft.warnings.isNotEmpty()) {
                    append("\n\n校验提醒：\n")
                    draft.warnings.forEach { append("- ").append(it).append('\n') }
                }
            }.trim()
        }
        return """[我的代码气泡档案]
当前生效模式：${residentModeLabel(friendId)}

当前保存的代码（即使现在穿普通气泡，它也可能仍在衣柜里）：
$currentCodeText

等待人类预览确认的候选草稿：
$draftText

${SafeBubbleCss.supportedSyntaxText()}

提交格式：
[BUBBLE_STYLE_DRAFT]
.bubble.ai {
  background: #F5F0FF;
  text-color: #463D59;
  border: 1dp solid #CDBFE3;
  radius: 22dp;
  near-avatar-radius: 8dp;
  padding: 13dp 17dp;
  shadow: 0dp 4dp 12dp 18%;
  font-size: 16sp;
  font-weight: bold;
  line-height: 1.38;
  letter-spacing: 0.2sp;
}
[/BUBBLE_STYLE_DRAFT]

这一步只提交我的候选草稿，不会直接应用，也不能修改用户气泡。"""
    }

    fun defaultStyle(target: Target): BubbleStyle {
        val colors = ThemeHelper.getColors(appContext)
        return when (target) {
            Target.FRIEND -> BubbleStyle(
                backgroundColor = Color.rgb(0xB3, 0xA0, 0xFF),
                backgroundOpacityPercent = 8,
                textColor = colors.textOnAccent,
                cornerRadiusDp = 14,
                anchorCornerRadiusDp = 4,
                borderWidthDp = 0,
                borderColor = colors.borderMedium,
                shadowRadiusDp = 0,
                shadowOpacityPercent = 0
            )
            Target.USER -> BubbleStyle(
                backgroundColor = Color.WHITE,
                backgroundOpacityPercent = 7,
                textColor = colors.textOnAccent,
                cornerRadiusDp = 14,
                anchorCornerRadiusDp = 4,
                borderWidthDp = 0,
                borderColor = colors.borderMedium,
                shadowRadiusDp = 0,
                shadowOpacityPercent = 0
            )
        }
    }

    private fun keyPrefix(friendId: String, target: Target): String =
        "bubble_style_${target.storageValue}_${friendId}_"

    private fun Int.withOpaqueAlpha(): Int = Color.rgb(Color.red(this), Color.green(this), Color.blue(this))

    companion object {
        private const val PREFS_NAME = "haven_bubble_style"
        private const val KEY_CUSTOM = "custom"
        private const val KEY_BACKGROUND_COLOR = "background_color"
        private const val KEY_BACKGROUND_OPACITY = "background_opacity"
        private const val KEY_TEXT_COLOR = "text_color"
        private const val KEY_CORNER_RADIUS = "corner_radius"
        private const val KEY_ANCHOR_CORNER_RADIUS = "anchor_corner_radius"
        private const val KEY_BORDER_WIDTH = "border_width"
        private const val KEY_BORDER_COLOR = "border_color"
        private const val KEY_SHADOW_RADIUS = "shadow_radius"
        private const val KEY_SHADOW_OPACITY = "shadow_opacity"
        private const val KEY_FILL_MODE = "fill_mode"
        private const val KEY_IMAGE_PATH = "image_path"
        private const val KEY_IMAGE_RENDER_MODE = "image_render_mode"
        private const val KEY_IMAGE_OPACITY = "image_opacity"
        private const val KEY_IMAGE_AVATAR_OFFSET = "image_avatar_offset"
        private const val KEY_IMAGE_VERTICAL_OFFSET = "image_vertical_offset"
        private const val KEY_IMAGE_FIXED_LEFT = "image_fixed_left"
        private const val KEY_IMAGE_FIXED_TOP = "image_fixed_top"
        private const val KEY_IMAGE_FIXED_RIGHT = "image_fixed_right"
        private const val KEY_IMAGE_FIXED_BOTTOM = "image_fixed_bottom"
        private const val KEY_IMAGE_PADDING_LEFT = "image_padding_left"
        private const val KEY_IMAGE_PADDING_TOP = "image_padding_top"
        private const val KEY_IMAGE_PADDING_RIGHT = "image_padding_right"
        private const val KEY_IMAGE_PADDING_BOTTOM = "image_padding_bottom"
        private const val KEY_CODE_CSS = "code_css"
        private const val KEY_RESIDENT_DRAFT_CSS = "resident_draft_css"
        private const val KEY_RESIDENT_DRAFT_CREATED_AT = "resident_draft_created_at"
        private const val KEY_RESIDENT_DRAFT_WARNINGS = "resident_draft_warnings"
        private const val KEY_RESIDENT_DRAFT_FEEDBACK = "resident_draft_feedback"
        private const val DRAFT_WARNING_SEPARATOR = "\u001F"
        private const val MAX_DRAFT_FEEDBACK_LENGTH = 2_000
        const val MAX_CODE_CSS_LENGTH = 4_000

        const val MIN_OPACITY_PERCENT = 0
        const val MAX_OPACITY_PERCENT = 100
        const val MIN_CORNER_RADIUS_DP = 0
        const val MAX_CORNER_RADIUS_DP = 32
        const val MIN_BORDER_WIDTH_DP = 0
        const val MAX_BORDER_WIDTH_DP = 4
        const val MIN_SHADOW_RADIUS_DP = 0
        const val MAX_SHADOW_RADIUS_DP = 16
        const val MIN_SHADOW_OPACITY_PERCENT = 0
        const val MAX_SHADOW_OPACITY_PERCENT = 60

        const val MIN_IMAGE_OPACITY_PERCENT = 0
        const val MAX_IMAGE_OPACITY_PERCENT = 100
        const val DEFAULT_IMAGE_OPACITY_PERCENT = 100
        const val MIN_IMAGE_AVATAR_OFFSET_DP = -40
        const val MAX_IMAGE_AVATAR_OFFSET_DP = 40
        const val DEFAULT_IMAGE_AVATAR_OFFSET_DP = 0
        const val MIN_IMAGE_VERTICAL_OFFSET_DP = -28
        const val MAX_IMAGE_VERTICAL_OFFSET_DP = 28
        const val DEFAULT_IMAGE_VERTICAL_OFFSET_DP = 0

        const val MIN_IMAGE_FIXED_PERCENT = 0
        const val MAX_IMAGE_FIXED_PERCENT = 45
        const val DEFAULT_IMAGE_FIXED_PERCENT = 25
        const val MIN_IMAGE_PADDING_DP = 0
        const val MAX_IMAGE_PADDING_DP = 48
        const val DEFAULT_IMAGE_HORIZONTAL_PADDING_DP = 12
        const val DEFAULT_IMAGE_VERTICAL_PADDING_DP = 8
    }
}
