//go:build e2e

package e2e

import (
	"bytes"
	"context"
	"encoding/json"
	"os"
	"os/exec"
	"strings"
	"testing"
	"time"

	"github.com/gantoine/muxer/relay/internal/cmux"
)

const bundledCLI = "/Applications/cmux.app/Contents/Resources/bin/cmux"

func cmuxPath() string {
	if path, err := exec.LookPath("cmux"); err == nil {
		return path
	}
	return bundledCLI
}

func cmuxCLI(t *testing.T, args ...string) string {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), requestTimeout)
	defer cancel()
	command := exec.CommandContext(ctx, cmuxPath(), args...)
	command.Env = append(os.Environ(), "CMUX_QUIET=1")
	output, err := command.CombinedOutput()
	if err != nil {
		t.Fatalf("cmux %s: %v\n%s", strings.Join(args, " "), err, output)
	}
	return string(output)
}

func cmuxClient(t *testing.T) *cmux.Client {
	t.Helper()
	client := cmux.NewClient(cmux.DefaultSocketPath(), cmux.DefaultPasswordSource())
	t.Cleanup(client.Close)
	return client
}

type workspace struct {
	ID        string
	SurfaceID string
	Title     string
}

func scratchWorkspace(t *testing.T, p *phone) workspace {
	t.Helper()
	title := uniqueName(t)
	created := p.must("create_workspace", map[string]any{"title": title, "cwd": t.TempDir()})
	ws := workspace{ID: text(created["workspace_id"]), SurfaceID: text(created["surface_id"]), Title: title}
	if ws.ID == "" || ws.SurfaceID == "" {
		t.Fatalf("create_workspace answered %v", created)
	}
	t.Cleanup(func() { closeWorkspace(ws.ID) })
	return ws
}

func closeWorkspace(id string) {
	ctx, cancel := context.WithTimeout(context.Background(), requestTimeout)
	defer cancel()
	client := cmux.NewClient(cmux.DefaultSocketPath(), cmux.DefaultPasswordSource())
	defer client.Close()
	_ = client.Call(ctx, cmux.MethodWorkspaceAction, map[string]any{"workspace_id": id, "action": "unpin"}, nil)
	_ = client.CloseWorkspace(ctx, id, true)
}

type hookResult struct {
	decision message
	err      error
	output   string
}

type agentHook struct {
	done   chan hookResult
	cancel context.CancelFunc
}

func raiseHook(t *testing.T, ws workspace, tool string, input map[string]any) *agentHook {
	t.Helper()
	payload, _ := json.Marshal(map[string]any{
		"session_id":             uniqueName(t),
		"hook_event_name":        "PermissionRequest",
		"tool_name":              tool,
		"tool_input":             input,
		"cwd":                    os.TempDir(),
		"transcript_path":        os.TempDir() + "/e2e.jsonl",
		"permission_suggestions": []any{},
	})
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	command := exec.CommandContext(ctx, cmuxPath(), "hooks", "feed", "--source", "claude", "--event", "PermissionRequest")
	command.Env = append(os.Environ(),
		"CMUX_SURFACE_ID="+ws.SurfaceID,
		"CMUX_PANEL_ID="+ws.SurfaceID,
		"CMUX_WORKSPACE_ID="+ws.ID,
		"CMUX_TAB_ID="+ws.ID,
	)
	command.Stdin = bytes.NewReader(payload)
	var stdout, stderr bytes.Buffer
	command.Stdout, command.Stderr = &stdout, &stderr
	if err := command.Start(); err != nil {
		cancel()
		t.Fatalf("start cmux hooks feed: %v", err)
	}
	hook := &agentHook{done: make(chan hookResult, 1), cancel: cancel}
	go func() {
		err := command.Wait()
		var output struct {
			HookSpecificOutput struct {
				Decision message `json:"decision"`
			} `json:"hookSpecificOutput"`
		}
		_ = json.Unmarshal(stdout.Bytes(), &output)
		hook.done <- hookResult{decision: output.HookSpecificOutput.Decision, err: err, output: stdout.String() + stderr.String()}
	}()
	t.Cleanup(cancel)
	return hook
}

func (h *agentHook) decision(t *testing.T) message {
	t.Helper()
	select {
	case result := <-h.done:
		if result.err != nil || result.decision == nil {
			t.Fatalf("the agent hook got no decision (%v): %s", result.err, result.output)
		}
		return result.decision
	case <-time.After(settleTimeout):
		h.cancel()
		t.Fatal("the agent hook never received the phone's answer")
		return nil
	}
}
