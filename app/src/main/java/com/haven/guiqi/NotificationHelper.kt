package com.haven.guiqi

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * NotificationHelper - 通知管理
 *
 * 负责创建通知渠道和发送通知
 * 归栖的通知跟真的手机短信通知一样：
 *   - 显示好友头像（用文字代替，因为我们的头像是 emoji）
 *   - 显示好友名字
 *   - 显示消息预览
 *   - 点击通知直接跳转到跟这个好友的聊天界面
 *
 * 通知渠道：
 *   - haven_chat: 聊天消息通知（重要度高，会弹出来）
 *   - haven_system: 系统通知（重要度低，静默）
 */
class NotificationHelper(private val context: Context) {

    companion object {
        const val CHANNEL_CHAT = "haven_chat"
        const val CHANNEL_SYSTEM = "haven_system"

        /**
         * 创建通知渠道（应用启动时调用一次就行）
         * Android 8.0+ 必须先创建渠道才能发通知
         */
        fun createChannels(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

                // 聊天消息渠道（重要度高，会弹出来、有声音）
                val chatChannel = NotificationChannel(
                    CHANNEL_CHAT,
                    "聊天消息",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "来自 AI 的新消息"
                    enableVibration(true)
                    setShowBadge(true)
                }

                // 系统通知渠道（重要度低，静默）
                val systemChannel = NotificationChannel(
                    CHANNEL_SYSTEM,
                    "系统通知",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "归栖系统通知"
                }

                manager.createNotificationChannel(chatChannel)
                manager.createNotificationChannel(systemChannel)
            }
        }
    }

    /**
     * 发送一条聊天消息通知
     *
     * @param friendId 好友 ID（用于点击跳转）
     * @param friendName 好友名字（通知标题）
     * @param friendIcon 好友头像字符
     * @param message 消息内容（通知正文）
     */
    fun sendChatNotification(
        friendId: String,
        friendName: String,
        friendIcon: String,
        message: String
    ) {
        // 检查通知权限
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return
        }

        // 点击通知时打开聊天界面
        val intent = Intent(context, ChatConversationActivity::class.java).apply {
            putExtra("friend_id", friendId)
            putExtra("friend_name", friendName)
            putExtra("friend_icon", friendIcon)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, friendId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 消息预览（太长截断）
        val preview = if (message.length > 100) message.substring(0, 100) + "..." else message

        // 构建通知
        val notification = NotificationCompat.Builder(context, CHANNEL_CHAT)
            .setSmallIcon(android.R.drawable.ic_dialog_info)  // 用系统图标（以后可以换自定义的）
            .setContentTitle("$friendIcon $friendName")
            .setContentText(preview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)  // 点击后自动消失
            .setDefaults(NotificationCompat.DEFAULT_ALL)  // 声音+震动+灯光
            .build()

        // 发送通知（用 friendId 的 hashCode 作为通知 ID，同一个好友的通知会覆盖）
        try {
            NotificationManagerCompat.from(context).notify(friendId.hashCode(), notification)
        } catch (e: SecurityException) {
            // 没有通知权限，静默忽略
        }
    }

    /**
     * 发送系统通知（比如梦境提醒、记忆提醒等）
     */
    fun sendSystemNotification(title: String, message: String) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_SYSTEM)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(
                System.currentTimeMillis().toInt(), notification
            )
        } catch (e: SecurityException) {
            // 静默
        }
    }
}