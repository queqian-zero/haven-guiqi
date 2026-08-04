package com.haven.guiqi

/**
 * 用户在本轮消息里直接附上网页链接时，归栖先读取网页，再把纯资料交给住户。
 *
 * 这里不规定住户如何理解、评价或使用资料；只负责提取链接、读取正文和报告结果。
 */
internal object DirectLinkReadSession {

    data class TraceEvent(
        val kind: String,
        val content: String
    )

    data class Resolution(
        val contextMessages: List<ChatMessage>,
        val traceEvents: List<TraceEvent>
    )

    private val urlPattern = Regex(
        "https?://[^\\s<>\\\"'`，。！？；：、（）【】《》)\\]}]+",
        RegexOption.IGNORE_CASE
    )

    fun resolve(
        friendName: String,
        baseContext: List<ChatMessage>,
        coordinator: SearchCoordinator,
        onStatus: ((String) -> Unit)? = null,
        maxLinks: Int = MAX_LINKS_PER_TURN
    ): Resolution {
        if (!coordinator.canReadPages()) return Resolution(emptyList(), emptyList())

        val urls = extractRecentUserUrls(baseContext, maxLinks)
        if (urls.isEmpty()) return Resolution(emptyList(), emptyList())

        val messages = mutableListOf<ChatMessage>()
        val traces = mutableListOf<TraceEvent>()
        var remainingChars = MAX_TOTAL_TEXT

        for ((index, url) in urls.withIndex()) {
            if (remainingChars <= 0) break
            onStatus?.invoke(
                if (urls.size == 1) "$friendName 正在读取你发来的网页…"
                else "$friendName 正在读取你发来的网页（${index + 1}/${urls.size}）…"
            )

            try {
                val page = coordinator.readPage(url)
                val pageLimit = minOf(MAX_TEXT_PER_PAGE, remainingChars)
                val body = page.text.take(pageLimit)
                remainingChars -= body.length

                messages += ChatMessage(
                    role = "user",
                    content = buildString {
                        append("[用户消息附带的网页资料]\n")
                        append("地址：").append(page.finalUrl).append('\n')
                        append("标题：").append(page.title).append('\n')
                        append("内容类型：")
                            .append(page.contentType.ifBlank { "未知" })
                            .append("\n\n")
                        append("[正文")
                        if (body.length < page.text.length) append("（已截取）")
                        append("]\n")
                        append(body)
                    }
                )
                traces += TraceEvent(
                    kind = "读取链接",
                    content = "$friendName 读取了用户消息里的网页「${page.title}」。"
                )
            } catch (e: Exception) {
                if (e is ApiRequestCancelledException) throw e
                val error = friendlyError(e)
                messages += ChatMessage(
                    role = "user",
                    content = buildString {
                        append("[用户消息附带的网页读取结果]\n")
                        append("地址：").append(url).append('\n')
                        append("状态：失败\n")
                        append("错误：").append(error)
                    }
                )
                traces += TraceEvent(
                    kind = "读取链接",
                    content = "$friendName 尝试读取用户消息里的网页，但读取失败：$error"
                )
            }
        }

        return Resolution(messages, traces)
    }

    /**
     * 只查看最近一段尚未收到住户回复的用户消息，避免旧链接每轮都被重复读取。
     */
    internal fun extractRecentUserUrls(
        context: List<ChatMessage>,
        maxLinks: Int = MAX_LINKS_PER_TURN
    ): List<String> {
        if (maxLinks <= 0) return emptyList()
        val lastAssistant = context.indexOfLast { it.role.equals("assistant", ignoreCase = true) }
        val recent = context.drop(lastAssistant + 1)
            .filter { it.role.equals("user", ignoreCase = true) }

        val seen = linkedSetOf<String>()
        for (message in recent) {
            for (match in urlPattern.findAll(message.content)) {
                val clean = cleanUrl(match.value)
                if (clean.isBlank()) continue
                if (seen.add(clean) && seen.size >= maxLinks) return seen.toList()
            }
        }
        return seen.toList()
    }

    private fun cleanUrl(raw: String): String {
        var value = raw.trim()
        while (value.isNotEmpty() && value.last() in TRAILING_PUNCTUATION) {
            value = value.dropLast(1)
        }
        return value
    }

    private fun friendlyError(error: Exception): String {
        val text = error.message.orEmpty().replace(Regex("\\s+"), " ").trim()
        return if (text.isBlank()) error.javaClass.simpleName else text.take(220)
    }

    private val TRAILING_PUNCTUATION = setOf(
        '.', ',', ';', ':', '!', '?',
        '。', '，', '；', '：', '！', '？', '、',
        ')', ']', '}', '）', '】', '》', '”', '’'
    )

    private const val MAX_LINKS_PER_TURN = 3
    private const val MAX_TEXT_PER_PAGE = 7_000
    private const val MAX_TOTAL_TEXT = 18_000
}
