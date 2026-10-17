package events

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"slices"
	"sync"
	"testing"
	"time"

	"github.com/gantoine/muxer/relay/internal/cmux"
)

type droppingSource struct {
	mu        sync.Mutex
	calls     int
	resumedAt []*int64
}

func (s *droppingSource) Events(ctx context.Context, afterSeq *int64, filter cmux.EventFilter, handle func(cmux.Event) error) error {
	s.mu.Lock()
	s.calls++
	call := s.calls
	s.resumedAt = append(s.resumedAt, afterSeq)
	s.mu.Unlock()
	if !slices.Equal(filter.Categories, []string{"notification", "agent"}) {
		return fmt.Errorf("unexpected categories %v", filter.Categories)
	}
	if call == 1 {
		_ = handle(cmux.Event{Type: "heartbeat"})
		_ = handle(cmux.Event{Type: "event", Seq: 1, Name: "notification.created"})
		_ = handle(cmux.Event{Type: "event", Seq: 2, Name: "agent.hook.Stop"})
		return errors.New("socket closed")
	}
	_ = handle(cmux.Event{Type: "event", Seq: 3, Name: "agent.auto_resume.sent"})
	<-ctx.Done()
	return ctx.Err()
}

type recorder struct {
	mu   sync.Mutex
	seen []string
}

func (r *recorder) HandleEvent(_ context.Context, event cmux.Event) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.seen = append(r.seen, event.Name)
}

func (r *recorder) StreamResumed(context.Context) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.seen = append(r.seen, "resumed")
}

func (r *recorder) snapshot() []string {
	r.mu.Lock()
	defer r.mu.Unlock()
	return slices.Clone(r.seen)
}

func TestEveryConsumerGetsEventsInOrderAndResumesWhereTheStreamDropped(t *testing.T) {
	source := &droppingSource{}
	first, second := &recorder{}, &recorder{}
	stream := NewStream(source, slog.New(slog.DiscardHandler), cmux.EventFilter{Categories: []string{"notification", "agent"}}, first, second)
	stream.firstRetry = time.Millisecond
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go stream.Run(ctx)

	want := []string{"notification.created", "agent.hook.Stop", "resumed", "agent.auto_resume.sent"}
	deadline := time.Now().Add(2 * time.Second)
	for !slices.Equal(first.snapshot(), want) || !slices.Equal(second.snapshot(), want) {
		if time.Now().After(deadline) {
			t.Fatalf("first saw %v, second saw %v, want %v", first.snapshot(), second.snapshot(), want)
		}
		time.Sleep(5 * time.Millisecond)
	}
	source.mu.Lock()
	defer source.mu.Unlock()
	if source.resumedAt[0] != nil || source.resumedAt[1] == nil || *source.resumedAt[1] != 2 {
		t.Fatalf("the second connection must resume after the last event seen, got %v", source.resumedAt)
	}
}
