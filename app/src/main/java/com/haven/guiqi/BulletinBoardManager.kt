package com.haven.guiqi

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * BulletinBoardManager — 桌面留言板 UI
 *
 * 职责：
 * - 留言条（桌面上那个像搜索条的东西，显示最新一条留言）
 * - 点击留言条 → 弹窗展示今日留言
 * - 弹窗里有"历史"和"收藏"两个入口
 * - 历史：按日期列出近30天的留言
 * - 收藏：永久保存的留言，可取消收藏
 *
 * DesktopActivity 只管调 init() 和 refresh()
 */
class BulletinBoardManager(
    private val context: Context,
    private val bulletinStrip: View,
    private val bulletinText: TextView
) {
    private val storage = BulletinStorage(context)

    fun init() {
        bulletinStrip.setOnClickListener { showTodayDialog() }
        refresh()
    }

    /** 刷新留言条上显示的文字 */
    fun refresh() {
        val latest = storage.getLatestToday()
        if (latest != null) {
            bulletinText.text = "💬 ${latest.authorName} · ${latest.content}"
        } else {
            bulletinText.text = "💬 暂无留言"
        }
    }

    // ===== 今日留言弹窗 =====

    private fun showTodayDialog() {
        val messages = storage.getTodayMessages()
        val dp = { v: Int -> (v * context.resources.displayMetrics.density).toInt() }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }

        // 标题行：今日留言（左） + 历史 收藏（右）
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        headerRow.addView(TextView(context).apply {
            text = "今日留言"
            textSize = 16f; setTextColor(0xFF333333.toInt())
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val favCount = storage.getFavoriteCount()
        val favLabel = if (favCount > 0) "收藏($favCount)" else "收藏"
        headerRow.addView(makeTextButton("◁ 历史") { showHistoryDialog() }.apply { textSize = 11f })
        headerRow.addView(makeTextButton("☆ $favLabel") { showFavoritesDialog() }.apply { textSize = 11f })
        root.addView(headerRow)

        if (messages.isEmpty()) {
            root.addView(makeEmptyHint("今天还没有留言"))
        } else {
            val scroll = ScrollView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(300)
                )
            }
            val list = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }
            for (msg in messages) {
                list.addView(makeBulletinItem(msg, showDate = false))
            }
            scroll.addView(list)
            root.addView(scroll)
        }

        AlertDialog.Builder(context)
            .setView(root)
            .setPositiveButton("关闭", null)
            .show()
    }

    // ===== 历史留言弹窗 =====

    private fun showHistoryDialog() {
        val dates = storage.getHistoryDates()
        val dp = { v: Int -> (v * context.resources.displayMetrics.density).toInt() }

        if (dates.isEmpty()) {
            AlertDialog.Builder(context)
                .setTitle("历史留言")
                .setMessage("最近30天没有留言记录")
                .setPositiveButton("关闭", null)
                .show()
            return
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }

        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(350)
            )
        }
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        for (date in dates) {
            val messages = storage.getMessagesForDate(date)
            if (messages.isEmpty()) continue

            // 日期标题
            list.addView(TextView(context).apply {
                text = formatDateLabel(date)
                textSize = 12f
                setTextColor(0xFF888888.toInt())
                setPadding(0, dp(12), 0, dp(6))
                typeface = Typeface.DEFAULT_BOLD
            })

            for (msg in messages) {
                list.addView(makeBulletinItem(msg, showDate = false))
            }
        }

        scroll.addView(list)
        root.addView(scroll)

        AlertDialog.Builder(context)
            .setTitle("历史留言")
            .setView(root)
            .setPositiveButton("关闭", null)
            .show()
    }

    // ===== 收藏弹窗 =====

    private fun showFavoritesDialog() {
        val favorites = storage.getFavorites()
        val dp = { v: Int -> (v * context.resources.displayMetrics.density).toInt() }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }

        if (favorites.isEmpty()) {
            root.addView(makeEmptyHint("还没有收藏的留言\n长按留言可以收藏"))
        } else {
            val scroll = ScrollView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(350)
                )
            }
            val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

            for (msg in favorites) {
                list.addView(makeBulletinItem(msg, showDate = true, isFavView = true))
            }

            scroll.addView(list)
            root.addView(scroll)
        }

        AlertDialog.Builder(context)
            .setTitle("⭐ 收藏的留言")
            .setView(root)
            .setPositiveButton("关闭", null)
            .show()
    }

    // ===== UI 构件 =====

    /**
     * 一条留言卡片
     * 长按收藏/取消收藏
     */
    private fun makeBulletinItem(
        msg: BulletinMessage,
        showDate: Boolean,
        isFavView: Boolean = false
    ): LinearLayout {
        val dp = { v: Int -> (v * context.resources.displayMetrics.density).toInt() }
        val isFav = storage.isFavorite(msg.timestamp)
        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(msg.timestamp))
        val dateTimeStr = if (showDate) {
            SimpleDateFormat("M月d日 HH:mm", Locale.getDefault()).format(Date(msg.timestamp))
        } else timeStr

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            val bg = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(0x08000000)
            }
            background = bg
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }

            // 头行：作者 + 时间 + 收藏标记
            val header = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            header.addView(TextView(context).apply {
                text = msg.authorName
                textSize = 12f
                setTextColor(0xFF555555.toInt())
                typeface = Typeface.DEFAULT_BOLD
            })
            header.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
            })
            if (isFav) {
                header.addView(TextView(context).apply {
                    text = "⭐"
                    textSize = 11f
                    setPadding(0, 0, dp(4), 0)
                })
            }
            header.addView(TextView(context).apply {
                text = dateTimeStr
                textSize = 10f
                setTextColor(0xFF999999.toInt())
            })
            addView(header)

            // 内容
            addView(TextView(context).apply {
                text = msg.content
                textSize = 13f
                setTextColor(0xFF333333.toInt())
                setPadding(0, dp(4), 0, 0)
                setLineSpacing(0f, 1.3f)
            })

            // 长按：收藏 / 取消收藏
            setOnLongClickListener {
                if (isFavView) {
                    // 在收藏页：取消收藏
                    AlertDialog.Builder(context)
                        .setTitle("取消收藏")
                        .setMessage("取消后这条留言会进入30天倒计时，之后自动消失。")
                        .setPositiveButton("取消收藏") { _, _ ->
                            storage.removeFavorite(msg.timestamp)
                            Toast.makeText(context, "已取消收藏", Toast.LENGTH_SHORT).show()
                            showFavoritesDialog()
                        }
                        .setNegativeButton("保留", null)
                        .show()
                } else if (!isFav) {
                    // 不在收藏页，还没收藏：收藏
                    storage.addFavorite(msg)
                    Toast.makeText(context, "已收藏 ⭐", Toast.LENGTH_SHORT).show()
                    showTodayDialog()
                } else {
                    Toast.makeText(context, "已经收藏过了", Toast.LENGTH_SHORT).show()
                }
                true
            }
        }
    }

    private fun makeEmptyHint(text: String): TextView {
        val dp = { v: Int -> (v * context.resources.displayMetrics.density).toInt() }
        return TextView(context).apply {
            this.text = text
            textSize = 13f
            setTextColor(0xFF999999.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(40), 0, dp(40))
        }
    }

    private fun makeTextButton(label: String, onClick: () -> Unit): TextView {
        val dp = { v: Int -> (v * context.resources.displayMetrics.density).toInt() }
        return TextView(context).apply {
            text = label
            textSize = 13f
            setTextColor(0xFF666666.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { onClick() }
        }
    }

    private fun makeSpacer(width: Int): View {
        return View(context).apply {
            layoutParams = LinearLayout.LayoutParams(width, 1)
        }
    }

    private fun formatDateLabel(dateStr: String): String {
        return try {
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(dateStr) ?: return dateStr
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            val cal = Calendar.getInstance()
            cal.add(Calendar.DAY_OF_YEAR, -1)
            val yesterday = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(cal.time)
            when (dateStr) {
                today -> "今天"
                yesterday -> "昨天"
                else -> SimpleDateFormat("M月d日 EEEE", Locale.CHINESE).format(date)
            }
        } catch (_: Exception) { dateStr }
    }
}