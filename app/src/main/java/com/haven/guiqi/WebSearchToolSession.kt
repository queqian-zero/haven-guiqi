package com.haven.guiqi

/**
 * 联网搜索同轮工具会话。
 *
 * 住户输出 [WEB_SEARCH:关键词] 或 [WEB_READ:https://...] 后，归栖在本地执行，
 * 把真实结果作为隐藏工具消息送回住户，再让住户继续完成这一轮回复。
 */
internal object WebSearchToolSession {

    data class TraceEvent(
        val kind: String,
        val content: String
    )

    data class Resolution(
        val response: ApiResponse,
        val traceEvents: List<TraceEvent>,
        val continuationMessages: List<ChatMessage>
    )

    private sealed class Request {
        data class Search(
            val query: String,
            val page: Int,
            val sourceName: String?
        ) : Request()

        data class Read(val url: String) : Request()

        data class GitHubTree(val url: String) : Request()

        data class GitHubRead(
            val url: String,
            val startLine: Int,
            val endLine: Int?
        ) : Request()

        data class GitHubFind(
            val url: String,
            val query: String,
            val maxMatches: Int
        ) : Request()
    }

    private val searchPattern = Regex(
        "\\[WEB_SEARCH\\s*:\\s*([^\\]]+)]",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val readPattern = Regex(
        "\\[WEB_READ\\s*:\\s*(https?://[^\\]]+)]",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val githubTreePattern = Regex(
        "\\[GITHUB_TREE\\s*:\\s*(https?://[^\\]]+)]",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val githubReadPattern = Regex(
        "\\[GITHUB_READ\\s*:\\s*(https?://[^\\]]+)]",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val githubFindPattern = Regex(
        "\\[GITHUB_FIND\\s*:\\s*(https?://[^\\]]+)]",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    fun resolve(
        friendName: String,
        baseContext: List<ChatMessage>,
        firstResponse: ApiResponse,
        coordinator: SearchCoordinator,
        sendChat: (List<ChatMessage>) -> ApiResponse,
        onStatus: ((String) -> Unit)? = null,
        maxToolSteps: Int = 6
    ): Resolution {
        var currentResponse = firstResponse
        val loopMessages = mutableListOf<ChatMessage>()
        val traceEvents = mutableListOf<TraceEvent>()
        val thinkingParts = mutableListOf<String>()
        var usedTool = false
        var toolStep = 0

        fun addThinking(text: String) {
            val clean = text.trim()
            if (clean.isNotEmpty()) {
                thinkingParts += clean
                if (usedTool) traceEvents += TraceEvent("思考", clean)
            }
        }

        fun finish(): Resolution {
            val cleanText = stripMarkup(currentResponse.text)
            return if (usedTool) {
                Resolution(
                    response = currentResponse.copy(thinking = "", text = cleanText),
                    traceEvents = traceEvents.toList(),
                    continuationMessages = loopMessages.toList()
                )
            } else {
                Resolution(
                    response = currentResponse.copy(
                        thinking = thinkingParts.joinToString("\n\n"),
                        text = cleanText
                    ),
                    traceEvents = emptyList(),
                    continuationMessages = emptyList()
                )
            }
        }

        addThinking(firstResponse.thinking)

        while (toolStep < maxToolSteps) {
            val request = findFirstRequest(currentResponse.text) ?: break
            if (!usedTool) {
                usedTool = true
                // 第一段思考是在工具调用前产生的；工具会话开始后将它也放入真实轨迹。
                thinkingParts.forEach { traceEvents += TraceEvent("思考", it) }
            }

            val toolResult = when (request) {
                is Request.Search -> {
                    val sourceLabel = request.sourceName?.let { "（来源：$it）" }.orEmpty()
                    onStatus?.invoke("$friendName 正在搜索「${request.query.take(34)}」$sourceLabel…")
                    try {
                        val response = coordinator.search(
                            rawQuery = request.query,
                            page = request.page,
                            sourceName = request.sourceName
                        )
                        coordinator.recordSearchHistory(response)
                        traceEvents += TraceEvent(
                            "联网搜索",
                            buildString {
                                append(friendName).append("搜索了「").append(response.query).append("」")
                                if (response.page > 1) append("第 ").append(response.page).append(" 页")
                                append("，通过").append(response.provider)
                                append("得到 ").append(response.results.size).append(" 条结果。")
                                if (response.warnings.isNotEmpty()) {
                                    append(" 期间发生了自动切换：")
                                    append(response.warnings.joinToString("；").take(300))
                                }
                            }
                        )
                        buildSearchToolResult(response)
                    } catch (e: Exception) {
                        if (e is ApiRequestCancelledException) throw e
                        val message = friendlyError(e)
                        coordinator.recordSearchFailure(request.query, request.page, message)
                        traceEvents += TraceEvent(
                            "联网搜索",
                            "$friendName 尝试搜索「${request.query}」，但搜索失败：$message"
                        )
                        buildSearchFailureResult(request, message)
                    }
                }
                is Request.Read -> {
                    onStatus?.invoke("$friendName 正在阅读网页…")
                    try {
                        val page = coordinator.readPage(request.url)
                        traceEvents += TraceEvent(
                            "阅读网页",
                            "$friendName 阅读了「${page.title}」的网页正文。"
                        )
                        buildPageToolResult(page)
                    } catch (e: Exception) {
                        if (e is ApiRequestCancelledException) throw e
                        val message = friendlyError(e)
                        traceEvents += TraceEvent(
                            "阅读网页",
                            "$friendName 尝试读取网页，但失败了：$message"
                        )
                        buildPageFailureResult(request.url, message)
                    }
                }
                is Request.GitHubTree -> {
                    onStatus?.invoke("$friendName 正在查看 GitHub 目录…")
                    try {
                        val directory = coordinator.readGitHubDirectory(request.url)
                        traceEvents += TraceEvent(
                            "GitHub 目录",
                            "$friendName 查看了 ${directory.repository} 的 ${directory.path.ifBlank { "根目录" }}。"
                        )
                        buildGitHubDirectoryResult(directory)
                    } catch (e: Exception) {
                        if (e is ApiRequestCancelledException) throw e
                        val message = friendlyError(e)
                        traceEvents += TraceEvent("GitHub 目录", "$friendName 查看 GitHub 目录失败：$message")
                        buildGitHubFailureResult("目录读取", request.url, message)
                    }
                }
                is Request.GitHubRead -> {
                    onStatus?.invoke("$friendName 正在读取 GitHub 代码…")
                    try {
                        val file = coordinator.readGitHubFile(
                            rawUrl = request.url,
                            startLine = request.startLine,
                            endLine = request.endLine
                        )
                        traceEvents += TraceEvent(
                            "GitHub 代码",
                            "$friendName 阅读了 ${file.path} 第 ${file.startLine}-${file.endLine} 行。"
                        )
                        buildGitHubFileResult(file)
                    } catch (e: Exception) {
                        if (e is ApiRequestCancelledException) throw e
                        val message = friendlyError(e)
                        traceEvents += TraceEvent("GitHub 代码", "$friendName 读取 GitHub 文件失败：$message")
                        buildGitHubFailureResult("代码读取", request.url, message)
                    }
                }
                is Request.GitHubFind -> {
                    onStatus?.invoke("$friendName 正在 GitHub 文件内查找…")
                    try {
                        val result = coordinator.findInGitHubFile(
                            rawUrl = request.url,
                            rawQuery = request.query,
                            maxMatches = request.maxMatches
                        )
                        traceEvents += TraceEvent(
                            "GitHub 文件搜索",
                            "$friendName 在 ${result.path} 中查找了「${result.query}」，命中 ${result.matches.size} 处。"
                        )
                        buildGitHubFindResult(result)
                    } catch (e: Exception) {
                        if (e is ApiRequestCancelledException) throw e
                        val message = friendlyError(e)
                        traceEvents += TraceEvent("GitHub 文件搜索", "$friendName 在 GitHub 文件内查找失败：$message")
                        buildGitHubFailureResult("文件内搜索", request.url, message)
                    }
                }
            }

            loopMessages += ChatMessage("assistant", currentResponse.text)
            loopMessages += ChatMessage("user", toolResult)
            toolStep++
            currentResponse = sendChat(baseContext + loopMessages)
            addThinking(currentResponse.thinking)
        }

        if (findFirstRequest(currentResponse.text) != null) {
            traceEvents += TraceEvent(
                "联网搜索",
                "本轮联网工具调用达到安全上限，归栖停止了继续搜索，避免住户陷入循环。"
            )
        }
        return finish()
    }

    private fun findFirstRequest(text: String): Request? {
        val candidates = mutableListOf<Pair<Int, Request>>()
        searchPattern.find(text)?.let { candidates += it.range.first to parseSearch(it.groupValues[1]) }
        readPattern.find(text)?.let { candidates += it.range.first to Request.Read(it.groupValues[1].trim()) }
        githubTreePattern.find(text)?.let {
            candidates += it.range.first to Request.GitHubTree(it.groupValues[1].trim())
        }
        githubReadPattern.find(text)?.let {
            candidates += it.range.first to parseGitHubRead(it.groupValues[1])
        }
        githubFindPattern.find(text)?.let {
            candidates += it.range.first to parseGitHubFind(it.groupValues[1])
        }
        return candidates.minByOrNull { it.first }?.second
    }

    private fun parseGitHubRead(raw: String): Request.GitHubRead {
        val parts = raw.trim().split('|').map { it.trim() }.filter { it.isNotEmpty() }
        val url = parts.firstOrNull().orEmpty()
        var start = 1
        var end: Int? = null
        for (option in parts.drop(1)) {
            val pair = option.split('=', '：', limit = 2)
            if (pair.size < 2) continue
            when (pair[0].trim().uppercase()) {
                "LINES", "LINE", "行", "行号" -> {
                    val range = Regex("\\s*(\\d+)\\s*(?:-|~|—|–|至)\\s*(\\d+)\\s*")
                        .matchEntire(pair[1].trim())
                    if (range != null) {
                        start = range.groupValues[1].toIntOrNull() ?: start
                        end = range.groupValues[2].toIntOrNull()
                    } else {
                        start = pair[1].trim().toIntOrNull() ?: start
                    }
                }
                "START", "起始" -> start = pair[1].trim().toIntOrNull() ?: start
                "END", "结束" -> end = pair[1].trim().toIntOrNull() ?: end
            }
        }
        return Request.GitHubRead(url = url, startLine = start.coerceAtLeast(1), endLine = end)
    }

    private fun parseGitHubFind(raw: String): Request.GitHubFind {
        val parts = raw.trim().split('|').map { it.trim() }.filter { it.isNotEmpty() }
        val url = parts.firstOrNull().orEmpty()
        var query = ""
        var limit = 12
        for (option in parts.drop(1)) {
            val pair = option.split('=', '：', limit = 2)
            if (pair.size < 2) continue
            when (pair[0].trim().uppercase()) {
                "QUERY", "Q", "关键词", "查找" -> query = pair[1].trim()
                "LIMIT", "数量" -> limit = pair[1].trim().toIntOrNull() ?: limit
            }
        }
        return Request.GitHubFind(url = url, query = query, maxMatches = limit.coerceIn(1, 30))
    }

    private fun parseSearch(raw: String): Request.Search {
        val parts = raw.trim().split('|').map { it.trim() }.filter { it.isNotEmpty() }
        var query = parts.firstOrNull().orEmpty()
        var page = 1
        var sourceName: String? = null

        for (option in parts.drop(1)) {
            val pair = option.split('=', '：', ':', limit = 2)
            if (pair.size < 2) continue
            when (pair[0].trim().uppercase()) {
                "PAGE", "页", "页码" -> page = pair[1].trim().toIntOrNull() ?: page
                "SOURCE", "来源" -> sourceName = pair[1].trim().takeIf { it.isNotBlank() }
            }
        }

        // 兼容最早约定的 [WEB_SEARCH:关键词:2] 写法。
        if (parts.size == 1) {
            val oldPage = Regex("(?s)^(.*?):(\\d+)$").matchEntire(query)
            if (oldPage != null) {
                query = oldPage.groupValues[1].trim()
                page = oldPage.groupValues[2].toIntOrNull() ?: 1
            }
        }

        return Request.Search(
            query = query.trim(),
            page = page.coerceIn(1, 50),
            sourceName = sourceName
        )
    }

    private fun buildSearchToolResult(response: SearchCoordinator.SearchResponse): String = buildString {
        append("[联网搜索结果]\n")
        append("查询：").append(response.query).append('\n')
        append("页码：").append(response.page).append('\n')
        append("通道：").append(response.provider).append('\n')
        append("状态：").append(if (response.results.isEmpty()) "没有相关结果" else "成功").append('\n')

        if (response.results.isNotEmpty()) {
            append("\n[结果]\n")
            response.results.forEachIndexed { index, result ->
                append(index + 1).append(". ").append(result.title).append('\n')
                append("   来源：").append(result.source).append('\n')
                append("   链接：").append(result.url).append('\n')
                if (result.snippet.isNotBlank()) {
                    append("   摘要：").append(result.snippet).append('\n')
                }
            }
        }
    }.trim()

    private fun buildSearchFailureResult(request: Request.Search, error: String): String = buildString {
        append("[联网搜索结果]\n")
        append("查询：").append(request.query).append('\n')
        append("页码：").append(request.page).append('\n')
        append("状态：失败\n")
        append("错误：").append(error)
    }

    private fun buildPageToolResult(page: SearchCoordinator.PageResponse): String = buildString {
        append("[网页读取结果]\n")
        append("状态：成功\n")
        append("标题：").append(page.title).append('\n')
        append("最终地址：").append(page.finalUrl).append('\n')
        append("内容类型：").append(page.contentType.ifBlank { "未知" }).append("\n\n")
        append("[正文]\n")
        append(page.text)
    }.trim()

    private fun buildGitHubDirectoryResult(
        response: SearchCoordinator.GitHubDirectoryResponse
    ): String = buildString {
        append("[GitHub 目录读取结果]\n")
        append("状态：成功\n")
        append("仓库：").append(response.repository).append('\n')
        append("分支：").append(response.ref).append('\n')
        append("路径：").append(response.path.ifBlank { "/" }).append('\n')
        append("条目数：").append(response.totalEntries).append('\n')
        if (response.entries.size < response.totalEntries) {
            append("本次列出：").append(response.entries.size).append('\n')
        }
        append('\n')
        append("[目录与文件]\n")
        response.entries.forEach { entry ->
            val label = if (entry.type == "dir") "目录" else "文件"
            append("- ").append(label).append("：").append(entry.path)
            if (entry.type == "file" && entry.size >= 0) append("（").append(entry.size).append(" B）")
            if (entry.pageUrl.isNotBlank()) append("\n  页面：").append(entry.pageUrl)
            if (entry.rawUrl.isNotBlank()) append("\n  原始文件：").append(entry.rawUrl)
            append('\n')
        }
    }.trim()

    private fun buildGitHubFileResult(response: SearchCoordinator.GitHubFileResponse): String = buildString {
        append("[GitHub 代码读取结果]\n")
        append("状态：成功\n")
        append("仓库：").append(response.repository).append('\n')
        append("分支：").append(response.ref).append('\n')
        append("文件：").append(response.path).append('\n')
        append("总行数：").append(response.totalLines).append('\n')
        append("本次行号：").append(response.startLine).append('-').append(response.endLine).append('\n')
        if (response.endLine < response.totalLines) {
            append("下一段起始行：").append(response.endLine + 1).append('\n')
        }
        if (response.sourceClipped) append("文件状态：只下载到大小上限以内的部分\n")
        append("\n[代码]\n")
        append(response.numberedText)
    }.trim()

    private fun buildGitHubFindResult(response: SearchCoordinator.GitHubFindResponse): String = buildString {
        append("[GitHub 文件内搜索结果]\n")
        append("状态：成功\n")
        append("仓库：").append(response.repository).append('\n')
        append("分支：").append(response.ref).append('\n')
        append("文件：").append(response.path).append('\n')
        append("查询：").append(response.query).append('\n')
        append("命中：").append(response.matches.size).append("\n\n")
        response.matches.forEachIndexed { index, match ->
            append(index + 1).append(". 第 ").append(match.line).append(" 行\n")
            append(match.preview).append("\n\n")
        }
        if (response.sourceClipped) append("[只搜索到文件大小上限以内的部分]")
    }.trim()

    private fun buildGitHubFailureResult(kind: String, url: String, error: String): String = buildString {
        append("[GitHub ").append(kind).append("结果]\n")
        append("状态：失败\n")
        append("地址：").append(url).append('\n')
        append("错误：").append(error)
    }

    private fun buildPageFailureResult(url: String, error: String): String = buildString {
        append("[网页读取结果]\n")
        append("状态：失败\n")
        append("地址：").append(url).append('\n')
        append("错误：").append(error)
    }

    private fun stripMarkup(text: String): String {
        var clean = text
        listOf(searchPattern, readPattern, githubTreePattern, githubReadPattern, githubFindPattern)
            .forEach { pattern -> clean = pattern.replace(clean, "") }
        return clean.replace(Regex("\n{3,}"), "\n\n").trim()
    }

    private fun friendlyError(error: Exception): String {
        val text = error.message.orEmpty().replace(Regex("\\s+"), " ").trim()
        return if (text.isBlank()) error.javaClass.simpleName else text.take(220)
    }
}
