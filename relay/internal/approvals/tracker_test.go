package approvals

import (
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"sync"
	"testing"
	"time"

	"github.com/gantoine/muxer/relay/internal/cmux"
)

type fakeSource struct {
	tracker *Tracker

	mu      sync.Mutex
	items   []cmux.FeedItem
	replies []string
}

func newFakeSource() *fakeSource {
	return &fakeSource{}
}

func (f *fakeSource) FeedItems(context.Context) ([]cmux.FeedItem, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]cmux.FeedItem(nil), f.items...), nil
}

func (f *fakeSource) ReplyPermission(_ context.Context, requestID, mode string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.replies = append(f.replies, requestID+":"+mode)
	return nil
}

func (f *fakeSource) ReplyQuestion(_ context.Context, requestID string, selections []string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	encoded, _ := json.Marshal(selections)
	f.replies = append(f.replies, requestID+":"+string(encoded))
	return nil
}

func (f *fakeSource) ReplyExitPlan(_ context.Context, requestID, mode, feedback string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.replies = append(f.replies, requestID+":"+mode+":"+feedback)
	return nil
}

func (f *fakeSource) setItems(items ...cmux.FeedItem) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.items = items
}

func (f *fakeSource) emit(hook, phase, requestID, surfaceID string) {
	payload, _ := json.Marshal(map[string]string{
		"hook_event_name":      hook,
		"_opencode_request_id": requestID,
		"_source":              "claude",
		"workspace_id":         "w1",
		"surface_id":           surfaceID,
		"phase":                phase,
	})
	f.tracker.HandleEvent(context.Background(), cmux.Event{Type: "event", Name: "agent.hook." + hook, Category: "agent", Payload: payload})
}

func startTracker(t *testing.T, source *fakeSource) *Tracker {
	t.Helper()
	tracker := NewTracker(source, slog.New(slog.NewTextHandler(io.Discard, nil)))
	source.tracker = tracker
	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(cancel)
	go tracker.Run(ctx)
	return tracker
}

func waitFor(t *testing.T, tracker *Tracker, want int) []Approval {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for {
		pending := tracker.Pending()
		if len(pending) == want {
			return pending
		}
		if time.Now().After(deadline) {
			t.Fatalf("want %d pending approvals, got %+v", want, pending)
		}
		time.Sleep(10 * time.Millisecond)
	}
}

func TestPermissionRequestShowsTheFullCommandUntilCmuxCompletesIt(t *testing.T) {
	source := newFakeSource()
	source.setItems(cmux.FeedItem{
		Kind: "permissionRequest", Status: "pending", RequestID: "r1", Source: "claude", ToolName: "Bash",
		ToolInput: `{"command":"rm -rf build","description":"Clean the build folder"}`, Cwd: "/repo",
	})
	tracker := startTracker(t, source)

	source.emit("PermissionRequest", "received", "r1", "s1")
	pending := waitFor(t, tracker, 1)
	got := pending[0]
	if got.Kind != KindPermission || got.Tool != "Bash" || got.Detail != "rm -rf build" || got.Description != "Clean the build folder" {
		t.Fatalf("permission must carry the tool and its command: %+v", got)
	}
	if got.WorkspaceID != "w1" || got.SurfaceID != "s1" {
		t.Fatalf("permission must name the terminal it came from: %+v", got)
	}

	source.emit("PermissionRequest", "completed", "r1", "s1")
	waitFor(t, tracker, 0)
}

func TestTerminalAnswerClearsTheRequestOnTheNextAgentEvent(t *testing.T) {
	source := newFakeSource()
	question := cmux.FeedItem{
		Kind: "question", Status: "pending", RequestID: "q1", Source: "claude",
		Questions: []cmux.FeedQuestion{{Prompt: "Which?", Options: []cmux.FeedOption{{ID: "opt0", Label: "A"}, {ID: "opt1", Label: "B"}}}},
	}
	source.setItems(question)
	tracker := startTracker(t, source)

	source.emit("AskUserQuestion", "received", "q1", "s1")
	pending := waitFor(t, tracker, 1)
	if pending[0].Kind != KindQuestion || len(pending[0].Questions[0].Options) != 2 || pending[0].Questions[0].Options[1].Label != "B" {
		t.Fatalf("question must carry its options: %+v", pending[0])
	}

	source.emit("PreToolUse", "received", "other", "s2")
	time.Sleep(50 * time.Millisecond)
	waitFor(t, tracker, 1)

	question.Status = "expired"
	source.setItems(question)
	source.emit("PreToolUse", "received", "next", "s1")
	waitFor(t, tracker, 0)
}

func TestAnswerRepliesToCmuxAndRejectsInvalidAnswers(t *testing.T) {
	source := newFakeSource()
	source.setItems(
		cmux.FeedItem{Kind: "permissionRequest", Status: "pending", RequestID: "r1", ToolName: "Bash", ToolInput: `{"command":"ls"}`},
		cmux.FeedItem{Kind: "question", Status: "pending", RequestID: "q1", Questions: []cmux.FeedQuestion{{Prompt: "Which?", Options: []cmux.FeedOption{{Label: "A"}}}}},
	)
	tracker := startTracker(t, source)
	source.emit("PermissionRequest", "received", "r1", "s1")
	source.emit("AskUserQuestion", "received", "q1", "s1")
	waitFor(t, tracker, 2)

	ctx := context.Background()
	if err := tracker.Answer(ctx, "r1", Answer{Mode: "bypass"}); err == nil {
		t.Fatal("bypass must not be offered from the phone")
	}
	if err := tracker.Answer(ctx, "q1", Answer{Answers: []string{"A", "B"}}); err == nil {
		t.Fatal("a question needs exactly one answer per question")
	}
	if err := tracker.Answer(ctx, "r1", Answer{Mode: "once"}); err != nil {
		t.Fatal(err)
	}
	if err := tracker.Answer(ctx, "q1", Answer{Answers: []string{"A"}}); err != nil {
		t.Fatal(err)
	}
	if err := tracker.Answer(ctx, "r1", Answer{Mode: "once"}); err != ErrNotPending {
		t.Fatalf("answering twice must report the request is gone, got %v", err)
	}
	waitFor(t, tracker, 0)

	source.mu.Lock()
	defer source.mu.Unlock()
	if len(source.replies) != 2 || source.replies[0] != "r1:once" || source.replies[1] != `q1:["A"]` {
		t.Fatalf("unexpected replies to cmux: %v", source.replies)
	}
}

func TestPlanCanBeApprovedOrSentBackWithFeedback(t *testing.T) {
	source := newFakeSource()
	source.setItems(
		cmux.FeedItem{Kind: "exitPlan", Status: "pending", RequestID: "p1", Plan: `{"plan":"# Plan\n1. Add tests","planFilePath":"/Users/me/.claude/plans/p.md"}`},
		cmux.FeedItem{Kind: "exitPlan", Status: "pending", RequestID: "p2", Plan: "# Other"},
	)
	tracker := startTracker(t, source)
	source.emit("ExitPlanMode", "received", "p1", "s1")
	source.emit("ExitPlanMode", "received", "p2", "s1")
	pending := waitFor(t, tracker, 2)
	if pending[0].Kind != KindPlan || pending[0].Plan != "# Plan\n1. Add tests" {
		t.Fatalf("plan must carry its text: %+v", pending[0])
	}

	ctx := context.Background()
	for _, invalid := range []Answer{{Mode: "bypassPermissions"}, {Mode: "ultraplan"}, {Mode: "autoAccept", Feedback: "also do X"}} {
		if err := tracker.Answer(ctx, "p1", invalid); err == nil {
			t.Fatalf("%+v must be refused from the phone", invalid)
		}
	}
	if err := tracker.Answer(ctx, "p1", Answer{Mode: "autoAccept"}); err != nil {
		t.Fatal(err)
	}
	if err := tracker.Answer(ctx, "p2", Answer{Mode: "manual", Feedback: "Split step 2"}); err != nil {
		t.Fatal(err)
	}
	waitFor(t, tracker, 0)
	source.mu.Lock()
	defer source.mu.Unlock()
	if len(source.replies) != 2 || source.replies[0] != "p1:autoAccept:" || source.replies[1] != "p2:manual:Split step 2" {
		t.Fatalf("unexpected replies to cmux: %v", source.replies)
	}
}

func TestToolInputWithoutACommandIsShownAsJSON(t *testing.T) {
	detail, description := describeToolInput(`{"old_string":"a","new_string":"b","description":"tweak"}`)
	if description != "tweak" || detail != "{\n  \"new_string\": \"b\",\n  \"old_string\": \"a\"\n}" {
		t.Fatalf("got detail %q description %q", detail, description)
	}
	if detail, _ := describeToolInput(`{"file_path":"/repo/main.go","content":"x"}`); detail != "/repo/main.go" {
		t.Fatalf("file tools must show the path, got %q", detail)
	}
}
