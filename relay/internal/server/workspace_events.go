package server

import (
	"context"

	"github.com/gantoine/muxer/relay/internal/cmux"
)

var WorkspaceEventCategories = []string{"workspace", "surface"}

var workspaceEvents = map[string]bool{
	"workspace.created":   true,
	"workspace.closed":    true,
	"workspace.renamed":   true,
	"workspace.reordered": true,
	"workspace.action":    true,
	"surface.created":     true,
	"surface.closed":      true,
	"surface.action":      true,
}

func (s *Server) HandleEvent(_ context.Context, event cmux.Event) {
	if !workspaceEvents[event.Name] {
		return
	}
	if event.Name == "workspace.action" {
		s.organizing.invalidate()
	}
	s.workspaces.refresh()
}

func (s *Server) StreamResumed(context.Context) {
	s.organizing.invalidate()
	s.workspaces.refresh()
}
