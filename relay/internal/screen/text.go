package screen

import (
	"strings"
	"unicode/utf8"

	"github.com/gantoine/muxer/relay/internal/cmux"
)

func TextLines(grid *cmux.RenderGrid) []string {
	lines := make([]string, 0, grid.ScrollbackRows+grid.Rows)
	lines = append(lines, rowsToText(grid.ScrollbackSpans, grid.ScrollbackRows, grid.Columns)...)
	lines = append(lines, rowsToText(grid.RowSpans, grid.Rows, grid.Columns)...)
	return lines
}

func Tail(lines []string, count int) []string {
	end := len(lines)
	for end > 0 && strings.TrimSpace(lines[end-1]) == "" {
		end--
	}
	start := end - count
	if count <= 0 || start < 0 {
		start = 0
	}
	return lines[start:end]
}

func rowsToText(spans []cmux.Span, rows, columns int) []string {
	cells := make([][]rune, rows)
	for index := range cells {
		cells[index] = []rune(strings.Repeat(" ", columns))
	}
	for _, span := range spans {
		if span.Row < 0 || span.Row >= rows {
			continue
		}
		column := span.Column
		for _, char := range span.Text {
			if column < 0 || column >= columns {
				break
			}
			cells[span.Row][column] = char
			column++
			if span.CellWidth != nil && *span.CellWidth > utf8.RuneCountInString(span.Text) {
				column++
			}
		}
	}
	lines := make([]string, rows)
	for index, row := range cells {
		lines[index] = strings.TrimRight(string(row), " ")
	}
	return lines
}
