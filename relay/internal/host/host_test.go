package host

import (
	"context"
	"testing"
)

const vmStatFixture = `Mach Virtual Memory Statistics: (page size of 16384 bytes)
Pages free:                                    11450.
Pages active:                                1204404.
Pages wired down:                             238518.
Pages purgeable:                                5651.
"Translation faults":                     2949582450.
Anonymous pages:                             1631115.
Pages occupied by compressor:                 431982.
`

func TestUsedMemoryCountsAppWiredAndCompressedPages(t *testing.T) {
	used, err := UsedMemory([]byte(vmStatFixture))
	if err != nil {
		t.Fatal(err)
	}
	if want := uint64(1631115-5651+238518+431982) * 16384; used != want {
		t.Fatalf("used memory = %d, want %d", used, want)
	}
	if _, err := UsedMemory([]byte("nothing useful")); err == nil {
		t.Fatal("output without a page size must be refused")
	}
}

func TestCPUPercentAveragesOverCores(t *testing.T) {
	if got := CPUPercent([]byte(" 50.0\n 25.5\n  0.0\n"), 4); got != 18.875 {
		t.Fatalf("cpu = %v", got)
	}
	if got := CPUPercent([]byte("900\n"), 2); got != 100 {
		t.Fatalf("cpu must be capped at 100, got %v", got)
	}
}

func TestSampleReadsThisMac(t *testing.T) {
	usage, err := Sample(context.Background())
	if err != nil {
		t.Skip("not a Mac:", err)
	}
	if usage.MemoryTotalBytes == 0 || usage.MemoryUsedBytes == 0 || usage.MemoryUsedBytes > usage.MemoryTotalBytes {
		t.Fatalf("implausible sample: %+v", usage)
	}
}
