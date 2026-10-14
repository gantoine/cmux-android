//go:build !darwin

package claude

type SystemProcesses struct{}

func (SystemProcesses) Alive(int) bool { return false }

func (SystemProcesses) Env(int, string) string { return "" }
