package claude

import (
	"encoding/json"
	"os"
	"path/filepath"
)

const surfaceEnv = "CMUX_SURFACE_ID"

type RunningSession struct {
	PID         int    `json:"pid"`
	SessionID   string `json:"sessionId"`
	Cwd         string `json:"cwd"`
	Kind        string `json:"kind"`
	JobID       string `json:"jobId"`
	ParkedJobID string `json:"parkedJobId"`
	Spare       bool   `json:"spare"`
}

type SessionRef struct {
	SessionID string
	Cwd       string
}

type ProcessInspector interface {
	Alive(pid int) bool
	Env(pid int, name string) string
}

func RunningSessions(configDir string, processes ProcessInspector) []RunningSession {
	paths, _ := filepath.Glob(filepath.Join(configDir, "sessions", "*.json"))
	var sessions []RunningSession
	for _, path := range paths {
		data, err := os.ReadFile(path)
		if err != nil {
			continue
		}
		var session RunningSession
		if json.Unmarshal(data, &session) != nil || session.PID <= 0 || session.SessionID == "" || session.Spare {
			continue
		}
		if processes.Alive(session.PID) {
			sessions = append(sessions, session)
		}
	}
	return sessions
}

func SessionForSurface(sessions []RunningSession, surfaceID string, processes ProcessInspector) (SessionRef, bool) {
	for _, session := range sessions {
		if session.Kind != "interactive" || processes.Env(session.PID, surfaceEnv) != surfaceID {
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
