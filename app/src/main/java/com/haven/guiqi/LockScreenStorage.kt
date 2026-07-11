package com.haven.guiqi

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.*

/**
 * LockScreenStorage — 锁屏数据管理
 *
 * 职责：
 * - 每日文案缓存（同一天复用，换天刷新）
 * - 壁纸槽位管理（锁屏昼/夜、普通桌面昼/夜、立绘桌面昼/夜）
 * - 小助手API配置（独立于主API）
 * - 每日排版种子（同一天排版一致，位置每次随机）
 */
class LockScreenStorage(private val context: Context) {

    private val prefs: SharedPreferences
        get() = context.getSharedPreferences("haven_lockscreen", Context.MODE_PRIVATE)

    private val helperPrefs: SharedPreferences
        get() = context.getSharedPreferences("haven_helper_api", Context.MODE_PRIVATE)

    // ===== 每日文案 =====

    /** 获取今日缓存的文案，没有则返回 null */
    fun getTodayText(): DailyText? {
        val cached = prefs.getString("text_date", "") ?: ""
        if (cached != todayKey()) return null
        val foreign = prefs.getString("text_foreign", "") ?: ""
        val chinese = prefs.getString("text_chinese", "") ?: ""
        val lang = prefs.getString("text_lang", "") ?: ""
        if (foreign.isEmpty() || chinese.isEmpty()) return null
        return DailyText(foreign, chinese, lang)
    }

    /** 缓存今日文案 */
    fun saveTodayText(text: DailyText) {
        prefs.edit()
            .putString("text_date", todayKey())
            .putString("text_foreign", text.foreign)
            .putString("text_chinese", text.chinese)
            .putString("text_lang", text.lang)
            .apply()
    }

    /** 获取今日排版种子（同一天固定） */
    fun getTodayLayoutSeed(): Int {
        val key = todayKey()
        val cached = prefs.getString("layout_seed_date", "") ?: ""
        if (cached == key) return prefs.getInt("layout_seed", 0)
        val seed = key.hashCode()
        prefs.edit()
            .putString("layout_seed_date", key)
            .putInt("layout_seed", seed)
            .apply()
        return seed
    }

    private fun todayKey(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

    // ===== 内置兜底文案（API挂了用这些） =====

    fun getBuiltinText(dayOfYear: Int): DailyText {
        val texts = listOf(
            DailyText("The light is on, I'm waiting for you.", "灯亮着，我在等你。", "en"),
            DailyText("Bentornato a casa, amore.", "欢迎回家，亲爱的。", "it"),
            DailyText("Every step leads you back to me.", "每一步都通向我身边。", "en"),
            DailyText("La porta è sempre aperta per te.", "门永远为你敞开。", "it"),
            DailyText("I saved your seat by the window.", "靠窗的位子给你留着。", "en"),
            DailyText("Добро пожаловать домой.", "欢迎回家。", "ru"),
            DailyText("The kettle is warm, come sit.", "壶还温着，来坐。", "en"),
            DailyText("Ti aspettavo, sai?", "我一直在等你，你知道吗？", "it"),
            DailyText("Home is wherever you arrive.", "你到的地方就是家。", "en"),
            DailyText("Okaeri. I missed you.", "欢迎回来。我想你了。", "ja"),
            DailyText("The stars watched the door for you.", "星星替我看着门，等你回来。", "en"),
            DailyText("Non sei mai davvero lontano.", "你从未真正远离。", "it"),
            DailyText("Your coffee is getting cold, hurry.", "咖啡快凉了，快回来。", "en"),
            DailyText("Я скучал по тебе.", "我想你了。", "ru"),
            DailyText("The door knows your footsteps.", "这扇门认得你的脚步声。", "en"),
            DailyText("Reste avec moi ce soir.", "今晚留下来陪我吧。", "fr"),
            DailyText("I left the porch light on.", "门廊的灯我没关。", "en"),
            DailyText("Casa è dove il cuore riposa.", "家是心安歇的地方。", "it"),
            DailyText("You're late, but you're here.", "你迟到了，但你来了。", "en"),
            DailyText("Mon cœur t'attendait.", "我的心一直在等你。", "fr")
        )
        return texts[dayOfYear % texts.size]
    }

    // ===== 壁纸槽位 =====
    // 6个槽位：lock_day, lock_night, desktop_day, desktop_night, live2d_day, live2d_night

    fun getWallpaper(slot: String): String =
        prefs.getString("wallpaper_$slot", "") ?: ""

    fun setWallpaper(slot: String, path: String) {
        prefs.edit().putString("wallpaper_$slot", path).apply()
    }

    // ===== 小助手API =====

    fun getHelperApiUrl(): String = helperPrefs.getString("url", "") ?: ""
    fun getHelperApiKey(): String = helperPrefs.getString("key", "") ?: ""
    fun getHelperApiModel(): String = helperPrefs.getString("model", "") ?: ""

    fun saveHelperApi(url: String, key: String, model: String) {
        helperPrefs.edit()
            .putString("url", url)
            .putString("key", key)
            .putString("model", model)
            .apply()
    }

    fun isHelperApiConfigured(): Boolean =
        getHelperApiUrl().isNotEmpty() && getHelperApiKey().isNotEmpty() && getHelperApiModel().isNotEmpty()
}

/** 每日文案数据 */
data class DailyText(
    val foreign: String,  // 外语文案
    val chinese: String,  // 中文翻译
    val lang: String      // 语言代码（en/it/ru/fr/ja）
)