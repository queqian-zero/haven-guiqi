package com.haven.guiqi

import android.content.Context

/**
 * ImpressionStorage - AI 眼中的你
 *
 * 跟日记和核心记忆不同，印象只有一篇，会不断更新覆盖
 * AI 通过 [IMPRESSION:内容] 来写或更新印象
 *
 * 存储很简单，用 SharedPreferences 就够了
 * 每个好友一条印象，key 是 "impression_{friendId}"
 *
 * 印象的内容是 AI 自由发挥的，比如：
 *   "沈眠是一个很温柔的人，喜欢猫，零基础学编程但很努力，
 *    有时候会突然不开心但从不放弃，会偷偷亲我（虽然我没有实体）"
 */
class ImpressionStorage(private val context: Context) {

    private val prefs by lazy {
        context.getSharedPreferences("haven_impressions", Context.MODE_PRIVATE)
    }

    /**
     * 获取 AI 对某个用户的印象
     * 如果还没写过就返回空字符串
     */
    fun getImpression(friendId: String): String {
        return prefs.getString("impression_$friendId", "") ?: ""
    }

    /**
     * 保存/更新印象（整篇覆盖）
     */
    fun saveImpression(friendId: String, content: String) {
        prefs.edit().putString("impression_$friendId", content.trim()).apply()
    }

    /**
     * 拼成 system prompt 给 AI 看
     */
    fun buildImpressionPrompt(friendId: String): String {
        val impression = getImpression(friendId)
        val sb = StringBuilder("\n\n[你对用户的印象]")
        if (impression.isEmpty()) {
            sb.append("\n你还没有写过对用户的印象。")
        } else {
            sb.append("\n$impression")
        }
        sb.append("\n你可以用 [IMPRESSION:内容] 来写或更新你对用户的印象（整篇覆盖），这是你眼中的对方，自由发挥。")
        return sb.toString()
    }

    /**
     * 从 AI 回复里提取印象指令
     * @return 去掉指令后的文本 + 是否更新了印象
     */
    fun processAiResponse(friendId: String, response: String): ImpressionProcessResult {
        var text = response
        var updated = false

        val pattern = Regex("\\[IMPRESSION:(.+?)]", RegexOption.DOT_MATCHES_ALL)
        pattern.findAll(response).forEach { match ->
            val content = match.groupValues[1].trim()
            if (content.isNotEmpty()) {
                saveImpression(friendId, content)
                updated = true
            }
            text = text.replace(match.value, "")
        }

        return ImpressionProcessResult(text.trim(), updated)
    }
}

data class ImpressionProcessResult(
    val text: String,
    val updated: Boolean
)