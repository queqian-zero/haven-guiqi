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
        // 优先用天气代码查中文映射（最可靠）
        val code = obj.optString("weatherCode", "0").toIntOrNull() ?: 0
        val mapped = descFromCode(code)
        if (mapped != null) return mapped
        // 其次尝试 wttr.in 的中文字段
        try {
            val arr = obj.getJSONArray("lang_zh")
            if (arr.length() > 0) {
                val zh = arr.getJSONObject(0).getString("value")
                if (zh.isNotEmpty() && zh.any { it.code > 0x4E00 }) return zh
            }
        } catch (_: Exception) {}
        // 最后：英文原文
        return try {
            val arr = obj.getJSONArray("weatherDesc")
            if (arr.length() > 0) arr.getJSONObject(0).getString("value") else "未知"
        } catch (_: Exception) { "未知" }
    }

    private fun descFromCode(code: Int): String? = mapOf(
        113 to "晴", 116 to "多云", 119 to "阴", 122 to "阴",
        143 to "雾", 176 to "阵雨", 179 to "小雪", 182 to "冻雨",
        185 to "冻毛毛雨", 200 to "雷阵雨", 227 to "暴风雪", 230 to "大暴风雪",
        248 to "雾", 260 to "冻雾", 263 to "小阵雨", 266 to "毛毛雨",
        281 to "冻毛毛雨", 284 to "大冻雨", 293 to "小雨", 296 to "小雨",
        299 to "中雨", 302 to "中雨", 305 to "大雨", 308 to "暴雨",
        311 to "冻雨", 314 to "大冻雨", 317 to "雨夹雪", 320 to "大雨夹雪",
        323 to "小雪", 326 to "小雪", 329 to "中雪", 332 to "中雪",
        335 to "大雪", 338 to "暴雪", 350 to "冰粒",
        353 to "阵雨", 356 to "大阵雨", 359 to "暴雨",
        362 to "小雨夹雪", 365 to "大雨夹雪", 368 to "小阵雪", 371 to "大阵雪",
        374 to "小冰粒", 377 to "大冰粒",
        386 to "雷阵雨", 389 to "雷暴雨", 392 to "雷阵雪", 395 to "大雷暴雪"
    )[code]

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

    /** 解析天文数据（日出日落月出月落） */
    fun getCachedAstronomy(): AstronomyData? {
        val json = prefs.getString("weather_json", null) ?: return null
        return try {
            val root = JSONObject(json)
            val astro = root.getJSONArray("weather").getJSONObject(0)
                .getJSONArray("astronomy").getJSONObject(0)
            AstronomyData(
                sunrise = astro.getString("sunrise").trim(),
                sunset = astro.getString("sunset").trim(),
                moonrise = astro.getString("moonrise").trim(),
                moonset = astro.getString("moonset").trim(),
                moonPhase = astro.optString("moon_phase", ""),
                moonIllumination = astro.optString("moon_illumination", "0").toIntOrNull() ?: 0
            )
        } catch (_: Exception) { null }
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

        /** 把天气数据+城市序列化成 JSON 字符串（存进 StoredMessage.extras） */
        fun toExtras(data: WeatherData, city: String): String {
            val obj = org.json.JSONObject()
            obj.put("city", city)
            obj.put("tempC", data.tempC); obj.put("feelsLikeC", data.feelsLikeC)
            obj.put("humidity", data.humidity); obj.put("windDir", data.windDir)
            obj.put("windSpeed", data.windSpeed); obj.put("uvIndex", data.uvIndex)
            obj.put("code", data.code); obj.put("desc", data.desc)
            val ha = org.json.JSONArray()
            for (h in data.hourly) ha.put(org.json.JSONObject().apply {
                put("time", h.time); put("tempC", h.tempC); put("code", h.code); put("desc", h.desc)
            })
            obj.put("hourly", ha)
            val da = org.json.JSONArray()
            for (d in data.daily) da.put(org.json.JSONObject().apply {
                put("date", d.date); put("maxC", d.maxC); put("minC", d.minC); put("code", d.code); put("desc", d.desc)
            })
            obj.put("daily", da)
            return obj.toString()
        }

        /** 从 extras JSON 还原天气数据和城市，解析失败返回 null */
        fun fromExtras(json: String): Pair<WeatherData, String>? {
            if (json.isEmpty()) return null
            return try {
                val obj = org.json.JSONObject(json)
                val city = obj.getString("city")
                val ha = obj.getJSONArray("hourly")
                val hourly = (0 until ha.length()).map { i -> ha.getJSONObject(i).let { h ->
                    HourlyWeather(h.getString("time"), h.getInt("tempC"), h.getInt("code"), h.getString("desc"))
                }}
                val da = obj.getJSONArray("daily")
                val daily = (0 until da.length()).map { i -> da.getJSONObject(i).let { d ->
                    DailyWeather(d.getString("date"), d.getInt("maxC"), d.getInt("minC"), d.getInt("code"), d.getString("desc"))
                }}
                val data = WeatherData(obj.getInt("tempC"), obj.getInt("feelsLikeC"),
                    obj.getInt("humidity"), obj.getString("windDir"), obj.getInt("windSpeed"),
                    obj.getInt("uvIndex"), obj.getInt("code"), obj.getString("desc"), hourly, daily)
                Pair(data, city)
            } catch (_: Exception) { null }
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

data class AstronomyData(
    val sunrise: String, val sunset: String,
    val moonrise: String, val moonset: String,
    val moonPhase: String, val moonIllumination: Int
)