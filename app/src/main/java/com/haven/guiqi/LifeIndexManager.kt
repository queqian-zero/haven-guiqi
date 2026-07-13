package com.haven.guiqi

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

/**
 * LifeIndexManager — 天气生活指数
 *
 * 用小助手API根据天气数据推算穿衣/防晒/运动/洗车等建议。
 * 没配置小助手API就显示占位。
 * 结果缓存到当天，不重复调用。
 */
class LifeIndexManager(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val c = ThemeHelper.getColors(context)
    private val prefs = context.getSharedPreferences("haven_life_index", Context.MODE_PRIVATE)

    data class IndexItem(val icon: String, val name: String, val level: String, val tip: String)

    /** 构建生活指数区域的容器 */
    fun buildContainer(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
    }

    /** 加载生活指数：有缓存用缓存，没缓存调API，没配置API显示占位 */
    fun load(container: LinearLayout, weatherData: WeatherData, city: String) {
        container.removeAllViews()

        val cached = getCachedIndex()
        if (cached != null) {
            renderCards(container, cached)
            return
        }

        val storage = LockScreenStorage(context)
        if (!storage.isHelperApiConfigured()) {
            renderPlaceholder(container)
            return
        }

        // 显示加载中
        container.addView(TextView(context).apply {
            text = "🔮 正在推算生活指数..."
            textSize = 12f; setTextColor(c.textHint); gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
        })

        Thread {
            val items = fetchFromApi(storage, weatherData, city)
            mainHandler.post {
                container.removeAllViews()
                if (items != null && items.isNotEmpty()) {
                    saveCachedIndex(items)
                    renderCards(container, items)
                } else {
                    renderPlaceholder(container)
                }
            }
        }.start()
    }

    // ===== 渲染 =====

    private fun renderCards(container: LinearLayout, items: List<IndexItem>) {
        // 2列网格
        var row: LinearLayout? = null
        for ((i, item) in items.withIndex()) {
            if (i % 2 == 0) {
                row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(8) }
                }
                container.addView(row)
            }
            row?.addView(makeCard(item), LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { if (i % 2 == 0) marginEnd = dp(8) })
        }
    }

    private fun makeCard(item: IndexItem): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat(); setColor(c.card)
            }
            // 图标 + 名称行
            val header = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            }
            header.addView(TextView(context).apply {
                text = item.icon; textSize = 14f
                setPadding(0, 0, dp(4), 0)
            })
            header.addView(TextView(context).apply {
                text = item.name; textSize = 11f; setTextColor(c.textHint)
            })
            header.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
            })
            header.addView(TextView(context).apply {
                text = item.level; textSize = 12f; setTextColor(c.textPrimary)
                typeface = Typeface.DEFAULT_BOLD
            })
            addView(header)
            // 建议（点击展开/折叠）
            val tipView = TextView(context).apply {
                text = item.tip; textSize = 10f; setTextColor(c.textSecondary)
                setPadding(0, dp(4), 0, dp(2))
                setLineSpacing(0f, 1.2f)
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            }
            addView(tipView)
            setOnClickListener {
                if (tipView.maxLines == 1) {
                    tipView.maxLines = Integer.MAX_VALUE
                    tipView.ellipsize = null
                } else {
                    tipView.maxLines = 1
                    tipView.ellipsize = android.text.TextUtils.TruncateAt.END
                }
            }
        }
    }

    private fun renderPlaceholder(container: LinearLayout) {
        container.addView(TextView(context).apply {
            text = "🔮 配置小助手API后可查看穿衣、防晒等生活建议"
            textSize = 11f; setTextColor(c.textHint); gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
        })
    }

    // ===== API 调用 =====

    private fun fetchFromApi(storage: LockScreenStorage, data: WeatherData, city: String): List<IndexItem>? {
        return try {
            val url = storage.getHelperApiUrl()
            val key = storage.getHelperApiKey()
            val model = storage.getHelperApiModel()
            val today = data.daily.firstOrNull()

            val prompt = """根据以下天气信息，给出6项生活指数建议。
城市：$city  温度：${data.tempC}°C  体感：${data.feelsLikeC}°C
天气：${data.desc}  湿度：${data.humidity}%  紫外线指数：${data.uvIndex}
风速：${data.windSpeed}km/h  今日最低${today?.minC ?: "--"}°C 最高${today?.maxC ?: "--"}°C

请按JSON数组格式回复，每项包含icon(一个emoji)、name(指数名)、level(等级如"适宜""较差")、tip(一句话建议)。
6项为：穿衣、防晒、运动、洗车、出行、晾晒。
只返回JSON数组，不要其他文字。"""

            val endpoint = if (url.endsWith("/")) "${url}chat/completions" else "$url/chat/completions"
            val conn = URL(endpoint).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 15000; conn.readTimeout = 30000
            conn.doOutput = true

            val body = JSONObject().apply {
                put("model", model)
                put("max_tokens", 500)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply { put("role", "user"); put("content", prompt) })
                })
            }
            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

            if (conn.responseCode == 200) {
                val resp = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                val text = JSONObject(resp).getJSONArray("choices")
                    .getJSONObject(0).getJSONObject("message").getString("content")
                    .trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                val arr = JSONArray(text)
                (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    IndexItem(
                        icon = obj.optString("icon", "📊"),
                        name = obj.optString("name", "指数"),
                        level = obj.optString("level", "—"),
                        tip = obj.optString("tip", "")
                    )
                }
            } else null
        } catch (e: Exception) {
            android.util.Log.e("LifeIndex", "fetch failed", e)
            null
        }
    }

    // ===== 缓存（按天） =====

    private fun todayKey(): String = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

    private fun getCachedIndex(): List<IndexItem>? {
        val key = prefs.getString("date", "") ?: ""
        if (key != todayKey()) return null
        val json = prefs.getString("data", null) ?: return null
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                IndexItem(obj.getString("icon"), obj.getString("name"),
                    obj.getString("level"), obj.getString("tip"))
            }
        } catch (_: Exception) { null }
    }

    private fun saveCachedIndex(items: List<IndexItem>) {
        val arr = JSONArray()
        for (item in items) arr.put(JSONObject().apply {
            put("icon", item.icon); put("name", item.name)
            put("level", item.level); put("tip", item.tip)
        })
        prefs.edit().putString("date", todayKey()).putString("data", arr.toString()).apply()
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()
}