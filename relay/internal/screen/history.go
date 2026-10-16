package screen

import "github.com/gantoine/muxer/relay/internal/cmux"

type History struct {
	Styles []Style  `json:"styles"`
	Lines  [][]Span `json:"lines"`
	Total  int      `json:"total"`
}

func HistoryFrom(grid *cmux.RenderGrid) History {
	history := History{Styles: []Style{}, Lines: make([][]Span, grid.ScrollbackRows), Total: grid.HistoryRows}
	sourceStyles := stylesByID(grid.Styles)
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
		history.Lines[span.Row] = append(history.Lines[span.Row], Span{Column: span.Column, Width: spanWidth(span), Style: id, Text: span.Text})
	}
	for index, line := range history.Lines {
		if line == nil {
			history.Lines[index] = []Span{}
		}
	}
	return history
}
