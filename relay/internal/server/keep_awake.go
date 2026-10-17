package server

import (
	"context"
	"encoding/json"

	"github.com/gantoine/muxer/relay/internal/cmux"
)

var errKeepAwakeUnsupported = unsupported("keep the computer awake")

func (s *Server) readKeepAwake(ctx context.Context) (map[string]bool, error) {
	enabled, err := guarded(s, cmux.MethodKeepAwake, errKeepAwakeUnsupported, func() (bool, error) {
		return s.cmux.KeepAwake(ctx)
	})
	if err != nil {
		return nil, err
	}
	return map[string]bool{"enabled": enabled}, nil
}

func (s *Server) setKeepAwake(ctx context.Context, enabled bool) (map[string]bool, error) {
	enabled, err := guarded(s, cmux.MethodSetKeepAwake, errKeepAwakeUnsupported, func() (bool, error) {
		return s.cmux.SetKeepAwake(ctx, enabled)
	})
	if err != nil {
		return nil, err
	}
	s.keepAwake.refresh()
	return map[string]bool{"enabled": enabled}, nil
}

func (s *Server) pollKeepAwake(ctx context.Context) ([]byte, bool) {
	enabled, ok := probe(s, cmux.MethodKeepAwake, func() (bool, error) { return s.cmux.KeepAwake(ctx) })
	if !ok {
		return nil, false
	}
	encoded, err := json.Marshal(map[string]any{"type": "keep_awake", "enabled": enabled})
	return encoded, err == nil
}
