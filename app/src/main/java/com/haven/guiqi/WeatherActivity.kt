package com.haven.guiqi

import android.Manifest
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.location.Geocoder
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.text.SimpleDateFormat
import java.util.*

class WeatherActivity : AppCompatActivity() {

    private lateinit var weatherStorage: WeatherStorage
    private val handler = Handler(Looper.getMainLooper())
    private val c get() = ThemeHelper.getColors(this)

    private lateinit var cityText: TextView
    private lateinit var tempText: TextView
    private lateinit var descText: TextView
    private lateinit var iconText: TextView
    private lateinit var feelsText: TextView
    private lateinit var humidityText: TextView
    private lateinit var windText: TextView
    private lateinit var uvText: TextView
    private lateinit var hourlyContainer: LinearLayout
    private lateinit var dailyContainer: LinearLayout
    private lateinit var mainContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.statusBarColor = Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            weatherStorage = WeatherStorage(this)
            ThemeHelper.init(this)
            buildUI()
            val city = weatherStorage.getCity()
            if (city.isEmpty()) showCitySetup() else loadWeather(city)
        } catch (e: Exception) {
            Toast.makeText(this, "窗外崩溃: ${e.message}\n${e.stackTrace.firstOrNull()}", Toast.LENGTH_LONG).show()
            android.util.Log.e("WeatherActivity", "onCreate crashed", e)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUI() {
        val root = ScrollView(this).apply {
            setBackgroundColor(c.background)
            isFillViewport = true
        }
        mainContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(48), 0, dp(24))
        }
        root.addView(mainContainer)

        // 顶栏
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val btnBack = TextView(this).apply {
            text = "←"; textSize = 20f; setTextColor(c.textPrimary)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { finish() }
        }
        val title = TextView(this).apply {
            text = "窗外"; textSize = 16f; setTextColor(c.textPrimary)
            typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnRefresh = TextView(this).apply {
            text = "↻"; textSize = 20f; setTextColor(c.textSecondary)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener {
                val city = weatherStorage.getCity()
                if (city.isNotEmpty()) loadWeather(city, force = true)
            }
        }
        topBar.addView(btnBack); topBar.addView(title); topBar.addView(btnRefresh)
        mainContainer.addView(topBar)

        // 城市
        val cityRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(dp(16), dp(8), dp(16), 0)
        }
        cityText = TextView(this).apply {
            text = "未设置城市"; textSize = 13f; setTextColor(c.textSecondary)
            setOnClickListener { showCitySetup() }
        }
        val cityArrow = TextView(this).apply {
            text = " ▾"; textSize = 11f; setTextColor(c.textHint)
        }
        cityRow.addView(cityText); cityRow.addView(cityArrow)
        mainContainer.addView(cityRow)

        // 当前温度
        val currentBlock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(dp(16), dp(20), dp(16), dp(12))
        }
        tempText = TextView(this).apply {
            text = "--°"; textSize = 56f; setTextColor(c.textPrimary)
            typeface = Typeface.DEFAULT_BOLD
        }
        iconText = TextView(this).apply {
            text = ""; textSize = 40f
            setPadding(dp(24), 0, 0, 0)
        }
        currentBlock.addView(tempText); currentBlock.addView(iconText)
        mainContainer.addView(currentBlock)

        descText = TextView(this).apply {
            text = ""; textSize = 15f; setTextColor(c.textSecondary); gravity = Gravity.CENTER
            setPadding(dp(16), 0, dp(16), dp(16))
        }
        mainContainer.addView(descText)

        // 指标卡片 2x2
        val metricsGrid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), dp(16))
        }
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        feelsText = makeMetricCard("体感", "--°")
        humidityText = makeMetricCard("湿度", "--%")
        row1.addView(feelsText.parent as View, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        row1.addView(humidityText.parent as View, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        windText = makeMetricCard("风速", "--")
        uvText = makeMetricCard("紫外线", "--")
        row2.addView(windText.parent as View, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        row2.addView(uvText.parent as View, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        metricsGrid.addView(row1); metricsGrid.addView(row2)
        mainContainer.addView(metricsGrid)

        // 分隔 + 逐时标题
        mainContainer.addView(makeDivider())
        mainContainer.addView(makeSectionTitle("今日逐时"))

        val hourlyScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        hourlyContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), 0, dp(12), dp(12))
        }
        hourlyScroll.addView(hourlyContainer)
        mainContainer.addView(hourlyScroll)

        // 分隔 + 7天标题
        mainContainer.addView(makeDivider())
        mainContainer.addView(makeSectionTitle("未来几天"))
        dailyContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(8))
        }
        mainContainer.addView(dailyContainer)

        // 底部按钮栏
        mainContainer.addView(makeDivider())
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        val locateBtn = TextView(this).apply {
            text = "⊕ 定位"; textSize = 12f; setTextColor(c.accent)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setOnClickListener { requestLocation() }
        }
        val searchBtn = TextView(this).apply {
            text = "⌕ 搜索"; textSize = 12f; setTextColor(c.accent)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setOnClickListener { showSearchDialog() }
        }
        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }
        val shareBtn = TextView(this).apply {
            text = "♡ 分享给TA"; textSize = 12f; setTextColor(c.accent)
            setBackgroundColor(c.accentBg)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { shareWeather() }
        }
        bottomBar.addView(locateBtn); bottomBar.addView(searchBtn)
        bottomBar.addView(spacer); bottomBar.addView(shareBtn)
        mainContainer.addView(bottomBar)

        setContentView(root)
        val insetsCtrl = WindowInsetsControllerCompat(window, window.decorView)
        insetsCtrl.hide(WindowInsetsCompat.Type.navigationBars())
        insetsCtrl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsCtrl.isAppearanceLightStatusBars = !ThemeHelper.isDark(this)
    }

    private fun makeMetricCard(label: String, defaultVal: String): TextView {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(c.card)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val labelView = TextView(this).apply {
            text = label; textSize = 11f; setTextColor(c.textHint)
        }
        val valueView = TextView(this).apply {
            text = defaultVal; textSize = 14f; setTextColor(c.textPrimary)
            typeface = Typeface.DEFAULT_BOLD
        }
        card.addView(labelView); card.addView(valueView)
        return valueView
    }

    private fun makeDivider(): View {
        val wrapper = FrameLayout(this).apply { setPadding(dp(16), 0, dp(16), 0) }
        val line = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(c.divider)
        }
        wrapper.addView(line); return wrapper
    }

    private fun makeSectionTitle(text: String): TextView {
        return TextView(this).apply {
            this.text = text; textSize = 12f; setTextColor(c.textHint)
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }
    }

    // ===== 加载天气 =====

    private fun loadWeather(city: String, force: Boolean = false) {
        cityText.text = city
        descText.text = "加载中..."
        if (!force) {
            val cached = weatherStorage.getCachedWeather()
            if (cached != null && !weatherStorage.shouldRefresh()) {
                displayWeather(cached); return
            }
        }
        Thread {
            val data = weatherStorage.fetchWeather(city)
            handler.post {
                if (data != null) displayWeather(data)
                else descText.text = "加载失败，点右上角重试"
            }
        }.start()
    }

    private fun displayWeather(data: WeatherData) {
        // 温度动画
        animateNumber(tempText, data.tempC, "°")
        iconText.text = weatherEmoji(data.code)
        descText.text = data.desc
        feelsText.text = "${data.feelsLikeC}°"
        humidityText.text = "${data.humidity}%"
        windText.text = "${data.windDir} ${WeatherStorage.windLevel(data.windSpeed)}"
        val uvLbl = WeatherStorage.uvLabel(data.uvIndex)
        uvText.text = uvLbl

        // 逐时（全部显示，当前时间高亮）
        hourlyContainer.removeAllViews()
        val nowHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        for (h in data.hourly) {
            val hh = h.time.substringBefore(":").toIntOrNull() ?: 0
            val isClosest = hh <= nowHour && (data.hourly.indexOf(h) == data.hourly.size - 1 ||
                (data.hourly.getOrNull(data.hourly.indexOf(h) + 1)?.time?.substringBefore(":")?.toIntOrNull() ?: 99) > nowHour)
            hourlyContainer.addView(makeHourlyItem(h, isClosest))
        }

        // 7天
        dailyContainer.removeAllViews()
        val dayNames = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")
        for ((i, d) in data.daily.withIndex()) {
            val label = if (i == 0) "今天" else {
                try {
                    val cal = Calendar.getInstance()
                    cal.time = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(d.date)!!
                    dayNames[cal.get(Calendar.DAY_OF_WEEK) - 1]
                } catch (_: Exception) { d.date.takeLast(5) }
            }
            dailyContainer.addView(makeDailyRow(label, d, i < data.daily.size - 1))
        }
    }

    private fun makeHourlyItem(h: HourlyWeather, isNow: Boolean): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            minimumWidth = dp(52); setPadding(dp(4), dp(6), dp(4), dp(6))
            addView(TextView(this@WeatherActivity).apply {
                text = if (isNow) "现在" else h.time
                textSize = 11f; setTextColor(c.textHint); gravity = Gravity.CENTER
            })
            addView(TextView(this@WeatherActivity).apply {
                text = weatherEmoji(h.code); textSize = 16f; gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, dp(4))
            })
            addView(TextView(this@WeatherActivity).apply {
                text = "${h.tempC}°"; textSize = 13f
                setTextColor(c.textPrimary); gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
            })
        }
    }

    private fun makeDailyRow(dayLabel: String, d: DailyWeather, showDivider: Boolean): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            if (showDivider) {
                val bg = android.graphics.drawable.GradientDrawable()
                bg.setStroke(1, c.divider)
            }
            addView(TextView(this@WeatherActivity).apply {
                text = dayLabel; textSize = 13f; setTextColor(c.textSecondary)
                layoutParams = LinearLayout.LayoutParams(dp(48), LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            addView(TextView(this@WeatherActivity).apply {
                text = weatherEmoji(d.code); textSize = 15f
                layoutParams = LinearLayout.LayoutParams(dp(24), LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            addView(TextView(this@WeatherActivity).apply {
                text = d.desc; textSize = 12f; setTextColor(c.textHint)
                setPadding(dp(6), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@WeatherActivity).apply {
                text = "${d.minC}°"; textSize = 12f; setTextColor(c.textHint)
            })
            // 温度条
            addView(View(this@WeatherActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(4)).apply {
                    marginStart = dp(6); marginEnd = dp(6)
                }
                setBackgroundColor(c.accent)
            })
            addView(TextView(this@WeatherActivity).apply {
                text = "${d.maxC}°"; textSize = 12f; setTextColor(c.textPrimary)
                typeface = Typeface.DEFAULT_BOLD
            })
        }
    }

    private fun animateNumber(view: TextView, target: Int, suffix: String) {
        ValueAnimator.ofInt(0, target).apply {
            duration = 800; interpolator = DecelerateInterpolator()
            addUpdateListener { view.text = "${it.animatedValue}$suffix" }
            start()
        }
    }

    private fun weatherEmoji(code: Int): String = when (WeatherStorage.weatherIcon(code)) {
        "sun" -> "☀"
        "cloud" -> "☁"
        "cloud_rain" -> "🌧"
        "bolt" -> "⚡"
        "snowflake" -> "❄"
        "mist" -> "🌫"
        else -> "☁"
    }

    // ===== 城市设置 =====

    private fun showCitySetup() {
        val items = arrayOf("自动定位", "手动搜索")
        AlertDialog.Builder(this).setTitle("设置城市").setItems(items) { _, w ->
            when (w) { 0 -> requestLocation(); 1 -> showSearchDialog() }
        }.show()
    }

    private fun showSearchDialog() {
        val input = EditText(this).apply {
            hint = "输入城市名（如：上海、Tokyo）"
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        AlertDialog.Builder(this).setTitle("搜索城市").setView(input)
            .setPositiveButton("确定") { _, _ ->
                val city = input.text.toString().trim()
                if (city.isNotEmpty()) {
                    weatherStorage.setCity(city); loadWeather(city, force = true)
                }
            }.setNegativeButton("取消", null).show()
    }

    private val LOCATION_PERMISSION = 5001
    private fun requestLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), LOCATION_PERMISSION)
            return
        }
        doLocate()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == LOCATION_PERMISSION && results.isNotEmpty() &&
            results[0] == PackageManager.PERMISSION_GRANTED) doLocate()
        else Toast.makeText(this, "需要定位权限", Toast.LENGTH_SHORT).show()
    }

    private fun doLocate() {
        try {
            val lm = getSystemService(LOCATION_SERVICE) as LocationManager
            val loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (loc != null) {
                val geocoder = Geocoder(this, Locale.CHINESE)
                val addrs = geocoder.getFromLocation(loc.latitude, loc.longitude, 1)
                val city = addrs?.firstOrNull()?.locality ?: addrs?.firstOrNull()?.adminArea ?: "${loc.latitude},${loc.longitude}"
                weatherStorage.setCity(city)
                loadWeather(city, force = true)
            } else {
                Toast.makeText(this, "无法获取位置，请手动搜索", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "定位失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ===== 分享 =====

    private fun shareWeather() {
        val data = weatherStorage.getCachedWeather()
        if (data == null) { Toast.makeText(this, "暂无天气数据", Toast.LENGTH_SHORT).show(); return }
        val summary = weatherStorage.buildWeatherSummary()
        val friends = FriendStorage(this).loadFriends()
        if (friends.isEmpty()) { Toast.makeText(this, "还没有好友", Toast.LENGTH_SHORT).show(); return }
        val names = friends.map { "${it.icon} ${it.name}" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("分享天气给谁？").setItems(names) { _, which ->
            val friend = friends[which]
            val chatStorage = ChatStorage(this)
            val now = System.currentTimeMillis()
            chatStorage.appendMessage(friend.id, StoredMessage(
                "user", summary, now, type = "tip"
            ))
            Toast.makeText(this, "已分享给「${friend.name}」♡", Toast.LENGTH_SHORT).show()
        }.setNegativeButton("取消", null).show()
    }
}