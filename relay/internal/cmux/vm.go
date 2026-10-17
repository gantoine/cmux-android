package cmux

import "context"

const (
	MethodVMList   = "vm.list"
	MethodVMStatus = "vm.status"
	MethodVMStats  = "vm.stats"
	MethodVMPause  = "vm.pause"
	MethodVMResume = "vm.resume"
)

type VM struct {
	ID           string `json:"id"`
	DisplayName  string `json:"displayName"`
	Slug         string `json:"slug"`
	Status       string `json:"status"`
	Image        string `json:"image"`
	Kind         string `json:"kind"`
	CreatedAt    string `json:"createdAt"`
	Capabilities struct {
		Stats bool `json:"stats"`
	} `json:"capabilities"`
}

type VMLimits struct {
	PlanID string `json:"planId"`
}

type VMList struct {
	VMs    []VM      `json:"vms"`
	Limits *VMLimits `json:"limits"`
}

type VMStats struct {
	ID            string   `json:"id"`
	State         string   `json:"state"`
	SampledAtUnix int64    `json:"sampled_at_unix"`
	CPUs          *int     `json:"cpus"`
	CPUPercent    *float64 `json:"cpu_percent"`
	MemoryTotalMB *int     `json:"memory_total_mb"`
	MemoryUsedMB  *int     `json:"memory_used_mb"`
	DiskTotalMB   *int     `json:"disk_total_mb"`
	DiskUsedMB    *int     `json:"disk_used_mb"`
}

func (c *Client) VMs(ctx context.Context) (VMList, error) {
	var list VMList
	err := c.Call(ctx, MethodVMList, nil, &list)
	return list, err
}

func (c *Client) VMStatus(ctx context.Context, id string) (VM, error) {
	var vm VM
	err := c.Call(ctx, MethodVMStatus, map[string]any{"id": id}, &vm)
	return vm, err
}

func (c *Client) VMStats(ctx context.Context, id string) (VMStats, error) {
	var stats VMStats
	err := c.Call(ctx, MethodVMStats, map[string]any{"id": id}, &stats)
	return stats, err
}

func (c *Client) SetVMPaused(ctx context.Context, id string, paused bool) error {
	method := MethodVMResume
	if paused {
		method = MethodVMPause
	}
	return c.Call(ctx, method, map[string]any{"id": id}, nil)
}
