package com.haven.guiqi

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * NestActivity — 小窝
 *
 * 推开门就是家。
 * 竖屏可以左右滑动看房间全貌，横屏刚好一屏。
 * 窗外的天跟手机时间同步。
 * 点家具有反应。
 */
class NestActivity : AppCompatActivity() {

    private lateinit var roomView: NestRoomView
    private lateinit var scrollView: HorizontalScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 全屏沉浸
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        )
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        // 根布局
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        // 房间视图
        roomView = NestRoomView(this)

        // 横向滚动容器（竖屏时房间比屏幕宽）
        scrollView = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundColor(Color.BLACK)
            addView(roomView)
        }

        root.addView(scrollView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // 旋转按钮（右上角小图标）
        val rotateBtn = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_always_landscape_portrait)
            setColorFilter(Color.argb(180, 255, 255, 255))
            setPadding(24, 24, 24, 24)
            setOnClickListener { toggleOrientation() }
        }
        val btnParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.END
            topMargin = 48
            marginEnd = 16
        }
        root.addView(rotateBtn, btnParams)

        // 返回按钮（左上角）
        val backBtn = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_revert)
            setColorFilter(Color.argb(180, 255, 255, 255))
            setPadding(24, 24, 24, 24)
            setOnClickListener { finish() }
        }
        val backParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            topMargin = 48
            marginStart = 16
        }
        root.addView(backBtn, backParams)

        setContentView(root)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 重新布局房间视图
        roomView.requestLayout()
    }

    private fun toggleOrientation() {
        requestedOrientation = if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
        }
    }
}