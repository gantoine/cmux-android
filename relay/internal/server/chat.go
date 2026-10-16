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
	cmuxTracksAgents := true
	for {
		var agent cmux.AgentSession
		var err error
		if cmuxTracksAgents {
			callCtx, cancel := context.WithTimeout(ctx, callTimeout)
			agent, err = s.server.cmux.AgentSession(callCtx, current.workspaceID, current.surfaceID)
			cancel()
			if ctx.Err() != nil {
				return
			}
			if cmux.ErrorCode(err) == methodNotFoundErrorCode {
				cmuxTracksAgents, agent, err = false, cmux.AgentSession{}, nil
			}
		}
		if err == nil {
			sessionID, cwd := "", ""
			if agent.Kind == agentKindClaude && agent.SessionID != "" {
				sessionID, cwd = agent.SessionID, agent.Cwd
			} else if running, ok := s.server.options.ClaudeSession(current.surfaceID); ok {
				sessionID, cwd = running.SessionID, running.Cwd
			}
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
