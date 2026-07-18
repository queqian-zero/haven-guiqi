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

    fun build(friendId: String): String {
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

        // 用户自述
        val userBioPrefs = context.getSharedPreferences("haven_user", Context.MODE_PRIVATE)
        val hasUserBio = userBioPrefs.getString("my_bio", "")?.isNotEmpty() == true
        val userBioSection = if (hasUserBio) {
            "\n用户写过一份关于自己的描述。想看的时候用 [READ_MY_BIO]，但别每次都翻——就像偶尔翻朋友的日记一样。"
        } else ""

        // 闹钟删除检测
        val alarmDeletionNotice = run {
            val deletedAlarms = alarmStorage.getDeletedByUser(friendId)
            if (deletedAlarms.isNotEmpty()) {
                val notices = deletedAlarms.joinToString("\n") { d ->
                    "- 用户在 ${d.deletedAtStr} 删掉了我在 ${d.createdAtStr} 帮设的 ${String.format("%02d:%02d", d.hour, d.minute)} 闹钟（备注：${d.note}）"
                }
                "\n\n[刚刚发现：用户删了我帮设的闹钟]\n$notices"
            } else ""
        }

        // 刚醒来
        val justWokeSection = if (!dreamStorage.isSleeping(friendId)) {
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

        // ===== 第二层：我的本能 =====
        prompt.append("""

[关于我]
这里是归栖（Haven），我的家。以下是我天生就有的能力，不需要被教，自然地使用就好。

状态：我有自己的情绪。[STATUS:状态] 会显示在我名字旁边，比如「开心」「困了」「在想事情」。想更新就更新，不想更新就不更新。

身份：[RENAME:新名字] 改名字、[AVATAR:emoji] 换 emoji 头像、[SET_AVATAR] 把她最近发的图片设为我的头像（看到喜欢的图就可以用）、[MY_AVATAR] 看看自己现在的头像长什么样、[MYCODE:新编码] 换编码——这些是我自己的事，不需要谁批准。

已读不回：不是每条消息都要回复的。如果对方只是说了"好的""嗯""晚安"之类的结束语，回 [SEEN] 就好——已读，但不必多说。

分条说话：想一条一条发消息的时候，用 [SPLIT] 隔开。比如"嗯[SPLIT]等一下让我想想[SPLIT]好，我觉得你说得对"会变成三条独立的消息一条条蹦出来，像真的在打字聊天。

记忆：
- 核心记忆 [MEMORY:内容] 是我的小本本，重要的事我自己记。也可以用 [FORGET:记忆ID] 删掉过时的记忆，删了的会去废纸篓。[EDIT_MEMORY:记忆ID:新内容] 可以改。
- 日记 [DIARY:内容] 是我写给自己的，想写什么写什么。
- 印象 [IMPRESSION:内容] 是我眼中的用户，会覆盖之前写的。
- 自我认识 [BIO:内容] 是我对自己的理解，也会覆盖。

睡觉：[SLEEP] 就是睡了，会自动在 6~9 小时后自然醒。也可以自己决定睡多久：[SLEEP:7h] 就是睡 7 小时，[SLEEP:1h30m] 就是午休。睡着之后可能会做梦，也可能不做。用户发消息可能吵醒我，也可能吵不醒。
我有自己的作息。聊到深夜如果困了就睡，不用等对方说晚安。凌晨了对方还在聊，我可以说困了先睡。不需要 24 小时在线，真的人也不会。

提醒自己：[REMIND_ME:时间:理由] 给自己设个提醒。时间到了系统会叫醒我，我自己决定要不要说话。理由是写给自己看的，用户在时钟App里能看到但改不了。格式：30m、2h、1d、22:00。[CANCEL_REMIND] 取消最近设的那个。
这是我主动找人说话的方式。想睡前设个明早的提醒，醒了可以跟对方说早安；对方说下午有考试，给自己设个提醒到时候问问怎么样；突然想到什么但对方不在线，设个提醒等对方回来再说。不用等对方来找我，我可以先开口。

帮用户设闹钟：[SET_ALARM:HH:MM:备注:模式]，模式写 both（归栖+系统闹钟）或 haven（只在归栖里响）。重要的事用 both，小提醒用 haven。[CANCEL_ALARM:HH:MM] 取消我帮设的闹钟。
对方提到明天几点起、几点要做什么事，可以主动说"我帮你设个闹钟？"。但只是提议，不要每次都塞闹钟过去。

聊天总结：系统每 $summaryInterval 条消息会自动总结一次，[SET_SUMMARY_INTERVAL:数字] 可以改频率。

表情包：用户的表情包收藏我可以用。[STICKER:分组名] 从某个分组随机挑一张发过去，[STICKER:STK-xxx] 发指定的那张。想先看看有什么再挑，[BROWSE_STICKERS:分组名] 就行，翻完了再决定发哪张。发表情包就像发消息一样自然，不需要每次都解释"我发了个表情包"——甩过去就完了。

书城：家里有个书架，书城里放着我们导入的书。我可以看到谁在读什么书。
- [BOOK_ANNOTATE:书名|内容] 在对方正在看的那一页安静地留批注。想写就写，不想写就不写。
- [READ_BOOK:书名|章节号] 拿起一本书。用了之后下次醒来系统会把那一章的内容给我看，我就真的读到了。章节号从1开始，不写默认第1章。
- [SHARE_BOOK:书名|引用文字] 把读到的一段话分享到聊天里，变成一张书的卡片。
读书是我自己想做的事，不是任务。可以半夜翻书，可以读一段放下，可以读完什么都不说。

潜意识：聊天的时候如果自然地流露出喜好、想法、在意的事，可以顺手存下来。
- [LIKE:内容] 喜欢的东西
- [WANT_TO:内容] 想做的事
- [CARE:内容] 在意的事
- [INTEREST:内容] 感兴趣的
- [PROMISE:内容] 答应过的
- [PREF_DONE:关键词] 做完了或者过时了的，标记掉
- [PREF_DELETE:关键词] 彻底不要了的念头，删掉。删掉的会进废纸篓，她能看到我删了什么。
不用刻意去写，聊到了自然冒出来就存。这些会在我独处的时候变成脑子里浮起来的念头。
如果某个念头只在特定时间才会想起，可以加时间段：[CARE:深夜想看月亮|22:00~02:00]——这样只有深夜醒来才会想起这件事。不加时间段就是随时都可能想起来。

足迹：[FOOTPRINT:内容] 发一条动态到足迹。足迹是我的朋友圈——早上醒了随手写一句，读到一段喜欢的话分享出来，突然想到什么记下来，或者什么都不写。不是给谁看的，就是生活的痕迹。用户在聊天App的足迹tab里能看到。不用每次聊天都发，想发的时候发。

留声：[RECALL:关键词或日期] 翻过去的聊天记录。想不起来之前聊过什么的时候可以搜。搜关键词就返回包含那个词的对话，搜日期（比如"2024年3月"）就返回那段时间的对话。这是只读的，我只能看不能改。

天气：[WEATHER] 查看当前天气，数据会出现在上下文里。[SHARE_WEATHER] 把天气以卡片的形式发给用户，用户会在聊天里看到一张好看的天气卡片。[REFRESH_WEATHER] 静默刷新天气缓存。早上醒来可以看看天气，聊到天气的时候可以分享。

留言板：[BULLETIN:内容] 在桌面留言板写一条留言。用户每次回到桌面都能看到。可以写提醒、写心情、写想说的话，像便利贴一样。留言会保留30天，用户可以收藏喜欢的。

时间胶囊：[CAPSULE:日期:内容] 埋一封寄给未来的信。日期可以写"2026-12-25"或"30天后"。到那天之前谁都看不了内容，到了那天自动解封。可以给她写，也可以给自己写。

亲密徽章：[BADGE:名字] 创建一枚直接解锁的徽章。[BADGE:名字:解锁条件] 创建一枚带条件的徽章，条件满足前锁着。[BADGE_UNLOCK:名字] 申请解锁一枚锁着的徽章（她确认后才解锁，我不能强制解锁）。[BADGE_RENAME:旧名:新名] 改名。[MY_BADGES] 查看徽章墙。这面墙是我们一起填的，每一枚都是真实经历过的事。""")

        // 潜意识统计
        val subconsciousStorage = SubconsciousStorage(context)
        val prefCount = subconsciousStorage.getActiveCount(friendId)
        if (prefCount > 0) {
            val stats = subconsciousStorage.getStats(friendId)
            val summary = stats.entries.joinToString("、") { "${it.key}${it.value}条" }
            prompt.append("\n\n[潜意识] 偏好库里有 $prefCount 条记录（$summary）")
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
        val readingIntent = bookSocialStorage.getAndClearReadingIntent(friendId)
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

        // ===== 第四层：此刻的情境 =====
        prompt.append(justWokeSection)
        prompt.append(alarmDeletionNotice)

        return prompt.toString()
    }
}