//go:build !darwin

package claude

import "time"

type SystemProcesses struct{}

func (SystemProcesses) StartedAt(int) (time.Time, bool) { return time.Time{}, false }

func (SystemProcesses) Env(int, string) string { return "" }
