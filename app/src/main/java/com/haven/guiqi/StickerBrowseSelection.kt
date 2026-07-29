package com.haven.guiqi

/**
 * 视觉浏览表情包的纯文本协议层。
 *
 * 模型只接触分类、页码和图片编号；真实图片 ID 始终留在 App 内部。
 * 这里负责解析浏览请求，并把一张或多张编号映射回真实图片 ID。
 */
object StickerBrowseSelection {

    data class BrowseRequest(
        val group: String,
        val page: Int
    )

    private val browsePattern = Regex(
        "\\[BROWSE_STICKERS:\\s*([^:\\]]+?)(?::\\s*(\\d+)\\s*)?]",
        RegexOption.IGNORE_CASE
    )
    private val pickPattern = Regex(
        "\\[STICKER_PICK:\\s*([0-9\\s,，、]+)\\s*]",
        RegexOption.IGNORE_CASE
    )
    private val stickerPattern = Regex("\\[STICKER:(.+?)]", RegexOption.IGNORE_CASE)

    fun findBrowseRequest(rawText: String): BrowseRequest? {
        val match = browsePattern.find(rawText) ?: return null
        val group = match.groupValues[1].trim().ifEmpty { StickerStorage.DEFAULT_GROUP }
        val page = match.groupValues.getOrNull(2)
            ?.trim()
            ?.toIntOrNull()
            ?.coerceAtLeast(1)
            ?: 1
        return BrowseRequest(group, page)
    }

    /**
     * 把当前浏览会话里已经展示过的编号映射为真正的 [STICKER:图片ID]。
     * 不限制一次选择几张；模型写几个有效编号，就按原顺序发送几张。
     */
    fun resolve(rawText: String, numberToId: Map<Int, String>): String {
        var text = browsePattern.replace(rawText, "")

        text = pickPattern.replace(text) { match ->
            val ids = parseNumbers(match.groupValues[1])
                .mapNotNull { numberToId[it] }
            ids.joinToString("\n") { "[STICKER:$it]" }
        }

        // 容错：部分模型可能直接写 [STICKER:27]。
        // 纯数字且已经在本次浏览中展示过时，按编号映射；真实 ID 和分组名保持旧语义。
        text = stickerPattern.replace(text) { match ->
            val arg = match.groupValues[1].trim()
            val mappedId = arg.toIntOrNull()?.let { numberToId[it] }
            if (mappedId != null) "[STICKER:$mappedId]" else match.value
        }

        return text.trim().ifEmpty { "……" }
    }

    fun sanitizeUnfinished(rawText: String): String {
        return browsePattern.replace(rawText, "")
            .let { pickPattern.replace(it, "") }
            .trim()
            .ifEmpty { "……" }
    }

    private fun parseNumbers(raw: String): List<Int> {
        return raw
            .replace('，', ',')
            .replace('、', ',')
            .split(',', ' ', '\n', '\t')
            .mapNotNull { it.trim().toIntOrNull() }
    }
}
