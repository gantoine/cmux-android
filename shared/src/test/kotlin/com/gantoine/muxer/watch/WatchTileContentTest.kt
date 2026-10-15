package com.gantoine.muxer.watch

import org.junit.Assert.assertEquals
import org.junit.Test

class WatchTileContentTest {
    private val connected = listOf(WatchComputer("mac-1", "Ada's MacBook", connected = true))
    private val time: (Long) -> String = { "t$it" }

    @Test
    fun noDataMeansThePhoneIsNotReachable() {
        assertEquals(WatchTileContent("Phone not reachable", emptyList(), null), WatchTileContent.of(null, time))
    }

    @Test
    fun waitingAgentsAreCountedAndListedUpToThree() {
        val summary = WatchSummary(
            computers = connected,
            approvals = listOf("api", "web", "docs", "infra").map { approval(it) },
            updatedAt = 42,
        )

        val content = WatchTileContent.of(summary, time)

        assertEquals("4 agents waiting", content.headline)
        assertEquals(listOf("Claude · api", "Claude · web", "Claude · docs"), content.rows)
        assertEquals("Updated t42", content.footer)
    }

    @Test
    fun oneWaitingAgentIsSingular() {
        val content = WatchTileContent.of(WatchSummary(connected, listOf(approval("api"))), time)

        assertEquals("1 agent waiting", content.headline)
    }

    @Test
    fun allQuietWhenNothingIsWaiting() {
        val content = WatchTileContent.of(WatchSummary(connected), time)

        assertEquals("All quiet", content.headline)
        assertEquals(emptyList<String>(), content.rows)
    }

    @Test
    fun quietTileMentionsWorkingAgents() {
        val one = WatchTileContent.of(WatchSummary(connected, working = listOf(WatchAgent("mac-1", "Codex", "api"))), time)
        val two = WatchTileContent.of(
            WatchSummary(connected, working = listOf(WatchAgent("mac-1", "Codex", "api"), WatchAgent("mac-1", "Claude", "web"))),
            time,
        )

        assertEquals(listOf("Codex · api is working"), one.rows)
        assertEquals(listOf("2 agents working"), two.rows)
    }

    @Test
    fun quietHeadlineExplainsMissingConnections() {
        assertEquals("No computers paired", WatchTileContent.of(WatchSummary(), time).headline)
        assertEquals(
            "Not connected",
            WatchTileContent.of(WatchSummary(listOf(WatchComputer("mac-1", "Ada's MacBook", connected = false))), time).headline,
        )
    }

    @Test
    fun rowSkipsBlankWorkspace() {
        assertEquals("Claude", WatchTileContent.row("Claude", ""))
    }

    private fun approval(workspace: String) = WatchApproval(
        id = workspace,
        macId = "mac-1",
        workspaceId = workspace,
        surfaceId = workspace,
        kind = WatchProtocol.KIND_PERMISSION,
        agent = "Claude",
        workspace = workspace,
        title = "Claude wants to use Bash",
        summary = "ls",
    )
}
