package server

import (
	"encoding/json"
	"testing"

	"github.com/coder/websocket/wsjson"

	"github.com/gantoine/muxer/relay/internal/cmux"
)

const vmListFixture = `{
  "vms": [
    {"id": "vm_1", "displayName": "Build box", "status": "running", "image": "ubuntu-24.04", "kind": "linux", "createdAt": "2026-10-01T10:00:00Z", "capabilities": {"stats": true}},
    {"id": "vm_2", "slug": "scratch", "status": "paused", "image": "ubuntu-24.04", "kind": "linux", "capabilities": {"stats": true}}
  ],
  "limits": {"planId": "pro", "maxActiveVms": 3}
}`

func TestPhoneSeesAndPausesCloudVMs(t *testing.T) {
	relay := startRelay(t)
	if err := json.Unmarshal([]byte(vmListFixture), &relay.cmux.vmList); err != nil {
		t.Fatal(err)
	}
	cpus, used, total := 4, 2048, 8192
	relay.cmux.vmStats = map[string]cmux.VMStats{"vm_1": {ID: "vm_1", State: "running", CPUs: &cpus, MemoryUsedMB: &used, MemoryTotalMB: &total}}
	ctx, conn := relay.connect(t)

	_ = wsjson.Write(ctx, conn, map[string]any{"type": "hello", "id": "1"})
	_ = wsjson.Write(ctx, conn, map[string]any{"type": "vms", "id": "2"})
	result := resultFor(t, ctx, conn, "2")
	if result["ok"] != true {
		t.Fatalf("vms must succeed: %v", result)
	}
	data := result["data"].(map[string]any)
	if data["plan"] != "pro" {
		t.Fatalf("the plan must come through: %v", data)
	}
	vms := data["vms"].([]any)
	running, paused := vms[0].(map[string]any), vms[1].(map[string]any)
	if running["name"] != "Build box" || running["stats"].(map[string]any)["memory_used_mb"] != float64(2048) {
		t.Fatalf("a running VM must carry its display name and stats: %v", running)
	}
	if paused["name"] != "scratch" || paused["stats"] != nil {
		t.Fatalf("a paused VM must fall back to its slug and skip stats: %v", paused)
	}
	relay.cmux.mu.Lock()
	calls := append([]string(nil), relay.cmux.vmCalls...)
	relay.cmux.mu.Unlock()
	if len(calls) != 1 || calls[0] != "stats vm_1" {
		t.Fatalf("stats must only be asked for running VMs: %v", calls)
	}

	_ = wsjson.Write(ctx, conn, map[string]any{"type": "set_vm_paused", "id": "3", "vm_id": "x"})
	if result := resultFor(t, ctx, conn, "3"); result["ok"] == true {
		t.Fatalf("set_vm_paused without paused must be refused: %v", result)
	}
	_ = wsjson.Write(ctx, conn, map[string]any{"type": "set_vm_paused", "id": "4", "vm_id": "vm_2", "paused": false})
	result = resultFor(t, ctx, conn, "4")
	if result["ok"] != true || result["data"].(map[string]any)["status"] != "running" {
		t.Fatalf("resuming must return the VM's new status: %v", result)
	}

	relay.cmux.mu.Lock()
	relay.cmux.vmErr = &cmux.RPCError{Code: "cloud_disabled", Message: "Cloud is disabled"}
	relay.cmux.mu.Unlock()
	_ = wsjson.Write(ctx, conn, map[string]any{"type": "vms", "id": "5"})
	result = resultFor(t, ctx, conn, "5")
	if result["ok"] == true || errorCode(result) != "cloud_disabled" {
		t.Fatalf("a computer with cmux Cloud off must say so: %v", result)
	}
}
