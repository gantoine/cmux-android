package claude

import (
	"encoding/binary"
	"os"
	"os/exec"
	"testing"
	"time"
)

func TestEnvFromProcArgsSkipsTheCommandLine(t *testing.T) {
	raw := binary.LittleEndian.AppendUint32(nil, 2)
	raw = append(raw, "/bin/claude\x00\x00\x00claude\x00CMUX_SURFACE_ID=fake\x00HOME=/u\x00CMUX_SURFACE_ID=real\x00"...)
	if got := envFromProcArgs(raw, "CMUX_SURFACE_ID"); got != "real" {
		t.Errorf("got %q", got)
	}
	if got := envFromProcArgs(raw, "MISSING"); got != "" {
		t.Errorf("got %q", got)
	}
}

func TestSystemProcessesReadsThisProcess(t *testing.T) {
	processes := SystemProcesses{}
	started, ok := processes.StartedAt(os.Getpid())
	if !ok || started.After(time.Now()) {
		t.Fatalf("started at %v, alive %v", started, ok)
	}
	if got := processes.Env(os.Getpid(), "HOME"); got != os.Getenv("HOME") {
		t.Errorf("HOME = %q", got)
	}
}

func TestSystemProcessesReportsAnExitedProcessAsGone(t *testing.T) {
	command := exec.Command("true")
	if err := command.Run(); err != nil {
		t.Fatal(err)
	}
	if _, ok := (SystemProcesses{}).StartedAt(command.Process.Pid); ok {
		t.Error("an exited process must not count as running")
	}
}
