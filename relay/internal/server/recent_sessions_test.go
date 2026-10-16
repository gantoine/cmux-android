package server

import (
	"context"
	"reflect"
	"testing"
	"time"

	"github.com/coder/websocket/wsjson"

	"github.com/gantoine/muxer/relay/internal/claude"
	"github.com/gantoine/muxer/relay/internal/cmux"
)

func agentWorkspaces() *cmux.WorkspaceList {
	source := "claude"
	return &cmux.WorkspaceList{
		Workspaces: []cmux.Workspace{
			{ID: "w1", Terminals: []cmux.Terminal{{ID: "t1", AgentSource: &source, IsReady: true}, {ID: "t9", IsReady: true}}},
			{ID: "w2", Terminals: []cmux.Terminal{{ID: "t2"}}},
		},
	}
}

func TestRecentSessionsMarkWhichAreOpenAndHideBareCommandTitles(t *testing.T) {
	relay := startRelay(t)
	relay.cmux.workspaceList = agentWorkspaces()
	relay.cmux.setRunning(claude.SessionRef{SessionID: "s-open"})
	relay.cmux.vaultSessions = []cmux.VaultSession{
		{Agent: "claude", SessionID: "s-open", Title: "fix the relay\nand more", Cwd: "/src/muxer", Modified: "2026-10-16T20:00:00Z"},
		{Agent: "codex", SessionID: "s-old", Title: "/clear", Cwd: "/src/api", GitBranch: "main", Modified: "2026-10-15T09:00:00Z"},
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	conn, _, err := relay.dial(t, ctx, relay.pair(t))
	if err != nil {
		t.Fatal(err)
	}
	defer conn.CloseNow()

	_ = wsjson.Write(ctx, conn, map[string]any{"type": "recent_sessions", "id": "1"})
	result := resultFor(t, ctx, conn, "1")
	if result["ok"] != true {
		t.Fatalf("recent_sessions failed: %v", result)
	}
	sessions := result["data"].(map[string]any)["sessions"].([]any)
	open := sessions[0].(map[string]any)
	if open["title"] != "fix the relay" || open["workspace_id"] != "w1" || open["surface_id"] != "t1" {
		t.Fatalf("the running session must point at its terminal: %v", open)
	}
	old := sessions[1].(map[string]any)
	if old["title"] != "" || old["agent"] != "codex" || old["git_branch"] != "main" || old["workspace_id"] != nil {
		t.Fatalf("a bare slash command is not a title, and a closed session has no terminal: %v", old)
	}
	if _, leaked := old["resume_command"]; leaked {
		t.Fatalf("the resume command stays on the computer: %v", old)
	}

	_ = wsjson.Write(ctx, conn, map[string]any{"type": "recent_sessions", "id": "2", "query": " relay ", "limit": 1000})
	result = resultFor(t, ctx, conn, "2")
	if got := len(result["data"].(map[string]any)["sessions"].([]any)); got != 1 {
		t.Fatalf("search returned %d sessions, want 1", got)
	}
	relay.cmux.mu.Lock()
	calls := append([]string(nil), relay.cmux.vaultCalls...)
	relay.cmux.mu.Unlock()
	want := []string{`sessions agent="" limit=50`, `search "relay" limit=200`}
	if !reflect.DeepEqual(calls, want) {
		t.Fatalf("vault calls = %q, want %q", calls, want)
	}
}

func TestResumingASessionWakesItsNewTerminalAndRunsItsResumeCommand(t *testing.T) {
	relay := startRelay(t)
	relay.cmux.workspaceList = agentWorkspaces()
	relay.cmux.setRunning(claude.SessionRef{SessionID: "s-open"})
	relay.cmux.submitted = true
	relay.cmux.vaultSessions = []cmux.VaultSession{
		{Agent: "claude", SessionID: "s-open", ResumeCommand: "claude --resume s-open"},
		{Agent: "claude", SessionID: "s-old", Cwd: "/src/api", ResumeCommand: "claude --resume s-old"},
		{Agent: "pi", SessionID: "s-pi"},
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	conn, _, err := relay.dial(t, ctx, relay.pair(t))
	if err != nil {
		t.Fatal(err)
	}
	defer conn.CloseNow()

	_ = wsjson.Write(ctx, conn, map[string]any{"type": "resume_session", "id": "1", "agent": "claude", "session_id": "s-open"})
	if data := resultFor(t, ctx, conn, "1")["data"].(map[string]any); data["already_open"] != true || data["surface_id"] != "t1" {
		t.Fatalf("a session that is already running must open its terminal: %v", data)
	}

	_ = wsjson.Write(ctx, conn, map[string]any{"type": "resume_session", "id": "2", "agent": "claude", "session_id": "s-old"})
	if data := resultFor(t, ctx, conn, "2")["data"].(map[string]any); data["already_open"] != false || data["workspace_id"] != "w2" || data["surface_id"] != "t2" {
		t.Fatalf("resume must answer with the new terminal: %v", data)
	}
	relay.cmux.mu.Lock()
	commands := append([]string(nil), relay.cmux.commands...)
	relay.cmux.mu.Unlock()
	if want := []string{"w2/t2 claude --resume s-old"}; !reflect.DeepEqual(commands, want) {
		t.Fatalf("commands = %q, want %q", commands, want)
	}

	for id, request := range map[string]map[string]any{
		"3": {"agent": "pi", "session_id": "s-pi"},
		"4": {"agent": "claude", "session_id": "s-gone"},
		"5": {"agent": "claude"},
	} {
		request["type"] = "resume_session"
		request["id"] = id
		_ = wsjson.Write(ctx, conn, request)
		code := resultFor(t, ctx, conn, id)["error"].(map[string]any)["code"]
		if want := map[string]string{"3": "not_resumable", "4": "not_found", "5": "invalid_params"}[id]; code != want {
			t.Fatalf("request %s: code = %v, want %s", id, code, want)
		}
	}
}

func TestResumePressesEnterWhenThePasteWasNotSubmitted(t *testing.T) {
	relay := startRelay(t)
	relay.cmux.workspaceList = agentWorkspaces()
	relay.cmux.vaultSessions = []cmux.VaultSession{{Agent: "claude", SessionID: "s-old", ResumeCommand: "claude --resume s-old"}}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	conn, _, err := relay.dial(t, ctx, relay.pair(t))
	if err != nil {
		t.Fatal(err)
	}
	defer conn.CloseNow()

	_ = wsjson.Write(ctx, conn, map[string]any{"type": "resume_session", "id": "1", "agent": "claude", "session_id": "s-old"})
	if result := resultFor(t, ctx, conn, "1"); result["ok"] != true {
		t.Fatalf("resume failed: %v", result)
	}
	_, keys, _ := relay.cmux.snapshot()
	if !reflect.DeepEqual(keys, []string{"t2:enter"}) {
		t.Fatalf("keys = %q, want t2:enter", keys)
	}
}

func TestRecentSessionsNeedAVaultAwareCmux(t *testing.T) {
	relay := startRelay(t)
	relay.cmux.methods = []string{"system.identify"}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	conn, _, err := relay.dial(t, ctx, relay.pair(t))
	if err != nil {
		t.Fatal(err)
	}
	defer conn.CloseNow()

	_ = wsjson.Write(ctx, conn, map[string]any{"type": "hello", "id": "1"})
	resultFor(t, ctx, conn, "1")
	_ = wsjson.Write(ctx, conn, map[string]any{"type": "recent_sessions", "id": "2"})
	if code := resultFor(t, ctx, conn, "2")["error"].(map[string]any)["code"]; code != "unsupported" {
		t.Fatalf("code = %v, want unsupported", code)
	}
}

func TestClaudeResumeKeepsTheDefaultConfigDirImplicit(t *testing.T) {
	t.Setenv("HOME", "/Users/me")
	t.Setenv("CLAUDE_CONFIG_DIR", "")
	command := `cd -- '/src' && /bin/sh -c 'env CLAUDE_CONFIG_DIR=/Users/me/.claude CMUX_PRESERVE_CLAUDE_AUTH_SELECTION_ENV=1 claude --resume s1'`
	want := `cd -- '/src' && /bin/sh -c 'env CMUX_PRESERVE_CLAUDE_AUTH_SELECTION_ENV=1 claude --resume s1'`
	if got := resumeCommand(cmux.VaultSession{Agent: "claude", ResumeCommand: command}); got != want {
		t.Fatalf("resumeCommand = %s, want %s", got, want)
	}
	custom := `env CLAUDE_CONFIG_DIR=/Users/me/.claude-work claude --resume s1`
	if got := resumeCommand(cmux.VaultSession{Agent: "claude", ResumeCommand: custom}); got != custom {
		t.Fatalf("a custom config dir must stay: %s", got)
	}
	if got := resumeCommand(cmux.VaultSession{Agent: "codex", ResumeCommand: command}); got != command {
		t.Fatalf("other agents' commands must stay: %s", got)
	}
}
