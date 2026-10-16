package server

import (
	"cmp"
	"context"
	"path/filepath"
	"slices"
	"strings"
	"unicode"

	"github.com/gantoine/muxer/relay/internal/cmux"
)

const topProcessesPerWorkspace = 5

type topSummary struct {
	SampledAt      string         `json:"sampled_at"`
	CPUPercent     float64        `json:"cpu_percent"`
	MemoryBytes    int64          `json:"memory_bytes"`
	ProcessCount   int            `json:"process_count"`
	AppMemoryBytes int64          `json:"app_memory_bytes"`
	Agents         []topAgent     `json:"agents"`
	Workspaces     []topWorkspace `json:"workspaces"`
}

type topAgent struct {
	Name string `json:"name"`
	cmux.Resources
}

type topWorkspace struct {
	ID    string `json:"id"`
	Title string `json:"title"`
	cmux.Resources
	Processes []topProcess `json:"processes"`
}

type topProcess struct {
	PID         int     `json:"pid"`
	Name        string  `json:"name"`
	CPUPercent  float64 `json:"cpu_percent"`
	MemoryBytes int64   `json:"memory_bytes"`
}

var errTopUnsupported = &requestError{Code: "unsupported", Message: "This version of cmux can't report CPU and memory use. Update cmux."}

func (s *Server) top(ctx context.Context) (topSummary, error) {
	if !s.methods.supports(cmux.MethodTop) {
		return topSummary{}, errTopUnsupported
	}
	top, err := s.cmux.Top(ctx)
	if s.methods.observe(cmux.MethodTop, err) {
		return topSummary{}, errTopUnsupported
	}
	if err != nil {
		return topSummary{}, err
	}
	return summarizeTop(top), nil
}

func summarizeTop(top cmux.Top) topSummary {
	summary := topSummary{
		SampledAt:      top.Sample.SampledAt,
		CPUPercent:     top.Totals.CPUPercent,
		MemoryBytes:    top.Totals.MemoryBytes,
		ProcessCount:   top.Totals.ProcessCount,
		AppMemoryBytes: top.MemoryDiagnostic.App.PhysicalFootprintBytes,
		Agents:         []topAgent{},
		Workspaces:     []topWorkspace{},
	}
	for _, agent := range top.CodingAgents {
		summary.Agents = append(summary.Agents, topAgent{Name: cmp.Or(agent.DisplayName, agent.ID), Resources: agent.Resources})
	}
	for _, window := range top.Windows {
		for _, workspace := range window.Workspaces {
			summary.Workspaces = append(summary.Workspaces, topWorkspace{
				ID:        workspace.ID,
				Title:     workspace.Title,
				Resources: workspace.Resources,
				Processes: heaviestProcesses(workspace),
			})
		}
	}
	slices.SortStableFunc(summary.Workspaces, func(a, b topWorkspace) int { return cmp.Compare(b.MemoryBytes, a.MemoryBytes) })
	return summary
}

func heaviestProcesses(workspace cmux.TopWorkspace) []topProcess {
	byPID := map[int]topProcess{}
	var visit func([]cmux.TopProcess)
	visit = func(processes []cmux.TopProcess) {
		for _, process := range processes {
			if process.PID > 0 && (process.Resources.MemoryBytes > 0 || process.Resources.CPUPercent > 0) {
				byPID[process.PID] = topProcess{
					PID:         process.PID,
					Name:        processName(process),
					CPUPercent:  process.Resources.CPUPercent,
					MemoryBytes: process.Resources.MemoryBytes,
				}
			}
			visit(process.Children)
		}
	}
	for _, pane := range workspace.Panes {
		for _, surface := range pane.Surfaces {
			visit(surface.Processes)
		}
	}
	processes := make([]topProcess, 0, len(byPID))
	for _, process := range byPID {
		processes = append(processes, process)
	}
	slices.SortFunc(processes, func(a, b topProcess) int {
		return cmp.Or(cmp.Compare(b.MemoryBytes, a.MemoryBytes), cmp.Compare(a.PID, b.PID))
	})
	return processes[:min(len(processes), topProcessesPerWorkspace)]
}

func processName(process cmux.TopProcess) string {
	if base := filepath.Base(process.Path); process.Name != "" && hasLetter(base) && strings.HasPrefix(base, process.Name) {
		return base
	}
	if hasLetter(process.Name) {
		return process.Name
	}
	parts := strings.Split(filepath.ToSlash(process.Path), "/")
	for index := len(parts) - 2; index >= 0; index-- {
		if hasLetter(parts[index]) && parts[index] != "versions" {
			return parts[index]
		}
	}
	return cmp.Or(process.Name, filepath.Base(process.Path))
}

func hasLetter(text string) bool {
	return strings.IndexFunc(text, unicode.IsLetter) >= 0
}
