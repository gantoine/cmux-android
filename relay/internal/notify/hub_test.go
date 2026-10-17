package notify

import (
	"context"
	"encoding/json"
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
			event("notification.read", `{"count":1,"notification_ids":["n1"]}`, 2),
		},
		notifications: []cmux.Notification{{ID: "n1", Title: "Claude Code", Body: "Claude is waiting for your input", WorkspaceID: "w1"}},
	}
	hub := NewHub(source, slog.New(slog.DiscardHandler))
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

func TestHubDismissesEveryNotificationReadOrRemovedOnTheMac(t *testing.T) {
	hub := NewHub(&fakeSource{}, slog.New(slog.DiscardHandler))
	hub.HandleEvent(context.Background(), event("notification.read", `{"count":2,"notification_ids":["n1","n2"]}`, 1))
	hub.HandleEvent(context.Background(), event("notification.removed", `{"notification_id":"n3"}`, 2))

	_, history, unsubscribe := hub.Subscribe(1)
	unsubscribe()
	var dismissed []string
	for _, notification := range history {
		if notification.Kind == KindDismissed {
			dismissed = append(dismissed, notification.ID)
		}
	}
	if len(dismissed) != 3 || dismissed[0] != "n1" || dismissed[1] != "n2" || dismissed[2] != "n3" {
		t.Fatalf("every read or removed notification must be dismissed on the phone, got %v", dismissed)
	}
}
