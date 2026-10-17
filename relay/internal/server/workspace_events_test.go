package server

import (
	"context"
	"testing"
	"time"

	"github.com/coder/websocket/wsjson"

	"github.com/gantoine/muxer/relay/internal/cmux"
)

func TestMacWorkspaceEventsRefreshThePhoneWithoutWaitingForThePoll(t *testing.T) {
	relay := startRelay(t)
	relay.server.workspaces.interval = time.Hour
	relay.cmux.workspaceList = &cmux.WorkspaceList{Workspaces: []cmux.Workspace{{ID: "w1"}}}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	conn, _, err := relay.dial(t, ctx, relay.pair(t))
	if err != nil {
		t.Fatal(err)
	}
	defer conn.CloseNow()
	_ = wsjson.Write(ctx, conn, map[string]any{"type": "hello", "id": "1"})
	readUntil(t, ctx, conn, "workspaces")

	relay.cmux.mu.Lock()
	relay.cmux.workspaceList = &cmux.WorkspaceList{Workspaces: []cmux.Workspace{{ID: "w1"}, {ID: "w2"}}}
	relay.cmux.mu.Unlock()
	relay.server.HandleEvent(ctx, cmux.Event{Type: "event", Name: "workspace.created"})
	if workspaces := readUntil(t, ctx, conn, "workspaces")["workspaces"].([]any); len(workspaces) != 2 {
		t.Fatalf("a workspace made on the Mac must reach the phone at once, got %v", workspaces)
	}

	relay.cmux.mu.Lock()
	relay.cmux.lanes = map[string]cmux.LaneStatus{"w2": {Effective: "review", Override: true}}
	relay.cmux.mu.Unlock()
	relay.server.HandleEvent(ctx, cmux.Event{Type: "event", Name: "workspace.action"})
	second := readUntil(t, ctx, conn, "workspaces")["workspaces"].([]any)[1].(map[string]any)
	if status, _ := second["status"].(map[string]any); status["effective"] != "review" {
		t.Fatalf("workspace.action must also refresh pins, colors and lanes, got %v", second)
	}
}
