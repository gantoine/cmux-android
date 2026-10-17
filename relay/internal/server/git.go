package server

import (
	"context"
	"errors"
	"time"

	"github.com/gantoine/muxer/relay/internal/cmux"
)

const failedGitReadsBeforeClearing = 3

type gitInfo struct {
	bySurface   map[string]cmux.TerminalGit
	fetchedAt   time.Time
	failedReads int
}

func (g *gitInfo) refresh(ctx context.Context, s *Server) {
	if !s.methods.supports(cmux.MethodTerminalGit) {
		g.bySurface = nil
		return
	}
	if time.Since(g.fetchedAt) < s.options.PollInterval.Git {
		return
	}
	g.fetchedAt = time.Now()
	terminals, err := guarded(s, cmux.MethodTerminalGit, errMethodMissing, func() ([]cmux.TerminalGit, error) {
		return s.cmux.TerminalGit(ctx)
	})
	if errors.Is(err, errMethodMissing) {
		g.bySurface = nil
		return
	}
	if err != nil {
		s.logger.Warn("could not read terminal git state", "error", err)
		g.failedReads++
		if g.failedReads >= failedGitReadsBeforeClearing {
			g.bySurface = nil
		}
		return
	}
	g.failedReads = 0
	fetched := make(map[string]cmux.TerminalGit, len(terminals))
	for _, terminal := range terminals {
		fetched[terminal.SurfaceID] = terminal
	}
	g.bySurface = fetched
}

func (g *gitInfo) apply(workspaces []cmux.Workspace) {
	for _, workspace := range workspaces {
		for index := range workspace.Terminals {
			terminal := &workspace.Terminals[index]
			git, ok := g.bySurface[terminal.ID]
			if !ok {
				continue
			}
			terminal.GitBranch = git.GitBranch
			terminal.GitDirty = git.GitDirty
			terminal.ListeningPorts = git.ListeningPorts
		}
	}
}
