//go:build e2e

package e2e

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"strconv"
	"sync"
	"testing"
	"time"

	"github.com/coder/websocket"
	"github.com/coder/websocket/wsjson"
)

const (
	requestTimeout = 20 * time.Second
	settleTimeout  = 15 * time.Second
)

type message map[string]any

type remoteError struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

func (e *remoteError) Error() string { return e.Code + ": " + e.Message }

type phone struct {
	t       *testing.T
	conn    *websocket.Conn
	mu      sync.Mutex
	pushes  []message
	raw     []string
	results map[string]message
	changed chan struct{}
	nextID  int
	welcome message
}

func connect(t *testing.T) *phone {
	t.Helper()
	ctx, cancel := context.WithCancel(context.Background())
	conn, _, err := websocket.Dial(ctx, "ws://"+relay.address+"/v1/ws", &websocket.DialOptions{
		HTTPHeader: http.Header{"Authorization": {"Bearer " + relay.token}},
	})
	if err != nil {
		cancel()
		t.Fatalf("connect to the relay: %v", err)
	}
	conn.SetReadLimit(64 << 20)
	p := &phone{t: t, conn: conn, results: map[string]message{}, changed: make(chan struct{})}
	go p.read(ctx)
	t.Cleanup(func() {
		_ = conn.Close(websocket.StatusNormalClosure, "")
		cancel()
	})
	p.welcome = p.must("hello", nil)
	return p
}

func (p *phone) read(ctx context.Context) {
	for {
		_, data, err := p.conn.Read(ctx)
		if err != nil {
			return
		}
		var msg message
		if json.Unmarshal(data, &msg) != nil {
			continue
		}
		p.mu.Lock()
		if msg["type"] == "result" {
			id, _ := msg["id"].(string)
			p.results[id] = msg
		} else {
			p.pushes = append(p.pushes, msg)
			p.raw = append(p.raw, string(data))
		}
		close(p.changed)
		p.changed = make(chan struct{})
		p.mu.Unlock()
	}
}

func (p *phone) request(kind string, fields map[string]any) (message, *remoteError) {
	p.t.Helper()
	p.mu.Lock()
	p.nextID++
	id := "e2e-" + strconv.Itoa(p.nextID)
	p.mu.Unlock()
	outgoing := map[string]any{"type": kind, "id": id}
	for key, value := range fields {
		outgoing[key] = value
	}
	ctx, cancel := context.WithTimeout(context.Background(), requestTimeout)
	defer cancel()
	if err := wsjson.Write(ctx, p.conn, outgoing); err != nil {
		p.t.Fatalf("send %s: %v", kind, err)
	}
	var result message
	p.waitUntil(fmt.Sprintf("the answer to %s", kind), requestTimeout, func() bool {
		result = p.results[id]
		return result != nil
	})
	if ok, _ := result["ok"].(bool); !ok {
		failure := &remoteError{}
		encoded, _ := json.Marshal(result["error"])
		_ = json.Unmarshal(encoded, failure)
		return nil, failure
	}
	data, _ := result["data"].(map[string]any)
	return data, nil
}

func (p *phone) must(kind string, fields map[string]any) message {
	p.t.Helper()
	data, failure := p.request(kind, fields)
	if failure != nil {
		p.t.Fatalf("%s failed: %v", kind, failure)
	}
	return data
}

func (p *phone) failure(kind string, fields map[string]any) *remoteError {
	p.t.Helper()
	_, failure := p.request(kind, fields)
	if failure == nil {
		p.t.Fatalf("%s should have failed", kind)
	}
	return failure
}

func (p *phone) waitUntil(what string, timeout time.Duration, done func() bool) {
	p.t.Helper()
	deadline := time.After(timeout)
	for {
		p.mu.Lock()
		finished := done()
		changed := p.changed
		p.mu.Unlock()
		if finished {
			return
		}
		select {
		case <-changed:
		case <-time.After(250 * time.Millisecond):
		case <-deadline:
			p.t.Fatalf("timed out waiting for %s", what)
		}
	}
}

func (p *phone) mark() int {
	p.mu.Lock()
	defer p.mu.Unlock()
	return len(p.pushes)
}

func (p *phone) pushAfter(mark int, kind, what string, matches func(message) bool) message {
	p.t.Helper()
	var found message
	p.waitUntil(what, settleTimeout, func() bool {
		for _, push := range p.pushes[mark:] {
			if push["type"] == kind && matches(push) {
				found = push
				return true
			}
		}
		return false
	})
	return found
}

func (p *phone) rawPushAfter(mark int, kind, what string, matches func(string) bool) {
	p.t.Helper()
	p.waitUntil(what, settleTimeout, func() bool {
		for i, push := range p.pushes[mark:] {
			if push["type"] == kind && matches(p.raw[mark+i]) {
				return true
			}
		}
		return false
	})
}

func (p *phone) eventually(kind, what string, matches func(message) bool) message {
	p.t.Helper()
	var found message
	p.waitUntil(what, settleTimeout, func() bool {
		for i := len(p.pushes) - 1; i >= 0; i-- {
			if p.pushes[i]["type"] == kind {
				found = p.pushes[i]
				return matches(found)
			}
		}
		return false
	})
	return found
}

func (p *phone) workspaceSatisfies(id, what string, matches func(message) bool) message {
	p.t.Helper()
	var found message
	p.eventually("workspaces", what, func(push message) bool {
		found = find(list(push["workspaces"]), "id", id)
		return found != nil && matches(found)
	})
	return found
}

func (p *phone) workspaceGone(id string) {
	p.t.Helper()
	p.eventually("workspaces", "workspace "+id+" to disappear", func(push message) bool {
		return find(list(push["workspaces"]), "id", id) == nil
	})
}

func list(value any) []message {
	items, _ := value.([]any)
	out := make([]message, 0, len(items))
	for _, item := range items {
		if object, ok := item.(map[string]any); ok {
			out = append(out, object)
		}
	}
	return out
}

func find(items []message, key, value string) message {
	for _, item := range items {
		if item[key] == value {
			return item
		}
	}
	return nil
}

func object(value any) message {
	m, _ := value.(map[string]any)
	return m
}

func text(value any) string {
	s, _ := value.(string)
	return s
}

func number(value any) float64 {
	n, _ := value.(float64)
	return n
}

func flag(value any) bool {
	b, _ := value.(bool)
	return b
}
