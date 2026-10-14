package dev.cmuxandroid.chat

sealed interface MarkdownBlock {
    data class Paragraph(val spans: List<InlineSpan>) : MarkdownBlock
    data class Heading(val spans: List<InlineSpan>) : MarkdownBlock
    data class Code(val code: String) : MarkdownBlock
}

data class InlineSpan(val text: String, val bold: Boolean = false, val code: Boolean = false)

object ChatMarkdown {
    private val fence = Regex("^\\s*```")
    private val heading = Regex("^#{1,6}\\s+(.*)$")
    private val bullet = Regex("^(\\s*)[-*+]\\s+")
    private val inlineToken = Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`|\\[([^\\]]+)]\\([^)]+\\)")

    fun blocks(text: String): List<MarkdownBlock> {
        val blocks = mutableListOf<MarkdownBlock>()
        val paragraph = mutableListOf<String>()
        var code: MutableList<String>? = null

        fun flushParagraph() {
            if (paragraph.isNotEmpty()) blocks += MarkdownBlock.Paragraph(inline(paragraph.joinToString("\n")))
            paragraph.clear()
        }

        for (line in text.lines()) {
            val openCode = code
            when {
                openCode != null && fence.containsMatchIn(line) -> {
                    blocks += MarkdownBlock.Code(openCode.joinToString("\n"))
                    code = null
                }
                openCode != null -> openCode += line
                fence.containsMatchIn(line) -> {
                    flushParagraph()
                    code = mutableListOf()
                }
                line.isBlank() -> flushParagraph()
                else -> {
                    val title = heading.matchEntire(line.trim())
                    if (title != null) {
                        flushParagraph()
                        blocks += MarkdownBlock.Heading(inline(title.groupValues[1]))
                    } else {
                        paragraph += line.replace(bullet) { "${it.groupValues[1]}• " }
                    }
                }
            }
        }
        code?.let { blocks += MarkdownBlock.Code(it.joinToString("\n")) }
        flushParagraph()
        return blocks
    }

    fun inline(text: String): List<InlineSpan> {
        val spans = mutableListOf<InlineSpan>()
        var position = 0
        for (match in inlineToken.findAll(text)) {
            if (match.range.first > position) spans += InlineSpan(text.substring(position, match.range.first))
            val (bold, code, link) = match.destructured
            spans += when {
                bold.isNotEmpty() -> InlineSpan(bold, bold = true)
                code.isNotEmpty() -> InlineSpan(code, code = true)
                else -> InlineSpan(link)
            }
            position = match.range.last + 1
        }
        if (position < text.length) spans += InlineSpan(text.substring(position))
        return spans
    }
}
