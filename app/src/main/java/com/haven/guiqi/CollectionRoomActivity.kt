package com.haven.guiqi

import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 馆藏里暂未施工完成的房间。
 * 当前只提供真实可落地的空页面骨架，不提前塞上传、来源或管理按钮。
 */
class CollectionRoomActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ROOM = "collection_room"
    }

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

        setContentView(R.layout.activity_collection_room)

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

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }

        val room = when (intent.getStringExtra(EXTRA_ROOM)) {
            "comic" -> RoomCopy(
                title = "漫廊",
                glyph = "漫",
                emptyTitle = "漫廊还是空的",
                emptyHint = "以后从这里导入漫画，也从这里接漫画来源。"
            )
            "screening" -> RoomCopy(
                title = "放映室",
                glyph = "映",
                emptyTitle = "放映室还是空的",
                emptyHint = "以后从这里导入影片，也从这里接视频来源。"
            )
            else -> RoomCopy(
                title = "画匣",
                glyph = "画",
                emptyTitle = "画匣还是空的",
                emptyHint = "上传进来的图片，会成为全屋都能取用的藏品。"
            )
        }

        findViewById<TextView>(R.id.roomTitle).apply {
            text = room.title
            setTextColor(c.textPrimary)
        }
        findViewById<TextView>(R.id.roomGlyph).apply {
            text = room.glyph
            setTextColor(c.accent)
        }
        findViewById<TextView>(R.id.emptyTitle).apply {
            text = room.emptyTitle
            setTextColor(c.textPrimary)
        }
        findViewById<TextView>(R.id.emptyHint).apply {
            text = room.emptyHint
            setTextColor(c.textSecondary)
        }
    }

    private data class RoomCopy(
        val title: String,
        val glyph: String,
        val emptyTitle: String,
        val emptyHint: String
    )
}
