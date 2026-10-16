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
	errVMsUnsupported = &requestError{Code: "unsupported", Message: "This version of cmux can't list cloud VMs. Update cmux."}
	errCloudDisabled  = &requestError{Code: "cloud_disabled", Message: "cmux Cloud is off on this computer."}
)

func asleep(status string) bool {
	switch status {
	case "paused", "pausing", "stopped", "suspended":
		return true
	}
	return false
}

func (s *Server) vmError(method string, err error) error {
	if s.methods.observe(method, err) {
		return errVMsUnsupported
	}
	if cmux.ErrorCode(err) == "cloud_disabled" {
		return errCloudDisabled
	}
	return err
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
	if !s.methods.supports(cmux.MethodVMList) {
		return vmsResponse{}, errVMsUnsupported
	}
	list, err := s.cmux.VMs(ctx)
	if err != nil {
		return vmsResponse{}, s.vmError(cmux.MethodVMList, err)
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
		wait.Add(1)
		go func() {
			defer wait.Done()
			statsCtx, cancel := context.WithTimeout(ctx, vmStatsTimeout)
			defer cancel()
			if stats, err := s.cmux.VMStats(statsCtx, vm.ID); err == nil {
				response.VMs[index].Stats = &stats
			}
		}()
	}
	wait.Wait()
	return response, nil
}

func (s *Server) setVMPaused(ctx context.Context, id string, paused bool) (vmView, error) {
	method := cmux.MethodVMResume
	if paused {
		method = cmux.MethodVMPause
	}
	if !s.methods.supports(method) {
		return vmView{}, errVMsUnsupported
	}
	if err := s.cmux.SetVMPaused(ctx, id, paused); err != nil {
		return vmView{}, s.vmError(method, err)
	}
	vm, err := s.cmux.VMStatus(ctx, id)
	if err != nil {
		return vmView{}, s.vmError(cmux.MethodVMStatus, err)
	}
	return viewOf(vm), nil
}
