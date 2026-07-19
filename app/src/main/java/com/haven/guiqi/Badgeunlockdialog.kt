package com.haven.guiqi

import android.app.Activity
import android.app.AlertDialog

/**
 * 徽章解锁确认弹窗 —— 从 ChatConversationActivity 拆出（军规二：拆文件）
 * AI 通过指令申请解锁徽章时，弹窗征求用户同意。
 * 同意/拒绝的结果通过 onSystemTip 回调写回聊天（系统提示）。
 */
class BadgeUnlockDialog(
    private val activity: Activity,
    private val friendId: String,
    private val onSystemTip: (String) -> Unit
) {
    fun show(badgeName: String) {
        val bs = BadgeStorage(activity)
        val badge = bs.loadAll(friendId).find { it.name == badgeName && !it.isUnlocked } ?: return
        AlertDialog.Builder(activity)
            .setTitle("🏅 TA 想解锁一枚徽章")
            .setMessage("「$badgeName」\n\n同意解锁吗？")
            .setPositiveButton("同意") { _, _ ->
                bs.unlock(friendId, badge.id)
                onSystemTip("🏅 徽章「$badgeName」已解锁！")
            }
            .setNegativeButton("拒绝") { _, _ ->
                bs.rejectUnlock(friendId, badge.id)
                onSystemTip("🏅 拒绝了解锁「$badgeName」")
            }
            .setCancelable(false)
            .show()
    }
}