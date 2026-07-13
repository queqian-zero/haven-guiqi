package com.haven.guiqi

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.*

/**
 * WeatherCardRenderer — 天气分享卡片构建器
 *
 * 纯 View 工厂，不依赖 Activity 也不管气泡定位。
 * BubbleRenderer 拿到 View 后自己决定放左还是放右。
 */
object WeatherCardRenderer {

    fun buildCard(context: Context, data: WeatherData, city: String): View {
        val dp = { v: Int -> (v * context.resources.displayMetrics.density).toInt() }
        val c = ThemeHelper.getColors(context)

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(c.card)
                setStroke(1, c.border)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // ===== 上部：城市 日期 / 温度 天气 =====
        val topSection = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(8))
        }

        // 城市 + 日期 行
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerRow.addView(TextView(context).apply {
            text = "📍 $city"
            textSize = 11f; setTextColor(c.textSecondary)
        })
        headerRow.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        })
        headerRow.addView(TextView(context).apply {
            text = SimpleDateFormat("M月d日", Locale.CHINESE).format(Date())
            textSize = 11f; setTextColor(c.textSecondary)
        })
        topSection.addView(headerRow)

        // 温度 + emoji + 天气描述 行
        val tempRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, 0)
        }
        // 左：大温度 + emoji
        val tempLeft = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        tempLeft.addView(TextView(context).apply {
            text = "${data.tempC}°"
            textSize = 30f; setTextColor(c.textPrimary)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            includeFontPadding = false
        })
        tempRow.addView(tempLeft)

        // 右：天气描述 + 高低温
        val tempRight = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        tempRight.addView(TextView(context).apply {
            text = data.desc; textSize = 13f; maxLines = 1
            setTextColor(c.textPrimary); typeface = Typeface.DEFAULT_BOLD
        })
        val today = data.daily.firstOrNull()
        if (today != null) {
            tempRight.addView(TextView(context).apply {
                text = "${today.minC}° / ${today.maxC}°"
                textSize = 12f; setTextColor(c.textSecondary)
                setPadding(0, dp(2), 0, 0)
            })
        }
        tempRow.addView(tempRight)
        topSection.addView(tempRow)

        // 指标行：体感 湿度 UV 风
        val metricsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        val metrics = listOf(
            "体感 ${data.feelsLikeC}°",
            "湿度 ${data.humidity}%",
            "UV ${WeatherStorage.uvLabel(data.uvIndex)}",
            "${data.windDir}风"
        )
        for (m in metrics) {
            metricsRow.addView(TextView(context).apply {
                text = m; textSize = 10f; setTextColor(c.textSecondary)
                setPadding(0, 0, dp(10), 0)
            })
        }
        topSection.addView(metricsRow)
        card.addView(topSection)

        // ===== 分隔线 =====
        card.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            )
            setBackgroundColor(c.border)
        })

        // ===== 下部：逐时预报 =====
        val now = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val upcoming = data.hourly.filter { h ->
            val hour = h.time.substringBefore(":").toIntOrNull() ?: 0
            hour >= now
        }.take(5)

        if (upcoming.isNotEmpty()) {
            val hourlyRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(8), dp(14), dp(10))
            }
            for (h in upcoming) {
                val col = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                col.addView(TextView(context).apply {
                    text = h.time.substringBefore(":") + "时"
                    textSize = 10f; setTextColor(c.textSecondary); gravity = Gravity.CENTER
                })
                col.addView(TextView(context).apply {
                    text = weatherEmoji(h.code)
                    textSize = 12f; gravity = Gravity.CENTER
                    setPadding(0, dp(2), 0, dp(2))
                })
                col.addView(TextView(context).apply {
                    text = "${h.tempC}°"; textSize = 11f
                    setTextColor(c.textPrimary); gravity = Gravity.CENTER
                    typeface = Typeface.DEFAULT_BOLD
                })
                hourlyRow.addView(col)
            }
            card.addView(hourlyRow)
        } else {
            card.addView(TextView(context).apply {
                text = "今日预报已结束，明天见 ☽"
                textSize = 11f; setTextColor(c.textSecondary)
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(10), dp(14), dp(12))
            })
        }

        return card
    }

    fun weatherEmoji(code: Int): String = when (code) {
        113 -> "☀"
        116 -> "⛅"
        119, 122 -> "☁"
        143, 248, 260 -> "🌫"
        176, 263, 266, 293, 296 -> "🌦"
        299, 302, 305, 308, 356, 359 -> "🌧"
        200, 386, 389, 392, 395 -> "⛈"
        179, 182, 185, 227, 230, 311, 314, 317, 320,
        323, 326, 329, 332, 335, 338, 350,
        362, 365, 368, 371, 374, 377 -> "❄"
        else -> "☁"
    }
}