package server

import (
	"bytes"
	"context"
	"sync"
	"time"

	"github.com/gantoine/muxer/relay/internal/pubsub"
)

type feed struct {
	poll     func(ctx context.Context) ([]byte, bool)
	interval time.Duration
	wake     chan struct{}
	changes  pubsub.Broadcaster

	mu       sync.Mutex
	latest   []byte
	watchers int
	running  bool
}

func newFeed(interval time.Duration, poll func(ctx context.Context) ([]byte, bool)) *feed {
	return &feed{poll: poll, interval: interval, wake: make(chan struct{}, 1)}
}

func (f *feed) refresh() {
	pubsub.Signal(f.wake)
}

func (f *feed) current() []byte {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.latest
}

func (f *feed) subscribe(ctx context.Context) (<-chan struct{}, func()) {
	changes, unsubscribe := f.changes.Subscribe()
	f.mu.Lock()
	f.watchers++
	start := !f.running
	f.running = true
	f.mu.Unlock()
	if start {
		go f.run(ctx)
	} else {
		f.refresh()
	}
	return changes, func() {
		unsubscribe()
		f.mu.Lock()
		f.watchers--
		f.mu.Unlock()
	}
}

func (f *feed) run(ctx context.Context) {
	for {
		pollCtx, cancel := context.WithTimeout(ctx, callTimeout)
		encoded, ok := f.poll(pollCtx)
		cancel()
		if ok && f.publish(encoded) {
			f.changes.Notify()
		}
		select {
		case <-ctx.Done():
		case <-f.wake:
		case <-time.After(f.interval):
		}
		if f.stopIfUnwatched(ctx) {
			return
		}
	}
}

func (f *feed) publish(encoded []byte) bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	if bytes.Equal(encoded, f.latest) {
		return false
	}
	f.latest = encoded
	return true
}

func (f *feed) stopIfUnwatched(ctx context.Context) bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.watchers > 0 && ctx.Err() == nil {
		return false
	}
	f.running = false
	f.latest = nil
	return true
}

func (s *session) follow(f *feed) {
	changes, leave := f.subscribe(s.server.ctx)
	defer leave()
	var sent []byte
	onEachChange(s.ctx, changes, func() {
		if latest := f.current(); latest != nil && !bytes.Equal(latest, sent) {
			sent = latest
			s.sendEncoded(latest)
		}
	})
}
