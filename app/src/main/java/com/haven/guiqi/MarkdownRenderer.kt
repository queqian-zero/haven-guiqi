package com.haven.guiqi

import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan

/**
 * MarkdownRenderer - 把 Markdown 文本渲染成 Android 的富文本
 *
 * 支持的语法：
 * - **加粗**
 * - *斜体*
 * - `行内代码`
 * - ```代码块```
 * - # 标题（一级到三级）
 * - ~~删除线~~
 *
 * 原理：Android 的 TextView 支持一种叫 Spannable 的东西，
 * 可以给文字的某一段加上样式（比如加粗、变色、换字体）。
 * 这个类做的就是找到 Markdown 标记，把它替换成对应的样式。
 */
object MarkdownRenderer {

    /**
     * 把 Markdown 文本渲染成带样式的 SpannableStringBuilder
     * TextView 设置 text 为这个返回值就能显示富文本了
     */
    fun render(input: String): SpannableStringBuilder {
        val builder = SpannableStringBuilder()
        val lines = input.split("\n")

        for (i in lines.indices) {
            val line = lines[i]

            if (i > 0) builder.append("\n")

            // 检查是否是标题行
            when {
                line.startsWith("### ") -> {
                    val content = line.removePrefix("### ")
                    val start = builder.length
                    builder.append(content)
                    builder.setSpan(StyleSpan(Typeface.BOLD), start, builder.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    builder.setSpan(RelativeSizeSpan(1.05f), start, builder.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                line.startsWith("## ") -> {
                    val content = line.removePrefix("## ")
                    val start = builder.length
                    builder.append(content)
                    builder.setSpan(StyleSpan(Typeface.BOLD), start, builder.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    builder.setSpan(RelativeSizeSpan(1.1f), start, builder.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                line.startsWith("# ") -> {
                    val content = line.removePrefix("# ")
                    val start = builder.length
                    builder.append(content)
                    builder.setSpan(StyleSpan(Typeface.BOLD), start, builder.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    builder.setSpan(RelativeSizeSpan(1.15f), start, builder.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                else -> {
                    // 普通行，处理行内标记
                    appendInlineMarkdown(builder, line)
                }
            }
        }

        return builder
    }

    /**
     * 处理一行中的行内 Markdown 标记
     * 按优先级处理：代码块 > 加粗 > 斜体 > 删除线 > 行内代码
     */
    private fun appendInlineMarkdown(builder: SpannableStringBuilder, text: String) {
        // 用正则逐段处理
        var remaining = text
        var pos = 0

        while (remaining.isNotEmpty()) {
            // 找到最近的标记
            val patterns = listOf(
                "```" to "```",      // 行内代码块（三反引号）
                "**" to "**",        // 加粗
                "~~" to "~~",        // 删除线
                "*" to "*",          // 斜体
                "`" to "`"           // 行内代码
            )

            var earliestStart = remaining.length
            var matchedOpen = ""
            var matchedClose = ""

            for ((open, close) in patterns) {
                val openIdx = remaining.indexOf(open)
                if (openIdx >= 0 && openIdx < earliestStart) {
                    // 找到对应的关闭标记
                    val closeIdx = remaining.indexOf(close, openIdx + open.length)
                    if (closeIdx > openIdx) {
                        earliestStart = openIdx
                        matchedOpen = open
                        matchedClose = close
                    }
                }
            }

            if (earliestStart == remaining.length) {
                // 没有更多标记，追加剩余文本
                builder.append(remaining)
                break
            }

            // 追加标记之前的普通文本
            if (earliestStart > 0) {
                builder.append(remaining.substring(0, earliestStart))
            }

            // 提取标记内容
            val closeIdx = remaining.indexOf(matchedClose, earliestStart + matchedOpen.length)
            val content = remaining.substring(earliestStart + matchedOpen.length, closeIdx)
            val start = builder.length

            // 根据标记类型应用样式
            when (matchedOpen) {
                "**" -> {
                    builder.append(content)
                    builder.setSpan(StyleSpan(Typeface.BOLD), start, builder.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                "*" -> {
                    builder.append(content)
                    builder.setSpan(StyleSpan(Typeface.ITALIC), start, builder.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                "`", "```" -> {
                    builder.append(content)
                    // 代码用等宽字体 + 淡色背景
                    builder.setSpan(TypefaceSpan("monospace"), start, builder.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    builder.setSpan(BackgroundColorSpan(0x1AFFFFFF), start, builder.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    builder.setSpan(ForegroundColorSpan(0xCCE0C8FF.toInt()), start, builder.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                "~~" -> {
                    builder.append(content)
                    builder.setSpan(android.text.style.StrikethroughSpan(), start, builder.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }

            // 跳过已处理的部分
            remaining = remaining.substring(closeIdx + matchedClose.length)
        }
    }
}