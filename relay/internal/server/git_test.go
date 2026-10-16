package server

import (
	"context"
	"testing"
	"time"

	"github.com/coder/websocket/wsjson"

	"github.com/gantoine/muxer/relay/internal/cmux"
)

func TestEachTerminalCarriesItsOwnGitState(t *testing.T) {
	relay := startRelay(t)
	branch, dirty := "feat/approvals", true
	relay.cmux.terminalGit = []cmux.TerminalGit{
		{SurfaceID: "t1", GitBranch: &branch, GitDirty: &dirty, ListeningPorts: []int{3000, 5173}},
		{SurfaceID: "elsewhere", GitBranch: &branch},
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	conn, _, err := relay.dial(t, ctx, relay.pair(t))
	if err != nil {
		t.Fatal(err)
	}
	defer conn.CloseNow()

	_ = wsjson.Write(ctx, conn, map[string]any{"type": "hello", "id": "1"})
	workspaces := readUntil(t, ctx, conn, "workspaces")["workspaces"].([]any)
	terminals := workspaces[0].(map[string]any)["terminals"].([]any)
	if len(terminals) != 1 {
		t.Fatalf("git state must not add terminals: %v", terminals)
	}
	terminal := terminals[0].(map[string]any)
	if terminal["git_branch"] != "feat/approvals" || terminal["git_dirty"] != true {
		t.Fatalf("terminal must carry its own branch and dirty state: %v", terminal)
	}
	if ports := terminal["listening_ports"].([]any); len(ports) != 2 || ports[0] != float64(3000) {
		t.Fatalf("terminal must carry its listening ports: %v", terminal)
	}
}
