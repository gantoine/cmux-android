package claude

import (
	"os"
	"path/filepath"
	"testing"
)

type fakeProcesses struct {
	alive map[int]bool
	env   map[int]string
}

func (f fakeProcesses) Alive(pid int) bool { return f.alive[pid] }

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
	processes := fakeProcesses{
		alive: map[int]bool{1: true, 2: true, 3: true, 4: true},
		env:   map[int]string{1: "surface-a", 2: "surface-a", 3: "surface-a", 4: "surface-b", 5: "surface-c"},
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
}
