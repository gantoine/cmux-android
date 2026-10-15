package server

import (
	"context"
	"sync"
	"testing"
	"time"

	"github.com/coder/websocket/wsjson"

	"github.com/gantoine/muxer/relay/internal/agents"
	"github.com/gantoine/muxer/relay/internal/cmux"
)

type fakeAgents struct {
	mu       sync.Mutex
	retries  []agents.Retry
	busy     []string
	sent     []string
	messages []cmux.AgentMessage
	changes  chan struct{}
	messaged chan struct{}
}

func (f *fakeAgents) Retries() []agents.Retry {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]agents.Retry{}, f.retries...)
}

func (f *fakeAgents) Busy() []string {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]string{}, f.busy...)
}

func (f *fakeAgents) SubscribeActivity() (<-chan struct{}, func()) { return f.changes, func() {} }

func (f *fakeAgents) SubscribeMessages(string) (<-chan struct{}, func()) {
	return f.messaged, func() {}
}

func (f *fakeAgents) Send(_ context.Context, surfaceID, deviceName, body string) (cmux.AgentMessage, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if body == "" {
		return cmux.AgentMessage{}, agents.ErrInvalidMessage
	}
	f.sent = append(f.sent, surfaceID+"|"+deviceName+"|"+body)
	message := cmux.AgentMessage{ID: "m1", SenderName: agents.SenderName(deviceName), Body: body, State: "queued"}
	f.messages = append(f.messages, message)
	f.messaged <- struct{}{}
	return message, nil
}

func (f *fakeAgents) Messages(context.Context, string) ([]cmux.AgentMessage, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]cmux.AgentMessage{}, f.messages...), nil
}

func TestPhoneSeesRetriesAndCanMessageTheAgent(t *testing.T) {
	relay := startRelay(t)
	fake := &fakeAgents{
		retries:  []agents.Retry{{SurfaceID: "t1", WorkspaceID: "w1", Attempt: 1, DueAt: time.Now().Add(20 * time.Second)}},
		busy:     []string{"t1"},
		changes:  make(chan struct{}, 1),
		messaged: make(chan struct{}, 1),
	}
	relay.server.options.Agents = fake
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	conn, _, err := relay.dial(t, ctx, relay.pair(t))
	if err != nil {
		t.Fatal(err)
	}
	defer conn.CloseNow()

	_ = wsjson.Write(ctx, conn, map[string]any{"type": "hello", "id": "1"})
	activity := readUntil(t, ctx, conn, "agent_activity")
	retries := activity["retries"].([]any)
	if len(retries) != 1 || retries[0].(map[string]any)["surface_id"] != "t1" {
		t.Fatalf("phone must get pending retries on connect: %v", retries)
	}
	if busy := activity["busy"].([]any); len(busy) != 1 || busy[0] != "t1" {
		t.Fatalf("phone must learn which agents are busy: %v", busy)
	}

	_ = wsjson.Write(ctx, conn, map[string]any{"type": "send_agent_message", "id": "2", "text": "use staging"})
	if result := readUntil(t, ctx, conn, "result"); result["ok"] != false || result["error"].(map[string]any)["code"] != "not_attached" {
		t.Fatalf("messaging needs an open terminal: %v", result)
	}

	_ = wsjson.Write(ctx, conn, map[string]any{"type": "attach", "workspace_id": "w1", "surface_id": "t1"})
	if listed := readUntil(t, ctx, conn, "agent_messages"); len(listed["messages"].([]any)) != 0 || listed["surface_id"] != "t1" {
		t.Fatalf("opening a terminal must list its messages: %v", listed)
	}

	_ = wsjson.Write(ctx, conn, map[string]any{"type": "send_agent_message", "id": "3", "text": ""})
	if result := readUntil(t, ctx, conn, "result"); result["ok"] != false || result["error"].(map[string]any)["code"] != "invalid_params" {
		t.Fatalf("empty messages must be refused: %v", result)
	}

	_ = wsjson.Write(ctx, conn, map[string]any{"type": "send_agent_message", "id": "4", "text": "use staging"})
	result := readUntil(t, ctx, conn, "result")
	if result["ok"] != true || result["data"].(map[string]any)["state"] != "queued" {
		t.Fatalf("sending must return the queued message: %v", result)
	}
	if listed := readUntil(t, ctx, conn, "agent_messages")["messages"].([]any); len(listed) != 1 {
		t.Fatalf("a sent message must show up in the list: %v", listed)
	}
	fake.mu.Lock()
	defer fake.mu.Unlock()
	if len(fake.sent) != 1 || fake.sent[0] != "t1|Pixel|use staging" {
		t.Fatalf("sent = %v", fake.sent)
	}
}
