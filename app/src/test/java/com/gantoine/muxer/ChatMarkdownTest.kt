package com.gantoine.muxer

import com.gantoine.muxer.chat.ChatMarkdown
import com.gantoine.muxer.chat.InlineSpan
import com.gantoine.muxer.chat.MarkdownBlock
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatMarkdownTest {
    @Test
    fun splitsParagraphsHeadingsAndCode() {
        val blocks = ChatMarkdown.blocks("## Plan\n\nFirst line\n- item one\n\n```kotlin\nval x = 1\n```\nAfter")
        assertEquals(
            listOf(
                MarkdownBlock.Heading(listOf(InlineSpan("Plan"))),
                MarkdownBlock.Paragraph(listOf(InlineSpan("First line\n• item one"))),
                MarkdownBlock.Code("val x = 1"),
                MarkdownBlock.Paragraph(listOf(InlineSpan("After"))),
            ),
            blocks,
        )
    }

    @Test
    fun unclosedCodeStillShows() {
        assertEquals(listOf(MarkdownBlock.Code("partial")), ChatMarkdown.blocks("```\npartial"))
    }

    @Test
    fun inlineBoldCodeAndLinks() {
        assertEquals(
            listOf(
                InlineSpan("Run "),
                InlineSpan("go test", code = true),
                InlineSpan(", it is "),
                InlineSpan("fast", bold = true),
                InlineSpan(". See "),
                InlineSpan("docs"),
            ),
            ChatMarkdown.inline("Run `go test`, it is **fast**. See [docs](https://x.y)"),
        )
    }
}
