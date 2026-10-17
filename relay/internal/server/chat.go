package server

import (
	"context"
	"errors"
	"time"

	"github.com/gantoine/muxer/relay/internal/claude"
	"github.com/gantoine/muxer/relay/internal/cmux"
)

const (
	agentKindClaude         = "claude"
	maxInitialEntries       = 300
	initialTranscriptWindow = 4 << 20
)

type chatWatch struct {
	sessionID  string
	cwd        string
	transcript *claude.Transcript
	tail       *claude.Tail
	sent       bool
	sentEmpty  bool
}

func (s *session) watchChat(ctx context.Context, current *attachment) {
	var watch chatWatch
	announced := false
	for {
		callCtx, cancel := context.WithTimeout(ctx, callTimeout)
		sessionID, cwd, err := s.server.claudeSession(callCtx, current.workspaceID, current.surfaceID)
		cancel()
		if ctx.Err() != nil {
			return
		}
		if err == nil {
			if !announced || sessionID != watch.sessionID {
				announced = true
				watch = chatWatch{sessionID: sessionID, cwd: cwd}
				s.sendChatSession(current, sessionID)
			}
			if sessionID != "" {
				s.followTranscript(current, &watch)
			}
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(s.server.options.PollInterval.Chat):
		}
	}
}

func (s *Server) agentSession(ctx context.Context, workspaceID, surfaceID string) (cmux.AgentSession, error) {
	if running, ok := s.options.ClaudeSession(surfaceID); ok && running.SessionID != "" {
		return cmux.AgentSession{Kind: agentKindClaude, SessionID: running.SessionID, Cwd: running.Cwd}, nil
	}
	agent, err := guarded(s, cmux.MethodAgentSession, errMethodMissing, func() (cmux.AgentSession, error) {
		return s.cmux.AgentSession(ctx, workspaceID, surfaceID)
	})
	if errors.Is(err, errMethodMissing) {
		return cmux.AgentSession{}, nil
	}
	return agent, err
}

func (s *Server) claudeSession(ctx context.Context, workspaceID, surfaceID string) (sessionID, cwd string, err error) {
	agent, err := s.agentSession(ctx, workspaceID, surfaceID)
	if err != nil || agent.Kind != agentKindClaude {
		return "", "", err
	}
	return agent.SessionID, agent.Cwd, nil
}

func (s *session) followTranscript(current *attachment, watch *chatWatch) {
	if watch.tail == nil {
		path, ok := claude.FindTranscript(s.server.options.ClaudeConfigDir, watch.cwd, watch.sessionID)
		if !ok {
			if !watch.sentEmpty {
				watch.sentEmpty = true
				s.sendChat(current, watch.sessionID, nil, true)
			}
			return
		}
		watch.tail = claude.NewTail(path, initialTranscriptWindow)
		watch.transcript = claude.NewTranscript()
	}
	lines, restarted, err := watch.tail.Lines()
	if err != nil {
		s.server.logger.Warn("could not read the Claude transcript", "error", err)
		return
	}
	if restarted {
		watch.transcript = claude.NewTranscript()
		watch.sent = false
	}
	var changed []claude.Entry
	for _, line := range lines {
		changed = append(changed, watch.transcript.Feed(line)...)
	}
	if !watch.sent {
		entries := watch.transcript.Entries()
		if len(entries) > maxInitialEntries {
			entries = entries[len(entries)-maxInitialEntries:]
		}
		watch.sent = true
		s.sendChat(current, watch.sessionID, entries, true)
		return
	}
	if len(changed) > 0 {
		s.sendChat(current, watch.sessionID, changed, false)
	}
}

func (s *session) sendChatSession(current *attachment, sessionID string) {
	var id any
	if sessionID != "" {
		id = sessionID
	}
	s.send(map[string]any{"type": "chat_session", "surface_id": current.surfaceID, "session_id": id})
}

func (s *session) sendChat(current *attachment, sessionID string, entries []claude.Entry, reset bool) {
	if entries == nil {
		entries = []claude.Entry{}
	}
	s.send(map[string]any{
		"type":       "chat",
		"surface_id": current.surfaceID,
		"session_id": sessionID,
		"reset":      reset,
		"entries":    entries,
	})
}
