package com.haven.guiqi

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * ReminderScheduler - 闹钟注册和触发
 *
 * 两个角色：
 * 1. schedule() / cancel() — 注册和取消 AlarmManager 闹钟
 * 2. ReminderReceiver — 闹钟响了之后的入口，唤醒 HavenService 去调 API
 */
object ReminderScheduler {

    private const val TAG = "ReminderScheduler"
    private const val ACTION_REMINDER = "com.haven.guiqi.ACTION_REMINDER"
    private const val EXTRA_REMINDER_ID = "reminder_id"
    private const val EXTRA_FRIEND_ID = "friend_id"

    /**
     * 注册一个闹钟
     */
    fun schedule(context: Context, reminderId: String, friendId: String, triggerAt: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_REMINDER
            putExtra(EXTRA_REMINDER_ID, reminderId)
            putExtra(EXTRA_FRIEND_ID, friendId)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            reminderId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+ 需要检查精确闹钟权限
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent
                    )
                } else {
                    // 没有精确闹钟权限，用不精确的
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent
                    )
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent
                )
            }
            Log.d(TAG, "Scheduled reminder $reminderId for friend $friendId at $triggerAt")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule reminder: ${e.message}")
        }
    }

    /**
     * 取消一个闹钟
     */
    fun cancel(context: Context, reminderId: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_REMINDER
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            reminderId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
        Log.d(TAG, "Cancelled reminder $reminderId")
    }

    /**
     * 重新注册所有未触发的闹钟
     * （手机重启后闹钟会丢，需要重新注册）
     */
    fun rescheduleAll(context: Context) {
        val storage = ReminderStorage(context)
        val pending = storage.getAllPending()
        val now = System.currentTimeMillis()
        for (reminder in pending) {
            if (reminder.triggerAt > now) {
                schedule(context, reminder.id, reminder.friendId, reminder.triggerAt)
            } else {
                // 过期的闹钟立即触发
                HavenService.handleReminder(context, reminder.id, reminder.friendId)
            }
        }
        Log.d(TAG, "Rescheduled ${pending.size} reminders")
    }
}

/**
 * 闹钟响了的接收器
 * 收到广播后通知 HavenService 去处理
 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reminderId = intent.getStringExtra("reminder_id") ?: return
        val friendId = intent.getStringExtra("friend_id") ?: return
        Log.d("ReminderReceiver", "Reminder triggered: $reminderId for $friendId")
        HavenService.handleReminder(context, reminderId, friendId)
    }
}

/**
 * 开机后重新注册闹钟
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("BootReceiver", "Device rebooted, rescheduling reminders")
            ReminderScheduler.rescheduleAll(context)
            // 同时重启保活服务
            HavenService.start(context)
        }
    }
}