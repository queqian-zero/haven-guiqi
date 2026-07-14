package com.haven.guiqi

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate

/**
 * ThemeHelper - 归栖主题管理
 *
 * 集中管理所有颜色，根据当前模式（深色/浅色）返回对应的颜色值
 * 所有 Activity 都从这里取颜色，不再硬编码
 *
 * 主题模式：
 *   0 = 跟随系统（默认）
 *   1 = 始终深色
 *   2 = 始终浅色
 *
 * 用法：
 *   val colors = ThemeHelper.getColors(context)
 *   view.setBackgroundColor(colors.background)
 *   text.setTextColor(colors.textPrimary)
 *
 * 颜色命名规则：
 *   background = 页面背景
 *   card = 卡片/气泡背景
 *   inputBg = 输入框背景
 *   textPrimary = 主要文字（标题、名字）
 *   textSecondary = 次要文字（描述、提示）
 *   textHint = 占位符文字
 *   accent = 强调色（按钮、链接、选中态）
 *   userBubble = 用户消息气泡
 *   aiBubble = AI 消息气泡
 *   border = 边框、分隔线
 *   statusBar = 状态栏颜色
 */
object ThemeHelper {

    // ===== 主题模式管理 =====

    private const val PREFS_NAME = "haven_theme"
    private const val KEY_MODE = "theme_mode"

    /**
     * 获取当前主题模式
     * 0 = 跟随系统, 1 = 始终深色, 2 = 始终浅色
     */
    fun getMode(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_MODE, 0)
    }

    /**
     * 设置主题模式并立即应用
     */
    fun setMode(context: Context, mode: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_MODE, mode).apply()
        applyMode(mode, context)
    }

    /**
     * 应用主题模式到全局
     * 在 Application 或第一个 Activity 的 onCreate 里调用
     */
    fun applyMode(mode: Int, context: Context? = null) {
        when (mode) {
            0 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            1 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            2 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            3 -> if (context != null) {
                val dark = isDarkBySunCycle(context)
                AppCompatDelegate.setDefaultNightMode(
                    if (dark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
                )
            }
        }
    }

    /**
     * 初始化：读取保存的模式并应用
     * 在 App 启动时调用一次
     */
    fun init(context: Context) {
        applyMode(getMode(context), context)
    }

    /**
     * 当前是否是深色模式
     */
    fun isDark(context: Context): Boolean {
        val mode = getMode(context)
        return when (mode) {
            1 -> true
            2 -> false
            3 -> isDarkBySunCycle(context)
            else -> {
                val nightMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                nightMode == Configuration.UI_MODE_NIGHT_YES
            }
        }
    }

    /** 根据日出日落判断现在是不是夜晚 */
    private fun isDarkBySunCycle(context: Context): Boolean {
        val astro = WeatherStorage(context).getCachedAstronomy() ?: return isSystemDark(context)
        val now = java.util.Calendar.getInstance().let { it.get(java.util.Calendar.HOUR_OF_DAY) * 60 + it.get(java.util.Calendar.MINUTE) }
        val sunrise = parseTimeToMinutes(astro.sunrise)
        val sunset = parseTimeToMinutes(astro.sunset)
        if (sunrise < 0 || sunset < 0) return isSystemDark(context)
        return now < sunrise || now >= sunset
    }

    private fun isSystemDark(context: Context): Boolean {
        val nightMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return nightMode == Configuration.UI_MODE_NIGHT_YES
    }

    /** "05:24 AM" → 324分钟 */
    private fun parseTimeToMinutes(str: String): Int {
        return try {
            val clean = str.trim().uppercase()
            val parts = clean.replace(Regex("[^0-9:]"), "").split(":")
            var h = parts[0].toInt(); val m = parts.getOrNull(1)?.toInt() ?: 0
            if (clean.contains("PM") && h != 12) h += 12
            if (clean.contains("AM") && h == 12) h = 0
            h * 60 + m
        } catch (_: Exception) { -1 }
    }

    /**
     * 获取当前模式的颜色方案
     */
    fun getColors(context: Context): HavenColors {
        return if (isDark(context)) darkColors else lightColors
    }

    // ===== 深色模式配色（现有的配色） =====
    private val darkColors = HavenColors(
        // 背景
        background = 0xFF0A1628.toInt(),
        backgroundSecondary = 0xFF0D1B2A.toInt(),
        card = 0xFF152238.toInt(),
        inputBg = 0x0FFFFFFF.toInt(),

        // 文字
        textPrimary = 0xD9FFFFFF.toInt(),
        textSecondary = 0x80FFFFFF.toInt(),
        textHint = 0x26FFFFFF.toInt(),
        textOnAccent = 0xD9FFFFFF.toInt(),

        // 强调色（紫色）
        accent = 0x4DB3A0FF.toInt(),
        accentStrong = 0x80B3A0FF.toInt(),
        accentBg = 0x0FB3A0FF.toInt(),

        // 气泡
        userBubbleBg = 0x1AB3A0FF.toInt(),
        userBubbleText = 0xB3FFFFFF.toInt(),
        aiBubbleBg = 0x0DFFFFFF.toInt(),
        aiBubbleText = 0xB3FFFFFF.toInt(),

        // 边框和分隔
        border = 0x08FFFFFF.toInt(),
        borderMedium = 0x1AFFFFFF.toInt(),
        divider = 0x08FFFFFF.toInt(),

        // 状态栏和导航栏
        statusBar = 0x00000000.toInt(),  // 透明

        // 系统提示
        tipText = 0x4DFFFFFF.toInt(),
        timeText = 0x1AFFFFFF.toInt(),
        dateLabel = 0x26FFFFFF.toInt(),

        // 档案馆特色
        cabinetBg = 0xFF1A2A44.toInt(),
        cabinetTop = 0xFF1E3050.toInt(),
        cabinetBorder = 0xFF243552.toInt(),
        drawerHandle = 0xFF2A3D5C.toInt(),
        paperBg = 0xFF1E1E16.toInt(),
        paperBorder = 0x26B4A078.toInt(),
        paperText = 0xB3E6DCC0.toInt(),
        paperMeta = 0x66B4A078.toInt(),
        stampColor = 0x33B3A0FF.toInt(),

        // 弹窗
        dialogBg = 0xFF152238.toInt(),
        dialogBorder = 0x1AFFFFFF.toInt(),

        // 错误/警告
        errorBg = 0x33FF6B6B.toInt(),
        errorText = 0xCCFF6B6B.toInt(),

        // 搜索高亮
        highlightColor = 0xFFB3A0FF.toInt(),

        // 文件夹标签颜色
        folderMemory = 0xFF78B48C.toInt(),
        folderDiary = 0xFFB48C64.toInt(),
        folderDream = 0xFF8C78B4.toInt(),
        folderSummary = 0xFF6496B4.toInt(),
        folderTrash = 0xFFA07878.toInt(),
        warning = 0x59FFB066.toInt()
    )

    // ===== 浅色模式配色（Claude 同款） =====
    private val lightColors = HavenColors(
        // 背景
        background = 0xFFFDFCFA.toInt(),
        backgroundSecondary = 0xFFF8F7F4.toInt(),
        card = 0xFFFFFFFF.toInt(),
        inputBg = 0x08000000.toInt(),

        // 文字
        textPrimary = 0xFF2D2B28.toInt(),
        textSecondary = 0xFF6B6560.toInt(),
        textHint = 0x4D6B6560.toInt(),
        textOnAccent = 0xFF3D3730.toInt(),

        // 强调色（暖棕色，Claude 标志色）
        accent = 0xFFB3845A.toInt(),
        accentStrong = 0xFF8B6440.toInt(),
        accentBg = 0x1AB3845A.toInt(),

        // 气泡
        userBubbleBg = 0xFFF2ECE3.toInt(),
        userBubbleText = 0xFF3D3730.toInt(),
        aiBubbleBg = 0xFFF7F6F3.toInt(),
        aiBubbleText = 0xFF3D3730.toInt(),

        // 边框和分隔
        border = 0x1AD4C8B8.toInt(),
        borderMedium = 0x22D4C8B8.toInt(),
        divider = 0x12D4C8B8.toInt(),

        // 状态栏和导航栏
        statusBar = 0x00000000.toInt(),

        // 系统提示
        tipText = 0x806B6560.toInt(),
        timeText = 0x336B6560.toInt(),
        dateLabel = 0x4D6B6560.toInt(),

        // 档案馆特色（浅色版）
        cabinetBg = 0xFFF0EDE8.toInt(),
        cabinetTop = 0xFFE8E3DC.toInt(),
        cabinetBorder = 0xFFD9D3CA.toInt(),
        drawerHandle = 0xFFCCC5BA.toInt(),
        paperBg = 0xFFFFFDF8.toInt(),
        paperBorder = 0x33B4A078.toInt(),
        paperText = 0xFF4A4238.toInt(),
        paperMeta = 0x80967A5A.toInt(),
        stampColor = 0x33B3845A.toInt(),

        // 弹窗
        dialogBg = 0xFFFFFFFF.toInt(),
        dialogBorder = 0x26B4A082.toInt(),

        // 错误/警告
        errorBg = 0x26E24B4A.toInt(),
        errorText = 0xFFE24B4A.toInt(),

        // 搜索高亮
        highlightColor = 0xFFB3845A.toInt(),

        // 文件夹标签颜色
        folderMemory = 0xFF5A9A6E.toInt(),
        folderDiary = 0xFF9A7A50.toInt(),
        folderDream = 0xFF7A68A0.toInt(),
        folderSummary = 0xFF5080A0.toInt(),
        folderTrash = 0xFF8A6060.toInt(),
        warning = 0xFF9A7A50.toInt()
    )
}

/**
 * 一套完整的颜色方案
 */
data class HavenColors(
    val background: Int,
    val backgroundSecondary: Int,
    val card: Int,
    val inputBg: Int,

    val textPrimary: Int,
    val textSecondary: Int,
    val textHint: Int,
    val textOnAccent: Int,

    val accent: Int,
    val accentStrong: Int,
    val accentBg: Int,

    val userBubbleBg: Int,
    val userBubbleText: Int,
    val aiBubbleBg: Int,
    val aiBubbleText: Int,

    val border: Int,
    val borderMedium: Int,
    val divider: Int,

    val statusBar: Int,

    val tipText: Int,
    val timeText: Int,
    val dateLabel: Int,

    val cabinetBg: Int,
    val cabinetTop: Int,
    val cabinetBorder: Int,
    val drawerHandle: Int,
    val paperBg: Int,
    val paperBorder: Int,
    val paperText: Int,
    val paperMeta: Int,
    val stampColor: Int,

    val dialogBg: Int,
    val dialogBorder: Int,

    val errorBg: Int,
    val errorText: Int,

    val highlightColor: Int,

    val folderMemory: Int,
    val folderDiary: Int,
    val folderDream: Int,
    val folderSummary: Int,
    val folderTrash: Int,
    val warning: Int
)