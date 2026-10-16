package server

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"testing"
	"time"

	"github.com/coder/websocket/wsjson"

	"github.com/gantoine/muxer/relay/internal/cmux"
)

func TestEachTerminalCarriesItsOwnGitState(t *testing.T) {
	relay := startRelay(t)
	branch, dirty := "feat/approvals", true
	relay.cmux.terminalGit = []cmux.TerminalGit{
		{SurfaceID: "t1", GitBranch: &branch, GitDirty: &dirty, ListeningPorts: []int{3000, 5173}},
		{SurfaceID: "elsewhere", GitBranch: &branch},
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	conn, _, err := relay.dial(t, ctx, relay.pair(t))
	if err != nil {
		t.Fatal(err)
	}
	defer conn.CloseNow()

	_ = wsjson.Write(ctx, conn, map[string]any{"type": "hello", "id": "1"})
	workspaces := readUntil(t, ctx, conn, "workspaces")["workspaces"].([]any)
	terminals := workspaces[0].(map[string]any)["terminals"].([]any)
	if len(terminals) != 1 {
		t.Fatalf("git state must not add terminals: %v", terminals)
	}
	terminal := terminals[0].(map[string]any)
	if terminal["git_branch"] != "feat/approvals" || terminal["git_dirty"] != true {
		t.Fatalf("terminal must carry its own branch and dirty state: %v", terminal)
	}
	if ports := terminal["listening_ports"].([]any); len(ports) != 2 || ports[0] != float64(3000) {
		t.Fatalf("terminal must carry its listening ports: %v", terminal)
	}
}

func gitServer(fake *fakeCmux, intervals Intervals) *Server {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	return &Server{cmux: fake, options: Options{PollInterval: intervals}, logger: logger}
}

func TestFailedGitReadWaitsForTheNextInterval(t *testing.T) {
	fake := &fakeCmux{terminalGitErr: errors.New("decode debug.terminals result: unexpected shape")}
	s := gitServer(fake, DefaultIntervals)
	git := gitInfo{}
	git.refresh(context.Background(), s)
	git.refresh(context.Background(), s)
	if fake.gitReads != 1 {
		t.Fatalf("a failed read must not be retried on every workspace poll, got %d reads", fake.gitReads)
	}
}

func TestGitStateIsDroppedWhenCmuxStopsSupportingIt(t *testing.T) {
	branch := "main"
	fake := &fakeCmux{terminalGit: []cmux.TerminalGit{{SurfaceID: "t1", GitBranch: &branch}}}
	s := gitServer(fake, Intervals{})
	git := gitInfo{}
	git.refresh(context.Background(), s)
	fake.terminalGitErr = &cmux.RPCError{Code: "method_not_found"}
	git.refresh(context.Background(), s)
	workspaces := []cmux.Workspace{{ID: "w1", Terminals: []cmux.Terminal{{ID: "t1"}}}}
	git.apply(workspaces)
	if workspaces[0].Terminals[0].GitBranch != nil {
		t.Fatalf("stale git state must not outlive cmux support: %v", *workspaces[0].Terminals[0].GitBranch)
	}
}

func TestGitStateIsDroppedAfterRepeatedFailedReads(t *testing.T) {
	branch := "main"
	fake := &fakeCmux{terminalGit: []cmux.TerminalGit{{SurfaceID: "t1", GitBranch: &branch}}}
	s := gitServer(fake, Intervals{})
	git := gitInfo{}
	git.refresh(context.Background(), s)
	fake.terminalGitErr = errors.New("debug.terminals timed out")
	branchAfter := func() *string {
		workspaces := []cmux.Workspace{{ID: "w1", Terminals: []cmux.Terminal{{ID: "t1"}}}}
		git.apply(workspaces)
		return workspaces[0].Terminals[0].GitBranch
	}
	for range failedGitReadsBeforeClearing - 1 {
		git.refresh(context.Background(), s)
	}
	if branchAfter() == nil {
		t.Fatal("a brief run of failed reads must keep the last git state")
	}
	git.refresh(context.Background(), s)
	if branchAfter() != nil {
		t.Fatal("git state must be dropped once reads keep failing")
	}
}

func TestSuccessfulGitReadResetsTheFailureCount(t *testing.T) {
	branch := "main"
	fake := &fakeCmux{terminalGit: []cmux.TerminalGit{{SurfaceID: "t1", GitBranch: &branch}}}
	s := gitServer(fake, Intervals{})
	git := gitInfo{}
	for range failedGitReadsBeforeClearing - 1 {
		fake.terminalGitErr = errors.New("debug.terminals timed out")
		git.refresh(context.Background(), s)
		fake.terminalGitErr = nil
		git.refresh(context.Background(), s)
	}
	fake.terminalGitErr = errors.New("debug.terminals timed out")
	git.refresh(context.Background(), s)
	workspaces := []cmux.Workspace{{ID: "w1", Terminals: []cmux.Terminal{{ID: "t1"}}}}
	git.apply(workspaces)
	if workspaces[0].Terminals[0].GitBranch == nil {
		t.Fatal("only consecutive failed reads may drop git state")
	}
}
