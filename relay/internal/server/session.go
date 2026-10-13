package server

import (
	"context"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/coder/websocket"
	"github.com/coder/websocket/wsjson"

	"github.com/gantoine/cmux-android/relay/internal/cmux"
	"github.com/gantoine/cmux-android/relay/internal/devices"
	"github.com/gantoine/cmux-android/relay/internal/screen"
)

type Intervals struct {
	Fast       time.Duration
	Idle       time.Duration
	Slow       time.Duration
	IdleAfter  time.Duration
	SlowAfter  time.Duration
	Workspaces time.Duration
}

var DefaultIntervals = Intervals{
	Fast:       80 * time.Millisecond,
	Idle:       250 * time.Millisecond,
	Slow:       time.Second,
	IdleAfter:  3 * time.Second,
	SlowAfter:  time.Minute,
	Workspaces: 2 * time.Second,
}

const (
	writeTimeout          = 10 * time.Second
	pingInterval          = 30 * time.Second
	revocationCheck       = 10 * time.Second
	maxConsecutiveErrors  = 10
	maxInputBytes         = 64 * 1024
	callTimeout           = 15 * time.Second
	terminalGoneErrorCode = "not_found"
)

type requestError struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

func (e *requestError) Error() string { return e.Code + ": " + e.Message }

type attachment struct {
	workspaceID  string
	surfaceID    string
	cancel       context.CancelFunc
	done         chan struct{}
	wake         chan struct{}
	resync       atomic.Bool
	lastActivity atomic.Int64
	fitted       bool
}

type session struct {
	server   *Server
	conn     *websocket.Conn
	device   devices.Device
	clientID string

	ctx    context.Context
	cancel context.CancelFunc

	mu                 sync.Mutex
	attached           *attachment
	viewportGeneration int
	started            bool
	workspacesWake     chan struct{}
}

func newSession(server *Server, conn *websocket.Conn, device devices.Device) *session {
	return &session{
		server:         server,
		conn:           conn,
		device:         device,
		clientID:       "cmux-android-" + device.ID,
		workspacesWake: make(chan struct{}, 1),
	}
}

func (s *session) run(parent context.Context) {
	s.ctx, s.cancel = context.WithCancel(context.WithoutCancel(parent))
	defer s.cancel()
	defer s.conn.CloseNow()
	go s.keepAlive()
	go s.watchRevocation()

	for {
		var raw json.RawMessage
		if err := wsjson.Read(s.ctx, s.conn, &raw); err != nil {
			break
		}
		s.handle(raw)
	}
	s.detach()
}

func (s *session) keepAlive() {
	ticker := time.NewTicker(pingInterval)
	defer ticker.Stop()
	for {
		select {
		case <-s.ctx.Done():
			return
		case <-ticker.C:
			ctx, cancel := context.WithTimeout(s.ctx, writeTimeout)
			err := s.conn.Ping(ctx)
			cancel()
			if err != nil {
				s.cancel()
				return
			}
		}
	}
}

func (s *session) watchRevocation() {
	ticker := time.NewTicker(revocationCheck)
	defer ticker.Stop()
	for {
		select {
		case <-s.ctx.Done():
			return
		case <-ticker.C:
			if s.server.devices.IsRevoked(s.device.ID) {
				_ = s.conn.Close(websocket.StatusPolicyViolation, "device removed")
				s.cancel()
				return
			}
		}
	}
}

func (s *session) send(message any) {
	ctx, cancel := context.WithTimeout(s.ctx, writeTimeout)
	defer cancel()
	if err := wsjson.Write(ctx, s.conn, message); err != nil {
		s.cancel()
	}
}

type envelope struct {
	Type string `json:"type"`
	ID   string `json:"id"`
}

func (s *session) handle(raw json.RawMessage) {
	var message envelope
	if err := json.Unmarshal(raw, &message); err != nil {
		s.reply("", nil, &requestError{Code: "invalid_message", Message: "Messages must be JSON objects with a type."})
		return
	}
	data, err := s.dispatch(message.Type, raw)
	if message.ID == "" && err == nil {
		return
	}
	s.reply(message.ID, data, err)
}

func (s *session) reply(id string, data any, err error) {
	result := map[string]any{"type": "result", "id": id, "ok": err == nil}
	if data != nil {
		result["data"] = data
	}
	if err != nil {
		var reqErr *requestError
		var rpcErr *cmux.RPCError
		switch {
		case errors.As(err, &reqErr):
			result["error"] = reqErr
		case errors.As(err, &rpcErr):
			result["error"] = requestError{Code: rpcErr.Code, Message: rpcErr.Message}
		default:
			result["error"] = requestError{Code: "cmux_unavailable", Message: err.Error()}
		}
	}
	s.send(result)
}

func (s *session) dispatch(kind string, raw json.RawMessage) (any, error) {
	ctx, cancel := context.WithTimeout(s.ctx, callTimeout)
	defer cancel()
	switch kind {
	case "hello":
		var hello struct {
			NotificationsAfter int64 `json:"notifications_after"`
		}
		_ = json.Unmarshal(raw, &hello)
		return s.hello(ctx, hello.NotificationsAfter)
	case "refresh":
		s.wakeWorkspaces()
		return nil, nil
	case "attach":
		var request struct {
			WorkspaceID string `json:"workspace_id"`
			SurfaceID   string `json:"surface_id"`
			Fit         *struct {
				Columns int `json:"columns"`
				Rows    int `json:"rows"`
			} `json:"fit"`
		}
		if err := json.Unmarshal(raw, &request); err != nil || request.WorkspaceID == "" || request.SurfaceID == "" {
			return nil, &requestError{Code: "invalid_params", Message: "attach needs workspace_id and surface_id."}
		}
		s.attach(request.WorkspaceID, request.SurfaceID)
		if request.Fit != nil {
			return nil, s.fit(ctx, request.Fit.Columns, request.Fit.Rows)
		}
		return nil, nil
	case "detach":
		s.detach()
		return nil, nil
	case "resync":
		if current := s.current(); current != nil {
			current.resync.Store(true)
			s.wakeTerminal(current)
		}
		return nil, nil
	case "input":
		var request struct {
			Text string `json:"text"`
		}
		_ = json.Unmarshal(raw, &request)
		return nil, s.withTerminal(func(current *attachment) error {
			if request.Text == "" || len(request.Text) > maxInputBytes {
				return &requestError{Code: "invalid_params", Message: "input needs 1 to 65536 bytes of text."}
			}
			return s.server.cmux.Input(ctx, current.workspaceID, current.surfaceID, request.Text)
		})
	case "key":
		var request struct {
			Key string `json:"key"`
		}
		_ = json.Unmarshal(raw, &request)
		return nil, s.withTerminal(func(current *attachment) error {
			if request.Key == "" || len(request.Key) > 64 {
				return &requestError{Code: "invalid_params", Message: "key needs a key name such as ctrl-c or up."}
			}
			return s.server.cmux.SendKey(ctx, current.workspaceID, current.surfaceID, request.Key)
		})
	case "paste":
		var request struct {
			Text   string `json:"text"`
			Submit bool   `json:"submit"`
		}
		_ = json.Unmarshal(raw, &request)
		return nil, s.withTerminal(func(current *attachment) error {
			if request.Text == "" || len(request.Text) > maxInputBytes {
				return &requestError{Code: "invalid_params", Message: "paste needs 1 to 65536 bytes of text."}
			}
			return s.server.cmux.Paste(ctx, current.workspaceID, current.surfaceID, request.Text, request.Submit)
		})
	case "scroll":
		var request struct {
			Lines int `json:"lines"`
		}
		_ = json.Unmarshal(raw, &request)
		return nil, s.withTerminal(func(current *attachment) error {
			return s.server.cmux.Scroll(ctx, current.workspaceID, current.surfaceID, request.Lines)
		})
	case "fit":
		var request struct {
			Columns int `json:"columns"`
			Rows    int `json:"rows"`
		}
		_ = json.Unmarshal(raw, &request)
		return nil, s.fit(ctx, request.Columns, request.Rows)
	case "unfit":
		return nil, s.unfit(ctx)
	case "create_workspace":
		var request struct {
			Title string `json:"title"`
			Cwd   string `json:"cwd"`
		}
		_ = json.Unmarshal(raw, &request)
		created, err := s.server.cmux.CreateWorkspace(ctx, request.Title, request.Cwd)
		s.wakeWorkspaces()
		return created, err
	case "close_workspace":
		var request struct {
			WorkspaceID string `json:"workspace_id"`
			Force       bool   `json:"force"`
		}
		if err := json.Unmarshal(raw, &request); err != nil || request.WorkspaceID == "" {
			return nil, &requestError{Code: "invalid_params", Message: "close_workspace needs workspace_id."}
		}
		err := s.server.cmux.CloseWorkspace(ctx, request.WorkspaceID, request.Force)
		s.wakeWorkspaces()
		return nil, err
	case "rename_workspace":
		var request struct {
			WorkspaceID string `json:"workspace_id"`
			Title       string `json:"title"`
		}
		if err := json.Unmarshal(raw, &request); err != nil || request.WorkspaceID == "" || strings.TrimSpace(request.Title) == "" {
			return nil, &requestError{Code: "invalid_params", Message: "rename_workspace needs workspace_id and title."}
		}
		err := s.server.cmux.RenameWorkspace(ctx, request.WorkspaceID, request.Title)
		s.wakeWorkspaces()
		return nil, err
	case "create_terminal":
		var request struct {
			WorkspaceID string `json:"workspace_id"`
		}
		if err := json.Unmarshal(raw, &request); err != nil || request.WorkspaceID == "" {
			return nil, &requestError{Code: "invalid_params", Message: "create_terminal needs workspace_id."}
		}
		terminalID, err := s.server.cmux.CreateTerminal(ctx, request.WorkspaceID)
		s.wakeWorkspaces()
		return map[string]string{"surface_id": terminalID}, err
	default:
		return nil, &requestError{Code: "unknown_type", Message: fmt.Sprintf("Unknown message type %q.", kind)}
	}
}

func (s *session) hello(ctx context.Context, notificationsAfter int64) (any, error) {
	identity, err := s.server.cmux.Identify(ctx)
	if err != nil {
		return nil, err
	}
	s.mu.Lock()
	alreadyStarted := s.started
	s.started = true
	s.mu.Unlock()
	welcome := map[string]any{
		"mac_name":         s.server.options.MacName,
		"relay_version":    s.server.options.RelayVersion,
		"protocol_version": ProtocolVersion,
		"cmux_version":     identity.Version,
		"device_id":        s.device.ID,
	}
	if s.server.hub != nil {
		welcome["notification_seq"] = s.server.hub.LatestSeq()
	}
	if !alreadyStarted {
		go s.watchWorkspaces()
		go s.forwardNotifications(notificationsAfter)
	}
	return welcome, nil
}

func (s *session) watchWorkspaces() {
	var lastHash [32]byte
	for {
		ctx, cancel := context.WithTimeout(s.ctx, callTimeout)
		workspaces, err := s.server.cmux.Workspaces(ctx)
		cancel()
		if err == nil {
			if workspaces == nil {
				workspaces = []cmux.Workspace{}
			}
			encoded, _ := json.Marshal(workspaces)
			if hash := sha256.Sum256(encoded); hash != lastHash {
				lastHash = hash
				s.send(map[string]any{"type": "workspaces", "workspaces": json.RawMessage(encoded)})
			}
		} else {
			s.server.logger.Warn("could not list workspaces", "error", err)
		}
		select {
		case <-s.ctx.Done():
			return
		case <-s.workspacesWake:
		case <-time.After(s.server.options.PollInterval.Workspaces):
		}
	}
}

func (s *session) forwardNotifications(after int64) {
	if s.server.hub == nil {
		return
	}
	updates, missed, unsubscribe := s.server.hub.Subscribe(after)
	defer unsubscribe()
	for _, notification := range missed {
		s.send(map[string]any{"type": "notification", "notification": notification})
	}
	for {
		select {
		case <-s.ctx.Done():
			return
		case notification := <-updates:
			s.send(map[string]any{"type": "notification", "notification": notification})
		}
	}
}

func (s *session) wakeWorkspaces() {
	select {
	case s.workspacesWake <- struct{}{}:
	default:
	}
}

func (s *session) current() *attachment {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.attached
}

func (s *session) withTerminal(action func(*attachment) error) error {
	current := s.current()
	if current == nil {
		return &requestError{Code: "not_attached", Message: "Open a terminal first."}
	}
	current.lastActivity.Store(time.Now().UnixNano())
	err := action(current)
	s.wakeTerminal(current)
	return err
}

func (s *session) wakeTerminal(current *attachment) {
	select {
	case current.wake <- struct{}{}:
	default:
	}
}

func (s *session) attach(workspaceID, surfaceID string) {
	s.detach()
	ctx, cancel := context.WithCancel(s.ctx)
	current := &attachment{
		workspaceID: workspaceID,
		surfaceID:   surfaceID,
		cancel:      cancel,
		done:        make(chan struct{}),
		wake:        make(chan struct{}, 1),
	}
	current.lastActivity.Store(time.Now().UnixNano())
	s.mu.Lock()
	s.attached = current
	s.mu.Unlock()
	go s.pollTerminal(ctx, current)
}

func (s *session) detach() {
	s.mu.Lock()
	current := s.attached
	s.attached = nil
	fitted := current != nil && current.fitted
	s.mu.Unlock()
	if current == nil {
		return
	}
	current.cancel()
	<-current.done
	if fitted {
		s.clearViewport(current)
	}
}

func (s *session) fit(ctx context.Context, columns, rows int) error {
	if columns < 20 || columns > 300 || rows < 5 || rows > 120 {
		return &requestError{Code: "invalid_params", Message: "fit needs 20-300 columns and 5-120 rows."}
	}
	s.mu.Lock()
	current := s.attached
	s.viewportGeneration++
	generation := s.viewportGeneration
	s.mu.Unlock()
	if current == nil {
		return &requestError{Code: "not_attached", Message: "Open a terminal first."}
	}
	err := s.server.cmux.SetViewport(ctx, current.workspaceID, current.surfaceID, cmux.Viewport{
		ClientID: s.clientID, Columns: columns, Rows: rows, Generation: generation,
	})
	if err == nil {
		s.mu.Lock()
		current.fitted = true
		s.mu.Unlock()
		s.wakeTerminal(current)
	}
	return err
}

func (s *session) unfit(ctx context.Context) error {
	s.mu.Lock()
	current := s.attached
	if current == nil || !current.fitted {
		s.mu.Unlock()
		return nil
	}
	current.fitted = false
	s.mu.Unlock()
	return s.clearViewportWith(ctx, current)
}

func (s *session) clearViewport(current *attachment) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := s.clearViewportWith(ctx, current); err != nil {
		s.server.logger.Warn("could not restore the Mac terminal size", "error", err)
	}
}

func (s *session) clearViewportWith(ctx context.Context, current *attachment) error {
	s.mu.Lock()
	s.viewportGeneration++
	generation := s.viewportGeneration
	s.mu.Unlock()
	err := s.server.cmux.ClearViewport(ctx, current.workspaceID, current.surfaceID, s.clientID, generation)
	s.wakeTerminal(current)
	return err
}

func (s *session) pollTerminal(ctx context.Context, current *attachment) {
	defer close(current.done)
	tracker := screen.NewTracker()
	intervals := s.server.options.PollInterval
	lastChange := time.Now()
	consecutiveErrors := 0
	for {
		if current.resync.Swap(false) {
			tracker.Reset()
		}
		replay, err := s.server.cmux.Replay(ctx, current.workspaceID, current.surfaceID)
		if ctx.Err() != nil {
			return
		}
		if err == nil && replay.RenderGrid == nil {
			err = &cmux.RPCError{Code: "no_render_grid", Message: "cmux returned no screen for this terminal."}
		}
		if err != nil {
			consecutiveErrors++
			if cmux.ErrorCode(err) == terminalGoneErrorCode || consecutiveErrors >= maxConsecutiveErrors {
				s.send(map[string]any{"type": "detached", "surface_id": current.surfaceID, "reason": err.Error()})
				return
			}
		} else {
			consecutiveErrors = 0
			if replay.WorkspaceID == "" {
				replay.WorkspaceID = current.workspaceID
			}
			if replay.SurfaceID == "" {
				replay.SurfaceID = current.surfaceID
			}
			if frame, changed := tracker.Update(replay); changed {
				s.send(frame)
				lastChange = time.Now()
			}
		}

		quietFor := time.Since(lastChange)
		if sinceActivity := time.Since(time.Unix(0, current.lastActivity.Load())); sinceActivity < quietFor {
			quietFor = sinceActivity
		}
		interval := intervals.Fast
		switch {
		case err != nil:
			interval = intervals.Idle * time.Duration(consecutiveErrors)
		case quietFor > intervals.SlowAfter:
			interval = intervals.Slow
		case quietFor > intervals.IdleAfter:
			interval = intervals.Idle
		}
		select {
		case <-ctx.Done():
			return
		case <-current.wake:
		case <-time.After(interval):
		}
	}
}
