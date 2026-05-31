package com.haven.guiqi

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * HavenAccessibilityService - 保活第三层
 *
 * 作用：
 * 1. 无障碍服务本身不容易被系统杀死
 * 2. 定期检查 HavenService 是否还活着，死了就拉起来
 * 3. 重新注册所有闹钟（防止闹钟丢失）
 *
 * 不会读取任何屏幕内容，canRetrieveWindowContent = false
 * 只监听 windowStateChanged 事件用来做心跳检查
 */
class HavenAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "HavenA11yService"
        private const val CHECK_INTERVAL = 60_000L // 每 60 秒检查一次

        /**
         * 检查无障碍服务是否已开启
         */
        fun isEnabled(context: Context): Boolean {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val serviceName = "${context.packageName}/${HavenAccessibilityService::class.java.canonicalName}"
            return enabledServices.contains(serviceName)
        }

        /**
         * 跳转到无障碍设置页面
         */
        fun openSettings(context: Context) {
            try {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open accessibility settings: ${e.message}")
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val checkRunnable = object : Runnable {
        override fun run() {
            checkAndRevive()
            handler.postDelayed(this, CHECK_INTERVAL)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility service connected")

        // 配置服务（最小权限）
        serviceInfo = serviceInfo?.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 5000
            flags = 0 // 不需要任何额外权限
        } ?: serviceInfo

        // 立即检查一次
        checkAndRevive()

        // 启动定期检查
        handler.postDelayed(checkRunnable, CHECK_INTERVAL)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 借着系统事件顺便检查一下
        // 不读取任何事件内容，只是用这个时机做存活检查
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        Log.d(TAG, "Accessibility service destroyed")
    }

    /**
     * 检查 HavenService 是否存活，死了就拉起来
     */
    private fun checkAndRevive() {
        try {
            if (!HavenService.isRunning(this)) {
                Log.d(TAG, "HavenService not running, restarting...")
                HavenService.start(this)
            }

            // 顺便确保闹钟都在
            ReminderScheduler.rescheduleAll(this)

        } catch (e: Exception) {
            Log.e(TAG, "Error during check: ${e.message}")
        }
    }
}