package com.haven.guiqi

import android.app.Activity
import android.view.View
import android.widget.LinearLayout

/**
 * 加号菜单管理器 —— 从 ChatConversationActivity 拆出（军规二：拆文件）
 * 负责加号面板的展开/收起，以及三个入口（图片/表情包/天气卡片）的点击分发。
 * 具体动作通过回调交还给 Activity，本类不持有业务逻辑。
 */
class PlusMenuManager(
    private val activity: Activity,
    private val stickerPanelManager: StickerPanelManager,
    private val onPickImage: () -> Unit,
    private val onInsertWeather: () -> Unit
) {
    private var listenersBound = false

    fun toggle() {
        val plusPanel = activity.findViewById<LinearLayout>(R.id.plusPanel)
        if (plusPanel.visibility == View.VISIBLE) {
            plusPanel.visibility = View.GONE
            return
        }
        stickerPanelManager.hide()
        plusPanel.visibility = View.VISIBLE

        if (listenersBound) return
        listenersBound = true
        activity.findViewById<LinearLayout>(R.id.plusBtnImage).setOnClickListener {
            plusPanel.visibility = View.GONE
            onPickImage()
        }
        activity.findViewById<LinearLayout>(R.id.plusBtnSticker).setOnClickListener {
            plusPanel.visibility = View.GONE
            stickerPanelManager.toggle()
        }
        activity.findViewById<LinearLayout>(R.id.plusBtnWeather).setOnClickListener {
            plusPanel.visibility = View.GONE
            onInsertWeather()
        }
    }
}