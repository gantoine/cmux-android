package server

import (
	"context"
	"reflect"
	"testing"
	"time"

	"github.com/coder/websocket/wsjson"

	"github.com/gantoine/muxer/relay/internal/cmux"
)

func TestPhoneCanCloseRenameAndRestartATerminal(t *testing.T) {
	relay := startRelay(t)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	conn, _, err := relay.dial(t, ctx, relay.pair(t))
	if err != nil {
		t.Fatal(err)
	}
	defer conn.CloseNow()
	t.Setenv("SHELL", "/opt/homebrew/bin/fish")

	_ = wsjson.Write(ctx, conn, map[string]any{"type": "rename_terminal", "id": "1", "workspace_id": "w1"})
	if result := resultFor(t, ctx, conn, "1"); result["ok"] != false || result["error"].(map[string]any)["code"] != "invalid_params" {
		t.Fatalf("a request without surface_id must be refused: %v", result)
	}

	requests := []map[string]any{
		{"type": "rename_terminal", "id": "2", "workspace_id": "w1", "surface_id": "t1", "title": "  server  "},
		{"type": "rename_terminal", "id": "3", "workspace_id": "w1", "surface_id": "t1", "title": " "},
		{"type": "restart_terminal", "id": "4", "workspace_id": "w1", "surface_id": "t1"},
		{"type": "close_terminal", "id": "5", "workspace_id": "w1", "surface_id": "t2", "force": true},
	}
	for _, request := range requests {
		_ = wsjson.Write(ctx, conn, request)
		if result := resultFor(t, ctx, conn, request["id"].(string)); result["ok"] != true {
			t.Fatalf("%s failed: %v", request["type"], result)
		}
	}
	relay.cmux.mu.Lock()
	calls := append([]string(nil), relay.cmux.terminalCalls...)
	relay.cmux.mu.Unlock()
	want := []string{
		`rename w1/t1 "server"`,
		`rename w1/t1 ""`,
		"restart w1/t1 /opt/homebrew/bin/fish",
		"close w1/t2 force=true",
	}
	if !reflect.DeepEqual(calls, want) {
		t.Fatalf("calls = %q, want %q", calls, want)
	}
}

func TestClosingTheLastTerminalExplainsWhatToDoInstead(t *testing.T) {
	relay := startRelay(t)
	relay.cmux.closeErr = &cmux.RPCError{Code: "invalid_state", Message: "Cannot close the last surface"}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	conn, _, err := relay.dial(t, ctx, relay.pair(t))
	if err != nil {
		t.Fatal(err)
	}
	defer conn.CloseNow()

	_ = wsjson.Write(ctx, conn, map[string]any{"type": "close_terminal", "id": "1", "workspace_id": "w1", "surface_id": "t1"})
	if result := resultFor(t, ctx, conn, "1"); result["error"].(map[string]any)["code"] != "last_terminal" {
		t.Fatalf("the last terminal must be reported as such: %v", result)
	}

	relay.cmux.mu.Lock()
	relay.cmux.closeErr = &cmux.RPCError{Code: "confirmation_required", Message: "Surface has a running process; retry with force=true"}
	relay.cmux.mu.Unlock()
	_ = wsjson.Write(ctx, conn, map[string]any{"type": "close_terminal", "id": "2", "workspace_id": "w1", "surface_id": "t1"})
	if result := resultFor(t, ctx, conn, "2"); result["error"].(map[string]any)["code"] != "confirmation_required" {
		t.Fatalf("a busy terminal must ask the phone to confirm: %v", result)
	}

	relay.cmux.mu.Lock()
	relay.cmux.methods = []string{"system.identify"}
	relay.cmux.mu.Unlock()
	_ = wsjson.Write(ctx, conn, map[string]any{"type": "hello", "id": "3"})
	resultFor(t, ctx, conn, "3")
	_ = wsjson.Write(ctx, conn, map[string]any{"type": "restart_terminal", "id": "4", "workspace_id": "w1", "surface_id": "t1"})
	if result := resultFor(t, ctx, conn, "4"); result["error"].(map[string]any)["code"] != "unsupported" {
		t.Fatalf("an older cmux must be reported as unsupported: %v", result)
	}
}

func TestAttachedTerminalReportsWhetherTheAgentIsWaitingOnYou(t *testing.T) {
	relay := startRelay(t)
	relay.server.options.PollInterval.InputState = 10 * time.Millisecond
	relay.cmux.inputState = cmux.InputState{SurfaceID: "t1", Agent: true, State: "empty"}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	conn, _, err := relay.dial(t, ctx, relay.pair(t))
	if err != nil {
		t.Fatal(err)
	}
	defer conn.CloseNow()

	_ = wsjson.Write(ctx, conn, map[string]any{"type": "attach", "workspace_id": "w1", "surface_id": "t1"})
	if state := readUntil(t, ctx, conn, "input_state"); state["surface_id"] != "t1" || state["waiting_on_human"] != false {
		t.Fatalf("the first state must be sent on attach: %v", state)
	}

	relay.cmux.mu.Lock()
	relay.cmux.inputState = cmux.InputState{SurfaceID: "t1", Agent: true, Lifecycle: "needsInput", State: "dialog", BlocksTyping: true, WaitingOnHuman: true}
	relay.cmux.mu.Unlock()
	state := readUntil(t, ctx, conn, "input_state")
	if state["waiting_on_human"] != true || state["blocks_typing"] != true || state["state"] != "dialog" {
		t.Fatalf("a prompt opening must be pushed: %v", state)
	}
}
