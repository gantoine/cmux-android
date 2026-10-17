//go:build e2e

package e2e

import (
	"encoding/json"
	"regexp"
	"strings"
	"testing"
	"time"
)

func attach(t *testing.T, p *phone, ws workspace, surfaceID string) {
	t.Helper()
	mark := p.mark()
	p.must("attach", map[string]any{"workspace_id": ws.ID, "surface_id": surfaceID})
	p.pushAfter(mark, "screen", "the first screen frame", func(frame message) bool {
		return frame["surface_id"] == surfaceID && flag(frame["full"])
	})
}

func TestTypingShowsUpOnTheScreen(t *testing.T) {
	p := connect(t)
	ws := scratchWorkspace(t, p)
	attach(t, p, ws, ws.SurfaceID)

	mark := p.mark()
	p.must("input", map[string]any{"text": "printf 'e2e%s\\n' -typed-ok\r"})
	p.rawPushAfter(mark, "screen", "the command's output on screen", func(raw string) bool {
		return strings.Contains(raw, "e2e-typed-ok")
	})

	mark = p.mark()
	p.must("paste", map[string]any{"text": "printf 'e2e%s\\n' -pasted-ok", "submit": true})
	p.rawPushAfter(mark, "screen", "the pasted command's output on screen", func(raw string) bool {
		return strings.Contains(raw, "e2e-pasted-ok")
	})
}

var earlyLine = regexp.MustCompile(`e2e-line-5[^0-9]`)

func TestHistoryHoldsLinesThatScrolledOff(t *testing.T) {
	p := connect(t)
	ws := scratchWorkspace(t, p)
	attach(t, p, ws, ws.SurfaceID)

	mark := p.mark()
	p.must("input", map[string]any{"text": "seq -f 'e2e-line-%g' 1 400\r"})
	p.rawPushAfter(mark, "screen", "the last line on screen", func(raw string) bool {
		return strings.Contains(raw, "e2e-line-400")
	})
	var found bool
	deadline := time.Now().Add(settleTimeout)
	for !found && time.Now().Before(deadline) {
		history := p.must("history", map[string]any{"lines": 1000})
		encoded, _ := json.Marshal(history)
		found = earlyLine.Match(encoded)
		if !found {
			time.Sleep(500 * time.Millisecond)
		}
	}
	if !found {
		t.Fatal("history doesn't include lines that scrolled off the screen")
	}
}

func TestTerminalsCanBeRenamedRestartedAndClosed(t *testing.T) {
	p := connect(t)
	ws := scratchWorkspace(t, p)

	only := p.failure("close_terminal", map[string]any{"workspace_id": ws.ID, "surface_id": ws.SurfaceID})
	if only.Code != "last_terminal" {
		t.Fatalf("closing a workspace's only terminal should fail with last_terminal, got %v", only)
	}

	created := p.must("create_terminal", map[string]any{"workspace_id": ws.ID})
	second := text(created["surface_id"])
	if second == "" {
		t.Fatalf("create_terminal answered %v", created)
	}
	p.workspaceSatisfies(ws.ID, "the second terminal", func(w message) bool {
		return find(list(w["terminals"]), "id", second) != nil
	})

	p.must("rename_terminal", map[string]any{"workspace_id": ws.ID, "surface_id": second, "title": "e2e-renamed-terminal"})
	p.workspaceSatisfies(ws.ID, "the terminal rename", func(w message) bool {
		return find(list(w["terminals"]), "id", second)["title"] == "e2e-renamed-terminal"
	})

	p.must("restart_terminal", map[string]any{"workspace_id": ws.ID, "surface_id": second})

	attach(t, p, ws, second)
	mark := p.mark()
	p.must("input", map[string]any{"text": "printf 'e2e%s\\n' -sleeping; sleep 600\r"})
	p.rawPushAfter(mark, "screen", "the sleep to start", func(raw string) bool {
		return strings.Contains(raw, "e2e-sleeping")
	})
	busy := p.failure("close_terminal", map[string]any{"workspace_id": ws.ID, "surface_id": second})
	if busy.Code != "confirmation_required" {
		t.Fatalf("closing a busy terminal should ask for confirmation, got %v", busy)
	}
	p.must("close_terminal", map[string]any{"workspace_id": ws.ID, "surface_id": second, "force": true})
	p.workspaceSatisfies(ws.ID, "the closed terminal to go away", func(w message) bool {
		return find(list(w["terminals"]), "id", second) == nil
	})
}
