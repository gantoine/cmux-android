package server

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/coder/websocket"

	"github.com/gantoine/muxer/relay/internal/agents"
	"github.com/gantoine/muxer/relay/internal/approvals"
	"github.com/gantoine/muxer/relay/internal/claude"
	"github.com/gantoine/muxer/relay/internal/cmux"
	"github.com/gantoine/muxer/relay/internal/devices"
	"github.com/gantoine/muxer/relay/internal/notify"
)

const ProtocolVersion = 1

type Cmux interface {
	Identify(ctx context.Context) (cmux.Identity, error)
	Workspaces(ctx context.Context) (cmux.WorkspaceList, error)
	Replay(ctx context.Context, workspaceID, surfaceID string) (cmux.Replay, error)
	History(ctx context.Context, workspaceID, surfaceID string, maxRows int) (cmux.Replay, error)
	Input(ctx context.Context, workspaceID, surfaceID, text string) error
	SendKey(ctx context.Context, workspaceID, surfaceID, key string) error
	Paste(ctx context.Context, workspaceID, surfaceID, text string, submit bool) error
	Scroll(ctx context.Context, workspaceID, surfaceID string, deltaLines int) error
	SetViewport(ctx context.Context, workspaceID, surfaceID string, viewport cmux.Viewport) error
	ClearViewport(ctx context.Context, workspaceID, surfaceID, clientID string, generation int) error
	CreateWorkspace(ctx context.Context, title, cwd string) (cmux.CreatedWorkspace, error)
	CloseWorkspace(ctx context.Context, workspaceID string, force bool) error
	RenameWorkspace(ctx context.Context, workspaceID, title string) error
	CreateTerminal(ctx context.Context, workspaceID string) (string, error)
	AgentSession(ctx context.Context, workspaceID, surfaceID string) (cmux.AgentSession, error)
	KeepAwake(ctx context.Context) (bool, error)
	TerminalGit(ctx context.Context) ([]cmux.TerminalGit, error)
	SetKeepAwake(ctx context.Context, enabled bool) (bool, error)
	Methods(ctx context.Context) ([]string, error)
}

type Approvals interface {
	Pending() []approvals.Approval
	Subscribe() (<-chan struct{}, func())
	Answer(ctx context.Context, id string, answer approvals.Answer) error
}

type Agents interface {
	Retries() []agents.Retry
	Busy() []string
	SubscribeActivity() (<-chan struct{}, func())
	SubscribeMessages(surfaceID string) (<-chan struct{}, func())
	Send(ctx context.Context, surfaceID, deviceName, body string) (cmux.AgentMessage, error)
	Messages(ctx context.Context, surfaceID string) ([]cmux.AgentMessage, error)
}

type Options struct {
	MacName         string
	RelayVersion    string
	PollInterval    Intervals
	ClaudeConfigDir string
	ClaudeSession   func(surfaceID string) (claude.SessionRef, bool)
	Approvals       Approvals
	Agents          Agents
}

type Server struct {
	cmux    Cmux
	devices *devices.Store
	hub     *notify.Hub
	options Options
	logger  *slog.Logger

	ctx    context.Context
	cancel context.CancelFunc

	mu       sync.Mutex
	closed   bool
	sessions sync.WaitGroup

	viewportGeneration atomic.Int64

	methods    methodSupport
	workspaces *feed
	keepAwake  *feed
}

func New(cmuxAPI Cmux, store *devices.Store, hub *notify.Hub, options Options, logger *slog.Logger) *Server {
	if options.PollInterval == (Intervals{}) {
		options.PollInterval = DefaultIntervals
	}
	if options.PollInterval.Chat == 0 {
		options.PollInterval.Chat = DefaultIntervals.Chat
	}
	if options.PollInterval.Git == 0 {
		options.PollInterval.Git = DefaultIntervals.Git
	}
	if options.PollInterval.KeepAwake == 0 {
		options.PollInterval.KeepAwake = DefaultIntervals.KeepAwake
	}
	if options.ClaudeConfigDir == "" {
		options.ClaudeConfigDir = claude.ConfigDir()
	}
	if options.ClaudeSession == nil {
		configDir := options.ClaudeConfigDir
		options.ClaudeSession = func(surfaceID string) (claude.SessionRef, bool) {
			processes := claude.SystemProcesses{}
			return claude.SessionForSurface(claude.RunningSessions(configDir, processes), surfaceID, processes)
		}
	}
	ctx, cancel := context.WithCancel(context.Background())
	server := &Server{cmux: cmuxAPI, devices: store, hub: hub, options: options, logger: logger, ctx: ctx, cancel: cancel}
	server.viewportGeneration.Store(time.Now().UnixMilli())
	server.workspaces = newFeed(options.PollInterval.Workspaces, server.pollWorkspaces(&gitInfo{}))
	server.keepAwake = newFeed(options.PollInterval.KeepAwake, server.pollKeepAwake)
	return server
}

func (s *Server) nextViewportGeneration() int {
	return int(s.viewportGeneration.Add(1))
}

func (s *Server) Close(ctx context.Context) {
	s.mu.Lock()
	s.closed = true
	s.mu.Unlock()
	s.cancel()
	drained := make(chan struct{})
	go func() {
		s.sessions.Wait()
		close(drained)
	}()
	select {
	case <-drained:
	case <-ctx.Done():
	}
}

func (s *Server) trackSession() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		return false
	}
	s.sessions.Add(1)
	return true
}

func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /v1/health", s.handleHealth)
	mux.HandleFunc("POST /v1/pair", s.handlePair)
	mux.HandleFunc("GET /v1/ws", s.handleWebSocket)
	return mux
}

func (s *Server) handleHealth(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{
		"app":              "muxer",
		"mac_name":         s.options.MacName,
		"relay_version":    s.options.RelayVersion,
		"protocol_version": ProtocolVersion,
	})
}

func (s *Server) handlePair(w http.ResponseWriter, r *http.Request) {
	var request struct {
		Code       string `json:"code"`
		DeviceName string `json:"device_name"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4096)).Decode(&request); err != nil {
		writeJSON(w, http.StatusBadRequest, errorBody("invalid_request", "Send a JSON body with code and device_name."))
		return
	}
	device, token, err := s.devices.Pair(request.Code, request.DeviceName)
	if errors.Is(err, devices.ErrInvalidPairingCode) {
		time.Sleep(500 * time.Millisecond)
		writeJSON(w, http.StatusUnauthorized, errorBody("invalid_code", "That pairing code is wrong or expired. Run `muxer pair` on your computer for a new one."))
		return
	}
	if err != nil {
		s.logger.Error("pairing failed", "error", err)
		writeJSON(w, http.StatusInternalServerError, errorBody("internal", "Pairing failed on the computer. Check the relay log."))
		return
	}
	s.logger.Info("device paired", "device_id", device.ID, "name", device.Name)
	writeJSON(w, http.StatusOK, map[string]any{
		"token":            token,
		"device_id":        device.ID,
		"mac_name":         s.options.MacName,
		"protocol_version": ProtocolVersion,
	})
}

func (s *Server) handleWebSocket(w http.ResponseWriter, r *http.Request) {
	token, found := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
	if !found {
		token = ""
	}
	device, ok := s.devices.Authenticate(token)
	if !ok {
		writeJSON(w, http.StatusUnauthorized, errorBody("unauthorized", "This device is not paired with the computer, or it was removed."))
		return
	}
	if !s.trackSession() {
		writeJSON(w, http.StatusServiceUnavailable, errorBody("shutting_down", "The relay is restarting. Try again in a moment."))
		return
	}
	defer s.sessions.Done()
	conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{CompressionMode: websocket.CompressionContextTakeover})
	if err != nil {
		return
	}
	conn.SetReadLimit(1 << 20)
	session := newSession(s, conn, device)
	session.run()
}

func writeJSON(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(body)
}

func errorBody(code, message string) map[string]any {
	return map[string]any{"error": map[string]string{"code": code, "message": message}}
}
