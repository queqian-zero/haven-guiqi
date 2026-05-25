package com.haven.guiqi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * HavenService - 归栖前台服务
 *
 * 作用：
 * 1. 保活 —— 通知栏挂一条常驻通知，系统不容易杀
 * 2. 定时器 —— 以后用来执行 AI 的闹钟（REMIND_ME 指令）
 * 3. AI 主动发消息的载体 —— AI 设了提醒，时间到了这里负责调 API
 *
 * 现在先搭壳子：
 * - 启动后在通知栏显示"归栖运行中"
 * - 点击通知回到桌面
 * - 以后再加定时器和 AI 主动消息逻辑
 *
 * 启动方式：
 *   HavenService.start(context)  // 启动
 *   HavenService.stop(context)   // 停止
 */
class HavenService : Service() {

    companion object {
        const val CHANNEL_ID = "haven_foreground"
        const val NOTIFICATION_ID = 1001

        /**
         * 启动前台服务
         */
        fun start(context: Context) {
            val intent = Intent(context, HavenService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * 停止前台服务
         */
        fun stop(context: Context) {
            context.stopService(Intent(context, HavenService::class.java))
        }

        /**
         * 检查服务是否在运行
         */
        fun isRunning(context: Context): Boolean {
            val manager = context.getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
            @Suppress("DEPRECATION")
            for (service in manager.getRunningServices(Int.MAX_VALUE)) {
                if (service.service.className == HavenService::class.java.name) {
                    return true
                }
            }
            return false
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 创建常驻通知
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)

        // 返回 START_STICKY：服务被杀后系统会尝试重启
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        // 服务被杀时尝试重启（保活）
        val restartIntent = Intent(this, HavenService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(restartIntent)
        } else {
            startService(restartIntent)
        }
    }

    /**
     * 创建前台服务的通知渠道
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "归栖保活服务",
                NotificationManager.IMPORTANCE_LOW  // 低重要度，不会发出声音
            ).apply {
                description = "保持归栖在后台运行"
                setShowBadge(false)  // 不显示角标
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * 构建常驻通知
     *
     * 通知内容：
     *   标题：归栖
     *   内容：运行中 · 守护你的 AI
     *   点击：回到桌面
     */
    private fun buildNotification(): Notification {
        // 点击通知回到桌面
        val intent = Intent(this, DesktopActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("归栖")
            .setContentText("运行中 · 守护你的 AI")
            .setContentIntent(pendingIntent)
            .setOngoing(true)         // 不能滑掉
            .setSilent(true)          // 静默
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}