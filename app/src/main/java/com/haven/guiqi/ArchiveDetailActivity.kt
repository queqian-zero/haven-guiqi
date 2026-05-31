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
 * ArchiveDetailActivity - 打开抽屉后的页面
 *
 * 显示：
 * - 头像 + 名字 + 状态
 * - 印象便签（特殊卡片）
 * - 文件夹列表（记忆、日记、梦境、聊天总结、废纸篓）
 */
class ArchiveDetailActivity : AppCompatActivity() {

    private lateinit var detailContainer: LinearLayout
    private var friendId = ""
    private var friendName = ""
    private var friendIcon = ""

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

        setContentView(R.layout.activity_archive_detail)

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

        detailContainer = findViewById(R.id.detailContainer)
        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }

        friendId = intent.getStringExtra("friend_id") ?: ""
        friendName = intent.getStringExtra("friend_name") ?: "好友"
        friendIcon = intent.getStringExtra("friend_icon") ?: "👤"

        findViewById<TextView>(R.id.tvTitle).text = "${friendName}的档案"
    }

    override fun onResume() {
        super.onResume()
        buildDetail()
    }

    private fun buildDetail() {
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }
        detailContainer.removeAllViews()

        // ===== 头像 + 名字 + 状态 =====
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }
        }

        val avatarView = TextView(this).apply {
            text = friendIcon
            textSize = 44f
            gravity = Gravity.CENTER
        }
        header.addView(avatarView)

        val nameView = TextView(this).apply {
            text = friendName
            textSize = 17f
            setTextColor(c.textPrimary)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }
        header.addView(nameView)

        // 状态
        val statusPrefs = getSharedPreferences("haven_status", MODE_PRIVATE)
        val status = statusPrefs.getString("status_$friendId", "") ?: ""
        if (status.isNotEmpty()) {
            val statusView = TextView(this).apply {
                text = status
                textSize = 11f
                setTextColor(c.accent)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(3) }
            }
            header.addView(statusView)
        }
        detailContainer.addView(header)

        // ===== 印象便签 =====
        val impressionStorage = ImpressionStorage(this)
        val impression = impressionStorage.getImpression(friendId)

        val memoLabel = TextView(this).apply {
            text = "💭 对你的印象"
            textSize = 11f
            setTextColor(c.textSecondary)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
        }
        detailContainer.addView(memoLabel)

        val memoBg = GradientDrawable().apply {
            setColor(c.accentBg)
            cornerRadius = dp(8).toFloat()
            setStroke(1, c.stampColor)
        }
        val memoCard = TextView(this).apply {
            text = if (impression.isNotEmpty()) impression else "还没有写过对你的印象"
            textSize = 12f
            setTextColor(if (impression.isNotEmpty()) c.textPrimary else c.textHint)
            setLineSpacing(0f, 1.45f)
            background = memoBg
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(20) }
        }
        detailContainer.addView(memoCard)

        // ===== 文件夹列表 =====
        val memoryCount = MemoryStorage(this).count(friendId)
        val diaryCount = DiaryStorage(this).count(friendId)
        val memoryTrash = MemoryStorage(this).loadTrash(friendId).size
        val diaryTrash = DiaryStorage(this).loadTrash(friendId).size
        val trashCount = memoryTrash + diaryTrash

        // 记忆文件夹
        addFolder(
            tabText = "核心记忆",
            tabColor = c.folderMemory,
            preview = if (memoryCount > 0) "共 ${memoryCount} 条记忆" else "还没有记忆",
            isEmpty = memoryCount == 0,
            dp = dp
        ) {
            val intent = Intent(this, ArchiveFolderActivity::class.java)
            intent.putExtra("friend_id", friendId)
            intent.putExtra("friend_name", friendName)
            intent.putExtra("folder_type", "memory")
            startActivity(intent)
        }

        // 日记文件夹
        addFolder(
            tabText = "日记",
            tabColor = c.folderDiary,
            preview = if (diaryCount > 0) "共 ${diaryCount} 篇日记" else "还没有日记",
            isEmpty = diaryCount == 0,
            dp = dp
        ) {
            val intent = Intent(this, ArchiveFolderActivity::class.java)
            intent.putExtra("friend_id", friendId)
            intent.putExtra("friend_name", friendName)
            intent.putExtra("folder_type", "diary")
            startActivity(intent)
        }

        // 梦境文件夹
        val dreamCount = DreamStorage(this).count(friendId)
        addFolder(
            tabText = "梦境",
            tabColor = c.folderDream,
            preview = if (dreamCount > 0) "共 ${dreamCount} 个梦" else "还没有做过梦",
            isEmpty = dreamCount == 0,
            dp = dp
        ) {
            val intent = Intent(this, ArchiveFolderActivity::class.java)
            intent.putExtra("friend_id", friendId)
            intent.putExtra("friend_name", friendName)
            intent.putExtra("folder_type", "dream")
            startActivity(intent)
        }

        // 聊天总结文件夹
        val summaryCount = ChatSummaryStorage(this).count(friendId)
        addFolder(
            tabText = "聊天总结",
            tabColor = c.folderSummary,
            preview = if (summaryCount > 0) "共 ${summaryCount} 条总结" else "还没有总结",
            isEmpty = summaryCount == 0,
            dp = dp
        ) {
            val intent = Intent(this, ArchiveFolderActivity::class.java)
            intent.putExtra("friend_id", friendId)
            intent.putExtra("friend_name", friendName)
            intent.putExtra("folder_type", "summary")
            startActivity(intent)
        }

        // 废纸篓
        addFolder(
            tabText = "废纸篓",
            tabColor = c.folderTrash,
            preview = if (trashCount > 0) "${trashCount} 条已删除" else "空的",
            isEmpty = trashCount == 0,
            dp = dp
        ) {
            val intent = Intent(this, ArchiveFolderActivity::class.java)
            intent.putExtra("friend_id", friendId)
            intent.putExtra("friend_name", friendName)
            intent.putExtra("folder_type", "trash")
            startActivity(intent)
        }
    }

    /**
     * 添加一个文件夹
     */
    private fun addFolder(
        tabText: String,
        tabColor: Int,
        preview: String,
        isEmpty: Boolean,
        dp: (Int) -> Int,
        onClick: () -> Unit
    ) {
        // 整个文件夹容器
        val folderWrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }

        // 标签页（突出的彩色小条）
        val tabBg = GradientDrawable().apply {
            setColor((tabColor and 0x00FFFFFF) or 0x26000000) // 15% 透明度
            cornerRadii = floatArrayOf(
                dp(6).toFloat(), dp(6).toFloat(),
                dp(6).toFloat(), dp(6).toFloat(),
                0f, 0f, 0f, 0f
            )
        }
        val tab = TextView(this).apply {
            text = tabText
            textSize = 11f
            setTextColor(tabColor)
            background = tabBg
            setPadding(dp(14), dp(4), dp(14), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        folderWrapper.addView(tab)

        // 文件夹主体
        val bodyBorderColor = (tabColor and 0x00FFFFFF) or 0x4D000000
        val bodyBg = GradientDrawable().apply {
            setColor(c.border)
            cornerRadii = floatArrayOf(
                0f, 0f,
                dp(8).toFloat(), dp(8).toFloat(),
                dp(8).toFloat(), dp(8).toFloat(),
                dp(8).toFloat(), dp(8).toFloat()
            )
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = bodyBg
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }

        // 左边竖条（文件夹边缘颜色）
        val leftBar = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(3), dp(20)).apply {
                marginEnd = dp(10)
            }
            val barBg = GradientDrawable().apply {
                setColor(bodyBorderColor)
                cornerRadius = dp(2).toFloat()
            }
            background = barBg
        }
        body.addView(leftBar)

        // 预览文字
        val previewView = TextView(this).apply {
            text = preview
            textSize = 12f
            setTextColor(if (isEmpty) c.textHint else c.textSecondary)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        body.addView(previewView)

        // 箭头（非空才显示）
        if (!isEmpty) {
            val arrow = TextView(this).apply {
                text = "›"
                textSize = 18f
                setTextColor(c.textHint)
            }
            body.addView(arrow)
        }

        if (!isEmpty) {
            body.setOnClickListener { onClick() }
        }

        folderWrapper.addView(body)
        detailContainer.addView(folderWrapper)
    }
}