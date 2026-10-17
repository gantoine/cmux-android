package server

import (
	"context"

	"github.com/gantoine/muxer/relay/internal/cmux"
)

var WorkspaceEventNames = []string{
	"workspace.created",
	"workspace.closed",
	"workspace.renamed",
	"workspace.reordered",
	"workspace.action",
	"surface.created",
	"surface.closed",
	"surface.action",
}

func (s *Server) HandleEvent(_ context.Context, event cmux.Event) {
	if event.Name == "workspace.action" {
		s.organizing.invalidate()
	}
	s.workspaces.refresh()
}

func (s *Server) StreamResumed(context.Context) {
	s.organizing.invalidate()
	s.workspaces.refresh()
}
