package com.haven.guiqi

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * SubconsciousActivity — 潜意识
 *
 * 档案馆里的一个文件夹。
 * 按类别显示 AI 的偏好、想法、在意的事、答应过的承诺。
 * 做完的灰掉但还在——你能看到它做完了什么。
 */
class SubconsciousActivity : AppCompatActivity() {

    private lateinit var friendId: String
    private lateinit var friendName: String
    private lateinit var storage: SubconsciousStorage
    private lateinit var container: LinearLayout

    private val c get() = ThemeHelper.getColors(this)
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        friendId = intent.getStringExtra("friend_id") ?: run { finish(); return }
        friendName = intent.getStringExtra("friend_name") ?: "TA"
        storage = SubconsciousStorage(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(c.background)
        }

        // 标题栏
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val btnBack = TextView(this).apply {
            text = "←"; textSize = 20f; setTextColor(c.textPrimary)
            setPadding(0, 0, dp(16), 0)
            setOnClickListener { finish() }
        }
        val title = TextView(this).apply {
            text = "${friendName}的潜意识"
            textSize = 18f; setTextColor(c.textPrimary)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        topBar.addView(btnBack)
        topBar.addView(title)
        root.addView(topBar)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }
        scroll.addView(container)
        root.addView(scroll)

        setContentView(root)
        renderItems()
    }

    override fun onResume() {
        super.onResume()
        renderItems()
    }

    private fun renderItems() {
        container.removeAllViews()
        val allItems = storage.loadItems(friendId)

        if (allItems.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "还没有记录\n\n聊天的时候，$friendName 会自然地把想法存在这里"
                textSize = 14f; setTextColor(c.tipText); gravity = Gravity.CENTER
                setPadding(dp(20), dp(60), dp(20), dp(60))
            })
            return
        }

        val activeItems = allItems.filter { it.status == "active" }
        val doneItems = allItems.filter { it.status == "done" }

        // 统计
        container.addView(TextView(this).apply {
            text = "共 ${activeItems.size} 条活跃 · ${doneItems.size} 条已完成"
            textSize = 11f; setTextColor(c.tipText); setPadding(0, 0, 0, dp(12))
        })

        // ===== 当前念头 =====
        renderSection(activeItems, false)

        // ===== 已完成（折叠） =====
        if (doneItems.isNotEmpty()) {
            val doneContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val toggle = TextView(this).apply {
                text = "▸ 已完成 (${doneItems.size})"
                textSize = 12f; setTextColor(c.tipText)
                setPadding(0, dp(16), 0, dp(8))
                setOnClickListener {
                    if (doneContainer.childCount == 0) {
                        text = "▾ 已完成 (${doneItems.size})"
                        renderSection(doneItems, true, doneContainer)
                    } else {
                        text = "▸ 已完成 (${doneItems.size})"
                        doneContainer.removeAllViews()
                    }
                }
            }
            container.addView(toggle)
            container.addView(doneContainer)
        }
    }

    private fun renderSection(items: List<SubconsciousStorage.PreferenceItem>, isDoneSection: Boolean, target: LinearLayout? = null) {
        val parent = target ?: container
        val categories = listOf(
            "like" to "❤️ 喜欢的", "want_to" to "🌟 想做的", "care" to "💭 在意的",
            "interest" to "🔍 感兴趣的", "promise" to "🤝 答应过的",
            "habit" to "🔄 习惯", "dislike" to "🚫 讨厌的"
        )
        for ((catKey, catLabel) in categories) {
            val catItems = items.filter { it.category == catKey }
            if (catItems.isEmpty()) continue
            parent.addView(TextView(this).apply {
                text = "$catLabel (${catItems.size})"
                textSize = 13f; setTextColor(c.accent); setPadding(0, dp(12), 0, dp(6))
            })
            for (item in catItems.sortedByDescending { it.createdAt }) {
                parent.addView(buildItemCard(item, isDoneSection))
            }
        }
    }

    private fun buildItemCard(item: SubconsciousStorage.PreferenceItem, isDone: Boolean): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.chat_card_bg)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) }
            if (isDone) alpha = 0.45f
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(TextView(this).apply {
            text = if (isDone) "✓ ${item.content}" else item.content
            textSize = 13f; setTextColor(if (isDone) c.tipText else c.textPrimary)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        // 时间段标签
        if (item.activeFrom.isNotEmpty() && item.activeTo.isNotEmpty()) {
            row.addView(TextView(this).apply {
                text = "${item.activeFrom}~${item.activeTo}"
                textSize = 9f; setTextColor(c.accent)
                setPadding(dp(6), dp(2), dp(6), dp(2))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(4).toFloat(); setColor(c.backgroundSecondary)
                }
            })
        }
        val days = ((System.currentTimeMillis() - item.createdAt) / (24 * 60 * 60 * 1000)).toInt()
        row.addView(TextView(this).apply {
            text = when { days == 0 -> "今天"; days == 1 -> "昨天"; days < 30 -> "${days}天前"; else -> "${days/30}月前" }
            textSize = 10f; setTextColor(c.dateLabel); setPadding(dp(8), 0, 0, 0)
        })
        card.addView(row)
        if (!isDone) {
            card.setOnLongClickListener {
                android.app.AlertDialog.Builder(this)
                    .setTitle(item.content)
                    .setItems(arrayOf("标记完成", "删除")) { _, which ->
                        when (which) {
                            0 -> { storage.markDone(friendId, item.id); renderItems() }
                            1 -> { storage.deleteItem(friendId, item.content); renderItems() }
                        }
                    }.show()
                true
            }
        }
        return card
    }
}