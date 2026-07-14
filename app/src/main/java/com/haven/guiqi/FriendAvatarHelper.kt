package com.haven.guiqi

import android.content.Context
import android.graphics.Outline
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

/**
 * FriendAvatarHelper — 统一的好友头像 View 工厂
 *
 * 所有显示好友头像的地方都调这里。
 * 有 avatarPath → 圆形图片
 * 没有 → 显示 icon 字符
 * 以后加头像框改这一个文件。
 */
object FriendAvatarHelper {

    /**
     * 创建好友头像 View
     * @param friend 好友对象（读 avatarPath 和 icon）
     * @param sizeDp 头像大小（dp）
     */
    fun create(context: Context, friend: Friend, sizeDp: Int = 30): View {
        return create(context, friend.avatarPath, friend.icon, sizeDp)
    }

    /**
     * 创建头像 View（手动传参版本）
     * @param avatarPath 图片路径，空则用 icon
     * @param icon emoji 字符
     * @param sizeDp 大小
     */
    fun create(context: Context, avatarPath: String, icon: String, sizeDp: Int = 30): View {
        val dp = { v: Int -> (v * context.resources.displayMetrics.density).toInt() }
        val size = dp(sizeDp)
        val c = ThemeHelper.getColors(context)

        if (avatarPath.isNotEmpty()) {
            val file = File(avatarPath)
            if (file.exists()) {
                return ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(size, size)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageURI(Uri.fromFile(file))
                    clipToOutline = true
                    outlineProvider = object : ViewOutlineProvider() {
                        override fun getOutline(v: View, outline: Outline) {
                            outline.setOval(0, 0, v.width, v.height)
                        }
                    }
                }
            }
        }

        return TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(size, size)
            gravity = Gravity.CENTER
            text = icon; textSize = (sizeDp * 0.4f)
            setTextColor(c.accentStrong)
            setBackgroundResource(R.drawable.icon_bg)
        }
    }

    /**
     * 创建用户头像 View（从 SharedPreferences 读）
     * @param sizeDp 大小
     */
    fun createUserAvatar(context: Context, sizeDp: Int = 30): View {
        val prefs = context.getSharedPreferences("haven_prefs", Context.MODE_PRIVATE)
        val imagePath = prefs.getString("user_avatar_path", "") ?: ""
        val emoji = prefs.getString("user_avatar", "") ?: ""
        val userName = prefs.getString("user_name", "") ?: ""
        val fallback = when {
            emoji.isNotEmpty() -> emoji
            userName.isNotEmpty() -> userName.first().toString()
            else -> "?"
        }
        return create(context, imagePath, fallback, sizeDp)
    }
}