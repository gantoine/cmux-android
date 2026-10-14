package claude

import (
	"bytes"
	"encoding/binary"
	"time"

	"golang.org/x/sys/unix"
)

type SystemProcesses struct{}

func (SystemProcesses) StartedAt(pid int) (time.Time, bool) {
	info, err := unix.SysctlKinfoProc("kern.proc.pid", pid)
	if err != nil || int(info.Proc.P_pid) != pid {
		return time.Time{}, false
	}
	return time.Unix(info.Proc.P_starttime.Unix()), true
}

func (SystemProcesses) Env(pid int, name string) string {
	raw, err := unix.SysctlRaw("kern.procargs2", pid)
	if err != nil || len(raw) < 4 {
		return ""
	}
	return envFromProcArgs(raw, name)
}

func envFromProcArgs(raw []byte, name string) string {
	argc := int(binary.LittleEndian.Uint32(raw[:4]))
	fields := bytes.Split(raw[4:], []byte{0})
	index := 1
	for index < len(fields) && len(fields[index]) == 0 {
		index++
	}
	index += argc
	prefix := []byte(name + "=")
	for ; index < len(fields); index++ {
		if bytes.HasPrefix(fields[index], prefix) {
			return string(fields[index][len(prefix):])
		}
	}
	return ""
}
