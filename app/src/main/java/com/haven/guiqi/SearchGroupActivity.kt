package com.haven.guiqi

import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 搜索组设置页。
 *
 * 搜索由归栖本机执行，模型 API 只负责发起工具指令和阅读结果。
 * 没有自定义配置时可使用内置免配置通道；自定义服务按优先级失败回退。
 */
class SearchGroupActivity : AppCompatActivity() {

    private val c get() = ThemeHelper.getColors(this)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private lateinit var container: LinearLayout
    private lateinit var storage: SearchGroupStorage
    private var focusedFriendId: String = ""
    private var focusedFriendName: String = ""
    private val expandedSections = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureWindow()
        setContentView(R.layout.activity_search_group)
        applyInsets()

        storage = SearchGroupStorage(this)
        container = findViewById(R.id.searchGroupContainer)
        focusedFriendId = intent.getStringExtra(EXTRA_FRIEND_ID).orEmpty()
        focusedFriendName = intent.getStringExtra(EXTRA_FRIEND_NAME).orEmpty()

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.tvPageSubtitle).text = if (focusedFriendId.isBlank()) {
            "搜索服务、来源、外部应用与住户权限"
        } else {
            "正在配置 ${focusedFriendName.ifBlank { "当前住户" }} 的联网与搜索权限"
        }

        expandedSections += if (focusedFriendId.isBlank()) SECTION_GENERAL else SECTION_RESIDENTS
        buildPage()
    }

    private fun configureWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.navigationBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            isAppearanceLightStatusBars = !ThemeHelper.isDark(this@SearchGroupActivity)
        }
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { view, insets ->
            view.setPadding(0, insets.getInsets(WindowInsetsCompat.Type.statusBars()).top, 0, 0)
            insets
        }
    }

    private fun buildPage() {
        container.removeAllViews()
        addNotice()

        val general = storage.loadGeneral()
        addExpandableSection(
            SECTION_GENERAL,
            "总开关",
            if (general.enabled) {
                if (general.useDefaultProvider) "已开启 · 内置免配置通道可用" else "已开启 · 仅使用自定义通道"
            } else "目前关闭，不会允许住户执行联网搜索"
        ) { body -> buildGeneralSection(body, general) }

        val services = storage.loadServices()
        addExpandableSection(
            SECTION_SERVICES,
            "搜索服务",
            "${services.count { it.enabled }} 个启用 · ${services.size} 个已配置"
        ) { body -> buildServicesSection(body, services) }

        val sources = storage.loadSources()
        addExpandableSection(
            SECTION_SOURCES,
            "网站来源",
            "${sources.count { it.enabled }} 个启用 · 可限制域名、搜索地址或 RSS/API"
        ) { body -> buildSourcesSection(body, sources) }

        val apps = storage.loadExternalApps()
        addExpandableSection(
            SECTION_APPS,
            "外部搜索应用",
            "${apps.size} 个已登记 · 当前不参与住户后台搜索"
        ) { body -> buildExternalAppsSection(body, apps) }

        val residentSummary = if (focusedFriendId.isNotBlank()) {
            storage.residentSummary(focusedFriendId)
        } else {
            val friends = FriendStorage(this).loadFriends()
            val allowed = friends.count { storage.loadResidentPermission(it.id).allowSearch }
            "$allowed / ${friends.size} 位住户已获搜索权限"
        }
        addExpandableSection(
            SECTION_RESIDENTS,
            "住户权限",
            residentSummary
        ) { body -> buildResidentsSection(body) }

        val privacy = storage.loadPrivacy()
        val historyCount = storage.loadSearchHistory(
            focusedFriendId.takeIf { it.isNotBlank() }
        ).size
        addExpandableSection(
            SECTION_PRIVACY,
            "隐私与历史",
            if (privacy.saveHistory) "已记录 $historyCount 条 · 保留 ${privacy.historyKeepDays} 天" else "不保存查询记录"
        ) { body -> buildPrivacySection(body, privacy) }
    }

    private fun addNotice() {
        container.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.settings_item_bg)
            setPadding(dp(14), dp(13), dp(14), dp(13))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }

            addView(TextView(this@SearchGroupActivity).apply {
                text = "搜索工具已经接入"
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(c.textPrimary)
            })
            addView(TextView(this@SearchGroupActivity).apply {
                text = "只要打开总开关并允许某位住户搜索，即使没有填写任何服务，也会使用归栖内置的免配置公共搜索通道。自定义服务会按优先级尝试，失败时自动切换备用通道。"
                textSize = 11f
                setLineSpacing(0f, 1.35f)
                setTextColor(c.textSecondary)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(5) }
            })
        })
    }

    private fun addExpandableSection(
        key: String,
        title: String,
        summary: String,
        buildBody: (LinearLayout) -> Unit
    ) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.chat_card_bg)
            setPadding(dp(13), dp(3), dp(13), dp(3))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(9) }
        }

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (expandedSections.contains(key)) View.VISIBLE else View.GONE
            setPadding(0, 0, 0, dp(10))
        }

        val arrow = TextView(this).apply {
            text = if (body.visibility == View.VISIBLE) "⌃" else "⌄"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(c.textSecondary)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(1), dp(11), dp(1), dp(11))
            setOnClickListener {
                val open = body.visibility != View.VISIBLE
                body.visibility = if (open) View.VISIBLE else View.GONE
                arrow.text = if (open) "⌃" else "⌄"
                if (open) expandedSections += key else expandedSections -= key
            }
        }
        header.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@SearchGroupActivity).apply {
                text = title
                textSize = 14f
                setTextColor(c.textPrimary)
            })
            addView(TextView(this@SearchGroupActivity).apply {
                text = summary
                textSize = 10.5f
                setTextColor(c.textHint)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(3) }
            })
        })
        header.addView(arrow, LinearLayout.LayoutParams(dp(28), dp(34)))
        card.addView(header)

        buildBody(body)
        card.addView(body)
        container.addView(card)
    }

    private fun buildGeneralSection(body: LinearLayout, general: SearchGroupStorage.GeneralSettings) {
        body.addView(createActionCard(
            title = if (general.enabled) "搜索组总开关：开启" else "搜索组总开关：关闭",
            description = buildString {
                append("总开关关闭时，任何住户权限都不会生效。")
                append(if (general.useDefaultProvider) "内置免配置通道已开启。" else "内置免配置通道已关闭，仅使用自定义服务。")
                append("搜索结果每批最多 ${SearchGroupStorage.RESULTS_PER_BATCH} 条；资料不足时，住户可以继续查下一批。")
            },
            status = "分批返回"
        ) { showGeneralDialog(general) })
    }

    private fun buildServicesSection(body: LinearLayout, services: List<SearchGroupStorage.SearchService>) {
        if (services.isEmpty()) addEmpty(body, "还没有自定义搜索服务。没关系，内置免配置通道仍可直接使用；这里用于添加 SearXNG、自定义 JSON API 或搜索网址模板。")
        services.forEach { service ->
            body.addView(createActionCard(
                title = service.name.ifBlank { "未命名搜索服务" },
                description = "${serviceTypeLabel(service.type)} · 优先级 ${service.priority}\n${maskEndpoint(service.endpoint)}",
                status = if (service.enabled) "已启用" else "已停用"
            ) { showServiceDialog(service) })
        }
        body.addView(createAddButton("＋ 添加搜索服务") { showServiceDialog(null) })
    }

    private fun buildSourcesSection(body: LinearLayout, sources: List<SearchGroupStorage.WebsiteSource>) {
        if (sources.isEmpty()) addEmpty(body, "还没有网站来源。来源可以是限定域名、搜索网址模板，或 RSS/API 地址。")
        sources.forEach { source ->
            val flags = buildList {
                add(sourceTypeLabel(source.type))
                if (source.allowFullText) add("可读正文")
                if (source.requiresLogin) add("需要登录")
            }.joinToString(" · ")
            body.addView(createActionCard(
                title = source.name.ifBlank { "未命名来源" },
                description = "$flags\n${source.value}",
                status = if (source.enabled) "已启用" else "已停用"
            ) { showSourceDialog(source) })
        }
        body.addView(createAddButton("＋ 添加网站来源") { showSourceDialog(null) })
    }

    private fun buildExternalAppsSection(body: LinearLayout, apps: List<SearchGroupStorage.ExternalSearchApp>) {
        if (apps.isEmpty()) addEmpty(body, "还没有登记外部搜索应用。外部应用目前只作为扩展登记，不参与住户的后台结构化搜索；真正的 AI 搜索由内置通道和搜索服务完成。")
        apps.forEach { app ->
            body.addView(createActionCard(
                title = app.name.ifBlank { "未命名外部应用" },
                description = "${appModeLabel(app.mode)}\n${app.packageName.ifBlank { "尚未填写包名或接口标识" }}",
                status = if (app.enabled) "已启用" else "已停用"
            ) { showExternalAppDialog(app) })
        }
        body.addView(createAddButton("＋ 登记外部搜索应用") { showExternalAppDialog(null) })
    }

    private fun buildResidentsSection(body: LinearLayout) {
        val friends = FriendStorage(this).loadFriends()
        val targets = if (focusedFriendId.isNotBlank()) {
            friends.filter { it.id == focusedFriendId }.ifEmpty {
                listOf(Friend(focusedFriendId, focusedFriendName.ifBlank { "当前住户" }))
            }
        } else {
            friends
        }

        if (targets.isEmpty()) {
            addEmpty(body, "还没有住户。等住户入住后，可以在这里逐个决定谁能搜索、读正文、使用登录态或下载文件。")
            return
        }

        targets.forEach { friend ->
            val permission = storage.loadResidentPermission(friend.id)
            body.addView(createActionCard(
                title = friend.name,
                description = permissionDescription(permission),
                status = if (permission.allowSearch) "已允许" else "未允许"
            ) { showResidentPermissionDialog(friend, permission) })
        }
    }

    private fun buildPrivacySection(body: LinearLayout, privacy: SearchGroupStorage.PrivacySettings) {
        val lines = buildList {
            add(if (privacy.saveHistory) "查询历史保留 ${privacy.historyKeepDays} 天" else "不保存查询历史")
            add(if (privacy.savePageText) "正文缓存选项已开启（预留，当前不落盘正文）" else "不保存网页正文缓存")
            add(if (privacy.redactSensitiveText) "写入历史前遮盖常见密钥与邮箱" else "不自动遮盖敏感文本")
            add(if (privacy.allowLoginState) "登录态选项已开启（预留，当前不注入 Cookie）" else "不使用网站登录态")
        }
        body.addView(createActionCard(
            title = "隐私与历史设置",
            description = lines.joinToString("\n"),
            status = "可修改"
        ) { showPrivacyDialog(privacy) })

        val history = storage.loadSearchHistory(focusedFriendId.takeIf { it.isNotBlank() })
        if (history.isNotEmpty()) {
            val latest = history.first()
            body.addView(createActionCard(
                title = "清除搜索历史",
                description = "当前 ${history.size} 条 · 最近：${latest.query.take(48)}",
                status = "立即清除"
            ) {
                confirmDelete(if (focusedFriendId.isBlank()) "清除全部住户的搜索历史？" else "清除当前住户的搜索历史？") {
                    storage.clearSearchHistory(focusedFriendId.takeIf { it.isNotBlank() })
                    buildPage()
                }
            })
        }
    }

    private fun createActionCard(
        title: String,
        description: String,
        status: String,
        onClick: () -> Unit
    ): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.settings_item_bg)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(7) }
            setOnClickListener { onClick() }

            addView(LinearLayout(this@SearchGroupActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@SearchGroupActivity).apply {
                    text = title
                    textSize = 13.5f
                    setTextColor(c.textPrimary)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(TextView(this@SearchGroupActivity).apply {
                    text = status
                    textSize = 10f
                    setTextColor(c.accentStrong)
                })
            })
            addView(TextView(this@SearchGroupActivity).apply {
                text = description
                textSize = 10.5f
                setLineSpacing(0f, 1.25f)
                setTextColor(c.textSecondary)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) }
            })
        }
    }

    private fun createAddButton(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        textSize = 12.5f
        gravity = Gravity.CENTER_VERTICAL
        setTextColor(c.accentStrong)
        background = getDrawable(R.drawable.input_bg)
        setPadding(dp(12), dp(11), dp(12), dp(11))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(2) }
        setOnClickListener { onClick() }
    }

    private fun addEmpty(parent: LinearLayout, text: String) {
        parent.addView(TextView(this).apply {
            this.text = text
            textSize = 11f
            setLineSpacing(0f, 1.3f)
            setTextColor(c.textHint)
            setPadding(dp(3), dp(2), dp(3), dp(10))
        })
    }

    private fun showGeneralDialog(current: SearchGroupStorage.GeneralSettings) {
        val layout = dialogLayout()
        val enabled = checkBox("启用搜索组总开关", current.enabled)
        val defaultProvider = checkBox("启用内置免配置搜索通道", current.useDefaultProvider)
        layout.addView(enabled)
        layout.addView(defaultProvider)
        layout.addView(TextView(this).apply {
            text = "搜索结果会分批交给住户阅读。每批最多 ${SearchGroupStorage.RESULTS_PER_BATCH} 条，住户看完后可以自己决定继续搜索、换关键词，或结束搜索；这里没有整个搜索过程的固定总上限。"
            textSize = 11f
            setLineSpacing(0f, 1.3f)
            setTextColor(c.textSecondary)
            setPadding(0, dp(8), 0, 0)
        })
        AlertDialog.Builder(this)
            .setTitle("搜索组总开关")
            .setView(wrapDialog(layout))
            .setPositiveButton("保存") { _, _ ->
                storage.saveGeneral(
                    SearchGroupStorage.GeneralSettings(
                        enabled = enabled.isChecked,
                        useDefaultProvider = defaultProvider.isChecked
                    )
                )
                buildPage()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showServiceDialog(existing: SearchGroupStorage.SearchService?) {
        val value = existing ?: SearchGroupStorage.SearchService()
        val layout = dialogLayout()
        val enabled = checkBox("启用这个服务", value.enabled)
        layout.addView(enabled)
        val name = labeledInput(layout, "显示名称", value.name, InputType.TYPE_CLASS_TEXT)
        val type = labeledSpinner(layout, "服务类型", SERVICE_TYPES, serviceTypeIndex(value.type))
        val endpoint = labeledInput(layout, "地址或网址模板", value.endpoint, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val key = labeledInput(layout, "API 密钥（可留空）", value.apiKey, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val priority = labeledInput(layout, "优先级（数字越小越先使用）", value.priority.toString(), InputType.TYPE_CLASS_NUMBER)
        layout.addView(TextView(this).apply {
            text = "SearXNG 可填写实例根地址或 /search 地址。模板和自定义 API 支持 {query}、{page}、{count}、{api_key}；若不写 {query}，归栖会自动追加 q 参数。API 密钥会同时尝试 Bearer 与 X-API-Key 请求头。"
            textSize = 10.5f
            setLineSpacing(0f, 1.3f)
            setTextColor(c.textHint)
            setPadding(0, dp(9), 0, 0)
        })

        val builder = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "添加搜索服务" else "编辑搜索服务")
            .setView(wrapDialog(layout))
            .setPositiveButton("保存") { _, _ ->
                if (name.text.toString().trim().isBlank()) {
                    toast("请先填写服务名称")
                    return@setPositiveButton
                }
                if (endpoint.text.toString().trim().isBlank()) {
                    toast("请先填写服务地址或网址模板")
                    return@setPositiveButton
                }
                storage.upsertService(
                    value.copy(
                        name = name.text.toString().trim(),
                        type = serviceTypeValue(type.selectedItemPosition),
                        endpoint = endpoint.text.toString().trim(),
                        apiKey = key.text.toString().trim(),
                        enabled = enabled.isChecked,
                        priority = priority.text.toString().toIntOrNull()?.coerceIn(1, 999) ?: 100
                    )
                )
                buildPage()
            }
            .setNegativeButton("取消", null)
        if (existing != null) builder.setNeutralButton("删除") { _, _ -> confirmDelete("删除这个搜索服务？") {
            storage.deleteService(existing.id)
            buildPage()
        } }
        builder.show()
    }

    private fun showSourceDialog(existing: SearchGroupStorage.WebsiteSource?) {
        val value = existing ?: SearchGroupStorage.WebsiteSource()
        val layout = dialogLayout()
        val enabled = checkBox("启用这个来源", value.enabled)
        val fullText = checkBox("允许读取网页正文", value.allowFullText)
        val login = checkBox("这个来源需要登录", value.requiresLogin)
        layout.addView(enabled)
        layout.addView(fullText)
        layout.addView(login)
        val name = labeledInput(layout, "来源名称", value.name, InputType.TYPE_CLASS_TEXT)
        val type = labeledSpinner(layout, "来源类型", SOURCE_TYPES, sourceTypeIndex(value.type))
        val address = labeledInput(layout, "域名、网址模板或 RSS/API 地址", value.value, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        layout.addView(TextView(this).apply {
            text = "网址模板支持 {query}、{page} 和 {count}。限定域名来源会通过当前可用搜索通道追加 site:域名 查询。住户可在工具指令中按来源名称指定它。"
            textSize = 10.5f
            setLineSpacing(0f, 1.3f)
            setTextColor(c.textHint)
            setPadding(0, dp(9), 0, 0)
        })

        val builder = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "添加网站来源" else "编辑网站来源")
            .setView(wrapDialog(layout))
            .setPositiveButton("保存") { _, _ ->
                if (name.text.toString().trim().isBlank()) {
                    toast("请先填写来源名称")
                    return@setPositiveButton
                }
                if (address.text.toString().trim().isBlank()) {
                    toast("请先填写域名、网址模板或 RSS/API 地址")
                    return@setPositiveButton
                }
                storage.upsertSource(
                    value.copy(
                        name = name.text.toString().trim(),
                        type = sourceTypeValue(type.selectedItemPosition),
                        value = address.text.toString().trim(),
                        enabled = enabled.isChecked,
                        allowFullText = fullText.isChecked,
                        requiresLogin = login.isChecked
                    )
                )
                buildPage()
            }
            .setNegativeButton("取消", null)
        if (existing != null) builder.setNeutralButton("删除") { _, _ -> confirmDelete("删除这个网站来源？") {
            storage.deleteSource(existing.id)
            buildPage()
        } }
        builder.show()
    }

    private fun showExternalAppDialog(existing: SearchGroupStorage.ExternalSearchApp?) {
        val value = existing ?: SearchGroupStorage.ExternalSearchApp()
        val layout = dialogLayout()
        val enabled = checkBox("启用这个外部应用配置", value.enabled)
        layout.addView(enabled)
        val name = labeledInput(layout, "应用名称", value.name, InputType.TYPE_CLASS_TEXT)
        val packageName = labeledInput(layout, "包名或接口标识", value.packageName, InputType.TYPE_CLASS_TEXT)
        val mode = labeledSpinner(layout, "连接方式", APP_MODES, appModeIndex(value.mode))

        val builder = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "登记外部搜索应用" else "编辑外部搜索应用")
            .setView(wrapDialog(layout))
            .setPositiveButton("保存") { _, _ ->
                if (name.text.toString().trim().isBlank()) {
                    toast("请先填写应用名称")
                    return@setPositiveButton
                }
                storage.upsertExternalApp(
                    value.copy(
                        name = name.text.toString().trim(),
                        packageName = packageName.text.toString().trim(),
                        mode = appModeValue(mode.selectedItemPosition),
                        enabled = enabled.isChecked
                    )
                )
                buildPage()
            }
            .setNegativeButton("取消", null)
        if (existing != null) builder.setNeutralButton("删除") { _, _ -> confirmDelete("删除这个外部应用配置？") {
            storage.deleteExternalApp(existing.id)
            buildPage()
        } }
        builder.show()
    }

    private fun showResidentPermissionDialog(
        friend: Friend,
        current: SearchGroupStorage.ResidentPermission
    ) {
        val layout = dialogLayout()
        val allowSearch = checkBox("允许这位住户使用联网搜索", current.allowSearch)
        val allServices = checkBox("可使用全部已启用搜索服务", current.useAllServices)
        val allSources = checkBox("可使用全部已启用网站来源", current.useAllSources)
        val fullText = checkBox("可读取搜索结果正文", current.allowFullText)
        val login = checkBox("登录态权限（预留，当前不注入 Cookie）", current.allowLogin)
        val downloads = checkBox("下载权限（预留，当前不自动下载文件）", current.allowDownloads)
        val history = checkBox("允许保存这位住户的搜索历史", current.allowHistory)
        listOf(allowSearch, allServices, allSources, fullText, login, downloads, history).forEach(layout::addView)
        layout.addView(TextView(this).apply {
            text = "搜索工具已经会读取这些权限。逐项服务／来源白名单尚未补齐，因此取消“全部服务”后不会擅自调用自定义服务，但仍可使用允许的内置通道。"
            textSize = 10.5f
            setLineSpacing(0f, 1.3f)
            setTextColor(c.textHint)
            setPadding(0, dp(8), 0, 0)
        })

        AlertDialog.Builder(this)
            .setTitle("${friend.name}的联网与搜索权限")
            .setView(wrapDialog(layout))
            .setPositiveButton("保存") { _, _ ->
                storage.saveResidentPermission(
                    current.copy(
                        allowSearch = allowSearch.isChecked,
                        useAllServices = allServices.isChecked,
                        useAllSources = allSources.isChecked,
                        allowFullText = fullText.isChecked,
                        allowLogin = login.isChecked,
                        allowDownloads = downloads.isChecked,
                        allowHistory = history.isChecked
                    )
                )
                buildPage()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showPrivacyDialog(current: SearchGroupStorage.PrivacySettings) {
        val layout = dialogLayout()
        val history = checkBox("保存查询历史", current.saveHistory)
        val pageText = checkBox("正文缓存（预留，当前不落盘正文）", current.savePageText)
        val redact = checkBox("写入历史前遮盖常见密钥与邮箱", current.redactSensitiveText)
        val loginState = checkBox("网站登录态（预留，当前不注入 Cookie）", current.allowLoginState)
        layout.addView(history)
        layout.addView(pageText)
        layout.addView(redact)
        layout.addView(loginState)
        val days = labeledInput(layout, "历史保留天数", current.historyKeepDays.toString(), InputType.TYPE_CLASS_NUMBER)

        AlertDialog.Builder(this)
            .setTitle("隐私与历史")
            .setView(wrapDialog(layout))
            .setPositiveButton("保存") { _, _ ->
                storage.savePrivacy(
                    SearchGroupStorage.PrivacySettings(
                        saveHistory = history.isChecked,
                        historyKeepDays = days.text.toString().toIntOrNull()?.coerceIn(1, 3650) ?: 30,
                        savePageText = pageText.isChecked,
                        redactSensitiveText = redact.isChecked,
                        allowLoginState = loginState.isChecked
                    )
                )
                buildPage()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun dialogLayout(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(10), dp(20), dp(12))
    }

    private fun wrapDialog(content: View): ScrollView = ScrollView(this).apply { addView(content) }

    private fun checkBox(label: String, checked: Boolean): CheckBox = CheckBox(this).apply {
        text = label
        isChecked = checked
        textSize = 12.5f
        setTextColor(c.textPrimary)
        setPadding(0, dp(3), 0, dp(3))
    }

    private fun labeledInput(
        parent: LinearLayout,
        label: String,
        value: String,
        inputType: Int
    ): EditText {
        parent.addView(TextView(this).apply {
            text = label
            textSize = 11f
            setTextColor(c.textSecondary)
            setPadding(0, dp(9), 0, dp(4))
        })
        return EditText(this).apply {
            setText(value)
            this.inputType = inputType
            textSize = 13f
            setTextColor(c.textPrimary)
            setHintTextColor(c.textHint)
            background = getDrawable(R.drawable.input_bg)
            setPadding(dp(11), dp(9), dp(11), dp(9))
            if (inputType and InputType.TYPE_TEXT_VARIATION_PASSWORD != 0) {
                setSelection(text.length)
            }
            parent.addView(this, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
    }

    private fun labeledSpinner(
        parent: LinearLayout,
        label: String,
        values: Array<String>,
        selected: Int
    ): Spinner {
        parent.addView(TextView(this).apply {
            text = label
            textSize = 11f
            setTextColor(c.textSecondary)
            setPadding(0, dp(9), 0, dp(4))
        })
        return Spinner(this).apply {
            adapter = ArrayAdapter(
                this@SearchGroupActivity,
                android.R.layout.simple_spinner_dropdown_item,
                values
            )
            setSelection(selected.coerceIn(0, values.lastIndex))
            background = getDrawable(R.drawable.input_bg)
            parent.addView(this, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(44)
            ))
        }
    }

    private fun confirmDelete(message: String, action: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("确认删除")
            .setMessage(message)
            .setPositiveButton("删除") { _, _ -> action() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun permissionDescription(value: SearchGroupStorage.ResidentPermission): String {
        if (!value.allowSearch) return "不能使用搜索组；其他细分权限暂不生效。"
        return buildList {
            add(if (value.useAllServices) "全部服务" else "指定服务")
            add(if (value.useAllSources) "全部来源" else "指定来源")
            if (value.allowFullText) add("可读正文")
            if (value.allowLogin) add("登录态预留")
            if (value.allowDownloads) add("下载预留")
            if (value.allowHistory) add("记搜索历史")
        }.joinToString(" · ")
    }

    private fun maskEndpoint(value: String): String = when {
        value.isBlank() -> "尚未填写地址"
        value.length <= 72 -> value
        else -> value.take(69) + "…"
    }

    private fun serviceTypeLabel(value: String): String = when (value) {
        SearchGroupStorage.TYPE_GENERIC_API -> "自定义 JSON API"
        SearchGroupStorage.TYPE_URL_TEMPLATE -> "搜索网址模板"
        else -> "SearXNG"
    }

    private fun sourceTypeLabel(value: String): String = when (value) {
        SearchGroupStorage.SOURCE_SEARCH_URL -> "搜索网址模板"
        SearchGroupStorage.SOURCE_RSS_API -> "RSS / API"
        else -> "限定域名"
    }

    private fun appModeLabel(value: String): String = when (value) {
        SearchGroupStorage.APP_STRUCTURED -> "结构化结果（预留）"
        else -> "只负责打开应用"
    }

    private fun serviceTypeIndex(value: String): Int = when (value) {
        SearchGroupStorage.TYPE_GENERIC_API -> 1
        SearchGroupStorage.TYPE_URL_TEMPLATE -> 2
        else -> 0
    }

    private fun serviceTypeValue(index: Int): String = when (index) {
        1 -> SearchGroupStorage.TYPE_GENERIC_API
        2 -> SearchGroupStorage.TYPE_URL_TEMPLATE
        else -> SearchGroupStorage.TYPE_SEARXNG
    }

    private fun sourceTypeIndex(value: String): Int = when (value) {
        SearchGroupStorage.SOURCE_SEARCH_URL -> 1
        SearchGroupStorage.SOURCE_RSS_API -> 2
        else -> 0
    }

    private fun sourceTypeValue(index: Int): String = when (index) {
        1 -> SearchGroupStorage.SOURCE_SEARCH_URL
        2 -> SearchGroupStorage.SOURCE_RSS_API
        else -> SearchGroupStorage.SOURCE_DOMAIN
    }

    private fun appModeIndex(value: String): Int = if (value == SearchGroupStorage.APP_STRUCTURED) 1 else 0
    private fun appModeValue(index: Int): String = if (index == 1) SearchGroupStorage.APP_STRUCTURED else SearchGroupStorage.APP_OPEN_ONLY

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_FRIEND_ID = "friend_id"
        const val EXTRA_FRIEND_NAME = "friend_name"

        private const val SECTION_GENERAL = "general"
        private const val SECTION_SERVICES = "services"
        private const val SECTION_SOURCES = "sources"
        private const val SECTION_APPS = "apps"
        private const val SECTION_RESIDENTS = "residents"
        private const val SECTION_PRIVACY = "privacy"

        private val SERVICE_TYPES = arrayOf("SearXNG", "自定义 JSON API", "搜索网址模板")
        private val SOURCE_TYPES = arrayOf("限定域名", "搜索网址模板", "RSS / API")
        private val APP_MODES = arrayOf("只负责打开应用（预留）", "结构化结果（预留）")
    }
}
