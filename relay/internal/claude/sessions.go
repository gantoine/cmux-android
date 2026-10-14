package claude

import (
	"encoding/json"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"time"
)

const (
	surfaceEnv          = "CMUX_SURFACE_ID"
	fileTimeGranularity = 2 * time.Second
)

type RunningSession struct {
	PID         int    `json:"pid"`
	SessionID   string `json:"sessionId"`
	Cwd         string `json:"cwd"`
	Kind        string `json:"kind"`
	JobID       string `json:"jobId"`
	ParkedJobID string `json:"parkedJobId"`
	Spare       bool   `json:"spare"`

	startedAt time.Time
}

type SessionRef struct {
	SessionID string
	Cwd       string
}

type ProcessInspector interface {
	StartedAt(pid int) (time.Time, bool)
	Env(pid int, name string) string
}

func RunningSessions(configDir string, processes ProcessInspector) []RunningSession {
	if configDir == "" {
		return nil
	}
	paths, _ := filepath.Glob(filepath.Join(configDir, "sessions", "*.json"))
	var sessions []RunningSession
	for _, path := range paths {
		session, written, ok := readSession(path)
		if !ok {
			continue
		}
		started, alive := processes.StartedAt(session.PID)
		if alive && started.Before(written.Add(fileTimeGranularity)) {
			session.startedAt = started
			sessions = append(sessions, session)
		}
	}
	slices.SortStableFunc(sessions, func(a, b RunningSession) int { return b.startedAt.Compare(a.startedAt) })
	return sessions
}

func readSession(path string) (RunningSession, time.Time, bool) {
	var session RunningSession
	info, err := os.Stat(path)
	if err != nil {
		return session, time.Time{}, false
	}
	data, err := os.ReadFile(path)
	if err != nil || json.Unmarshal(data, &session) != nil || session.PID <= 0 || session.SessionID == "" || session.Spare {
		return session, time.Time{}, false
	}
	return session, info.ModTime(), true
}

func SessionForSurface(sessions []RunningSession, surfaceID string, processes ProcessInspector) (SessionRef, bool) {
	if surfaceID == "" {
		return SessionRef{}, false
	}
	for _, session := range sessions {
		if session.Kind != "interactive" || !strings.EqualFold(processes.Env(session.PID, surfaceEnv), surfaceID) {
			continue
		}
		if session.ParkedJobID != "" {
			for _, job := range sessions {
				if job.JobID == session.ParkedJobID {
					return SessionRef{SessionID: job.SessionID, Cwd: job.Cwd}, true
				}
			}
		}
		return SessionRef{SessionID: session.SessionID, Cwd: session.Cwd}, true
	}
	return SessionRef{}, false
}
