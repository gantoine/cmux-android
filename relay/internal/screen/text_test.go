package screen

import (
	"reflect"
	"testing"

	"github.com/gantoine/muxer/relay/internal/cmux"
)

func TestTextLinesPlacesSpansInColumnsAcrossScrollbackAndScreen(t *testing.T) {
	grid := &cmux.RenderGrid{
		Columns:        10,
		Rows:           2,
		ScrollbackRows: 2,
		ScrollbackSpans: []cmux.Span{
			{Row: 0, Column: 0, Text: "$ ls"},
			{Row: 1, Column: 2, Text: "a.txt"},
		},
		RowSpans: []cmux.Span{
			{Row: 0, Column: 0, Text: "$ "},
			{Row: 0, Column: 2, Text: "echo"},
			{Row: 5, Column: 0, Text: "ignored"},
		},
	}
	got := TextLines(grid)
	want := []string{"$ ls", "  a.txt", "$ echo", ""}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("TextLines = %q, want %q", got, want)
	}
}

func TestTailDropsTrailingBlankLinesAndKeepsTheLastOnes(t *testing.T) {
	lines := []string{"one", "two", "", "three", "", "  "}
	if got := Tail(lines, 2); !reflect.DeepEqual(got, []string{"", "three"}) {
		t.Fatalf("Tail(2) = %q", got)
	}
	if got := Tail(lines, 10); !reflect.DeepEqual(got, []string{"one", "two", "", "three"}) {
		t.Fatalf("Tail(10) = %q", got)
	}
	if got := Tail([]string{"", ""}, 3); len(got) != 0 {
		t.Fatalf("Tail of blanks = %q", got)
	}
}
