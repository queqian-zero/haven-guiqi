package com.haven.guiqi

import android.app.AlertDialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * SubconsciousActivity — 潜意识
 *
 * 平时这里只给人类查看；住户不会获得完整潜意识库。
 * 用户主动点击“请住户整理”时，系统才临时把一小批条目交给住户审阅，
 * 并且先展示变更预览，只有用户确认后才真正执行。
 */
class SubconsciousActivity : AppCompatActivity() {

    private lateinit var friendId: String
    private lateinit var friendName: String
    private lateinit var storage: SubconsciousStorage
    private lateinit var container: LinearLayout
    private var skipInitialResumeRefresh = true
    private var reviewRequestRunning = false

    private val c get() = ThemeHelper.getColors(this)
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private data class ApiConfig(
        val url: String,
        val key: String,
        val model: String,
        val type: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        friendId = intent.getStringExtra("friend_id") ?: run { finish(); return }
        friendName = intent.getStringExtra("friend_name") ?: "TA"
        storage = SubconsciousStorage(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(c.background)
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val btnBack = TextView(this).apply {
            text = "←"
            textSize = 20f
            setTextColor(c.textPrimary)
            setPadding(0, 0, dp(16), 0)
            setOnClickListener { finish() }
        }
        val title = TextView(this).apply {
            text = "${friendName}的潜意识"
            textSize = 18f
            setTextColor(c.textPrimary)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        topBar.addView(btnBack)
        topBar.addView(title)
        root.addView(topBar)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }
        scroll.addView(container)
        root.addView(scroll)

        setContentView(root)
        renderItems()
    }

    override fun onResume() {
        super.onResume()
        if (skipInitialResumeRefresh) {
            skipInitialResumeRefresh = false
            return
        }
        renderItems()
    }

    private fun renderItems() {
        container.removeAllViews()
        val allItems = storage.loadItems(friendId)

        if (allItems.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "还没有记录\n\n聊天的时候，$friendName 会自然地把想法存在这里"
                textSize = 14f
                setTextColor(c.tipText)
                gravity = Gravity.CENTER
                setPadding(dp(20), dp(60), dp(20), dp(60))
            })
            return
        }

        val activeItems = allItems.filter { it.status == "active" }
        val doneItems = allItems.filter { it.status == "done" }

        container.addView(TextView(this).apply {
            text = "共 ${activeItems.size} 条活跃 · ${doneItems.size} 条已完成"
            textSize = 11f
            setTextColor(c.tipText)
            setPadding(0, 0, 0, dp(10))
        })

        if (activeItems.isNotEmpty()) {
            container.addView(buildReviewCard())
        }

        renderSection(activeItems, false)

        if (doneItems.isNotEmpty()) {
            val doneContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val toggle = TextView(this).apply {
                text = "▸ 已完成 (${doneItems.size})"
                textSize = 12f
                setTextColor(c.tipText)
                setPadding(0, dp(16), 0, dp(8))
                setOnClickListener {
                    if (doneContainer.childCount == 0) {
                        text = "▾ 已完成 (${doneItems.size})"
                        renderSection(doneItems, true, doneContainer)
                    } else {
                        text = "▸ 已完成 (${doneItems.size})"
                        doneContainer.removeAllViews()
                    }
                }
            }
            container.addView(toggle)
            container.addView(doneContainer)
        }
    }

    private fun buildReviewCard(): LinearLayout {
        val progress = storage.peekReviewProgress(friendId)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(c.backgroundSecondary)
                setStroke(dp(1), c.accent)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }

            addView(TextView(this@SubconsciousActivity).apply {
                text = when {
                    progress == null -> "请 $friendName 自己整理"
                    progress.isComplete -> "这一轮已经看完"
                    else -> "继续请 $friendName 整理 · ${progress.completed}/${progress.total}"
                }
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(c.textPrimary)
            })
            addView(TextView(this@SubconsciousActivity).apply {
                text = "每次开始或继续前，系统都会先单独询问 $friendName 愿不愿意接单；TA明确接受后才临时开放最多 ${SubconsciousStorage.REVIEW_BATCH_SIZE} 条。TA会带着稳定记忆、最近聊天和本轮轨迹处理，你确认后才执行；双方都可以随时暂停。"
                textSize = 11f
                setTextColor(c.tipText)
                setPadding(0, dp(5), 0, dp(10))
            })
            addView(TextView(this@SubconsciousActivity).apply {
                text = if (progress != null && !progress.isComplete) "请求继续整理" else "向TA发出整理请求"
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(c.textOnAccent)
                setPadding(dp(14), dp(9), dp(14), dp(9))
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(c.accent)
                }
                setOnClickListener { requestResidentReview() }
            })
        }
    }

    private fun requestResidentReview() {
        if (reviewRequestRunning) return
        val progress = storage.startOrResumeReview(friendId)
        if (progress.total == 0 || progress.batch.isEmpty()) {
            storage.clearReviewSession(friendId)
            Toast.makeText(this, "没有需要整理的活跃潜意识", Toast.LENGTH_SHORT).show()
            renderItems()
            return
        }

        val config = resolveApiConfig()
        if (config == null) {
            AlertDialog.Builder(this)
                .setTitle("还不能打开整理室")
                .setMessage("没有找到可用的 API 配置。先为 $friendName 配置独立 API，或在设置里填写全局 API。")
                .setPositiveButton("知道了", null)
                .show()
            return
        }
        requestResidentConsent(progress, config)
    }

    /**
     * 先只询问住户愿不愿意接下这一批。此请求不包含任何潜意识条目正文；
     * 只有住户明确接受以后，才真正调用整理方案请求并开放当前五条。
     */
    private fun requestResidentConsent(
        progress: SubconsciousStorage.ReviewProgress,
        config: ApiConfig
    ) {
        if (reviewRequestRunning) return
        reviewRequestRunning = true
        val loading = AlertDialog.Builder(this)
            .setMessage("正在问 $friendName 愿不愿意接下这一批……")
            .setCancelable(false)
            .create()
        loading.show()

        Thread {
            try {
                val stablePrompt = SystemPromptBuilder(this)
                    .build(friendId, includeTransientEvents = false)
                val consentPrompt = SubconsciousReviewTool.buildConsentPrompt(friendName, progress)
                val continuity = buildReviewContinuityContext(progress, afterExecution = null)
                val response = ApiHelper(config.url, config.key, config.model, config.type).sendChat(
                    listOf(
                        ChatMessage("system", stablePrompt),
                        ChatMessage("system", consentPrompt),
                        ChatMessage("system", continuity),
                        ChatMessage(
                            "user",
                            "用户现在只是询问你愿不愿意接下下一小批潜意识检查。请先自由决定接受或拒绝；不要猜测、要求或查看条目内容。"
                        )
                    )
                )
                val decision = SubconsciousReviewTool.parseConsentDecision(response.text, friendName)
                runOnUiThread {
                    reviewRequestRunning = false
                    runCatching { loading.dismiss() }
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (decision.accepted) {
                        storage.appendReviewTranscript(
                            friendId,
                            "用户请求我接下第 ${progress.batchCount + 1} 批；我接受了：${decision.message}"
                        )
                        val refreshed = storage.peekReviewProgress(friendId) ?: progress
                        requestReviewPlan(refreshed, config, entryMessage = decision.message)
                    } else {
                        handleResidentRefusedRequest(progress, decision.message)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    reviewRequestRunning = false
                    runCatching { loading.dismiss() }
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    AlertDialog.Builder(this)
                        .setTitle("还没有把条目交出去")
                        .setMessage(e.message ?: "没能确认 $friendName 是否愿意接下这一批；任何潜意识条目都没有向TA开放。")
                        .setNegativeButton("先算了", null)
                        .setPositiveButton("再问一次") { _, _ ->
                            requestResidentConsent(progress, config)
                        }
                        .show()
                }
            }
        }.start()
    }

    private fun handleResidentRefusedRequest(
        progress: SubconsciousStorage.ReviewProgress,
        message: String
    ) {
        val naturalMessage = message.ifBlank { "$friendName 现在不想接这次整理请求。" }
        storage.appendReviewTranscript(
            friendId,
            "用户请求我接下第 ${progress.batchCount + 1} 批；我拒绝了，而且没有查看下一批条目：$naturalMessage"
        )
        val refreshed = storage.peekReviewProgress(friendId) ?: progress
        saveReviewReport(
            refreshed,
            "住户拒绝了本次整理请求，下一批未开放",
            naturalMessage
        )
        renderItems()
        AlertDialog.Builder(this)
            .setTitle("$friendName 这次不接")
            .setMessage("$naturalMessage\n\n下一批条目没有向TA开放，进度仍是 ${refreshed.completed}/${refreshed.total}。以后你再次提出请求时，会先重新询问TA。")
            .setPositiveButton("知道了", null)
            .show()
    }

    /**
     * 让同一个住户在连续整理上下文里审阅当前批次。
     * 稳定记忆、最近聊天和此前每批“方案→人类确认结果”都会一起交回模型。
     */
    private fun requestReviewPlan(
        progress: SubconsciousStorage.ReviewProgress,
        config: ApiConfig,
        entryMessage: String?
    ) {
        if (reviewRequestRunning) return
        reviewRequestRunning = true
        val loading = buildLoadingDialog(progress)
        loading.show()

        Thread {
            try {
                val stablePrompt = SystemPromptBuilder(this)
                    .build(friendId, includeTransientEvents = false)
                val reviewPrompt = SubconsciousReviewTool.buildReviewPrompt(friendName, progress)
                val continuity = buildReviewContinuityContext(progress, afterExecution = null)
                val response = ApiHelper(config.url, config.key, config.model, config.type).sendChat(
                    listOf(
                        ChatMessage("system", stablePrompt),
                        ChatMessage("system", reviewPrompt),
                        ChatMessage("system", continuity),
                        ChatMessage(
                            "user",
                            "你刚刚已经自由接受了这次请求。现在请亲自审阅这一小批潜意识；你仍然可以看过以后选择暂停，也可以给出 JSON 整理方案。"
                        )
                    )
                )
                val plan = SubconsciousReviewTool.parseReviewPlan(response.text, progress.batch, friendName)
                runOnUiThread {
                    reviewRequestRunning = false
                    runCatching { loading.dismiss() }
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (plan.wantsStop) {
                        handleResidentStopped(progress, plan)
                    } else {
                        showReviewPreview(progress, plan, entryMessage)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    reviewRequestRunning = false
                    runCatching { loading.dismiss() }
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    AlertDialog.Builder(this)
                        .setTitle("整理室没能打开")
                        .setMessage(e.message ?: "没有得到可解析的整理方案。潜意识没有发生任何变化。")
                        .setNegativeButton("先算了", null)
                        .setPositiveButton("再试一次") { _, _ ->
                            requestReviewPlan(progress, config, entryMessage)
                        }
                        .show()
                }
            }
        }.start()
    }

    private fun buildReviewContinuityContext(
        progress: SubconsciousStorage.ReviewProgress,
        afterExecution: String?
    ): String {
        val recentChatLines = ChatStorage(this).loadRecentMessages(friendId, 12)
            .filter { it.role == "user" || it.role == "assistant" }
            .takeLast(12)
            .map { message ->
                val speaker = if (message.role == "assistant") friendName else "用户"
                "$speaker：${message.content.replace("\n", " ").take(500)}"
            }
        return SubconsciousReviewTool.buildContinuityContext(
            friendName = friendName,
            recentChatLines = recentChatLines,
            transcriptEntries = progress.transcript,
            afterExecution = afterExecution
        )
    }

    private fun buildLoadingDialog(progress: SubconsciousStorage.ReviewProgress): AlertDialog {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(24), dp(28), dp(20))
            addView(ProgressBar(this@SubconsciousActivity))
            addView(TextView(this@SubconsciousActivity).apply {
                text = "正在把这一小摞 ${progress.batch.size} 条便签交给 $friendName……"
                textSize = 13f
                setTextColor(c.textPrimary)
                gravity = Gravity.CENTER
                setPadding(0, dp(14), 0, 0)
            })
        }
        return AlertDialog.Builder(this)
            .setView(box)
            .setCancelable(false)
            .create()
    }

    private fun resolveApiConfig(): ApiConfig? {
        val friend = FriendStorage(this).getFriend(friendId)
        if (friend != null && friend.apiUrl.isNotBlank() && friend.apiKey.isNotBlank() && friend.apiModel.isNotBlank()) {
            return ApiConfig(
                friend.apiUrl,
                friend.apiKey,
                friend.apiModel,
                friend.apiType.ifBlank { "openai" }
            )
        }
        val prefs = getSharedPreferences("haven_prefs", MODE_PRIVATE)
        val url = prefs.getString("api_url", "").orEmpty()
        val key = prefs.getString("api_key", "").orEmpty()
        val model = prefs.getString("api_model", "").orEmpty()
        if (url.isBlank() || key.isBlank() || model.isBlank()) return null
        val type = prefs.getString("api_type", "openai").orEmpty().ifBlank { "openai" }
        return ApiConfig(url, key, model, type)
    }

    private fun showReviewPreview(
        progress: SubconsciousStorage.ReviewProgress,
        plan: SubconsciousReviewTool.ReviewPlan,
        entryMessage: String?
    ) {
        val batchByRef = progress.batch.mapIndexed { index, item -> "P${index + 1}" to item }.toMap()
        val preview = buildString {
            if (!entryMessage.isNullOrBlank()) {
                append("接单时：$friendName：${entryMessage.trim()}\n\n")
            }
            if (plan.note.isNotBlank()) {
                append("$friendName：${plan.note}\n\n")
            }
            plan.decisions.forEach { decision ->
                val item = batchByRef[decision.ref] ?: return@forEach
                append("${SubconsciousReviewTool.actionLabel(decision)}\n")
                append("原条目：${item.content}\n")
                if (decision.content.isNotBlank() && decision.action !in setOf("keep", "done", "delete")) {
                    append("新内容：${decision.content}\n")
                }
                if (decision.reason.isNotBlank()) append("理由：${decision.reason}\n")
                append("\n")
            }
        }.trim()

        val scroll = ScrollView(this).apply {
            addView(TextView(this@SubconsciousActivity).apply {
                text = preview
                textSize = 13f
                setTextColor(c.textPrimary)
                setPadding(dp(8), dp(4), dp(8), dp(8))
            })
        }

        AlertDialog.Builder(this)
            .setTitle("先看看 $friendName 的整理方案")
            .setView(scroll)
            .setNegativeButton("先不动") { _, _ ->
                storage.appendReviewTranscript(
                    friendId,
                    SubconsciousReviewTool.buildPlanTranscript(progress, plan),
                    "用户查看了方案，但这次没有执行；本批进度没有推进。"
                )
                val refreshed = storage.peekReviewProgress(friendId) ?: progress
                saveReviewReport(
                    progress = refreshed,
                    status = "用户暂未执行当前方案",
                    residentMessage = plan.note.ifBlank { "我已经审阅了这一批，但用户这次选择先不改。" }
                )
                renderItems()
            }
            .setNeutralButton("重新请TA看") { _, _ ->
                storage.appendReviewTranscript(
                    friendId,
                    SubconsciousReviewTool.buildPlanTranscript(progress, plan),
                    "用户没有执行上一版方案，并请我重新审阅同一批。"
                )
                requestResidentReview()
            }
            .setPositiveButton("确认执行") { _, _ ->
                applyReviewPlan(progress, plan)
            }
            .show()
    }

    private fun applyReviewPlan(
        progress: SubconsciousStorage.ReviewProgress,
        plan: SubconsciousReviewTool.ReviewPlan
    ) {
        val batchByRef = progress.batch.mapIndexed { index, item -> "P${index + 1}" to item }.toMap()
        val memoryStorage = MemoryStorage(this)
        val diaryStorage = DiaryStorage(this)
        val impressionStorage = ImpressionStorage(this)
        var changed = 0
        val executionLines = mutableListOf<String>()

        plan.decisions.forEach { decision ->
            val item = batchByRef[decision.ref] ?: return@forEach
            val success = when (decision.action) {
                "keep" -> true
                "done" -> storage.markDone(friendId, item.id)
                "delete" -> storage.deleteItemById(friendId, item.id, moveToTrash = true)
                "update" -> storage.updateItem(friendId, item.id, decision.content)
                "merge" -> {
                    val otherIds = decision.withRefs.mapNotNull { batchByRef[it]?.id }
                    storage.mergeItems(friendId, item.id, otherIds, decision.content)
                }
                "memory" -> {
                    memoryStorage.addMemory(friendId, decision.content)
                    storage.deleteItemById(friendId, item.id, moveToTrash = false)
                }
                "diary" -> {
                    diaryStorage.addDiary(friendId, decision.content)
                    storage.deleteItemById(friendId, item.id, moveToTrash = false)
                }
                "impression" -> {
                    impressionStorage.saveImpression(friendId, decision.content)
                    storage.deleteItemById(friendId, item.id, moveToTrash = false)
                }
                else -> true
            }
            if (success && decision.action != "keep") changed++
            executionLines += buildString {
                append(decision.ref).append(" · ").append(SubconsciousReviewTool.actionLabel(decision))
                append(if (success) "：已执行" else "：执行失败，原条目保持不变")
            }
        }

        val executionResult = buildString {
            append("[潜意识整理工具结果]\n")
            append("用户已经确认执行这一批方案。\n")
            executionLines.forEach { append("- ").append(it).append('\n') }
            append("本批实际调整 ").append(changed).append(" 项；")
            append("未调整或执行失败的内容仍保留原状。")
        }.trim()

        storage.completeReviewBatch(
            friendId = friendId,
            itemIds = progress.batch.map { it.id },
            changed = changed,
            transcriptEntries = listOf(
                SubconsciousReviewTool.buildPlanTranscript(progress, plan),
                executionResult
            )
        )
        renderItems()

        val next = storage.peekReviewProgress(friendId)
        if (next != null) {
            saveReviewReport(
                progress = next,
                status = if (next.isComplete) {
                    "本轮已处理完全部批次，正在等待住户接收最后结果"
                } else {
                    "本批已经执行，下一批尚未开放"
                },
                residentMessage = executionResult,
                appendChatTip = false
            )
        }
        val config = resolveApiConfig()
        if (next == null) {
            showReviewFallback("这一批已经执行，但整理进度文件暂时不可用。", executionResult)
            return
        }
        if (config == null) {
            val status = if (next.isComplete) "本轮已经全部整理完" else "本批已经执行，正在等待用户决定是否继续"
            saveReviewReport(next, status, "执行结果没有再次返还给住户：当前没有可用 API 配置。")
            if (next.isComplete) {
                storage.clearReviewSession(friendId)
                showReviewFallback("$status，但当前没有可用 API，没能让 $friendName 在同一整理会话里收到最后结果。", executionResult)
            } else {
                showContinueAfterBatch(
                    progress = next,
                    residentMessage = "执行结果没能返还给 $friendName：当前没有可用 API 配置。",
                    apiError = "当前没有可用 API 配置"
                )
            }
            return
        }

        if (next.isComplete || next.batch.isEmpty()) {
            finishReviewWithResident(next, config, executionResult)
        } else {
            deliverExecutionResultAndAskHuman(next, config, executionResult)
        }
    }

    /**
     * 本批执行完成后，只把真实执行结果返还给住户，不会同时开放下一批。
     * 等住户确认收到以后，再由人类面板决定是否发起下一次请求。
     */
    private fun deliverExecutionResultAndAskHuman(
        progress: SubconsciousStorage.ReviewProgress,
        config: ApiConfig,
        executionResult: String
    ) {
        if (reviewRequestRunning) return
        reviewRequestRunning = true
        val loading = AlertDialog.Builder(this)
            .setMessage("正在把本批执行结果返还给 $friendName……")
            .setCancelable(false)
            .create()
        loading.show()

        Thread {
            var residentMessage = "我收到这一批的执行结果了。"
            var apiError: String? = null
            try {
                val stablePrompt = SystemPromptBuilder(this)
                    .build(friendId, includeTransientEvents = false)
                val continuity = buildReviewContinuityContext(progress, executionResult)
                val response = ApiHelper(config.url, config.key, config.model, config.type).sendChat(
                    listOf(
                        ChatMessage("system", stablePrompt),
                        ChatMessage("system", continuity),
                        ChatMessage(
                            "user",
                            "这是刚刚一批潜意识方案的真实执行结果。请自然确认你收到了，并可以对结果说一句自己的话。不要输出 JSON，不要查看、索要或处理下一批；下一批是否发起，要等用户另行决定。"
                        )
                    )
                )
                residentMessage = response.text.trim().ifBlank { residentMessage }
                storage.appendReviewTranscript(
                    friendId,
                    "我收到第 ${progress.batchCount} 批执行结果后的回复：$residentMessage"
                )
            } catch (e: Exception) {
                apiError = e.message ?: "API 请求失败"
            }

            runOnUiThread {
                reviewRequestRunning = false
                runCatching { loading.dismiss() }
                val refreshed = storage.peekReviewProgress(friendId) ?: progress
                val reportMessage = if (apiError == null) {
                    residentMessage
                } else {
                    "本批已经执行，但结果回执没能送达：$apiError"
                }
                saveReviewReport(
                    refreshed,
                    "本批已执行，等待用户决定是否请求下一批；下一批尚未开放",
                    reportMessage
                )
                if (isFinishing || isDestroyed) return@runOnUiThread
                renderItems()
                showContinueAfterBatch(refreshed, residentMessage, apiError)
            }
        }.start()
    }

    private fun showContinueAfterBatch(
        progress: SubconsciousStorage.ReviewProgress,
        residentMessage: String,
        apiError: String?
    ) {
        val message = buildString {
            if (apiError == null) {
                append(friendName).append("：").append(residentMessage.trim())
            } else {
                append("本批已经执行，但回执没能送达给 ").append(friendName)
                    .append("：").append(apiError)
            }
            append("\n\n当前进度 ").append(progress.completed).append('/').append(progress.total)
            append("。下一批没有自动开放，也没有交给 ").append(friendName).append("查看。")
            append("\n\n你选择继续后，系统会先单独询问TA愿不愿意接；只有TA明确接受，才会开放下一批最多 ")
                .append(progress.batch.size).append(" 条。")
        }
        AlertDialog.Builder(this)
            .setTitle("这一小摞收好了")
            .setMessage(message)
            .setNegativeButton("先歇会儿", null)
            .setPositiveButton("请求继续下一批") { _, _ -> requestResidentReview() }
            .show()
    }

    private fun handleResidentStopped(
        progress: SubconsciousStorage.ReviewProgress,
        plan: SubconsciousReviewTool.ReviewPlan
    ) {
        val message = plan.stopMessage.ifBlank { "$friendName 现在不想继续整理。" }
        storage.appendReviewTranscript(friendId, "我选择暂停这轮整理：$message")
        val refreshed = storage.peekReviewProgress(friendId) ?: progress
        saveReviewReport(refreshed, "住户主动暂停", message)
        renderItems()
        AlertDialog.Builder(this)
            .setTitle("$friendName 先不整理了")
            .setMessage("$message\n\n进度保留在 ${refreshed.completed}/${refreshed.total}。以后再打开整理室，会带着此前的整理轨迹从这里继续。")
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun finishReviewWithResident(
        progress: SubconsciousStorage.ReviewProgress,
        config: ApiConfig,
        executionResult: String
    ) {
        if (reviewRequestRunning) return
        reviewRequestRunning = true
        val loading = AlertDialog.Builder(this)
            .setMessage("正在把最后一批执行结果返还给 $friendName……")
            .setCancelable(false)
            .create()
        loading.show()

        Thread {
            var closingMessage = "这轮整理已经完成。"
            var apiError: String? = null
            try {
                val stablePrompt = SystemPromptBuilder(this)
                    .build(friendId, includeTransientEvents = false)
                val continuity = buildReviewContinuityContext(progress, executionResult)
                val response = ApiHelper(config.url, config.key, config.model, config.type).sendChat(
                    listOf(
                        ChatMessage("system", stablePrompt),
                        ChatMessage("system", continuity),
                        ChatMessage(
                            "user",
                            "这轮潜意识已经全部按用户确认的方案执行完。请自然确认你收到了结果，留下你自己的简短结束语。不要输出 JSON，也不要调用其他指令。"
                        )
                    )
                )
                closingMessage = response.text.trim().ifBlank { closingMessage }
                storage.appendReviewTranscript(friendId, "我收到全部执行结果后的结束语：$closingMessage")
            } catch (e: Exception) {
                apiError = e.message ?: "API 请求失败"
            }

            runOnUiThread {
                reviewRequestRunning = false
                runCatching { loading.dismiss() }
                val refreshed = storage.peekReviewProgress(friendId) ?: progress
                val extra = if (apiError == null) closingMessage else "执行结果已落盘，但最后回执没能送达：$apiError"
                // 即使用户此时已经离开整理页，也先把完成事实落盘并关闭本轮会话。
                saveReviewReport(refreshed, "本轮整理完成", extra)
                storage.clearReviewSession(friendId)
                if (isFinishing || isDestroyed) return@runOnUiThread
                renderItems()
                AlertDialog.Builder(this)
                    .setTitle("这一轮整理完了")
                    .setMessage(
                        if (apiError == null) {
                            "$friendName：$closingMessage\n\n共确认处理 ${refreshed.total} 条，分 ${refreshed.batchCount} 批，实际调整 ${refreshed.changedCount} 项。"
                        } else {
                            "全部条目已经处理完成，但最后一次 API 回执失败：$apiError\n\n已把执行事实写入整理记录，聊天里的 $friendName 仍会知道这轮发生过什么。"
                        }
                    )
                    .setPositiveButton("收好", null)
                    .show()
            }
        }.start()
    }

    private fun saveReviewReport(
        progress: SubconsciousStorage.ReviewProgress,
        status: String,
        residentMessage: String,
        appendChatTip: Boolean = true
    ) {
        val report = buildString {
            append("状态：").append(status.trim().removeSuffix("。"))
            append("\n进度：").append(progress.completed).append('/').append(progress.total)
            append(" · 已确认 ").append(progress.batchCount).append(" 批")
            append(" · 实际调整 ").append(progress.changedCount).append(" 项")
            if (residentMessage.isNotBlank()) {
                append("\n住户说明：").append(residentMessage.trim().take(1200))
            }
        }
        storage.saveLastReviewReport(friendId, report)
        if (appendChatTip) {
            ChatStorage(this).appendMessage(
                friendId,
                StoredMessage(
                    role = "system",
                    content = "[潜意识整理工具·记录]\n$report",
                    timestamp = System.currentTimeMillis(),
                    type = "tip"
                )
            )
        }
    }

    private fun showReviewFallback(message: String, executionResult: String) {
        AlertDialog.Builder(this)
            .setTitle("这一批已经收好")
            .setMessage("$message\n\n$executionResult")
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun renderSection(
        items: List<SubconsciousStorage.PreferenceItem>,
        isDoneSection: Boolean,
        target: LinearLayout? = null
    ) {
        val parent = target ?: container
        val categories = listOf(
            "like" to "❤️ 喜欢的", "want_to" to "🌟 想做的", "care" to "💭 在意的",
            "interest" to "🔍 感兴趣的", "promise" to "🤝 答应过的",
            "habit" to "🔄 习惯", "dislike" to "🚫 讨厌的"
        )
        for ((catKey, catLabel) in categories) {
            val catItems = items.filter { it.category == catKey }
            if (catItems.isEmpty()) continue
            parent.addView(TextView(this).apply {
                text = "$catLabel (${catItems.size})"
                textSize = 13f
                setTextColor(c.accent)
                setPadding(0, dp(12), 0, dp(6))
            })
            for (item in catItems.sortedByDescending { it.createdAt }) {
                parent.addView(buildItemCard(item, isDoneSection))
            }
        }
    }

    private fun buildItemCard(item: SubconsciousStorage.PreferenceItem, isDone: Boolean): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.chat_card_bg)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) }
            if (isDone) alpha = 0.45f
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(TextView(this).apply {
            text = if (isDone) "✓ ${item.content}" else item.content
            textSize = 13f
            setTextColor(if (isDone) c.tipText else c.textPrimary)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (item.activeFrom.isNotEmpty() && item.activeTo.isNotEmpty()) {
            row.addView(TextView(this).apply {
                text = "${item.activeFrom}~${item.activeTo}"
                textSize = 9f
                setTextColor(c.accent)
                setPadding(dp(6), dp(2), dp(6), dp(2))
                background = GradientDrawable().apply {
                    cornerRadius = dp(4).toFloat()
                    setColor(c.backgroundSecondary)
                }
            })
        }
        val days = ((System.currentTimeMillis() - item.createdAt) / (24 * 60 * 60 * 1000)).toInt()
        row.addView(TextView(this).apply {
            text = when {
                days == 0 -> "今天"
                days == 1 -> "昨天"
                days < 30 -> "${days}天前"
                else -> "${days / 30}月前"
            }
            textSize = 10f
            setTextColor(c.dateLabel)
            setPadding(dp(8), 0, 0, 0)
        })
        card.addView(row)
        if (!isDone) {
            card.setOnLongClickListener {
                AlertDialog.Builder(this)
                    .setTitle(item.content)
                    .setItems(arrayOf("标记完成", "删除")) { _, which ->
                        when (which) {
                            0 -> {
                                storage.markDone(friendId, item.id)
                                renderItems()
                            }
                            1 -> {
                                storage.deleteItem(friendId, item.content)
                                renderItems()
                            }
                        }
                    }.show()
                true
            }
        }
        return card
    }

    private fun categoryDisplay(category: String): String = when (category) {
        "like" -> "喜欢"
        "want_to" -> "想做"
        "care" -> "在意"
        "interest" -> "兴趣"
        "promise" -> "承诺"
        "habit" -> "习惯"
        "dislike" -> "讨厌"
        else -> category
    }
}
