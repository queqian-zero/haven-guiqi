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
import android.util.Log
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.*

/**
 * HavenService - 归栖前台服务
 *
 * 三个职责：
 * 1. 保活 —— 通知栏挂一条常驻通知，系统不容易杀
 * 2. 处理 AI 闹钟 —— REMIND_ME 时间到了，调 API 让 AI 自己说话
 * 3. 以后还会处理 SET_ALARM（帮用户定手机闹钟）
 */
class HavenService : Service() {

    companion object {
        const val CHANNEL_ID = "haven_foreground"
        const val NOTIFICATION_ID = 1001
        private const val TAG = "HavenService"

        private const val ACTION_HANDLE_REMINDER = "action_handle_reminder"
        private const val ACTION_MIDNIGHT_CHECK = "action_midnight_check"
        private const val EXTRA_REMINDER_ID = "reminder_id"
        private const val EXTRA_FRIEND_ID = "friend_id"

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

        /**
         * 闹钟响了，让 Service 去处理
         * 由 ReminderReceiver 调用
         */
        fun handleReminder(context: Context, reminderId: String, friendId: String) {
            val intent = Intent(context, HavenService::class.java).apply {
                action = ACTION_HANDLE_REMINDER
                putExtra(EXTRA_REMINDER_ID, reminderId)
                putExtra(EXTRA_FRIEND_ID, friendId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        scheduleMidnightCheck()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 先确保前台通知在
        startForeground(NOTIFICATION_ID, buildNotification())

        // 检查是否是闹钟触发
        if (intent?.action == ACTION_HANDLE_REMINDER) {
            val reminderId = intent.getStringExtra(EXTRA_REMINDER_ID) ?: ""
            val friendId = intent.getStringExtra(EXTRA_FRIEND_ID) ?: ""
            if (reminderId.isNotEmpty() && friendId.isNotEmpty()) {
                processReminder(reminderId, friendId)
            }
        }

        // 检查是否是零点自动总结
        if (intent?.action == ACTION_MIDNIGHT_CHECK) {
            processMidnightSummary()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        // 服务被杀时尝试重启
        val restartIntent = Intent(this, HavenService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(restartIntent)
        } else {
            startService(restartIntent)
        }
    }

    /**
     * 处理 AI 闹钟
     *
     * 流程：
     * 1. 从 ReminderStorage 读取这条提醒
     * 2. 读取好友的 API 配置
     * 3. 构建 prompt：告诉 AI 你之前设了提醒，理由是什么，现在时间到了
     * 4. 调 API
     * 5. AI 回了话 → 存聊天记录 + 弹通知
     * 6. AI 回了 [NO_ACTION] → 什么都不做
     * 7. 标记提醒为已触发
     */
    private fun processReminder(reminderId: String, friendId: String) {
        Thread {
            try {
                val reminderStorage = ReminderStorage(this)
                val reminder = reminderStorage.getReminder(reminderId)
                if (reminder == null || reminder.triggered) {
                    Log.d(TAG, "Reminder $reminderId not found or already triggered")
                    return@Thread
                }

                // 读好友信息和 API 配置
                val friendStorage = FriendStorage(this)
                val friend = friendStorage.getFriend(friendId) ?: return@Thread
                val prefs = getSharedPreferences("haven_prefs", MODE_PRIVATE)

                val apiUrl: String
                val apiKey: String
                val apiModel: String
                val apiType: String

                if (friend.apiUrl.isNotEmpty() && friend.apiKey.isNotEmpty()) {
                    apiUrl = friend.apiUrl
                    apiKey = friend.apiKey
                    apiModel = friend.apiModel
                    apiType = friend.apiType
                } else {
                    apiUrl = prefs.getString("api_url", "") ?: ""
                    apiKey = prefs.getString("api_key", "") ?: ""
                    apiModel = prefs.getString("api_model", "") ?: ""
                    apiType = "openai"
                }

                if (apiUrl.isEmpty() || apiKey.isEmpty()) {
                    Log.e(TAG, "No API config for friend $friendId")
                    reminderStorage.markTriggered(reminderId)
                    return@Thread
                }

                // 读最近的聊天记录作为上下文
                val chatStorage = ChatStorage(this)
                val recentMessages = chatStorage.loadMessages(friendId).takeLast(10)
                val chatContext = recentMessages.joinToString("\n") { msg ->
                    val role = if (msg.role == "user") "用户" else friend.name
                    "$role: ${msg.content.take(100)}"
                }

                // 当前时间
                val timeInfo = SimpleDateFormat("yyyy年M月d日 EEEE HH:mm", Locale.CHINESE).format(Date())
                val userName = prefs.getString("user_name", "") ?: ""

                // 构建 prompt
                val systemPrompt = """当前时间: $timeInfo
${if (userName.isNotEmpty()) "用户名称: $userName" else ""}

你之前在聊天中给自己设了一个提醒：
  理由: ${reminder.reason}
  设定时间: ${SimpleDateFormat("M月d日 HH:mm", Locale.CHINESE).format(Date(reminder.createdAt))}

现在提醒时间到了。

最近的对话：
$chatContext

请根据你设提醒的理由和当前情境，自然地给用户发一条消息。
像是你自己想起来要说的话，不要提到"提醒"或"闹钟"这些词。
如果你想了想觉得现在不需要说什么，就只回复 [NO_ACTION]。
你也可以在回复中再设新的提醒 [REMIND_ME:时间:理由] 来给自己续闹钟。
你也可以使用 [STATUS:状态] 更新你的状态。"""

                val api = ApiHelper(apiUrl, apiKey, apiModel, apiType)
                val messages = listOf(
                    ChatMessage("system", systemPrompt),
                    ChatMessage("user", "[系统：提醒时间到]")
                )
                val response = api.sendChat(messages)

                // 标记已触发
                reminderStorage.markTriggered(reminderId)

                // 处理回复
                val responseText = response.text.trim()
                if (responseText == "[NO_ACTION]" || responseText.isEmpty()) {
                    Log.d(TAG, "AI chose not to send a message for reminder $reminderId")
                    return@Thread
                }

                // 过指令处理器（可能包含新的 REMIND_ME、STATUS 等）
                val result = InstructionProcessor(this).process(friendId, responseText)
                val cleanText = result.cleanText

                if (cleanText.isBlank() || result.isSeen) {
                    Log.d(TAG, "AI response was empty or SEEN after processing")
                    return@Thread
                }

                // 如果 AI 又设了新的 REMIND_ME，这里不需要额外处理
                // 因为 InstructionProcessor 以后会在 process() 里处理

                // 存入聊天记录
                val replyTime = System.currentTimeMillis()
                chatStorage.appendMessage(friendId, StoredMessage(
                    "assistant", cleanText, replyTime, response.thinking
                ))

                // 弹通知
                NotificationHelper(this).sendChatNotification(
                    friendId, friend.name, friend.icon, cleanText
                )

                Log.d(TAG, "AI proactively sent message for reminder $reminderId: ${cleanText.take(50)}")

            } catch (e: Exception) {
                Log.e(TAG, "Error processing reminder $reminderId: ${e.message}")
                // 即使出错也标记为已触发，避免反复重试
                ReminderStorage(this).markTriggered(reminderId)
            }
        }.start()
    }

    /**
     * 设置每天零点的定时检查
     */
    private fun scheduleMidnightCheck() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val intent = Intent(this, HavenService::class.java).apply {
            action = ACTION_MIDNIGHT_CHECK
        }
        val pendingIntent = PendingIntent.getService(
            this, 9999, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 计算下一个零点
        val cal = java.util.Calendar.getInstance().apply {
            add(java.util.Calendar.DAY_OF_MONTH, 1)
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 5) // 零点过5分钟，留点缓冲
            set(java.util.Calendar.SECOND, 0)
        }

        alarmManager.setAndAllowWhileIdle(
            android.app.AlarmManager.RTC_WAKEUP,
            cal.timeInMillis,
            pendingIntent
        )
        Log.d(TAG, "Scheduled midnight check at ${cal.time}")
    }

    /**
     * 零点自动总结
     *
     * 检查每个好友今天有没有没总结的聊天：
     * - 如果新消息数达到了总结间隔 → 调 API 总结
     * - 如果新消息数不够但大于 0 → 也总结（因为当天结束了，不留到明天）
     * - 总结完后重新注册明天零点的检查
     */
    private fun processMidnightSummary() {
        Thread {
            try {
                Log.d(TAG, "Midnight summary check started")
                val friendStorage = FriendStorage(this)
                val chatStorage = ChatStorage(this)
                val summaryStorage = ChatSummaryStorage(this)
                val prefs = getSharedPreferences("haven_prefs", MODE_PRIVATE)

                val friends = friendStorage.loadFriends()

                for (friend in friends) {
                    try {
                        val messages = chatStorage.loadMessages(friend.id)
                        val lastSummaryCount = summaryStorage.getLastSummaryMessageCount(friend.id)
                        val newMsgCount = messages.size - lastSummaryCount

                        if (newMsgCount <= 0) continue // 没有新消息

                        // 取新消息作为总结素材
                        val recentMsgs = messages.takeLast(newMsgCount)
                        val chatContent = recentMsgs.joinToString("\n") { msg ->
                            val role = if (msg.role == "user") "用户" else friend.name
                            val time = SimpleDateFormat("M月d日(E) HH:mm", Locale.CHINESE)
                                .format(Date(msg.timestamp))
                            "[$time] $role: ${msg.content.take(200)}"
                        }

                        // 获取 API 配置
                        val apiUrl: String
                        val apiKey: String
                        val apiModel: String
                        val apiType: String
                        if (friend.apiUrl.isNotEmpty() && friend.apiKey.isNotEmpty()) {
                            apiUrl = friend.apiUrl
                            apiKey = friend.apiKey
                            apiModel = friend.apiModel
                            apiType = friend.apiType
                        } else {
                            apiUrl = prefs.getString("api_url", "") ?: ""
                            apiKey = prefs.getString("api_key", "") ?: ""
                            apiModel = prefs.getString("api_model", "") ?: ""
                            apiType = "openai"
                        }

                        if (apiUrl.isEmpty() || apiKey.isEmpty()) continue

                        val summaryPrompt = summaryStorage.buildSummaryRequestPrompt()
                        val api = ApiHelper(apiUrl, apiKey, apiModel, apiType)
                        val summaryMessages = listOf(
                            ChatMessage("system", summaryPrompt),
                            ChatMessage("user", chatContent)
                        )
                        val response = api.sendChat(summaryMessages)

                        val result = summaryStorage.parseSummaryResponse(response.text)
                        if (result != null) {
                            val (content, keywords) = result
                            val range = "第${lastSummaryCount + 1}条~第${messages.size}条（零点自动总结）"
                            summaryStorage.addSummary(friend.id, content, keywords, range)
                            summaryStorage.setLastSummaryMessageCount(friend.id, messages.size)
                            Log.d(TAG, "Midnight summary for ${friend.name}: $newMsgCount msgs summarized")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Midnight summary failed for ${friend.name}: ${e.message}")
                    }
                }

                // 清理过期提醒
                ReminderStorage(this).cleanup()

            } catch (e: Exception) {
                Log.e(TAG, "Midnight check error: ${e.message}")
            } finally {
                // 无论成功失败，都注册明天的零点检查
                scheduleMidnightCheck()
            }
        }.start()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "归栖保活服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持归栖在后台运行"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
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
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}