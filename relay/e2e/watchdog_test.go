//go:build e2e

package e2e

import (
	"context"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"

	"github.com/gantoine/muxer/relay/internal/cmux"
)

const (
	watchdogInterval = 2 * time.Second
	stallThreshold   = 5 * time.Second
	maxStallReports  = 3
)

func watchCmux(ctx context.Context, dir string) {
	if dir == "" {
		return
	}
	client := cmux.NewClient(cmux.DefaultSocketPath(), cmux.DefaultPasswordSource())
	defer client.Close()
	reports := 0
	stalled := false
	ticker := time.NewTicker(watchdogInterval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}
		pingCtx, cancel := context.WithTimeout(ctx, stallThreshold)
		started := time.Now()
		_, err := client.Identify(pingCtx)
		cancel()
		switch {
		case err == nil:
			if stalled {
				fmt.Fprintf(os.Stderr, "cmux answered again at %s\n", time.Now().Format(time.TimeOnly))
			}
			stalled = false
		case ctx.Err() != nil:
			return
		case !stalled && reports < maxStallReports:
			stalled = true
			reports++
			fmt.Fprintf(os.Stderr, "cmux stopped answering at %s (%v); recording diagnostics\n", started.Format(time.TimeOnly), err)
			recordStall(dir, started)
		}
	}
}

func recordStall(dir string, at time.Time) {
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return
	}
	prefix := filepath.Join(dir, "stall-"+at.Format("150405"))
	if pid := cmuxAppPID(); pid != "" {
		_ = exec.Command("sample", pid, "3", "-file", prefix+"-sample.txt").Run()
	}
	status, _ := exec.Command(cmuxPath(), "socket-status", "--json").CombinedOutput()
	if len(status) > 0 {
		_ = os.WriteFile(prefix+"-socket-status.json", status, 0o644)
	}
}

func cmuxAppPID() string {
	listing, err := exec.Command("ps", "-axo", "pid=,comm=").Output()
	if err != nil {
		return ""
	}
	for _, line := range strings.Split(string(listing), "\n") {
		pid, command, found := strings.Cut(strings.TrimSpace(line), " ")
		if found && strings.HasSuffix(strings.TrimSpace(command), "cmux.app/Contents/MacOS/cmux") {
			return pid
		}
	}
	return ""
}
