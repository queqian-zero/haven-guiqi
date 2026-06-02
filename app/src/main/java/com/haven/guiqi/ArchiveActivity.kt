package com.haven.guiqi

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * ArchiveActivity - 馆藏主页
 *
 * 两个标签页：
 * - 书城：暂时占位
 * - 档案馆：档案柜风格，每个 AI 是一个抽屉
 *
 * 档案柜的视觉设计：
 * - 外壳是深色金属质感的圆角矩形
 * - 顶部有一小条深色的"柜顶"
 * - 每个好友是一个"抽屉"，有拉手和标签
 * - 按分组分成不同的柜子
 */
class ArchiveActivity : AppCompatActivity() {

    private lateinit var tabLibrary: TextView
    private lateinit var tabArchive: TextView
    private lateinit var libraryPage: LinearLayout
    private lateinit var archivePage: View
    private lateinit var cabinetContainer: LinearLayout

    /** 当前主题色 */
    private val c get() = ThemeHelper.getColors(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        setContentView(R.layout.activity_archive)

        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        insetsController.hide(WindowInsetsCompat.Type.navigationBars())
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController.isAppearanceLightStatusBars = !ThemeHelper.isDark(this)

        val contentView = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(contentView) { view, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.setPadding(0, top, 0, 0)
            insets
        }

        tabLibrary = findViewById(R.id.tabLibrary)
        tabArchive = findViewById(R.id.tabArchive)
        libraryPage = findViewById(R.id.libraryPage)
        archivePage = findViewById(R.id.archivePage)
        cabinetContainer = findViewById(R.id.cabinetContainer)

        findViewById<TextView>(R.id.btnBack).setOnClickListener {
            startActivity(Intent(this, DesktopActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            })
            finish()
        }

        tabLibrary.setOnClickListener { switchTab(false) }
        tabArchive.setOnClickListener { switchTab(true) }

        switchTab(true)
    }

    override fun onResume() {
        super.onResume()
        loadCabinets()
    }

    private fun switchTab(isArchive: Boolean) {
        if (isArchive) {
            tabArchive.setTextColor(c.textPrimary)
            tabLibrary.setTextColor(c.textHint)
            archivePage.visibility = View.VISIBLE
            libraryPage.visibility = View.GONE
            loadCabinets()
        } else {
            tabLibrary.setTextColor(c.textPrimary)
            tabArchive.setTextColor(c.textHint)
            libraryPage.visibility = View.VISIBLE
            archivePage.visibility = View.GONE
        }
    }

    /**
     * 加载档案柜
     * 按好友分组来分柜子，每个组一个柜子
     */
    private fun loadCabinets() {
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }
        cabinetContainer.removeAllViews()

        val friends = FriendStorage(this).loadFriends()

        if (friends.isEmpty()) {
            val tip = TextView(this).apply {
                text = "还没有好友的档案\n先去聊天 App 里添加好友吧"
                textSize = 13f
                setTextColor(c.textHint)
                gravity = Gravity.CENTER
                setLineSpacing(0f, 1.4f)
                setPadding(dp(20), dp(60), dp(20), dp(60))
            }
            cabinetContainer.addView(tip)
            return
        }

        // 按分组分柜子
        val grouped = friends.groupBy { it.group.ifEmpty { "好友" } }

        for ((group, groupFriends) in grouped) {
            val cabinet = buildCabinet(group, groupFriends, dp)
            cabinetContainer.addView(cabinet)
        }
    }

    /**
     * 构建一个档案柜（一个分组）
     */
    private fun buildCabinet(group: String, friends: List<Friend>, dp: (Int) -> Int): LinearLayout {
        // 柜子外壳
        val cabinetBg = GradientDrawable().apply {
            setColor(c.cabinetBg)
            cornerRadius = dp(10).toFloat()
            setStroke(dp(1), c.cabinetBorder)
        }

        val cabinet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cabinetBg
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }

        // 柜顶（深色窄条，模拟金属柜子顶部）
        val topBar = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(8)
            )
            val topBg = GradientDrawable().apply {
                setColor(c.cabinetTop)
                cornerRadii = floatArrayOf(
                    dp(10).toFloat(), dp(10).toFloat(), // 左上
                    dp(10).toFloat(), dp(10).toFloat(), // 右上
                    0f, 0f, 0f, 0f  // 下方不圆角
                )
            }
            background = topBg
        }
        cabinet.addView(topBar)

        // 分组标签
        val groupLabel = TextView(this).apply {
            text = group
            textSize = 11f
            setTextColor(c.textSecondary)
            setPadding(dp(14), dp(8), dp(14), dp(6))
            letterSpacing = 0.05f
        }
        cabinet.addView(groupLabel)

        // 分隔线
        val sep = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
            ).apply { marginStart = dp(12); marginEnd = dp(12) }
            setBackgroundColor(c.divider)
        }
        cabinet.addView(sep)

        // 每个好友一个抽屉
        for ((index, friend) in friends.withIndex()) {
            val drawer = buildDrawer(friend, dp)
            cabinet.addView(drawer)

            // 抽屉之间的分隔线（最后一个不加）
            if (index < friends.size - 1) {
                val drawerSep = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
                    ).apply { marginStart = dp(52); marginEnd = dp(12) }
                    setBackgroundColor(c.border)
                }
                cabinet.addView(drawerSep)
            }
        }

        return cabinet
    }

    /**
     * 构建一个抽屉（一个好友）
     */
    private fun buildDrawer(friend: Friend, dp: (Int) -> Int): LinearLayout {
        val memoryCount = MemoryStorage(this).count(friend.id)
        val diaryCount = DiaryStorage(this).count(friend.id)
        val hasImpression = ImpressionStorage(this).getImpression(friend.id).isNotEmpty()

        // 统计文字
        val stats = mutableListOf<String>()
        if (memoryCount > 0) stats.add("${memoryCount}条记忆")
        if (diaryCount > 0) stats.add("${diaryCount}篇日记")
        if (hasImpression) stats.add("有印象")
        val statsText = if (stats.isEmpty()) "还没有档案" else stats.joinToString(" · ")

        val drawer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(13), dp(14), dp(13))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

            // 点击进入详情页
            setOnClickListener {
                val intent = Intent(this@ArchiveActivity, ArchiveDetailActivity::class.java)
                intent.putExtra("friend_id", friend.id)
                intent.putExtra("friend_name", friend.name)
                intent.putExtra("friend_icon", friend.icon)
                startActivity(intent)
            }
        }

        // 拉手（小矩形，模拟抽屉把手）
        val handleBg = GradientDrawable().apply {
            setColor(c.drawerHandle)
            cornerRadius = dp(3).toFloat()
            setStroke(1, c.borderMedium)
        }
        val handle = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(10)).apply {
                marginEnd = dp(12)
            }
            background = handleBg
        }

        // 名字 + 统计（竖排）
        val labelColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val nameView = TextView(this).apply {
            text = friend.name
            textSize = 14f
            setTextColor(c.textPrimary)
        }
        val statView = TextView(this).apply {
            text = statsText
            textSize = 10f
            setTextColor(c.accent)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2) }
        }
        labelColumn.addView(nameView)
        labelColumn.addView(statView)

        // 头像
        val avatar = TextView(this).apply {
            text = friend.icon
            textSize = 22f
            gravity = Gravity.CENTER
        }

        drawer.addView(handle)
        drawer.addView(labelColumn)
        drawer.addView(avatar)

        return drawer
    }
}