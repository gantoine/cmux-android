package claude

import (
	"encoding/json"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"sync"
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
	ref, ok := sessionsBySurface(sessions, processes)[strings.ToLower(surfaceID)]
	return ref, ok
}

type Resolver struct {
	configDir string
	processes ProcessInspector
	maxAge    time.Duration
	now       func() time.Time

	mu        sync.Mutex
	scannedAt time.Time
	bySurface map[string]SessionRef
}

func NewResolver(configDir string, processes ProcessInspector, maxAge time.Duration) *Resolver {
	return &Resolver{configDir: configDir, processes: processes, maxAge: maxAge, now: time.Now}
}

func (r *Resolver) ForSurface(surfaceID string) (SessionRef, bool) {
	if surfaceID == "" {
		return SessionRef{}, false
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.bySurface == nil || r.now().Sub(r.scannedAt) >= r.maxAge {
		r.bySurface = sessionsBySurface(RunningSessions(r.configDir, r.processes), r.processes)
		r.scannedAt = r.now()
	}
	ref, ok := r.bySurface[strings.ToLower(surfaceID)]
	return ref, ok
}

func sessionsBySurface(sessions []RunningSession, processes ProcessInspector) map[string]SessionRef {
	byJob := map[string]RunningSession{}
	for _, session := range sessions {
		if _, seen := byJob[session.JobID]; session.JobID != "" && !seen {
			byJob[session.JobID] = session
		}
	}
	bySurface := map[string]SessionRef{}
	for _, session := range sessions {
		if session.Kind != "interactive" {
			continue
		}
		surfaceID := strings.ToLower(processes.Env(session.PID, surfaceEnv))
		if _, seen := bySurface[surfaceID]; surfaceID == "" || seen {
			continue
		}
		if job, ok := byJob[session.ParkedJobID]; ok && session.ParkedJobID != "" {
			session = job
		}
		bySurface[surfaceID] = SessionRef{SessionID: session.SessionID, Cwd: session.Cwd}
	}
	return bySurface
}
