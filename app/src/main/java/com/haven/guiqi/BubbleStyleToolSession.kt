package com.haven.guiqi

/**
 * 住户代码气泡的同轮工具会话。
 *
 * 这个对象不依赖 Activity，便于用伪造 API 回复走完整流程测试：
 * 查看规则 → 提交 → 得到成功/错误 → 住户自主重试或结束。
 *
 * 与普通回复不同，工具会话保留“可见思考片段 → 工具调用 → 可见思考片段”
 * 的真实先后顺序。聊天页会把这些片段画成一条可折叠的工具时间轴，
 * 而不是把所有思考合并到最前面、把所有工具卡片堆到后面。
 */
internal object BubbleStyleToolSession {

    enum class TraceKind {
        THINKING,
        BUBBLE_TOOL
    }

    data class TraceEvent(
        val kind: TraceKind,
        val content: String
    ) {
        fun toStoredRecord(index: Int, total: Int): String {
            val safeIndex = index.coerceIn(1, total.coerceAtLeast(1))
            val safeTotal = total.coerceAtLeast(1)
            val kindLabel = when (kind) {
                TraceKind.THINKING -> "思考"
                TraceKind.BUBBLE_TOOL -> "代码气泡"
            }
            return "[工具轨迹·$kindLabel·$safeIndex/$safeTotal]\n${content.trim()}"
        }
    }

    data class Resolution(
        val response: ApiResponse,
        /** 保留给旧测试与调用方读取；新界面以 traceEvents 为准。 */
        val humanRecords: List<String>,
        val traceEvents: List<TraceEvent>
    )

    fun resolve(
        friendName: String,
        friendId: String,
        storage: BubbleStyleStorage,
        baseContext: List<ChatMessage>,
        firstResponse: ApiResponse,
        sendChat: (List<ChatMessage>) -> ApiResponse,
        maxDraftAttempts: Int = 5,
        maxToolSteps: Int = 8
    ): Resolution {
        var currentResponse = firstResponse
        val loopMessages = mutableListOf<ChatMessage>()
        val humanRecords = mutableListOf<String>()
        val traceEvents = mutableListOf<TraceEvent>()
        val thinkingParts = mutableListOf<String>()
        var draftAttempt = 0
        var toolStep = 0
        var usedTool = false

        fun addThinking(text: String) {
            val clean = text.trim()
            if (clean.isNotEmpty()) {
                thinkingParts += clean
                traceEvents += TraceEvent(TraceKind.THINKING, clean)
            }
        }

        fun addToolRecord(record: String) {
            val clean = record.trim()
            if (clean.isEmpty()) return
            usedTool = true
            humanRecords += clean
            traceEvents += TraceEvent(TraceKind.BUBBLE_TOOL, clean)
        }

        fun sendToolResult(result: String) {
            loopMessages += ChatMessage("assistant", currentResponse.text)
            loopMessages += ChatMessage("user", result)
            currentResponse = sendChat(baseContext + loopMessages)
            addThinking(currentResponse.thinking)
        }

        fun finish(responseText: String = currentResponse.text): Resolution {
            val cleanText = BubbleStyleDraftTool.stripAllToolMarkup(responseText)
            return if (usedTool) {
                Resolution(
                    response = currentResponse.copy(thinking = "", text = cleanText),
                    humanRecords = humanRecords.toList(),
                    traceEvents = traceEvents.toList()
                )
            } else {
                Resolution(
                    response = currentResponse.copy(
                        thinking = thinkingParts.joinToString("\n\n"),
                        text = cleanText
                    ),
                    humanRecords = emptyList(),
                    traceEvents = emptyList()
                )
            }
        }

        addThinking(firstResponse.thinking)

        while (toolStep < maxToolSteps) {
            if (BubbleStyleDraftTool.requestsInfo(currentResponse.text)) {
                val alsoSubmitted = BubbleStyleDraftTool.containsDraft(currentResponse.text)
                val prematureCss = BubbleStyleDraftTool.firstDraftCss(currentResponse.text)
                val record = buildString {
                    append("[代码气泡工具·查看]\n")
                    append(friendName).append("查看了自己的代码气泡规则、当前模式和待确认草稿。")
                    if (alsoSubmitted) {
                        append("\n这条回复里同时写出的草稿尚未提交；要等工具档案返回后由TA自己决定是否重交。")
                        append("\n\n尚未提交的原始代码：\n")
                        append(prematureCss?.ifBlank { "（空）" } ?: "（无法读取）")
                    }
                }
                addToolRecord(record)
                val result = buildString {
                    append(BubbleStyleDraftTool.buildInfoResult(friendId, storage))
                    if (alsoSubmitted) {
                        append("\n\n你在查看工具结果之前同时写出了一份草稿。")
                        append("为了保证这是你看完真实规则后的自主决定，那份草稿本次没有提交。")
                        append("读完后愿意继续就重新提交；不愿意就自然结束。")
                    }
                }
                toolStep++
                sendToolResult(result)
                continue
            }

            val nextAttempt = draftAttempt + 1
            val evaluation = BubbleStyleDraftTool.evaluateDraft(
                currentResponse.text,
                friendId,
                storage,
                nextAttempt
            ) ?: return finish()

            draftAttempt = nextAttempt
            addToolRecord(evaluation.humanRecord)
            toolStep++

            if (draftAttempt >= maxDraftAttempts) {
                val limitResult = evaluation.residentResult +
                    "\n\n本轮已经连续校验 $maxDraftAttempts 次。为避免无限循环，当前轮不再接受新的提交。" +
                    "请保留自己的判断，自然告诉用户最终决定；可以下次再继续。"
                sendToolResult(limitResult)
                if (BubbleStyleDraftTool.containsDraft(currentResponse.text)) {
                    addToolRecord("[代码气泡工具·停止]\n$friendName 在本轮达到 $maxDraftAttempts 次校验上限后又提交了一份代码；系统没有继续执行，真实气泡没有变化。")
                }
                return finish()
            }

            sendToolResult(evaluation.residentResult)
        }

        addToolRecord("[代码气泡工具·停止]\n本轮工具步骤达到安全上限，已经停止继续调用；现有气泡保持不变。")
        return finish()
    }
}
