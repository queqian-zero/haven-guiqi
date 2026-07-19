package com.haven.guiqi

import android.app.Activity
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 天气卡片发送管理器 —— 从 ChatConversationActivity 拆出（军规二：拆文件）
 * "立即发送"与"分条队列里的天气渲染"共用同一份逻辑（军规一：单一事实来源）。
 * 此前 insertWeatherCard 和 sendAllPending 的天气分支各写了一遍，现在合并到这里。
 */
class WeatherCardManager(
    private val activity: Activity,
    private val bubbleRenderer: BubbleRenderer,
    private val chatStorage: ChatStorage,
    private val friendId: String,
    private val chatHistory: MutableList<ChatMessage>,
    private val batchModeManager: BatchModeManager,
    private val checkDateSeparator: (Long) -> Unit,
    private val requestReply: () -> Unit
) {
    /** 加号菜单点"天气"：开着分条模式就进队列，否则立即发送并请求回复 */
    fun insert() {
        val ws = WeatherStorage(activity)
        if (ws.getCachedWeather() == null || ws.getCity().isEmpty()) {
            Toast.makeText(activity, "还没有天气数据，先去窗外看看？", Toast.LENGTH_SHORT).show()
            return
        }
        if (batchModeManager.isBatchMode) {
            batchModeManager.addWeather()
            return
        }
        val now = System.currentTimeMillis()
        checkDateSeparator(now)
        if (!renderAndStore(now)) return
        chatHistory.add(ChatMessage("user", ws.buildWeatherSummary()))
        requestReply()
    }

    /**
     * 渲染天气气泡 + 落盘存档（不触发回复、不写 chatHistory）
     * 供 sendAllPending 的天气分支复用
     * @return 是否有可用的天气数据
     */
    fun renderAndStore(now: Long): Boolean {
        val ws = WeatherStorage(activity)
        val data = ws.getCachedWeather()
        val city = ws.getCity()
        if (data == null || city.isEmpty()) return false
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(now))
        bubbleRenderer.addWeatherCard(data, city, isUser = true, timeStr = timeStr)
        val extras = WeatherStorage.toExtras(data, city)
        chatStorage.appendMessage(
            friendId,
            StoredMessage("user", ws.buildWeatherSummary(), now, type = "weather", extras = extras)
        )
        return true
    }
}