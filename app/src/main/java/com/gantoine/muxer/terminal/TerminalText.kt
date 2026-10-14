package com.gantoine.muxer.terminal

import com.gantoine.muxer.protocol.CellStyle
import com.gantoine.muxer.protocol.Span

data class CellPosition(val row: Int, val column: Int) : Comparable<CellPosition> {
    override fun compareTo(other: CellPosition): Int =
        if (row != other.row) row.compareTo(other.row) else column.compareTo(other.column)
}

data class Selection(val anchor: CellPosition, val focus: CellPosition) {
    val start: CellPosition get() = minOf(anchor, focus)
    val end: CellPosition get() = maxOf(anchor, focus)

    fun columnsOn(row: Int, columns: Int): IntRange? {
        if (row < start.row || row > end.row) return null
        val from = if (row == start.row) start.column else 0
        val to = if (row == end.row) end.column else columns - 1
        return from..to
    }
}

class TerminalContent(val screen: TerminalScreen, history: TerminalHistory?) {
    private val historyLines = history?.lines.orEmpty()
    private val historyStyles = history?.styles.orEmpty()

    val historyRows: Int = historyLines.size
    val rowCount: Int = historyRows + screen.rows
    val columns: Int = screen.columns

    fun spans(row: Int): List<Span> =
        if (row < historyRows) historyLines.getOrElse(row) { emptyList() } else screen.lines.getOrElse(row - historyRows) { emptyList() }

    fun styles(row: Int): Map<Int, CellStyle> = if (row < historyRows) historyStyles else screen.styles

    fun cells(row: Int): Array<String?> = TerminalText.cells(spans(row), columns)

    fun text(selection: Selection): String {
        val start = selection.start
        val end = selection.end
        return (start.row..end.row).joinToString("\n") { row ->
            val range = selection.columnsOn(row, columns) ?: IntRange.EMPTY
            TerminalText.textIn(cells(row), range)
        }.trimEnd('\n')
    }

    fun wordAt(position: CellPosition): Selection {
        val cells = cells(position.row)
        val (from, to) = TerminalText.wordBounds(cells, position.column)
        return Selection(CellPosition(position.row, from), CellPosition(position.row, to))
    }
}

object TerminalText {
    const val CONTINUATION = ""
    private const val TEXT_STYLE = '\uFE0E'
    private const val EMOJI_STYLE = '\uFE0F'

    private val lookAlikes = mapOf(
        '\u23FA' to '\u25CF',
        '\u23F5' to '\u25B8',
        '\u23F4' to '\u25C2',
        '\u23F9' to '\u25A0',
        '\u23F8' to '\u2016',
    )

    fun asText(text: String): String {
        if (text.none(::hasEmojiForm)) return text
        val builder = StringBuilder(text.length + 4)
        text.forEachIndexed { index, char ->
            val lookAlike = lookAlikes[char]
            if (lookAlike != null) {
                builder.append(lookAlike)
                return@forEachIndexed
            }
            builder.append(char)
            val next = text.getOrNull(index + 1)
            if (hasEmojiForm(char) && next != TEXT_STYLE && next != EMOJI_STYLE) builder.append(TEXT_STYLE)
        }
        return builder.toString()
    }

    private fun hasEmojiForm(char: Char): Boolean =
        char in '\u2190'..'\u21FF' || char in '\u2300'..'\u23FF' || char in '\u25A0'..'\u27BF' || char in '\u2B00'..'\u2BFF'

    fun cells(spans: List<Span>, columns: Int): Array<String?> {
        val cells = arrayOfNulls<String>(columns)
        for (span in spans) {
            val characters = codePoints(span.text)
            if (characters.isEmpty()) continue
            val cellsPerCharacter = if (span.width % characters.size == 0) span.width / characters.size else 0
            if (cellsPerCharacter == 0) {
                put(cells, span.column, span.text, span.width)
                continue
            }
            characters.forEachIndexed { index, character ->
                put(cells, span.column + index * cellsPerCharacter, character, cellsPerCharacter)
            }
        }
        return cells
    }

    fun textIn(cells: Array<String?>, range: IntRange): String {
        val builder = StringBuilder()
        for (column in range) {
            if (column !in cells.indices) continue
            when (val cell = cells[column]) {
                null -> builder.append(' ')
                CONTINUATION -> Unit
                else -> builder.append(cell)
            }
        }
        return builder.toString().trimEnd()
    }

    fun wordBounds(cells: Array<String?>, column: Int): Pair<Int, Int> {
        val clamped = column.coerceIn(0, (cells.size - 1).coerceAtLeast(0))
        if (cells.isEmpty() || isBlank(cells[clamped])) return clamped to clamped
        var from = clamped
        while (from > 0 && !isBlank(cells[from - 1])) from--
        var to = clamped
        while (to < cells.size - 1 && !isBlank(cells[to + 1])) to++
        return from to to
    }

    private fun isBlank(cell: String?): Boolean = cell == null || (cell != CONTINUATION && cell.isBlank())

    private fun put(cells: Array<String?>, column: Int, text: String, width: Int) {
        if (column !in cells.indices) return
        cells[column] = text
        for (offset in 1 until width) {
            if (column + offset in cells.indices) cells[column + offset] = CONTINUATION
        }
    }

    private fun codePoints(text: String): List<String> {
        val result = mutableListOf<String>()
        var index = 0
        while (index < text.length) {
            val next = text.offsetByCodePoints(index, 1)
            result += text.substring(index, next)
            index = next
        }
        return result
    }
}
