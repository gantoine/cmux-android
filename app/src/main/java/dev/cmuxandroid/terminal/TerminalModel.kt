package dev.cmuxandroid.terminal

import dev.cmuxandroid.protocol.CellStyle
import dev.cmuxandroid.protocol.CursorInfo
import dev.cmuxandroid.protocol.ScreenFrame
import dev.cmuxandroid.protocol.Span

data class TerminalScreen(
    val surfaceId: String,
    val columns: Int,
    val rows: Int,
    val foreground: String,
    val background: String,
    val cursor: CursorInfo?,
    val styles: Map<Int, CellStyle>,
    val lines: List<List<Span>>,
    val altScreen: Boolean = false,
) {
    val lastUsedRow: Int by lazy {
        val lastText = lines.indexOfLast { line -> line.any { it.text.isNotBlank() } }
        maxOf(lastText, cursor?.row ?: 0)
    }

    fun text(row: Int): String {
        val builder = StringBuilder()
        for (span in lines.getOrNull(row).orEmpty()) {
            while (builder.length < span.column) builder.append(' ')
            builder.append(span.text)
        }
        return builder.toString().trimEnd()
    }
}

object TerminalModel {
    fun apply(previous: TerminalScreen?, frame: ScreenFrame): TerminalScreen? {
        val sameTerminal = previous != null && previous.surfaceId == frame.surfaceId
        if (!frame.full && !sameTerminal) return null

        val base = if (frame.full || previous == null) {
            TerminalScreen(
                surfaceId = frame.surfaceId,
                columns = frame.columns,
                rows = frame.rows,
                foreground = frame.foreground,
                background = frame.background,
                cursor = frame.cursor,
                styles = frame.styles.associateBy { it.id },
                lines = List(frame.rows) { emptyList() },
            )
        } else {
            previous.copy(styles = previous.styles + frame.styles.associateBy { it.id })
        }

        val lines = base.lines.toMutableList()
        for (update in frame.changed) {
            if (update.row in lines.indices) lines[update.row] = update.spans
        }
        return base.copy(lines = lines, cursor = frame.cursor ?: base.cursor, altScreen = frame.altScreen)
    }
}

data class TerminalHistory(val styles: Map<Int, CellStyle>, val lines: List<List<Span>>)
