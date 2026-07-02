package com.haven.guiqi

import android.content.Context
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * SearchManager — 聊天记录搜索
 *
 * 从 ChatConversationActivity 拆出来的第六刀。
 * 管搜索面板的显示/隐藏、实时搜索、结果渲染。
 * Activity 只管初始化和传 friendId 进来。
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

    /** 绑定搜索按钮和输入框事件 */
    fun setupListeners(btnSearch: View, btnCloseSearch: View) {
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

    private fun performSearch(keyword: String) {
        searchResults.removeAllViews()

        val allMessages = chatStorage.loadMessages(friendId)
        val matches = allMessages.filter {
            it.content.contains(keyword, ignoreCase = true) && it.content != "[SEEN]"
        }

        val maxH = (context.resources.displayMetrics.heightPixels * 0.4).toInt()
        searchResultsScroll.layoutParams.height = maxH
        searchResultsScroll.visibility = View.VISIBLE

        if (matches.isEmpty()) {
            val tip = TextView(context).apply {
                text = "没有找到相关记录"
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
                val time = java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(msg.timestamp))
                text = "$role · $time"
                textSize = 10f
                setTextColor(c.accent)
            }

            val content = TextView(context).apply {
                val fullText = msg.content
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

                this.text = spannable
                textSize = 13f
                setTextColor(c.textSecondary)
                setPadding(0, dp(4), 0, 0)
                maxLines = 3
            }

            card.addView(header)
            card.addView(content)
            searchResults.addView(card)
        }

        val countTip = TextView(context).apply {
            text = "找到 ${matches.size} 条记录" +
                    (if (matches.size > 50) "（显示最近 50 条）" else "")
            textSize = 10f
            setTextColor(c.textHint)
            setPadding(dp(4), dp(4), dp(4), dp(8))
        }
        searchResults.addView(countTip, 0)
    }
}