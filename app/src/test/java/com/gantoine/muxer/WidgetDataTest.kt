package com.gantoine.muxer

import com.gantoine.muxer.data.MacSnapshot
import com.gantoine.muxer.model.Approvals
import com.gantoine.muxer.protocol.Approval
import com.gantoine.muxer.protocol.ApprovalOption
import com.gantoine.muxer.protocol.ApprovalQuestion
import com.gantoine.muxer.protocol.Retry
import com.gantoine.muxer.protocol.TerminalInfo
import com.gantoine.muxer.protocol.WorkspaceInfo
import com.gantoine.muxer.widget.ApprovalButton
import com.gantoine.muxer.widget.BoardCounts
import com.gantoine.muxer.widget.WidgetData
import com.gantoine.muxer.widget.WorkspaceState
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetDataTest {
    private val now = Instant.parse("2026-10-15T18:00:00Z").toEpochMilli()

    private fun workspace(id: String, vararg terminals: TerminalInfo) = WorkspaceInfo(id = id, title = id, terminals = terminals.toList())

    private fun snapshot(
        macId: String = "mac",
        workspaces: List<WorkspaceInfo> = emptyList(),
        approvals: List<Approval> = emptyList(),
        retries: List<Retry> = emptyList(),
        busy: Set<String> = emptySet(),
        connected: Boolean = true,
        updatedAt: Long = now,
    ) = MacSnapshot(macId, "$macId's Mac", connected, updatedAt, workspaces, approvals, retries, busy)

    private fun permission(id: String, surfaceId: String, createdAt: String? = null) =
        Approval(id = id, kind = Approval.KIND_PERMISSION, workspaceId = "w", surfaceId = surfaceId, tool = "Bash", createdAt = createdAt)

    @Test
    fun snapshotsAreStaleWhenDisconnectedOrOlderThanTwoMinutes() {
        assertFalse(WidgetData.isStale(snapshot(updatedAt = now - 60_000), now))
        assertTrue(WidgetData.isStale(snapshot(updatedAt = now - 121_000), now))
        assertTrue(WidgetData.isStale(snapshot(connected = false), now))
    }

    @Test
    fun asOfNamesTheOldestStaleSnapshotOnly() {
        val zone = ZoneId.of("UTC")
        assertNull(WidgetData.asOf(listOf(snapshot()), now, zone, Locale.UK))
        val stale = listOf(
            snapshot(macId = "a", updatedAt = now - 10 * 60_000),
            snapshot(macId = "b", connected = false, updatedAt = now - 30 * 60_000),
            snapshot(macId = "c", updatedAt = now - 60 * 60_000 + 1),
        )
        assertEquals("as of 17:00", WidgetData.asOf(stale, now, zone, Locale.UK))
        assertEquals("as of 17:30", WidgetData.asOf(stale.dropLast(1), now, zone, Locale.UK))
    }

    @Test
    fun workspaceStatePrefersWaitingThenRetryingThenWorking() {
        val agent = TerminalInfo(id = "t1", agentSource = "claude", agentState = "Idle")
        val ws = workspace("w1", agent)
        assertEquals(WorkspaceState.Idle, WidgetData.state(ws, snapshot()))
        assertEquals(WorkspaceState.Working, WidgetData.state(ws, snapshot(busy = setOf("t1"))))
        assertEquals(
            WorkspaceState.Working,
            WidgetData.state(workspace("w1", agent.copy(agentState = "Running")), snapshot()),
        )
        val retrying = snapshot(busy = setOf("t1"), retries = listOf(Retry(surfaceId = "t1", dueAt = "2026-10-15T18:01:00Z")))
        assertEquals(WorkspaceState.Retrying, WidgetData.state(ws, retrying))
        val waiting = retrying.copy(approvals = listOf(permission("a1", "t1")))
        assertEquals(WorkspaceState.Waiting, WidgetData.state(ws, waiting))
    }

    @Test
    fun rowsOpenTheAgentTerminalOrTheFirstOne() {
        val plain = TerminalInfo(id = "shell")
        val agent = TerminalInfo(id = "claude", agentSource = "claude")
        assertEquals("claude", WidgetData.agentSurface(workspace("w", plain, agent)))
        assertEquals("shell", WidgetData.agentSurface(workspace("w", plain)))
        assertNull(WidgetData.agentSurface(workspace("w")))
    }

    @Test
    fun boardNamesComputersOnlyWhenAskedAndCountsStates() {
        val first = snapshot(macId = "a", workspaces = listOf(workspace("w1", TerminalInfo("t1"))), busy = setOf("t1"))
        val second = snapshot(
            macId = "b",
            workspaces = listOf(workspace("w2", TerminalInfo("t2")), workspace("w3", TerminalInfo("t3"))),
            approvals = listOf(permission("x", "t2")),
        )
        val grouped = WidgetData.board(listOf(first, second), showComputerNames = true)
        assertEquals(listOf("a's Mac", "b's Mac"), grouped.map { it.title })
        assertEquals(listOf(null), WidgetData.board(listOf(first), showComputerNames = false).map { it.title })
        val counts = WidgetData.counts(grouped)
        assertEquals(BoardCounts(waiting = 1, retrying = 0, working = 1, idle = 1), counts)
        assertEquals("1 waiting · 1 working", WidgetData.countsLabel(counts))
        assertEquals("All idle", WidgetData.countsLabel(BoardCounts(0, 0, 0, 2)))
        assertEquals("No workspaces", WidgetData.countsLabel(BoardCounts(0, 0, 0, 0)))
    }

    @Test
    fun pairedKeepsTheComputersOrderAndDropsUnpairedSnapshots() {
        val snapshots = mapOf("a" to snapshot(macId = "a"), "b" to snapshot(macId = "b"), "gone" to snapshot(macId = "gone"))
        assertEquals(listOf("b", "a"), WidgetData.paired(snapshots, listOf("b", "a", "new")).map { it.macId })
    }

    @Test
    fun approvalButtonsMatchTheKindOfPrompt() {
        assertEquals(
            listOf("Allow", "Always", "Deny"),
            WidgetData.buttons(permission("p", "t")).map { it.label },
        )
        val plan = Approval(id = "p", kind = Approval.KIND_PLAN, workspaceId = "w", surfaceId = "t")
        val planButtons = WidgetData.buttons(plan)
        assertEquals(listOf("Approve", "Auto-edit", "Reject"), planButtons.map { it.label })
        assertEquals(ApprovalButton.Decide("Auto-edit", Approvals.PLAN_AUTO_ACCEPT), planButtons[1])

        fun question(options: Int, multiSelect: Boolean = false) = ApprovalQuestion(
            prompt = "Which?",
            multiSelect = multiSelect,
            options = (1..options).map { ApprovalOption("Option $it") },
        )
        fun asking(vararg questions: ApprovalQuestion) =
            Approval(id = "q", kind = Approval.KIND_QUESTION, workspaceId = "w", surfaceId = "t", questions = questions.toList())

        assertEquals(
            listOf(ApprovalButton.Choose("Option 1"), ApprovalButton.Choose("Option 2")),
            WidgetData.buttons(asking(question(2))),
        )
        assertEquals(listOf(ApprovalButton.OpenTerminal()), WidgetData.buttons(asking(question(4))))
        assertEquals(listOf(ApprovalButton.OpenTerminal()), WidgetData.buttons(asking(question(2, multiSelect = true))))
        assertEquals(listOf(ApprovalButton.OpenTerminal()), WidgetData.buttons(asking(question(2), question(2))))
        assertEquals(listOf(ApprovalButton.OpenTerminal()), WidgetData.buttons(asking(question(0))))
    }

    @Test
    fun approvalsAreNewestFirstAcrossComputers() {
        val a = snapshot(macId = "a", approvals = listOf(permission("old", "t1", "2026-10-15T17:00:00Z"), permission("undated", "t1")))
        val b = snapshot(macId = "b", approvals = listOf(permission("new", "t2", "2026-10-15T17:30:00Z")))
        val items = WidgetData.approvals(listOf(a, b))
        assertEquals(listOf("new", "old", "undated"), items.map { it.approval.id })
        assertEquals("b's Mac", items.first().macName)
    }

    @Test
    fun retriesFindTheirWorkspaceAndCountDown() {
        val ws = workspace("w1", TerminalInfo("t1"))
        val retry = Retry(surfaceId = "t1", agent = "claude", dueAt = "2026-10-15T18:00:42Z")
        val items = WidgetData.retries(listOf(snapshot(workspaces = listOf(ws), retries = listOf(retry))))
        assertEquals("w1", items.single().workspaceId)
        assertEquals("Claude retries in 0:42", WidgetData.retryLabel(retry, Instant.ofEpochMilli(now)))
        assertEquals("Claude retries now", WidgetData.retryLabel(retry, Instant.parse("2026-10-15T18:05:00Z")))
        assertEquals("The agent retries now", WidgetData.retryLabel(retry.copy(agent = null, dueAt = "soon"), Instant.ofEpochMilli(now)))
    }



    @Test
    fun idleAgentsWaitingForInputNeedYou() {
        val waiting = TerminalInfo(id = "t1", agentSource = "claude", agentState = "Claude is waiting for your input")
        val ws = workspace("w", waiting, TerminalInfo(id = "t2"))
        assertEquals(WorkspaceState.NeedsYou, WidgetData.state(ws, snapshot(workspaces = listOf(ws))))
        assertEquals(WorkspaceState.Working, WidgetData.state(ws, snapshot(workspaces = listOf(ws), busy = setOf("t1"))))
        assertEquals(WorkspaceState.Waiting, WidgetData.state(ws, snapshot(workspaces = listOf(ws), approvals = listOf(permission("a", "t1")))))
        val items = WidgetData.needsYou(listOf(snapshot(workspaces = listOf(ws))))
        assertEquals(1, items.size)
        assertEquals("Claude is waiting for your input", items.single().label)
        assertEquals("t1", items.single().surfaceId)
        assertEquals("w", items.single().workspaceTitle)
        assertTrue(WidgetData.needsYou(listOf(snapshot(workspaces = listOf(ws), busy = setOf("t1")))).isEmpty())
    }

    @Test
    fun workspacePreviewsAndUnreadCountsAlsoNeedYou() {
        val agent = TerminalInfo(id = "t1", agentSource = "claude", agentState = "Waiting")
        val previewed = workspace("p", agent).copy(preview = "Claude is waiting for your input")
        val unread = workspace("u", agent.copy(id = "t2")).copy(unreadCount = 2, preview = "Build finished")
        val quiet = workspace("q", agent.copy(id = "t3")).copy(preview = "~/workspace/quiet")
        val stale = workspace("s", TerminalInfo(id = "t4")).copy(unreadCount = 1, preview = "Claude is waiting for your input")
        val snapshot = snapshot(workspaces = listOf(previewed, unread, quiet, stale))
        assertEquals(WorkspaceState.NeedsYou, WidgetData.state(previewed, snapshot))
        assertEquals(WorkspaceState.NeedsYou, WidgetData.state(unread, snapshot))
        assertEquals(WorkspaceState.Idle, WidgetData.state(quiet, snapshot))
        assertEquals(WorkspaceState.Idle, WidgetData.state(stale, snapshot))
        assertEquals(listOf("Claude is waiting for your input", "Build finished"), WidgetData.needsYou(listOf(snapshot)).map { it.label })
    }

    @Test
    fun countsLabelMentionsAgentsThatNeedYou() {
        assertEquals("1 need you · 1 working", WidgetData.countsLabel(BoardCounts(0, 0, 1, 0, needsYou = 1)))
    }

}
