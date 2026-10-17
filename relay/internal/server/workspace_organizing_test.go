package server

import (
	"reflect"
	"testing"

	"github.com/coder/websocket/wsjson"

	"github.com/gantoine/muxer/relay/internal/cmux"
)

func TestWorkspacesCarryTheirLaneChecklistRemoteAndGroupColor(t *testing.T) {
	relay := startRelay(t)
	relay.cmux.workspaceList = &cmux.WorkspaceList{
		Workspaces: []cmux.Workspace{{ID: "w1", GroupID: ptr("g1")}, {ID: "w2"}},
		Groups:     []cmux.WorkspaceGroup{{ID: "g1", Name: "Q4", MemberWorkspaceIDs: []string{"w1"}}},
	}
	relay.cmux.lanes = map[string]cmux.LaneStatus{"w1": {Effective: "review", Inferred: "working", Override: true}}
	relay.cmux.checklists = map[string]cmux.Checklist{"w1": {Progress: cmux.ChecklistProgress{Completed: 1, Total: 3, Next: "write tests"}}}
	relay.cmux.remotes = map[string]cmux.RemoteState{"w2": {State: "reconnecting", Destination: "devbox"}}
	relay.cmux.groupDetails = []cmux.GroupDetails{{ID: "g1", CustomColor: ptr("#1565C0")}}
	ctx, conn := relay.connect(t)

	_ = wsjson.Write(ctx, conn, map[string]any{"type": "hello", "id": "1"})
	message := readUntil(t, ctx, conn, "workspaces")
	workspaces := message["workspaces"].([]any)
	first, second := workspaces[0].(map[string]any), workspaces[1].(map[string]any)
	status := first["status"].(map[string]any)
	if status["effective"] != "review" || status["inferred"] != "working" || status["override"] != true {
		t.Fatalf("the lane must come with the workspace: %v", first)
	}
	if checklist := first["checklist"].(map[string]any); checklist["completed"] != float64(1) || checklist["total"] != float64(3) || checklist["next"] != "write tests" {
		t.Fatalf("checklist progress must come with the workspace: %v", first)
	}
	if _, present := second["checklist"]; present {
		t.Fatalf("an empty checklist is left out: %v", second)
	}
	if remote := second["remote"].(map[string]any); remote["state"] != "reconnecting" || remote["destination"] != "devbox" {
		t.Fatalf("an SSH workspace must report its connection: %v", second)
	}
	if _, present := first["remote"]; present {
		t.Fatalf("a local workspace has no remote state: %v", first)
	}
	group := message["groups"].([]any)[0].(map[string]any)
	if group["custom_color"] != "#1565C0" {
		t.Fatalf("the group's color must come from workspace.group.list: %v", group)
	}
}

func TestPhoneOrganizesWorkspacesAndGroups(t *testing.T) {
	relay := startRelay(t)
	ctx, conn := relay.connect(t)
	relay.cmux.performResults = map[string]string{
		cmux.MethodGroupCreate: `{"group":{"id":"g9"}}`,
	}
	requests := []map[string]any{
		{"type": "workspace_action", "workspace_id": "w1", "action": "pin"},
		{"type": "workspace_action", "workspace_id": "w1", "action": "set_color", "color": " #196F3D "},
		{"type": "workspace_action", "workspace_id": "w1", "action": "set_description", "description": "  "},
		{"type": "set_workspace_status", "workspace_id": "w1", "status": "review"},
		{"type": "rename_group", "group_id": "g1", "name": " Launch "},
		{"type": "pin_group", "group_id": "g1", "pinned": false},
		{"type": "set_group_color", "group_id": "g1", "color": ""},
		{"type": "add_to_group", "group_id": "g1", "workspace_id": "w2"},
		{"type": "ungroup", "group_id": "g1"},
		{"type": "reconnect_workspace", "workspace_id": "w2"},
	}
	for index, request := range requests {
		request["id"] = string(rune('a' + index))
		_ = wsjson.Write(ctx, conn, request)
		if result := resultFor(t, ctx, conn, request["id"].(string)); result["ok"] != true {
			t.Fatalf("%s failed: %v", request["type"], result)
		}
	}
	_ = wsjson.Write(ctx, conn, map[string]any{"type": "create_group", "id": "z", "name": "Q4", "workspace_ids": []string{"w1"}})
	if data := resultFor(t, ctx, conn, "z")["data"].(map[string]any); data["group_id"] != "g9" {
		t.Fatalf("create_group must answer with the new group: %v", data)
	}
	want := []string{
		`workspace.action {"action":"pin","workspace_id":"w1"}`,
		`workspace.action {"action":"set_color","color":"#196F3D","workspace_id":"w1"}`,
		`workspace.action {"action":"clear_description","workspace_id":"w1"}`,
		`workspace.status.set {"status":"review","workspace_id":"w1"}`,
		`workspace.group.rename {"group_id":"g1","name":"Launch"}`,
		`workspace.group.unpin {"group_id":"g1"}`,
		`workspace.group.set_color {"color":null,"group_id":"g1"}`,
		`workspace.group.add {"group_id":"g1","workspace_id":"w2"}`,
		`workspace.group.ungroup {"group_id":"g1"}`,
		`workspace.remote.reconnect {"workspace_id":"w2"}`,
		`workspace.group.create {"child_workspace_ids":["w1"],"name":"Q4"}`,
	}
	if calls := relay.cmux.performedCalls(); !reflect.DeepEqual(calls, want) {
		t.Fatalf("calls =\n%q\nwant\n%q", calls, want)
	}
}

func TestOrganizingRequestsRejectWhatCmuxWouldMisread(t *testing.T) {
	relay := startRelay(t)
	ctx, conn := relay.connect(t)
	requests := []map[string]any{
		{"type": "workspace_action", "workspace_id": "w1", "action": "close_others"},
		{"type": "workspace_action", "workspace_id": "w1", "action": "set_color"},
		{"type": "set_workspace_status", "workspace_id": "w1", "status": "blocked"},
		{"type": "set_checklist_item_state", "workspace_id": "w1", "item_id": "i1", "state": "done"},
		{"type": "add_checklist_item", "workspace_id": "w1", "text": " "},
		{"type": "create_group", "name": "Q4"},
		{"type": "remove_from_group", "group_id": "g1"},
	}
	for index, request := range requests {
		request["id"] = string(rune('a' + index))
		_ = wsjson.Write(ctx, conn, request)
		result := resultFor(t, ctx, conn, request["id"].(string))
		if result["ok"] != false || errorCode(result) != "invalid_params" {
			t.Fatalf("%v must be refused: %v", request, result)
		}
	}
	if calls := relay.cmux.performedCalls(); len(calls) != 0 {
		t.Fatalf("nothing may reach cmux: %q", calls)
	}
}

func TestChecklistEditsAnswerWithTheUpdatedChecklist(t *testing.T) {
	relay := startRelay(t)
	ctx, conn := relay.connect(t)
	relay.cmux.checklists = map[string]cmux.Checklist{"w1": {
		Items:    []cmux.ChecklistItem{{ID: "i1", Text: "write tests", State: "completed", Origin: "agent"}},
		Progress: cmux.ChecklistProgress{Completed: 1, Total: 1},
	}}
	requests := []map[string]any{
		{"type": "add_checklist_item", "workspace_id": "w1", "text": " ship it "},
		{"type": "set_checklist_item_state", "workspace_id": "w1", "item_id": "i1", "state": "completed"},
		{"type": "edit_checklist_item", "workspace_id": "w1", "item_id": "i1", "text": "write more tests"},
		{"type": "remove_checklist_item", "workspace_id": "w1", "item_id": "i1"},
		{"type": "clear_checklist", "workspace_id": "w1"},
		{"type": "checklist", "workspace_id": "w1"},
	}
	for index, request := range requests {
		request["id"] = string(rune('a' + index))
		_ = wsjson.Write(ctx, conn, request)
		data := resultFor(t, ctx, conn, request["id"].(string))["data"].(map[string]any)
		items := data["items"].([]any)
		if len(items) != 1 || items[0].(map[string]any)["origin"] != "agent" {
			t.Fatalf("%s must answer with the checklist: %v", request["type"], data)
		}
	}
	want := []string{
		`workspace.todo.add {"text":"ship it","workspace_id":"w1"}`,
		`workspace.todo.set_state {"id":"i1","state":"completed","workspace_id":"w1"}`,
		`workspace.todo.edit {"id":"i1","text":"write more tests","workspace_id":"w1"}`,
		`workspace.todo.remove {"id":"i1","workspace_id":"w1"}`,
		`workspace.todo.clear {"workspace_id":"w1"}`,
	}
	if calls := relay.cmux.performedCalls(); !reflect.DeepEqual(calls, want) {
		t.Fatalf("calls =\n%q\nwant\n%q", calls, want)
	}
}

func TestNewWorkspaceInAGroupOpensItsTerminal(t *testing.T) {
	relay := startRelay(t)
	ctx, conn := relay.connect(t)
	relay.cmux.performResults = map[string]string{cmux.MethodGroupNewSpace: `{"workspace_id":"w5"}`}
	relay.cmux.workspaceList = &cmux.WorkspaceList{Workspaces: []cmux.Workspace{{ID: "w5", Terminals: []cmux.Terminal{{ID: "t5"}}}}}

	_ = wsjson.Write(ctx, conn, map[string]any{"type": "create_workspace_in_group", "id": "1", "group_id": "g1"})
	data := resultFor(t, ctx, conn, "1")["data"].(map[string]any)
	if data["workspace_id"] != "w5" || data["surface_id"] != "t5" {
		t.Fatalf("the new workspace must come with its terminal: %v", data)
	}
}

func TestOrganizingNeedsACmuxThatHasIt(t *testing.T) {
	relay := startRelay(t)
	ctx, conn := relay.connect(t)
	relay.cmux.mu.Lock()
	relay.cmux.methods = []string{"system.identify"}
	relay.cmux.mu.Unlock()
	_ = wsjson.Write(ctx, conn, map[string]any{"type": "hello", "id": "1"})
	resultFor(t, ctx, conn, "1")

	_ = wsjson.Write(ctx, conn, map[string]any{"type": "set_workspace_status", "id": "2", "workspace_id": "w1", "status": "done"})
	if code := resultFor(t, ctx, conn, "2")["error"].(map[string]any)["code"]; code != "unsupported" {
		t.Fatalf("code = %v, want unsupported", code)
	}
}

func ptr(value string) *string { return &value }
