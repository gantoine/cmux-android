package claude

import (
	"encoding/binary"
	"os"
	"testing"
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
	t.Setenv("CLAUDE_RELAY_TEST_ENV", "x")
	processes := SystemProcesses{}
	if !processes.Alive(os.Getpid()) {
		t.Fatal("this process is not alive")
	}
	if got := processes.Env(os.Getpid(), "HOME"); got != os.Getenv("HOME") {
		t.Errorf("HOME = %q", got)
	}
}
