package com.gantoine.muxer

import com.gantoine.muxer.data.Mac
import com.gantoine.muxer.data.MacSnapshot
import com.gantoine.muxer.protocol.Approval
import com.gantoine.muxer.protocol.ApprovalOption
import com.gantoine.muxer.protocol.ApprovalQuestion
import com.gantoine.muxer.protocol.TerminalInfo
import com.gantoine.muxer.protocol.WorkspaceInfo
import com.gantoine.muxer.ui.Approvals
import com.gantoine.muxer.watch.WatchAgent
import com.gantoine.muxer.watch.WatchComputer
import com.gantoine.muxer.watch.WatchProtocol
import com.gantoine.muxer.wear.WatchSummaries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchSummariesTest {
    private val mac = Mac(id = "mac-1", name = "Ada's MacBook", url = "https://mac.example", encryptedToken = "")
    private val workspaces = listOf(
        WorkspaceInfo(
            id = "ws-1",
            title = "muxer",
            terminals = listOf(
                TerminalInfo(id = "sf-1", agentSource = "claude"),
                TerminalInfo(id = "sf-2", agentSource = "codex"),
                TerminalInfo(id = "sf-3", agentSource = "claude", agentState = "Running tool"),
                TerminalInfo(id = "sf-4"),
            ),
        ),
    )
    private val permission = Approval(
        id = "a-1",
        kind = Approval.KIND_PERMISSION,
        agent = "claude",
        workspaceId = "ws-1",
        surfaceId = "sf-1",
        tool = "Bash",
        detail = "rm -rf build",
    )

    @Test
    fun approvalsCarryTitleSummaryAndWorkspace() {
        val summary = WatchSummaries.build(listOf(mac), mapOf(mac.id to snapshot(approvals = listOf(permission))), now = 0)

        val approval = summary.approvals.single()
        assertEquals("a-1", approval.id)
        assertEquals("mac-1", approval.macId)
        assertEquals("sf-1", approval.surfaceId)
        assertEquals("Claude", approval.agent)
        assertEquals("muxer", approval.workspace)
        assertEquals(Approvals.title(permission), approval.title)
        assertEquals("rm -rf build", approval.summary)
        assertTrue(approval.options.isEmpty())
    }

    @Test
    fun singleQuestionWithFewOptionsCarriesThem() {
        val question = Approval(
            id = "q-1",
            kind = Approval.KIND_QUESTION,
            workspaceId = "ws-1",
            surfaceId = "sf-1",
            questions = listOf(ApprovalQuestion(prompt = "Which?", options = listOf(ApprovalOption("Red"), ApprovalOption("Blue")))),
        )
        val tooMany = question.copy(
            id = "q-2",
            questions = listOf(ApprovalQuestion(prompt = "Which?", options = (1..5).map { ApprovalOption("$it") })),
        )
        val multi = question.copy(id = "q-3", questions = listOf(question.questions.single().copy(multiSelect = true)))

        val summary = WatchSummaries.build(listOf(mac), mapOf(mac.id to snapshot(approvals = listOf(question, tooMany, multi))), now = 0)

        assertEquals(listOf(listOf("Red", "Blue"), emptyList(), emptyList()), summary.approvals.map { it.options })
        assertEquals("Which?", summary.approvals.first().summary)
    }

    @Test
    fun workingAgentsComeFromBusySurfacesAndAgentStateButNotWaitingOnes() {
        val summary = WatchSummaries.build(
            listOf(mac),
            mapOf(mac.id to snapshot(approvals = listOf(permission), busy = setOf("sf-1", "sf-2"))),
            now = 0,
        )

        assertEquals(listOf(WatchAgent("mac-1", "Codex", "muxer"), WatchAgent("mac-1", "Claude", "muxer")), summary.working)
    }

    @Test
    fun disconnectedComputersShowNothingPending() {
        val summary = WatchSummaries.build(
            listOf(mac),
            mapOf(mac.id to snapshot(approvals = listOf(permission), busy = setOf("sf-2"), connected = false)),
            now = 0,
        )

        assertEquals(listOf(WatchComputer("mac-1", "Ada's MacBook", connected = false)), summary.computers)
        assertTrue(summary.approvals.isEmpty())
        assertTrue(summary.working.isEmpty())
        assertEquals(1_000L, summary.updatedAt)
    }

    @Test
    fun removedComputersAreIgnoredAndNoSnapshotUsesNow() {
        val summary = WatchSummaries.build(emptyList(), mapOf(mac.id to snapshot(approvals = listOf(permission))), now = 77)

        assertTrue(summary.computers.isEmpty())
        assertTrue(summary.approvals.isEmpty())
        assertEquals(77L, summary.updatedAt)
    }

    @Test
    fun longSummariesAreShortened() {
        val long = permission.copy(detail = "x".repeat(1_000))

        val summary = WatchSummaries.build(listOf(mac), mapOf(mac.id to snapshot(approvals = listOf(long))), now = 0)

        assertEquals(WatchSummaries.MAX_SUMMARY_LENGTH, summary.approvals.single().summary.length)
    }

    @Test
    fun watchModesMatchThePhoneModes() {
        assertEquals(Approvals.MODE_ONCE, WatchProtocol.MODE_ONCE)
        assertEquals(Approvals.MODE_ALWAYS, WatchProtocol.MODE_ALWAYS)
        assertEquals(Approvals.MODE_DENY, WatchProtocol.MODE_DENY)
        assertEquals(Approvals.PLAN_MANUAL, WatchProtocol.PLAN_MANUAL)
        assertEquals(Approvals.PLAN_AUTO_ACCEPT, WatchProtocol.PLAN_AUTO_ACCEPT)
        assertEquals(Approvals.PLAN_DENY, WatchProtocol.PLAN_DENY)
        assertEquals(Approval.KIND_PERMISSION, WatchProtocol.KIND_PERMISSION)
        assertEquals(Approval.KIND_PLAN, WatchProtocol.KIND_PLAN)
    }

    private fun snapshot(approvals: List<Approval> = emptyList(), busy: Set<String> = emptySet(), connected: Boolean = true) = MacSnapshot(
        macId = mac.id,
        macName = mac.name,
        connected = connected,
        updatedAt = 1_000,
        workspaces = workspaces,
        approvals = approvals,
        busySurfaces = busy,
    )
}
