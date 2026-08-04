package com.haven.guiqi

import android.graphics.Color
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 归栖安全代码气泡解析器。
 *
 * 它只解析一小组 CSS 风格声明，不执行 HTML、JavaScript、Kotlin、网络请求或文件访问。
 * 所有数值都会被夹在安全范围内，未知选择器和属性只会产生提示，不会影响聊天页面。
 *
 * 这里刻意不用正则表达式解析代码：不同 Android 运行时对复杂正则的兼容性并不完全一致，
 * 手写的小型扫描器更容易限制输入、给出明确错误，也不会因为 PatternSyntaxException 让编辑器失效。
 */
internal object SafeBubbleCss {

    data class InsetsDp(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    )

    data class Overrides(
        val backgroundColor: Int? = null,
        val backgroundOpacityPercent: Int? = null,
        val textColor: Int? = null,
        val borderWidthDp: Int? = null,
        val borderColor: Int? = null,
        val cornerRadiusDp: Int? = null,
        val anchorCornerRadiusDp: Int? = null,
        val padding: InsetsDp? = null,
        val shadowRadiusDp: Int? = null,
        val shadowOpacityPercent: Int? = null,
        val fontSizeSp: Float? = null,
        val fontWeight: Int? = null,
        val lineHeightMultiplier: Float? = null,
        val letterSpacingEm: Float? = null
    )

    data class ParseResult(
        val overrides: Overrides,
        val errors: List<String>,
        val warnings: List<String>
    ) {
        val isUsable: Boolean get() = errors.isEmpty()
        val hasEffectiveOverrides: Boolean get() = overrides != Overrides()
    }

    data class RuntimeStyle(
        val style: BubbleStyleStorage.BubbleStyle,
        val padding: InsetsDp?,
        val fontSizeSp: Float?,
        val fontWeight: Int?,
        val lineHeightMultiplier: Float?,
        val letterSpacingEm: Float?,
        val errors: List<String>,
        val warnings: List<String>
    )

    private data class CacheKey(
        val css: String,
        val target: BubbleStyleStorage.Target
    )

    private data class CssBlock(
        val selector: String,
        val body: String
    )

    private data class ScanResult(
        val text: String,
        val errors: List<String>
    )

    private const val MAX_CODE_LENGTH = 4_000
    private const val MAX_BLOCK_COUNT = 16
    private const val MAX_DECLARATIONS_PER_BLOCK = 32
    private const val DEFAULT_FONT_SIZE_FOR_SPACING_SP = 16f

    private val cache = object : LinkedHashMap<CacheKey, ParseResult>(32, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<CacheKey, ParseResult>?
        ): Boolean = size > 32
    }

    fun resolve(
        sourceStyle: BubbleStyleStorage.BubbleStyle,
        target: BubbleStyleStorage.Target
    ): RuntimeStyle = runCatching {
        resolveUnchecked(sourceStyle, target)
    }.getOrElse { error ->
        // 任何解析器内部异常都只能让代码样式失效，不能带崩编辑器或聊天页。
        RuntimeStyle(
            style = sourceStyle,
            padding = null,
            fontSizeSp = null,
            fontWeight = null,
            lineHeightMultiplier = null,
            letterSpacingEm = null,
            errors = listOf("代码解析器已安全回退：${error.javaClass.simpleName}"),
            warnings = emptyList()
        )
    }

    private fun resolveUnchecked(
        sourceStyle: BubbleStyleStorage.BubbleStyle,
        target: BubbleStyleStorage.Target
    ): RuntimeStyle {
        if (!sourceStyle.fillMode.usesCode || sourceStyle.codeCss.isBlank()) {
            return RuntimeStyle(
                style = sourceStyle,
                padding = null,
                fontSizeSp = null,
                fontWeight = null,
                lineHeightMultiplier = null,
                letterSpacingEm = null,
                errors = emptyList(),
                warnings = emptyList()
            )
        }

        val parsed = compile(sourceStyle.codeCss, target)
        if (!parsed.isUsable) {
            // 代码有硬错误时退回原有安全样式，绝不让气泡消失或撑坏页面。
            return RuntimeStyle(
                style = sourceStyle,
                padding = null,
                fontSizeSp = null,
                fontWeight = null,
                lineHeightMultiplier = null,
                letterSpacingEm = null,
                errors = parsed.errors,
                warnings = parsed.warnings
            )
        }

        val o = parsed.overrides
        val resolvedStyle = sourceStyle.copy(
            backgroundColor = o.backgroundColor ?: sourceStyle.backgroundColor,
            backgroundOpacityPercent = o.backgroundOpacityPercent
                ?: sourceStyle.backgroundOpacityPercent,
            textColor = o.textColor ?: sourceStyle.textColor,
            cornerRadiusDp = o.cornerRadiusDp ?: sourceStyle.cornerRadiusDp,
            anchorCornerRadiusDp = o.anchorCornerRadiusDp
                ?: sourceStyle.anchorCornerRadiusDp,
            borderWidthDp = o.borderWidthDp ?: sourceStyle.borderWidthDp,
            borderColor = o.borderColor ?: sourceStyle.borderColor,
            shadowRadiusDp = o.shadowRadiusDp ?: sourceStyle.shadowRadiusDp,
            shadowOpacityPercent = o.shadowOpacityPercent
                ?: sourceStyle.shadowOpacityPercent
        )
        return RuntimeStyle(
            style = resolvedStyle,
            padding = o.padding,
            fontSizeSp = o.fontSizeSp,
            fontWeight = o.fontWeight,
            lineHeightMultiplier = o.lineHeightMultiplier,
            letterSpacingEm = o.letterSpacingEm,
            errors = parsed.errors,
            warnings = parsed.warnings
        )
    }

    fun compile(css: String, target: BubbleStyleStorage.Target): ParseResult {
        val normalizedInput = normalizeDraftSource(css)
        val key = CacheKey(normalizedInput, target)
        synchronized(cache) { cache[key]?.let { return it } }

        val result = runCatching { compileUncached(normalizedInput, target) }
            .getOrElse { error ->
                ParseResult(
                    overrides = Overrides(),
                    errors = listOf("代码解析失败，已安全回退：${error.javaClass.simpleName}"),
                    warnings = emptyList()
                )
            }
        synchronized(cache) { cache[key] = result }
        return result
    }

    fun defaultTemplate(target: BubbleStyleStorage.Target): String {
        val selector = if (target == BubbleStyleStorage.Target.FRIEND) {
            ".bubble.ai"
        } else {
            ".bubble.user"
        }
        return """.bubble {
  background: #FFF8F1;
  opacity: 92%;
  text-color: #4A382F;
  border: 1dp solid #D8BFAE;
  radius: 18dp;
  near-avatar-radius: 6dp;
  padding: 10dp 13dp;
  shadow: 0dp 3dp 10dp 16%;
  font-size: 14sp;
  font-weight: normal;
  line-height: 1.35;
  letter-spacing: 0.2sp;
}

$selector {
  /* 这里只覆盖这一侧的气泡 */
}
""".trim()
    }

    fun supportedSyntaxText(): String =
        "选择器：.bubble、.bubble.ai、.bubble.user\n" +
            "属性：background、opacity、text-color、border、border-width、border-color、" +
            "radius、near-avatar-radius、padding、shadow、font-size、font-weight、" +
            "line-height、letter-spacing\n" +
            "border 支持 1dp solid #颜色；shadow 支持 12dp 18% 或 0dp 4dp 12dp 18%。\n" +
            "letter-spacing 支持 normal、0、em、sp、dp 或 px；Markdown 的 ```css 代码围栏会自动剥离。\n" +
            "代码不会执行脚本、联网、读取文件或控制聊天页其他组件。"

    /**
     * 住户常会把样式包进 Markdown ```css 围栏。围栏只是展示格式，不应成为 CSS 内容。
     * 这里只剥离最外层完整围栏，不会执行或解释围栏之外的任何代码。
     */
    fun normalizeDraftSource(raw: String): String {
        var text = raw.trim().removePrefix("\uFEFF").trim()
        if (!text.startsWith("```")) return text

        val firstLineEnd = text.indexOf('\n')
        if (firstLineEnd < 0) return text
        val closing = text.lastIndexOf("```")
        if (closing <= firstLineEnd) return text
        val trailing = text.substring(closing + 3).trim()
        if (trailing.isNotEmpty()) return text
        text = text.substring(firstLineEnd + 1, closing).trim()
        return text
    }

    private fun compileUncached(
        rawCss: String,
        target: BubbleStyleStorage.Target
    ): ParseResult {
        if (rawCss.isBlank()) return ParseResult(Overrides(), emptyList(), emptyList())
        if (rawCss.length > MAX_CODE_LENGTH) {
            return ParseResult(
                Overrides(),
                listOf("代码超过 ${MAX_CODE_LENGTH} 个字符，已拒绝应用"),
                emptyList()
            )
        }

        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        val commentScan = stripComments(rawCss)
        errors += commentScan.errors
        val blockScan = scanBlocks(commentScan.text)
        errors += blockScan.errors
        val blocks = blockScan.blocks

        if (blocks.isEmpty() && errors.isEmpty()) {
            errors += "没有找到形如 .bubble { 属性: 值; } 的代码块"
        }
        if (blocks.size > MAX_BLOCK_COUNT) {
            errors += "代码块超过 $MAX_BLOCK_COUNT 个，已拒绝应用"
        }

        var overrides = Overrides()
        blocks.take(MAX_BLOCK_COUNT).forEachIndexed { blockIndex, block ->
            val selectors = block.selector.split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (selectors.isEmpty()) {
                errors += "第 ${blockIndex + 1} 个代码块缺少选择器"
                return@forEachIndexed
            }

            val applies = selectors.any { selectorApplies(it, target, warnings) }
            if (!applies) return@forEachIndexed

            val declarations = splitDeclarations(block.body)
            if (declarations.size > MAX_DECLARATIONS_PER_BLOCK) {
                errors += "第 ${blockIndex + 1} 个代码块属性过多"
                return@forEachIndexed
            }

            declarations.forEach { declaration ->
                val separator = declaration.indexOf(':')
                if (separator <= 0 || separator == declaration.lastIndex) {
                    errors += "无法识别：$declaration"
                    return@forEach
                }
                val property = declaration.substring(0, separator)
                    .trim()
                    .lowercase(Locale.US)
                val value = declaration.substring(separator + 1).trim()
                overrides = applyDeclaration(overrides, property, value, errors, warnings)
            }
        }

        return ParseResult(overrides, errors.distinct(), warnings.distinct())
    }

    private data class BlockScanResult(
        val blocks: List<CssBlock>,
        val errors: List<String>
    )

    /** 去掉 C 风格注释；不使用正则，避免 Android 运行时兼容问题。 */
    private fun stripComments(input: String): ScanResult {
        val out = StringBuilder(input.length)
        val errors = mutableListOf<String>()
        var i = 0
        while (i < input.length) {
            if (i + 1 < input.length && input[i] == '/' && input[i + 1] == '*') {
                val start = i
                i += 2
                var closed = false
                while (i + 1 < input.length) {
                    if (input[i] == '*' && input[i + 1] == '/') {
                        i += 2
                        closed = true
                        break
                    }
                    // 保留换行，避免注释前后两条声明粘到一起。
                    if (input[i] == '\n') out.append('\n')
                    i++
                }
                if (!closed) {
                    errors += "从第 ${lineNumberAt(input, start)} 行开始的注释没有结束"
                    break
                }
            } else {
                out.append(input[i])
                i++
            }
        }
        return ScanResult(out.toString(), errors)
    }

    /**
     * 只允许一层 selector { declarations }，不支持嵌套规则。
     * 这足够覆盖气泡样式，也能让错误位置更可控。
     */
    private fun scanBlocks(input: String): BlockScanResult {
        val blocks = mutableListOf<CssBlock>()
        val errors = mutableListOf<String>()
        var cursor = 0

        while (cursor < input.length) {
            while (cursor < input.length && input[cursor].isWhitespace()) cursor++
            if (cursor >= input.length) break

            if (input[cursor] == '}') {
                errors += "第 ${lineNumberAt(input, cursor)} 行出现了多余的 }"
                cursor++
                continue
            }

            val open = input.indexOf('{', cursor)
            if (open < 0) {
                val tail = input.substring(cursor).trim()
                if (tail.isNotEmpty()) errors += "花括号外还有无法识别的内容：${tail.take(40)}"
                break
            }

            val strayClose = input.indexOf('}', cursor)
            if (strayClose in cursor until open) {
                errors += "第 ${lineNumberAt(input, strayClose)} 行出现了多余的 }"
                cursor = strayClose + 1
                continue
            }

            val selector = input.substring(cursor, open).trim()
            if (selector.isEmpty()) {
                errors += "第 ${lineNumberAt(input, open)} 行的代码块缺少选择器"
            }

            val close = input.indexOf('}', open + 1)
            if (close < 0) {
                errors += "第 ${lineNumberAt(input, open)} 行开始的代码块缺少 }"
                break
            }

            val nestedOpen = input.indexOf('{', open + 1)
            if (nestedOpen in (open + 1) until close) {
                errors += "代码气泡不支持嵌套花括号"
                cursor = close + 1
                continue
            }

            blocks += CssBlock(
                selector = selector,
                body = input.substring(open + 1, close)
            )
            cursor = close + 1
        }

        return BlockScanResult(blocks, errors)
    }

    private fun splitDeclarations(body: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()

        fun flush() {
            val declaration = current.toString().trim()
            if (declaration.isNotEmpty()) result += declaration
            current.setLength(0)
        }

        body.forEach { ch ->
            when (ch) {
                ';' -> flush()
                '\n', '\r' -> {
                    // 为了照顾手输代码，行末即使漏了分号，也仍按一条声明处理。
                    if (current.indexOf(":") >= 0) flush() else if (current.isNotEmpty()) current.append(' ')
                }
                else -> current.append(ch)
            }
        }
        flush()
        return result
    }

    private fun lineNumberAt(text: String, index: Int): Int {
        var line = 1
        var i = 0
        val end = index.coerceAtMost(text.length)
        while (i < end) {
            if (text[i] == '\n') line++
            i++
        }
        return line
    }

    private fun selectorApplies(
        rawSelector: String,
        target: BubbleStyleStorage.Target,
        warnings: MutableList<String>
    ): Boolean {
        val selector = rawSelector.lowercase(Locale.US).filterNot { it.isWhitespace() }
        return when (selector) {
            ".bubble" -> true
            ".bubble.ai", ".bubble.friend" -> target == BubbleStyleStorage.Target.FRIEND
            ".bubble.user", ".bubble.me" -> target == BubbleStyleStorage.Target.USER
            else -> {
                warnings += "已忽略暂不支持的选择器：$rawSelector"
                false
            }
        }
    }

    private fun applyDeclaration(
        current: Overrides,
        property: String,
        rawValue: String,
        errors: MutableList<String>,
        warnings: MutableList<String>
    ): Overrides {
        fun bad(message: String): Overrides {
            errors += "$property：$message"
            return current
        }

        return when (property) {
            "background", "background-color" -> {
                if (rawValue.equals("none", true) || rawValue.equals("transparent", true)) {
                    current.copy(backgroundColor = Color.TRANSPARENT, backgroundOpacityPercent = 0)
                } else {
                    parseColor(rawValue)?.let { current.copy(backgroundColor = it) }
                        ?: bad("颜色格式应为 #RGB、#RRGGBB 或 #AARRGGBB")
                }
            }

            "opacity", "background-opacity" -> {
                parsePercent(rawValue, 0, 100)?.let {
                    current.copy(backgroundOpacityPercent = it)
                } ?: bad("请输入 0%～100%")
            }

            "text-color", "color" -> {
                parseColor(rawValue)?.let { current.copy(textColor = it) }
                    ?: bad("颜色格式应为 #RGB、#RRGGBB 或 #AARRGGBB")
            }

            "border" -> parseBorder(current, rawValue, errors)

            "border-width" -> {
                parseDp(rawValue, 0, BubbleStyleStorage.MAX_BORDER_WIDTH_DP)?.let {
                    current.copy(borderWidthDp = it)
                } ?: bad("请输入 0～${BubbleStyleStorage.MAX_BORDER_WIDTH_DP}dp")
            }

            "border-color" -> {
                parseColor(rawValue)?.let { current.copy(borderColor = it) }
                    ?: bad("边框颜色无法识别")
            }

            "radius", "border-radius" -> {
                parseDp(rawValue, 0, BubbleStyleStorage.MAX_CORNER_RADIUS_DP)?.let {
                    current.copy(cornerRadiusDp = it)
                } ?: bad("请输入 0～${BubbleStyleStorage.MAX_CORNER_RADIUS_DP}dp")
            }

            "near-avatar-radius", "anchor-radius" -> {
                parseDp(rawValue, 0, BubbleStyleStorage.MAX_CORNER_RADIUS_DP)?.let {
                    current.copy(anchorCornerRadiusDp = it)
                } ?: bad("请输入 0～${BubbleStyleStorage.MAX_CORNER_RADIUS_DP}dp")
            }

            "padding" -> {
                parseInsets(rawValue)?.let { current.copy(padding = it) }
                    ?: bad("请输入 1～4 个 0～48dp 数值")
            }

            "shadow" -> parseShadow(current, rawValue, errors, warnings)

            "font-size" -> {
                parseUnitFloat(rawValue, "sp", 10f, 24f)?.let {
                    current.copy(fontSizeSp = it)
                } ?: bad("请输入 10sp～24sp")
            }

            "font-weight" -> {
                val weight = when (rawValue.lowercase(Locale.US)) {
                    "normal" -> 400
                    "bold" -> 700
                    else -> rawValue.toIntOrNull()?.coerceIn(100, 900)
                }
                weight?.let { current.copy(fontWeight = it) }
                    ?: bad("请输入 normal、bold 或 100～900")
            }

            "line-height" -> {
                rawValue.lowercase(Locale.US).removeSuffix("x").trim().toFloatOrNull()
                    ?.coerceIn(1f, 1.8f)
                    ?.let { current.copy(lineHeightMultiplier = it) }
                    ?: bad("请输入 1.0～1.8")
            }

            "letter-spacing" -> {
                parseLetterSpacing(rawValue, current.fontSizeSp)?.let {
                    current.copy(letterSpacingEm = it)
                } ?: bad("请输入 normal、0、-0.12em～0.5em，或 -2sp～8sp（dp/px 同样按字号换算）")
            }

            "background-image", "animation", "position", "z-index", "transform" -> {
                warnings += "出于安全和性能限制，已忽略属性：$property"
                current
            }

            else -> {
                warnings += "已忽略未知属性：$property"
                current
            }
        }
    }

    private fun parseBorder(
        current: Overrides,
        rawValue: String,
        errors: MutableList<String>
    ): Overrides {
        if (rawValue.equals("none", true)) {
            return current.copy(borderWidthDp = 0)
        }

        val parts = splitWhitespace(rawValue)
        val widthPart: String
        val colorPart: String
        when (parts.size) {
            2 -> {
                widthPart = parts[0]
                colorPart = parts[1]
            }
            3 -> {
                widthPart = parts[0]
                if (!parts[1].equals("solid", true)) {
                    errors += "border：目前只支持 solid 实线边框"
                    return current
                }
                colorPart = parts[2]
            }
            else -> {
                errors += "border：格式应为 1dp solid #D8BFAE、1dp #D8BFAE，或 none"
                return current
            }
        }

        val width = parseDp(widthPart, 0, BubbleStyleStorage.MAX_BORDER_WIDTH_DP)
        if (width == null) {
            errors += "border：边框粗细应为 0～${BubbleStyleStorage.MAX_BORDER_WIDTH_DP}dp"
            return current
        }
        val color = parseColor(colorPart)
        if (color == null) {
            errors += "border：边框颜色无法识别"
            return current
        }
        return current.copy(borderWidthDp = width, borderColor = color)
    }

    private fun parseShadow(
        current: Overrides,
        rawValue: String,
        errors: MutableList<String>,
        warnings: MutableList<String>
    ): Overrides {
        if (rawValue.equals("none", true)) {
            return current.copy(shadowRadiusDp = 0, shadowOpacityPercent = 0)
        }

        val parts = splitWhitespace(rawValue)
        val radiusPart: String
        val opacityPart: String
        when (parts.size) {
            2 -> {
                radiusPart = parts[0]
                opacityPart = parts[1]
            }
            4 -> {
                // Android 原生气泡只保存扩散半径与浓度；接受 CSS 式 x/y/blur/opacity，
                // 其中 x/y 只用于兼容书写，不参与布局，避免气泡阴影导致列表抖动。
                if (parseSignedDp(parts[0], -24f, 24f) == null ||
                    parseSignedDp(parts[1], -24f, 24f) == null
                ) {
                    errors += "shadow：前两个偏移量应为 -24dp～24dp"
                    return current
                }
                radiusPart = parts[2]
                opacityPart = parts[3]
                if (parts[0] != "0dp" || parts[1] != "0dp") {
                    warnings += "shadow 的水平/垂直偏移仅作兼容读取；归栖当前使用安全的居中阴影"
                }
            }
            else -> {
                errors += "shadow：格式应为 12dp 18%、0dp 4dp 12dp 18%，或 none"
                return current
            }
        }

        val radius = parseDp(radiusPart, 0, BubbleStyleStorage.MAX_SHADOW_RADIUS_DP)
        if (radius == null) {
            errors += "shadow：阴影扩散应为 0～${BubbleStyleStorage.MAX_SHADOW_RADIUS_DP}dp"
            return current
        }
        val opacity = parsePercent(
            opacityPart,
            0,
            BubbleStyleStorage.MAX_SHADOW_OPACITY_PERCENT
        )
        if (opacity == null) {
            errors += "shadow：阴影浓度应为 0%～${BubbleStyleStorage.MAX_SHADOW_OPACITY_PERCENT}%"
            return current
        }
        return current.copy(shadowRadiusDp = radius, shadowOpacityPercent = opacity)
    }

    private fun parseColor(raw: String): Int? {
        val value = raw.trim()
        if (value.equals("transparent", true)) return Color.TRANSPARENT
        if (!value.startsWith('#')) return null
        val hex = value.substring(1)
        if (hex.any { !it.isDigit() && it.lowercaseChar() !in 'a'..'f' }) return null
        val expanded = when (hex.length) {
            3 -> hex.map { "$it$it" }.joinToString("")
            4 -> hex.map { "$it$it" }.joinToString("")
            6, 8 -> hex
            else -> return null
        }
        return runCatching { Color.parseColor("#$expanded") }.getOrNull()
    }

    private fun parseDp(raw: String, min: Int, max: Int): Int? =
        parseUnitFloat(raw, "dp", min.toFloat(), max.toFloat())?.roundToInt()

    private fun parseSignedDp(raw: String, min: Float, max: Float): Float? =
        parseUnitFloat(raw, "dp", min, max)

    private fun parseUnitFloat(raw: String, unit: String, min: Float, max: Float): Float? {
        val value = raw.trim().lowercase(Locale.US)
        if (!value.endsWith(unit)) return null
        return value.removeSuffix(unit).trim().toFloatOrNull()?.coerceIn(min, max)
    }

    private fun parsePercent(raw: String, min: Int, max: Int): Int? {
        val valueText = raw.trim()
        if (!valueText.endsWith('%')) return null
        val value = valueText.dropLast(1).trim().toFloatOrNull() ?: return null
        return value.roundToInt().coerceIn(min, max)
    }

    private fun parseLetterSpacing(raw: String, activeFontSizeSp: Float?): Float? {
        val value = raw.trim().lowercase(Locale.US)
        if (value == "normal" || value == "0" || value == "0.0") return 0f

        fun absoluteUnitToEm(numberText: String): Float? {
            val spacing = numberText.trim().toFloatOrNull() ?: return null
            if (spacing !in -2f..8f) return null
            val fontSize = (activeFontSizeSp ?: DEFAULT_FONT_SIZE_FOR_SPACING_SP)
                .coerceAtLeast(1f)
            return (spacing / fontSize).coerceIn(-0.12f, 0.5f)
        }

        return when {
            value.endsWith("em") -> value.removeSuffix("em").trim().toFloatOrNull()
                ?.takeIf { it in -0.12f..0.5f }
            value.endsWith("sp") -> absoluteUnitToEm(value.removeSuffix("sp"))
            value.endsWith("dp") -> absoluteUnitToEm(value.removeSuffix("dp"))
            value.endsWith("px") -> absoluteUnitToEm(value.removeSuffix("px"))
            // 兼容模型偶尔写出的无单位小数；按 Android TextView 的 em 比例理解。
            else -> value.toFloatOrNull()?.takeIf { it in -0.12f..0.5f }
        }
    }

    private fun parseInsets(raw: String): InsetsDp? {
        val values = splitWhitespace(raw)
            .map { parseDp(it, 0, BubbleStyleStorage.MAX_IMAGE_PADDING_DP) ?: return null }
        return when (values.size) {
            1 -> InsetsDp(values[0], values[0], values[0], values[0])
            2 -> InsetsDp(values[1], values[0], values[1], values[0])
            3 -> InsetsDp(values[1], values[0], values[1], values[2])
            4 -> InsetsDp(values[3], values[0], values[1], values[2])
            else -> null
        }
    }

    private fun splitWhitespace(raw: String): List<String> =
        raw.trim()
            .split(' ', '\t', '\r', '\n')
            .filter { it.isNotBlank() }
}
