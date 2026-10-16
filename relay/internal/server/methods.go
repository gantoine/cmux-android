package server

import (
	"context"
	"sync"

	"github.com/gantoine/muxer/relay/internal/cmux"
)

type methodSupport struct {
	mu      sync.Mutex
	listed  map[string]bool
	missing map[string]bool
}

func (m *methodSupport) load(ctx context.Context, api Cmux) {
	methods, err := api.Methods(ctx)
	if err != nil && !cmux.IsMethodNotFound(err) {
		return
	}
	var listed map[string]bool
	if err == nil && len(methods) > 0 {
		listed = make(map[string]bool, len(methods))
		for _, method := range methods {
			listed[method] = true
		}
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	m.listed = listed
	m.missing = nil
}

func (m *methodSupport) supports(method string) bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.missing[method] {
		return false
	}
	return m.listed == nil || m.listed[method]
}

func (m *methodSupport) observe(method string, err error) bool {
	if !cmux.IsMethodNotFound(err) {
		return false
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.missing == nil {
		m.missing = map[string]bool{}
	}
	m.missing[method] = true
	return true
}
