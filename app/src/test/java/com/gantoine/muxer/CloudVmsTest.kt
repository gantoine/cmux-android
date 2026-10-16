package com.gantoine.muxer

import com.gantoine.muxer.net.RelayException
import com.gantoine.muxer.protocol.CloudVm
import com.gantoine.muxer.protocol.CloudVmStats
import com.gantoine.muxer.protocol.CloudVmsResponse
import com.gantoine.muxer.ui.CloudVms
import com.gantoine.muxer.ui.VmPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CloudVmsTest {
    private val build = CloudVm(id = "vm_1", name = "Build box", status = "running")
    private val scratch = CloudVm(id = "vm_2", name = "scratch", status = "paused")

    @Test
    fun statusesGroupIntoPhases() {
        assertEquals(VmPhase.Running, CloudVms.phase("standby"))
        assertEquals(VmPhase.Asleep, CloudVms.phase("suspended"))
        assertEquals(VmPhase.Changing, CloudVms.phase("resuming"))
        assertEquals(VmPhase.Attention, CloudVms.phase("failed"))
    }

    @Test
    fun summaryShowsLoadOnlyWhenStatsCameBack() {
        val withStats = build.copy(stats = CloudVmStats(cpuPercent = 12.4, memoryUsedMb = 2048, memoryTotalMb = 8192))
        assertEquals("Running · 12% CPU · 25% RAM", CloudVms.summary(withStats))
        assertEquals("Paused", CloudVms.summary(scratch))
    }

    @Test
    fun vmsSeenThroughTwoComputersAreListedOnce() {
        var answers = CloudVms.answers(emptyMap(), "mac_a", Result.success(CloudVmsResponse(listOf(build))))
        answers = CloudVms.answers(answers, "mac_b", Result.success(CloudVmsResponse(listOf(build, scratch))))
        val merged = CloudVms.merge(answers, listOf("mac_a", "mac_b"))!!
        assertEquals(listOf("vm_1" to "mac_a", "vm_2" to "mac_b"), merged.map { it.vm.id to it.macId })
    }

    @Test
    fun aTimeoutKeepsTheLastAnswerButCloudOffDropsIt() {
        val answers = CloudVms.answers(emptyMap(), "mac_a", Result.success(CloudVmsResponse(listOf(build))))
        val afterTimeout = CloudVms.answers(answers, "mac_a", Result.failure(RelayException("timeout", "slow")))
        assertEquals(answers, afterTimeout)
        val afterCloudOff = CloudVms.answers(answers, "mac_a", Result.failure(RelayException("cloud_disabled", "off")))
        assertNull(CloudVms.merge(afterCloudOff, listOf("mac_a")))
    }
}
