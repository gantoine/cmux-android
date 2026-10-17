//go:build e2e

package e2e

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/gantoine/muxer/relay/internal/cmuxrecord"
)

const (
	scenarioWorkspace      = "Payments API"
	scenarioOtherWorkspace = "Docs site"
	scenarioOutput         = "Payments API tests passed"
	scenarioDoneItem       = "Write the migration"
	scenarioOpenItem       = "Ship the release"
	scenarioNotification   = "Build finished"
	scenarioNotifyBody     = "Docs site deployed"
	scenarioCommand        = "npm test"
	scenarioCommandAbout   = "Run the test suite"
	committedRecording     = "testdata/recording.json"
	recordingSettleTime    = 7 * time.Second
)

func namedWorkspace(t *testing.T, p *phone, title string) workspace {
	t.Helper()
	created := p.must("create_workspace", map[string]any{"title": title, "cwd": t.TempDir()})
	ws := workspace{ID: text(created["workspace_id"]), SurfaceID: text(created["surface_id"]), Title: title}
	t.Cleanup(func() { closeWorkspace(ws.ID) })
	return ws
}

func TestAppScenarioStillMatchesTheRecording(t *testing.T) {
	recorder.Arm()
	recording := func() *cmuxrecord.Recording {
		defer func() {
			if r := recover(); r != nil {
				recorder.Disarm()
				panic(r)
			}
		}()
		p := connect(t)
		payments := namedWorkspace(t, p, scenarioWorkspace)
		docs := namedWorkspace(t, p, scenarioOtherWorkspace)

		p.must("add_checklist_item", map[string]any{"workspace_id": payments.ID, "text": scenarioDoneItem})
		added := p.must("add_checklist_item", map[string]any{"workspace_id": payments.ID, "text": scenarioOpenItem})
		done := find(list(added["items"]), "text", scenarioDoneItem)
		p.must("set_checklist_item_state", map[string]any{"workspace_id": payments.ID, "item_id": done["id"], "state": "completed"})
		p.must("set_workspace_status", map[string]any{"workspace_id": payments.ID, "status": "review"})
		p.must("workspace_action", map[string]any{"workspace_id": docs.ID, "action": "pin"})
		p.must("workspace_action", map[string]any{"workspace_id": docs.ID, "action": "set_color", "color": "#2E7D32"})

		attach(t, p, payments, payments.SurfaceID)
		mark := p.mark()
		p.must("input", map[string]any{"text": "clear; printf '%s\\n' '" + scenarioOutput + "'\r"})
		p.rawPushAfter(mark, "screen", "the scenario output", func(raw string) bool { return strings.Contains(raw, scenarioOutput) })

		notify(t, p, docs, scenarioNotification)
		cmuxCLI(t, "notify", "--surface", docs.SurfaceID, "--title", scenarioNotification, "--body", scenarioNotifyBody, "--desktop", "false")

		hook := raiseHook(t, payments, "Bash", map[string]any{"command": scenarioCommand, "description": scenarioCommandAbout})
		pendingApproval(t, p, payments, "permission")
		t.Cleanup(func() {
			if approvals := p.eventually("approvals", "the scenario approval", func(message) bool { return true }); approvals != nil {
				for _, approval := range list(approvals["approvals"]) {
					p.request("answer_approval", map[string]any{"approval_id": approval["id"], "mode": "deny"})
				}
			}
			hook.cancel()
		})

		for _, kind := range []string{"notifications", "top", "keep_awake", "recent_sessions", "vms", "history"} {
			p.request(kind, nil)
		}
		p.must("checklist", map[string]any{"workspace_id": payments.ID})
		time.Sleep(recordingSettleTime)
		return recorder.Disarm()
	}()

	if dir := os.Getenv("E2E_RECORDING_OUT"); dir != "" {
		if err := os.MkdirAll(dir, 0o755); err != nil {
			t.Fatal(err)
		}
		if err := recording.Save(filepath.Join(dir, "recording.json")); err != nil {
			t.Fatal(err)
		}
	}
	committed, err := cmuxrecord.Load(committedRecording)
	if os.IsNotExist(err) {
		t.Skipf("no committed recording yet; save one from CI to %s", committedRecording)
	}
	if err != nil {
		t.Fatal(err)
	}
	comparison := cmuxrecord.Compare(committed, recording)
	for _, change := range comparison.Added {
		t.Logf("cmux added: %s", change)
	}
	for _, change := range comparison.Broken {
		t.Errorf("cmux changed: %s", change)
	}
	if len(comparison.Broken) > 0 {
		t.Logf("If the change is expected, update the relay and the app, then commit the new recording from the cmux-e2e artifact to relay/e2e/%s.", committedRecording)
	}
}
