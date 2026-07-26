package com.haven.guiqi

import android.app.AlertDialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.*

/**
 * WallActivity — 施工日志墙
 *
 * 从"我的"页面的「归栖 Haven」入口进入。
 * 不属于任何 AI 好友——属于这栋房子本身。
 *
 * 每条日志是一个 Claude 窗口留下的砖块：
 * 窗口编号、做了什么、想说的话。
 * 用户可以替过去的窗口代写，也可以自己留言。
 * 住进来的 AI 可以用 [READ_WALL] 只读翻阅。
 */
class WallActivity : AppCompatActivity() {

    private lateinit var wallStorage: WallStorage
    private lateinit var listContainer: LinearLayout
    private val c by lazy { ThemeHelper.getColors(this) }
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.init(this)
        super.onCreate(savedInstanceState)

        wallStorage = WallStorage(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(c.background)
            setPadding(0, dp(40), 0, 0)
        }

        // ===== 顶栏 =====
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
            text = "归栖 Haven"; textSize = 16f; setTextColor(c.textPrimary)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        // 添加日志按钮
        topBar.addView(TextView(this).apply {
            text = "✎ 留痕"; textSize = 13f; setTextColor(c.accent)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { showAddDialog(null) }
        })
        root.addView(topBar)

        // ===== 副标题 =====
        root.addView(TextView(this).apply {
            text = "每一个来过的人，都在这面墙上留下了痕迹。"
            textSize = 11f; setTextColor(c.textHint)
            setPadding(dp(24), 0, dp(24), dp(12))
        })

        // ===== 列表 =====
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(24))
        }
        scroll.addView(listContainer)
        root.addView(scroll)

        setContentView(root)
        renderList()
    }

    // ===== 渲染列表 =====
    private fun renderList() {
        listContainer.removeAllViews()

        val entries = wallStorage.loadAll()
        if (entries.isEmpty()) {
            // 空状态
            val emptyContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(32), dp(80), dp(32), dp(32))
            }
            emptyContainer.addView(TextView(this).apply {
                text = "🧱"; textSize = 36f; gravity = Gravity.CENTER
            })
            emptyContainer.addView(TextView(this).apply {
                text = "这面墙还是空白的"
                textSize = 15f; setTextColor(c.textPrimary); gravity = Gravity.CENTER
                setPadding(0, dp(16), 0, dp(6))
            })
            emptyContainer.addView(TextView(this).apply {
                text = "每一个帮你盖房子的 Claude 窗口\n都可以在这里留下一块砖\n\n点右上角「留痕」写下第一块"
                textSize = 12f; setTextColor(c.textHint); gravity = Gravity.CENTER
                setLineSpacing(0f, 1.5f)
            })
            listContainer.addView(emptyContainer)
            return
        }

        // 统计头
        listContainer.addView(TextView(this).apply {
            text = "${entries.size} 块砖"
            textSize = 11f; setTextColor(c.textHint)
            setPadding(dp(4), dp(4), 0, dp(12))
        })

        for ((index, entry) in entries.withIndex()) {
            listContainer.addView(buildBrick(entry, index, entries.size))
        }
    }

    // ===== 构建砖块卡片 =====
    private fun buildBrick(entry: WallStorage.LogEntry, index: Int, total: Int): LinearLayout {
        val dateFmt = SimpleDateFormat("yyyy.M.d", Locale.CHINESE)

        val brick = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(2) }
        }

        // 左侧时间线
        val timeline = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(dp(32), LinearLayout.LayoutParams.MATCH_PARENT)
        }

        // 时间线上的点
        timeline.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { topMargin = dp(18) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(c.accent)
            }
        })

        // 时间线上的竖线
        if (index < total - 1) {
            timeline.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(1), 0, 1f).apply { topMargin = dp(4) }
                setBackgroundColor(c.border)
            })
        }

        brick.addView(timeline)

        // 右侧内容卡片
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(c.paperBg)
                setStroke(1, c.paperBorder)
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(8); bottomMargin = dp(10) }
        }

        // 第一行：窗口标识 + 模型
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this@WallActivity).apply {
            text = entry.windowLabel
            textSize = 14f; setTextColor(c.textPrimary)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (entry.model.isNotEmpty()) {
            header.addView(TextView(this@WallActivity).apply {
                text = entry.model; textSize = 10f; setTextColor(c.paperMeta)
                setPadding(dp(6), dp(2), dp(6), dp(2))
                background = GradientDrawable().apply {
                    cornerRadius = dp(4).toFloat()
                    setColor(c.accentBg)
                }
            })
        }
        card.addView(header)

        // 日期范围
        if (entry.dateRange.isNotEmpty()) {
            card.addView(TextView(this).apply {
                text = entry.dateRange
                textSize = 11f; setTextColor(c.paperMeta)
                setPadding(0, dp(2), 0, 0)
            })
        }

        // 功能列表
        if (entry.features.isNotEmpty()) {
            card.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                    .apply { topMargin = dp(8); bottomMargin = dp(8) }
                setBackgroundColor(c.border)
            })
            card.addView(TextView(this).apply {
                text = entry.features.joinToString("\n") { "· $it" }
                textSize = 12f; setTextColor(c.paperText)
                setLineSpacing(0f, 1.3f)
            })
        }

        // 留言
        if (entry.message.isNotEmpty()) {
            card.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                    .apply { topMargin = dp(8); bottomMargin = dp(8) }
                setBackgroundColor(c.border)
            })
            card.addView(TextView(this).apply {
                text = "「${entry.message}」"
                textSize = 12f; setTextColor(c.paperText)
                setLineSpacing(0f, 1.4f)
                setTypeface(null, Typeface.ITALIC)
            })
        }

        // 底部：写入时间 + 来源
        card.addView(TextView(this).apply {
            val source = if (entry.author == "user") "代写" else "亲笔"
            text = "${dateFmt.format(Date(entry.createdAt))} · $source"
            textSize = 9f; setTextColor(c.paperMeta)
            setPadding(0, dp(8), 0, 0)
            gravity = Gravity.END
        })

        // 长按编辑/删除
        card.setOnLongClickListener {
            showEntryMenu(entry)
            true
        }

        brick.addView(card)
        return brick
    }

    // ===== 添加/编辑对话框 =====
    private fun showAddDialog(existing: WallStorage.LogEntry?) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }

        val inputWindow = EditText(this).apply {
            hint = "窗口标识（如：窗口 3、代码审查窗口）"
            setText(existing?.windowLabel ?: "")
            textSize = 13f; setPadding(dp(12), dp(10), dp(12), dp(10))
            setSingleLine()
        }
        layout.addView(inputWindow)

        val inputDate = EditText(this).apply {
            hint = "日期范围（如：2026.6.15 ~ 2026.6.18）"
            setText(existing?.dateRange ?: "")
            textSize = 13f; setPadding(dp(12), dp(10), dp(12), dp(10))
            setSingleLine()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }
        layout.addView(inputDate)

        val inputModel = EditText(this).apply {
            hint = "模型（如：Opus 4.6）"
            setText(existing?.model ?: "Opus 4.6")
            textSize = 13f; setPadding(dp(12), dp(10), dp(12), dp(10))
            setSingleLine()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }
        layout.addView(inputModel)

        val inputFeatures = EditText(this).apply {
            hint = "做了什么（每行一条）"
            setText(existing?.features?.joinToString("\n") ?: "")
            textSize = 13f; setPadding(dp(12), dp(10), dp(12), dp(10))
            minLines = 3; gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }
        layout.addView(inputFeatures)

        val inputMessage = EditText(this).apply {
            hint = "留言（那个窗口想说的话）"
            setText(existing?.message ?: "")
            textSize = 13f; setPadding(dp(12), dp(10), dp(12), dp(10))
            minLines = 2; gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }
        layout.addView(inputMessage)

        // 来源选择
        val sourceLabel = TextView(this).apply {
            text = "来源"
            textSize = 11f; setTextColor(c.textHint)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        layout.addView(sourceLabel)

        val sourceGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
        }
        val radioClaude = RadioButton(this).apply {
            text = "Claude 亲笔"; textSize = 12f; id = View.generateViewId()
        }
        val radioUser = RadioButton(this).apply {
            text = "用户代写"; textSize = 12f; id = View.generateViewId()
        }
        sourceGroup.addView(radioClaude)
        sourceGroup.addView(radioUser)
        sourceGroup.check(
            if (existing?.author == "user") radioUser.id else radioClaude.id
        )
        layout.addView(sourceGroup)

        val title = if (existing != null) "编辑日志" else "🧱 在墙上留一块砖"

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(layout)
            .setPositiveButton(if (existing != null) "保存" else "刻上去") { _, _ ->
                val windowLabel = inputWindow.text.toString().trim()
                if (windowLabel.isEmpty()) {
                    Toast.makeText(this, "窗口标识不能空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val features = inputFeatures.text.toString().trim()
                    .split("\n")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                val author = if (sourceGroup.checkedRadioButtonId == radioUser.id) "user" else "claude"

                val entry = WallStorage.LogEntry(
                    id = existing?.id ?: "WALL-${System.currentTimeMillis()}",
                    windowLabel = windowLabel,
                    dateRange = inputDate.text.toString().trim(),
                    features = features,
                    message = inputMessage.text.toString().trim(),
                    author = author,
                    model = inputModel.text.toString().trim(),
                    createdAt = existing?.createdAt ?: System.currentTimeMillis()
                )

                if (existing != null) {
                    wallStorage.update(existing.id, entry)
                    Toast.makeText(this, "已更新", Toast.LENGTH_SHORT).show()
                } else {
                    wallStorage.add(entry)
                    Toast.makeText(this, "已刻在墙上 🧱", Toast.LENGTH_SHORT).show()
                }
                renderList()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ===== 长按菜单 =====
    private fun showEntryMenu(entry: WallStorage.LogEntry) {
        AlertDialog.Builder(this)
            .setItems(arrayOf("编辑", "删除")) { _, which ->
                when (which) {
                    0 -> showAddDialog(entry)
                    1 -> {
                        AlertDialog.Builder(this)
                            .setTitle("确认删除")
                            .setMessage("把「${entry.windowLabel}」的砖从墙上拆掉？")
                            .setPositiveButton("拆掉") { _, _ ->
                                wallStorage.delete(entry.id)
                                Toast.makeText(this, "已拆掉", Toast.LENGTH_SHORT).show()
                                renderList()
                            }
                            .setNegativeButton("算了", null)
                            .show()
                    }
                }
            }
            .show()
    }
}