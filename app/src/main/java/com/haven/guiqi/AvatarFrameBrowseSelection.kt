package com.haven.guiqi

/**
 * 住户视觉浏览画匣头像框的纯文本协议层。
 * 模型只接触页码和临时编号，真实画匣 ID 始终留在 App 内部。
 */
object AvatarFrameBrowseSelection {

    data class BrowseRequest(val album: String, val page: Int)

    private val browsePattern = Regex(
        "\\[BROWSE_AVATAR_FRAMES(?:\\s*:\\s*([^:\\]]+?))?(?::\\s*(\\d+)\\s*)?]",
        RegexOption.IGNORE_CASE
    )
    private val pickPattern = Regex(
        "\\[AVATAR_FRAME_PICK:\\s*(\\d+)\\s*]",
        RegexOption.IGNORE_CASE
    )

    fun findBrowseRequest(rawText: String): BrowseRequest? {
        val match = browsePattern.find(rawText) ?: return null
        val album = match.groupValues.getOrNull(1)?.trim().orEmpty().ifEmpty { "全部" }
        val page = match.groupValues.getOrNull(2)
            ?.trim()
            ?.toIntOrNull()
            ?.coerceAtLeast(1)
            ?: 1
        return BrowseRequest(album, page)
    }

    fun resolve(rawText: String, numberToId: Map<Int, String>): String {
        var text = browsePattern.replace(rawText, "")
        text = pickPattern.replace(text) { match ->
            val number = match.groupValues[1].toIntOrNull()
            val itemId = number?.let(numberToId::get)
            if (itemId == null) "" else "[GALLERY_AVATAR_FRAME:$itemId]"
        }
        return text.trim().ifEmpty { "……" }
    }
}
