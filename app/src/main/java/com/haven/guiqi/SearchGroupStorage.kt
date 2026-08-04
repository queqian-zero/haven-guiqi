package com.haven.guiqi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 搜索组的数据层。
 *
 * 保存内置通道开关、可插拔搜索服务、网站来源、外部应用、住户权限和隐私设置。
 * 实际联网由 SearchCoordinator 统一执行，不与任何一家模型 API 绑死。
 */
class SearchGroupStorage(context: Context) {

    data class GeneralSettings(
        val enabled: Boolean = false,
        /** 没有任何自定义配置时仍可使用归栖内置的免配置公共搜索通道。 */
        val useDefaultProvider: Boolean = true
    )

    data class SearchService(
        val id: String = newId("service"),
        val name: String = "",
        val type: String = TYPE_SEARXNG,
        val endpoint: String = "",
        val apiKey: String = "",
        val enabled: Boolean = true,
        val priority: Int = 100
    )

    data class WebsiteSource(
        val id: String = newId("source"),
        val name: String = "",
        val type: String = SOURCE_DOMAIN,
        val value: String = "",
        val enabled: Boolean = true,
        val requiresLogin: Boolean = false,
        val allowFullText: Boolean = true
    )

    data class ExternalSearchApp(
        val id: String = newId("app"),
        val name: String = "",
        val packageName: String = "",
        val mode: String = APP_OPEN_ONLY,
        val enabled: Boolean = true
    )

    data class ResidentPermission(
        val friendId: String,
        val allowSearch: Boolean = false,
        val useAllServices: Boolean = true,
        val useAllSources: Boolean = true,
        val allowFullText: Boolean = true,
        val allowLogin: Boolean = false,
        val allowDownloads: Boolean = false,
        val allowHistory: Boolean = true
    )

    data class PrivacySettings(
        val saveHistory: Boolean = true,
        val historyKeepDays: Int = 30,
        val savePageText: Boolean = false,
        val redactSensitiveText: Boolean = true,
        val allowLoginState: Boolean = false
    )

    data class SearchHistoryEntry(
        val id: String = newId("history"),
        val friendId: String,
        val query: String,
        val page: Int = 1,
        val provider: String = "",
        val resultCount: Int = 0,
        val success: Boolean = true,
        val error: String = "",
        val createdAt: Long = System.currentTimeMillis()
    )

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadGeneral(): GeneralSettings = runCatching {
        val obj = JSONObject(prefs.getString(KEY_GENERAL, "{}") ?: "{}")
        GeneralSettings(
            enabled = obj.optBoolean("enabled", false),
            useDefaultProvider = obj.optBoolean("useDefaultProvider", true)
        )
    }.getOrDefault(GeneralSettings())

    fun saveGeneral(value: GeneralSettings) {
        prefs.edit().putString(
            KEY_GENERAL,
            JSONObject()
                .put("enabled", value.enabled)
                .put("useDefaultProvider", value.useDefaultProvider)
                .toString()
        ).apply()
    }

    fun loadServices(): List<SearchService> = readArray(KEY_SERVICES) { obj ->
        SearchService(
            id = obj.optString("id").ifBlank { newId("service") },
            name = obj.optString("name"),
            type = obj.optString("type", TYPE_SEARXNG),
            endpoint = obj.optString("endpoint"),
            apiKey = obj.optString("apiKey"),
            enabled = obj.optBoolean("enabled", true),
            priority = obj.optInt("priority", 100).coerceIn(1, 999)
        )
    }.sortedWith(compareBy<SearchService> { it.priority }.thenBy { it.name })

    fun saveServices(values: List<SearchService>) {
        writeArray(KEY_SERVICES, values) { value ->
            JSONObject()
                .put("id", value.id)
                .put("name", value.name)
                .put("type", value.type)
                .put("endpoint", value.endpoint)
                .put("apiKey", value.apiKey)
                .put("enabled", value.enabled)
                .put("priority", value.priority.coerceIn(1, 999))
        }
    }

    fun upsertService(value: SearchService) {
        val list = loadServices().toMutableList()
        val index = list.indexOfFirst { it.id == value.id }
        if (index >= 0) list[index] = value else list.add(value)
        saveServices(list)
    }

    fun deleteService(id: String) {
        saveServices(loadServices().filterNot { it.id == id })
    }

    fun loadSources(): List<WebsiteSource> = readArray(KEY_SOURCES) { obj ->
        WebsiteSource(
            id = obj.optString("id").ifBlank { newId("source") },
            name = obj.optString("name"),
            type = obj.optString("type", SOURCE_DOMAIN),
            value = obj.optString("value"),
            enabled = obj.optBoolean("enabled", true),
            requiresLogin = obj.optBoolean("requiresLogin", false),
            allowFullText = obj.optBoolean("allowFullText", true)
        )
    }

    fun saveSources(values: List<WebsiteSource>) {
        writeArray(KEY_SOURCES, values) { value ->
            JSONObject()
                .put("id", value.id)
                .put("name", value.name)
                .put("type", value.type)
                .put("value", value.value)
                .put("enabled", value.enabled)
                .put("requiresLogin", value.requiresLogin)
                .put("allowFullText", value.allowFullText)
        }
    }

    fun upsertSource(value: WebsiteSource) {
        val list = loadSources().toMutableList()
        val index = list.indexOfFirst { it.id == value.id }
        if (index >= 0) list[index] = value else list.add(value)
        saveSources(list)
    }

    fun deleteSource(id: String) {
        saveSources(loadSources().filterNot { it.id == id })
    }

    fun loadExternalApps(): List<ExternalSearchApp> = readArray(KEY_EXTERNAL_APPS) { obj ->
        ExternalSearchApp(
            id = obj.optString("id").ifBlank { newId("app") },
            name = obj.optString("name"),
            packageName = obj.optString("packageName"),
            mode = obj.optString("mode", APP_OPEN_ONLY),
            enabled = obj.optBoolean("enabled", true)
        )
    }

    fun saveExternalApps(values: List<ExternalSearchApp>) {
        writeArray(KEY_EXTERNAL_APPS, values) { value ->
            JSONObject()
                .put("id", value.id)
                .put("name", value.name)
                .put("packageName", value.packageName)
                .put("mode", value.mode)
                .put("enabled", value.enabled)
        }
    }

    fun upsertExternalApp(value: ExternalSearchApp) {
        val list = loadExternalApps().toMutableList()
        val index = list.indexOfFirst { it.id == value.id }
        if (index >= 0) list[index] = value else list.add(value)
        saveExternalApps(list)
    }

    fun deleteExternalApp(id: String) {
        saveExternalApps(loadExternalApps().filterNot { it.id == id })
    }

    fun loadResidentPermission(friendId: String): ResidentPermission {
        if (friendId.isBlank()) return ResidentPermission("")
        return runCatching {
            val all = JSONObject(prefs.getString(KEY_RESIDENT_PERMISSIONS, "{}") ?: "{}")
            val obj = all.optJSONObject(friendId) ?: return@runCatching ResidentPermission(friendId)
            ResidentPermission(
                friendId = friendId,
                allowSearch = obj.optBoolean("allowSearch", false),
                useAllServices = obj.optBoolean("useAllServices", true),
                useAllSources = obj.optBoolean("useAllSources", true),
                allowFullText = obj.optBoolean("allowFullText", true),
                allowLogin = obj.optBoolean("allowLogin", false),
                allowDownloads = obj.optBoolean("allowDownloads", false),
                allowHistory = obj.optBoolean("allowHistory", true)
            )
        }.getOrDefault(ResidentPermission(friendId))
    }

    fun saveResidentPermission(value: ResidentPermission) {
        if (value.friendId.isBlank()) return
        val all = runCatching {
            JSONObject(prefs.getString(KEY_RESIDENT_PERMISSIONS, "{}") ?: "{}")
        }.getOrDefault(JSONObject())
        all.put(
            value.friendId,
            JSONObject()
                .put("allowSearch", value.allowSearch)
                .put("useAllServices", value.useAllServices)
                .put("useAllSources", value.useAllSources)
                .put("allowFullText", value.allowFullText)
                .put("allowLogin", value.allowLogin)
                .put("allowDownloads", value.allowDownloads)
                .put("allowHistory", value.allowHistory)
        )
        prefs.edit().putString(KEY_RESIDENT_PERMISSIONS, all.toString()).apply()
    }

    fun loadPrivacy(): PrivacySettings = runCatching {
        val obj = JSONObject(prefs.getString(KEY_PRIVACY, "{}") ?: "{}")
        PrivacySettings(
            saveHistory = obj.optBoolean("saveHistory", true),
            historyKeepDays = obj.optInt("historyKeepDays", 30).coerceIn(1, 3650),
            savePageText = obj.optBoolean("savePageText", false),
            redactSensitiveText = obj.optBoolean("redactSensitiveText", true),
            allowLoginState = obj.optBoolean("allowLoginState", false)
        )
    }.getOrDefault(PrivacySettings())

    fun savePrivacy(value: PrivacySettings) {
        prefs.edit().putString(
            KEY_PRIVACY,
            JSONObject()
                .put("saveHistory", value.saveHistory)
                .put("historyKeepDays", value.historyKeepDays.coerceIn(1, 3650))
                .put("savePageText", value.savePageText)
                .put("redactSensitiveText", value.redactSensitiveText)
                .put("allowLoginState", value.allowLoginState)
                .toString()
        ).apply()
    }

    fun loadSearchHistory(friendId: String? = null): List<SearchHistoryEntry> {
        val privacy = loadPrivacy()
        val cutoff = System.currentTimeMillis() - privacy.historyKeepDays.toLong() * 24L * 60L * 60L * 1000L
        val values = readArray(KEY_HISTORY) { obj ->
            SearchHistoryEntry(
                id = obj.optString("id").ifBlank { newId("history") },
                friendId = obj.optString("friendId"),
                query = obj.optString("query"),
                page = obj.optInt("page", 1).coerceIn(1, 50),
                provider = obj.optString("provider"),
                resultCount = obj.optInt("resultCount", 0).coerceAtLeast(0),
                success = obj.optBoolean("success", true),
                error = obj.optString("error"),
                createdAt = obj.optLong("createdAt", 0L)
            )
        }.filter { it.createdAt >= cutoff }

        // 读取时顺手清掉过期条目，避免 SharedPreferences 无限增长。
        val storedCount = runCatching { JSONArray(prefs.getString(KEY_HISTORY, "[]") ?: "[]").length() }.getOrDefault(values.size)
        if (storedCount != values.size) saveSearchHistory(values)

        return values
            .asSequence()
            .filter { friendId.isNullOrBlank() || it.friendId == friendId }
            .sortedByDescending { it.createdAt }
            .toList()
    }

    fun appendSearchHistory(value: SearchHistoryEntry) {
        val privacy = loadPrivacy()
        val permission = loadResidentPermission(value.friendId)
        if (!privacy.saveHistory || !permission.allowHistory) return

        val safeValue = if (privacy.redactSensitiveText) {
            value.copy(
                query = redactSensitive(value.query),
                error = redactSensitive(value.error)
            )
        } else value

        val current = loadSearchHistory().toMutableList()
        current.add(0, safeValue)
        saveSearchHistory(current.distinctBy { it.id }.take(MAX_HISTORY_ITEMS))
    }

    fun clearSearchHistory(friendId: String? = null) {
        if (friendId.isNullOrBlank()) {
            prefs.edit().remove(KEY_HISTORY).apply()
        } else {
            saveSearchHistory(loadSearchHistory().filterNot { it.friendId == friendId })
        }
    }

    private fun saveSearchHistory(values: List<SearchHistoryEntry>) {
        writeArray(KEY_HISTORY, values) { value ->
            JSONObject()
                .put("id", value.id)
                .put("friendId", value.friendId)
                .put("query", value.query)
                .put("page", value.page)
                .put("provider", value.provider)
                .put("resultCount", value.resultCount)
                .put("success", value.success)
                .put("error", value.error)
                .put("createdAt", value.createdAt)
        }
    }

    private fun redactSensitive(value: String): String {
        return value
            .replace(Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]{8,}"), "Bearer [已遮盖]")
            .replace(Regex("(?i)\\b(?:sk|key|token)[-_][A-Za-z0-9_-]{8,}"), "[已遮盖密钥]")
            .replace(Regex("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b"), "[已遮盖邮箱]")
            .take(800)
    }

    fun globalSummary(): String {
        val general = loadGeneral()
        val services = loadServices().count { it.enabled && it.endpoint.isNotBlank() }
        val sources = loadSources().count { it.enabled && it.value.isNotBlank() }
        return if (!general.enabled) {
            "总开关关闭 · 已配置 $services 个服务、$sources 个来源"
        } else {
            val defaultText = if (general.useDefaultProvider) "内置通道可用" else "内置通道关闭"
            "总开关开启 · $defaultText · $services 个服务、$sources 个来源"
        }
    }

    fun residentSummary(friendId: String): String {
        val permission = loadResidentPermission(friendId)
        val general = loadGeneral()
        if (!general.enabled) return "搜索组总开关目前关闭"
        if (!permission.allowSearch) return "当前住户未获联网搜索权限"
        if (!hasSearchPathForResident(friendId)) return "已授权，但没有分配可用搜索通道"
        val details = buildList {
            add("可搜索")
            if (general.useDefaultProvider) add("含内置通道")
            if (permission.allowFullText) add("可读正文")
            if (permission.allowHistory && loadPrivacy().saveHistory) add("记录历史")
        }
        return details.joinToString(" · ")
    }

    fun hasAnySearchPath(): Boolean {
        val general = loadGeneral()
        if (!general.enabled) return false
        if (general.useDefaultProvider) return true
        if (loadServices().any { it.enabled && it.endpoint.isNotBlank() }) return true
        return loadSources().any {
            it.enabled && it.value.isNotBlank() &&
                (it.type == SOURCE_SEARCH_URL || it.type == SOURCE_RSS_API)
        }
    }

    fun hasSearchPathForResident(friendId: String): Boolean {
        val general = loadGeneral()
        if (!general.enabled) return false
        val permission = loadResidentPermission(friendId)
        if (!permission.allowSearch) return false
        if (general.useDefaultProvider) return true
        if (permission.useAllServices && loadServices().any { it.enabled && it.endpoint.isNotBlank() }) {
            return true
        }
        if (permission.useAllSources) {
            val directSource = loadSources().any {
                it.enabled && it.value.isNotBlank() &&
                    (it.type == SOURCE_SEARCH_URL || it.type == SOURCE_RSS_API)
            }
            if (directSource) return true
        }
        return false
    }

    fun isSearchAllowed(friendId: String): Boolean = hasSearchPathForResident(friendId)

    private fun <T> readArray(key: String, parser: (JSONObject) -> T): List<T> = runCatching {
        val array = JSONArray(prefs.getString(key, "[]") ?: "[]")
        buildList {
            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index) ?: continue
                runCatching { parser(obj) }.getOrNull()?.let(::add)
            }
        }
    }.getOrDefault(emptyList())

    private fun <T> writeArray(key: String, values: List<T>, writer: (T) -> JSONObject) {
        val array = JSONArray()
        values.forEach { array.put(writer(it)) }
        prefs.edit().putString(key, array.toString()).apply()
    }

    companion object {
        const val TYPE_SEARXNG = "searxng"
        const val TYPE_GENERIC_API = "generic_api"
        const val TYPE_URL_TEMPLATE = "url_template"

        const val SOURCE_DOMAIN = "domain"
        const val SOURCE_SEARCH_URL = "search_url"
        const val SOURCE_RSS_API = "rss_api"

        const val APP_OPEN_ONLY = "open_only"
        const val APP_STRUCTURED = "structured"

        /**
         * 搜索执行器每次交给住户阅读的候选结果上限。
         * 这是单批次保护，不是整个搜索过程的总上限；资料不足时可以继续请求下一批。
         */
        const val RESULTS_PER_BATCH = 8

        private const val PREFS_NAME = "haven_search_group"
        private const val KEY_GENERAL = "general"
        private const val KEY_SERVICES = "services"
        private const val KEY_SOURCES = "sources"
        private const val KEY_EXTERNAL_APPS = "external_apps"
        private const val KEY_RESIDENT_PERMISSIONS = "resident_permissions"
        private const val KEY_PRIVACY = "privacy"
        private const val KEY_HISTORY = "history"
        private const val MAX_HISTORY_ITEMS = 300

        private fun newId(prefix: String): String = "$prefix-${UUID.randomUUID()}"
    }
}
