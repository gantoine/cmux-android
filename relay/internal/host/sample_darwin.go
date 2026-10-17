package host

import (
	"context"
	"os/exec"

	"golang.org/x/sys/unix"
)

func Sample(ctx context.Context) (Usage, error) {
	total, err := unix.SysctlUint64("hw.memsize")
	if err != nil {
		return Usage{}, err
	}
	cores, err := unix.SysctlUint32("hw.logicalcpu")
	if err != nil {
		return Usage{}, err
	}
	vmStat, err := exec.CommandContext(ctx, "/usr/bin/vm_stat").Output()
	if err != nil {
		return Usage{}, err
	}
	used, err := UsedMemory(vmStat)
	if err != nil {
		return Usage{}, err
	}
	processes, err := exec.CommandContext(ctx, "/bin/ps", "-A", "-o", "%cpu=").Output()
	if err != nil {
		return Usage{}, err
	}
	return Usage{
		CPUPercent:       CPUPercent(processes, int(cores)),
		MemoryUsedBytes:  min(used, total),
		MemoryTotalBytes: total,
	}, nil
}
