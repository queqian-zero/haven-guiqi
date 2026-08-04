package com.haven.guiqi

import android.content.Context
import android.text.Html
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.Locale

/**
 * 归栖自己的联网搜索执行器。
 *
 * 它完全独立于住户正在使用的 OpenAI / Claude / Gemini / 中转站接口：
 * 模型只输出文字工具指令，真正的网络请求由 Android App 在这里执行。
 */
class SearchCoordinator(
    context: Context,
    private val friendId: String
) {

    data class SearchResult(
        val title: String,
        val url: String,
        val snippet: String,
        val source: String
    )

    data class SearchResponse(
        val query: String,
        val page: Int,
        val results: List<SearchResult>,
        val provider: String,
        val warnings: List<String> = emptyList()
    )

    data class PageResponse(
        val requestedUrl: String,
        val finalUrl: String,
        val title: String,
        val text: String,
        val contentType: String
    )

    data class GitHubDirectoryEntry(
        val type: String,
        val name: String,
        val path: String,
        val size: Long,
        val pageUrl: String,
        val rawUrl: String
    )

    data class GitHubDirectoryResponse(
        val requestedUrl: String,
        val repository: String,
        val ref: String,
        val path: String,
        val entries: List<GitHubDirectoryEntry>,
        val totalEntries: Int
    )

    data class GitHubFileResponse(
        val requestedUrl: String,
        val finalUrl: String,
        val repository: String,
        val ref: String,
        val path: String,
        val startLine: Int,
        val endLine: Int,
        val totalLines: Int,
        val numberedText: String,
        val sourceClipped: Boolean
    )

    data class GitHubFileMatch(
        val line: Int,
        val preview: String
    )

    data class GitHubFindResponse(
        val requestedUrl: String,
        val repository: String,
        val ref: String,
        val path: String,
        val query: String,
        val totalLines: Int,
        val matches: List<GitHubFileMatch>,
        val sourceClipped: Boolean
    )

    private data class HttpPayload(
        val body: String,
        val finalUrl: String,
        val contentType: String,
        val truncated: Boolean
    )

    private data class ProviderAttempt(
        val name: String,
        val results: List<SearchResult>
    )

    private val appContext = context.applicationContext
    private val storage = SearchGroupStorage(appContext)

    @Volatile
    private var cancelled = false

    @Volatile
    private var activeConnection: HttpURLConnection? = null

    fun cancel() {
        cancelled = true
        runCatching { activeConnection?.disconnect() }
    }

    fun search(
        rawQuery: String,
        page: Int = 1,
        sourceName: String? = null
    ): SearchResponse {
        ensureActive()
        val query = rawQuery.trim().take(MAX_QUERY_LENGTH)
        require(query.isNotBlank()) { "搜索关键词不能为空" }
        require(storage.isSearchAllowed(friendId)) { "当前住户没有可用的联网搜索权限" }

        val safePage = page.coerceIn(1, MAX_PAGE)
        val permission = storage.loadResidentPermission(friendId)
        val warnings = mutableListOf<String>()

        if (!sourceName.isNullOrBlank()) {
            if (!permission.useAllSources) {
                throw IllegalStateException("当前住户没有使用网站来源的权限")
            }
            val source = storage.loadSources().firstOrNull {
                it.enabled && it.name.equals(sourceName.trim(), ignoreCase = true)
            } ?: throw IllegalArgumentException("没有找到已启用的网站来源：${sourceName.trim()}")

            if (source.requiresLogin) {
                val privacy = storage.loadPrivacy()
                if (!permission.allowLogin || !privacy.allowLoginState) {
                    throw IllegalStateException("来源「${source.name}」需要登录态，但当前权限不允许")
                }
                throw IllegalStateException("来源「${source.name}」需要登录态；当前版本尚未接入 Cookie 登录态")
            }

            val attempt = searchWebsiteSource(source, query, safePage, warnings)
            val relevant = keepRelevantResults(query, attempt.results)
            if (attempt.results.isNotEmpty() && relevant.isEmpty()) {
                warnings += "来源「${source.name}」返回的内容与查询无关，已忽略"
            }
            return SearchResponse(
                query = query,
                page = safePage,
                results = deduplicate(relevant).take(SearchGroupStorage.RESULTS_PER_BATCH),
                provider = attempt.name,
                warnings = warnings
            )
        }

        val configured = if (permission.useAllServices) {
            storage.loadServices().filter { it.enabled && it.endpoint.isNotBlank() }
        } else {
            // 当前数据层尚未保存逐项服务白名单；在补齐选择 UI 前，关闭“全部服务”时
            // 不擅自调用任何自定义服务，仍可回退到内置通道。
            emptyList()
        }

        for (service in configured) {
            ensureActive()
            try {
                val results = searchService(service, query, safePage)
                val relevant = keepRelevantResults(query, results)
                if (relevant.isNotEmpty()) {
                    return SearchResponse(
                        query = query,
                        page = safePage,
                        results = deduplicate(relevant).take(SearchGroupStorage.RESULTS_PER_BATCH),
                        provider = service.name.ifBlank { serviceTypeLabel(service.type) },
                        warnings = warnings
                    )
                }
                warnings += if (results.isEmpty()) {
                    "${service.name.ifBlank { serviceTypeLabel(service.type) }}没有返回结果"
                } else {
                    "${service.name.ifBlank { serviceTypeLabel(service.type) }}返回的内容与查询无关，已忽略"
                }
            } catch (e: Exception) {
                ensureActive()
                warnings += "${service.name.ifBlank { serviceTypeLabel(service.type) }}不可用：${friendlyError(e)}"
            }
        }

        if (storage.loadGeneral().useDefaultProvider) {
            val defaultAttempt = searchDefault(query, safePage, warnings)
            return SearchResponse(
                query = query,
                page = safePage,
                results = deduplicate(defaultAttempt.results).take(SearchGroupStorage.RESULTS_PER_BATCH),
                provider = defaultAttempt.name,
                warnings = warnings
            )
        }

        throw IllegalStateException(
            if (warnings.isEmpty()) "没有可用搜索通道" else warnings.joinToString("；")
        )
    }

    fun canReadPages(): Boolean {
        val permission = storage.loadResidentPermission(friendId)
        return storage.isSearchAllowed(friendId) && permission.allowFullText
    }

    fun readPage(rawUrl: String): PageResponse {
        ensureActive()
        val permission = storage.loadResidentPermission(friendId)
        if (!storage.isSearchAllowed(friendId) || !permission.allowFullText) {
            throw IllegalStateException("当前住户没有读取网页正文的权限")
        }

        val url = rawUrl.trim().take(MAX_URL_LENGTH)
        require(isSafePublicHttpUrl(url)) { "只允许读取公开的 HTTP/HTTPS 网页，不能访问本机或局域网地址" }

        // GitHub 仓库主页用公开 REST API 读取。这样住户拿到的不只是网页上碰巧
        // 展开的 README，还能同时看到仓库资料、默认分支和顶层文件树。
        // API 不可用（限流、仓库不存在等）时自动退回普通网页读取。
        parseGitHubRepositoryUrl(url)?.let { repository ->
            try {
                return readGitHubRepository(url, repository)
            } catch (e: Exception) {
                if (e is ApiRequestCancelledException) throw e
            }
        }

        val payload = request(url, maxBytes = MAX_PAGE_BYTES, requirePublicUrl = true)
        require(isSafePublicHttpUrl(payload.finalUrl)) { "网页跳转到了不允许访问的地址" }

        val lowerType = payload.contentType.lowercase(Locale.ROOT)
        if (lowerType.contains("pdf") || payload.finalUrl.lowercase(Locale.ROOT).endsWith(".pdf")) {
            throw IllegalArgumentException("当前版本暂不解析 PDF 正文")
        }

        val title: String
        val text: String
        when {
            lowerType.contains("html") || payload.body.contains("<html", ignoreCase = true) -> {
                title = extractHtmlTitle(payload.body)
                text = htmlToReadableText(payload.body)
            }
            lowerType.contains("json") || payload.body.trimStart().startsWith("{") ||
                payload.body.trimStart().startsWith("[") -> {
                title = "JSON 资料"
                text = payload.body.trim()
            }
            else -> {
                title = "网页资料"
                text = cleanText(payload.body)
            }
        }

        val clipped = text
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
            .take(MAX_PAGE_TEXT)

        if (clipped.isBlank()) throw IllegalStateException("网页可以访问，但没有提取到可读正文")
        return PageResponse(
            requestedUrl = url,
            finalUrl = payload.finalUrl,
            title = title.ifBlank { "网页资料" }.take(240),
            text = clipped,
            contentType = payload.contentType
        )
    }

    private data class GitHubRepositoryRef(
        val owner: String,
        val repository: String
    )

    private data class GitHubRootEntry(
        val type: String,
        val name: String,
        val path: String,
        val size: Long,
        val htmlUrl: String,
        val downloadUrl: String
    )

    private enum class GitHubResourceKind { REPOSITORY, DIRECTORY, FILE }

    private data class GitHubResourceRef(
        val owner: String,
        val repository: String,
        val ref: String?,
        val path: String,
        val kind: GitHubResourceKind,
        val rawUrl: String = ""
    )

    private data class GitHubTextFile(
        val target: GitHubResourceRef,
        val finalUrl: String,
        val lines: List<String>,
        val sourceClipped: Boolean
    )

    /**
     * 逐层读取 GitHub 公开仓库目录。仓库主页表示根目录，tree 链接表示具体目录。
     */
    fun readGitHubDirectory(rawUrl: String): GitHubDirectoryResponse {
        ensureGitHubToolPermission()
        val requestedUrl = rawUrl.trim().take(MAX_URL_LENGTH)
        val target = parseGitHubResourceUrl(requestedUrl)
            ?: throw IllegalArgumentException("只支持 GitHub 公开仓库主页或 tree 目录链接")
        require(target.kind != GitHubResourceKind.FILE) { "这个链接指向文件，不是目录" }

        val metadata = loadGitHubMetadata(target.owner, target.repository)
        val ref = target.ref?.takeIf { it.isNotBlank() } ?: metadata.optString("default_branch").ifBlank { "main" }
        val apiBase = githubApiBase(target.owner, target.repository)
        val encodedPath = target.path.trim('/').takeIf { it.isNotBlank() }
            ?.let { "/${encodePathSegment(it)}" }.orEmpty()
        val contentsUrl = addQueryParamIfMissing("$apiBase/contents$encodedPath", "ref", ref)
        val payload = request(
            url = contentsUrl,
            headers = githubHeaders(),
            maxBytes = GITHUB_TREE_BYTES,
            requirePublicUrl = true
        )

        val rawEntries = parseGitHubRootEntries(payload.body)
        val entries = rawEntries.take(MAX_GITHUB_DIRECTORY_ENTRIES).map { entry ->
            GitHubDirectoryEntry(
                type = entry.type,
                name = entry.name,
                path = entry.path,
                size = entry.size,
                pageUrl = entry.htmlUrl,
                rawUrl = entry.downloadUrl
            )
        }
        return GitHubDirectoryResponse(
            requestedUrl = requestedUrl,
            repository = metadata.optString("full_name").ifBlank { "${target.owner}/${target.repository}" },
            ref = ref,
            path = target.path.trim('/'),
            entries = entries,
            totalEntries = rawEntries.size
        )
    }

    /**
     * 按行读取 GitHub 公开代码文件。每次只把需要的行交给住户，文件本身可继续翻页。
     */
    fun readGitHubFile(
        rawUrl: String,
        startLine: Int = 1,
        endLine: Int? = null
    ): GitHubFileResponse {
        ensureGitHubToolPermission()
        val requestedUrl = rawUrl.trim().take(MAX_URL_LENGTH)
        val file = loadGitHubTextFile(requestedUrl)
        val totalLines = file.lines.size.coerceAtLeast(1)
        require(startLine <= totalLines) { "起始行 $startLine 超过文件总行数 $totalLines" }
        val safeStart = startLine.coerceAtLeast(1)
        val requestedEnd = endLine ?: (safeStart + DEFAULT_GITHUB_LINE_BATCH - 1)
        val safeEnd = requestedEnd
            .coerceAtLeast(safeStart)
            .coerceAtMost(totalLines)
            .coerceAtMost(safeStart + MAX_GITHUB_LINES_PER_READ - 1)

        val builder = StringBuilder()
        var actualEnd = safeStart - 1
        for (lineNumber in safeStart..safeEnd) {
            val line = file.lines.getOrElse(lineNumber - 1) { "" }
            val rendered = "$lineNumber | $line\n"
            if (builder.isNotEmpty() && builder.length + rendered.length > MAX_GITHUB_CODE_TEXT) break
            builder.append(rendered)
            actualEnd = lineNumber
        }
        if (actualEnd < safeStart) {
            val line = file.lines.getOrElse(safeStart - 1) { "" }
            builder.append(safeStart).append(" | ").append(line.take(MAX_GITHUB_CODE_TEXT)).append('\n')
            actualEnd = safeStart
        }

        val target = file.target
        return GitHubFileResponse(
            requestedUrl = requestedUrl,
            finalUrl = file.finalUrl,
            repository = "${target.owner}/${target.repository}",
            ref = target.ref.orEmpty(),
            path = target.path,
            startLine = safeStart,
            endLine = actualEnd,
            totalLines = totalLines,
            numberedText = builder.toString().trimEnd(),
            sourceClipped = file.sourceClipped
        )
    }

    /**
     * 在一个 GitHub 公开文本文件中做字面量搜索，返回命中行和少量上下文。
     */
    fun findInGitHubFile(
        rawUrl: String,
        rawQuery: String,
        maxMatches: Int = DEFAULT_GITHUB_FIND_MATCHES
    ): GitHubFindResponse {
        ensureGitHubToolPermission()
        val requestedUrl = rawUrl.trim().take(MAX_URL_LENGTH)
        val query = rawQuery.trim().take(MAX_GITHUB_FIND_QUERY)
        require(query.isNotBlank()) { "文件内搜索词不能为空" }
        val file = loadGitHubTextFile(requestedUrl)
        val normalized = query.lowercase(Locale.ROOT)
        val limit = maxMatches.coerceIn(1, MAX_GITHUB_FIND_MATCHES)
        val matches = mutableListOf<GitHubFileMatch>()

        for (index in file.lines.indices) {
            ensureActive()
            val line = file.lines[index]
            if (!line.lowercase(Locale.ROOT).contains(normalized)) continue
            val from = (index - GITHUB_FIND_CONTEXT_LINES).coerceAtLeast(0)
            val to = (index + GITHUB_FIND_CONTEXT_LINES).coerceAtMost(file.lines.lastIndex)
            val preview = buildString {
                for (contextIndex in from..to) {
                    append(contextIndex + 1).append(" | ")
                        .append(file.lines[contextIndex].take(MAX_GITHUB_PREVIEW_LINE))
                        .append('\n')
                }
            }.trimEnd()
            matches += GitHubFileMatch(index + 1, preview)
            if (matches.size >= limit) break
        }

        val target = file.target
        return GitHubFindResponse(
            requestedUrl = requestedUrl,
            repository = "${target.owner}/${target.repository}",
            ref = target.ref.orEmpty(),
            path = target.path,
            query = query,
            totalLines = file.lines.size.coerceAtLeast(1),
            matches = matches,
            sourceClipped = file.sourceClipped
        )
    }

    private fun ensureGitHubToolPermission() {
        val permission = storage.loadResidentPermission(friendId)
        if (!storage.isSearchAllowed(friendId) || !permission.allowFullText) {
            throw IllegalStateException("当前住户没有读取网页正文的权限")
        }
    }

    private fun loadGitHubMetadata(owner: String, repository: String): JSONObject {
        val payload = request(
            url = githubApiBase(owner, repository),
            headers = githubHeaders(),
            maxBytes = GITHUB_METADATA_BYTES,
            requirePublicUrl = true
        )
        return JSONObject(payload.body)
    }

    private fun githubApiBase(owner: String, repository: String): String =
        "https://api.github.com/repos/${encodePathSegment(owner)}/${encodePathSegment(repository)}"

    private fun githubHeaders(): Map<String, String> = linkedMapOf(
        "Accept" to "application/vnd.github+json",
        "X-GitHub-Api-Version" to GITHUB_API_VERSION
    )

    private fun parseGitHubResourceUrl(rawUrl: String): GitHubResourceRef? = runCatching {
        val uri = URI(rawUrl)
        val host = uri.host.orEmpty().lowercase(Locale.ROOT)
        val parts = uri.path.orEmpty().trim('/').split('/').filter { it.isNotBlank() }

        when (host) {
            "github.com", "www.github.com" -> {
                if (parts.size < 2) return@runCatching null
                val owner = decodePathPart(parts[0])
                val repository = decodePathPart(parts[1]).removeSuffix(".git")
                if (!GITHUB_NAME_PATTERN.matches(owner) || !GITHUB_NAME_PATTERN.matches(repository)) {
                    return@runCatching null
                }
                when {
                    parts.size == 2 -> GitHubResourceRef(
                        owner, repository, null, "", GitHubResourceKind.REPOSITORY
                    )
                    parts.size >= 4 && parts[2].equals("tree", true) -> GitHubResourceRef(
                        owner = owner,
                        repository = repository,
                        ref = decodePathPart(parts[3]),
                        path = parts.drop(4).joinToString("/") { decodePathPart(it) },
                        kind = GitHubResourceKind.DIRECTORY
                    )
                    parts.size >= 5 && (parts[2].equals("blob", true) || parts[2].equals("raw", true)) -> {
                        val ref = decodePathPart(parts[3])
                        val path = parts.drop(4).joinToString("/") { decodePathPart(it) }
                        GitHubResourceRef(
                            owner = owner,
                            repository = repository,
                            ref = ref,
                            path = path,
                            kind = GitHubResourceKind.FILE,
                            rawUrl = buildGitHubRawUrl(owner, repository, ref, path)
                        )
                    }
                    else -> null
                }
            }
            "raw.githubusercontent.com" -> {
                if (parts.size < 4) return@runCatching null
                val owner = decodePathPart(parts[0])
                val repository = decodePathPart(parts[1]).removeSuffix(".git")
                val ref = decodePathPart(parts[2])
                val path = parts.drop(3).joinToString("/") { decodePathPart(it) }
                if (!GITHUB_NAME_PATTERN.matches(owner) || !GITHUB_NAME_PATTERN.matches(repository) || path.isBlank()) {
                    return@runCatching null
                }
                GitHubResourceRef(
                    owner = owner,
                    repository = repository,
                    ref = ref,
                    path = path,
                    kind = GitHubResourceKind.FILE,
                    rawUrl = rawUrl
                )
            }
            else -> null
        }
    }.getOrNull()

    private fun loadGitHubTextFile(rawUrl: String): GitHubTextFile {
        val target = parseGitHubResourceUrl(rawUrl)
            ?: throw IllegalArgumentException("只支持 GitHub blob/raw 文件链接")
        require(target.kind == GitHubResourceKind.FILE && target.path.isNotBlank()) {
            "这个链接没有指向具体代码文件"
        }
        val fileUrl = target.rawUrl.ifBlank {
            buildGitHubRawUrl(target.owner, target.repository, target.ref.orEmpty(), target.path)
        }
        require(isSafePublicHttpUrl(fileUrl)) { "GitHub 文件地址不安全" }
        val payload = request(
            url = fileUrl,
            headers = mapOf("Accept" to "text/plain, application/octet-stream;q=0.9, */*;q=0.5"),
            maxBytes = MAX_GITHUB_FILE_BYTES,
            requirePublicUrl = true
        )
        require(isProbablyText(payload.body, payload.contentType)) { "这个 GitHub 文件不是可阅读的文本代码" }
        val normalized = payload.body
            .removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        val lines = normalized.split('\n')
        val sourceClipped = payload.truncated
        return GitHubTextFile(target, payload.finalUrl, lines, sourceClipped)
    }

    private fun isProbablyText(body: String, contentType: String): Boolean {
        if (body.indexOf('\u0000') >= 0) return false
        val lowerType = contentType.lowercase(Locale.ROOT)
        if (lowerType.startsWith("image/") || lowerType.startsWith("audio/") || lowerType.startsWith("video/")) {
            return false
        }
        if (body.isBlank()) return true
        val sample = body.take(4096)
        val controls = sample.count { it.code in 0..8 || it.code in 14..31 }
        return controls * 20 <= sample.length.coerceAtLeast(1)
    }

    private fun buildGitHubRawUrl(owner: String, repository: String, ref: String, path: String): String =
        "https://raw.githubusercontent.com/${encodePathSegment(owner)}/${encodePathSegment(repository)}/" +
            "${encodePathSegment(ref)}/${encodePathSegment(path)}"

    private fun decodePathPart(value: String): String = runCatching {
        URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
    }.getOrDefault(value)

    /**
     * 只把 github.com/{owner}/{repo} 这种仓库主页识别为仓库。
     * blob、tree、issues、releases 等页面仍交给普通网页读取，避免擅自改写链接含义。
     */
    private fun parseGitHubRepositoryUrl(rawUrl: String): GitHubRepositoryRef? = runCatching {
        val uri = URI(rawUrl)
        val host = uri.host.orEmpty().lowercase(Locale.ROOT)
        if (host != "github.com" && host != "www.github.com") return@runCatching null
        val parts = uri.path.orEmpty().trim('/').split('/').filter { it.isNotBlank() }
        if (parts.size != 2) return@runCatching null
        val owner = parts[0]
        val repository = parts[1].removeSuffix(".git")
        if (!GITHUB_NAME_PATTERN.matches(owner) || !GITHUB_NAME_PATTERN.matches(repository)) {
            return@runCatching null
        }
        GitHubRepositoryRef(owner, repository)
    }.getOrNull()

    private fun readGitHubRepository(
        requestedUrl: String,
        repository: GitHubRepositoryRef
    ): PageResponse {
        ensureActive()
        val apiBase = githubApiBase(repository.owner, repository.repository)
        val headers = githubHeaders()

        val metadataPayload = request(
            url = apiBase,
            headers = headers,
            maxBytes = GITHUB_METADATA_BYTES,
            requirePublicUrl = true
        )
        val metadata = JSONObject(metadataPayload.body)
        val fullName = metadata.optString("full_name")
            .ifBlank { "${repository.owner}/${repository.repository}" }
        val canonicalUrl = metadata.optString("html_url")
            .ifBlank { "https://github.com/${encodePathSegment(repository.owner)}/${encodePathSegment(repository.repository)}" }
        val defaultBranch = metadata.optString("default_branch").ifBlank { "main" }

        val treeEntries = runCatching {
            val rootUrl = addQueryParamIfMissing(
                "$apiBase/contents",
                "ref",
                defaultBranch
            )
            val rootPayload = request(
                url = rootUrl,
                headers = headers,
                maxBytes = GITHUB_TREE_BYTES,
                requirePublicUrl = true
            )
            parseGitHubRootEntries(rootPayload.body)
        }.getOrElse { error ->
            if (error is ApiRequestCancelledException) throw error
            emptyList()
        }

        val readme = runCatching {
            val readmeUrl = addQueryParamIfMissing(
                "$apiBase/readme",
                "ref",
                defaultBranch
            )
            val readmePayload = request(
                url = readmeUrl,
                headers = headers,
                maxBytes = GITHUB_README_API_BYTES,
                requirePublicUrl = true
            )
            decodeGitHubReadme(JSONObject(readmePayload.body))
        }.getOrElse { error ->
            if (error is ApiRequestCancelledException) throw error
            null
        }

        val text = buildString {
            append("[GitHub 公开仓库]\n")
            append("仓库：").append(fullName).append('\n')
            append("地址：").append(canonicalUrl).append('\n')
            append("默认分支：").append(defaultBranch).append('\n')
            metadata.optString("description").takeIf { it.isNotBlank() }?.let {
                append("简介：").append(cleanText(it)).append('\n')
            }
            metadata.optString("language").takeIf { it.isNotBlank() }?.let {
                append("主要语言：").append(it).append('\n')
            }
            metadata.optJSONObject("license")?.let { license ->
                val label = license.optString("name")
                    .ifBlank { license.optString("spdx_id") }
                if (label.isNotBlank() && !label.equals("NOASSERTION", true)) {
                    append("许可证：").append(label).append('\n')
                }
            }
            metadata.optString("homepage").takeIf { it.isNotBlank() }?.let {
                append("主页：").append(it).append('\n')
            }
            append("公开状态：")
                .append(if (metadata.optBoolean("archived")) "已归档" else "正常")
                .append(if (metadata.optBoolean("fork")) " · Fork" else "")
                .append('\n')
            append("Stars：").append(metadata.optInt("stargazers_count"))
                .append(" · Forks：").append(metadata.optInt("forks_count"))
                .append(" · Open issues：").append(metadata.optInt("open_issues_count"))
                .append("\n\n")

            append("[顶层目录与文件]\n")
            if (treeEntries.isEmpty()) {
                append("未取得顶层文件树。\n")
            } else {
                treeEntries.take(MAX_GITHUB_ROOT_ENTRIES).forEach { entry ->
                    val marker = when (entry.type) {
                        "dir" -> "目录"
                        "file" -> "文件"
                        "symlink" -> "链接"
                        "submodule" -> "子模块"
                        else -> entry.type.ifBlank { "条目" }
                    }
                    append("- ").append(marker).append("：").append(entry.path)
                    if (entry.type == "file" && entry.size >= 0) {
                        append("（").append(formatFileSize(entry.size)).append("）")
                    }
                    if (entry.htmlUrl.isNotBlank()) {
                        append("\n  页面：").append(entry.htmlUrl)
                    }
                    if (entry.type == "file" && entry.downloadUrl.isNotBlank()) {
                        append("\n  原始文件：").append(entry.downloadUrl)
                    }
                    append('\n')
                }
                if (treeEntries.size > MAX_GITHUB_ROOT_ENTRIES) {
                    append("- 其余 ")
                        .append(treeEntries.size - MAX_GITHUB_ROOT_ENTRIES)
                        .append(" 个顶层条目未展开。\n")
                }
            }

            append("\n[README]\n")
            if (readme == null || readme.second.isBlank()) {
                append("仓库没有可读取的 README，或 README 暂时无法取得。")
            } else {
                append("文件：").append(readme.first).append("\n\n")
                append(readme.second.take(MAX_GITHUB_README_TEXT))
                if (readme.second.length > MAX_GITHUB_README_TEXT) {
                    append("\n\n[README 已截取]")
                }
            }
        }.replace(Regex("\\n{3,}"), "\n\n")
            .trim()
            .take(MAX_PAGE_TEXT)

        return PageResponse(
            requestedUrl = requestedUrl,
            finalUrl = canonicalUrl,
            title = "$fullName · GitHub 仓库",
            text = text,
            contentType = "application/vnd.github+json; repository-summary"
        )
    }

    private fun parseGitHubRootEntries(body: String): List<GitHubRootEntry> {
        val array = JSONArray(body)
        val entries = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val name = item.optString("name")
                val path = item.optString("path").ifBlank { name }
                if (name.isBlank() || path.isBlank()) continue
                add(
                    GitHubRootEntry(
                        type = item.optString("type"),
                        name = name,
                        path = path,
                        size = item.optLong("size", -1L),
                        htmlUrl = item.optString("html_url"),
                        downloadUrl = item.optString("download_url")
                    )
                )
            }
        }
        return entries.sortedWith(
            compareBy<GitHubRootEntry> { if (it.type == "dir") 0 else 1 }
                .thenBy { it.name.lowercase(Locale.ROOT) }
        )
    }

    private fun decodeGitHubReadme(json: JSONObject): Pair<String, String>? {
        val encoded = json.optString("content")
            .replace("\n", "")
            .replace("\r", "")
            .trim()
        if (encoded.isBlank()) return null
        val bytes = Base64.decode(encoded, Base64.DEFAULT)
        val text = bytes.toString(Charsets.UTF_8)
            .removePrefix("\uFEFF")
            .trim()
        if (text.isBlank()) return null
        return json.optString("name").ifBlank { "README" } to text
    }

    private fun formatFileSize(bytes: Long): String = when {
        bytes < 0 -> "未知大小"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
        else -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
    }

    fun enabledSourceNames(): List<String> = storage.loadSources()
        .filter { it.enabled && it.name.isNotBlank() && it.value.isNotBlank() }
        .map { it.name }
        .distinct()
        .take(12)

    fun recordSearchHistory(response: SearchResponse) {
        storage.appendSearchHistory(
            SearchGroupStorage.SearchHistoryEntry(
                friendId = friendId,
                query = response.query,
                page = response.page,
                provider = response.provider,
                resultCount = response.results.size,
                success = true
            )
        )
    }

    fun recordSearchFailure(query: String, page: Int, error: String) {
        storage.appendSearchHistory(
            SearchGroupStorage.SearchHistoryEntry(
                friendId = friendId,
                query = query.trim().take(MAX_QUERY_LENGTH),
                page = page.coerceIn(1, MAX_PAGE),
                success = false,
                error = error.take(400)
            )
        )
    }

    private fun searchWebsiteSource(
        source: SearchGroupStorage.WebsiteSource,
        query: String,
        page: Int,
        warnings: MutableList<String>
    ): ProviderAttempt {
        return when (source.type) {
            SearchGroupStorage.SOURCE_DOMAIN -> {
                val domain = source.value
                    .removePrefix("https://")
                    .removePrefix("http://")
                    .substringBefore('/')
                    .trim()
                require(domain.isNotBlank()) { "来源「${source.name}」没有有效域名" }
                val scoped = "$query site:$domain"
                val permission = storage.loadResidentPermission(friendId)
                val configured = if (permission.useAllServices) {
                    storage.loadServices().filter { it.enabled && it.endpoint.isNotBlank() }
                } else {
                    emptyList()
                }
                for (service in configured) {
                    try {
                        val found = searchService(service, scoped, page)
                        if (found.isNotEmpty()) {
                            return ProviderAttempt("${source.name} · ${service.name.ifBlank { serviceTypeLabel(service.type) }}", found)
                        }
                    } catch (e: Exception) {
                        warnings += "${service.name.ifBlank { serviceTypeLabel(service.type) }}不可用：${friendlyError(e)}"
                    }
                }
                if (storage.loadGeneral().useDefaultProvider) {
                    val fallback = searchDefault(scoped, page, warnings)
                    ProviderAttempt("${source.name} · ${fallback.name}", fallback.results)
                } else {
                    ProviderAttempt(source.name, emptyList())
                }
            }
            SearchGroupStorage.SOURCE_SEARCH_URL,
            SearchGroupStorage.SOURCE_RSS_API -> {
                val url = expandTemplate(source.value, query, page, "")
                val payload = request(url, maxBytes = MAX_SEARCH_BYTES)
                ProviderAttempt(
                    source.name.ifBlank { "网站来源" },
                    parsePayload(payload, source.name.ifBlank { "网站来源" })
                )
            }
            else -> ProviderAttempt(source.name.ifBlank { "网站来源" }, emptyList())
        }
    }

    private fun searchService(
        service: SearchGroupStorage.SearchService,
        query: String,
        page: Int
    ): List<SearchResult> {
        return when (service.type) {
            SearchGroupStorage.TYPE_SEARXNG -> searchSearxng(service, query, page)
            SearchGroupStorage.TYPE_GENERIC_API -> searchGenericApi(service, query, page)
            SearchGroupStorage.TYPE_URL_TEMPLATE -> searchUrlTemplate(service, query, page)
            else -> emptyList()
        }
    }

    private fun searchSearxng(
        service: SearchGroupStorage.SearchService,
        query: String,
        page: Int
    ): List<SearchResult> {
        val endpoint = service.endpoint.trim()
        val target = if (endpoint.contains("{query}") || endpoint.contains("{q}") || endpoint.contains("%s")) {
            addQueryParamIfMissing(
                addQueryParamIfMissing(expandTemplate(endpoint, query, page, service.apiKey), "format", "json"),
                "pageno",
                page.toString()
            )
        } else {
            val base = endpoint.trimEnd('/')
            val searchUrl = if (base.endsWith("/search")) base else "$base/search"
            addQueryParams(
                searchUrl,
                linkedMapOf("q" to query, "format" to "json", "pageno" to page.toString())
            )
        }
        val payload = request(target, authHeaders(service.apiKey), MAX_SEARCH_BYTES)
        return parseJsonResults(payload.body, service.name.ifBlank { "SearXNG" })
    }

    private fun searchGenericApi(
        service: SearchGroupStorage.SearchService,
        query: String,
        page: Int
    ): List<SearchResult> {
        val target = expandTemplate(service.endpoint, query, page, service.apiKey)
        val payload = request(target, authHeaders(service.apiKey), MAX_SEARCH_BYTES)
        return parsePayload(payload, service.name.ifBlank { "自定义 JSON API" })
    }

    private fun searchUrlTemplate(
        service: SearchGroupStorage.SearchService,
        query: String,
        page: Int
    ): List<SearchResult> {
        val target = expandTemplate(service.endpoint, query, page, service.apiKey)
        val payload = request(target, authHeaders(service.apiKey), MAX_SEARCH_BYTES)
        return parsePayload(payload, service.name.ifBlank { "搜索网址模板" })
    }

    private fun searchDefault(
        query: String,
        page: Int,
        warnings: MutableList<String>
    ): ProviderAttempt {
        // DuckDuckGo 的轻量结果页更接近普通网页搜索；Bing RSS 作为第二兜底。
        // 无论哪个公共入口返回了内容，都必须先经过相关性筛选，避免把热榜、彩票、
        // 广告或缓存污染当作本次查询结果交给住户。
        val providers = listOf<(String, Int) -> ProviderAttempt>(
            { q, p -> searchDuckDuckGoLite(q, p) },
            { q, p -> searchBingRss(q, p) },
            { q, p -> searchWikipedia(q, p) }
        )

        for (provider in providers) {
            ensureActive()
            try {
                val attempt = provider(query, page)
                val relevant = keepRelevantResults(query, attempt.results)
                if (relevant.isNotEmpty()) return attempt.copy(results = relevant)
                warnings += if (attempt.results.isEmpty()) {
                    "${attempt.name}没有返回结果"
                } else {
                    "${attempt.name}返回的内容与查询无关，已忽略"
                }
            } catch (e: Exception) {
                ensureActive()
                warnings += "内置通道切换：${friendlyError(e)}"
            }
        }
        return ProviderAttempt("内置公共搜索", emptyList())
    }

    private fun searchBingRss(query: String, page: Int): ProviderAttempt {
        val first = ((page - 1) * SearchGroupStorage.RESULTS_PER_BATCH + 1).coerceAtLeast(1)
        val url = addQueryParams(
            "https://www.bing.com/search",
            linkedMapOf("q" to query, "format" to "rss", "first" to first.toString())
        )
        val payload = request(url, maxBytes = MAX_SEARCH_BYTES)
        return ProviderAttempt("内置公共搜索 · Bing RSS", parseRssResults(payload.body, "Bing"))
    }

    private fun searchDuckDuckGoLite(query: String, page: Int): ProviderAttempt {
        val offset = (page - 1) * 30
        val formBody = linkedMapOf(
            "q" to query,
            "s" to offset.toString(),
            "kl" to "wt-wt"
        ).entries.joinToString("&") { (key, value) ->
            "${encodeQuery(key)}=${encodeQuery(value)}"
        }
        val payload = request(
            url = "https://lite.duckduckgo.com/lite/",
            maxBytes = MAX_SEARCH_BYTES,
            method = "POST",
            body = formBody
        )
        return ProviderAttempt("内置公共搜索 · DuckDuckGo", parseHtmlResults(payload.body, "DuckDuckGo"))
    }

    private fun searchWikipedia(query: String, page: Int): ProviderAttempt {
        val language = if (query.any { it.code in 0x3400..0x9FFF }) "zh" else "en"
        val offset = (page - 1) * SearchGroupStorage.RESULTS_PER_BATCH
        val url = addQueryParams(
            "https://$language.wikipedia.org/w/api.php",
            linkedMapOf(
                "action" to "query",
                "list" to "search",
                "srsearch" to query,
                "format" to "json",
                "utf8" to "1",
                "srlimit" to SearchGroupStorage.RESULTS_PER_BATCH.toString(),
                "sroffset" to offset.toString()
            )
        )
        val payload = request(url, maxBytes = MAX_SEARCH_BYTES)
        val json = JSONObject(payload.body)
        val array = json.optJSONObject("query")?.optJSONArray("search") ?: JSONArray()
        val results = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val title = cleanText(item.optString("title"))
                if (title.isBlank()) continue
                val article = "https://$language.wikipedia.org/wiki/" +
                    encodePathSegment(title.replace(' ', '_'))
                add(
                    SearchResult(
                        title = title,
                        url = article,
                        snippet = cleanText(item.optString("snippet")),
                        source = "Wikipedia"
                    )
                )
            }
        }
        return ProviderAttempt("内置知识兜底 · Wikipedia", results)
    }

    private fun parsePayload(payload: HttpPayload, source: String): List<SearchResult> {
        val body = payload.body.trimStart()
        val type = payload.contentType.lowercase(Locale.ROOT)
        return when {
            type.contains("json") || body.startsWith("{") || body.startsWith("[") ->
                parseJsonResults(payload.body, source)
            type.contains("rss") || type.contains("xml") || body.startsWith("<?xml") ||
                body.contains("<rss", ignoreCase = true) || body.contains("<feed", ignoreCase = true) ->
                parseRssResults(payload.body, source)
            else -> parseHtmlResults(payload.body, source)
        }
    }

    private fun parseJsonResults(body: String, source: String): List<SearchResult> {
        val root: Any = when (val trimmed = body.trim()) {
            "" -> return emptyList()
            else -> if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed)
        }
        val results = mutableListOf<SearchResult>()

        fun walk(value: Any?, depth: Int) {
            if (value == null || depth > 7 || results.size >= 48) return
            when (value) {
                is JSONObject -> {
                    val title = firstString(value, TITLE_KEYS)
                    val url = normalizeResultUrl(firstString(value, URL_KEYS))
                    if (title.isNotBlank() && url.isNotBlank()) {
                        results += SearchResult(
                            title = cleanText(title).take(300),
                            url = url,
                            snippet = cleanText(firstString(value, SNIPPET_KEYS)).take(1200),
                            source = source
                        )
                    }
                    val keys = value.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val child = value.opt(key)
                        if (child is JSONObject || child is JSONArray) walk(child, depth + 1)
                    }
                }
                is JSONArray -> {
                    for (index in 0 until value.length()) walk(value.opt(index), depth + 1)
                }
            }
        }

        walk(root, 0)
        return deduplicate(results)
    }

    private fun parseRssResults(body: String, source: String): List<SearchResult> {
        val blocks = Regex("(?is)<item\\b[^>]*>(.*?)</item>|<entry\\b[^>]*>(.*?)</entry>")
            .findAll(body)
            .map { it.groupValues[1].ifBlank { it.groupValues[2] } }
            .toList()
        return blocks.mapNotNull { block ->
            val title = cleanText(extractXmlTag(block, "title"))
            val link = normalizeResultUrl(
                extractXmlTag(block, "link").ifBlank {
                    Regex("(?is)<link\\b[^>]*href\\s*=\\s*['\"]([^'\"]+)['\"]")
                        .find(block)?.groupValues?.getOrNull(1).orEmpty()
                }
            )
            if (title.isBlank() || link.isBlank()) return@mapNotNull null
            val snippet = cleanText(
                extractXmlTag(block, "description")
                    .ifBlank { extractXmlTag(block, "summary") }
                    .ifBlank { extractXmlTag(block, "content") }
            )
            SearchResult(title.take(300), link, snippet.take(1200), source)
        }
    }

    private fun parseHtmlResults(body: String, source: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        val anchorRegex = Regex("(?is)<a\\b([^>]*)>(.*?)</a>")
        val matches = anchorRegex.findAll(body).toList()
        for ((index, match) in matches.withIndex()) {
            if (results.size >= 32) break
            val attrs = match.groupValues[1]
            val className = attribute(attrs, "class").lowercase(Locale.ROOT)
            val rawHref = attribute(attrs, "href")
            val href = normalizeResultUrl(rawHref)
            val title = cleanText(match.groupValues[2])
            if (href.isBlank() || title.length < 2) continue

            val looksLikeResult = className.contains("result") ||
                className.contains("title")
            if (!looksLikeResult && index > 24) continue
            if (isSearchEngineNavigation(href)) continue

            val after = body.substring(match.range.last + 1, (match.range.last + 1800).coerceAtMost(body.length))
            val snippet = Regex(
                "(?is)<(?:td|div|span|p)\\b[^>]*class\\s*=\\s*['\"][^'\"]*(?:snippet|result__snippet|description)[^'\"]*['\"][^>]*>(.*?)</(?:td|div|span|p)>"
            ).find(after)?.groupValues?.getOrNull(1).orEmpty()

            results += SearchResult(
                title = title.take(300),
                url = href,
                snippet = cleanText(snippet).take(1200),
                source = source
            )
        }
        return deduplicate(results)
    }

    /**
     * 公共搜索入口偶尔会返回热榜、广告或与查询完全无关的缓存结果。
     * 这里宁可把可疑结果丢掉并继续换通道，也不把垃圾资料交给住户。
     */
    private fun keepRelevantResults(
        query: String,
        results: List<SearchResult>
    ): List<SearchResult> {
        if (results.isEmpty()) return emptyList()
        val signals = buildQuerySignals(query)
        if (signals.isEmpty()) return results

        // 这里只拦截“完全不沾边”的结果，不尝试替住户判断内容价值。
        // 阈值保持宽松，避免同义词、品牌别名或短标题被误杀。
        val minimumScore = 2

        return results
            .map { result -> result to relevanceScore(result, signals) }
            .filter { (_, score) -> score >= minimumScore }
            .sortedByDescending { (_, score) -> score }
            .map { (result, _) -> result }
    }

    private data class QuerySignal(val text: String, val weight: Int)

    private fun buildQuerySignals(rawQuery: String): List<QuerySignal> {
        var normalized = rawQuery.lowercase(Locale.ROOT)
            .replace(Regex("https?://\\S+"), " ")
            .replace(Regex("\\bsite:[^\\s]+", RegexOption.IGNORE_CASE), " ")

        CHINESE_QUERY_STOP_PHRASES.forEach { phrase ->
            normalized = normalized.replace(phrase, " ")
        }

        val signals = linkedMapOf<String, Int>()
        fun addSignal(text: String, weight: Int) {
            val clean = text.trim().lowercase(Locale.ROOT)
            if (clean.length < 2 || clean in LATIN_QUERY_STOP_WORDS) return
            val previous = signals[clean] ?: 0
            if (weight > previous) signals[clean] = weight
        }

        Regex("[a-z0-9][a-z0-9._+-]*", RegexOption.IGNORE_CASE)
            .findAll(normalized)
            .forEach { match -> addSignal(match.value, if (match.value.length >= 4) 3 else 2) }

        Regex("[\\u3400-\\u9fff]+").findAll(normalized).forEach { match ->
            val segment = match.value
            if (segment.length in 2..14) addSignal(segment, 4)
            if (segment.length >= 2) {
                for (index in 0 until segment.length - 1) {
                    addSignal(segment.substring(index, index + 2), 1)
                }
            }
            if (segment.length >= 3) {
                for (index in 0 until segment.length - 2) {
                    addSignal(segment.substring(index, index + 3), 2)
                }
            }
        }

        return signals.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key.length })
            .take(MAX_RELEVANCE_SIGNALS)
            .map { QuerySignal(it.key, it.value) }
    }

    private fun relevanceScore(
        result: SearchResult,
        signals: List<QuerySignal>
    ): Int {
        val title = cleanText(result.title).lowercase(Locale.ROOT)
        val snippet = cleanText(result.snippet).lowercase(Locale.ROOT)
        val url = result.url.lowercase(Locale.ROOT)
        var score = 0

        for (signal in signals) {
            when {
                title.contains(signal.text) -> score += signal.weight * 2
                snippet.contains(signal.text) -> score += signal.weight
                url.contains(signal.text) -> score += 1
            }
        }
        return score
    }

    private fun request(
        url: String,
        headers: Map<String, String> = emptyMap(),
        maxBytes: Int,
        requirePublicUrl: Boolean = false,
        method: String = "GET",
        body: String? = null
    ): HttpPayload {
        var currentUrl = url
        var currentMethod = method.uppercase(Locale.ROOT)
        var currentBody = body
        var redirects = 0

        while (true) {
            ensureActive()
            if (requirePublicUrl && !isSafePublicHttpUrl(currentUrl)) {
                throw IllegalArgumentException("网页跳转到了本机、局域网或其他不允许访问的地址")
            }

            val connection = URI(currentUrl).toURL().openConnection() as HttpURLConnection
            activeConnection = connection
            try {
                // 手动处理跳转，确保网页阅读不会在检查前被恶意重定向到内网。
                connection.instanceFollowRedirects = false
                connection.requestMethod = currentMethod
                connection.connectTimeout = 15000
                connection.readTimeout = 22000
                connection.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36 Guiqi/1.0"
                )
                connection.setRequestProperty(
                    "Accept",
                    "application/json, application/rss+xml, application/xml, text/html, text/plain;q=0.9, */*;q=0.5"
                )
                connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.7")
                headers.forEach { (key, value) ->
                    if (value.isNotBlank()) connection.setRequestProperty(key, value)
                }
                val requestBody = currentBody
                if (requestBody != null && currentMethod != "GET" && currentMethod != "HEAD") {
                    val bytes = requestBody.toByteArray(Charsets.UTF_8)
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    connection.setRequestProperty("Content-Length", bytes.size.toString())
                    connection.outputStream.use { it.write(bytes) }
                }

                val code = connection.responseCode
                ensureActive()
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location").orEmpty().trim()
                    if (location.isBlank()) throw IllegalStateException("HTTP $code 跳转缺少目标地址")
                    if (redirects >= MAX_REDIRECTS) throw IllegalStateException("网页跳转次数过多")
                    currentUrl = URI(currentUrl).resolve(location).toString()
                    if (code == 303 || ((code == 301 || code == 302) && currentMethod == "POST")) {
                        currentMethod = "GET"
                        currentBody = null
                    }
                    redirects++
                    continue
                }

                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val bytes = stream?.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        val remaining = maxBytes - output.size()
                        if (remaining <= 0) break
                        output.write(buffer, 0, read.coerceAtMost(remaining))
                        if (output.size() >= maxBytes) break
                    }
                    output.toByteArray()
                } ?: ByteArray(0)

                val contentType = connection.contentType.orEmpty()
                val charset = parseCharset(contentType)
                val text = bytes.toString(charset)
                if (code !in 200..299) {
                    val detail = cleanText(text).take(240)
                    throw IllegalStateException("HTTP $code${if (detail.isBlank()) "" else "：$detail"}")
                }
                return HttpPayload(
                    body = text,
                    finalUrl = connection.url.toString(),
                    contentType = contentType,
                    truncated = bytes.size >= maxBytes
                )
            } finally {
                if (activeConnection === connection) activeConnection = null
                runCatching { connection.disconnect() }
            }
        }
    }

    private fun ensureActive() {
        if (cancelled || Thread.currentThread().isInterrupted) throw ApiRequestCancelledException()
    }

    private fun expandTemplate(template: String, query: String, page: Int, apiKey: String): String {
        val encodedQuery = encodeQuery(query)
        var result = template.trim()
            .replace("{query}", encodedQuery, ignoreCase = true)
            .replace("{q}", encodedQuery, ignoreCase = true)
            .replace("{query_raw}", query, ignoreCase = true)
            .replace("{page}", page.toString(), ignoreCase = true)
            .replace("{pageno}", page.toString(), ignoreCase = true)
            .replace("{count}", SearchGroupStorage.RESULTS_PER_BATCH.toString(), ignoreCase = true)
            .replace("{api_key}", encodeQuery(apiKey), ignoreCase = true)
        if (result.contains("%s")) result = result.replaceFirst("%s", encodedQuery)

        val hadQueryPlaceholder = template.contains("{query}", true) ||
            template.contains("{q}", true) || template.contains("{query_raw}", true) || template.contains("%s")
        if (!hadQueryPlaceholder) result = addQueryParamIfMissing(result, "q", query)
        if (!template.contains("{page}", true) && !template.contains("{pageno}", true)) {
            result = addQueryParamIfMissing(result, "page", page.toString())
        }
        return result
    }

    private fun addQueryParams(base: String, params: Map<String, String>): String {
        var result = base
        params.forEach { (key, value) -> result = addQueryParamIfMissing(result, key, value) }
        return result
    }

    private fun addQueryParamIfMissing(base: String, key: String, value: String): String {
        val lower = base.lowercase(Locale.ROOT)
        if (Regex("(?:[?&])${Regex.escape(key.lowercase(Locale.ROOT))}=").containsMatchIn(lower)) return base
        val separator = when {
            base.endsWith("?") || base.endsWith("&") -> ""
            base.contains("?") -> "&"
            else -> "?"
        }
        return "$base$separator${encodeQuery(key)}=${encodeQuery(value)}"
    }

    private fun authHeaders(apiKey: String): Map<String, String> = if (apiKey.isBlank()) {
        emptyMap()
    } else {
        linkedMapOf(
            "Authorization" to "Bearer $apiKey",
            "X-API-Key" to apiKey
        )
    }

    private fun firstString(obj: JSONObject, keys: List<String>): String {
        for (key in keys) {
            val value = obj.opt(key)
            if (value is String && value.isNotBlank()) return value
        }
        return ""
    }

    private fun extractXmlTag(block: String, tag: String): String {
        val match = Regex("(?is)<${Regex.escape(tag)}\\b[^>]*>(.*?)</${Regex.escape(tag)}>")
            .find(block)?.groupValues?.getOrNull(1).orEmpty()
        return match
            .replace("<![CDATA[", "")
            .replace("]]>", "")
            .trim()
    }

    private fun attribute(attrs: String, name: String): String {
        return Regex("(?is)\\b${Regex.escape(name)}\\s*=\\s*(['\"])(.*?)\\1")
            .find(attrs)?.groupValues?.getOrNull(2).orEmpty()
    }

    private fun normalizeResultUrl(raw: String): String {
        var value = decodeHtml(raw).trim()
        if (value.startsWith("//")) value = "https:$value"
        if (value.startsWith("/l/?") || value.contains("duckduckgo.com/l/?")) {
            val absolute = if (value.startsWith("/")) "https://duckduckgo.com$value" else value
            runCatching {
                val uri = URI(absolute)
                val params = parseQuery(uri.rawQuery.orEmpty())
                val redirected = params["uddg"]
                if (!redirected.isNullOrBlank()) value = redirected
            }
        }
        return runCatching {
            val uri = URI(value)
            if (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) uri.toString() else ""
        }.getOrDefault("")
    }

    private fun parseQuery(rawQuery: String): Map<String, String> = buildMap {
        rawQuery.split('&').forEach { part ->
            val pair = part.split('=', limit = 2)
            if (pair.isNotEmpty()) {
                val key = URLDecoder.decode(pair[0], "UTF-8")
                val value = URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
                put(key, value)
            }
        }
    }

    private fun deduplicate(values: List<SearchResult>): List<SearchResult> {
        val seen = linkedSetOf<String>()
        return values.filter { result ->
            val key = runCatching {
                val uri = URI(result.url)
                (uri.host.orEmpty().lowercase(Locale.ROOT) + uri.path.orEmpty().trimEnd('/'))
            }.getOrDefault(result.url.lowercase(Locale.ROOT))
            result.title.isNotBlank() && result.url.isNotBlank() && seen.add(key)
        }
    }

    private fun cleanText(value: String): String {
        if (value.isBlank()) return ""
        val noScripts = value
            .replace(Regex("(?is)<script\\b[^>]*>.*?</script>"), " ")
            .replace(Regex("(?is)<style\\b[^>]*>.*?</style>"), " ")
        val decoded = decodeHtml(noScripts)
        return decoded
            .replace('\u00A0', ' ')
            .replace(Regex("[\\t\\r ]+"), " ")
            .replace(Regex(" *\\n *"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    private fun htmlToReadableText(html: String): String {
        val cleaned = html
            .replace(Regex("(?is)<!--.*?-->"), " ")
            .replace(Regex("(?is)<(?:script|style|svg|canvas|noscript|iframe|form)\\b[^>]*>.*?</(?:script|style|svg|canvas|noscript|iframe|form)>"), " ")
            .replace(Regex("(?is)<(?:nav|header|footer|aside)\\b[^>]*>.*?</(?:nav|header|footer|aside)>"), " ")
            .replace(Regex("(?is)<br\\s*/?>"), "\n")
            .replace(Regex("(?is)</(?:p|div|article|section|main|h[1-6]|li|tr)>"), "\n")
        return cleanText(cleaned)
    }

    private fun extractHtmlTitle(html: String): String = cleanText(
        Regex("(?is)<title\\b[^>]*>(.*?)</title>").find(html)?.groupValues?.getOrNull(1).orEmpty()
    )

    private fun decodeHtml(value: String): String = runCatching {
        Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString()
    }.getOrDefault(value)

    private fun parseCharset(contentType: String): Charset {
        val name = Regex("(?i)charset\\s*=\\s*['\"]?([^;'\" ]+)")
            .find(contentType)?.groupValues?.getOrNull(1)
        return runCatching { Charset.forName(name ?: "UTF-8") }.getOrDefault(Charsets.UTF_8)
    }

    private fun isSafePublicHttpUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        if (!(uri.scheme.equals("http", true) || uri.scheme.equals("https", true))) return@runCatching false
        val host = uri.host?.lowercase(Locale.ROOT) ?: return@runCatching false
        if (
            host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") ||
            host == "0.0.0.0" || host == "::1"
        ) return@runCatching false
        val addresses = InetAddress.getAllByName(host)
        addresses.isNotEmpty() && addresses.none {
            it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress ||
                it.isSiteLocalAddress || it.isMulticastAddress
        }
    }.getOrDefault(false)

    private fun isSearchEngineNavigation(url: String): Boolean = runCatching {
        val uri = URI(url)
        val host = uri.host.orEmpty().lowercase(Locale.ROOT)
        val path = uri.path.orEmpty()
        (host.contains("duckduckgo.com") && !path.startsWith("/l/")) ||
            (host.contains("bing.com") && path.startsWith("/search"))
    }.getOrDefault(false)

    private fun friendlyError(error: Exception): String {
        val text = error.message.orEmpty().replace(Regex("\\s+"), " ").trim()
        return if (text.isBlank()) error.javaClass.simpleName else text.take(160)
    }

    private fun serviceTypeLabel(type: String): String = when (type) {
        SearchGroupStorage.TYPE_GENERIC_API -> "自定义 JSON API"
        SearchGroupStorage.TYPE_URL_TEMPLATE -> "搜索网址模板"
        else -> "SearXNG"
    }

    private fun encodeQuery(value: String): String = URLEncoder.encode(value, "UTF-8")
        .replace("+", "%20")

    private fun encodePathSegment(value: String): String = URLEncoder.encode(value, "UTF-8")
        .replace("+", "%20")
        .replace("%2F", "/", ignoreCase = true)

    companion object {
        private const val MAX_RELEVANCE_SIGNALS = 28
        private const val GITHUB_API_VERSION = "2026-03-10"
        private const val GITHUB_METADATA_BYTES = 300_000
        private const val GITHUB_TREE_BYTES = 1_200_000
        private const val GITHUB_README_API_BYTES = 1_500_000
        private const val MAX_GITHUB_ROOT_ENTRIES = 80
        private const val MAX_GITHUB_DIRECTORY_ENTRIES = 200
        private const val MAX_GITHUB_README_TEXT = 10_000
        private const val MAX_GITHUB_FILE_BYTES = 5_000_000
        private const val DEFAULT_GITHUB_LINE_BATCH = 320
        private const val MAX_GITHUB_LINES_PER_READ = 600
        private const val MAX_GITHUB_CODE_TEXT = 24_000
        private const val MAX_GITHUB_FIND_QUERY = 240
        private const val DEFAULT_GITHUB_FIND_MATCHES = 12
        private const val MAX_GITHUB_FIND_MATCHES = 30
        private const val GITHUB_FIND_CONTEXT_LINES = 2
        private const val MAX_GITHUB_PREVIEW_LINE = 700

        private val GITHUB_NAME_PATTERN = Regex("[A-Za-z0-9_.-]{1,100}")

        private val CHINESE_QUERY_STOP_PHRASES = listOf(
            "帮我搜索", "帮忙搜索", "帮我搜", "帮忙搜", "搜索一下", "搜一下", "查一下",
            "请问", "一下", "一个人", "一个", "这个", "那个", "什么", "怎么回事", "怎么办",
            "怎么样", "如何", "可以", "有没有", "是否", "为什么", "最新", "相关", "关于",
            "给我", "告诉我", "我想知道", "想知道"
        )

        private val LATIN_QUERY_STOP_WORDS = setOf(
            "the", "and", "for", "with", "from", "what", "when", "where", "which", "who",
            "how", "why", "please", "search", "find", "latest", "news", "about"
        )

        private const val MAX_QUERY_LENGTH = 500
        private const val MAX_URL_LENGTH = 4096
        private const val MAX_PAGE = 50
        private const val MAX_SEARCH_BYTES = 1_500_000
        private const val MAX_PAGE_BYTES = 2_000_000
        private const val MAX_PAGE_TEXT = 18_000
        private const val MAX_REDIRECTS = 5

        private val TITLE_KEYS = listOf("title", "name", "headline", "text")
        private val URL_KEYS = listOf("url", "link", "href", "targetUrl", "displayUrl")
        private val SNIPPET_KEYS = listOf(
            "content", "snippet", "description", "summary", "abstract", "body", "text"
        )
    }
}
