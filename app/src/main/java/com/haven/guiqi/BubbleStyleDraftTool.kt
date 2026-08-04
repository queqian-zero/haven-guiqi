package com.haven.guiqi

import java.util.Locale

/**
 * 住户代码气泡的即时工具层。
 *
 * 它只负责识别工具请求、校验草稿和生成同轮回执；真正的 API 循环由聊天页调度。
 * 任何失败都保留原始代码供人类查看，同时绝不修改当前生效气泡。
 */
internal object BubbleStyleDraftTool {

    private val infoPattern = Regex("\\[MY_BUBBLE_STYLE]", RegexOption.IGNORE_CASE)
    private val draftPattern = Regex(
        "\\[BUBBLE_STYLE_DRAFT](.*?)\\[/BUBBLE_STYLE_DRAFT]",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )

    data class Evaluation(
        val css: String,
        val success: Boolean,
        val errors: List<String>,
        val warnings: List<String>,
        val residentResult: String,
        val humanRecord: String
    )

    fun requestsInfo(text: String): Boolean = infoPattern.containsMatchIn(text)

    fun containsDraft(text: String): Boolean = draftPattern.containsMatchIn(text)

    fun firstDraftCss(text: String): String? = draftPattern.find(text)
        ?.groupValues
        ?.getOrNull(1)
        ?.let(SafeBubbleCss::normalizeDraftSource)

    fun stripInfoRequest(text: String): String = infoPattern.replace(text, "").trim()

    fun stripDrafts(text: String): String = draftPattern.replace(text, "").trim()

    fun stripAllToolMarkup(text: String): String {
        val stripped = stripDrafts(stripInfoRequest(text))
        // 兼容住户把整段工具标记包在 Markdown 围栏里的写法；移除工具块后不留下空代码框。
        return stripped.replace(
            Regex("```[A-Za-z0-9_-]*\\s*```", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
            ""
        ).trim()
    }

    fun buildInfoResult(friendId: String, storage: BubbleStyleStorage): String = buildString {
        append("[代码气泡工具结果]\n")
        append(storage.buildResidentCodeStyleInfo(friendId))
        append("\n\n这份档案已经在当前这一轮直接返回给你，也同步告诉用户你查看了规则。")
        append("接下来由你自己决定：提交草稿、继续思考，或者不做了并自然完成回复。")
    }

    fun evaluateDraft(
        text: String,
        friendId: String,
        storage: BubbleStyleStorage,
        attempt: Int
    ): Evaluation? {
        val matches = draftPattern.findAll(text).toList()
        if (matches.isEmpty()) return null

        val rawCss = matches.first().groupValues[1].trim()
        val css = SafeBubbleCss.normalizeDraftSource(rawCss)
        val warnings = mutableListOf<String>()
        if (matches.size > 1) {
            warnings += "同一条回复里出现了 ${matches.size} 份草稿，本次只校验第一份"
        }

        val lowerCss = css.lowercase(Locale.US)
        if (".bubble.user" in lowerCss || ".bubble.me" in lowerCss) {
            warnings += "用户侧选择器不会进入住户草稿，也不会修改用户气泡"
        }

        if (css.isBlank()) {
            val errors = listOf("成对标记中间没有代码")
            return failedEvaluation(css, attempt, errors, warnings)
        }

        val parsed = SafeBubbleCss.compile(css, BubbleStyleStorage.Target.FRIEND)
        warnings += parsed.warnings
        if (parsed.errors.isNotEmpty()) {
            return failedEvaluation(css, attempt, parsed.errors, warnings.distinct())
        }
        if (!parsed.hasEffectiveOverrides) {
            return failedEvaluation(
                css,
                attempt,
                listOf("没有找到任何能作用于住户气泡的有效属性；请使用 .bubble 或 .bubble.ai"),
                warnings.distinct()
            )
        }

        val finalWarnings = warnings.distinct()
        storage.saveResidentCodeDraft(friendId, css, finalWarnings)
        val residentResult = buildString {
            append("[代码气泡工具结果]\n")
            append("第 $attempt 次提交：校验通过。候选草稿已经保存，仍未应用到真实气泡。\n")
            if (finalWarnings.isNotEmpty()) {
                append("提醒：\n")
                finalWarnings.forEach { append("- ").append(it).append('\n') }
            }
            append("这份结果已经同步显示给用户。接下来由你自己决定：")
            append("可以自然地告诉用户已经交稿；也可以再次提交修改版；不想继续时也可以直接说不做了。")
        }.trim()
        val humanRecord = buildHumanRecord(
            success = true,
            attempt = attempt,
            css = css,
            errors = emptyList(),
            warnings = finalWarnings
        )
        return Evaluation(css, true, emptyList(), finalWarnings, residentResult, humanRecord)
    }

    private fun failedEvaluation(
        css: String,
        attempt: Int,
        errors: List<String>,
        warnings: List<String>
    ): Evaluation {
        val finalWarnings = warnings.distinct()
        val residentResult = buildString {
            append("[代码气泡工具结果]\n")
            append("第 $attempt 次提交：校验失败，草稿没有保存，真实气泡没有变化。\n")
            append("错误：\n")
            errors.distinct().forEach { append("- ").append(it).append('\n') }
            if (finalWarnings.isNotEmpty()) {
                append("提醒：\n")
                finalWarnings.forEach { append("- ").append(it).append('\n') }
            }
            append("你刚才提交的原始代码仍显示给你和用户。接下来由你自己决定：")
            append("修改后再次提交，换一种写法，或者不干了并自然完成回复。")
        }.trim()
        val humanRecord = buildHumanRecord(
            success = false,
            attempt = attempt,
            css = css,
            errors = errors.distinct(),
            warnings = finalWarnings
        )
        return Evaluation(css, false, errors.distinct(), finalWarnings, residentResult, humanRecord)
    }

    private fun buildHumanRecord(
        success: Boolean,
        attempt: Int,
        css: String,
        errors: List<String>,
        warnings: List<String>
    ): String = buildString {
        append(if (success) "[代码气泡工具·成功]" else "[代码气泡工具·失败]")
        append("\n第 $attempt 次提交")
        append("\n\n原始代码：\n")
        append(if (css.isBlank()) "（空）" else css)
        append("\n\n校验结果：")
        if (success) {
            append("通过，已保存为待确认草稿；当前真实气泡没有变化。")
        } else {
            append("失败，草稿没有保存；当前真实气泡没有变化。")
            errors.forEach { append("\n- ").append(it) }
        }
        if (warnings.isNotEmpty()) {
            append("\n\n提醒：")
            warnings.forEach { append("\n- ").append(it) }
        }
    }
}
