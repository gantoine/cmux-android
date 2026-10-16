package host

import (
	"bufio"
	"bytes"
	"context"
	"errors"
	"os/exec"
	"regexp"
	"strconv"
	"strings"

	"golang.org/x/sys/unix"
)

type Usage struct {
	CPUPercent       float64 `json:"cpu_percent"`
	MemoryUsedBytes  uint64  `json:"memory_used_bytes"`
	MemoryTotalBytes uint64  `json:"memory_total_bytes"`
}

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

var pageSizePattern = regexp.MustCompile(`page size of (\d+) bytes`)

func UsedMemory(vmStat []byte) (uint64, error) {
	match := pageSizePattern.FindSubmatch(vmStat)
	if match == nil {
		return 0, errors.New("vm_stat did not report a page size")
	}
	pageSize, _ := strconv.ParseUint(string(match[1]), 10, 64)
	pages := map[string]uint64{}
	scanner := bufio.NewScanner(bytes.NewReader(vmStat))
	for scanner.Scan() {
		name, value, found := strings.Cut(scanner.Text(), ":")
		if !found {
			continue
		}
		count, err := strconv.ParseUint(strings.TrimSuffix(strings.TrimSpace(value), "."), 10, 64)
		if err == nil {
			pages[strings.Trim(name, `"`)] = count
		}
	}
	appPages := pages["Anonymous pages"] - min(pages["Pages purgeable"], pages["Anonymous pages"])
	return (appPages + pages["Pages wired down"] + pages["Pages occupied by compressor"]) * pageSize, nil
}

func CPUPercent(psOutput []byte, cores int) float64 {
	var sum float64
	for _, field := range strings.Fields(string(psOutput)) {
		if value, err := strconv.ParseFloat(field, 64); err == nil {
			sum += value
		}
	}
	return min(sum/float64(max(cores, 1)), 100)
}
