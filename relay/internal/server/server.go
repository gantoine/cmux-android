package server

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"strings"
	"time"

	"github.com/coder/websocket"

	"github.com/gantoine/cmux-android/relay/internal/cmux"
	"github.com/gantoine/cmux-android/relay/internal/devices"
	"github.com/gantoine/cmux-android/relay/internal/notify"
)

const ProtocolVersion = 1

type Cmux interface {
	Identify(ctx context.Context) (cmux.Identity, error)
	Workspaces(ctx context.Context) ([]cmux.Workspace, error)
	Replay(ctx context.Context, workspaceID, surfaceID string) (cmux.Replay, error)
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
}

type Options struct {
	MacName      string
	RelayVersion string
	PollInterval Intervals
}

type Server struct {
	cmux    Cmux
	devices *devices.Store
	hub     *notify.Hub
	options Options
	logger  *slog.Logger
}

func New(cmuxAPI Cmux, store *devices.Store, hub *notify.Hub, options Options, logger *slog.Logger) *Server {
	if options.PollInterval == (Intervals{}) {
		options.PollInterval = DefaultIntervals
	}
	return &Server{cmux: cmuxAPI, devices: store, hub: hub, options: options, logger: logger}
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
		"app":              "cmux-android-relay",
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
		writeJSON(w, http.StatusUnauthorized, errorBody("invalid_code", "That pairing code is wrong or expired. Run `cmux-relay pair` on your Mac for a new one."))
		return
	}
	if err != nil {
		s.logger.Error("pairing failed", "error", err)
		writeJSON(w, http.StatusInternalServerError, errorBody("internal", "Pairing failed on the Mac. Check the relay log."))
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
		writeJSON(w, http.StatusUnauthorized, errorBody("unauthorized", "This device is not paired with the Mac, or it was removed."))
		return
	}
	conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{CompressionMode: websocket.CompressionContextTakeover})
	if err != nil {
		return
	}
	conn.SetReadLimit(1 << 20)
	session := newSession(s, conn, device)
	session.run(r.Context())
}

func writeJSON(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(body)
}

func errorBody(code, message string) map[string]any {
	return map[string]any{"error": map[string]string{"code": code, "message": message}}
}
