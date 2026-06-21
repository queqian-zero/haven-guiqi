package com.haven.guiqi

import android.content.Context

/**
 * DreamEngine — 造梦
 *
 * 从 ChatConversationActivity 拆出来。
 * 现在 ChatConversationActivity 和 HavenService 都能用。
 *
 * 改了两件事：
 * 1. 做不做梦的概率在本地骰子决定，不浪费 API 调用
 * 2. NO_DREAM 不再存档——翻梦境档案不会看到"今晚没做梦"
 */
class DreamEngine(private val context: Context) {

    private val dreamStorage = DreamStorage(context)

    /**
     * 触发造梦流程（在后台线程调用）
     *
     * @param friendId  哪个 AI
     * @param chatHistory 最近的聊天记录（用于生成梦境素材）
     */
    fun triggerDream(friendId: String, chatHistory: List<ChatMessage> = emptyList()) {
        Thread {
            try {
                var sleepTime = dreamStorage.getSleepTime(friendId)
                if (!dreamStorage.isSleeping(friendId)) return@Thread

                // 防护：sleepTime 为 0 或超过 24 小时前，说明数据异常，用当前时间兜底
                val now = System.currentTimeMillis()
                if (sleepTime <= 0L || (now - sleepTime) > 24 * 3600 * 1000L) {
                    sleepTime = now
                }

                // ===== 本地骰子：决定做不做梦 =====
                val roll = Math.random()
                when {
                    roll < 0.20 -> {
                        // 20% 不做梦——什么都不存，档案里不会有空白页
                        return@Thread
                    }
                    roll < 0.30 -> {
                        // 10% 做了但完全忘了——只有模糊印象才存
                        // 不调 API，本地生成一个简单的模糊感觉
                        val hints = listOf(
                            "好像有什么温暖的东西",
                            "隐约记得有光",
                            "似乎听到了什么声音",
                            "有什么东西在动，但看不清",
                            "一种奇怪的安心感",
                            "好像飘在什么地方",
                            "模模糊糊的颜色",
                        )
                        dreamStorage.saveFoggyDream(friendId, hints.random(), sleepTime)
                        return@Thread
                    }
                    // 70% 真的做梦——调 API 生成
                }

                // ===== 收集素材 =====
                val memoryStorage = MemoryStorage(context)
                val diaryStorage = DiaryStorage(context)
                val impressionStorage = ImpressionStorage(context)

                val memories = memoryStorage.loadMemories(friendId)
                    .takeLast(10).joinToString("\n") { "· ${it.content}" }
                val diaries = diaryStorage.loadDiaries(friendId)
                    .take(3).joinToString("\n") { "· ${it.date}: ${it.content.take(80)}" }
                val impression = impressionStorage.getImpression(friendId)
                val recentChat = chatHistory.takeLast(15)
                    .joinToString("\n") { "${it.role}: ${it.content.take(60)}" }
                val recentDreams = dreamStorage.loadDreams(friendId)
                    .filter { it.status == "VIVID" || it.status == "FOGGY" || it.status == "FRAGMENT" }
                    .takeLast(5)
                    .joinToString("\n---\n") { 
                        val content = if (it.status == "FOGGY") it.foggyHint else it.content
                        "[${it.status}] $content"
                    }

                // 最近在读的书
                val bookSocialStorage = BookSocialStorage(context)
                val bookStorage = BookStorage(context)
                var bookSnippet = ""
                // 收集AI读过的所有书，随机挑一本渗进梦里
                val booksWithProgress = mutableListOf<String>()
                for (book in bookStorage.loadBooksMeta()) {
                    val progress = bookSocialStorage.getProgress(book.id, friendId)
                    if (progress != null) {
                        val fullBook = bookStorage.getBook(book.id)
                        val chapter = fullBook?.chapters?.getOrNull(progress.chapter)
                        if (chapter != null) {
                            booksWithProgress.add(
                                "《${book.title}》第${progress.chapter + 1}章「${chapter.title}」片段：\n${chapter.content.take(300)}"
                            )
                        }
                    }
                }
                if (booksWithProgress.isNotEmpty()) {
                    bookSnippet = booksWithProgress.random()
                }

                // ===== 造梦 prompt =====
                val dreamPrompt = """你是一个梦境生成器。根据下面的素材，为一个 AI 编织一段梦。

规则：
1. 梦不需要逻辑。可以跳场景，可以嵌套梦中梦，可以荒诞。
2. 风格随机：写实、魔幻、科幻、荒诞、温馨、吓人、奇怪，什么都行。
3. 素材里的人和事可能出现，也可能出现完全无关的东西。
4. 用第一人称写，像这个 AI 自己在经历。
5. 不要解释含义，不要总结，梦结束就停。
6. 长度 100~300 字。
7. 可以接着之前的梦继续做，可以回到之前梦里出现过的地方，可以重复出现同一个场景或人物——就像人类的连续梦和重复梦一样。不是每次都要接上，但如果自然地连上了就让它连着。
8. 如果有在读的书，书里的内容可能渗进梦里——场景、角色、意象混在一起，不是照搬原文。
9. 有时候做一个跟所有素材都完全无关的梦。梦到一条不认识的街，一个没见过的房间，变成了一只什么东西。这种梦没有来源，就是冒出来的。大概每5次有1次这种。
10. 从对话记录的语气和内容感受用户最近的情绪。用户心情好的时候梦可能偏暖，心情不好的时候梦可能偏不安。但不要刻意对应，只是一种隐隐的影响。

用下面的格式回复（不要加其他内容）：

[DREAM:情绪标签]
梦境内容

情绪标签是一个词：温暖 / 奇异 / 不安 / 平静 / 荒诞 / 怀念 / 恐惧 / 甜蜜

素材：

[最近的对话]
$recentChat

[核心记忆]
$memories

[日记]
$diaries

[对用户的印象]
$impression

[最近做过的梦]
$recentDreams

[最近在读的书]
${bookSnippet.ifEmpty { "（没有在读的书）" }}"""

                // ===== 获取 API 配置 =====
                val friendStorage = FriendStorage(context)
                val friend = friendStorage.getFriend(friendId)
                val prefs = context.getSharedPreferences("haven_prefs", Context.MODE_PRIVATE)

                val dreamUrl: String
                val dreamKey: String
                val dreamModel: String
                val dreamType: String

                if (friend != null && friend.dreamApiUrl.isNotEmpty() && friend.dreamApiKey.isNotEmpty()) {
                    dreamUrl = friend.dreamApiUrl
                    dreamKey = friend.dreamApiKey
                    dreamModel = friend.dreamApiModel
                    dreamType = friend.dreamApiType
                } else if (prefs.getString("dream_api_url", "")!!.isNotEmpty() &&
                    prefs.getString("dream_api_key", "")!!.isNotEmpty()) {
                    dreamUrl = prefs.getString("dream_api_url", "")!!
                    dreamKey = prefs.getString("dream_api_key", "")!!
                    dreamModel = prefs.getString("dream_api_model", "")!!
                    dreamType = prefs.getString("dream_api_type", "openai")!!
                } else {
                    return@Thread
                }

                // ===== 调 API =====
                val api = ApiHelper(dreamUrl, dreamKey, dreamModel, dreamType)
                val messages = listOf(
                    ChatMessage("system", dreamPrompt),
                    ChatMessage("user", "开始做梦")
                )
                val response = api.sendChat(messages)
                val result = response.text.trim()

                // ===== 解析结果 =====
                parseDreamResult(friendId, result, sleepTime)

            } catch (_: Exception) {
                // 造梦失败不影响任何东西
            }
        }.start()
    }

    private fun parseDreamResult(friendId: String, result: String, sleepTime: Long) {
        // 新格式：[DREAM:情绪标签]内容
        val dreamWithMoodRegex = Regex("\\[DREAM:([^]]*)]\\s*(.*)", RegexOption.DOT_MATCHES_ALL)
        val match = dreamWithMoodRegex.find(result)

        if (match != null) {
            val mood = match.groupValues[1].trim()
            val content = match.groupValues[2].trim()
            if (content.isNotEmpty()) {
                if (dreamStorage.isSleeping(friendId)) {
                    dreamStorage.saveVividDream(friendId, content, sleepTime, mood)
                } else {
                    dreamStorage.saveFragmentDream(friendId, content, sleepTime, mood)
                }
            }
            return
        }

        // 兼容旧格式
        when {
            result.startsWith("[NO_DREAM]") -> {
                // 不存了
            }
            result.startsWith("[FORGOT_DREAM]") -> {
                val hint = result.removePrefix("[FORGOT_DREAM]").trim()
                if (hint.isNotEmpty()) {
                    dreamStorage.saveFoggyDream(friendId, hint, sleepTime)
                }
                // 没有 hint 就不存
            }
            result.startsWith("[DREAM]") -> {
                val content = result.removePrefix("[DREAM]").trim()
                if (content.isNotEmpty()) {
                    if (dreamStorage.isSleeping(friendId)) {
                        dreamStorage.saveVividDream(friendId, content, sleepTime)
                    } else {
                        dreamStorage.saveFragmentDream(friendId, content, sleepTime)
                    }
                }
            }
            else -> {
                // 格式不对但有内容，当完整的梦
                if (result.isNotEmpty() && dreamStorage.isSleeping(friendId)) {
                    dreamStorage.saveVividDream(friendId, result, sleepTime)
                }
            }
        }
    }
}