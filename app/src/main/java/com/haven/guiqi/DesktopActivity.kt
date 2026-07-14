package com.haven.guiqi

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.*
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.text.SimpleDateFormat
import java.util.*

class DesktopActivity : AppCompatActivity() {
    private lateinit var normalDesktop: LinearLayout
    private lateinit var desktopTime: TextView
    private lateinit var desktopDate: TextView
    private lateinit var gridTop: GridLayout
    private lateinit var gridBottom: GridLayout
    private lateinit var pageDots: LinearLayout

    private lateinit var liveDesktop: LinearLayout
    private lateinit var liveBgImage: ImageView
    private lateinit var normalBgImage: ImageView
    private lateinit var liveTime: TextView
    private lateinit var liveDate: TextView
    private lateinit var drawerBtn: FrameLayout
    private lateinit var drawerBtnIcon: ImageView
    private lateinit var drawerPanel: LinearLayout
    private lateinit var drawerOverlay: View
    private lateinit var drawerGrid: GridLayout

    private var isLiveMode = false
    private var isDrawerOpen = false
    private var isEditMode = false
    private var selectedIndex = -1
    private lateinit var prefs: SharedPreferences
    private var pendingWallpaperSlot = ""
    private lateinit var bulletinBoardManager: BulletinBoardManager

    private val wobbleAnimators = mutableListOf<ObjectAnimator>()
    private val handler = Handler(Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            updateTimeAndDate()
            handler.postDelayed(this, 1000)
        }
    }

    data class AppIcon(
        val type: String,
        val label: String,
        val action: String
    )

    // 默认顺序
    private val defaultIcons = listOf(
        AppIcon("chat", "聊天", "chat"),
        AppIcon("nest", "小窝", "nest"),
        AppIcon("archive", "馆藏", "archive"),
        AppIcon("world", "世界", "world"),
        AppIcon("workshop", "工坊", "workshop"),
        AppIcon("clock", "时钟", "clock"),
        AppIcon("weather", "窗外", "weather"),
        AppIcon("calendar", "日历", "calendar"),
        AppIcon("music", "留声机", "music"),
        AppIcon("browser", "出门", "browser"),
        AppIcon("settings", "杂物间", "settings"),
        AppIcon("beautify", "美化", "beautify")
    )

    // 当前实际使用的图标列表（可能被用户重新排序）
    private var currentIcons = mutableListOf<AppIcon>()

    companion object {
        private const val PICK_IMAGE_REQUEST = 1001
        private const val PICK_NORMAL_BG_REQUEST = 1002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 沉浸式全屏
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        // 允许内容画到挖孔/刘海区域
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        setContentView(R.layout.activity_desktop)

        // 只隐藏底部导航栏，保留顶部状态栏
        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        insetsController.hide(WindowInsetsCompat.Type.navigationBars())
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController.isAppearanceLightStatusBars = !ThemeHelper.isDark(this)

        prefs = getSharedPreferences("haven_prefs", MODE_PRIVATE)

        // 初始化主题
        ThemeHelper.init(this)

        // 创建通知渠道
        NotificationHelper.createChannels(this)
        // 启动前台保活服务
        HavenService.start(this)
        // 检查电池优化白名单
        checkBatteryOptimization()
        checkAccessibilityService()
        // 请求通知权限（Android 13+）
        checkNotificationPermission()

        // ===== 绑定普通模式元素 =====
        normalDesktop = findViewById(R.id.normalDesktop)
        desktopTime = findViewById(R.id.desktopTime)
        desktopDate = findViewById(R.id.desktopDate)
        gridTop = findViewById(R.id.gridTop)
        gridBottom = findViewById(R.id.gridBottom)
        pageDots = findViewById(R.id.pageDots)

        // ===== 绑定立绘模式元素 =====
        liveDesktop = findViewById(R.id.liveDesktop)
        liveBgImage = findViewById(R.id.liveBgImage)
        normalBgImage = findViewById(R.id.normalBgImage)

        // 加载普通桌面壁纸（昼/夜自动切换）
        loadDesktopWallpaper()
        liveTime = findViewById(R.id.liveTime)
        liveDate = findViewById(R.id.liveDate)
        drawerBtn = findViewById(R.id.drawerBtn)
        drawerBtnIcon = findViewById(R.id.drawerBtnIcon)
        drawerPanel = findViewById(R.id.drawerPanel)
        // 恢复上次的抽屉配色
        val savedTint = prefs.getInt("drawer_tint_color", 0)
        if (savedTint != 0) drawerPanel.setBackgroundColor(savedTint)
        drawerOverlay = findViewById(R.id.drawerOverlay)
        drawerGrid = findViewById(R.id.drawerGrid)

        // ===== 适配状态栏高度：给内容加顶部 padding =====
        val rootLayout = findViewById<FrameLayout>(R.id.rootLayout)
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            // 普通模式和立绘模式都加上顶部 padding
            normalDesktop.setPadding(0, statusBarHeight, 0, 0)
            liveDesktop.setPadding(0, statusBarHeight, 0, 0)
            insets
        }

        // ===== 加载保存的图标顺序 =====
        loadIconOrder()

        // ===== 填充图标 =====
        refreshDesktopIcons()
        fillIcons(drawerGrid, currentIcons, 3, 16, false)

        setupPageDots(3, 0)

        // ===== 留言板 =====
        bulletinBoardManager = BulletinBoardManager(
            this,
            findViewById(R.id.bulletinStrip),
            findViewById(R.id.bulletinText)
        )
        bulletinBoardManager.init()

        // ===== 抽屉按钮图标 =====
        drawerBtnIcon.setImageDrawable(LineIconDrawable(this, "grid", dpToPx(20)))

        // ===== 抽屉按钮点击 =====
        drawerBtn.setOnClickListener { toggleDrawer() }

        // ===== 遮罩点击关闭抽屉 =====
        drawerOverlay.setOnClickListener { toggleDrawer() }

        // ===== 长按切换模式（普通桌面） =====
        normalDesktop.setOnLongClickListener {
            if (isEditMode) {
                exitEditMode()
            } else {
                showModeMenu()
            }
            true
        }

        // ===== 长按切换模式（立绘桌面） =====
        liveDesktop.setOnLongClickListener {
            showModeMenu()
            true
        }

        // ===== 读取上次的模式 =====
        isLiveMode = prefs.getBoolean("live_mode", false)
        if (isLiveMode) {
            switchToLiveMode(false)
        }

        updateTimeAndDate()
    }

    override fun onResume() {
        super.onResume()
        handler.post(updateRunnable)
        bulletinBoardManager.refresh()
        bulletinBoardManager.resumeScroll()
        // 返回桌面时重新隐藏底部导航栏
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.navigationBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(updateRunnable)
        bulletinBoardManager.stopScroll()
    }

    private fun saveIconOrder() {
        val orderStr = currentIcons.joinToString(",") { it.action }
        prefs.edit().putString("icon_order", orderStr).apply()
    }

    private fun loadIconOrder() {
        val orderStr = prefs.getString("icon_order", null)
        if (orderStr != null) {
            val actionOrder = orderStr.split(",")
            val reordered = mutableListOf<AppIcon>()
            for (action in actionOrder) {
                val icon = defaultIcons.find { it.action == action }
                if (icon != null) reordered.add(icon)
            }
            // 把可能漏掉的新图标补到末尾
            for (icon in defaultIcons) {
                if (reordered.none { it.action == icon.action }) {
                    reordered.add(icon)
                }
            }
            currentIcons = reordered
        } else {
            currentIcons = defaultIcons.toMutableList()
        }
    }

    private fun refreshDesktopIcons() {
        fillIcons(gridTop, currentIcons, 4, 55, true)
    }

    private fun enterEditMode() {
        isEditMode = true
        selectedIndex = -1
        Toast.makeText(this, "编辑模式：点击两个图标交换位置\n长按空白处退出", Toast.LENGTH_SHORT).show()
        refreshDesktopIcons()  // 重新填充，带上晃动动画
    }

    private fun exitEditMode() {
        isEditMode = false
        selectedIndex = -1
        // 停止所有晃动动画
        for (anim in wobbleAnimators) {
            anim.cancel()
        }
        wobbleAnimators.clear()
        // 重新填充，去掉晃动
        refreshDesktopIcons()
        Toast.makeText(this, "已退出编辑模式", Toast.LENGTH_SHORT).show()
    }

    private fun showModeMenu() {
        val items = if (isLiveMode) {
            arrayOf("切换到普通模式", "更换立绘背景（昼）", "更换立绘背景（夜）")
        } else {
            arrayOf("切换到立绘模式", "更换桌面壁纸（昼）", "更换桌面壁纸（夜）", "编辑桌面图标")
        }

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
            .setTitle("桌面设置")
            .setItems(items) { _, which ->
                if (isLiveMode) {
                    when (which) {
                        0 -> switchToNormalMode()
                        1 -> { pendingWallpaperSlot = "live2d_day"; pickImage() }
                        2 -> { pendingWallpaperSlot = "live2d_night"; pickImage() }
                    }
                } else {
                    when (which) {
                        0 -> switchToLiveMode(true)
                        1 -> { pendingWallpaperSlot = "desktop_day"; pickNormalWallpaper() }
                        2 -> { pendingWallpaperSlot = "desktop_night"; pickNormalWallpaper() }
                        3 -> enterEditMode()
                    }
                }
            }
            .show()
    }

    private fun switchToLiveMode(save: Boolean) {
        // 如果在编辑模式，先退出
        if (isEditMode) exitEditMode()

        isLiveMode = true
        if (save) prefs.edit().putBoolean("live_mode", true).apply()

        normalDesktop.visibility = View.GONE
        normalBgImage.visibility = View.GONE
        liveDesktop.visibility = View.VISIBLE
        liveBgImage.visibility = View.VISIBLE
        drawerBtn.visibility = View.VISIBLE

        // 立绘壁纸（昼/夜自动切换）
        val liveSlot = if (isDarkMode()) "live2d_night" else "live2d_day"
        val livePath = LockScreenStorage(this).getWallpaper(liveSlot)
        val bgUri = if (livePath.isNotEmpty()) livePath else prefs.getString("live_bg_uri", null)
        if (bgUri != null) {
            try {
                liveBgImage.setImageURI(Uri.parse(bgUri))
                updateDrawerTint(Uri.parse(bgUri))
            } catch (e: Exception) {
                liveBgImage.visibility = View.GONE
            }
        }
    }

    /** 判断当前是否深色模式 */
    private fun isDarkMode(): Boolean = ThemeHelper.isDark(this)

    /** 加载普通桌面壁纸（昼/夜自动切换） */
    private fun loadDesktopWallpaper() {
        val slot = if (isDarkMode()) "desktop_night" else "desktop_day"
        val path = LockScreenStorage(this).getWallpaper(slot)
        if (path.isNotEmpty()) {
            try {
                normalBgImage.setImageURI(Uri.parse(path))
                normalBgImage.visibility = View.VISIBLE
                return
            } catch (_: Exception) { }
        }
        // 降级：尝试旧的 key
        val oldUri = prefs.getString("normal_bg_uri", null)
        if (oldUri != null) {
            try {
                normalBgImage.setImageURI(Uri.parse(oldUri))
                normalBgImage.visibility = View.VISIBLE
            } catch (_: Exception) { normalBgImage.visibility = View.GONE }
        } else {
            normalBgImage.visibility = View.GONE
        }
    }

    /**
     * 从壁纸提取主色调，给抽屉上半透明底色
     */
    private fun updateDrawerTint(imageUri: Uri) {
        try {
            val input = contentResolver.openInputStream(imageUri) ?: return
            val options = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = 16  // 缩小16倍，只要大致颜色
            }
            val bitmap = android.graphics.BitmapFactory.decodeStream(input, null, options)
            input.close()
            if (bitmap == null) return

            // 取中间区域像素的平均色
            var r = 0L; var g = 0L; var b = 0L; var count = 0L
            val w = bitmap.width; val h = bitmap.height
            val startX = w / 4; val endX = w * 3 / 4
            val startY = h / 4; val endY = h * 3 / 4

            for (x in startX until endX step 2) {
                for (y in startY until endY step 2) {
                    val pixel = bitmap.getPixel(x, y)
                    r += (pixel shr 16) and 0xFF
                    g += (pixel shr 8) and 0xFF
                    b += pixel and 0xFF
                    count++
                }
            }
            bitmap.recycle()

            if (count == 0L) return
            val avgR = (r / count).toInt()
            val avgG = (g / count).toInt()
            val avgB = (b / count).toInt()

            // 混合：适度变暗保持质感，90%不透明度
            val darkFactor = 0.7
            val tintColor = android.graphics.Color.argb(230,
                (avgR * darkFactor).toInt(),
                (avgG * darkFactor).toInt(),
                (avgB * darkFactor).toInt()
            )
            drawerPanel.setBackgroundColor(tintColor)

            // 保存颜色给下次启动用
            prefs.edit().putInt("drawer_tint_color", tintColor).apply()
        } catch (e: Exception) {
            // 提取失败就保持默认颜色
        }
    }

    private fun switchToNormalMode() {
        isLiveMode = false
        prefs.edit().putBoolean("live_mode", false).apply()

        if (isDrawerOpen) toggleDrawer()

        normalDesktop.visibility = View.VISIBLE
        liveDesktop.visibility = View.GONE
        liveBgImage.visibility = View.GONE
        drawerBtn.visibility = View.GONE

        // 显示普通桌面壁纸（昼/夜自动切换）
        loadDesktopWallpaper()
    }

    private fun toggleDrawer() {
        if (isDrawerOpen) {
            drawerPanel.animate()
                .translationX(dpToPx(220).toFloat())
                .setDuration(250)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    drawerPanel.visibility = View.GONE
                    drawerOverlay.visibility = View.GONE
                }
                .start()
            isDrawerOpen = false
        } else {
            drawerPanel.visibility = View.VISIBLE
            drawerOverlay.visibility = View.VISIBLE
            drawerPanel.translationX = dpToPx(220).toFloat()
            drawerPanel.animate()
                .translationX(0f)
                .setDuration(250)
                .setInterpolator(DecelerateInterpolator())
                .start()
            isDrawerOpen = true
        }
    }

    private fun pickImage() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, PICK_IMAGE_REQUEST)
    }

    private fun pickNormalWallpaper() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, PICK_NORMAL_BG_REQUEST)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return

        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: Exception) { }

        when (requestCode) {
            PICK_IMAGE_REQUEST -> {
                // 立绘背景（昼或夜）
                val slot = pendingWallpaperSlot.ifEmpty { "live2d_day" }
                LockScreenStorage(this).setWallpaper(slot, uri.toString())
                // 同时更新旧的 key（兼容）
                prefs.edit().putString("live_bg_uri", uri.toString()).apply()
                liveBgImage.setImageURI(uri)
                liveBgImage.visibility = View.VISIBLE
                if (!isLiveMode) switchToLiveMode(true)
                updateDrawerTint(uri)
                val label = if (slot.endsWith("night")) "夜间" else "日间"
                Toast.makeText(this, "立绘${label}背景已更换 ♡", Toast.LENGTH_SHORT).show()
            }
            PICK_NORMAL_BG_REQUEST -> {
                // 普通桌面壁纸（昼或夜）
                val slot = pendingWallpaperSlot.ifEmpty { "desktop_day" }
                LockScreenStorage(this).setWallpaper(slot, uri.toString())
                // 同时更新旧的 key（兼容）
                prefs.edit().putString("normal_bg_uri", uri.toString()).apply()
                normalBgImage.setImageURI(uri)
                normalBgImage.visibility = View.VISIBLE
                val label = if (slot.endsWith("night")) "夜间" else "日间"
                Toast.makeText(this, "桌面${label}壁纸已更换 ♡", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateTimeAndDate() {
        val now = Date()
        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
        val dateStr = SimpleDateFormat("yyyy年M月d日 EEEE", Locale.CHINESE).format(now)
        val liveDateStr = SimpleDateFormat("yyyy/M/d EEEE", Locale.CHINESE).format(now)
        desktopTime.text = timeStr
        desktopDate.text = dateStr
        liveTime.text = timeStr
        liveDate.text = liveDateStr
    }

    private fun fillIcons(
        grid: GridLayout,
        icons: List<AppIcon>,
        columns: Int,
        vMargin: Int,
        isDesktop: Boolean  // 是否是桌面的主图标区（需要支持编辑模式）
    ) {
        grid.removeAllViews()
        for ((index, icon) in icons.withIndex()) {
            val itemLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    height = GridLayout.LayoutParams.WRAP_CONTENT
                    columnSpec = GridLayout.spec(index % columns, 1, 1f)
                    rowSpec = GridLayout.spec(index / columns)
                    setMargins(4, vMargin, 4, vMargin)
                }
            }

            val iconSize = dpToPx(44)
            val iconView = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
                scaleType = ImageView.ScaleType.CENTER
                background = createIconBackground()
                setImageDrawable(LineIconDrawable(this@DesktopActivity, icon.type, dpToPx(20)))
                // 抽屉里图标固定白色，不跟主题走
                if (!isDesktop) {
                    setColorFilter(0xFFFFFFFF.toInt(), android.graphics.PorterDuff.Mode.SRC_IN)
                }
            }

            val labelView = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dpToPx(3) }
                text = icon.label
                textSize = 9f
                setTextColor(if (isDesktop)
                    ContextCompat.getColor(this@DesktopActivity, R.color.haven_desktop_icon_label)
                else 0xE6FFFFFF.toInt())
                gravity = Gravity.CENTER
                setShadowLayer(3f, 0f, 1f, 0x66000000)
            }

            itemLayout.addView(iconView)
            itemLayout.addView(labelView)

            if (isDesktop && isEditMode) {
                // ===== 编辑模式：点击交换，显示晃动 =====

                // 选中状态高亮
                if (index == selectedIndex) {
                    iconView.background = createSelectedBackground()
                }

                // 晃动动画
                val wobble = ObjectAnimator.ofFloat(
                    itemLayout, "rotation",
                    -2f, 2f
                ).apply {
                    duration = 150
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.REVERSE
                    // 每个图标错开一点启动时间，看起来更自然
                    startDelay = (index * 30).toLong()
                }
                wobble.start()
                wobbleAnimators.add(wobble)

                // 点击：选中或交换
                itemLayout.setOnClickListener {
                    if (selectedIndex == -1) {
                        // 没有选中的，选中当前这个
                        selectedIndex = index
                        refreshDesktopIcons()
                    } else if (selectedIndex == index) {
                        // 点了同一个，取消选中
                        selectedIndex = -1
                        refreshDesktopIcons()
                    } else {
                        // 有选中的，交换！
                        val temp = currentIcons[selectedIndex]
                        currentIcons[selectedIndex] = currentIcons[index]
                        currentIcons[index] = temp
                        selectedIndex = -1
                        saveIconOrder()
                        refreshDesktopIcons()
                        // 同步更新抽屉里的顺序
                        fillIcons(drawerGrid, currentIcons, 3, 16, false)
                        Toast.makeText(this, "交换成功！", Toast.LENGTH_SHORT).show()
                    }
                }

                // 长按：退出编辑模式
                itemLayout.setOnLongClickListener {
                    exitEditMode()
                    true
                }
            } else {
                // ===== 普通模式：正常点击 =====
                itemLayout.setOnClickListener {
                    if (isDrawerOpen) toggleDrawer()
                    onIconClick(icon)
                }

                // 桌面图标长按进入编辑模式（只在桌面主区域）
                if (isDesktop) {
                    itemLayout.setOnLongClickListener {
                        enterEditMode()
                        true
                    }
                }
            }

            grid.addView(itemLayout)
        }
    }

    private fun onIconClick(icon: AppIcon) {
        when (icon.action) {
            "chat" -> startActivity(Intent(this, ChatActivity::class.java))
            "nest" -> startActivity(Intent(this, NestActivity::class.java))
            "archive" -> startActivity(Intent(this, ArchiveActivity::class.java))
            "world" -> Toast.makeText(this, "世界 - 预设·正则·世界书", Toast.LENGTH_SHORT).show()
            "workshop" -> Toast.makeText(this, "工坊 - AI项目·代码·文件", Toast.LENGTH_SHORT).show()
            "settings" -> startActivity(Intent(this, SettingsActivity::class.java))
            "beautify" -> Toast.makeText(this, "美化 - 主题·图标·名字", Toast.LENGTH_SHORT).show()
            "clock" -> startActivity(Intent(this, ClockActivity::class.java))
            "weather" -> {
                try {
                    startActivity(Intent(this, WeatherActivity::class.java))
                } catch (e: Exception) {
                    Toast.makeText(this, "窗外启动失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            else -> Toast.makeText(this, "${icon.label} - 开发中", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupPageDots(totalPages: Int, currentPage: Int) {
        pageDots.removeAllViews()
        for (i in 0 until totalPages) {
            val dot = View(this)
            val isActive = (i == currentPage)
            val size = if (isActive) dpToPx(7) else dpToPx(5)
            dot.layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginStart = dpToPx(3)
                marginEnd = dpToPx(3)
                gravity = Gravity.CENTER_VERTICAL
            }
            val shape = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(if (isActive) ContextCompat.getColor(this@DesktopActivity, R.color.haven_desktop_dot_active) else ContextCompat.getColor(this@DesktopActivity, R.color.haven_desktop_dot_inactive))
            }
            dot.background = shape
            pageDots.addView(dot)
        }
    }

    private fun createIconBackground(): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(13).toFloat()
            setColor(ContextCompat.getColor(this@DesktopActivity, R.color.haven_desktop_icon_bg))
            setStroke(dpToPx(1), ContextCompat.getColor(this@DesktopActivity, R.color.haven_desktop_icon_border))
        }
    }

    private fun createSelectedBackground(): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(13).toFloat()
            setColor(ContextCompat.getColor(this@DesktopActivity, R.color.haven_desktop_icon_selected_bg))
            setStroke(dpToPx(2), ContextCompat.getColor(this@DesktopActivity, R.color.haven_desktop_icon_selected_border))
        }
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics
        ).toInt()
    }

    /**
     * 检查电池优化白名单
     *
     * 如果归栖不在白名单里，弹个对话框引导用户去关掉电池优化
     * 只提醒一次，用户选择"不再提醒"后不会再弹
     */
    private fun checkBatteryOptimization() {
        // 用户选过"不再提醒"就跳过
        if (prefs.getBoolean("battery_hint_dismissed", false)) return

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            AlertDialog.Builder(this)
                .setTitle("保持归栖在后台运行")
                .setMessage("为了让 AI 能主动找你、不被系统杀掉，需要关闭归栖的电池优化。\n\n点击「去设置」后，选择「不优化」或「无限制」。")
                .setPositiveButton("去设置") { _, _ ->
                    try {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        startActivity(intent)
                    } catch (e: Exception) {
                        try {
                            startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
                        } catch (e2: Exception) {
                            Toast.makeText(this, "请手动在设置中关闭归栖的电池优化", Toast.LENGTH_LONG).show()
                        }
                    }
                }
                .setNegativeButton("下次再说", null)
                .setNeutralButton("不再提醒") { _, _ ->
                    prefs.edit().putBoolean("battery_hint_dismissed", true).apply()
                }
                .show()
        }
    }

    /**
     * 检查无障碍服务是否已开启
     *
     * 如果没开启，弹个对话框引导用户去开。
     * 这是保活第三层——Vivo 等厂商即使开了电池白名单也可能杀后台，
     * 无障碍服务不容易被杀，能守护 HavenService。
     * 只在电池白名单搞定之后才提醒，避免一次弹两个弹窗。
     */
    private fun checkNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }
    }

    private fun checkAccessibilityService() {
        // 电池白名单还没搞定的话先不提醒这个
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) return

        // 用户选过"不再提醒"就跳过
        if (prefs.getBoolean("a11y_hint_dismissed", false)) return

        // 已经开了就不用提醒
        if (HavenAccessibilityService.isEnabled(this)) return

        AlertDialog.Builder(this)
            .setTitle("让 AI 更稳定地陪着你")
            .setMessage("Vivo 有时候会强制关闭后台应用。开启归栖的无障碍服务后，AI 的闹钟和主动消息不容易被系统杀掉。\n\n这个服务不会读取你的屏幕内容，只是用来保持归栖运行。\n\n点击「去开启」→ 找到「归栖」→ 打开开关。")
            .setPositiveButton("去开启") { _, _ ->
                HavenAccessibilityService.openSettings(this)
            }
            .setNegativeButton("下次再说", null)
            .setNeutralButton("不再提醒") { _, _ ->
                prefs.edit().putBoolean("a11y_hint_dismissed", true).apply()
            }
            .show()
    }
}