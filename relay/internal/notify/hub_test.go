package notify

import (
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"testing"

	"github.com/gantoine/muxer/relay/internal/cmux"
)

type fakeSource struct {
	events        []cmux.Event
	notifications []cmux.Notification
}

func (f *fakeSource) Notifications(context.Context) ([]cmux.Notification, error) {
	return f.notifications, nil
}

func event(name, payload string, seq int64) cmux.Event {
	return cmux.Event{Type: "event", Name: name, Seq: seq, Payload: json.RawMessage(payload)}
}

func TestHubResolvesRedactedTextAndReplaysOnlyForReturningPhones(t *testing.T) {
	source := &fakeSource{
		events: []cmux.Event{
			event("notification.created", `{"notification_id":"n1","replaced_notification_ids":["old"]}`, 1),
			event("notification.read", `{"notification_id":"n1"}`, 2),
		},
		notifications: []cmux.Notification{{ID: "n1", Title: "Claude Code", Body: "Claude is waiting for your input", WorkspaceID: "w1"}},
	}
	hub := NewHub(source, slog.New(slog.NewTextHandler(io.Discard, nil)))
	for _, event := range source.events {
		hub.HandleEvent(context.Background(), event)
	}

	_, history, unsubscribe := hub.Subscribe(1)
	unsubscribe()
	if len(history) != 3 {
		t.Fatalf("want replaced, created, read; got %+v", history)
	}
	if history[0].Kind != KindDismissed || history[0].ID != "old" {
		t.Fatalf("a replaced notification must be dismissed first: %+v", history[0])
	}
	if history[1].Kind != KindCreated || history[1].Body != "Claude is waiting for your input" {
		t.Fatalf("created notification must carry the text cmux redacts from events: %+v", history[1])
	}
	if history[2].Kind != KindDismissed || history[2].ID != "n1" {
		t.Fatalf("reading on the Mac must dismiss on the phone: %+v", history[2])
	}

	_, fresh, unsubscribe := hub.Subscribe(0)
	unsubscribe()
	if len(fresh) != 0 {
		t.Fatalf("a newly paired phone must not get old notifications: %+v", fresh)
	}
	_, afterCreated, unsubscribe := hub.Subscribe(history[1].Seq)
	unsubscribe()
	if len(afterCreated) != 1 || afterCreated[0].Seq != history[2].Seq {
		t.Fatalf("a returning phone gets only what it missed: %+v", afterCreated)
	}
}
