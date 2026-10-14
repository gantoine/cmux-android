package claude

import (
	"os"
	"path/filepath"
	"testing"
)

func feedAll(t *Transcript, lines ...string) []Entry {
	var changed []Entry
	for _, raw := range lines {
		changed = append(changed, t.Feed([]byte(raw))...)
	}
	return changed
}

func TestTranscriptTurnsMessagesIntoEntries(t *testing.T) {
	transcript := NewTranscript()
	feedAll(transcript,
		`{"type":"summary","summary":"ignored"}`,
		`{"type":"user","uuid":"u1","timestamp":"t1","message":{"role":"user","content":"fix the tests"}}`,
		`{"type":"assistant","uuid":"a1","message":{"role":"assistant","content":[{"type":"thinking","thinking":"hmm"},{"type":"text","text":"Running them now."}]}}`,
		`{"type":"assistant","uuid":"a2","message":{"role":"assistant","content":[{"type":"tool_use","id":"toolu_1","name":"Bash","input":{"command":"go test ./...\necho done","description":"Run tests"}}]}}`,
		`{"type":"user","uuid":"u2","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_1","content":"ok  all","is_error":false}]}}`,
		`{"type":"assistant","uuid":"a3","isSidechain":true,"message":{"role":"assistant","content":[{"type":"text","text":"subagent chatter"}]}}`,
		`{"type":"user","uuid":"u3","isMeta":true,"message":{"role":"user","content":"Caveat: meta"}}`,
	)
	entries := transcript.Entries()
	if len(entries) != 3 {
		t.Fatalf("got %d entries: %+v", len(entries), entries)
	}
	if entries[0].Kind != KindUser || entries[0].Text != "fix the tests" || entries[0].Time != "t1" {
		t.Errorf("user entry = %+v", entries[0])
	}
	if entries[1].Kind != KindAssistant || entries[1].Text != "Running them now." {
		t.Errorf("assistant entry = %+v", entries[1])
	}
	tool := entries[2]
	if tool.ID != "toolu_1" || tool.Tool.Name != "Bash" || tool.Tool.Summary != "go test ./..." || tool.Tool.Status != ToolDone || tool.Tool.Output != "ok  all" {
		t.Errorf("tool entry = %+v %+v", tool, tool.Tool)
	}
}

func TestToolResultsUpdateTheirToolEntry(t *testing.T) {
	transcript := NewTranscript()
	feedAll(transcript, `{"type":"assistant","uuid":"a","message":{"role":"assistant","content":[{"type":"tool_use","id":"t1","name":"Read","input":{"file_path":"/Users/me/app/src/main.go"}}]}}`)
	changed := transcript.Feed([]byte(`{"type":"user","uuid":"u","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","is_error":true,"content":[{"type":"text","text":"no such file"}]}]}}`))
	if len(changed) != 1 || changed[0].ID != "t1" || changed[0].Tool.Status != ToolFailed || changed[0].Tool.Output != "no such file" || changed[0].Tool.Summary != "src/main.go" {
		t.Fatalf("changed = %+v", changed)
	}
	if len(transcript.Entries()) != 1 {
		t.Fatalf("result added a new entry instead of updating: %+v", transcript.Entries())
	}
}

func TestSlashCommandsAndLocalOutput(t *testing.T) {
	transcript := NewTranscript()
	feedAll(transcript,
		`{"type":"user","uuid":"c","message":{"role":"user","content":"<command-message>model</command-message>\n<command-name>/model</command-name>\n<command-args>opus</command-args>"}}`,
		`{"type":"user","uuid":"o","message":{"role":"user","content":"<local-command-stdout>Set model to Opus</local-command-stdout>"}}`,
		`{"type":"user","uuid":"i","message":{"role":"user","content":[{"type":"text","text":"[Request interrupted by user]"}]}}`,
		`{"type":"user","uuid":"r","message":{"role":"user","content":"<system-reminder>hidden</system-reminder>"}}`,
	)
	entries := transcript.Entries()
	want := []struct{ kind, text string }{{KindUser, "/model opus"}, {KindNotice, "Set model to Opus"}, {KindNotice, "Interrupted"}}
	if len(entries) != len(want) {
		t.Fatalf("got %+v", entries)
	}
	for i, w := range want {
		if entries[i].Kind != w.kind || entries[i].Text != w.text {
			t.Errorf("entry %d = %+v, want %s %q", i, entries[i], w.kind, w.text)
		}
	}
}

func TestBrokenLinesAreSkipped(t *testing.T) {
	transcript := NewTranscript()
	if changed := transcript.Feed([]byte(`{"type":"user","message":`)); changed != nil {
		t.Fatalf("changed = %+v", changed)
	}
}

func TestFindTranscriptUsesTheEncodedProjectFolder(t *testing.T) {
	config := t.TempDir()
	want := filepath.Join(config, "projects", "-Users-me-my-app", "abc-123.jsonl")
	if err := os.MkdirAll(filepath.Dir(want), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(want, nil, 0o600); err != nil {
		t.Fatal(err)
	}
	if got, ok := FindTranscript(config, "/Users/me/my.app", "abc-123"); !ok || got != want {
		t.Errorf("FindTranscript = %q, %v", got, ok)
	}
	if got, ok := FindTranscript(config, "/somewhere/else", "abc-123"); !ok || got != want {
		t.Errorf("fallback FindTranscript = %q, %v", got, ok)
	}
	if _, ok := FindTranscript(config, "/Users/me/my.app", "../../etc/passwd"); ok {
		t.Error("accepted a session id with path characters")
	}
}

func TestTailReturnsWholeLinesAndNoticesTruncation(t *testing.T) {
	file := filepath.Join(t.TempDir(), "s.jsonl")
	tail := NewTail(file)
	if lines, _, err := tail.Lines(); err != nil || lines != nil {
		t.Fatalf("missing file: %q %v", lines, err)
	}
	write := func(content string, appendTo bool) {
		flags := os.O_CREATE | os.O_WRONLY | os.O_TRUNC
		if appendTo {
			flags = os.O_CREATE | os.O_WRONLY | os.O_APPEND
		}
		f, err := os.OpenFile(file, flags, 0o600)
		if err != nil {
			t.Fatal(err)
		}
		f.WriteString(content)
		f.Close()
	}
	write("one\ntw", false)
	lines, _, _ := tail.Lines()
	if len(lines) != 1 || string(lines[0]) != "one" {
		t.Fatalf("first read = %q", lines)
	}
	write("o\nthree\n", true)
	lines, _, _ = tail.Lines()
	if len(lines) != 2 || string(lines[0]) != "two" || string(lines[1]) != "three" {
		t.Fatalf("second read = %q", lines)
	}
	write("new\n", false)
	lines, restarted, _ := tail.Lines()
	if !restarted || len(lines) != 1 || string(lines[0]) != "new" {
		t.Fatalf("after truncation = %q restarted=%v", lines, restarted)
	}
}
