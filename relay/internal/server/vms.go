package server

import (
	"cmp"
	"context"
	"sync"
	"time"

	"github.com/gantoine/muxer/relay/internal/cmux"
)

const vmStatsTimeout = 5 * time.Second

type vmView struct {
	ID        string        `json:"id"`
	Name      string        `json:"name"`
	Status    string        `json:"status"`
	Image     string        `json:"image,omitempty"`
	Kind      string        `json:"kind,omitempty"`
	CreatedAt string        `json:"created_at,omitempty"`
	Stats     *cmux.VMStats `json:"stats,omitempty"`
}

type vmsResponse struct {
	VMs  []vmView `json:"vms"`
	Plan string   `json:"plan,omitempty"`
}

var (
	errVMsUnsupported = unsupported("list cloud VMs")
	errCloudDisabled  = &requestError{Code: "cloud_disabled", Message: "cmux Cloud is off on this computer."}
)

func asleep(status string) bool {
	switch status {
	case "paused", "pausing", "stopped", "suspended":
		return true
	}
	return false
}

func vmCall[T any](s *Server, method string, call func() (T, error)) (T, error) {
	result, err := guarded(s, method, errVMsUnsupported, call)
	if cmux.IsCloudDisabled(err) {
		return result, errCloudDisabled
	}
	return result, err
}

func viewOf(vm cmux.VM) vmView {
	return vmView{
		ID:        vm.ID,
		Name:      cmp.Or(vm.DisplayName, vm.Slug, vm.ID),
		Status:    vm.Status,
		Image:     vm.Image,
		Kind:      vm.Kind,
		CreatedAt: vm.CreatedAt,
	}
}

func (s *Server) vms(ctx context.Context) (vmsResponse, error) {
	list, err := vmCall(s, cmux.MethodVMList, func() (cmux.VMList, error) { return s.cmux.VMs(ctx) })
	if err != nil {
		return vmsResponse{}, err
	}
	response := vmsResponse{VMs: make([]vmView, len(list.VMs))}
	if list.Limits != nil {
		response.Plan = list.Limits.PlanID
	}
	withStats := s.methods.supports(cmux.MethodVMStats)
	var wait sync.WaitGroup
	for index, vm := range list.VMs {
		response.VMs[index] = viewOf(vm)
		if !withStats || !vm.Capabilities.Stats || asleep(vm.Status) {
			continue
		}
		wait.Go(func() {
			statsCtx, cancel := context.WithTimeout(ctx, vmStatsTimeout)
			defer cancel()
			if stats, ok := probe(s, cmux.MethodVMStats, func() (cmux.VMStats, error) { return s.cmux.VMStats(statsCtx, vm.ID) }); ok {
				response.VMs[index].Stats = &stats
			}
		})
	}
	wait.Wait()
	return response, nil
}

func (s *Server) setVMPaused(ctx context.Context, id string, paused bool) (vmView, error) {
	method := cmux.MethodVMResume
	if paused {
		method = cmux.MethodVMPause
	}
	if _, err := vmCall(s, method, func() (struct{}, error) { return struct{}{}, s.cmux.SetVMPaused(ctx, id, paused) }); err != nil {
		return vmView{}, err
	}
	vm, err := vmCall(s, cmux.MethodVMStatus, func() (cmux.VM, error) { return s.cmux.VMStatus(ctx, id) })
	if err != nil {
		return vmView{}, err
	}
	return viewOf(vm), nil
}
