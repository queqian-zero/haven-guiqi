package com.haven.guiqi

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 统一同步归栖普通页面的系统栏图标明暗与系统对比遮罩。
 *
 * 系统栏“颜色/透明度”必须由各 Activity 自己决定：
 * - 普通纯色页面可以继续使用主题背景色；
 * - 聊天、画匣、桌面等沉浸式页面可以自行设置透明并让背景延伸到栏下。
 *
 * 这里不能在 Activity 恢复前台时重新写入主题背景色，否则会把页面刚设置好的
 * 透明状态栏和透明导航栏覆盖掉，浅色模式就会重新出现上下白条。
 */
class HavenApplication : Application(), Application.ActivityLifecycleCallbacks {

    override fun onCreate() {
        super.onCreate()
        ThemeHelper.init(this)
        FullBackupManager.handleApplicationStart(this)
        registerActivityLifecycleCallbacks(this)
    }

    private fun syncSystemBarAppearance(activity: Activity) {
        // 锁屏、小窝和桌面使用各自的沉浸式/壁纸系统栏和图标策略。
        if (activity is MainActivity || activity is NestActivity || activity is DesktopActivity) return

        val window = activity.window

        // 只关闭系统自动添加的半透明对比遮罩，不修改页面自己选择的系统栏颜色。
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
        syncSystemBarAppearance(activity)
    }

    override fun onActivityResumed(activity: Activity) {
        // 恢复前台时只重新同步图标明暗；绝不覆盖 Activity 自己设置的栏颜色。
        syncSystemBarAppearance(activity)
    }

    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
