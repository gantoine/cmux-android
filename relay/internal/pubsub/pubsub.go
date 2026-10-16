package pubsub

import "sync"

func Signal(channel chan struct{}) {
	select {
	case channel <- struct{}{}:
	default:
	}
}

type Broadcaster struct {
	mu          sync.Mutex
	subscribers map[chan struct{}]struct{}
}

func (b *Broadcaster) Subscribe() (<-chan struct{}, func()) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.subscribers == nil {
		b.subscribers = map[chan struct{}]struct{}{}
	}
	channel := make(chan struct{}, 1)
	b.subscribers[channel] = struct{}{}
	return channel, func() {
		b.mu.Lock()
		defer b.mu.Unlock()
		delete(b.subscribers, channel)
	}
}

func (b *Broadcaster) Notify() {
	b.mu.Lock()
	defer b.mu.Unlock()
	for subscriber := range b.subscribers {
		Signal(subscriber)
	}
}
