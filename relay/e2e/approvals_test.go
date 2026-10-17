//go:build e2e

package e2e

import (
	"strings"
	"testing"
)

func pendingApproval(t *testing.T, p *phone, ws workspace, kind string) message {
	t.Helper()
	var found message
	p.eventually("approvals", "a "+kind+" approval on the scratch terminal", func(push message) bool {
		for _, approval := range list(push["approvals"]) {
			if approval["surface_id"] == ws.SurfaceID && approval["kind"] == kind {
				found = approval
				return true
			}
		}
		return false
	})
	return found
}

func answered(t *testing.T, p *phone, id string) {
	t.Helper()
	p.eventually("approvals", "approval "+id+" to leave the list", func(push message) bool {
		return find(list(push["approvals"]), "id", id) == nil
	})
}

func TestPermissionRequestsAreAnsweredFromThePhone(t *testing.T) {
	cases := []struct {
		mode     string
		behavior string
	}{
		{"once", "allow"},
		{"always", "allow"},
		{"deny", "deny"},
	}
	for _, c := range cases {
		t.Run(c.mode, func(t *testing.T) {
			p := connect(t)
			ws := scratchWorkspace(t, p)
			hook := raiseHook(t, ws, "Bash", map[string]any{"command": "echo e2e", "description": "E2E check"})
			approval := pendingApproval(t, p, ws, "permission")
			if approval["tool"] != "Bash" || approval["detail"] != "echo e2e" || approval["description"] != "E2E check" {
				t.Fatalf("the approval lost the tool details: %v", approval)
			}
			id := text(approval["id"])
			p.must("answer_approval", map[string]any{"approval_id": id, "mode": c.mode})
			if decision := hook.decision(t); decision["behavior"] != c.behavior {
				t.Fatalf("answering %s gave the agent %v", c.mode, decision)
			}
			answered(t, p, id)
		})
	}
}

func TestQuestionsAreAnsweredFromThePhone(t *testing.T) {
	p := connect(t)
	ws := scratchWorkspace(t, p)
	hook := raiseHook(t, ws, "AskUserQuestion", map[string]any{"questions": []any{map[string]any{
		"question":    "Red or blue?",
		"header":      "Color",
		"multiSelect": false,
		"options": []any{
			map[string]any{"label": "Red", "description": "Choose red"},
			map[string]any{"label": "Blue", "description": "Choose blue"},
		},
	}}})
	approval := pendingApproval(t, p, ws, "question")
	questions := list(approval["questions"])
	if len(questions) != 1 || questions[0]["prompt"] != "Red or blue?" || len(list(questions[0]["options"])) != 2 {
		t.Fatalf("the question arrived incomplete: %v", approval)
	}
	id := text(approval["id"])
	p.must("answer_approval", map[string]any{"approval_id": id, "answers": []string{"Blue"}})
	decision := hook.decision(t)
	answers := object(object(decision["updatedInput"])["answers"])
	if decision["behavior"] != "allow" || answers["Red or blue?"] != "Blue" {
		t.Fatalf("the agent got %v", decision)
	}
	answered(t, p, id)
}

func TestPlansAreApprovedOrRejectedFromThePhone(t *testing.T) {
	t.Run("approve", func(t *testing.T) {
		p := connect(t)
		ws := scratchWorkspace(t, p)
		hook := raiseHook(t, ws, "ExitPlanMode", map[string]any{"plan": "1. Write e2e.txt"})
		approval := pendingApproval(t, p, ws, "plan")
		if approval["plan"] != "1. Write e2e.txt" {
			t.Fatalf("the plan text was lost: %v", approval)
		}
		p.must("answer_approval", map[string]any{"approval_id": approval["id"], "mode": "manual"})
		if decision := hook.decision(t); decision["behavior"] != "allow" {
			t.Fatalf("approving the plan gave the agent %v", decision)
		}
	})
	t.Run("reject with feedback", func(t *testing.T) {
		p := connect(t)
		ws := scratchWorkspace(t, p)
		hook := raiseHook(t, ws, "ExitPlanMode", map[string]any{"plan": "1. Write e2e.txt"})
		approval := pendingApproval(t, p, ws, "plan")
		p.must("answer_approval", map[string]any{"approval_id": approval["id"], "mode": "deny", "feedback": "e2e feedback"})
		decision := hook.decision(t)
		if decision["behavior"] != "deny" || !strings.Contains(text(decision["message"]), "e2e feedback") {
			t.Fatalf("rejecting the plan gave the agent %v", decision)
		}
	})
}
