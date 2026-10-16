package server

import (
	"context"
	"slices"
	"testing"
	"time"

	"github.com/coder/websocket/wsjson"

	"github.com/gantoine/muxer/relay/internal/cmux"
)

func TestPhoneListsNotificationsAndMarksThemRead(t *testing.T) {
	relay := startRelay(t)
	relay.cmux.mu.Lock()
	relay.cmux.notifications = []cmux.Notification{
		{ID: "n1", Title: "Claude Code", WorkspaceID: "w1", SurfaceID: "t1"},
		{ID: "n2", Title: "Done", WorkspaceID: "w1", SurfaceID: "t1", IsRead: true},
		{ID: "n3", Title: "Build", WorkspaceID: "w2", SurfaceID: "t2"},
		{ID: "n4", Title: "Tests", WorkspaceID: "w3", SurfaceID: "t3"},
	}
	relay.cmux.mu.Unlock()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	conn, _, err := relay.dial(t, ctx, relay.pair(t))
	if err != nil {
		t.Fatal(err)
	}
	defer conn.CloseNow()

	_ = wsjson.Write(ctx, conn, map[string]any{"type": "notifications", "id": "1"})
	result := resultFor(t, ctx, conn, "1")
	listed := result["data"].(map[string]any)["notifications"].([]any)
	if len(listed) != 4 || listed[1].(map[string]any)["is_read"] != true {
		t.Fatalf("the phone must see every notification with its read state: %v", result)
	}

	_ = wsjson.Write(ctx, conn, map[string]any{"type": "mark_notifications_read", "id": "2"})
	if result := resultFor(t, ctx, conn, "2"); result["ok"] != false || result["error"].(map[string]any)["code"] != "invalid_params" {
		t.Fatalf("marking nothing in particular must be refused: %v", result)
	}

	_ = wsjson.Write(ctx, conn, map[string]any{"type": "mark_notifications_read", "id": "3", "notification_id": "n4"})
	if result := resultFor(t, ctx, conn, "3"); result["ok"] != true || !slices.Equal(relay.cmux.marked(), []string{"n4"}) {
		t.Fatalf("marking one notification read failed: %v %v", result, relay.cmux.marked())
	}

	_ = wsjson.Write(ctx, conn, map[string]any{"type": "mark_notifications_read", "id": "3b", "surface_id": "t2"})
	if result := resultFor(t, ctx, conn, "3b"); result["ok"] != true {
		t.Fatalf("marking a terminal read failed: %v", result)
	}
	if marked := relay.cmux.marked(); !slices.Equal(marked, []string{"n4", "n3"}) {
		t.Fatalf("only the terminal's unread notifications must be marked, got %v", marked)
	}

	_ = wsjson.Write(ctx, conn, map[string]any{"type": "attach", "workspace_id": "w1", "surface_id": "t1"})
	deadline := time.Now().Add(2 * time.Second)
	for !slices.Contains(relay.cmux.marked(), "n1") {
		if time.Now().After(deadline) {
			t.Fatalf("opening a terminal must mark its notifications read, got %v", relay.cmux.marked())
		}
		time.Sleep(10 * time.Millisecond)
	}
	if slices.Contains(relay.cmux.marked(), "n2") {
		t.Fatalf("opening a terminal must leave read and other terminals' notifications alone: %v", relay.cmux.marked())
	}

	_ = wsjson.Write(ctx, conn, map[string]any{"type": "mark_notifications_read", "id": "4", "all": true})
	if result := resultFor(t, ctx, conn, "4"); result["ok"] != true || !slices.Contains(relay.cmux.marked(), "all") {
		t.Fatalf("marking everything read failed: %v", result)
	}
}
