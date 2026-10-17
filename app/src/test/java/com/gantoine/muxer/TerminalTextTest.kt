package com.gantoine.muxer

import com.gantoine.muxer.protocol.Span
import com.gantoine.muxer.terminal.CellPosition
import com.gantoine.muxer.terminal.Selection
import com.gantoine.muxer.terminal.StickyModifiers
import com.gantoine.muxer.terminal.TerminalContent
import com.gantoine.muxer.terminal.TerminalHistory
import com.gantoine.muxer.terminal.TerminalKeys
import com.gantoine.muxer.terminal.TerminalScreen
import com.gantoine.muxer.terminal.TerminalText
import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalTextTest {
    private fun line(vararg spans: Pair<Int, String>) = spans.map { (column, text) -> Span(column, text.length, 0, text) }

    private val screen = TerminalScreen(
        surfaceId = "s",
        columns = 20,
        rows = 2,
        foreground = "#FFFFFF",
        background = "#000000",
        cursor = null,
        styles = emptyMap(),
        lines = listOf(
            line(0 to "$ git status"),
            listOf(Span(0, 3, 0, "ok "), Span(3, 4, 0, "日本"), Span(7, 4, 0, "done")),
        ),
    )
    private val history = TerminalHistory(emptyMap(), listOf(line(0 to "old  output here"), emptyList()))
    private val content = TerminalContent(screen, history)

    @Test
    fun screenTextReadsTheVisibleRowsWithoutHistory() {
        val history = TerminalHistory(emptyMap(), listOf(line(0 to "older output")))
        assertEquals("\$ git status\nok 日本done", TerminalContent(screen, history).screenText.lines().joinToString("\n") { it.trimEnd() })
    }

    @Test
    fun copiesAcrossHistoryAndScreenWithBlankLinesAndWideCharacters() {
        val selection = Selection(anchor = CellPosition(3, 10), focus = CellPosition(0, 5))
        assertEquals("output here\n\n$ git status\nok 日本done", content.text(selection))
    }

    @Test
    fun copyStopsAtTheSelectedColumns() {
        assertEquals("git", content.text(Selection(CellPosition(2, 2), CellPosition(2, 4))))
        assertEquals("日", content.text(Selection(CellPosition(3, 3), CellPosition(3, 4))))
    }

    @Test
    fun longPressSelectsTheWholeWordIncludingWideCharacters() {
        assertEquals(Selection(CellPosition(2, 2), CellPosition(2, 4)), content.wordAt(CellPosition(2, 3)))
        assertEquals(Selection(CellPosition(3, 0), CellPosition(3, 1)), content.wordAt(CellPosition(3, 1)))
        assertEquals(Selection(CellPosition(1, 5), CellPosition(1, 5)), content.wordAt(CellPosition(1, 5)))
    }

    @Test
    fun keyboardCorrectionsBecomeExactBackspacesAndAdditions() {
        assertEquals(TerminalKeys.Edit(backspaces = 0, added = "lo"), TerminalKeys.diff("hel", "hello"))
        assertEquals(TerminalKeys.Edit(backspaces = 2, added = "he"), TerminalKeys.diff("teh", "the"))
        assertEquals(TerminalKeys.Edit(backspaces = 2, added = ""), TerminalKeys.diff("ls -", "ls"))
    }

    @Test
    fun stickyModifiersTurnTypingIntoShortcuts() {
        val none = StickyModifiers()
        assertEquals(TerminalKeys.Input.Text("r"), TerminalKeys.withModifiers("r", none))
        assertEquals(TerminalKeys.Input.Key("ctrl+r"), TerminalKeys.withModifiers("R", StickyModifiers(ctrl = true)))
        assertEquals(TerminalKeys.Input.Key("ctrl+alt+x"), TerminalKeys.withModifiers("x", StickyModifiers(ctrl = true, alt = true)))
        assertEquals(TerminalKeys.Input.Text("A"), TerminalKeys.withModifiers("a", StickyModifiers(shift = true)))
        assertEquals(TerminalKeys.Input.Text("hello"), TerminalKeys.withModifiers("hello", StickyModifiers(ctrl = true)))

        assertEquals(TerminalKeys.Input.Key("shift+tab"), TerminalKeys.keyWithModifiers("tab", StickyModifiers(shift = true)))
        assertEquals(TerminalKeys.Input.Key("alt+left"), TerminalKeys.keyWithModifiers("left", StickyModifiers(alt = true)))
        assertEquals(TerminalKeys.Input.Key("ctrl-c"), TerminalKeys.keyWithModifiers("ctrl-c", StickyModifiers(alt = true)))
    }

    @Test
    fun newlinesAndTabsFromTheKeyboardAreSentAsKeys() {
        assertEquals(
            listOf(
                TerminalKeys.Input.Text("ls"),
                TerminalKeys.Input.Key("enter"),
                TerminalKeys.Input.Key("tab"),
                TerminalKeys.Input.Text("x"),
            ),
            TerminalKeys.splitText("ls\n\tx"),
        )
    }

    @Test
    fun symbolsWithAnEmojiFormDrawAsText() {
        assertEquals("\u25CF The workspace", TerminalText.asText("\u23FA The workspace"))
        assertEquals("\u25B8\u25B8 auto mode", TerminalText.asText("\u23F5\u23F5 auto mode"))
        assertEquals("\u2192\uFE0E next", TerminalText.asText("\u2192 next"))
        assertEquals("\u2764\uFE0F", TerminalText.asText("\u2764\uFE0F"))
        assertEquals("plain text", TerminalText.asText("plain text"))
    }
}
