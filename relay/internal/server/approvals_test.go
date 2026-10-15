package server

import (
	"context"
	"slices"
	"sync"
	"testing"
	"time"

	"github.com/coder/websocket/wsjson"

	"github.com/gantoine/muxer/relay/internal/approvals"
)

type fakeApprovals struct {
	mu      sync.Mutex
	pending []approvals.Approval
	answers []approvals.Answer
	changes chan struct{}
}

func (f *fakeApprovals) Pending() []approvals.Approval {
	f.mu.Lock()
	defer f.mu.Unlock()
	return slices.Clone(f.pending)
}

func (f *fakeApprovals) Subscribe() (<-chan struct{}, func()) {
	return f.changes, func() {}
}

func (f *fakeApprovals) Answer(_ context.Context, id string, answer approvals.Answer) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	index := slices.IndexFunc(f.pending, func(approval approvals.Approval) bool { return approval.ID == id })
	if index < 0 {
		return approvals.ErrNotPending
	}
	f.answers = append(f.answers, answer)
	f.pending = slices.Delete(f.pending, index, index+1)
	f.changes <- struct{}{}
	return nil
}

func TestPhoneSeesPendingApprovalsAndCanAnswerThem(t *testing.T) {
	relay := startRelay(t)
	fake := &fakeApprovals{
		pending: []approvals.Approval{{ID: "r1", Kind: approvals.KindPermission, Tool: "Bash", Detail: "npm test", WorkspaceID: "w1", SurfaceID: "t1"}},
		changes: make(chan struct{}, 1),
	}
	relay.server.options.Approvals = fake
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	conn, _, err := relay.dial(t, ctx, relay.pair(t))
	if err != nil {
		t.Fatal(err)
	}
	defer conn.CloseNow()

	_ = wsjson.Write(ctx, conn, map[string]any{"type": "hello", "id": "1"})
	pending := readUntil(t, ctx, conn, "approvals")["approvals"].([]any)
	if len(pending) != 1 || pending[0].(map[string]any)["detail"] != "npm test" {
		t.Fatalf("phone must get the pending approval on connect: %v", pending)
	}

	_ = wsjson.Write(ctx, conn, map[string]any{"type": "answer_approval", "id": "2", "approval_id": "r1", "mode": "once"})
	if result := readUntil(t, ctx, conn, "result"); result["ok"] != true || result["id"] != "2" {
		t.Fatalf("answering must succeed: %v", result)
	}
	if cleared := readUntil(t, ctx, conn, "approvals")["approvals"].([]any); len(cleared) != 0 {
		t.Fatalf("an answered approval must disappear from the phone: %v", cleared)
	}

	_ = wsjson.Write(ctx, conn, map[string]any{"type": "answer_approval", "id": "3", "approval_id": "r1", "mode": "once"})
	result := readUntil(t, ctx, conn, "result")
	if result["ok"] != false || result["error"].(map[string]any)["code"] != "not_pending" {
		t.Fatalf("answering twice must say the request is gone: %v", result)
	}
	fake.mu.Lock()
	defer fake.mu.Unlock()
	if len(fake.answers) != 1 || fake.answers[0].Mode != "once" {
		t.Fatalf("answers = %+v", fake.answers)
	}
}
