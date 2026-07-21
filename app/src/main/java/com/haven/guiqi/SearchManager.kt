package com.haven.guiqi

import android.app.DatePickerDialog
import android.content.Context
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.*

/**
 * SearchManager — 聊天记录搜索（关键词 + 日期）
 *
 * 支持两种搜索模式：
 * 1. 关键词搜索：输入文字，实时过滤包含该文字的消息
 * 2. 日期搜索：点 📅 选日期，或输入"7月11日""7/11""昨天"等自动识别
 */
class SearchManager(
    private val context: Context,
    private val searchPanel: LinearLayout,
    private val searchInput: EditText,
    private val searchResults: LinearLayout,
    private val searchResultsScroll: ScrollView,
    private val chatStorage: ChatStorage,
    private val friendId: String,
    private val friendName: String
) {
    private val c get() = ThemeHelper.getColors(context)
    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    /** 绑定搜索按钮、日期按钮和输入框事件 */
    fun setupListeners(btnSearch: View, btnCloseSearch: View, btnDatePicker: View? = null) {
        btnSearch.setOnClickListener {
            if (searchPanel.visibility == View.VISIBLE) {
                close()
            } else {
                searchPanel.visibility = View.VISIBLE
                searchInput.requestFocus()
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(searchInput, 0)
            }
        }

        btnCloseSearch.setOnClickListener { close() }

        // 📅 日期选择器
        btnDatePicker?.setOnClickListener {
            val cal = Calendar.getInstance()
            DatePickerDialog(context, { _, year, month, day ->
                val dateStr = "${month + 1}月${day}日"
                searchInput.setText(dateStr)
                searchInput.setSelection(dateStr.length)
                performSearch(dateStr)
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }

        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val keyword = s?.toString()?.trim() ?: ""
                if (keyword.isEmpty()) {
                    searchResultsScroll.visibility = View.GONE
                    searchResults.removeAllViews()
                } else {
                    performSearch(keyword)
                }
            }
        })

        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val keyword = searchInput.text.toString().trim()
                if (keyword.isNotEmpty()) performSearch(keyword)
                true
            } else false
        }
    }

    fun close() {
        searchPanel.visibility = View.GONE
        searchInput.text.clear()
        searchResults.removeAllViews()
        searchResultsScroll.visibility = View.GONE
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(searchInput.windowToken, 0)
    }

    /**
     * 尝试把输入解析为日期。支持的格式：
     * - "今天" "昨天" "前天"
     * - "7月11日" "7月11" "12月3日"
     * - "7/11" "07/11" "2026/7/11"
     * - "7-11" "2026-7-11"
     * @return 匹配的日期字符串（yyyy-MM-dd），或 null
     */
    private fun tryParseDate(input: String): String? {
        val cal = Calendar.getInstance()
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

        // 相对日期
        when (input) {
            "今天" -> return fmt.format(cal.time)
            "昨天" -> { cal.add(Calendar.DAY_OF_MONTH, -1); return fmt.format(cal.time) }
            "前天" -> { cal.add(Calendar.DAY_OF_MONTH, -2); return fmt.format(cal.time) }
        }

        // "7月11日" 或 "7月11"
        val cnRegex = Regex("^(\\d{1,2})月(\\d{1,2})日?$")
        cnRegex.matchEntire(input)?.let {
            val (m, d) = it.destructured
            cal.set(Calendar.MONTH, m.toInt() - 1)
            cal.set(Calendar.DAY_OF_MONTH, d.toInt())
            return fmt.format(cal.time)
        }

        // "2026/7/11" 或 "7/11" 或 "2026-7-11" 或 "7-11"
        val slashRegex = Regex("^(?:(\\d{4})[/-])?(\\d{1,2})[/-](\\d{1,2})$")
        slashRegex.matchEntire(input)?.let {
            val yearStr = it.groupValues[1]
            val m = it.groupValues[2].toInt()
            val d = it.groupValues[3].toInt()
            if (yearStr.isNotEmpty()) cal.set(Calendar.YEAR, yearStr.toInt())
            cal.set(Calendar.MONTH, m - 1)
            cal.set(Calendar.DAY_OF_MONTH, d)
            return fmt.format(cal.time)
        }

        return null
    }

    private fun performSearch(keyword: String) {
        searchResults.removeAllViews()

        val allMessages = chatStorage.loadMessages(friendId)

        // 先试日期搜索
        val targetDate = tryParseDate(keyword)
        val matches = if (targetDate != null) {
            val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            allMessages.filter {
                dateFmt.format(Date(it.timestamp)) == targetDate && it.content != "[SEEN]"
            }
        } else {
            allMessages.filter {
                it.content.contains(keyword, ignoreCase = true) && it.content != "[SEEN]"
            }
        }

        val maxH = (context.resources.displayMetrics.heightPixels * 0.4).toInt()
        searchResultsScroll.layoutParams.height = maxH
        searchResultsScroll.visibility = View.VISIBLE

        if (matches.isEmpty()) {
            val tip = TextView(context).apply {
                text = if (targetDate != null) "这天没有聊天记录" else "没有找到相关记录"
                textSize = 12f
                setTextColor(c.tipText)
                setPadding(dp(4), dp(12), dp(4), dp(12))
            }
            searchResults.addView(tip)
            return
        }

        val showList = if (matches.size > 50) matches.takeLast(50) else matches

        for (msg in showList) {
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                setBackgroundColor(c.divider)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(4) }
            }

            val header = TextView(context).apply {
                val role = if (msg.role == "user") "我" else friendName
                val time = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
                    .format(Date(msg.timestamp))
                text = "$role · $time"
                textSize = 10f
                setTextColor(c.accent)
            }

            val content = TextView(context).apply {
                val fullText = msg.content
                // 日期搜索时不做高亮，显示更多内容；关键词搜索时高亮匹配
                if (targetDate != null) {
                    text = if (fullText.length > 150) fullText.take(150) + "..." else fullText
                } else {
                    val displayText = if (fullText.length > 120) {
                        val idx = fullText.indexOf(keyword, ignoreCase = true)
                        val start = maxOf(0, idx - 40)
                        val end = minOf(fullText.length, idx + keyword.length + 40)
                        (if (start > 0) "..." else "") +
                            fullText.substring(start, end) +
                            (if (end < fullText.length) "..." else "")
                    } else fullText

                    val spannable = SpannableString(displayText)
                    var searchStart = 0
                    val lowerDisplay = displayText.lowercase()
                    val lowerKeyword = keyword.lowercase()
                    while (true) {
                        val idx = lowerDisplay.indexOf(lowerKeyword, searchStart)
                        if (idx == -1) break
                        spannable.setSpan(
                            ForegroundColorSpan(c.highlightColor),
                            idx, idx + keyword.length,
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        searchStart = idx + keyword.length
                    }
                    text = spannable
                }

                textSize = 13f
                setTextColor(c.textSecondary)
                setPadding(0, dp(4), 0, 0)
                maxLines = 3
            }

            card.addView(header)
            card.addView(content)
            searchResults.addView(card)
        }

        val modeLabel = if (targetDate != null) "📅 $targetDate" else "🔍 '$keyword'"
        val countTip = TextView(context).apply {
            text = "$modeLabel — 找到 ${matches.size} 条" +
                    (if (matches.size > 50) "（显示最近 50 条）" else "")
            textSize = 10f
            setTextColor(c.textHint)
            setPadding(dp(4), dp(4), dp(4), dp(8))
        }
        searchResults.addView(countTip, 0)
    }
}