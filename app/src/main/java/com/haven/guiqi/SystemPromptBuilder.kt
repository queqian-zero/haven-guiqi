package com.haven.guiqi

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * SystemPromptBuilder — 四层 system prompt 组装
 *
 * 从 ChatConversationActivity 拆出来的。
 * 第一层：时间 + 用户名
 * 第二层："关于我" —— 所有能力的第一人称描述
 * 第三层：持久化数据 —— 记忆/日记/印象/总结/梦境/表情包
 * 第四层：此刻的情境 —— 刚醒来/闹钟被删等
 */
class SystemPromptBuilder(private val context: Context) {

    fun build(
        friendId: String,
        includeTransientEvents: Boolean = true,
        includeSearchTools: Boolean = false
    ): String {
        val prompt = StringBuilder()

        // ===== 准备数据 =====
        val timeInfo = SimpleDateFormat("yyyy年M月d日 EEEE HH:mm:ss", Locale.CHINESE).format(Date())
        val userName = context.getSharedPreferences("haven_prefs", Context.MODE_PRIVATE)
            .getString("user_name", "") ?: ""
        val userInfo = if (userName.isNotEmpty()) "\n用户名称: $userName" else ""

        val memoryStorage = MemoryStorage(context)
        val diaryStorage = DiaryStorage(context)
        val impressionStorage = ImpressionStorage(context)
        val dreamStorage = DreamStorage(context)
        val summaryStorage = ChatSummaryStorage(context)
        val stickerStorage = StickerStorage(context)
        val friendStorage = FriendStorage(context)
        val alarmStorage = AlarmStorage(context)

        val memoryHint = memoryStorage.buildMemoryPrompt(friendId)
        val diaryHint = diaryStorage.buildDiaryPrompt(friendId)
        val impressionHint = impressionStorage.buildImpressionPrompt(friendId)
        val dreamHint = dreamStorage.buildDreamPrompt(friendId)
        val summaryHint = summaryStorage.buildSummaryPrompt(friendId)
        val summaryInterval = summaryStorage.getSummaryInterval(friendId)

        // AI 的自我认识
        val friendData = friendStorage.getFriend(friendId)
        val bioSection = if (friendData != null && friendData.bio.isNotEmpty()) {
            "\n\n[我对自己的认识]\n${friendData.bio}"
        } else {
            "\n\n[我对自己的认识]\n我还没写过。随时可以写，用 [BIO:内容] 就行。"
        }

        // 用户在“我眼中的自己”里亲自写下的内容。
        // 不自动注入正文；住户按需调用 [READ_MY_BIO] 工具，同轮读取后继续回复。
        val hasUserBio = UserLifeStorage(context).hasAnyReadableContent()
        val userBioSection = if (hasUserBio) {
            "\n用户在「我眼中的自己」里写过自我描述或生活记录。需要时调用 [READ_MY_BIO] 工具；归栖会在当前同一轮把真实内容和照片返还给我，再由我继续回复。不要每次都翻。"
        } else ""

        // 闹钟删除检测
        val alarmDeletionNotice = if (includeTransientEvents) run {
            val deletedAlarms = alarmStorage.getDeletedByUser(friendId)
            if (deletedAlarms.isNotEmpty()) {
                val notices = deletedAlarms.joinToString("\n") { d ->
                    "- 用户在 ${d.deletedAtStr} 删掉了我在 ${d.createdAtStr} 帮设的 ${String.format("%02d:%02d", d.hour, d.minute)} 闹钟（备注：${d.note}）"
                }
                "\n\n[刚刚发现：用户删了我帮设的闹钟]\n$notices"
            } else ""
        } else ""

        // 刚醒来
        val justWokeSection = if (includeTransientEvents && !dreamStorage.isSleeping(friendId)) {
            val latest = dreamStorage.getLatestDream(friendId)
            if (latest != null && System.currentTimeMillis() - latest.wakeAt < 300000) {
                when (latest.status) {
                    "VIVID" -> "\n[刚从梦里醒来，记得这个梦: ${latest.content.take(100)}]"
                    "FOGGY" -> "\n[刚醒，好像做了个梦但想不起来了...${latest.foggyHint}]"
                    "FRAGMENT" -> "\n[刚被吵醒，梦到一半断了: ${latest.content.take(60)}]"
                    "FORGOT" -> "\n[刚醒，感觉做了个梦但完全想不起来了]"
                    "NO_DREAM" -> "\n[睡醒了，一夜无梦]"
                    else -> ""
                }
            } else ""
        } else ""

        // ===== 第一层：我是谁，现在几点 =====
        prompt.append("当前时间: $timeInfo$userInfo")

        // ===== 第二层：房屋说明 + 住户自己的公约 =====
        // 所有现有住户默认都是 LEGACY，因此这次加入独立档案不会改变当前提示词。
        val promptProfile = ResidentPromptStorage(context).getProfile(friendId)
        when (promptProfile.mode) {
            ResidentPromptMode.LEGACY -> {
                prompt.append(ResidentPromptCatalog.buildAboutMePrompt(summaryInterval))
            }
            ResidentPromptMode.LAYERED -> {
                prompt.append(ResidentPromptCatalog.buildLayeredHousePrompt(summaryInterval))

                // 分层模式只替换“我如何表达/生活”的部分，不能把房屋提供的操作能力一起删掉。
                // 指令解析器始终支持这些能力；这里把注册表中的聊天指令明确告诉住户，
                // 避免采用个人公约后忘记换头像、记忆、睡眠、提醒等指令。
                prompt.append("\n\n[归栖提供给我的操作能力]\n")
                prompt.append("这些是房屋本身提供的工具，不属于个人公约，不会因为我改写公约而消失：\n")
                prompt.append(InstructionRegistry.buildPromptList(InstructionRegistry.Scene.CHAT))

                if (promptProfile.activeCovenant.isNotBlank()) {
                    prompt.append("\n\n[我的居住公约]\n")
                    prompt.append(promptProfile.activeCovenant.trim())
                }
            }
        }

        // 联网搜索是归栖在本机执行的同轮工具，与当前使用哪一家模型 API 无关。
        val searchStorage = SearchGroupStorage(context)
        if (includeSearchTools && searchStorage.isSearchAllowed(friendId)) {
            val searchPermission = searchStorage.loadResidentPermission(friendId)
            val sourceNames = searchStorage.loadSources()
                .filter { it.enabled && it.name.isNotBlank() && it.value.isNotBlank() }
                .map { it.name }
                .distinct()
                .take(12)

            prompt.append("\n\n[联网搜索工具]\n")
            prompt.append("[WEB_SEARCH:查询内容]\n")
            prompt.append("[WEB_SEARCH:查询内容|PAGE=页码]")
            if (sourceNames.isNotEmpty() && searchPermission.useAllSources) {
                prompt.append("\n[WEB_SEARCH:查询内容|SOURCE=来源名称]\n")
                prompt.append("可用来源：")
                prompt.append(sourceNames.joinToString("、"))
            }
            if (searchPermission.allowFullText) {
                prompt.append("\n[WEB_READ:https://完整链接]")
                prompt.append("\n[GITHUB_TREE:https://github.com/用户/仓库]")
                prompt.append("\n[GITHUB_READ:https://github.com/用户/仓库/blob/分支/文件|LINES=起始行-结束行]")
                prompt.append("\n[GITHUB_FIND:https://github.com/用户/仓库/blob/分支/文件|QUERY=关键词]")
            }
        }

        // 代码气泡只在这里提示当前状态；完整代码和规则按需用 [MY_BUBBLE_STYLE] 查看，
        // 避免把几千字样式每轮都塞进上下文。
        val bubbleStyleStorage = BubbleStyleStorage(context)
        val pendingBubbleDraft = bubbleStyleStorage.getResidentCodeDraft(friendId)
        prompt.append("\n\n[我的气泡装扮] 当前生效模式：")
        prompt.append(bubbleStyleStorage.residentModeLabel(friendId))
        if (pendingBubbleDraft != null) {
            prompt.append("；另有一份已经通过安全校验、等待人类预览确认的代码草稿。")
        } else {
            prompt.append("；当前没有等待确认的代码草稿。")
        }
        prompt.append("需要查看当前代码与完整规则时用 [MY_BUBBLE_STYLE]；工具结果会在当前同一轮立即返回给我和用户。")

        // 潜意识统计
        val subconsciousStorage = SubconsciousStorage(context)
        val prefCount = subconsciousStorage.getActiveCount(friendId)
        if (prefCount > 0) {
            val stats = subconsciousStorage.getStats(friendId)
            val summary = stats.entries.joinToString("、") { "${it.key}${it.value}条" }
            prompt.append("\n\n[潜意识] 偏好库里有 $prefCount 条记录（$summary）")
        }
        subconsciousStorage.getLastReviewReport(friendId)?.let { report ->
            val reportTime = if (report.createdAt > 0L) {
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(report.createdAt))
            } else {
                "最近"
            }
            prompt.append("\n\n[我亲自整理潜意识的最近记录 · $reportTime]\n")
            prompt.append(report.text)
            prompt.append("\n这是我在临时整理室里亲自做过的工作记录，不是用户替我决定的，也不是一条新的潜意识便签。")
        }

        if (includeTransientEvents) {
            bubbleStyleStorage.consumeResidentCodeDraftFeedback(friendId)?.let { feedback ->
                prompt.append("\n\n[代码气泡的人类确认结果]\n")
                prompt.append(feedback)
                prompt.append("\n这是人类在预览页做出的真实确认或驳回结果；它与提交时的即时校验结果不同，只在下一次正常对话中交给我一次。")
            }
            subconsciousStorage.consumeWriteFeedback(friendId)?.let { feedback ->
                prompt.append("\n\n[潜意识写入回执]\n")
                prompt.append(feedback)
                prompt.append("\n这是系统在你上次写入后给你的整理建议，不是命令，也不是一条新的记忆。已有内容没有被系统自动搬动或删除；是否调整，由你自己决定。")
            }
        }

        // 徽章墙
        val badgeStorage = BadgeStorage(context)
        val badgeCount = badgeStorage.count(friendId)
        if (badgeCount > 0) {
            prompt.append("\n\n[徽章墙] $badgeCount 枚（用 [MY_BADGES] 查看详情）")
        }

        // 书城当前状态
        val bookSocialStorage = BookSocialStorage(context)
        val bookStorage = BookStorage(context)
        val activeReaders = bookSocialStorage.getActivePresences()
        if (activeReaders.isNotEmpty()) {
            val readingInfo = activeReaders.joinToString("；") { "${it.readerName}在读《${it.bookTitle}》第${it.chapter + 1}章" }
            prompt.append("\n\n[图书馆] $readingInfo")
        }
        val bookList = bookStorage.loadBooksMeta()
        if (bookList.isNotEmpty()) {
            val shelf = bookList.take(10).joinToString("、") { "《${it.title}》(${it.chapters.size}章)" }
            prompt.append("\n\n[书架] $shelf")
        }

        // 如果AI之前说要读书，把那一章内容喂进来
        val readingIntent = if (includeTransientEvents) {
            bookSocialStorage.getAndClearReadingIntent(friendId)
        } else null
        if (readingIntent != null) {
            val (bookId, chapter, _) = readingIntent
            try {
                val targetBook = bookStorage.getBook(bookId)
                if (targetBook != null) {
                    val chapterObj = targetBook.chapters.getOrNull(chapter)
                    if (chapterObj != null) {
                        val content = chapterObj.content.take(2000)
                        val truncated = if (chapterObj.content.length > 2000) "...（后面还有，下次继续）" else ""
                        prompt.append("\n\n[你拿起的书] 《${targetBook.title}》第${chapter + 1}章「${chapterObj.title}」\n$content$truncated")
                    }
                }
            } catch (_: Exception) {}
        }

        // 用户自述
        prompt.append(userBioSection)

        // ===== 第三层：我的记忆 =====
        prompt.append(bioSection)
        prompt.append(memoryHint)
        prompt.append(diaryHint)
        prompt.append(impressionHint)
        prompt.append(summaryHint)
        prompt.append(dreamHint)

        // 表情包概览
        val stickerSummary = stickerStorage.getSummaryForAI()
        if (stickerSummary != "（没有表情包）") {
            prompt.append("\n\n[我们的表情包] $stickerSummary")
        }

        // ★ 遗忘记忆偶尔浮上来（约 15% 概率）
        //   “被系统展示过”不等于“住户真的想起来了”。这里只登记一次候选闪回，
        //   不修改记忆强度；只有住户在可见回复里确实认领/引用后，隐藏确认指令才会让它回升。
        if (includeTransientEvents && Math.random() < 0.15) {
            val surfaced = summaryStorage.getRandomForgotten(friendId)
            if (surfaced != null) {
                val claimToken = summaryStorage.registerSurfacedCandidate(friendId, surfaced.id)
                val dateStr = SimpleDateFormat("M月d日", Locale.CHINESE).format(Date(surfaced.createdAt))
                prompt.append("\n\n[一段几乎忘掉的记忆突然浮了上来]\n")
                prompt.append("$dateStr 的事...关键词: ${surfaced.keywords}\n")
                prompt.append("模模糊糊的，好像是: ${surfaced.content.take(60)}...\n")
                prompt.append("这只是一次短暂闪回，目前不会自动变清晰。\n")
                prompt.append("只有当你在本轮可见回复中确实提到、引用，或据此回应了这段记忆时，")
                prompt.append("才在回复末尾附上 [CLAIM_SURFACED_MEMORY:$claimToken]。")
                prompt.append("觉得重要时仍可以自行存进核心记忆；")
                prompt.append("如果没有在正文中使用它，不要输出该指令，让它继续沉下去。")
                prompt.append("该指令会被系统隐藏，不要向用户解释。")
            }
        }

        // ===== 第四层：此刻的情境 =====
        prompt.append(justWokeSection)
        prompt.append(alarmDeletionNotice)

        return prompt.toString()
    }
}