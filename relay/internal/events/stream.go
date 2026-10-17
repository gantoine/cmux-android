package events

import (
	"context"
	"log/slog"
	"time"

	"github.com/gantoine/muxer/relay/internal/cmux"
)

const consumerQueueSize = 256

type Source interface {
	Events(ctx context.Context, afterSeq *int64, filter cmux.EventFilter, handle func(cmux.Event) error) error
}

type Consumer interface {
	HandleEvent(ctx context.Context, event cmux.Event)
	StreamResumed(ctx context.Context)
}

type Stream struct {
	source     Source
	logger     *slog.Logger
	filter     cmux.EventFilter
	consumers  []Consumer
	firstRetry time.Duration
	maxRetry   time.Duration
}

func NewStream(source Source, logger *slog.Logger, filter cmux.EventFilter, consumers ...Consumer) *Stream {
	return &Stream{source: source, logger: logger, filter: filter, consumers: consumers, firstRetry: time.Second, maxRetry: 30 * time.Second}
}

type delivery struct {
	event   cmux.Event
	resumed bool
}

func (s *Stream) Run(ctx context.Context) {
	queues := make([]chan delivery, len(s.consumers))
	for index, consumer := range s.consumers {
		queue := make(chan delivery, consumerQueueSize)
		queues[index] = queue
		go drain(ctx, consumer, queue)
	}
	deliver := func(item delivery) bool {
		for _, queue := range queues {
			select {
			case queue <- item:
			case <-ctx.Done():
				return false
			}
		}
		return true
	}

	var afterSeq *int64
	backoff := s.firstRetry
	for ctx.Err() == nil {
		err := s.source.Events(ctx, afterSeq, s.filter, func(event cmux.Event) error {
			backoff = s.firstRetry
			if event.Type != "event" {
				return nil
			}
			seq := event.Seq
			afterSeq = &seq
			if !deliver(delivery{event: event}) {
				return ctx.Err()
			}
			return nil
		})
		if ctx.Err() != nil {
			return
		}
		s.logger.Warn("cmux event stream ended; reconnecting", "error", err, "retry_in", backoff)
		select {
		case <-ctx.Done():
			return
		case <-time.After(backoff):
		}
		backoff = min(backoff*2, s.maxRetry)
		if !deliver(delivery{resumed: true}) {
			return
		}
	}
}

func drain(ctx context.Context, consumer Consumer, queue <-chan delivery) {
	for {
		select {
		case <-ctx.Done():
			return
		case item := <-queue:
			if item.resumed {
				consumer.StreamResumed(ctx)
			} else {
				consumer.HandleEvent(ctx, item.event)
			}
		}
	}
}
