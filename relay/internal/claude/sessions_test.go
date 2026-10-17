package claude

import (
	"os"
	"path/filepath"
	"testing"
	"time"
)

type fakeProcesses struct {
	started map[int]time.Time
	env     map[int]string
}

func (f fakeProcesses) StartedAt(pid int) (time.Time, bool) {
	started, ok := f.started[pid]
	return started, ok
}

func (f fakeProcesses) Env(pid int, name string) string {
	if name != surfaceEnv {
		return ""
	}
	return f.env[pid]
}

func writeSession(t *testing.T, dir, name, content string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Join(dir, "sessions"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "sessions", name), []byte(content), 0o600); err != nil {
		t.Fatal(err)
	}
}

func TestSessionForSurfaceFollowsTheJobATerminalIsViewing(t *testing.T) {
	dir := t.TempDir()
	writeSession(t, dir, "1.json", `{"pid":1,"sessionId":"old","cwd":"/a","kind":"interactive","parkedJobId":"job2"}`)
	writeSession(t, dir, "2.json", `{"pid":2,"sessionId":"current","cwd":"/b","kind":"bg","jobId":"job2"}`)
	writeSession(t, dir, "3.json", `{"pid":3,"sessionId":"spare","kind":"bg","jobId":"job3","spare":true}`)
	writeSession(t, dir, "4.json", `{"pid":4,"sessionId":"other","cwd":"/c","kind":"interactive"}`)
	writeSession(t, dir, "5.json", `{"pid":5,"sessionId":"dead","kind":"interactive"}`)
	earlier := time.Now().Add(-time.Hour)
	processes := fakeProcesses{
		started: map[int]time.Time{1: earlier, 2: earlier, 3: earlier, 4: earlier},
		env:     map[int]string{1: "surface-a", 2: "surface-a", 3: "surface-a", 4: "surface-b", 5: "surface-c"},
	}
	sessions := RunningSessions(dir, processes)
	if len(sessions) != 3 {
		t.Fatalf("running sessions = %+v", sessions)
	}
	if ref, ok := SessionForSurface(sessions, "surface-a", processes); !ok || ref.SessionID != "current" || ref.Cwd != "/b" {
		t.Errorf("surface-a = %+v %v", ref, ok)
	}
	if ref, ok := SessionForSurface(sessions, "surface-b", processes); !ok || ref.SessionID != "other" {
		t.Errorf("surface-b = %+v %v", ref, ok)
	}
	if _, ok := SessionForSurface(sessions, "surface-c", processes); ok {
		t.Error("a dead process must not count")
	}
	if _, ok := SessionForSurface(sessions, "", processes); ok {
		t.Error("an empty surface must not match")
	}
}

func TestRunningSessionsIgnoresAReusedPID(t *testing.T) {
	dir := t.TempDir()
	writeSession(t, dir, "7.json", `{"pid":7,"sessionId":"stale","kind":"interactive"}`)
	processes := fakeProcesses{
		started: map[int]time.Time{7: time.Now().Add(time.Hour)},
		env:     map[int]string{7: "surface-a"},
	}
	if sessions := RunningSessions(dir, processes); len(sessions) != 0 {
		t.Fatalf("a process started after its session file was written is not that session: %+v", sessions)
	}
}

func TestSessionForSurfacePrefersTheNewestClaude(t *testing.T) {
	dir := t.TempDir()
	writeSession(t, dir, "10.json", `{"pid":10,"sessionId":"newer","kind":"interactive"}`)
	writeSession(t, dir, "9.json", `{"pid":9,"sessionId":"older","kind":"interactive"}`)
	processes := fakeProcesses{
		started: map[int]time.Time{9: time.Now().Add(-2 * time.Hour), 10: time.Now().Add(-time.Hour)},
		env:     map[int]string{9: "surface-a", 10: "surface-a"},
	}
	if ref, ok := SessionForSurface(RunningSessions(dir, processes), "surface-a", processes); !ok || ref.SessionID != "newer" {
		t.Errorf("surface-a = %+v %v", ref, ok)
	}
}

func TestResolverReusesOneScanForAWhile(t *testing.T) {
	dir := t.TempDir()
	writeSession(t, dir, "1.json", `{"pid":1,"sessionId":"first","cwd":"/a","kind":"interactive"}`)
	earlier := time.Now().Add(-time.Hour)
	processes := fakeProcesses{started: map[int]time.Time{1: earlier, 2: earlier}, env: map[int]string{1: "SURFACE-A", 2: "surface-b"}}
	now := time.Now()
	resolver := NewResolver(dir, processes, time.Minute)
	resolver.now = func() time.Time { return now }

	if ref, ok := resolver.ForSurface("surface-a"); !ok || ref.SessionID != "first" {
		t.Fatalf("surface-a = %+v %v", ref, ok)
	}
	writeSession(t, dir, "2.json", `{"pid":2,"sessionId":"second","cwd":"/b","kind":"interactive"}`)
	if _, ok := resolver.ForSurface("surface-b"); ok {
		t.Fatal("a fresh scan must be reused until it is too old")
	}
	now = now.Add(time.Minute)
	if ref, ok := resolver.ForSurface("surface-b"); !ok || ref.SessionID != "second" {
		t.Fatalf("an old scan must be replaced: %+v %v", ref, ok)
	}
}
