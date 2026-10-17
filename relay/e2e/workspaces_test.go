//go:build e2e

package e2e

import (
	"testing"
)

func TestHelloReportsCmuxAndEveryRequestSupported(t *testing.T) {
	p := connect(t)
	if text(p.welcome["cmux_version"]) == "" {
		t.Fatalf("hello carried no cmux version: %v", p.welcome)
	}
	unsupported, listed := p.welcome["unsupported"].([]any)
	if !listed || len(unsupported) != 0 {
		t.Fatalf("this cmux can't serve %v; a method the relay relies on is gone or renamed", p.welcome["unsupported"])
	}
}

func TestWorkspaceChangesReachThePhone(t *testing.T) {
	p := connect(t)
	ws := scratchWorkspace(t, p)
	p.workspaceSatisfies(ws.ID, "the new workspace", func(w message) bool { return w["title"] == ws.Title })

	renamed := ws.Title + "-renamed"
	p.must("rename_workspace", map[string]any{"workspace_id": ws.ID, "title": renamed})
	p.workspaceSatisfies(ws.ID, "the rename", func(w message) bool { return w["title"] == renamed })

	p.must("workspace_action", map[string]any{"workspace_id": ws.ID, "action": "pin"})
	p.workspaceSatisfies(ws.ID, "the pin", func(w message) bool { return flag(w["is_pinned"]) })
	p.must("workspace_action", map[string]any{"workspace_id": ws.ID, "action": "unpin"})
	p.workspaceSatisfies(ws.ID, "the unpin", func(w message) bool { return !flag(w["is_pinned"]) })

	p.must("workspace_action", map[string]any{"workspace_id": ws.ID, "action": "set_color", "color": "#1565C0"})
	p.workspaceSatisfies(ws.ID, "the color", func(w message) bool { return w["custom_color"] == "#1565C0" })
	p.must("workspace_action", map[string]any{"workspace_id": ws.ID, "action": "clear_color"})
	p.workspaceSatisfies(ws.ID, "the cleared color", func(w message) bool { return text(w["custom_color"]) == "" })

	p.must("workspace_action", map[string]any{"workspace_id": ws.ID, "action": "set_description", "description": "e2e description"})
	p.workspaceSatisfies(ws.ID, "the description", func(w message) bool { return w["description"] == "e2e description" })

	p.must("workspace_action", map[string]any{"workspace_id": ws.ID, "action": "mark_unread"})
	p.workspaceSatisfies(ws.ID, "the unread mark", func(w message) bool { return flag(w["has_unread"]) })
	p.must("workspace_action", map[string]any{"workspace_id": ws.ID, "action": "mark_read"})
	p.workspaceSatisfies(ws.ID, "the read mark", func(w message) bool { return !flag(w["has_unread"]) })

	p.must("set_workspace_status", map[string]any{"workspace_id": ws.ID, "status": "review"})
	p.workspaceSatisfies(ws.ID, "the review lane", func(w message) bool { return object(w["status"])["effective"] == "review" })
	p.must("set_workspace_status", map[string]any{"workspace_id": ws.ID, "status": "auto"})
	p.workspaceSatisfies(ws.ID, "the automatic lane", func(w message) bool { return !flag(object(w["status"])["override"]) })

	p.must("close_workspace", map[string]any{"workspace_id": ws.ID, "force": true})
	p.workspaceGone(ws.ID)
}

func TestWorkspaceCreatedInCmuxReachesThePhone(t *testing.T) {
	p := connect(t)
	title := uniqueName(t)
	cmuxCLI(t, "workspace", "create", "--name", title, "--focus", "false")
	created := p.eventually("workspaces", "the workspace made in cmux", func(push message) bool {
		return find(list(push["workspaces"]), "title", title) != nil
	})
	id := text(find(list(created["workspaces"]), "title", title)["id"])
	t.Cleanup(func() { closeWorkspace(id) })
}

func TestGroupsCanBeOrganizedFromThePhone(t *testing.T) {
	p := connect(t)
	anchor := scratchWorkspace(t, p)
	member := scratchWorkspace(t, p)
	name := uniqueName(t)

	created := p.must("create_group", map[string]any{"name": name, "workspace_ids": []string{anchor.ID}})
	groupID := text(created["group_id"])
	if groupID == "" {
		t.Fatalf("create_group answered %v", created)
	}
	group := func(what string, matches func(message) bool) {
		t.Helper()
		p.eventually("workspaces", what, func(push message) bool {
			g := find(list(push["groups"]), "id", groupID)
			return g != nil && matches(g)
		})
	}
	group("the new group", func(g message) bool { return g["anchor_workspace_id"] == anchor.ID })

	p.must("rename_group", map[string]any{"group_id": groupID, "name": name + "-renamed"})
	group("the group rename", func(g message) bool { return g["name"] == name+"-renamed" })

	p.must("pin_group", map[string]any{"group_id": groupID, "pinned": true})
	group("the group pin", func(g message) bool { return flag(g["is_pinned"]) })

	p.must("set_group_color", map[string]any{"group_id": groupID, "color": "#2E7D32"})
	group("the group color", func(g message) bool { return g["custom_color"] == "#2E7D32" })

	p.must("add_to_group", map[string]any{"group_id": groupID, "workspace_id": member.ID})
	p.workspaceSatisfies(member.ID, "the member joining", func(w message) bool { return w["group_id"] == groupID })

	p.must("remove_from_group", map[string]any{"group_id": groupID, "workspace_id": member.ID})
	p.workspaceSatisfies(member.ID, "the member leaving", func(w message) bool { return text(w["group_id"]) == "" })

	inGroup := p.must("create_workspace_in_group", map[string]any{"group_id": groupID, "title": name + "-child"})
	childID := text(inGroup["workspace_id"])
	if childID == "" || text(inGroup["surface_id"]) == "" {
		t.Fatalf("create_workspace_in_group answered %v", inGroup)
	}
	t.Cleanup(func() { closeWorkspace(childID) })
	p.workspaceSatisfies(childID, "the workspace made in the group", func(w message) bool { return w["group_id"] == groupID })

	p.must("ungroup", map[string]any{"group_id": groupID})
	p.eventually("workspaces", "the group to go away", func(push message) bool {
		return find(list(push["groups"]), "id", groupID) == nil
	})
}

func TestChecklistEditsReachCmux(t *testing.T) {
	p := connect(t)
	ws := scratchWorkspace(t, p)
	items := func(checklist message) []message { return list(checklist["items"]) }

	empty := p.must("checklist", map[string]any{"workspace_id": ws.ID})
	if len(items(empty)) != 0 {
		t.Fatalf("a new workspace should have an empty checklist, got %v", empty)
	}
	p.must("add_checklist_item", map[string]any{"workspace_id": ws.ID, "text": "first"})
	added := p.must("add_checklist_item", map[string]any{"workspace_id": ws.ID, "text": "second"})
	first := find(items(added), "text", "first")
	if first == nil || len(items(added)) != 2 {
		t.Fatalf("adding two items gave %v", added)
	}
	firstID := text(first["id"])

	edited := p.must("edit_checklist_item", map[string]any{"workspace_id": ws.ID, "item_id": firstID, "text": "first edited"})
	if find(items(edited), "text", "first edited") == nil {
		t.Fatalf("the edit didn't stick: %v", edited)
	}
	done := p.must("set_checklist_item_state", map[string]any{"workspace_id": ws.ID, "item_id": firstID, "state": "completed"})
	if find(items(done), "id", firstID)["state"] != "completed" {
		t.Fatalf("the item isn't completed: %v", done)
	}
	p.workspaceSatisfies(ws.ID, "checklist progress on the workspace", func(w message) bool {
		progress := object(w["checklist"])
		return progress["completed"] == float64(1) && progress["total"] == float64(2)
	})

	removed := p.must("remove_checklist_item", map[string]any{"workspace_id": ws.ID, "item_id": firstID})
	if len(items(removed)) != 1 {
		t.Fatalf("removing an item left %v", removed)
	}
	cleared := p.must("clear_checklist", map[string]any{"workspace_id": ws.ID})
	if len(items(cleared)) != 0 {
		t.Fatalf("clearing left %v", cleared)
	}
}
