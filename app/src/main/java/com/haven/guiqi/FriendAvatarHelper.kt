package com.haven.guiqi

import android.content.Context
import android.graphics.Outline
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import android.graphics.drawable.GradientDrawable
import java.io.File
import kotlin.math.ceil
import kotlin.math.floor

/**
 * FriendAvatarHelper — 统一的聊天头像 View 工厂。
 *
 * 头像本体始终保持固定尺寸；透明头像框可以缩放、移动。工厂会根据头像框的最终边界
 * 自动扩展外层“头像舞台”，避免聊天界面仍用 30dp 方框把超出的装饰裁掉。
 */
object FriendAvatarHelper {

    data class StageGeometry(
        val widthPx: Int,
        val heightPx: Int,
        val avatarLeftPx: Int,
        val avatarTopPx: Int
    )

    fun create(
        context: Context,
        friend: Friend,
        sizeDp: Int = 30,
        framePath: String = "",
        frameScalePercent: Int = ChatAppearanceStorage.DEFAULT_AVATAR_FRAME_SCALE_PERCENT,
        frameOffsetXPercent: Int = ChatAppearanceStorage.DEFAULT_AVATAR_FRAME_OFFSET_PERCENT,
        frameOffsetYPercent: Int = ChatAppearanceStorage.DEFAULT_AVATAR_FRAME_OFFSET_PERCENT,
        avatarShape: ChatAppearanceStorage.AvatarShape = ChatAppearanceStorage.AvatarShape.CIRCLE
    ): View = create(
        context,
        friend.avatarPath,
        friend.icon,
        sizeDp,
        framePath,
        frameScalePercent,
        frameOffsetXPercent,
        frameOffsetYPercent,
        avatarShape
    )

    fun create(
        context: Context,
        avatarPath: String,
        icon: String,
        sizeDp: Int = 30,
        framePath: String = "",
        frameScalePercent: Int = ChatAppearanceStorage.DEFAULT_AVATAR_FRAME_SCALE_PERCENT,
        frameOffsetXPercent: Int = ChatAppearanceStorage.DEFAULT_AVATAR_FRAME_OFFSET_PERCENT,
        frameOffsetYPercent: Int = ChatAppearanceStorage.DEFAULT_AVATAR_FRAME_OFFSET_PERCENT,
        avatarShape: ChatAppearanceStorage.AvatarShape = ChatAppearanceStorage.AvatarShape.CIRCLE
    ): View {
        val density = context.resources.displayMetrics.density
        val size = (sizeDp * density).toInt().coerceAtLeast(1)
        val avatar = createAvatarBody(context, avatarPath, icon, sizeDp, avatarShape)
        val frameFile = framePath.takeIf { it.isNotEmpty() }?.let(::File)?.takeIf { it.isFile }

        if (frameFile == null) {
            avatar.layoutParams = LinearLayout.LayoutParams(size, size)
            return avatar
        }

        val transform = ChatAppearanceStorage.AvatarFrameTransform(
            scalePercent = frameScalePercent,
            offsetXPercent = frameOffsetXPercent,
            offsetYPercent = frameOffsetYPercent
        )
        val geometry = calculateStageGeometry(size, transform)
        val normalizedScale = transform.scalePercent.coerceIn(
            ChatAppearanceStorage.MIN_AVATAR_FRAME_SCALE_PERCENT,
            ChatAppearanceStorage.MAX_AVATAR_FRAME_SCALE_PERCENT
        ) / 100f
        val normalizedOffsetX = transform.offsetXPercent.coerceIn(
            ChatAppearanceStorage.MIN_AVATAR_FRAME_OFFSET_PERCENT,
            ChatAppearanceStorage.MAX_AVATAR_FRAME_OFFSET_PERCENT
        ) / 100f
        val normalizedOffsetY = transform.offsetYPercent.coerceIn(
            ChatAppearanceStorage.MIN_AVATAR_FRAME_OFFSET_PERCENT,
            ChatAppearanceStorage.MAX_AVATAR_FRAME_OFFSET_PERCENT
        ) / 100f

        return FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(geometry.widthPx, geometry.heightPx)
            clipChildren = false
            clipToPadding = false

            addView(avatar, FrameLayout.LayoutParams(size, size).apply {
                leftMargin = geometry.avatarLeftPx
                topMargin = geometry.avatarTopPx
            })

            addView(AnimatedAssetImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_XY
                scaleX = normalizedScale
                scaleY = normalizedScale
                translationX = size * normalizedOffsetX
                translationY = size * normalizedOffsetY
                setAssetFile(frameFile)
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, FrameLayout.LayoutParams(size, size).apply {
                leftMargin = geometry.avatarLeftPx
                topMargin = geometry.avatarTopPx
                gravity = Gravity.TOP or Gravity.START
            })
        }
    }

    /** 创建用户头像 View（从 SharedPreferences 读），可套用与住户相同的头像框。 */
    fun createUserAvatar(
        context: Context,
        sizeDp: Int = 30,
        framePath: String = "",
        frameScalePercent: Int = ChatAppearanceStorage.DEFAULT_AVATAR_FRAME_SCALE_PERCENT,
        frameOffsetXPercent: Int = ChatAppearanceStorage.DEFAULT_AVATAR_FRAME_OFFSET_PERCENT,
        frameOffsetYPercent: Int = ChatAppearanceStorage.DEFAULT_AVATAR_FRAME_OFFSET_PERCENT,
        avatarShape: ChatAppearanceStorage.AvatarShape = ChatAppearanceStorage.AvatarShape.CIRCLE
    ): View {
        val prefs = context.getSharedPreferences("haven_prefs", Context.MODE_PRIVATE)
        val imagePath = prefs.getString("user_avatar_path", "") ?: ""
        val emoji = prefs.getString("user_avatar", "") ?: ""
        val userName = prefs.getString("user_name", "") ?: ""
        val fallback = when {
            emoji.isNotEmpty() -> emoji
            userName.isNotEmpty() -> userName.first().toString()
            else -> "?"
        }
        return create(
            context = context,
            avatarPath = imagePath,
            icon = fallback,
            sizeDp = sizeDp,
            framePath = framePath,
            frameScalePercent = frameScalePercent,
            frameOffsetXPercent = frameOffsetXPercent,
            frameOffsetYPercent = frameOffsetYPercent,
            avatarShape = avatarShape
        )
    }

    /**
     * 计算头像本体与变换后头像框的并集矩形。
     * 返回值全部为非负整数像素，且会向外取整，避免小数边缘被裁掉一条线。
     */
    fun calculateStageGeometry(
        avatarSizePx: Int,
        transform: ChatAppearanceStorage.AvatarFrameTransform
    ): StageGeometry {
        val size = avatarSizePx.coerceAtLeast(1).toFloat()
        val scale = transform.scalePercent.coerceIn(
            ChatAppearanceStorage.MIN_AVATAR_FRAME_SCALE_PERCENT,
            ChatAppearanceStorage.MAX_AVATAR_FRAME_SCALE_PERCENT
        ) / 100f
        val dx = size * transform.offsetXPercent.coerceIn(
            ChatAppearanceStorage.MIN_AVATAR_FRAME_OFFSET_PERCENT,
            ChatAppearanceStorage.MAX_AVATAR_FRAME_OFFSET_PERCENT
        ) / 100f
        val dy = size * transform.offsetYPercent.coerceIn(
            ChatAppearanceStorage.MIN_AVATAR_FRAME_OFFSET_PERCENT,
            ChatAppearanceStorage.MAX_AVATAR_FRAME_OFFSET_PERCENT
        ) / 100f

        // scaleX/scaleY 默认围绕 View 中心缩放。
        val frameLeft = (size - size * scale) / 2f + dx
        val frameTop = (size - size * scale) / 2f + dy
        val frameRight = frameLeft + size * scale
        val frameBottom = frameTop + size * scale

        val unionLeft = floor(minOf(0f, frameLeft)).toInt()
        val unionTop = floor(minOf(0f, frameTop)).toInt()
        val unionRight = ceil(maxOf(size, frameRight)).toInt()
        val unionBottom = ceil(maxOf(size, frameBottom)).toInt()

        return StageGeometry(
            widthPx = (unionRight - unionLeft).coerceAtLeast(1),
            heightPx = (unionBottom - unionTop).coerceAtLeast(1),
            avatarLeftPx = -unionLeft,
            avatarTopPx = -unionTop
        )
    }

    private fun createAvatarBody(
        context: Context,
        avatarPath: String,
        icon: String,
        sizeDp: Int,
        avatarShape: ChatAppearanceStorage.AvatarShape
    ): View {
        val colors = ThemeHelper.getColors(context)
        val shapeProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                when (avatarShape) {
                    ChatAppearanceStorage.AvatarShape.CIRCLE ->
                        outline.setOval(0, 0, view.width, view.height)
                    ChatAppearanceStorage.AvatarShape.SQUARE ->
                        outline.setRect(0, 0, view.width, view.height)
                }
            }
        }

        return if (avatarPath.isNotEmpty() && File(avatarPath).isFile) {
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageURI(Uri.fromFile(File(avatarPath)))
                clipToOutline = true
                outlineProvider = shapeProvider
            }
        } else {
            TextView(context).apply {
                gravity = Gravity.CENTER
                text = icon
                textSize = sizeDp * 0.4f
                setTextColor(colors.accentStrong)
                background = GradientDrawable().apply {
                    shape = if (avatarShape == ChatAppearanceStorage.AvatarShape.CIRCLE) {
                        GradientDrawable.OVAL
                    } else {
                        GradientDrawable.RECTANGLE
                    }
                    setColor(ContextCompat.getColor(context, R.color.haven_desktop_icon_circle_bg))
                    setStroke(
                        (context.resources.displayMetrics.density).toInt().coerceAtLeast(1),
                        ContextCompat.getColor(context, R.color.haven_desktop_icon_circle_border)
                    )
                }
                clipToOutline = true
                outlineProvider = shapeProvider
            }
        }
    }
}
