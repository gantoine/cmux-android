package screen

import (
	"unicode/utf8"

	"github.com/gantoine/muxer/relay/internal/cmux"
)

type History struct {
	Styles []Style  `json:"styles"`
	Lines  [][]Span `json:"lines"`
	Total  int      `json:"total"`
}

func HistoryFrom(grid *cmux.RenderGrid) History {
	history := History{Styles: []Style{}, Lines: make([][]Span, grid.ScrollbackRows), Total: grid.HistoryRows}
	sourceStyles := make(map[int]cmux.Style, len(grid.Styles))
	for _, style := range grid.Styles {
		sourceStyles[style.ID] = style
	}
	styleIDs := map[styleKey]int{}
	for _, span := range grid.ScrollbackSpans {
		if span.Row < 0 || span.Row >= grid.ScrollbackRows {
			continue
		}
		key := keyOf(sourceStyles[span.StyleID])
		id, known := styleIDs[key]
		if !known {
			id = len(styleIDs)
			styleIDs[key] = id
			history.Styles = append(history.Styles, styleFromKey(id, key))
		}
		width := utf8.RuneCountInString(span.Text)
		if span.CellWidth != nil {
			width = *span.CellWidth
		}
		history.Lines[span.Row] = append(history.Lines[span.Row], Span{Column: span.Column, Width: width, Style: id, Text: span.Text})
	}
	for index, line := range history.Lines {
		if line == nil {
			history.Lines[index] = []Span{}
		}
	}
	return history
}
