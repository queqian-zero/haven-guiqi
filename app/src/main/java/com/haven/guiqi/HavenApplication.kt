package com.haven.guiqi

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 统一处理归栖所有普通页面的系统状态栏与底部导航栏。
 * 避免页面顶部或底部出现与当前主题不协调的纯黑色横条。
 */
class HavenApplication : Application(), Application.ActivityLifecycleCallbacks {

    override fun onCreate() {
        super.onCreate()
        ThemeHelper.init(this)
        FullBackupManager.handleApplicationStart(this)
        registerActivityLifecycleCallbacks(this)
    }

    private fun syncSystemBars(activity: Activity) {
        // 锁屏、小窝和桌面使用各自的沉浸式/壁纸系统栏，不能被全局颜色覆盖。
        if (activity is MainActivity || activity is NestActivity || activity is DesktopActivity) return

        val colors = ThemeHelper.getColors(activity)
        val window = activity.window

        window.statusBarColor = colors.background
        window.navigationBarColor = colors.background

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }

        val useDarkIcons = !ThemeHelper.isDark(activity)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = useDarkIcons
            isAppearanceLightNavigationBars = useDarkIcons
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        syncSystemBars(activity)
    }

    override fun onActivityResumed(activity: Activity) {
        // 某些页面会在 onCreate 中重新设置系统栏；恢复到前台时再统一一次。
        syncSystemBars(activity)
    }

    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
