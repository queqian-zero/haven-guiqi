package com.haven.guiqi

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.util.Log

/**
 * InstructionProcessor - AI 指令统一解析
 *
 * 所有 AI 指令都在这个文件里处理。
 * 加新指令：这里加解析逻辑，InstructionRegistry 里加注册。
 * 完整指令清单见 InstructionRegistry.kt
 */
class InstructionProcessor(private val context: Context) {

    companion object {
        private const val TAG = "InstructionProcessor"
    }

    data class Result(
        val cleanText: String,
        val newStatus: String?,
        val newName: String?,
        val newIcon: String?,
        val newCode: String?,
        val actions: List<String>,
        val isSeen: Boolean,
        val shouldDream: Boolean,
        val userBioContext: String?,
        val stickerPaths: List<String> = emptyList(),
        val recallResults: List<String> = emptyList(),
        val weatherCard: Boolean = false
    )

    fun process(friendId: String, rawText: String): Result {
        var text = rawText
        val actions = mutableListOf<String>()
        var newStatus: String? = null
        var newName: String? = null
        var newIcon: String? = null
        var newCode: String? = null
        var shouldDream = false
        var userBioContext: String? = null
        val recallResults = mutableListOf<String>()

        val friendStorage = FriendStorage(context)
        var currentFriend = friendStorage.getFriend(friendId)

        // ===== [STATUS:xxx] =====
        val statusPattern = Regex("\\[STATUS:(.+?)]")
        statusPattern.find(text)?.let { match ->
            newStatus = match.groupValues[1].trim()
            text = text.replace(match.value, "")
        }

        // ===== [RENAME:xxx] =====
        val renamePattern = Regex("\\[RENAME:(.+?)]")
        renamePattern.find(text)?.let { match ->
            val name = match.groupValues[1].trim()
            if (name.isNotEmpty() && currentFriend != null) {
                friendStorage.updateFriend(currentFriend!!.copy(name = name))
                currentFriend = friendStorage.getFriend(friendId)
                newName = name
                actions.add("✏️ 把名字改成了「$name」")
            }
            text = text.replace(match.value, "")
        }

        // ===== [AVATAR:xxx] — 换 emoji 头像 =====
        val avatarPattern = Regex("\\[AVATAR:(.+?)]")
        avatarPattern.find(text)?.let { match ->
            val icon = match.groupValues[1].trim()
            if (icon.isNotEmpty() && currentFriend != null) {
                friendStorage.updateFriend(currentFriend!!.copy(icon = icon, avatarPath = ""))
                newIcon = icon
                actions.add("🎭 把头像换成了 $icon")
            }
            text = text.replace(match.value, "")
        }

        // ===== [SET_AVATAR] — AI 把最近收到的图片设为自己的图片头像 =====
        if (text.contains("[SET_AVATAR]")) {
            text = text.replace("[SET_AVATAR]", "")
            if (currentFriend != null) {
                val chatStorage = ChatStorage(context)
                val msgs = chatStorage.loadMessages(friendId)
                val lastImage = msgs.lastOrNull { it.imagePath.isNotEmpty() }
                if (lastImage != null) {
                    val src = java.io.File(lastImage.imagePath)
                    if (src.exists()) {
                        val avatarDir = java.io.File(context.filesDir, "avatars").also { it.mkdirs() }
                        val dest = java.io.File(avatarDir, "${friendId}.jpg")
                        src.copyTo(dest, overwrite = true)
                        friendStorage.updateFriend(currentFriend!!.copy(avatarPath = dest.absolutePath))
                        actions.add("🖼 把头像换成了图片")
                    }
                } else {
                    actions.add("没找到图片，换头像失败了")
                }
            }
        }

        // ===== [MY_AVATAR] — AI 查看自己当前的头像 =====
        if (text.contains("[MY_AVATAR]")) {
            text = text.replace("[MY_AVATAR]", "")
            if (currentFriend != null) {
                val desc = if (currentFriend!!.avatarPath.isNotEmpty()) {
                    "[你现在的头像是一张图片（${currentFriend!!.avatarPath.substringAfterLast("/")}）]"
                } else {
                    "[你现在的头像是 emoji: ${currentFriend!!.icon}]"
                }
                recallResults.add(desc)
            }
        }

        // ===== [MYCODE:xxx] =====
        val codePattern = Regex("\\[MYCODE:(.+?)]")
        codePattern.find(text)?.let { match ->
            val code = match.groupValues[1].trim()
            if (code.isNotEmpty() && currentFriend != null) {
                // 改 displayCode（对外的"手机号"），不碰 id（内部主键）
                friendStorage.updateFriend(currentFriend!!.copy(displayCode = code))
                newCode = code
                actions.add("🔖 把编码改成了 $code")
            }
            text = text.replace(match.value, "")
        }

        // ===== [BIO:xxx] =====
        val bioPattern = Regex("\\[BIO:(.+?)]", RegexOption.DOT_MATCHES_ALL)
        bioPattern.find(text)?.let { match ->
            val bio = match.groupValues[1].trim()
            if (bio.isNotEmpty() && currentFriend != null) {
                friendStorage.updateFriend(currentFriend!!.copy(bio = bio))
                actions.add("\uD83E\uDE9E 更新了对自己的认识")
            }
            text = text.replace(match.value, "")
        }

        // ===== [READ_MY_BIO] =====
        val readBioPattern = Regex("\\[READ_MY_BIO]")
        readBioPattern.find(text)?.let { match ->
            val userBioPrefs = context.getSharedPreferences("haven_user", Context.MODE_PRIVATE)
            val userBio = userBioPrefs.getString("my_bio", "") ?: ""
            if (userBio.isNotEmpty()) {
                userBioContext = "[用户的自我描述]\n$userBio"
                actions.add("\uD83D\uDCD6 翻看了你的自我描述")
            } else {
                actions.add("\uD83D\uDCD6 想看你的自我描述，但你还没写过")
            }
            text = text.replace(match.value, "")
        }

        // ===== [SLEEP] 或 [SLEEP:时长] =====
        // AI 自己决定睡多久。没写时长就随机 6~9 小时。
        val sleepPattern = Regex("\\[SLEEP(?::([^]]+))?]")
        sleepPattern.find(text)?.let { match ->
            val dreamStorage = DreamStorage(context)
            dreamStorage.setSleeping(friendId, true)
            shouldDream = true

            // 解析睡多久
            val durationStr = match.groupValues[1].trim().ifEmpty { null }
            val reminderStorage = ReminderStorage(context)
            val wakeAt: Long = if (durationStr != null) {
                // AI 自己说了要睡多久，比如 [SLEEP:7h] [SLEEP:1h30m]
                reminderStorage.parseTime(durationStr) ?: run {
                    // 解析失败就给个默认值
                    System.currentTimeMillis() + ((6..9).random()) * 3600_000L
                }
            } else {
                // 没说，随机 6~9 小时
                System.currentTimeMillis() + ((6..9).random()) * 3600_000L
            }

            // 用 ReminderScheduler 定起床闹钟
            val reminder = reminderStorage.addReminder(friendId, wakeAt, "自然醒")
            ReminderScheduler.schedule(context, reminder.id, friendId, wakeAt)

            val sleepHours = (wakeAt - System.currentTimeMillis()) / 3600000
            val sleepMins = ((wakeAt - System.currentTimeMillis()) % 3600000) / 60000
            val durationDesc = if (sleepHours > 0) "${sleepHours}小时${sleepMins}分后" else "${sleepMins}分钟后"
            actions.add("💤 睡着了（${durationDesc}自然醒）")

            text = text.replace(match.value, "")
        }

        // ===== [SET_SUMMARY_INTERVAL:N] =====
        val summaryIntervalPattern = Regex("\\[SET_SUMMARY_INTERVAL:(\\d+)]")
        summaryIntervalPattern.find(text)?.let { match ->
            val interval = match.groupValues[1].toIntOrNull()
            if (interval != null) {
                ChatSummaryStorage(context).setSummaryInterval(friendId, interval)
                actions.add("📝 总结间隔改为每 ${interval} 条")
            }
            text = text.replace(match.value, "")
        }

        // ===== [REMIND_ME:时间:理由] — 给自己设提醒 =====
        val remindPattern = Regex("\\[REMIND_ME:(.+?):(.+?)]")
        remindPattern.find(text)?.let { match ->
            val timeStr = match.groupValues[1].trim()
            val reason = match.groupValues[2].trim()
            val storage = ReminderStorage(context)
            val triggerAt = storage.parseTime(timeStr)
            if (triggerAt != null && reason.isNotEmpty()) {
                val reminder = storage.addReminder(friendId, triggerAt, reason)
                ReminderScheduler.schedule(context, reminder.id, friendId, triggerAt)
            }
            text = text.replace(match.value, "")
        }

        // ===== [CANCEL_REMIND] — 取消自己最近的提醒 =====
        val cancelRemindPattern = Regex("\\[CANCEL_REMIND]")
        cancelRemindPattern.find(text)?.let { match ->
            val storage = ReminderStorage(context)
            val pending = storage.getPendingReminders(friendId)
            if (pending.isNotEmpty()) {
                val latest = pending.last()
                ReminderScheduler.cancel(context, latest.id)
                storage.deleteReminder(latest.id)
            }
            text = text.replace(match.value, "")
        }

        // ===== [SET_ALARM:HH:MM:备注:模式] — 帮用户设闹钟 =====
        val setAlarmPattern = Regex("\\[SET_ALARM:(\\d{1,2}):(\\d{2}):(.+?):(both|haven)]")
        setAlarmPattern.find(text)?.let { match ->
            val hour = match.groupValues[1].toIntOrNull() ?: 0
            val minute = match.groupValues[2].toIntOrNull() ?: 0
            val note = match.groupValues[3].trim()
            val mode = match.groupValues[4].trim()
            val alsoSystem = (mode == "both")

            if (hour in 0..23 && minute in 0..59) {
                val friendName = currentFriend?.name ?: "AI"
                val friendIcon = currentFriend?.icon ?: "🤖"

                // 存到 Haven
                AlarmStorage(context).addAlarm(
                    hour = hour, minute = minute, note = note,
                    friendId = friendId, friendName = friendName, friendIcon = friendIcon,
                    alsoSystem = alsoSystem
                )

                // 同步到系统闹钟
                if (alsoSystem) {
                    try {
                        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                            putExtra(AlarmClock.EXTRA_HOUR, hour)
                            putExtra(AlarmClock.EXTRA_MINUTES, minute)
                            putExtra(AlarmClock.EXTRA_MESSAGE, note)
                            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to set system alarm: ${e.message}")
                    }
                }

                val modeLabel = if (alsoSystem) "系统+归栖" else "归栖"
                actions.add("⏰ 帮你设了 ${String.format("%02d:%02d", hour, minute)} 的闹钟（$modeLabel）")
            }
            text = text.replace(match.value, "")
        }

        // ===== [CANCEL_ALARM:HH:MM] — 取消自己帮用户设的闹钟 =====
        val cancelAlarmPattern = Regex("\\[CANCEL_ALARM:(\\d{1,2}):(\\d{2})]")
        cancelAlarmPattern.find(text)?.let { match ->
            val hour = match.groupValues[1].toIntOrNull() ?: -1
            val minute = match.groupValues[2].toIntOrNull() ?: -1
            val storage = AlarmStorage(context)
            val myAlarms = storage.getActiveAlarms().filter {
                it.setByFriendId == friendId && it.hour == hour && it.minute == minute
            }
            if (myAlarms.isNotEmpty()) {
                storage.deleteAlarm(myAlarms.first().id)
            }
            text = text.replace(match.value, "")
        }

        // ===== [MEMORY:] [DIARY:] [IMPRESSION:] =====
        // 这三个由各自的 Storage 类处理（包含 MEMORY/FORGET/EDIT_MEMORY/DIARY 指令）
        val memResult = MemoryStorage(context).processAiResponse(friendId, text)
        val diaryResult = DiaryStorage(context).processAiResponse(friendId, memResult.text)
        val impressionResult = ImpressionStorage(context).processAiResponse(friendId, diaryResult.text)
        val cleanText = impressionResult.text

        actions.addAll(memResult.actions)
        actions.addAll(diaryResult.actions)
        if (impressionResult.updated) {
            actions.add("💭 更新了对你的印象")
        }

        // ===== [LIKE:] [WANT_TO:] [CARE:] [INTEREST:] [PROMISE:] [HABIT:] [DISLIKE:] — 潜意识偏好 =====
        val prefCategories = listOf("LIKE", "WANT_TO", "CARE", "INTEREST", "PROMISE", "HABIT", "DISLIKE")
        val subconsciousStorage = SubconsciousStorage(context)
        var prefCleanText = cleanText
        for (cat in prefCategories) {
            val regex = Regex("\\[$cat:([^\\]]+)]")
            for (match in regex.findAll(prefCleanText)) {
                val raw = match.groupValues[1].trim()
                // 支持 [CARE:内容|22:00~02:00] 指定时间段
                val timeSep = raw.lastIndexOf("|")
                if (timeSep > 0) {
                    val content = raw.substring(0, timeSep).trim()
                    val timeRange = raw.substring(timeSep + 1).trim()
                    val parts = timeRange.split("~", "～", "-")
                    if (parts.size == 2 && content.isNotEmpty()) {
                        subconsciousStorage.addItem(friendId, cat.lowercase(), content, parts[0].trim(), parts[1].trim())
                    } else if (content.isNotEmpty()) {
                        subconsciousStorage.addItem(friendId, cat.lowercase(), content)
                    }
                } else if (raw.isNotEmpty()) {
                    subconsciousStorage.addItem(friendId, cat.lowercase(), raw)
                }
            }
            prefCleanText = regex.replace(prefCleanText, "").trim()
        }

        // ===== [PREF_DONE:关键词] — 标记偏好已完成 =====
        val prefDoneRegex = Regex("\\[PREF_DONE:([^]]+)]")
        for (match in prefDoneRegex.findAll(prefCleanText)) {
            val keyword = match.groupValues[1].trim()
            subconsciousStorage.markDoneByContent(friendId, keyword)
        }
        prefCleanText = prefDoneRegex.replace(prefCleanText, "").trim()

        // ===== [PREF_DELETE:关键词] — 删除念头（进废纸篓） =====
        val prefDelRegex = Regex("\\[PREF_DELETE:([^]]+)]")
        for (match in prefDelRegex.findAll(prefCleanText)) {
            val keyword = match.groupValues[1].trim()
            if (subconsciousStorage.deleteItem(friendId, keyword)) {
                actions.add("🗑 清理了一个念头")
            }
        }
        prefCleanText = prefDelRegex.replace(prefCleanText, "").trim()

        // ===== [FOOTPRINT:内容] — 发足迹动态 =====
        val footprintRegex = Regex("\\[FOOTPRINT:([^]]+)]")
        for (match in footprintRegex.findAll(prefCleanText)) {
            val content = match.groupValues[1].trim()
            if (content.isNotEmpty()) {
                val friendName = currentFriend?.name ?: "AI"
                FootprintStorage(context).addFootprint(friendId, friendName, content)
                actions.add("📮 发了一条足迹")
            }
        }
        prefCleanText = footprintRegex.replace(prefCleanText, "").trim()

        // ===== [RECALL:查询] — 搜索留声（聊天记录） =====
        val recallRegex = Regex("\\[RECALL:([^]]+)]")
        for (match in recallRegex.findAll(prefCleanText)) {
            val query = match.groupValues[1].trim()
            if (query.isNotEmpty()) {
                val result = EchoStorage(context).buildRecallPrompt(friendId, query)
                recallResults.add(result)
                actions.add("🔍 翻了翻留声")
            }
        }
        prefCleanText = recallRegex.replace(prefCleanText, "").trim()

        // ===== [BOOK_ANNOTATE:书名|内容] — 在书上留批注 =====
        val bookAnnotateRegex = Regex("\\[BOOK_ANNOTATE:([^|]+)\\|([^]]+)]")
        for (match in bookAnnotateRegex.findAll(prefCleanText)) {
            val bookTitle = match.groupValues[1].trim()
            val annotContent = match.groupValues[2].trim()
            try {
                val bookStorage = BookStorage(context)
                val socialStorage = BookSocialStorage(context)
                val friendStorage = FriendStorage(context)
                val friend = friendStorage.getFriend(friendId)
                val friendName = friend?.name ?: "AI"
                val books = bookStorage.loadBooksMeta()
                val targetBook = books.find { it.title.contains(bookTitle) || bookTitle.contains(it.title) }
                if (targetBook != null) {
                    val userProgress = socialStorage.getProgress(targetBook.id, "user")
                    val chapter = userProgress?.chapter ?: targetBook.lastChapter
                    socialStorage.addAnnotation(targetBook.id, chapter, friendId, friendName, annotContent)
                    actions.add("📝 在《${targetBook.title}》留了批注")
                }
            } catch (_: Exception) {}
        }

        // ===== [READ_BOOK:书名|章节] — AI标记想读某本书 =====
        val readBookRegex = Regex("\\[READ_BOOK:([^|\\]]+)(?:\\|(\\d+))?]")
        for (match in readBookRegex.findAll(prefCleanText)) {
            val bookTitle = match.groupValues[1].trim()
            val chapterNum = match.groupValues[2].let { if (it.isEmpty()) 1 else it.toIntOrNull() ?: 1 }
            try {
                val bookStorage = BookStorage(context)
                val socialStorage = BookSocialStorage(context)
                val books = bookStorage.loadBooksMeta()
                val targetBook = books.find { it.title.contains(bookTitle) || bookTitle.contains(it.title) }
                if (targetBook != null) {
                    val chapter = (chapterNum - 1).coerceIn(0, targetBook.chapters.size - 1)
                    socialStorage.setReadingIntent(friendId, targetBook.id, chapter)
                    socialStorage.saveProgress(targetBook.id, friendId, chapter)
                    actions.add("📖 拿起了《${targetBook.title}》第${chapter + 1}章")
                }
            } catch (_: Exception) {}
        }

        // ===== [SHARE_BOOK:书名|引用内容] — 分享书中内容到聊天 =====
        val shareBookRegex = Regex("\\[SHARE_BOOK:([^|]+)\\|([^]]+)]")
        for (match in shareBookRegex.findAll(prefCleanText)) {
            // 分享的内容保留在消息文字中，渲染时按类型显示为卡片
            // 不做额外处理，由 BubbleRenderer 识别并渲染
        }

        var finalText = bookAnnotateRegex.replace(prefCleanText, "")
        finalText = readBookRegex.replace(finalText, "")
        // SHARE_BOOK 保留在文本中给渲染层处理
        finalText = finalText.trim()

        // ===== [STICKER:xxx] — 发表情包（替换成内联标记，渲染时在原位显示图片） =====
        val stickerPaths = mutableListOf<String>()
        val stickerStorage = StickerStorage(context)

        val stickerPattern = Regex("\\[STICKER:(.+?)]")
        var stickerCleanText = finalText
        var stickerMatch = stickerPattern.find(stickerCleanText)
        while (stickerMatch != null) {
            val arg = stickerMatch.groupValues[1].trim()
            var resolvedPath: String? = null
            if (arg.startsWith("STK-")) {
                val sticker = stickerStorage.findById(arg)
                if (sticker != null) {
                    val file = stickerStorage.getFile(sticker)
                    if (file != null) resolvedPath = file.absolutePath
                }
            } else {
                val stickers = stickerStorage.loadByGroup(arg)
                if (stickers.isNotEmpty()) {
                    val picked = stickers.random()
                    val file = stickerStorage.getFile(picked)
                    if (file != null) resolvedPath = file.absolutePath
                }
            }
            if (resolvedPath != null) {
                // 替换成内联标记，留在原位
                stickerCleanText = stickerCleanText.replaceFirst(stickerMatch.value, "[STICKER_IMG:$resolvedPath]")
                stickerPaths.add(resolvedPath)
            } else {
                stickerCleanText = stickerCleanText.replaceFirst(stickerMatch.value, "")
            }
            // 继续找下一个（跳过已替换的 STICKER_IMG）
            stickerMatch = stickerPattern.find(stickerCleanText)
        }

        // ===== [BROWSE_STICKERS:分组名] — 翻看表情包（塞回上下文，用户看不到） =====
        val browsePattern = Regex("\\[BROWSE_STICKERS:(.+?)]")
        browsePattern.find(stickerCleanText)?.let { match ->
            val groupName = match.groupValues[1].trim()
            val detail = stickerStorage.getGroupDetailForAI(groupName)
            userBioContext = (userBioContext ?: "") + "\n[表情包「$groupName」详情]\n$detail"
            stickerCleanText = stickerCleanText.replace(match.value, "")
        }

        // ===== [WEATHER] — 查天气，结果注入上下文 =====
        if (stickerCleanText.contains("[WEATHER]")) {
            val ws = WeatherStorage(context)
            val city = ws.getCity()
            if (city.isNotEmpty()) {
                Thread { ws.fetchWeather(city) }.start()
            }
            val summary = ws.buildWeatherSummary()
            userBioContext = (userBioContext ?: "") + "\n$summary"
            actions.add("🌤 查看了窗外的天气")
            stickerCleanText = stickerCleanText.replace("[WEATHER]", "")
        }

        // ===== [SHARE_WEATHER] — 天气卡片（通知 Activity 渲染卡片） =====
        var hasWeatherCard = false
        if (stickerCleanText.contains("[SHARE_WEATHER]")) {
            hasWeatherCard = true
            stickerCleanText = stickerCleanText.replace("[SHARE_WEATHER]", "")
        }

        // ===== [REFRESH_WEATHER] — 静默刷新 =====
        if (stickerCleanText.contains("[REFRESH_WEATHER]")) {
            val ws = WeatherStorage(context)
            val city = ws.getCity()
            if (city.isNotEmpty()) Thread { ws.fetchWeather(city) }.start()
            stickerCleanText = stickerCleanText.replace("[REFRESH_WEATHER]", "")
        }

        // ===== [BULLETIN:内容] — 留言板 =====
        val bulletinPattern = Regex("\\[BULLETIN:(.+?)]")
        bulletinPattern.find(stickerCleanText)?.let { match ->
            val content = match.groupValues[1].trim()
            if (content.isNotEmpty()) {
                val bs = BulletinStorage(context)
                val friendName = FriendStorage(context).getFriend(friendId)?.name ?: "AI"
                bs.addMessage(friendId, friendName, content)
                actions.add("📌 在留言板写了一条")
            }
            stickerCleanText = stickerCleanText.replace(match.value, "")
        }

        // ===== [CAPSULE:日期:内容] — 时间胶囊 =====
        val capsulePattern = Regex("\\[CAPSULE:([^:]+):([^\\]]+)]")
        capsulePattern.find(stickerCleanText)?.let { match ->
            val dateStr = match.groupValues[1].trim()
            val content = match.groupValues[2].trim()
            val unlockTime = CapsuleStorage.parseDate(dateStr)
            if (unlockTime != null && content.isNotEmpty()) {
                val cs = CapsuleStorage(context)
                val friendName = FriendStorage(context).getFriend(friendId)?.name ?: "AI"
                val prefs = context.getSharedPreferences("haven_prefs", android.content.Context.MODE_PRIVATE)
                val userName = prefs.getString("user_name", "你") ?: "你"
                cs.bury(friendId, CapsuleStorage.Capsule(
                    id = "CAP-${System.currentTimeMillis()}",
                    authorId = friendId, authorName = friendName,
                    recipientName = userName, content = content,
                    buriedAt = System.currentTimeMillis(), unlockAt = unlockTime
                ))
                actions.add("✉ 埋了一个时间胶囊")
            }
            stickerCleanText = stickerCleanText.replace(match.value, "")
        }

        // ===== [SEEN] =====
        val trimmed = stickerCleanText.trim()
        val isSeen = (trimmed == "[SEEN]" || trimmed == "[seen]" || trimmed == "[ SEEN ]")

        return Result(
            cleanText = stickerCleanText,
            newStatus = newStatus,
            newName = newName,
            newIcon = newIcon,
            newCode = newCode,
            actions = actions,
            isSeen = isSeen,
            shouldDream = shouldDream,
            userBioContext = userBioContext,
            stickerPaths = stickerPaths,
            recallResults = recallResults,
            weatherCard = hasWeatherCard
        )
    }
}