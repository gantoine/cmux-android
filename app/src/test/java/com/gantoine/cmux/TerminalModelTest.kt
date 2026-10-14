package com.gantoine.cmux

import com.gantoine.cmux.protocol.CellStyle
import com.gantoine.cmux.protocol.RowUpdate
import com.gantoine.cmux.protocol.ScreenFrame
import com.gantoine.cmux.protocol.Span
import com.gantoine.cmux.terminal.TerminalModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TerminalModelTest {
    private val plain = CellStyle(id = 0, fg = "#FFFFFF", bg = "#000000")
    private val red = CellStyle(id = 1, fg = "#FF0000", bg = "#000000", bold = true)

    private fun frame(full: Boolean, surface: String = "s1", vararg rows: Pair<Int, String>, styles: List<CellStyle> = emptyList()) =
        ScreenFrame(
            workspaceId = "w1",
            surfaceId = surface,
            full = full,
            columns = 80,
            rows = 3,
            foreground = "#FFFFFF",
            background = "#000000",
            styles = styles,
            changed = rows.map { (row, text) ->
                RowUpdate(row, if (text.isEmpty()) emptyList() else listOf(Span(0, text.length, 0, text)))
            },
        )

    @Test
    fun partialFramesReplaceOnlyTheirRowsAndClearEmptiedOnes() {
        val first = TerminalModel.apply(null, frame(true, "s1", 0 to "$ ls", 1 to "a.txt", styles = listOf(plain)))!!
        val second = TerminalModel.apply(first, frame(false, "s1", 1 to "", 2 to "$ ", styles = listOf(red)))!!

        assertEquals("$ ls", second.text(0))
        assertEquals("", second.text(1))
        assertEquals("$", second.text(2))
        assertEquals(setOf(0, 1), second.styles.keys)
    }

    @Test
    fun partialFrameWithoutABaseAsksForAResync() {
        assertNull(TerminalModel.apply(null, frame(false, "s1", 0 to "x")))
        val other = TerminalModel.apply(null, frame(true, "s2", 0 to "y"))
        assertNull(TerminalModel.apply(other, frame(false, "s1", 0 to "x")))
    }

    @Test
    fun fullFrameDropsRowsAndStylesFromBefore() {
        val first = TerminalModel.apply(null, frame(true, "s1", 0 to "old", 2 to "old", styles = listOf(plain, red)))!!
        val reset = TerminalModel.apply(first, frame(true, "s1", 1 to "new", styles = listOf(plain)))!!

        assertEquals("", reset.text(0))
        assertEquals("new", reset.text(1))
        assertEquals("", reset.text(2))
        assertEquals(setOf(0), reset.styles.keys)
    }

    @Test
    fun textPadsToSpanColumns() {
        val screen = TerminalModel.apply(
            null,
            ScreenFrame(
                workspaceId = "w", surfaceId = "s", full = true, columns = 20, rows = 1,
                foreground = "#FFFFFF", background = "#000000",
                changed = listOf(RowUpdate(0, listOf(Span(0, 2, 0, "ab"), Span(5, 2, 0, "日")))),
            ),
        )!!
        assertEquals("ab   日", screen.text(0))
    }
}
