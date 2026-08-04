package com.haven.guiqi

import android.app.DatePickerDialog
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
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
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 当前聊天内查找。
 *
 * - 搜索在后台线程完成，长聊天输入关键词时不会卡主界面。
 * - 结果支持上一个 / 下一个、点击跳转与结果计数。
 * - 具体消息定位交给 ChatHistoryLoader；未加载的旧消息会显示其附近上下文。
 */
class SearchManager(
    private val context: Context,
    private val searchPanel: LinearLayout,
    private val searchInput: EditText,
    private val searchNavigation: LinearLayout,
    private val searchStatus: TextView,
    private val btnSearchPrev: TextView,
    private val btnSearchNext: TextView,
    private val searchResults: LinearLayout,
    private val searchResultsScroll: ScrollView,
    private val chatStorage: ChatStorage,
    private val friendId: String,
    private val friendName: String,
    private val onSearchOpened: () -> Unit,
    private val onSearchClosed: () -> Unit,
    private val onJumpToResult: (List<StoredMessage>, Int) -> Unit
) {
    private data class SearchMatch(
        val sourceIndex: Int,
        val message: StoredMessage,
        val displayText: String
    )

    private val c get() = ThemeHelper.getColors(context)
    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val searchExecutor = Executors.newSingleThreadExecutor()
    private val searchGeneration = AtomicInteger(0)
    private var pendingSearch: Runnable? = null
    private var allMessages: List<StoredMessage> = emptyList()
    private var matches: List<SearchMatch> = emptyList()
    private var currentMatchIndex = -1
    private var activeKeyword = ""
    private var activeTargetDate: String? = null
    private var isOpen = false

    fun setupListeners(btnSearch: View, btnCloseSearch: View, btnDatePicker: View? = null) {
        btnSearch.setOnClickListener {
            if (searchPanel.visibility == View.VISIBLE) close() else open()
        }
        btnCloseSearch.setOnClickListener { close() }

        btnDatePicker?.setOnClickListener {
            val cal = Calendar.getInstance()
            DatePickerDialog(context, { _, _, month, day ->
                val dateStr = "${month + 1}月${day}日"
                searchInput.setText(dateStr)
                searchInput.setSelection(dateStr.length)
                scheduleSearch(dateStr, immediate = true)
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }

        btnSearchPrev.setOnClickListener { moveSelection(-1) }
        btnSearchNext.setOnClickListener { moveSelection(1) }

        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                val keyword = s?.toString()?.trim().orEmpty()
                if (keyword.isEmpty()) clearResults() else scheduleSearch(keyword)
            }
        })

        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val keyword = searchInput.text.toString().trim()
                if (keyword.isNotEmpty()) {
                    if (keyword != activeKeyword || matches.isEmpty()) {
                        scheduleSearch(keyword, immediate = true, jumpAfterSearch = true)
                    } else {
                        jumpToCurrent()
                    }
                }
                true
            } else false
        }
    }

    private fun open() {
        if (!isOpen) {
            isOpen = true
            onSearchOpened()
        }
        searchPanel.visibility = View.VISIBLE
        searchInput.requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
    }

    fun close() {
        if (!isOpen && searchPanel.visibility != View.VISIBLE) return
        isOpen = false
        pendingSearch?.let(mainHandler::removeCallbacks)
        pendingSearch = null
        searchGeneration.incrementAndGet()
        searchPanel.visibility = View.GONE
        searchInput.text.clear()
        clearResults()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(searchInput.windowToken, 0)
        onSearchClosed()
    }

    fun destroy() {
        pendingSearch?.let(mainHandler::removeCallbacks)
        pendingSearch = null
        searchGeneration.incrementAndGet()
        searchExecutor.shutdownNow()
    }

    private fun clearResults() {
        allMessages = emptyList()
        matches = emptyList()
        currentMatchIndex = -1
        activeKeyword = ""
        activeTargetDate = null
        searchResults.removeAllViews()
        searchResultsScroll.visibility = View.GONE
        searchNavigation.visibility = View.GONE
    }

    private fun scheduleSearch(
        keyword: String,
        immediate: Boolean = false,
        jumpAfterSearch: Boolean = false
    ) {
        pendingSearch?.let(mainHandler::removeCallbacks)
        val runnable = Runnable { performSearchAsync(keyword, jumpAfterSearch) }
        pendingSearch = runnable
        mainHandler.postDelayed(runnable, if (immediate) 0L else 220L)
    }

    private fun performSearchAsync(keyword: String, jumpAfterSearch: Boolean) {
        val generation = searchGeneration.incrementAndGet()
        searchStatus.text = "正在查找…"
        searchNavigation.visibility = View.VISIBLE
        btnSearchPrev.isEnabled = false
        btnSearchNext.isEnabled = false
        btnSearchPrev.alpha = 0.35f
        btnSearchNext.alpha = 0.35f

        searchExecutor.execute {
            val messages = chatStorage.loadMessages(friendId)
            val targetDate = tryParseDate(keyword)
            val found = ArrayList<SearchMatch>()
            val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

            messages.forEachIndexed { index, msg ->
                if (msg.content == "[SEEN]") return@forEachIndexed
                val matched = if (targetDate != null) {
                    dateFmt.format(Date(msg.timestamp)) == targetDate
                } else {
                    msg.content.contains(keyword, ignoreCase = true) ||
                        msg.thinking.contains(keyword, ignoreCase = true)
                }
                if (matched) {
                    val source = when {
                        targetDate != null -> msg.content
                        msg.content.contains(keyword, ignoreCase = true) -> msg.content
                        else -> msg.thinking
                    }
                    found += SearchMatch(index, msg, source)
                }
            }

            mainHandler.post {
                if (generation != searchGeneration.get() || !isOpen) return@post
                allMessages = messages
                matches = found
                activeKeyword = keyword
                activeTargetDate = targetDate
                currentMatchIndex = if (found.isEmpty()) -1 else found.lastIndex
                renderResults()
                if (jumpAfterSearch && currentMatchIndex >= 0) jumpToCurrent()
            }
        }
    }

    private fun renderResults() {
        searchResults.removeAllViews()
        searchResultsScroll.visibility = View.VISIBLE
        searchNavigation.visibility = View.VISIBLE

        val count = matches.size
        if (count == 0) {
            searchStatus.text = if (activeTargetDate != null) "这天没有聊天记录" else "没有找到相关记录"
            btnSearchPrev.isEnabled = false
            btnSearchNext.isEnabled = false
            btnSearchPrev.alpha = 0.35f
            btnSearchNext.alpha = 0.35f
            searchResults.addView(TextView(context).apply {
                text = "换一个关键词试试"
                textSize = 12f
                setTextColor(c.tipText)
                setPadding(dp(4), dp(12), dp(4), dp(14))
            })
            return
        }

        searchStatus.text = "找到 $count 条 · ${currentMatchIndex + 1}/$count"
        btnSearchPrev.isEnabled = currentMatchIndex > 0
        btnSearchNext.isEnabled = currentMatchIndex < count - 1
        btnSearchPrev.alpha = if (btnSearchPrev.isEnabled) 1f else 0.35f
        btnSearchNext.alpha = if (btnSearchNext.isEnabled) 1f else 0.35f

        val maxShown = 50
        val start = when {
            count <= maxShown -> 0
            currentMatchIndex < maxShown / 2 -> 0
            currentMatchIndex > count - maxShown / 2 -> count - maxShown
            else -> currentMatchIndex - maxShown / 2
        }
        val end = minOf(count, start + maxShown)

        for (matchIndex in start until end) {
            val match = matches[matchIndex]
            val selected = matchIndex == currentMatchIndex
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(if (selected) c.accentBg else c.divider)
                    if (selected) setStroke(dp(1), c.accentStrong)
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(5) }
                setOnClickListener {
                    currentMatchIndex = matchIndex
                    renderResults()
                    jumpToCurrent()
                }
            }

            val header = TextView(context).apply {
                val role = when (match.message.role) {
                    "user" -> "我"
                    "assistant" -> friendName
                    else -> "归栖"
                }
                val time = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
                    .format(Date(match.message.timestamp))
                text = "$role · $time"
                textSize = 10f
                setTextColor(c.accentStrong)
            }

            val content = TextView(context).apply {
                text = buildHighlightedSnippet(match.displayText, activeKeyword, activeTargetDate != null)
                textSize = 13f
                setTextColor(c.textSecondary)
                setPadding(0, dp(4), 0, 0)
                maxLines = 3
            }

            card.addView(header)
            card.addView(content)
            searchResults.addView(card)
        }

        searchResults.post {
            val selectedChild = currentMatchIndex - start
            if (selectedChild in 0 until searchResults.childCount) {
                val child = searchResults.getChildAt(selectedChild)
                searchResultsScroll.smoothScrollTo(0, (child.top - dp(12)).coerceAtLeast(0))
            }
        }
    }

    private fun buildHighlightedSnippet(source: String, keyword: String, dateMode: Boolean): CharSequence {
        val safeSource = source.ifBlank { "（无文字内容）" }
        if (dateMode || keyword.isBlank()) {
            return if (safeSource.length > 150) safeSource.take(150) + "…" else safeSource
        }

        val index = safeSource.indexOf(keyword, ignoreCase = true).coerceAtLeast(0)
        val start = maxOf(0, index - 44)
        val end = minOf(safeSource.length, index + keyword.length + 56)
        val display = (if (start > 0) "…" else "") +
            safeSource.substring(start, end) +
            (if (end < safeSource.length) "…" else "")
        val spannable = SpannableString(display)
        var from = 0
        val lowerDisplay = display.lowercase(Locale.getDefault())
        val lowerKeyword = keyword.lowercase(Locale.getDefault())
        while (lowerKeyword.isNotEmpty()) {
            val found = lowerDisplay.indexOf(lowerKeyword, from)
            if (found < 0) break
            spannable.setSpan(
                ForegroundColorSpan(c.highlightColor),
                found,
                found + lowerKeyword.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            from = found + lowerKeyword.length
        }
        return spannable
    }

    private fun moveSelection(delta: Int) {
        if (matches.isEmpty()) return
        val next = (currentMatchIndex + delta).coerceIn(0, matches.lastIndex)
        if (next == currentMatchIndex) return
        currentMatchIndex = next
        renderResults()
        jumpToCurrent()
    }

    private fun jumpToCurrent() {
        val match = matches.getOrNull(currentMatchIndex) ?: return
        onJumpToResult(allMessages, match.sourceIndex)
    }

    private fun tryParseDate(input: String): String? {
        val cal = Calendar.getInstance()
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        when (input) {
            "今天" -> return fmt.format(cal.time)
            "昨天" -> { cal.add(Calendar.DAY_OF_MONTH, -1); return fmt.format(cal.time) }
            "前天" -> { cal.add(Calendar.DAY_OF_MONTH, -2); return fmt.format(cal.time) }
        }

        Regex("^(\\d{1,2})月(\\d{1,2})日?$").matchEntire(input)?.let {
            val (month, day) = it.destructured
            cal.set(Calendar.MONTH, month.toInt() - 1)
            cal.set(Calendar.DAY_OF_MONTH, day.toInt())
            return fmt.format(cal.time)
        }

        Regex("^(?:(\\d{4})[/-])?(\\d{1,2})[/-](\\d{1,2})$").matchEntire(input)?.let {
            val year = it.groupValues[1]
            if (year.isNotEmpty()) cal.set(Calendar.YEAR, year.toInt())
            cal.set(Calendar.MONTH, it.groupValues[2].toInt() - 1)
            cal.set(Calendar.DAY_OF_MONTH, it.groupValues[3].toInt())
            return fmt.format(cal.time)
        }
        return null
    }
}
