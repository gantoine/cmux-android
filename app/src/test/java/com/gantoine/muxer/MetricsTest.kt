package com.gantoine.muxer

import com.gantoine.muxer.protocol.RelayJson
import com.gantoine.muxer.protocol.TopSummary
import com.gantoine.muxer.ui.Metrics
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class MetricsTest {
    @Test
    fun formatsMemory() {
        assertEquals("512 KB", Metrics.bytes(512 * 1024L, Locale.US))
        assertEquals("9.5 MB", Metrics.bytes((9.5 * 1024 * 1024).toLong(), Locale.US))
        assertEquals("674 MB", Metrics.bytes(674L * 1024 * 1024, Locale.US))
        assertEquals("7.3 GB", Metrics.bytes((7.3 * 1024 * 1024 * 1024).toLong(), Locale.US))
    }

    @Test
    fun formatsCpu() {
        assertEquals("0%", Metrics.cpu(0.0, Locale.US))
        assertEquals("<1%", Metrics.cpu(0.0004, Locale.US))
        assertEquals("24%", Metrics.cpu(24.16, Locale.US))
    }

    @Test
    fun readsTheRelaySummary() {
        val summary = RelayJson.decodeFromString(
            TopSummary.serializer(),
            """{"cpu_percent":30.5,"memory_bytes":9000000000,"process_count":31,"app_memory_bytes":700000000,
               "agents":[{"name":"Claude Code","cpu_percent":5.6,"memory_bytes":1287558976,"process_count":4}],
               "workspaces":[{"id":"w2","title":"Muxer","cpu_percent":24.2,"memory_bytes":8500000000,"process_count":9,
                 "processes":[{"pid":7000,"name":"qemu-system-aarch64","cpu_percent":12,"memory_bytes":7330000000}]}]}""",
        )
        assertEquals(9_000_000_000L, summary.memoryBytes)
        assertEquals("qemu-system-aarch64", summary.workspaces.single().processes.single().name)
    }
}
