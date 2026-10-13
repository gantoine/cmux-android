package server

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/coder/websocket"
	"github.com/coder/websocket/wsjson"

	"github.com/gantoine/cmux-android/relay/internal/cmux"
	"github.com/gantoine/cmux-android/relay/internal/devices"
)

type fakeCmux struct {
	mu        sync.Mutex
	inputs    []string
	keys      []string
	viewports []string
}

func (f *fakeCmux) Identify(context.Context) (cmux.Identity, error) {
	return cmux.Identity{Version: "0.65.1"}, nil
}

func (f *fakeCmux) Workspaces(context.Context) ([]cmux.Workspace, error) {
	return []cmux.Workspace{{ID: "w1", Title: "api", Terminals: []cmux.Terminal{{ID: "t1", Title: "zsh"}}}}, nil
}

func (f *fakeCmux) Replay(_ context.Context, workspaceID, surfaceID string) (cmux.Replay, error) {
	if surfaceID != "t1" {
		return cmux.Replay{}, &cmux.RPCError{Code: "not_found", Message: "no such terminal"}
	}
	return cmux.Replay{WorkspaceID: workspaceID, SurfaceID: surfaceID, RenderGrid: &cmux.RenderGrid{
		Columns: 80, Rows: 2,
		Styles:   []cmux.Style{{ID: 0, Foreground: "#FFFFFF", Background: "#000000"}},
		RowSpans: []cmux.Span{{Row: 0, Column: 0, StyleID: 0, Text: "$ "}},
	}}, nil
}

func (f *fakeCmux) Input(_ context.Context, workspaceID, surfaceID, text string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.inputs = append(f.inputs, workspaceID+"/"+surfaceID+":"+text)
	return nil
}

func (f *fakeCmux) SendKey(_ context.Context, _, surfaceID, key string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.keys = append(f.keys, surfaceID+":"+key)
	return nil
}

func (f *fakeCmux) Paste(context.Context, string, string, string, bool) error { return nil }
func (f *fakeCmux) Scroll(context.Context, string, string, int) error         { return nil }

func (f *fakeCmux) SetViewport(_ context.Context, _, _ string, viewport cmux.Viewport) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.viewports = append(f.viewports, "set")
	return nil
}

func (f *fakeCmux) ClearViewport(context.Context, string, string, string, int) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.viewports = append(f.viewports, "clear")
	return nil
}

func (f *fakeCmux) CreateWorkspace(context.Context, string, string) (cmux.CreatedWorkspace, error) {
	return cmux.CreatedWorkspace{WorkspaceID: "w2", SurfaceID: "t2"}, nil
}
func (f *fakeCmux) CloseWorkspace(context.Context, string, bool) error    { return nil }
func (f *fakeCmux) RenameWorkspace(context.Context, string, string) error { return nil }
func (f *fakeCmux) CreateTerminal(context.Context, string) (string, error) {
	return "t3", nil
}

func (f *fakeCmux) snapshot() (inputs, keys, viewports []string) {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]string(nil), f.inputs...), append([]string(nil), f.keys...), append([]string(nil), f.viewports...)
}

type testRelay struct {
	url    string
	cmux   *fakeCmux
	store  *devices.Store
	server *Server
}

func startRelay(t *testing.T) testRelay {
	t.Helper()
	store, err := devices.Open(filepath.Join(t.TempDir(), "devices.json"))
	if err != nil {
		t.Fatal(err)
	}
	fake := &fakeCmux{}
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	intervals := DefaultIntervals
	intervals.Fast = 10 * time.Millisecond
	relay := New(fake, store, nil, Options{MacName: "Test Mac", RelayVersion: "test", PollInterval: intervals}, logger)
	server := httptest.NewServer(relay.Handler())
	t.Cleanup(server.Close)
	return testRelay{url: server.URL, cmux: fake, store: store, server: relay}
}

func (r testRelay) pair(t *testing.T) string {
	t.Helper()
	code, _, err := r.store.NewPairingCode()
	if err != nil {
		t.Fatal(err)
	}
	body, _ := json.Marshal(map[string]string{"code": code, "device_name": "Pixel"})
	response, err := http.Post(r.url+"/v1/pair", "application/json", bytes.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	var paired struct {
		Token   string `json:"token"`
		MacName string `json:"mac_name"`
	}
	_ = json.NewDecoder(response.Body).Decode(&paired)
	if response.StatusCode != http.StatusOK || paired.Token == "" || paired.MacName != "Test Mac" {
		t.Fatalf("pairing failed: status %d, %+v", response.StatusCode, paired)
	}
	return paired.Token
}

func (r testRelay) dial(t *testing.T, ctx context.Context, token string) (*websocket.Conn, *http.Response, error) {
	t.Helper()
	header := http.Header{}
	header.Set("Authorization", "Bearer "+token)
	return websocket.Dial(ctx, strings.Replace(r.url, "http", "ws", 1)+"/v1/ws", &websocket.DialOptions{HTTPHeader: header})
}

func readUntil(t *testing.T, ctx context.Context, conn *websocket.Conn, kind string) map[string]any {
	t.Helper()
	for {
		var message map[string]any
		if err := wsjson.Read(ctx, conn, &message); err != nil {
			t.Fatalf("waiting for %s: %v", kind, err)
		}
		if message["type"] == kind {
			return message
		}
	}
}

func TestPairedPhoneCanWatchAndTypeIntoATerminal(t *testing.T) {
	relay := startRelay(t)
	token := relay.pair(t)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	conn, _, err := relay.dial(t, ctx, token)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.CloseNow()

	_ = wsjson.Write(ctx, conn, map[string]any{"type": "hello", "id": "1"})
	welcome := readUntil(t, ctx, conn, "result")
	if welcome["ok"] != true || welcome["data"].(map[string]any)["cmux_version"] != "0.65.1" {
		t.Fatalf("unexpected welcome: %v", welcome)
	}
	workspaces := readUntil(t, ctx, conn, "workspaces")
	if len(workspaces["workspaces"].([]any)) != 1 {
		t.Fatalf("unexpected workspaces: %v", workspaces)
	}

	_ = wsjson.Write(ctx, conn, map[string]any{"type": "attach", "workspace_id": "w1", "surface_id": "t1", "fit": map[string]int{"columns": 40, "rows": 20}})
	frame := readUntil(t, ctx, conn, "screen")
	if frame["full"] != true || frame["surface_id"] != "t1" {
		t.Fatalf("unexpected first frame: %v", frame)
	}

	_ = wsjson.Write(ctx, conn, map[string]any{"type": "input", "id": "2", "text": "ls\r"})
	if result := readUntil(t, ctx, conn, "result"); result["ok"] != true {
		t.Fatalf("input failed: %v", result)
	}
	_ = wsjson.Write(ctx, conn, map[string]any{"type": "key", "id": "3", "key": "ctrl-c"})
	readUntil(t, ctx, conn, "result")
	_ = wsjson.Write(ctx, conn, map[string]any{"type": "detach", "id": "4"})
	readUntil(t, ctx, conn, "result")

	inputs, keys, viewports := relay.cmux.snapshot()
	if len(inputs) != 1 || inputs[0] != "w1/t1:ls\r" {
		t.Fatalf("input must reach the attached terminal, got %v", inputs)
	}
	if len(keys) != 1 || keys[0] != "t1:ctrl-c" {
		t.Fatalf("keys = %v", keys)
	}
	if strings.Join(viewports, ",") != "set,clear" {
		t.Fatalf("detaching must give the Mac its size back, viewports = %v", viewports)
	}
}

func TestRelayRejectsUnknownTokensAndInputBeforeAttach(t *testing.T) {
	relay := startRelay(t)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	_, response, err := relay.dial(t, ctx, "not-a-token")
	if err == nil || response == nil || response.StatusCode != http.StatusUnauthorized {
		t.Fatalf("unknown token must get 401, got %v %v", err, response)
	}

	conn, _, err := relay.dial(t, ctx, relay.pair(t))
	if err != nil {
		t.Fatal(err)
	}
	defer conn.CloseNow()
	_ = wsjson.Write(ctx, conn, map[string]any{"type": "input", "id": "1", "text": "rm -rf /\r"})
	result := readUntil(t, ctx, conn, "result")
	if result["ok"] != false || result["error"].(map[string]any)["code"] != "not_attached" {
		t.Fatalf("input without an attached terminal must fail, got %v", result)
	}
	if inputs, _, _ := relay.cmux.snapshot(); len(inputs) != 0 {
		t.Fatalf("no input should reach cmux, got %v", inputs)
	}
}

func TestDetachedMessageWhenTerminalDisappears(t *testing.T) {
	relay := startRelay(t)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	conn, _, err := relay.dial(t, ctx, relay.pair(t))
	if err != nil {
		t.Fatal(err)
	}
	defer conn.CloseNow()
	_ = wsjson.Write(ctx, conn, map[string]any{"type": "attach", "workspace_id": "w1", "surface_id": "gone"})
	detached := readUntil(t, ctx, conn, "detached")
	if detached["surface_id"] != "gone" {
		t.Fatalf("unexpected detached message: %v", detached)
	}
}

func TestClosingTheRelayGivesFittedTerminalsTheirSizeBack(t *testing.T) {
	relay := startRelay(t)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	conn, _, err := relay.dial(t, ctx, relay.pair(t))
	if err != nil {
		t.Fatal(err)
	}
	defer conn.CloseNow()

	_ = wsjson.Write(ctx, conn, map[string]any{"type": "attach", "id": "1", "workspace_id": "w1", "surface_id": "t1", "fit": map[string]int{"columns": 40, "rows": 20}})
	if result := readUntil(t, ctx, conn, "result"); result["ok"] != true {
		t.Fatalf("attach failed: %v", result)
	}

	relay.server.Close(ctx)

	if _, _, viewports := relay.cmux.snapshot(); strings.Join(viewports, ",") != "set,clear" {
		t.Fatalf("closing the relay must give the Mac its size back, viewports = %v", viewports)
	}
}
