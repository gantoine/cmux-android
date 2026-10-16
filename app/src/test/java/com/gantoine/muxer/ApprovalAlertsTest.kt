package com.gantoine.muxer

import com.gantoine.muxer.net.ApprovalAlerts
import com.gantoine.muxer.protocol.Approval
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ApprovalAlertsTest {
    private val first = Approval(id = "a1", kind = "permission", workspaceId = "w1", surfaceId = "t1")
    private val second = Approval(id = "a2", kind = "permission", workspaceId = "w1", surfaceId = "t2")
    private val alerted = mutableListOf<String>()
    private val dismissed = mutableListOf<String>()
    private val alerts = ApprovalAlerts(alert = { fresh -> alerted += fresh.map { it.id } }, dismiss = { dismissed += it }, outliveDropsForMs = 60_000)

    @Test
    fun alertsOnceAndDismissesWhatWasAnswered() = runTest {
        val approvals = MutableStateFlow<List<Approval>?>(listOf(first))
        val following = launch { alerts.follow(approvals) }
        runCurrent()
        approvals.value = listOf(first, second)
        runCurrent()
        approvals.value = listOf(second)
        runCurrent()
        assertEquals(listOf("a1", "a2"), alerted)
        assertEquals(listOf("a1"), dismissed)
        following.cancel()
    }

    @Test
    fun aBriefDropNeitherDismissesNorAlertsAgain() = runTest {
        val approvals = MutableStateFlow<List<Approval>?>(listOf(first))
        val following = launch { alerts.follow(approvals) }
        runCurrent()
        approvals.value = null
        advanceTimeBy(30_000)
        approvals.value = listOf(first)
        advanceTimeBy(60_000)
        assertEquals(listOf("a1"), alerted)
        assertEquals(emptyList<String>(), dismissed)
        following.cancel()
    }

    @Test
    fun aLongDropDismissesEverything() = runTest {
        val approvals = MutableStateFlow<List<Approval>?>(listOf(first, second))
        val following = launch { alerts.follow(approvals) }
        runCurrent()
        approvals.value = null
        advanceTimeBy(60_001)
        assertEquals(listOf("a1", "a2"), dismissed)
        approvals.value = listOf(first)
        runCurrent()
        assertEquals(listOf("a1", "a2", "a1"), alerted)
        following.cancel()
    }
}
