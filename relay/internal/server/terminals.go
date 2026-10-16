package server

import (
	"context"
	"encoding/json"
	"os"
	"strings"
	"time"

	"github.com/gantoine/muxer/relay/internal/cmux"
)

const (
	maxTerminalTitleBytes = 256
	defaultShell          = "/bin/zsh"
	lastSurfaceErrorCode  = "invalid_state"
)

type terminalRequest struct {
	WorkspaceID string `json:"workspace_id"`
	SurfaceID   string `json:"surface_id"`
	Title       string `json:"title"`
	Force       bool   `json:"force"`
}

var errTerminalActionsUnsupported = &requestError{Code: "unsupported", Message: "This version of cmux can't do that to a single terminal. Update cmux."}

var errLastTerminal = &requestError{Code: "last_terminal", Message: "This is the workspace's only terminal. Close the workspace instead."}

func parseTerminalRequest(kind string, raw json.RawMessage) (terminalRequest, error) {
	var request terminalRequest
	if err := json.Unmarshal(raw, &request); err != nil || request.WorkspaceID == "" || request.SurfaceID == "" {
		return request, &requestError{Code: "invalid_params", Message: kind + " needs workspace_id and surface_id."}
	}
	return request, nil
}

func (s *Server) terminalAction(method string, call func() error) error {
	if !s.methods.supports(method) {
		return errTerminalActionsUnsupported
	}
	err := call()
	if s.methods.observe(method, err) {
		return errTerminalActionsUnsupported
	}
	s.workspaces.refresh()
	return err
}

func (s *Server) closeTerminal(ctx context.Context, request terminalRequest) error {
	err := s.terminalAction(cmux.MethodCloseTerminal, func() error {
		return s.cmux.CloseTerminal(ctx, request.WorkspaceID, request.SurfaceID, request.Force)
	})
	if cmux.ErrorCode(err) == lastSurfaceErrorCode {
		return errLastTerminal
	}
	return err
}

func (s *Server) renameTerminal(ctx context.Context, request terminalRequest) error {
	title := strings.TrimSpace(request.Title)
	if len(title) > maxTerminalTitleBytes {
		return &requestError{Code: "invalid_params", Message: "A terminal name can be up to 256 bytes."}
	}
	return s.terminalAction(cmux.MethodRenameTerminal, func() error {
		return s.cmux.RenameTerminal(ctx, request.WorkspaceID, request.SurfaceID, title)
	})
}

func (s *Server) restartTerminal(ctx context.Context, request terminalRequest) error {
	return s.terminalAction(cmux.MethodRestartTerminal, func() error {
		return s.cmux.RestartTerminal(ctx, request.WorkspaceID, request.SurfaceID, loginShell())
	})
}

func loginShell() string {
	if shell := os.Getenv("SHELL"); strings.HasPrefix(shell, "/") {
		return shell
	}
	return defaultShell
}

func (s *session) watchInputState(ctx context.Context, current *attachment) {
	var last *cmux.InputState
	for s.server.methods.supports(cmux.MethodInputState) {
		callCtx, cancel := context.WithTimeout(ctx, callTimeout)
		state, err := s.server.cmux.InputState(callCtx, current.workspaceID, current.surfaceID)
		cancel()
		if ctx.Err() != nil || s.server.methods.observe(cmux.MethodInputState, err) {
			return
		}
		if err == nil && (last == nil || *last != state) {
			last = &state
			s.send(map[string]any{
				"type":             "input_state",
				"surface_id":       current.surfaceID,
				"state":            state.State,
				"blocks_typing":    state.BlocksTyping,
				"waiting_on_human": state.WaitingOnHuman,
			})
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(s.server.options.PollInterval.InputState):
		}
	}
}
