package server

import (
	"context"
	"testing"
	"time"

	"github.com/coder/websocket"
	"github.com/coder/websocket/wsjson"

	"github.com/gantoine/muxer/relay/internal/cmux"
)

func TestPhoneCanKeepTheComputerAwake(t *testing.T) {
	relay := startRelay(t)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	conn, _, err := relay.dial(t, ctx, relay.pair(t))
	if err != nil {
		t.Fatal(err)
	}
	defer conn.CloseNow()

	_ = wsjson.Write(ctx, conn, map[string]any{"type": "hello", "id": "1"})
	if state := readUntil(t, ctx, conn, "keep_awake"); state["enabled"] != false {
		t.Fatalf("phone must learn the current state on connect: %v", state)
	}

	_ = wsjson.Write(ctx, conn, map[string]any{"type": "set_keep_awake", "id": "2"})
	if result := resultFor(t, ctx, conn, "2"); result["ok"] != false || result["error"].(map[string]any)["code"] != "invalid_params" {
		t.Fatalf("set_keep_awake without enabled must be refused: %v", result)
	}

	_ = wsjson.Write(ctx, conn, map[string]any{"type": "set_keep_awake", "id": "3", "enabled": true})
	if result := resultFor(t, ctx, conn, "3"); result["ok"] != true || result["data"].(map[string]any)["enabled"] != true {
		t.Fatalf("turning it on must report the new state: %v", result)
	}
	if state := readUntil(t, ctx, conn, "keep_awake"); state["enabled"] != true {
		t.Fatalf("the change must be pushed so the computer list updates: %v", state)
	}

	relay.cmux.mu.Lock()
	relay.cmux.keepAwakeErr = &cmux.RPCError{Code: "method_not_found", Message: "Unknown method"}
	relay.cmux.mu.Unlock()
	_ = wsjson.Write(ctx, conn, map[string]any{"type": "keep_awake", "id": "4"})
	if result := resultFor(t, ctx, conn, "4"); result["ok"] != false || result["error"].(map[string]any)["code"] != "unsupported" {
		t.Fatalf("an older cmux must be reported as unsupported: %v", result)
	}
}

func resultFor(t *testing.T, ctx context.Context, conn *websocket.Conn, id string) map[string]any {
	t.Helper()
	isResult := func(message map[string]any) bool { return message["type"] == "result" && message["id"] == id }
	if message := takeFromBacklog(conn, isResult); message != nil {
		return message
	}
	for {
		var message map[string]any
		if err := wsjson.Read(ctx, conn, &message); err != nil {
			t.Fatalf("waiting for result %s: %v", id, err)
		}
		if isResult(message) {
			return message
		}
		backlogMu.Lock()
		backlog[conn] = append(backlog[conn], message)
		backlogMu.Unlock()
	}
}
