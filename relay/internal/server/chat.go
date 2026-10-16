package server

import (
	"context"
	"time"

	"github.com/gantoine/muxer/relay/internal/claude"
	"github.com/gantoine/muxer/relay/internal/cmux"
)

const (
	agentKindClaude   = "claude"
	maxInitialEntries = 300
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
		sessionID, cwd, err := s.claudeSession(callCtx, current)
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

func (s *session) claudeSession(ctx context.Context, current *attachment) (sessionID, cwd string, err error) {
	var agent cmux.AgentSession
	if s.server.methods.supports(cmux.MethodAgentSession) {
		agent, err = s.server.cmux.AgentSession(ctx, current.workspaceID, current.surfaceID)
		if s.server.methods.observe(cmux.MethodAgentSession, err) {
			agent, err = cmux.AgentSession{}, nil
		}
		if err != nil {
			return "", "", err
		}
	}
	if agent.Kind == agentKindClaude && agent.SessionID != "" {
		return agent.SessionID, agent.Cwd, nil
	}
	if running, ok := s.server.options.ClaudeSession(current.surfaceID); ok {
		return running.SessionID, running.Cwd, nil
	}
	return "", "", nil
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
		watch.tail = claude.NewTail(path)
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
