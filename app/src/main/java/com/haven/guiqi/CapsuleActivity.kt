package com.haven.guiqi

import android.app.AlertDialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.*

/**
 * CapsuleActivity — 时间胶囊列表
 *
 * 信封风格：
 * - 未到期：封着的信封，显示"还有X天"，内容是 · · · · · ·
 * - 已到期未读：信封上有个红点，可以点击拆封
 * - 已拆封：展开的信纸，显示完整内容
 */
class CapsuleActivity : AppCompatActivity() {

    private var friendId = ""
    private var friendName = ""
    private lateinit var capsuleStorage: CapsuleStorage
    private lateinit var listContainer: LinearLayout
    private val c by lazy { ThemeHelper.getColors(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.init(this)
        super.onCreate(savedInstanceState)

        friendId = intent.getStringExtra("friend_id") ?: ""
        friendName = intent.getStringExtra("friend_name") ?: ""
        capsuleStorage = CapsuleStorage(this)

        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(c.background)
            setPadding(0, dp(40), 0, 0)
        }

        // 顶栏
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(12))
        }
        topBar.addView(TextView(this).apply {
            text = "←"; textSize = 18f; setTextColor(c.textPrimary)
            setPadding(dp(8), dp(4), dp(16), dp(4))
            setOnClickListener { finish() }
        })
        topBar.addView(TextView(this).apply {
            text = "✉ 时间胶囊"; textSize = 16f; setTextColor(c.textPrimary)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        // 埋胶囊按钮
        topBar.addView(TextView(this).apply {
            text = "✎ 写信"; textSize = 13f; setTextColor(c.accent)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { showBuryDialog() }
        })
        root.addView(topBar)

        // 列表
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }
        scroll.addView(listContainer)
        root.addView(scroll)

        setContentView(root)
        renderList()
    }

    private fun renderList() {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        listContainer.removeAllViews()

        val capsules = capsuleStorage.loadAll(friendId)
        if (capsules.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = "还没有时间胶囊\n点右上角「写信」埋下第一封"
                textSize = 13f; setTextColor(c.textHint); gravity = Gravity.CENTER
                setPadding(0, dp(60), 0, 0)
            })
            return
        }

        val now = System.currentTimeMillis()
        for (cap in capsules) {
            val isUnlocked = cap.unlockAt <= now
            listContainer.addView(buildEnvelope(cap, isUnlocked, dp))
        }
    }

    /** 构建信封卡片 */
    private fun buildEnvelope(cap: CapsuleStorage.Capsule, unlocked: Boolean, dp: (Int) -> Int): LinearLayout {
        val now = System.currentTimeMillis()
        val dateFmt = SimpleDateFormat("yyyy年M月d日", Locale.CHINESE)

        val envelope = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(if (unlocked) c.card else c.paperBg)
                setStroke(1, c.paperBorder)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }

        // 头行：寄信人 → 收信人 + 日期
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "${cap.authorName} → ${cap.recipientName}"
            textSize = 12f; setTextColor(c.textSecondary)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        // 未拆封的红点
        if (unlocked && !cap.opened) {
            header.addView(TextView(this).apply {
                text = "●"; textSize = 10f; setTextColor(0xFFE57373.toInt())
                setPadding(0, 0, dp(6), 0)
            })
        }
        header.addView(TextView(this).apply {
            text = dateFmt.format(Date(cap.buriedAt))
            textSize = 10f; setTextColor(c.textHint)
        })
        envelope.addView(header)

        if (unlocked) {
            // 已解锁——显示信纸内容
            envelope.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                    .apply { topMargin = dp(8); bottomMargin = dp(8) }
                setBackgroundColor(c.border)
            })
            envelope.addView(TextView(this).apply {
                text = cap.content
                textSize = 13f; setTextColor(c.textPrimary)
                setLineSpacing(0f, 1.4f)
            })
            // 底部拆封时间
            envelope.addView(TextView(this).apply {
                text = "拆封日：${dateFmt.format(Date(cap.unlockAt))}"
                textSize = 9f; setTextColor(c.textHint)
                setPadding(0, dp(8), 0, 0)
                gravity = Gravity.END
            })
            // 点击标记已读
            if (!cap.opened) {
                envelope.setOnClickListener {
                    capsuleStorage.markOpened(friendId, cap.id)
                    renderList()
                }
            }
        } else {
            // 未解锁——密封状态
            envelope.addView(TextView(this).apply {
                text = "· · · · · · · · · · · · · · ·"
                textSize = 14f; setTextColor(c.textHint)
                gravity = Gravity.CENTER
                setPadding(0, dp(16), 0, dp(12))
            })
            val daysLeft = ((cap.unlockAt - now) / 86400000) + 1
            val countdownText = when {
                daysLeft > 30 -> "还有 ${daysLeft} 天拆封"
                daysLeft > 1 -> "还有 ${daysLeft} 天拆封 ♡"
                else -> "明天就能拆封了！"
            }
            envelope.addView(TextView(this).apply {
                text = countdownText
                textSize = 11f; setTextColor(c.accent)
                gravity = Gravity.CENTER
            })
        }

        return envelope
    }

    /** 用户手动埋胶囊 */
    private fun showBuryDialog() {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }

        val inputDate = EditText(this).apply {
            hint = "拆封日期（如：2026-12-25 或 30天后）"
            textSize = 13f; setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val inputContent = EditText(this).apply {
            hint = "写给未来的话..."
            textSize = 13f; setPadding(dp(12), dp(8), dp(12), dp(8))
            minLines = 4; gravity = Gravity.TOP
        }
        layout.addView(inputDate)
        layout.addView(inputContent)

        AlertDialog.Builder(this)
            .setTitle("✉ 埋一个时间胶囊")
            .setView(layout)
            .setPositiveButton("封存") { _, _ ->
                val dateStr = inputDate.text.toString().trim()
                val content = inputContent.text.toString().trim()
                if (dateStr.isEmpty() || content.isEmpty()) {
                    Toast.makeText(this, "日期和内容都要写哦", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val unlockTime = CapsuleStorage.parseDate(dateStr)
                if (unlockTime == null) {
                    Toast.makeText(this, "日期格式不对，试试 2026-12-25 或 30天后", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val prefs = getSharedPreferences("haven_prefs", MODE_PRIVATE)
                val userName = prefs.getString("user_name", "我") ?: "我"
                capsuleStorage.bury(friendId, CapsuleStorage.Capsule(
                    id = "CAP-${System.currentTimeMillis()}",
                    authorId = "user",
                    authorName = userName,
                    recipientName = friendName,
                    content = content,
                    buriedAt = System.currentTimeMillis(),
                    unlockAt = unlockTime
                ))
                Toast.makeText(this, "胶囊已封存 ✉", Toast.LENGTH_SHORT).show()
                renderList()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}