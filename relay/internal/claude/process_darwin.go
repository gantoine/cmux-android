package claude

import (
	"bytes"
	"encoding/binary"
	"errors"
	"syscall"

	"golang.org/x/sys/unix"
)

type SystemProcesses struct{}

func (SystemProcesses) Alive(pid int) bool {
	err := syscall.Kill(pid, 0)
	return err == nil || errors.Is(err, syscall.EPERM)
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
	index := 0
	for index < len(fields) && len(fields[index]) == 0 {
		index++
	}
	index++
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
