package com.haven.guiqi

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.*

/**
 * EchoActivity — 留声浏览
 *
 * 按时间线显示所有聊天记录，支持搜索和导入。
 */
class EchoActivity : AppCompatActivity() {

    private lateinit var echoStorage: EchoStorage
    private lateinit var friendId: String
    private lateinit var friendName: String
    private lateinit var listContainer: LinearLayout
    private lateinit var searchInput: EditText
    private val c get() = ThemeHelper.getColors(this)
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val PICK_FILE = 2001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        friendId = intent.getStringExtra("friend_id") ?: run { finish(); return }
        friendName = intent.getStringExtra("friend_name") ?: "AI"
        echoStorage = EchoStorage(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(c.background)
        }

        // ===== 顶栏 =====
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(8))
        }
        topBar.addView(TextView(this).apply {
            text = "←"
            textSize = 20f
            setTextColor(c.textPrimary)
            setPadding(dp(8), 0, dp(16), 0)
            setOnClickListener { finish() }
        })
        topBar.addView(TextView(this).apply {
            text = "${friendName}的留声"
            textSize = 17f
            setTextColor(c.textPrimary)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        // 导入按钮
        topBar.addView(TextView(this).apply {
            text = "导入"
            textSize = 13f
            setTextColor(c.accent)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { pickImportFile() }
        })
        // 同步按钮
        topBar.addView(TextView(this).apply {
            text = "同步"
            textSize = 13f
            setTextColor(c.accent)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { syncFromChat() }
        })
        // 清空按钮
        topBar.addView(TextView(this).apply {
            text = "清空"
            textSize = 13f
            setTextColor(c.errorText)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener {
                android.app.AlertDialog.Builder(this@EchoActivity)
                    .setTitle("清空留声？")
                    .setMessage("将删除${friendName}的所有留声记录，不可恢复")
                    .setPositiveButton("清空") { _, _ ->
                        val file = java.io.File(filesDir, "echo/$friendId.json")
                        if (file.exists()) file.delete()
                        showAll()
                        Toast.makeText(this@EchoActivity, "已清空", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        })
        root.addView(topBar)

        // ===== 搜索栏 =====
        searchInput = EditText(this).apply {
            hint = "搜索关键词或日期（如 2024年3月）"
            textSize = 13f
            setTextColor(c.textPrimary)
            setHintTextColor(c.textHint)
            setBackgroundColor(c.inputBg)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(12); marginEnd = dp(12); bottomMargin = dp(8)
            }
            setOnEditorActionListener { _, _, _ ->
                val query = text.toString().trim()
                if (query.isNotEmpty()) showSearchResults(query) else showAll()
                true
            }
        }
        root.addView(searchInput)

        // ===== 列表 =====
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(12), dp(12))
        }
        scroll.addView(listContainer)
        root.addView(scroll)

        setContentView(root)
        showAll()
    }

    // 分页状态：一次只画一页卡片（几千张卡片一次性画出来会把界面卡死）
    private var allEchoMessages: List<EchoStorage.EchoMessage> = emptyList()
    private var renderedCount = 0
    private val echoPageSize = 100

    private fun showAll() {
        listContainer.removeAllViews()
        listContainer.addView(TextView(this).apply {
            text = "加载中…"
            textSize = 12f
            setTextColor(c.textHint)
            gravity = Gravity.CENTER
            setPadding(0, dp(40), 0, 0)
        })
        // 读文件放后台线程，读完再回界面线程画卡片
        Thread {
            val messages = echoStorage.loadAll(friendId)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                allEchoMessages = messages
                renderedCount = 0
                listContainer.removeAllViews()
                if (messages.isEmpty()) {
                    listContainer.addView(TextView(this).apply {
                        text = "留声里还没有记录"
                        textSize = 13f
                        setTextColor(c.textHint)
                        gravity = Gravity.CENTER
                        setPadding(0, dp(40), 0, 0)
                    })
                    return@runOnUiThread
                }
                listContainer.addView(TextView(this).apply {
                    text = "共 ${messages.size} 条记录（最新的在前面）"
                    textSize = 11f
                    setTextColor(c.textHint)
                    setPadding(0, dp(4), 0, dp(8))
                })
                echoLastDate = ""
                renderNextEchoPage()
            }
        }.start()
    }

    /** 画下一页（100条），底部放"加载更早"按钮 */
    private fun renderNextEchoPage() {
        listContainer.findViewWithTag<android.view.View>("echo_more_btn")?.let {
            listContainer.removeView(it)
        }

        val newestFirst = allEchoMessages.asReversed()
        val from = renderedCount
        val to = minOf(from + echoPageSize, newestFirst.size)
        if (from >= to) return
        appendEchoCards(newestFirst.subList(from, to))
        renderedCount = to

        if (renderedCount < newestFirst.size) {
            listContainer.addView(TextView(this).apply {
                tag = "echo_more_btn"
                text = "▽ 加载更早的记录（还有 ${newestFirst.size - renderedCount} 条）"
                textSize = 12f
                setTextColor(c.accent)
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(16))
                setOnClickListener { renderNextEchoPage() }
            })
        }
    }

    private fun showSearchResults(query: String) {
        var results = echoStorage.searchByKeyword(friendId, query, 30)
        if (results.isEmpty()) {
            results = echoStorage.searchByDate(friendId, query, 30)
        }
        renderMessages(results, "没有找到「$query」相关的记录")
    }

    private fun renderMessages(messages: List<EchoStorage.EchoMessage>, emptyHint: String) {
        listContainer.removeAllViews()

        if (messages.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = emptyHint
                textSize = 13f
                setTextColor(c.textHint)
                gravity = Gravity.CENTER
                setPadding(0, dp(40), 0, 0)
            })
            return
        }

        // 统计
        listContainer.addView(TextView(this).apply {
            text = "共 ${messages.size} 条记录"
            textSize = 11f
            setTextColor(c.textHint)
            setPadding(0, dp(4), 0, dp(8))
        })

        echoLastDate = ""
        appendEchoCards(messages)
    }

    // 日期分隔线状态（分页和搜索共用）
    private var echoLastDate = ""

    /** 追加一批留声卡片到列表末尾 */
    private fun appendEchoCards(messages: List<EchoStorage.EchoMessage>) {
        var lastDate = echoLastDate
        for (msg in messages) {
            // 日期分隔
            val dateStr = if (msg.timestamp > 0) {
                SimpleDateFormat("yyyy年M月d日", Locale.getDefault()).format(Date(msg.timestamp))
            } else "导入的记录"

            if (dateStr != lastDate) {
                lastDate = dateStr
                listContainer.addView(TextView(this).apply {
                    text = dateStr
                    textSize = 11f
                    setTextColor(c.accent)
                    setPadding(0, dp(12), 0, dp(4))
                })
            }

            // 消息卡片
            val isUser = msg.role == "user"
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(6), dp(10), dp(6))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(4) }
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(if (isUser) c.accentBg else c.inputBg)
                    cornerRadius = dp(6).toFloat()
                }
            }

            // 标头：角色 + 时间
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            header.addView(TextView(this).apply {
                text = if (isUser) "我" else friendName
                textSize = 11f
                setTextColor(if (isUser) c.accent else c.accentStrong)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (msg.timestamp > 0) {
                val msgYear = java.util.Calendar.getInstance().apply { timeInMillis = msg.timestamp }.get(java.util.Calendar.YEAR)
                val thisYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
                val fmt = if (msgYear == thisYear) "M/d HH:mm" else "yyyy/M/d HH:mm"
                header.addView(TextView(this).apply {
                    text = SimpleDateFormat(fmt, Locale.getDefault()).format(Date(msg.timestamp))
                    textSize = 10f
                    setTextColor(c.timeText)
                })
            }
            if (msg.source == "import") {
                header.addView(TextView(this).apply {
                    text = " 导入"
                    textSize = 9f
                    setTextColor(c.textHint)
                })
            }
            card.addView(header)

            // 内容
            card.addView(TextView(this).apply {
                text = msg.content
                textSize = 13f
                setTextColor(c.textPrimary)
                setLineSpacing(0f, 1.3f)
                setPadding(0, dp(3), 0, 0)
            })

            listContainer.addView(card)
        }
        echoLastDate = lastDate
    }

    // ===== 同步聊天记录 =====
    private fun syncFromChat() {
        Toast.makeText(this, "同步中…", Toast.LENGTH_SHORT).show()
        Thread {
            val chatMessages = ChatStorage(this).loadMessages(friendId)
            echoStorage.syncFromChat(friendId, chatMessages)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                showAll()
                Toast.makeText(this, "同步完成", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    // ===== 导入文件 =====
    private fun pickImportFile() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "text/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(intent, PICK_FILE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_FILE && resultCode == RESULT_OK && data?.data != null) {
            try {
                val text = contentResolver.openInputStream(data.data!!)?.bufferedReader()?.readText() ?: return
                // 先选导入模式
                android.app.AlertDialog.Builder(this)
                    .setTitle("选择导入方式")
                    .setItems(arrayOf(
                        "自动识别（按\"名字：内容\"格式）",
                        "指定名字（手动填双方名字）"
                    )) { _, which ->
                        when (which) {
                            0 -> showAutoImportDialog(text)
                            1 -> showManualImportDialog(text)
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            } catch (e: Exception) {
                Toast.makeText(this, "导入失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 模式一：自动识别说话人 */
    private fun showAutoImportDialog(text: String) {
        val input = EditText(this).apply {
            hint = "比如：权浅、我、User"
            textSize = 14f
            setTextColor(c.textPrimary)
            setHintTextColor(c.textHint)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("你在文件里叫什么名字？")
            .setMessage("系统会用这个名字来区分哪些话是你说的")
            .setView(input)
            .setPositiveButton("导入") { _, _ ->
                val myName = input.text.toString().trim().ifEmpty { "我" }
                val count = echoStorage.importFromText(friendId, text, myName, friendName)
                Toast.makeText(this, "导入了 $count 条记录", Toast.LENGTH_SHORT).show()
                showAll()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 模式二：指定双方名字，只有这两个名字开头的行才切换说话人 */
    private fun showManualImportDialog(text: String) {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(4))
        }
        val inputMyName = EditText(this).apply {
            hint = "我的名字（如：权浅）"
            textSize = 14f
            setTextColor(c.textPrimary)
            setHintTextColor(c.textHint)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val inputAiName = EditText(this).apply {
            hint = "对方的名字（如：暂无）"
            textSize = 14f
            setTextColor(c.textPrimary)
            setHintTextColor(c.textHint)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        layout.addView(inputMyName)
        layout.addView(inputAiName)

        android.app.AlertDialog.Builder(this)
            .setTitle("填写双方名字")
            .setMessage("只有以这两个名字开头的行才会切换说话人，其他内容（空行、标题等）都续到上一个人的话里。")
            .setView(layout)
            .setPositiveButton("导入") { _, _ ->
                val myName = inputMyName.text.toString().trim().ifEmpty { "我" }
                val aiName = inputAiName.text.toString().trim().ifEmpty { friendName }
                val count = echoStorage.importByExplicitNames(friendId, text, myName, aiName)
                Toast.makeText(this, "导入了 $count 条记录", Toast.LENGTH_SHORT).show()
                showAll()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}