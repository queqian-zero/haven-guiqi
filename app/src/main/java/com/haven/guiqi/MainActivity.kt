package com.haven.guiqi

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * MainActivity — 锁屏 + 文案层 + 进入桌面
 *
 * 流程：锁屏壁纸（时间）→ 点击 → 文案层浮出 → 点文案 → 动画过渡 → 桌面
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tvTime: TextView
    private lateinit var tvDate: TextView
    private lateinit var lockScreen: FrameLayout
    private lateinit var textLayer: FrameLayout
    private lateinit var lockScreenStorage: LockScreenStorage

    private val handler = Handler(Looper.getMainLooper())
    private val updateTimeRunnable = object : Runnable {
        override fun run() {
            updateTime()
            handler.postDelayed(this, 1000)
        }
    }

    // 排版维度选项
    private val colorSchemes = listOf(
        intArrayOf(Color.parseColor("#CC3333"), Color.parseColor("#A0A0A0")),  // 红+灰
        intArrayOf(Color.parseColor("#D4A574"), Color.parseColor("#8B7B6B")),  // 暖金+棕
        intArrayOf(Color.parseColor("#6B8E9B"), Color.parseColor("#9B9B9B")),  // 青蓝+灰
        intArrayOf(Color.parseColor("#C97B7B"), Color.parseColor("#A08080")),  // 玫瑰+灰粉
        intArrayOf(Color.parseColor("#7BA37B"), Color.parseColor("#8B9B8B")),  // 森绿+灰绿
        intArrayOf(Color.parseColor("#9B7BB8"), Color.parseColor("#9090A0")),  // 薰紫+灰紫
        intArrayOf(Color.parseColor("#D4943A"), Color.parseColor("#A09080"))   // 琥珀+棕灰
    )

    private val foreignSizes = listOf(22f, 24f, 26f, 20f, 28f, 23f, 25f)
    private val chineseSizes = listOf(12f, 13f, 14f, 11f, 13f, 12f, 14f)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        setContentView(R.layout.activity_main)

        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.navigationBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.isAppearanceLightStatusBars = false

        lockScreen = findViewById(R.id.lockScreen)
        tvTime = findViewById(R.id.tvTime)
        tvDate = findViewById(R.id.tvDate)
        lockScreenStorage = LockScreenStorage(this)

        // 创建文案层（初始隐藏）
        textLayer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
            alpha = 0f
            isClickable = true
            isFocusable = true
        }
        lockScreen.addView(textLayer)

        // 加载壁纸
        loadLockWallpaper()

        // 点击锁屏 → 显示文案层
        lockScreen.setOnClickListener {
            lockScreen.setOnClickListener(null) // 防重复点击
            showTextLayer()
        }

        updateTime()
        loadDailyText()
    }

    override fun onResume() {
        super.onResume()
        handler.post(updateTimeRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(updateTimeRunnable)
    }

    private fun updateTime() {
        val now = Calendar.getInstance()
        tvTime.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now.time)
        val weekDays = arrayOf("星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六")
        val month = now.get(Calendar.MONTH) + 1
        val day = now.get(Calendar.DAY_OF_MONTH)
        tvDate.text = "${month}月${day}日 ${weekDays[now.get(Calendar.DAY_OF_WEEK) - 1]}"
    }

    // ===== 壁纸 =====

    private fun loadLockWallpaper() {
        val isDark = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val slot = if (isDark) "lock_night" else "lock_day"
        val path = lockScreenStorage.getWallpaper(slot)
        if (path.isNotEmpty() && File(path).exists()) {
            val bg = ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(BitmapFactory.decodeFile(path))
            }
            lockScreen.addView(bg, 0)
        }
        // 没有自定义壁纸就用默认的渐变背景（XML里定义的）
    }

    // ===== 文案加载 =====

    private var dailyText: DailyText? = null

    private fun loadDailyText() {
        // 先检查缓存
        val cached = lockScreenStorage.getTodayText()
        if (cached != null) {
            dailyText = cached
            return
        }

        // 尝试调小助手API
        if (lockScreenStorage.isHelperApiConfigured()) {
            Thread {
                try {
                    val api = ApiHelper(
                        lockScreenStorage.getHelperApiUrl(),
                        lockScreenStorage.getHelperApiKey(),
                        lockScreenStorage.getHelperApiModel(),
                        "openai"
                    )
                    val prompt = listOf(
                        ChatMessage("system", """你是一个文案生成器。生成一句关于"回家、等待、想念、温暖"主题的短文案。
要求：
1. 外语部分不超过15个英文单词，语言从英语/意大利语/法语/俄语/日语中随机选一种
2. 中文翻译不超过20个字
3. 只回复JSON格式：{"foreign":"外语文案","chinese":"中文翻译","lang":"语言代码"}
4. 不要加任何其他内容"""),
                        ChatMessage("user", "生成今日文案")
                    )
                    val response = api.sendChat(prompt)
                    val text = parseTextResponse(response.text)
                    if (text != null) {
                        dailyText = text
                        lockScreenStorage.saveTodayText(text)
                    }
                } catch (_: Exception) {
                    // API失败，用兜底
                }
            }.start()
        }

        // 兜底
        if (dailyText == null) {
            val day = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
            dailyText = lockScreenStorage.getBuiltinText(day)
            lockScreenStorage.saveTodayText(dailyText!!)
        }
    }

    private fun parseTextResponse(raw: String): DailyText? {
        return try {
            // 提取JSON部分
            val jsonStr = raw.let {
                val start = it.indexOf("{")
                val end = it.lastIndexOf("}") + 1
                if (start >= 0 && end > start) it.substring(start, end) else it
            }
            val obj = org.json.JSONObject(jsonStr)
            DailyText(
                obj.getString("foreign"),
                obj.getString("chinese"),
                obj.optString("lang", "en")
            )
        } catch (_: Exception) { null }
    }

    // ===== 文案层展示（随机组合排版） =====

    private fun showTextLayer() {
        val text = dailyText ?: return enterDesktop()
        val seed = lockScreenStorage.getTodayLayoutSeed()
        val rand = Random(System.currentTimeMillis()) // 位置每次随机

        // 随机组合各维度
        val colors = colorSchemes[Math.abs(seed) % colorSchemes.size]
        val foreignSize = foreignSizes[Math.abs(seed / 7) % foreignSizes.size]
        val chineseSize = chineseSizes[Math.abs(seed / 49) % chineseSizes.size]
        val layoutType = Math.abs(seed / 343) % 5  // 5种布局

        // 随机位置（安全区域内）
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val marginH = (screenW * 0.1f).toInt()
        val marginV = (screenH * 0.15f).toInt()
        val maxX = screenW - marginH * 2
        val maxY = screenH - marginV * 2
        val posX = marginH + rand.nextInt(maxOf(maxX / 2, 1))
        val posY = marginV + rand.nextInt(maxOf(maxY / 3, 1))

        // 尝试加载花体字体
        val fancyTypeface = try {
            Typeface.createFromAsset(assets, "fonts/GreatVibes-Regular.ttf")
        } catch (_: Exception) {
            Typeface.create("serif", Typeface.ITALIC)
        }

        // 构建文案视图
        val container = buildTextContainer(
            text, colors, foreignSize, chineseSize, fancyTypeface, layoutType
        )

        val containerParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            leftMargin = posX
            topMargin = posY
        }

        textLayer.removeAllViews()

        // 半透明遮罩
        val overlay = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#40000000"))
        }
        textLayer.addView(overlay)
        textLayer.addView(container, containerParams)

        // 点击文案 → 进桌面
        textLayer.setOnClickListener {
            textLayer.setOnClickListener(null)
            enterDesktopWithAnimation()
        }

        // 淡入动画
        textLayer.visibility = View.VISIBLE
        val fadeIn = ObjectAnimator.ofFloat(textLayer, "alpha", 0f, 1f).apply {
            duration = 600
            interpolator = DecelerateInterpolator()
        }

        // 时间区域淡出
        val timeFade = ObjectAnimator.ofFloat(
            findViewById<View>(R.id.tvTime), "alpha", 1f, 0.3f
        ).apply { duration = 600 }
        val dateFade = ObjectAnimator.ofFloat(
            findViewById<View>(R.id.tvDate), "alpha", 1f, 0.3f
        ).apply { duration = 600 }

        AnimatorSet().apply {
            playTogether(fadeIn, timeFade, dateFade)
            start()
        }
    }

    /** 根据布局类型构建不同排版的文案容器 */
    private fun buildTextContainer(
        text: DailyText, colors: IntArray,
        foreignSize: Float, chineseSize: Float,
        fancyTypeface: Typeface, layoutType: Int
    ): View {
        val foreignColor = colors[0]
        val chineseColor = colors[1]

        return when (layoutType) {
            0 -> buildVerticalLayout(text, foreignColor, chineseColor, foreignSize, chineseSize, fancyTypeface, Gravity.START)
            1 -> buildVerticalLayout(text, foreignColor, chineseColor, foreignSize, chineseSize, fancyTypeface, Gravity.CENTER)
            2 -> buildVerticalLayout(text, foreignColor, chineseColor, foreignSize, chineseSize, fancyTypeface, Gravity.END)
            3 -> buildReversedLayout(text, foreignColor, chineseColor, foreignSize, chineseSize, fancyTypeface)
            else -> buildCardLayout(text, foreignColor, chineseColor, foreignSize, chineseSize, fancyTypeface)
        }
    }

    /** 标准上下排列（外语在上，中文在下） */
    private fun buildVerticalLayout(
        text: DailyText, fc: Int, cc: Int,
        fs: Float, cs: Float, typeface: Typeface, gravity: Int
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            this.gravity = gravity
            setPadding(dp(16), dp(12), dp(16), dp(12))

            addView(TextView(this@MainActivity).apply {
                this.text = text.foreign
                textSize = fs
                setTextColor(fc)
                this.typeface = typeface
                setPadding(0, 0, 0, dp(6))
            })
            addView(TextView(this@MainActivity).apply {
                this.text = text.chinese
                textSize = cs
                setTextColor(cc)
            })
        }
    }

    /** 反转排列（中文在上，外语在下） */
    private fun buildReversedLayout(
        text: DailyText, fc: Int, cc: Int,
        fs: Float, cs: Float, typeface: Typeface
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            setPadding(dp(16), dp(12), dp(16), dp(12))

            addView(TextView(this@MainActivity).apply {
                this.text = text.chinese
                textSize = cs + 2
                setTextColor(cc)
                setPadding(0, 0, 0, dp(8))
            })
            addView(TextView(this@MainActivity).apply {
                this.text = text.foreign
                textSize = fs - 2
                setTextColor(fc)
                this.typeface = typeface
            })
        }
    }

    /** 卡片式（带半透明背景） */
    private fun buildCardLayout(
        text: DailyText, fc: Int, cc: Int,
        fs: Float, cs: Float, typeface: Typeface
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(16), dp(20), dp(16))
            setBackgroundColor(Color.parseColor("#30FFFFFF"))

            addView(TextView(this@MainActivity).apply {
                this.text = text.foreign
                textSize = fs
                setTextColor(fc)
                this.typeface = typeface
                this.gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(8))
            })
            addView(View(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(1)).apply {
                    bottomMargin = dp(8)
                }
                setBackgroundColor(Color.parseColor("#40FFFFFF"))
            })
            addView(TextView(this@MainActivity).apply {
                this.text = text.chinese
                textSize = cs
                setTextColor(cc)
                this.gravity = Gravity.CENTER
            })
        }
    }

    // ===== 进入桌面 =====

    private fun enterDesktopWithAnimation() {
        val fadeOut = ObjectAnimator.ofFloat(lockScreen, "alpha", 1f, 0f).apply {
            duration = 500
            interpolator = DecelerateInterpolator()
        }
        fadeOut.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(anim: android.animation.Animator) {
                enterDesktop()
            }
        })
        fadeOut.start()
    }

    private fun enterDesktop() {
        startActivity(Intent(this, DesktopActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}