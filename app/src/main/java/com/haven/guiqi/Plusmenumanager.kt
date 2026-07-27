package com.haven.guiqi

import android.app.Activity
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 加号菜单管理器。
 * 负责功能面板的展开/收起，以及拍照、图片、表情包、天气入口。
 */
class PlusMenuManager(
    private val activity: Activity,
    private val stickerPanelManager: StickerPanelManager,
    private val onTakePhoto: () -> Unit,
    private val onPickImage: () -> Unit,
    private val onInsertWeather: () -> Unit
) {
    private val plusPanel: LinearLayout by lazy { activity.findViewById(R.id.plusPanel) }
    private val easing = PathInterpolator(0.22f, 1f, 0.36f, 1f)
    private var listenersBound = false

    fun isVisible(): Boolean = plusPanel.visibility == View.VISIBLE

    fun toggle() {
        if (isVisible()) hide() else show()
    }

    fun show() {
        stickerPanelManager.hide()
        bindListenersIfNeeded()

        activity.findViewById<TextView>(R.id.btnPlus).animate().rotation(45f).setDuration(180L).start()
        plusPanel.animate().cancel()
        plusPanel.visibility = View.VISIBLE
        plusPanel.alpha = 0f
        plusPanel.translationY = dp(14).toFloat()
        plusPanel.scaleX = 0.97f
        plusPanel.scaleY = 0.94f
        plusPanel.animate()
            .alpha(1f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(230L)
            .setInterpolator(easing)
            .start()
    }

    fun hide(animated: Boolean = true) {
        activity.findViewById<TextView>(R.id.btnPlus).animate().rotation(0f).setDuration(160L).start()
        if (!isVisible()) return
        plusPanel.animate().cancel()
        if (!animated) {
            plusPanel.visibility = View.GONE
            plusPanel.alpha = 1f
            plusPanel.translationY = 0f
            plusPanel.scaleX = 1f
            plusPanel.scaleY = 1f
            return
        }
        plusPanel.animate()
            .alpha(0f)
            .translationY(dp(10).toFloat())
            .scaleX(0.98f)
            .scaleY(0.95f)
            .setDuration(160L)
            .setInterpolator(easing)
            .withEndAction {
                plusPanel.visibility = View.GONE
                plusPanel.alpha = 1f
                plusPanel.translationY = 0f
                plusPanel.scaleX = 1f
                plusPanel.scaleY = 1f
            }
            .start()
    }

    private fun bindListenersIfNeeded() {
        if (listenersBound) return
        listenersBound = true

        activity.findViewById<LinearLayout>(R.id.plusBtnCamera).setOnClickListener {
            hide()
            onTakePhoto()
        }
        activity.findViewById<LinearLayout>(R.id.plusBtnImage).setOnClickListener {
            hide()
            onPickImage()
        }
        activity.findViewById<LinearLayout>(R.id.plusBtnSticker).setOnClickListener {
            hide()
            stickerPanelManager.toggle()
        }
        activity.findViewById<LinearLayout>(R.id.plusBtnWeather).setOnClickListener {
            hide()
            onInsertWeather()
        }
    }

    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
