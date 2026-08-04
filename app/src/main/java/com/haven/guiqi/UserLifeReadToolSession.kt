package com.haven.guiqi

import android.content.Context
import java.io.File

/**
 * “我眼中的自己”同轮读取工具。
 *
 * [READ_MY_BIO] 不是普通延迟指令：住户调用后，归栖会立刻把用户亲自填写的
 * 自我描述、动物/植物记录和可读取照片作为工具结果返还，再由住户在本轮继续回复。
 * 工具中间消息不会写进普通聊天正文，只留下可折叠的工具轨迹。
 */
internal object UserLifeReadToolSession {

    private val readPattern = Regex("\\[READ_MY_BIO]", RegexOption.IGNORE_CASE)

    data class TraceEvent(
        val kind: String,
        val content: String
    )

    data class Resolution(
        val response: ApiResponse,
        val traceEvents: List<TraceEvent>,
        /** 已经真实送进 API 的“住户调用 → 工具结果”消息，供后续工具继续沿用上下文。 */
        val continuationMessages: List<ChatMessage>
    )

    fun resolve(
        context: Context,
        friendName: String,
        baseContext: List<ChatMessage>,
        firstResponse: ApiResponse,
        sendChat: (List<ChatMessage>) -> ApiResponse,
        onReading: (() -> Unit)? = null,
        maxToolSteps: Int = 3
    ): Resolution {
        var currentResponse = firstResponse
        val loopMessages = mutableListOf<ChatMessage>()
        val traceEvents = mutableListOf<TraceEvent>()
        val thinkingParts = mutableListOf<String>()
        var usedTool = false
        var toolStep = 0

        fun addThinking(text: String) {
            val clean = text.trim()
            if (clean.isNotEmpty()) {
                thinkingParts += clean
                traceEvents += TraceEvent("思考", clean)
            }
        }

        fun finish(): Resolution {
            val cleanText = stripMarkup(currentResponse.text)
            return if (usedTool) {
                Resolution(
                    response = currentResponse.copy(thinking = "", text = cleanText),
                    traceEvents = traceEvents.toList(),
                    continuationMessages = loopMessages.toList()
                )
            } else {
                Resolution(
                    response = currentResponse.copy(
                        thinking = thinkingParts.joinToString("\n\n"),
                        text = cleanText
                    ),
                    traceEvents = emptyList(),
                    continuationMessages = emptyList()
                )
            }
        }

        addThinking(firstResponse.thinking)

        while (toolStep < maxToolSteps && requestsRead(currentResponse.text)) {
            usedTool = true
            onReading?.invoke()

            val storage = UserLifeStorage(context)
            val readContext = storage.buildReadContext()
            val encodedPhotos = mutableListOf<String>()
            val encodedLabels = mutableListOf<String>()

            storage.readablePhotos().forEach { photo ->
                val file = File(photo.path)
                if (!file.isFile) return@forEach
                try {
                    encodedPhotos += ImageHelper.toBase64(file)
                    encodedLabels += photo.label
                } catch (_: Exception) {
                    // 单张照片读取失败不阻断文字资料和其余照片。
                }
            }

            val hasContent = !readContext.isNullOrBlank()
            val traceBody = if (hasContent) {
                buildString {
                    append(friendName).append("翻看了你在「我眼中的自己」里写下的内容")
                    if (encodedPhotos.isNotEmpty()) {
                        append("，并查看了 ").append(encodedPhotos.size).append(" 张照片")
                    }
                    append('。')
                }
            } else {
                "$friendName 想翻看「我眼中的自己」，但这个页面目前还是空的。"
            }
            traceEvents += TraceEvent("我眼中的自己", traceBody)

            val toolResult = buildToolResult(readContext, encodedLabels)
            loopMessages += ChatMessage("assistant", currentResponse.text)
            loopMessages += ChatMessage("user", toolResult, encodedPhotos)
            toolStep++
            currentResponse = sendChat(baseContext + loopMessages)
            addThinking(currentResponse.thinking)
        }

        if (requestsRead(currentResponse.text)) {
            traceEvents += TraceEvent(
                "我眼中的自己",
                "本轮重复查看次数达到安全上限，归栖停止了继续调用；页面内容没有发生变化。"
            )
        }
        return finish()
    }

    private fun requestsRead(text: String): Boolean = readPattern.containsMatchIn(text)

    private fun stripMarkup(text: String): String = readPattern.replace(text, "")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

    private fun buildToolResult(readContext: String?, photoLabels: List<String>): String = buildString {
        append("[查看『我眼中的自己』工具结果]\n")
        if (readContext.isNullOrBlank()) {
            append("用户目前还没有在这个页面写下内容，也没有动物或植物记录。\n")
        } else {
            append(readContext.trim()).append('\n')
        }

        if (photoLabels.isNotEmpty()) {
            append("\n[本次随附照片]\n")
            photoLabels.forEachIndexed { index, label ->
                append(index + 1).append(". ").append(label).append('\n')
            }
        }

        append("\n这是你刚刚主动调用后得到的真实工具结果。")
        append("请依据你实际看到的内容，自然完成当前这一轮回复；")
        append("不要向用户解释后台协议，也不要把工具标记原样发出来。")
    }.trim()
}
