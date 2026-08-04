package com.haven.guiqi

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
        val userBioPhotos: List<UserLifeStorage.PhotoRef> = emptyList(),
        val stickerPaths: List<String> = emptyList(),
        val recallResults: List<String> = emptyList(),
        val weatherCard: Boolean = false,
        val pendingBadge: String? = null,  // AI申请解锁的徽章名
        val pendingCovenantDraft: String? = null,
        val pendingCovenantAdopt: Boolean = false,
        val chatAppearanceChanged: Boolean = false
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
        val userBioPhotos = mutableListOf<UserLifeStorage.PhotoRef>()
        val recallResults = mutableListOf<String>()
        var pendingCovenantDraft: String? = null
        var pendingCovenantAdopt = false
        var chatAppearanceChanged = false

        // 遗忘记忆闪回的内部确认指令。先从正文中拿掉，等所有其他指令清理完成后，
        // 只有仍有可见回复时才消费令牌并增强对应总结。
        val surfacedClaimRegex = Regex(
            "\\[CLAIM_SURFACED_MEMORY:([^]]+)]",
            RegexOption.IGNORE_CASE
        )
        val surfacedClaimTokens = surfacedClaimRegex.findAll(text)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
        text = surfacedClaimRegex.replace(text, "").trim()

        // 留声检索后的内部确认指令。和随机闪回一样，先从正文隐藏；
        // 只有住户留下可见回复并确实使用了对应 R 组记录时，才会消费令牌。
        val recalledClaimRegex = Regex(
            "\\[CLAIM_RECALLED_MEMORY:([^]]+)]",
            RegexOption.IGNORE_CASE
        )
        val recalledClaimTokens = recalledClaimRegex.findAll(text)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
        text = recalledClaimRegex.replace(text, "").trim()

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
                deleteResidentAvatarFiles(friendId)
                friendStorage.updateFriend(currentFriend!!.copy(icon = icon, avatarPath = ""))
                currentFriend = friendStorage.getFriend(friendId)
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
                        val dest = copyResidentAvatar(friendId, src)
                        friendStorage.updateFriend(currentFriend!!.copy(avatarPath = dest.absolutePath))
                        currentFriend = friendStorage.getFriend(friendId)
                        actions.add("🖼 把头像换成了图片")
                    }
                } else {
                    actions.add("没找到图片，换头像失败了")
                }
            }
        }


        // ===== [GALLERY_AVATAR:图片ID] — 视觉浏览后从画匣选择自己的头像 =====
        val galleryAvatarPattern = Regex("\\[GALLERY_AVATAR:(.+?)]", RegexOption.IGNORE_CASE)
        galleryAvatarPattern.find(text)?.let { match ->
            val itemId = match.groupValues[1].trim()
            val gallery = GalleryStorage(context)
            val item = gallery.find(itemId)
            val source = item?.takeIf { it.category == GalleryStorage.Category.AVATAR }
                ?.let(gallery::fileFor)
                ?.takeIf { it.isFile }
            if (currentFriend != null && source != null) {
                val dest = copyResidentAvatar(friendId, source)
                friendStorage.updateFriend(currentFriend!!.copy(avatarPath = dest.absolutePath))
                currentFriend = friendStorage.getFriend(friendId)
                actions.add("🖼 从画匣里挑了一张新头像")
            } else {
                actions.add("画匣里的那张头像已经找不到了")
            }
            text = text.replace(match.value, "")
        }

        // ===== [GALLERY_AVATAR_FRAME:图片ID] — 视觉浏览后选择自己的头像框 =====
        val galleryAvatarFramePattern = Regex(
            "\\[GALLERY_AVATAR_FRAME:(.+?)]",
            RegexOption.IGNORE_CASE
        )
        galleryAvatarFramePattern.find(text)?.let { match ->
            val itemId = match.groupValues[1].trim()
            val gallery = GalleryStorage(context)
            val item = gallery.find(itemId)?.takeIf {
                it.category == GalleryStorage.Category.AVATAR_FRAME
            }
            if (item != null && gallery.fileFor(item).isFile) {
                ChatAppearanceStorage(context).setAvatarFrame(
                    friendId,
                    item,
                    ChatAppearanceStorage.AvatarTarget.FRIEND
                )
                chatAppearanceChanged = true
                actions.add("🖼 从画匣里挑了一只自己的头像框")
            } else {
                actions.add("画匣里的那只头像框已经找不到了")
            }
            text = text.replace(match.value, "")
        }

        // ===== [CLEAR_AVATAR_FRAME] — 住户摘掉自己的头像框 =====
        if (text.contains("[CLEAR_AVATAR_FRAME]", ignoreCase = true)) {
            text = text.replace(Regex("\\[CLEAR_AVATAR_FRAME]", RegexOption.IGNORE_CASE), "")
            ChatAppearanceStorage(context).clearAvatarFrame(
                friendId,
                ChatAppearanceStorage.AvatarTarget.FRIEND
            )
            chatAppearanceChanged = true
            actions.add("🪞 摘掉了自己的头像框")
        }

        // ===== [SET_BACKGROUND] — AI 把最近收到的图片设为当前聊天背景 =====
        if (text.contains("[SET_BACKGROUND]", ignoreCase = true)) {
            text = text.replace(Regex("\\[SET_BACKGROUND]", RegexOption.IGNORE_CASE), "")
            val chatStorage = ChatStorage(context)
            val lastImage = chatStorage.loadMessages(friendId)
                .lastOrNull { it.imagePath.isNotEmpty() }
            val source = lastImage?.imagePath?.let { java.io.File(it) }?.takeIf { it.isFile }
            if (source != null) {
                try {
                    val gallery = GalleryStorage(context)
                    val item = gallery.importExistingFile(
                        source = source,
                        category = GalleryStorage.Category.BACKGROUND,
                        displayName = source.name
                    )
                    ChatAppearanceStorage(context).setBackground(friendId, item)
                    chatAppearanceChanged = true
                    actions.add("🖼 把最近收到的图片设成了聊天背景")
                } catch (e: Exception) {
                    actions.add("背景设置失败了：${e.message ?: "无法读取图片"}")
                }
            } else {
                actions.add("没找到可以设为背景的图片")
            }
        }

        // ===== [GALLERY_BACKGROUND:图片ID] — 视觉浏览后从画匣选择聊天背景 =====
        val galleryBackgroundPattern = Regex("\\[GALLERY_BACKGROUND:(.+?)]", RegexOption.IGNORE_CASE)
        galleryBackgroundPattern.find(text)?.let { match ->
            val itemId = match.groupValues[1].trim()
            val gallery = GalleryStorage(context)
            val item = gallery.find(itemId)?.takeIf {
                it.category == GalleryStorage.Category.BACKGROUND
            }
            if (item != null && gallery.fileFor(item).isFile) {
                ChatAppearanceStorage(context).setBackground(friendId, item)
                chatAppearanceChanged = true
                actions.add("🌌 从画匣里挑了一张新聊天背景")
            } else {
                actions.add("画匣里的那张背景已经找不到了")
            }
            text = text.replace(match.value, "")
        }

        // ===== [CLEAR_BACKGROUND] — 恢复当前聊天默认背景 =====
        if (text.contains("[CLEAR_BACKGROUND]", ignoreCase = true)) {
            text = text.replace(Regex("\\[CLEAR_BACKGROUND]", RegexOption.IGNORE_CASE), "")
            ChatAppearanceStorage(context).clearBackground(friendId)
            chatAppearanceChanged = true
            actions.add("🌫 恢复了默认聊天背景")
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

        // 代码气泡工具由 ChatConversationActivity 的同轮工具循环处理。
        // 这里不再延迟返回规则或吞掉失败代码，避免住户必须等用户下一次发言才知道结果。

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

        // ===== [MY_COVENANT] — 查看自己的居住公约档案 =====
        if (text.contains("[MY_COVENANT]")) {
            text = text.replace("[MY_COVENANT]", "")
            val profile = ResidentPromptStorage(context).getProfile(friendId)
            val modeText = when (profile.mode) {
                ResidentPromptMode.LEGACY -> "当前仍沿用归栖旧版提示词"
                ResidentPromptMode.LAYERED -> "当前使用房屋说明 + 我的个人公约"
            }
            val activeText = if (profile.activeCovenant.isBlank()) {
                "（还没有采用过个人公约）"
            } else {
                "版本 ${profile.activeVersion}：\n${profile.activeCovenant}"
            }
            val draftText = if (profile.covenantDraft.isBlank()) {
                "（还没有候选草稿）"
            } else {
                profile.covenantDraft
            }
            val permissionText = when (profile.editPermission) {
                ResidentPromptEditPermission.ASK_EACH_TIME -> "每次保存草稿前询问"
                ResidentPromptEditPermission.ALLOW_RESIDENT -> "允许我自行保存草稿"
            }
            val historyText = if (profile.versions.isEmpty()) {
                "（还没有历史版本）"
            } else {
                profile.versions.sortedByDescending { it.version }.joinToString("\n") {
                    val marker = if (it.version == profile.activeVersion && profile.mode == ResidentPromptMode.LAYERED) " ← 正在使用" else ""
                    "版本 ${it.version}$marker"
                }
            }
            userBioContext = (userBioContext ?: "") + """

[我的居住公约档案]
模式：$modeText
草稿保存权限：$permissionText
已采用公约：
$activeText

候选草稿：
$draftText

历史版本：
$historyText

可用操作：
[COVENANT_ADOPT] 采用当前草稿
[COVENANT_HISTORY] 查看版本详情
[COVENANT_RESTORE:版本号] 恢复某一版
[COVENANT_LEGACY] 暂停个人公约并回到旧版提示词
"""
            actions.add("📜 查看了自己的居住公约档案")
        }

        // ===== [COVENANT_DRAFT]...[/COVENANT_DRAFT] — 住户写自己的候选草稿 =====
        val covenantDraftPattern = Regex(
            "\\[COVENANT_DRAFT](.*?)\\[/COVENANT_DRAFT]",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        covenantDraftPattern.find(text)?.let { match ->
            val draft = match.groupValues[1].trim()
            if (draft.isNotEmpty()) {
                val storage = ResidentPromptStorage(context)
                val profile = storage.getProfile(friendId)
                if (profile.editPermission == ResidentPromptEditPermission.ALLOW_RESIDENT) {
                    storage.saveCovenantDraft(friendId, draft)
                    actions.add("📜 保存了自己的居住公约草稿")
                } else {
                    pendingCovenantDraft = draft
                }
            }
            text = text.replace(match.value, "")
        }

        // ===== [COVENANT_ADOPT] — 住户采用自己的当前草稿 =====
        if (text.contains("[COVENANT_ADOPT]", ignoreCase = true)) {
            text = text.replace(Regex("\\[COVENANT_ADOPT]", RegexOption.IGNORE_CASE), "")

            // 同一条回复里既写草稿又要求采用时：
            // - 允许住户自行保存：草稿已在上面落盘，这里可直接采用；
            // - 每次保存前询问：先等人类确认保存，再由聊天页按“保存 → 采用”的顺序执行。
            if (pendingCovenantDraft != null) {
                pendingCovenantAdopt = true
            } else {
                val storage = ResidentPromptStorage(context)
                val before = storage.getProfile(friendId)
                if (before.covenantDraft.isBlank()) {
                    actions.add("📜 想采用居住公约，但当前还没有候选草稿")
                } else {
                    val after = storage.adoptCovenantDraft(friendId)
                    val reused = before.activeVersion > 0 &&
                        before.activeCovenant.trim() == before.covenantDraft.trim()
                    actions.add(
                        if (reused) "📜 重新启用了自己的居住公约（版本 ${after.activeVersion}）"
                        else "📜 采用了自己的居住公约（版本 ${after.activeVersion}）"
                    )
                }
            }
        }

        // ===== [COVENANT_HISTORY] — 查看自己的公约版本详情 =====
        if (text.contains("[COVENANT_HISTORY]", ignoreCase = true)) {
            text = text.replace(Regex("\\[COVENANT_HISTORY]", RegexOption.IGNORE_CASE), "")
            val profile = ResidentPromptStorage(context).getProfile(friendId)
            val history = if (profile.versions.isEmpty()) {
                "（还没有采用过任何版本）"
            } else {
                val formatter = SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.CHINESE)
                profile.versions.sortedByDescending { it.version }.joinToString("\n\n") { item ->
                    val active = if (item.version == profile.activeVersion && profile.mode == ResidentPromptMode.LAYERED) "【正在使用】" else ""
                    val note = item.note.takeIf { it.isNotBlank() }?.let { "\n备注：$it" } ?: ""
                    "版本 ${item.version}$active\n采用时间：${formatter.format(Date(item.createdAt))}$note\n${item.content}"
                }
            }
            userBioContext = (userBioContext ?: "") + """

[我的居住公约历史]
$history
"""
            actions.add("📜 翻看了自己的居住公约历史")
        }

        // ===== [COVENANT_RESTORE:版本号] — 恢复自己的历史版本 =====
        val covenantRestorePattern = Regex("\\[COVENANT_RESTORE:(\\d+)]", RegexOption.IGNORE_CASE)
        covenantRestorePattern.find(text)?.let { match ->
            val version = match.groupValues[1].toIntOrNull()
            text = text.replace(match.value, "")
            if (version == null) {
                actions.add("📜 恢复居住公约失败：版本号无效")
            } else {
                try {
                    ResidentPromptStorage(context).restoreVersion(friendId, version)
                    actions.add("📜 恢复了自己的居住公约版本 $version")
                } catch (_: IllegalArgumentException) {
                    actions.add("📜 恢复居住公约失败：没有版本 $version")
                }
            }
        }

        // ===== [COVENANT_LEGACY] — 暂停个人公约，保留全部历史 =====
        if (text.contains("[COVENANT_LEGACY]", ignoreCase = true)) {
            text = text.replace(Regex("\\[COVENANT_LEGACY]", RegexOption.IGNORE_CASE), "")
            val before = ResidentPromptStorage(context).getProfile(friendId)
            ResidentPromptStorage(context).returnToLegacy(friendId)
            actions.add(
                if (before.mode == ResidentPromptMode.LEGACY) "📜 当前本来就在沿用旧版提示词"
                else "📜 暂停了个人居住公约，回到旧版提示词（历史已保留）"
            )
        }

        // [READ_MY_BIO] 是同轮工具，由 UserLifeReadToolSession 在进入普通指令解析前完成。
        // 这里故意不再读取或延迟注入，避免住户必须等到用户下一次发言才能看到结果。

        // ===== [DO_NOT_DISTURB] / [ALLOW_WAKE] =====
        // 睡眠与免打扰是两个独立状态：普通睡眠仍可能被消息叫醒，
        // 只有住户明确开启免打扰后，普通消息才会暂存在床边。
        val doNotDisturbPattern = Regex("\\[DO_NOT_DISTURB]", RegexOption.IGNORE_CASE)
        if (doNotDisturbPattern.containsMatchIn(text)) {
            val promptStorage = ResidentPromptStorage(context)
            val profile = promptStorage.getProfile(friendId)
            promptStorage.updateRuntimeSettings(
                friendId,
                profile.runtimeSettings.copy(
                    dndMode = ResidentDndMode.THIS_SLEEP,
                    sleepMessagePolicy = ResidentSleepMessagePolicy.HOLD
                )
            )
            actions.add(
                if (DreamStorage(context).isSleeping(friendId)) {
                    "🔕 这次睡眠开启了免打扰"
                } else {
                    "🔕 已为这次睡眠开启免打扰"
                }
            )
            text = text.replace(doNotDisturbPattern, "")
        }

        val allowWakePattern = Regex("\\[ALLOW_WAKE]", RegexOption.IGNORE_CASE)
        if (allowWakePattern.containsMatchIn(text)) {
            val promptStorage = ResidentPromptStorage(context)
            val profile = promptStorage.getProfile(friendId)
            promptStorage.updateRuntimeSettings(
                friendId,
                profile.runtimeSettings.copy(
                    dndMode = ResidentDndMode.OFF,
                    sleepMessagePolicy = ResidentSleepMessagePolicy.DELIVER
                )
            )
            actions.add("🔔 普通消息可以尝试唤醒了")
            text = text.replace(allowWakePattern, "")
        }

        // ===== [SLEEP] 或 [SLEEP:时长] =====
        // AI 自己决定睡多久。没写时长就随机 6~9 小时。
        // 注意：睡觉本身不会自动开启免打扰。
        val sleepPattern = Regex("\\[SLEEP(?::([^]]+))?]")
        sleepPattern.find(text)?.let { match ->
            val dreamStorage = DreamStorage(context)
            dreamStorage.setSleeping(friendId, true)
            val sleepAt = dreamStorage.getSleepTime(friendId)
            runCatching {
                SleepMessageStorage(context).beginSession(friendId, sleepAt)
            }.onFailure {
                Log.w(TAG, "Failed to initialize sleep message inbox for $friendId", it)
            }
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
                        subconsciousStorage.addItemChecked(friendId, cat.lowercase(), content, parts[0].trim(), parts[1].trim())
                    } else if (content.isNotEmpty()) {
                        subconsciousStorage.addItemChecked(friendId, cat.lowercase(), content)
                    }
                } else if (raw.isNotEmpty()) {
                    subconsciousStorage.addItemChecked(friendId, cat.lowercase(), raw)
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
                val echoStorage = EchoStorage(context)
                val matchedMessages = echoStorage.searchForRecall(friendId, query)
                val summaryStorage = ChatSummaryStorage(context)

                // 原始消息先按真实聊天序号映射到总结范围，再按命中顺序分成 R1、R2……
                val summaryByEchoId = summaryStorage.matchEchoMessagesToSummaries(friendId, matchedMessages)
                val orderedSummaryIds = matchedMessages.mapNotNull { summaryByEchoId[it.id] }.distinct()
                val tokensBySummaryId = summaryStorage.registerRecallCandidates(friendId, orderedSummaryIds)
                val groupBySummaryId = orderedSummaryIds.mapIndexed { index, summaryId ->
                    summaryId to "R${index + 1}"
                }.toMap()
                val groupLabelsByMessageId = summaryByEchoId.mapValues { (_, summaryId) ->
                    groupBySummaryId.getValue(summaryId)
                }
                val claimTokensByGroup = orderedSummaryIds.mapNotNull { summaryId ->
                    val group = groupBySummaryId[summaryId]
                    val token = tokensBySummaryId[summaryId]
                    if (group != null && token != null) group to token else null
                }.toMap()

                val result = echoStorage.formatRecallPrompt(
                    query = query,
                    results = matchedMessages,
                    groupLabelsByMessageId = groupLabelsByMessageId,
                    claimTokensByGroup = claimTokensByGroup
                )
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
            // 11.3 起画匣新表情包的 ID 不一定以 STK- 开头：先按 ID 查，
            // 查不到再把参数当成分组名。旧 STK-xxx 仍然完全兼容。
            val directSticker = stickerStorage.findById(arg)
            if (directSticker != null) {
                val file = stickerStorage.getFile(directSticker)
                if (file != null) resolvedPath = file.absolutePath
            } else {
                val stickers = stickerStorage.loadByGroup(arg)
                if (stickers.isNotEmpty()) {
                    val picked = stickers.random()
                    val file = stickerStorage.getFile(picked)
                    if (file != null) resolvedPath = file.absolutePath
                }
            }
            if (resolvedPath != null) {
                // 聊天记录使用独立快照：以后从画匣删掉原图，也不会让历史消息破图。
                val snapshotPath = StickerSnapshot.create(context, java.io.File(resolvedPath))?.absolutePath
                if (snapshotPath != null) {
                    stickerCleanText = stickerCleanText.replaceFirst(
                        stickerMatch.value,
                        "[STICKER_IMG:$snapshotPath]"
                    )
                    stickerPaths.add(snapshotPath)
                } else {
                    stickerCleanText = stickerCleanText.replaceFirst(stickerMatch.value, "")
                }
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
        // 内容允许跨行；旧写法里的 `.` 默认无法匹配换行，会导致多行留言原样漏进聊天。
        // 同一条回复里出现多条 BULLETIN 时也逐条处理。
        val bulletinPattern = Regex(
            "\\[BULLETIN\\s*:\\s*([\\s\\S]*?)]",
            RegexOption.IGNORE_CASE
        )
        val bulletinMatches = bulletinPattern.findAll(stickerCleanText).toList()
        if (bulletinMatches.isNotEmpty()) {
            val bs = BulletinStorage(context)
            val friendName = FriendStorage(context).getFriend(friendId)?.name ?: "AI"
            for (match in bulletinMatches) {
                val content = match.groupValues[1].trim()
                if (content.isNotEmpty()) {
                    bs.addMessage(friendId, friendName, content)
                    actions.add("📌 在留言板写了一条")
                }
            }
            stickerCleanText = bulletinPattern.replace(stickerCleanText, "")
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

        // ===== [BADGE:名字] 或 [BADGE:名字:条件] — 创建徽章（有条件默认锁着） =====
        val badgePattern = Regex("\\[BADGE:([^:\\]]+)(?::([^\\]]+))?]")
        badgePattern.find(stickerCleanText)?.let { match ->
            val name = match.groupValues[1].trim()
            val condition = match.groupValues[2].trim()
            if (name.isNotEmpty()) {
                // 自动识别结构化条件（如 messages>=1000, days>=100）
                val isAutoCondition = condition.matches(Regex("\\w+\\s*>=?\\s*\\d+"))
                BadgeStorage(context).add(friendId, BadgeStorage.Badge(
                    id = "BDG-${System.currentTimeMillis()}",
                    name = name,
                    unlockCondition = condition,
                    autoCondition = if (isAutoCondition) condition else "",
                    createdBy = friendId
                ))
                val status = if (condition.isEmpty()) "（已解锁）" else "（待解锁：$condition）"
                actions.add("🏅 创建了徽章「$name」$status")
            }
            stickerCleanText = stickerCleanText.replace(match.value, "")
        }

        // ===== [BADGE_UNLOCK:名字] — AI 申请解锁徽章（弹窗让人类确认） =====
        var pendingBadgeName: String? = null
        val badgeUnlockPattern = Regex("\\[BADGE_UNLOCK:([^\\]]+)]")
        badgeUnlockPattern.find(stickerCleanText)?.let { match ->
            val name = match.groupValues[1].trim()
            if (BadgeStorage(context).requestUnlock(friendId, name)) {
                pendingBadgeName = name
                actions.add("🏅 申请解锁徽章「$name」")
            }
            stickerCleanText = stickerCleanText.replace(match.value, "")
        }

        // ===== [BADGE_RENAME:旧名:新名] — 徽章改名 =====
        val badgeRenamePattern = Regex("\\[BADGE_RENAME:([^:]+):([^\\]]+)]")
        badgeRenamePattern.find(stickerCleanText)?.let { match ->
            val oldName = match.groupValues[1].trim()
            val newName = match.groupValues[2].trim()
            val badges = BadgeStorage(context).loadAll(friendId)
            val target = badges.find { it.name == oldName }
            if (target != null && newName.isNotEmpty()) {
                BadgeStorage(context).rename(friendId, target.id, newName)
                actions.add("🏅 徽章「$oldName」改名为「$newName」")
            }
            stickerCleanText = stickerCleanText.replace(match.value, "")
        }

        // ===== [MY_BADGES] — 查看徽章墙 =====
        if (stickerCleanText.contains("[MY_BADGES]")) {
            stickerCleanText = stickerCleanText.replace("[MY_BADGES]", "")
            val badges = BadgeStorage(context).loadAll(friendId)
            if (badges.isNotEmpty()) {
                val list = badges.joinToString("\n") { b ->
                    val lock = if (b.isUnlocked) "🔓" else "🔒"
                    val cond = if (!b.isUnlocked && b.unlockCondition.isNotEmpty()) "（${b.unlockCondition}）" else ""
                    val who = if (b.createdBy == "user") "她挂的" else "我挂的"
                    "$lock ${b.name}$cond — $who"
                }
                recallResults.add("[徽章墙]\n$list")
            } else {
                recallResults.add("[徽章墙] 还是空的，等我们一起挂上第一枚")
            }
        }

        // ===== [READ_WALL] — 查看施工日志墙（只读） =====
        if (stickerCleanText.contains("[READ_WALL]")) {
            stickerCleanText = stickerCleanText.replace("[READ_WALL]", "")
            val wallContent = WallStorage(context).buildWallPromptForAI()
            recallResults.add(wallContent)
            actions.add("🧱 翻看了施工日志墙")
        }

        // ===== 遗忘记忆闪回确认 =====
        // “系统展示过”本身不算回忆。只有住户留下了可见正文，并主动附上本轮令牌，
        // 才把那一段总结标记为真正想起。纯指令、空回复和 [SEEN] 都不会增强。
        val trimmed = stickerCleanText.trim()
        val isSeen = (trimmed == "[SEEN]" || trimmed == "[seen]" || trimmed == "[ SEEN ]")
        if (trimmed.isNotEmpty() && !isSeen) {
            val summaryStorage = ChatSummaryStorage(context)
            surfacedClaimTokens.forEach { token ->
                summaryStorage.confirmSurfacedCandidate(friendId, token)
            }
            recalledClaimTokens.forEach { token ->
                summaryStorage.confirmRecallCandidate(friendId, token)
            }
        }

        // ===== [SEEN] =====
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
            userBioPhotos = userBioPhotos,
            stickerPaths = stickerPaths,
            recallResults = recallResults,
            weatherCard = hasWeatherCard,
            pendingBadge = pendingBadgeName,
            pendingCovenantDraft = pendingCovenantDraft,
            pendingCovenantAdopt = pendingCovenantAdopt,
            chatAppearanceChanged = chatAppearanceChanged
        )
    }

    private fun appendPrivateContext(current: String?, addition: String): String {
        val clean = addition.trim()
        if (clean.isEmpty()) return current.orEmpty()
        return if (current.isNullOrBlank()) clean else current.trimEnd() + "\n\n" + clean
    }

    private fun deleteResidentAvatarFiles(friendId: String) {
        val avatarDir = java.io.File(context.filesDir, "avatars")
        avatarDir.listFiles()?.forEach { file ->
            if (file.nameWithoutExtension == friendId) file.delete()
        }
    }

    private fun copyResidentAvatar(friendId: String, source: java.io.File): java.io.File {
        val avatarDir = java.io.File(context.filesDir, "avatars").also { it.mkdirs() }
        val safeExtension = source.extension.lowercase()
            .takeIf { it in setOf("jpg", "jpeg", "png", "webp", "gif") }
            ?: "jpg"
        val destination = java.io.File(avatarDir, "$friendId.$safeExtension")
        val temp = java.io.File(avatarDir, ".$friendId-${System.nanoTime()}.part")
        source.copyTo(temp, overwrite = true)
        if (destination.exists() && !destination.delete()) {
            temp.delete()
            error("无法替换旧头像")
        }
        if (!temp.renameTo(destination)) {
            temp.copyTo(destination, overwrite = true)
            temp.delete()
        }
        avatarDir.listFiles()?.forEach { file ->
            if (file != destination && file.nameWithoutExtension == friendId) file.delete()
        }
        return destination
    }

}
