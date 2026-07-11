package com.haven.guiqi

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * WeatherStorage — 天气数据缓存 + 城市设置
 *
 * 数据来源：wttr.in（免费，无需API key）
 * 缓存策略：每次刷新覆盖，30分钟内不重复请求
 */
class WeatherStorage(private val context: Context) {

    private val prefs: SharedPreferences
        get() = context.getSharedPreferences("haven_weather", Context.MODE_PRIVATE)

    fun getCity(): String = prefs.getString("city", "") ?: ""
    fun setCity(city: String) = prefs.edit().putString("city", city).apply()

    fun getCachedWeather(): WeatherData? {
        val json = prefs.getString("weather_json", "") ?: ""
        if (json.isEmpty()) return null
        return try { parseWeatherData(JSONObject(json)) } catch (_: Exception) { null }
    }

    fun saveWeatherJson(json: String) {
        prefs.edit()
            .putString("weather_json", json)
            .putLong("weather_time", System.currentTimeMillis())
            .apply()
    }

    fun shouldRefresh(): Boolean {
        val last = prefs.getLong("weather_time", 0)
        return System.currentTimeMillis() - last > 30 * 60 * 1000
    }

    fun fetchWeather(city: String): WeatherData? {
        return try {
            val encoded = java.net.URLEncoder.encode(city, "UTF-8")
            val url = java.net.URL("https://wttr.in/$encoded?format=j1&lang=zh")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("User-Agent", "Haven/1.0")
            val json = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            saveWeatherJson(json)
            parseWeatherData(JSONObject(json))
        } catch (e: Exception) {
            android.util.Log.e("WeatherStorage", "fetch failed", e)
            null
        }
    }

    private fun parseWeatherData(root: JSONObject): WeatherData {
        val current = root.getJSONArray("current_condition").getJSONObject(0)
        val hourly = root.getJSONArray("weather").getJSONObject(0)
            .getJSONArray("hourly").let { arr ->
                (0 until arr.length()).map { i ->
                    val h = arr.getJSONObject(i)
                    HourlyWeather(
                        time = formatHourlyTime(h.getString("time")),
                        tempC = h.getString("tempC").toIntOrNull() ?: 0,
                        code = h.getString("weatherCode").toIntOrNull() ?: 0,
                        desc = getDesc(h)
                    )
                }
            }
        val daily = root.getJSONArray("weather").let { arr ->
            (0 until minOf(arr.length(), 7)).map { i ->
                val d = arr.getJSONObject(i)
                DailyWeather(
                    date = d.getString("date"),
                    maxC = d.getString("maxtempC").toIntOrNull() ?: 0,
                    minC = d.getString("mintempC").toIntOrNull() ?: 0,
                    code = d.getJSONArray("hourly").getJSONObject(4)
                        .getString("weatherCode").toIntOrNull() ?: 0,
                    desc = getDesc(d.getJSONArray("hourly").getJSONObject(4))
                )
            }
        }
        return WeatherData(
            tempC = current.getString("temp_C").toIntOrNull() ?: 0,
            feelsLikeC = current.getString("FeelsLikeC").toIntOrNull() ?: 0,
            humidity = current.getString("humidity").toIntOrNull() ?: 0,
            windDir = getWindDir(current),
            windSpeed = current.getString("windspeedKmph").toIntOrNull() ?: 0,
            uvIndex = current.getString("uvIndex").toIntOrNull() ?: 0,
            code = current.getString("weatherCode").toIntOrNull() ?: 0,
            desc = getDesc(current),
            hourly = hourly, daily = daily
        )
    }

    private fun getDesc(obj: JSONObject): String {
        return try {
            val arr = obj.getJSONArray("lang_zh")
            if (arr.length() > 0) arr.getJSONObject(0).getString("value") else "未知"
        } catch (_: Exception) {
            try {
                val arr = obj.getJSONArray("weatherDesc")
                if (arr.length() > 0) arr.getJSONObject(0).getString("value") else "未知"
            } catch (_: Exception) { "未知" }
        }
    }

    private fun getWindDir(obj: JSONObject): String {
        val dirs = mapOf(
            "N" to "北", "NE" to "东北", "E" to "东", "SE" to "东南",
            "S" to "南", "SW" to "西南", "W" to "西", "NW" to "西北",
            "NNE" to "北东北", "ENE" to "东东北", "ESE" to "东东南", "SSE" to "南东南",
            "SSW" to "南西南", "WSW" to "西西南", "WNW" to "西西北", "NNW" to "北西北"
        )
        return dirs[obj.optString("winddir16Point", "N")] ?: "北"
    }

    private fun formatHourlyTime(raw: String): String {
        val padded = raw.padStart(4, '0')
        return "${padded.substring(0, 2)}:${padded.substring(2)}"
    }

    fun buildWeatherSummary(): String {
        val data = getCachedWeather() ?: return "[天气数据暂无，请先设置城市]"
        val city = getCity()
        val uv = uvLabel(data.uvIndex)
        val wind = "${data.windDir}风 ${windLevel(data.windSpeed)}"
        val today = data.daily.firstOrNull()
        val tomorrow = data.daily.getOrNull(1)
        val sb = StringBuilder()
        sb.append("[天气·$city] ${data.tempC}°C ${data.desc}")
        sb.append("，体感${data.feelsLikeC}°C，湿度${data.humidity}%，$wind，紫外线$uv")
        if (today != null) sb.append("。今天${today.minC}~${today.maxC}°C ${today.desc}")
        if (tomorrow != null) sb.append("，明天${tomorrow.minC}~${tomorrow.maxC}°C ${tomorrow.desc}")
        return sb.toString()
    }

    companion object {
        fun weatherIcon(code: Int): String = when (code) {
            113 -> "sun"
            116, 119, 122 -> "cloud"
            143, 248, 260 -> "mist"
            176, 263, 266, 293, 296, 299, 302, 305, 308, 356, 359 -> "cloud_rain"
            200, 386, 389, 392, 395 -> "bolt"
            179, 182, 185, 227, 230, 311, 314, 317, 320, 323, 326, 329, 332, 335, 338, 350, 362, 365, 368, 371, 374, 377 -> "snowflake"
            else -> "cloud"
        }
        fun uvLabel(index: Int): String = when {
            index <= 2 -> "弱"; index <= 5 -> "中等"; index <= 7 -> "较强"; index <= 10 -> "强"; else -> "极强"
        }
        fun windLevel(kmph: Int): String = when {
            kmph < 2 -> "0级"; kmph < 6 -> "1级"; kmph < 12 -> "2级"; kmph < 20 -> "3级"
            kmph < 29 -> "4级"; kmph < 39 -> "5级"; kmph < 50 -> "6级"; else -> "7级以上"
        }
    }
}

data class WeatherData(
    val tempC: Int, val feelsLikeC: Int, val humidity: Int,
    val windDir: String, val windSpeed: Int, val uvIndex: Int,
    val code: Int, val desc: String,
    val hourly: List<HourlyWeather>, val daily: List<DailyWeather>
)
data class HourlyWeather(val time: String, val tempC: Int, val code: Int, val desc: String)
data class DailyWeather(val date: String, val maxC: Int, val minC: Int, val code: Int, val desc: String)