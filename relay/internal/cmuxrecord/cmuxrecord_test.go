package cmuxrecord

import (
	"bufio"
	"context"
	"encoding/json"
	"net"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/gantoine/muxer/relay/internal/cmux"
)

func shortSocket(t *testing.T, name string) string {
	t.Helper()
	dir, err := os.MkdirTemp("/tmp", "cmuxrec")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { os.RemoveAll(dir) })
	return filepath.Join(dir, name)
}

type fakeCmux struct {
	listener net.Listener
	mu       sync.Mutex
	streams  []net.Conn
}

func startFakeCmux(t *testing.T) (*fakeCmux, string) {
	t.Helper()
	path := shortSocket(t, "up.sock")
	listener, err := net.Listen("unix", path)
	if err != nil {
		t.Fatal(err)
	}
	f := &fakeCmux{listener: listener}
	t.Cleanup(func() { listener.Close() })
	go func() {
		for {
			conn, err := listener.Accept()
			if err != nil {
				return
			}
			go f.serve(conn)
		}
	}()
	return f, path
}

func (f *fakeCmux) serve(conn net.Conn) {
	reader := bufio.NewReader(conn)
	for {
		line, err := reader.ReadBytes('\n')
		if err != nil {
			return
		}
		var req request
		_ = json.Unmarshal(line, &req)
		switch req.Method {
		case "auth.login":
			conn.Write(encodeLine(map[string]any{"id": req.ID, "ok": true, "result": map[string]any{"authenticated": true}}))
		case "events.stream":
			conn.Write(encodeLine(map[string]any{"type": "ack", "protocol": "cmux-events"}))
			f.mu.Lock()
			f.streams = append(f.streams, conn)
			f.mu.Unlock()
		case "system.capabilities":
			conn.Write(encodeLine(map[string]any{"id": req.ID, "ok": true, "result": map[string]any{"methods": []string{"system.identify", "workspace.rename"}}}))
		case "system.identify":
			conn.Write(encodeLine(map[string]any{"id": req.ID, "ok": true, "result": map[string]any{"version": "9.9.9"}}))
		default:
			conn.Write(encodeLine(map[string]any{"id": req.ID, "ok": false, "error": map[string]any{"code": "method_not_found", "message": "no"}}))
		}
	}
}

func (f *fakeCmux) publish(name string, seq int) {
	f.mu.Lock()
	defer f.mu.Unlock()
	for _, conn := range f.streams {
		conn.Write(encodeLine(map[string]any{"type": "event", "name": name, "seq": seq, "payload": map[string]any{"workspace_id": "w1"}}))
	}
}

func collect(t *testing.T, client *cmux.Client) (<-chan cmux.Event, context.CancelFunc) {
	t.Helper()
	events := make(chan cmux.Event, 10)
	ctx, cancel := context.WithCancel(context.Background())
	go func() {
		_ = client.Events(ctx, nil, cmux.EventFilter{Categories: []string{"workspace"}}, func(event cmux.Event) error {
			if event.Type == "event" {
				events <- event
			}
			return nil
		})
	}()
	return events, cancel
}

func TestARecordedSessionReplaysTheSameAnswersAndEvents(t *testing.T) {
	upstream, upstreamPath := startFakeCmux(t)
	recordPath := shortSocket(t, "rec.sock")
	recorder, err := NewRecorder(recordPath, upstreamPath)
	if err != nil {
		t.Fatal(err)
	}
	defer recorder.Close()

	client := cmux.NewClient(recordPath, func() (string, error) { return "pw", nil })
	defer client.Close()
	events, stopEvents := collect(t, client)
	defer stopEvents()
	time.Sleep(100 * time.Millisecond)
	ctx := context.Background()
	if _, err := client.Identify(ctx); err != nil {
		t.Fatal(err)
	}
	recorder.Arm()
	if _, err := client.Methods(ctx); err != nil {
		t.Fatal(err)
	}
	if identity, err := client.Identify(ctx); err != nil || identity.Version != "9.9.9" {
		t.Fatalf("identify through the recorder: %v %v", identity, err)
	}
	if err := client.Call(ctx, "workspace.close", map[string]any{"workspace_id": "w1"}, nil); !cmux.IsMethodNotFound(err) {
		t.Fatalf("errors must pass through, got %v", err)
	}
	upstream.publish("workspace.created", 7)
	select {
	case <-events:
	case <-time.After(2 * time.Second):
		t.Fatal("the event never came through the recorder")
	}
	recording := recorder.Disarm()
	if got := recording.Methods(); strings.Join(got, ",") != "system.capabilities,system.identify,workspace.close" {
		t.Fatalf("recorded methods %v", got)
	}
	if len(recording.Events) != 1 || recording.StreamAck == nil {
		t.Fatalf("recorded events %s, ack %s", recording.Events, recording.StreamAck)
	}

	saved := filepath.Join(t.TempDir(), "recording.json")
	if err := recording.Save(saved); err != nil {
		t.Fatal(err)
	}
	loaded, err := Load(saved)
	if err != nil {
		t.Fatal(err)
	}
	replayer := NewReplayer(loaded)
	replayPath := shortSocket(t, "replay.sock")
	listener, err := net.Listen("unix", replayPath)
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	go replayer.Serve(listener)

	replayed := cmux.NewClient(replayPath, func() (string, error) { return "anything", nil })
	defer replayed.Close()
	if identity, err := replayed.Identify(ctx); err != nil || identity.Version != "9.9.9" {
		t.Fatalf("replayed identify: %v %v", identity, err)
	}
	if err := replayed.Call(ctx, "workspace.close", map[string]any{"workspace_id": "w1"}, nil); !cmux.IsMethodNotFound(err) {
		t.Fatalf("replayed errors must match the recording, got %v", err)
	}
	if err := replayed.Call(ctx, "workspace.rename", map[string]any{"workspace_id": "w1", "title": "x"}, nil); err != nil {
		t.Fatalf("a supported method that wasn't recorded should succeed, got %v", err)
	}
	if err := replayed.Call(ctx, "vm.list", nil, nil); !cmux.IsMethodNotFound(err) {
		t.Fatalf("an unsupported method should be missing, got %v", err)
	}

	replayedEvents, stopReplayed := collect(t, replayed)
	defer stopReplayed()
	time.Sleep(100 * time.Millisecond)
	server := httptest.NewServer(replayer.ControlHandler())
	defer server.Close()
	response, err := server.Client().Post(server.URL+"/emit?name=workspace.created", "", nil)
	if err != nil {
		t.Fatal(err)
	}
	var emitted map[string]int
	_ = json.NewDecoder(response.Body).Decode(&emitted)
	response.Body.Close()
	if emitted["emitted"] != 1 {
		t.Fatalf("emit answered %v", emitted)
	}
	select {
	case event := <-replayedEvents:
		if event.Name != "workspace.created" || event.Workspace() != "w1" || event.Seq <= 7 {
			t.Fatalf("replayed event %+v", event)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("the emitted event never arrived")
	}
	if calls := replayer.Received("workspace.rename"); len(calls) != 1 {
		t.Fatalf("the replayer should log what it was asked, got %v", calls)
	}
}

func recordingOf(t *testing.T, calls map[string]string, events ...string) *Recording {
	t.Helper()
	recording := &Recording{}
	for method, result := range calls {
		recording.Calls = append(recording.Calls, Call{Method: method, Result: json.RawMessage(result)})
	}
	for _, event := range events {
		recording.Events = append(recording.Events, json.RawMessage(event))
	}
	return recording
}

func TestComparisonFlagsRemovedAndRetypedFieldsOnly(t *testing.T) {
	want := recordingOf(t, map[string]string{
		"mobile.workspace.list": `{"workspaces":[{"id":"a","title":"x","is_pinned":false,"remote":{"state":"up"}}]}`,
		"system.top":            `{"by_id":{"6F9619FF-8B86-D011-B42D-00C04FC964FF":{"cpu":1}},"total":2}`,
		"workspace.todo.list":   `{"items":[{"id":"1","text":"t"}]}`,
	}, `{"type":"event","name":"workspace.created","workspace_id":"w"}`)

	same := recordingOf(t, map[string]string{
		"mobile.workspace.list": `{"workspaces":[{"id":"b","title":"y","is_pinned":true,"remote":null,"new_field":1}]}`,
		"system.top":            `{"by_id":{"0E984725-C51C-4BF4-9960-E1C80E27ABA0":{"cpu":3}},"total":5}`,
		"workspace.todo.list":   `{"items":[]}`,
	}, `{"type":"event","name":"workspace.created","workspace_id":"v"}`)
	comparison := Compare(want, same)
	if len(comparison.Broken) != 0 {
		t.Fatalf("new fields, null parents, empty lists and changed ids aren't breakage: %v", comparison.Broken)
	}
	if len(comparison.Added) != 1 || comparison.Added[0].Path != "$.workspaces[].new_field" {
		t.Fatalf("added fields should be reported, got %v", comparison.Added)
	}

	broken := recordingOf(t, map[string]string{
		"mobile.workspace.list": `{"workspaces":[{"id":"b","name":"y","is_pinned":"yes","remote":{"state":"up"}}]}`,
		"system.top":            `{"by_id":{},"total":5}`,
	})
	comparison = Compare(want, broken)
	var problems []string
	for _, change := range comparison.Broken {
		problems = append(problems, change.String())
	}
	for _, expected := range []string{
		"cmux mobile.workspace.list $.workspaces[].title: missing",
		"cmux mobile.workspace.list $.workspaces[].is_pinned: was bool, now string",
		"cmux workspace.todo.list $: the relay no longer calls it, or cmux never answered",
		"event workspace.created $: cmux no longer publishes it during the scenario",
	} {
		found := false
		for _, problem := range problems {
			found = found || problem == expected
		}
		if !found {
			t.Errorf("expected %q in %v", expected, problems)
		}
	}
}
