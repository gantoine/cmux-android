package com.gantoine.muxer

import com.gantoine.muxer.notifications.ActivitySummary
import com.gantoine.muxer.notifications.ComputerActivity
import com.gantoine.muxer.notifications.LiveActivities
import com.gantoine.muxer.protocol.Approval
import com.gantoine.muxer.protocol.Retry
import com.gantoine.muxer.protocol.TerminalInfo
import com.gantoine.muxer.protocol.WorkspaceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class LiveActivitiesTest {
    private val now = Instant.parse("2026-10-15T12:00:00Z")

    private val workspaces = listOf(
        WorkspaceInfo(
            id = "w1",
            title = "API refactor",
            terminals = listOf(
                TerminalInfo(id = "busy", agentSource = "claude"),
                TerminalInfo(id = "running", agentSource = "codex", agentState = "Running"),
                TerminalInfo(id = "waiting", agentSource = "claude", agentState = "Waiting for input"),
                TerminalInfo(id = "shell"),
            ),
        ),
        WorkspaceInfo(id = "w2", title = "", terminals = listOf(TerminalInfo(id = "retrying", title = "docs", agentSource = "claude"))),
    )

    private val permission = Approval(id = "a1", kind = "permission", agent = "claude", workspaceId = "w1", surfaceId = "busy", tool = "Bash")

    private fun activity(busy: Set<String> = setOf("busy"), retries: List<Retry> = emptyList(), approvals: List<Approval> = emptyList()) =
        ComputerActivity(macId = "mac", workspaces = workspaces, busySurfaces = busy, retries = retries, approvals = approvals)

    private fun summary(activity: ComputerActivity, connected: Int = 1, total: Int = 1, names: Map<String, String> = mapOf("mac" to "M4")) =
        ActivitySummary(
            waiting = LiveActivities.waiting(activity),
            retries = LiveActivities.retries(activity, now),
            working = LiveActivities.working(activity).map { it.copy(since = now.toEpochMilli() - 90_000) },
            connected = connected,
            total = total,
            macNames = names,
        )

    @Test
    fun busyAndRunningTerminalsCountAsWorking() {
        val working = LiveActivities.working(activity())
        assertEquals(listOf("busy", "running"), working.map { it.surfaceId })
        assertEquals("Claude", working[0].agent)
        assertEquals("API refactor", working[0].workspaceTitle)
        assertEquals("activity:mac:busy", working[0].key)
    }

    @Test
    fun terminalsRetryingOrWaitingAreNotWorking() {
        val retry = Retry(surfaceId = "retrying", workspaceId = "w2", agent = "claude", dueAt = "2026-10-15T12:00:42Z")
        val working = LiveActivities.working(activity(busy = setOf("busy", "retrying"), retries = listOf(retry)))
        assertEquals(listOf("busy", "running"), working.map { it.surfaceId })
        val askedWhileBusy = LiveActivities.working(activity(approvals = listOf(permission)))
        assertEquals(listOf("running"), askedWhileBusy.map { it.surfaceId })
    }

    @Test
    fun blankWorkspaceTitlesFallBackToTheTerminal() {
        val working = LiveActivities.working(activity(busy = setOf("retrying")))
        assertEquals("docs", working.single { it.surfaceId == "retrying" }.workspaceTitle)
    }

    @Test
    fun approvalsBecomeWaitingAgents() {
        val waiting = LiveActivities.waiting(activity(approvals = listOf(permission, permission.copy(id = "a2", kind = "plan", agent = null, surfaceId = "running")))).map { it.request }
        assertEquals(listOf("wants to use Bash", "has a plan ready"), waiting)
        assertEquals("Codex", LiveActivities.waiting(activity(approvals = listOf(permission.copy(agent = null, surfaceId = "running")))).single().agent)
    }

    @Test
    fun retriesCountDownUntilTheyFire() {
        val pending = Retry(surfaceId = "retrying", agent = "claude", attempt = 2, dueAt = "2026-10-15T12:00:42Z")
        val fired = Retry(surfaceId = "busy", agent = "claude", dueAt = "2026-10-15T11:59:59Z")
        val broken = Retry(surfaceId = "running", dueAt = "soon")
        val retry = LiveActivities.retries(activity(retries = listOf(pending, fired, broken)), now).single()
        assertEquals("w2", retry.workspaceId)
        assertEquals("docs", retry.workspaceTitle)
        assertEquals(42, LiveActivities.secondsUntil(retry, now))
        assertEquals("retry:mac:retrying", retry.key)
    }

    @Test
    fun countdownsReadLikeAClock() {
        assertEquals("0:00", LiveActivities.countdown(-3))
        assertEquals("0:09", LiveActivities.countdown(9))
        assertEquals("2:05", LiveActivities.countdown(125))
    }

    @Test
    fun titleCountsWhatNeedsYouFirst() {
        val retry = Retry(surfaceId = "retrying", agent = "claude", dueAt = "2026-10-15T12:00:42Z")
        val all = summary(activity(busy = setOf("busy", "running"), retries = listOf(retry), approvals = listOf(permission)))
        assertEquals("1 waiting for you · 1 retrying · 1 agent working", LiveActivities.title(all))
        assertEquals("2 agents working", LiveActivities.title(summary(activity(busy = setOf("busy", "running")))))
    }

    @Test
    fun quietSummariesDescribeTheConnection() {
        val quiet = ActivitySummary(macNames = mapOf("mac" to "M4"))
        assertEquals("No computers to watch", LiveActivities.title(quiet))
        assertEquals("Connected to M4", LiveActivities.title(quiet.copy(connected = 1, total = 1)))
        assertEquals("Connected to 1 of 2 computers", LiveActivities.title(quiet.copy(connected = 1, total = 2)))
        assertEquals("Connected to 2 computers", LiveActivities.title(quiet.copy(connected = 2, total = 2)))
        assertTrue(LiveActivities.lines(quiet, now).isEmpty())
        assertNull(LiveActivities.chipText(quiet, now))
        assertNull(LiveActivities.chronometer(quiet))
    }

    @Test
    fun linesNameTheAgentThePlaceAndTheState() {
        val retry = Retry(surfaceId = "retrying", agent = "claude", attempt = 3, dueAt = "2026-10-15T12:00:42Z")
        val one = summary(activity(busy = setOf("running"), retries = listOf(retry), approvals = listOf(permission)))
        assertEquals(
            listOf("Claude · API refactor · wants to use Bash", "Claude · docs · retries in 0:42, attempt 3", "Codex · API refactor · working"),
            LiveActivities.lines(one, now),
        )
        val two = one.copy(total = 2, connected = 2, macNames = mapOf("mac" to "M4", "other" to "Mini"))
        assertEquals("Claude · M4 › API refactor · wants to use Bash", LiveActivities.lines(two, now).first())
    }

    @Test
    fun chipAndChronometerFollowTheMostUrgentState() {
        val retry = Retry(surfaceId = "retrying", agent = "claude", dueAt = "2026-10-15T12:00:42Z")
        val working = summary(activity(busy = setOf("busy", "running")))
        assertEquals("2 working", LiveActivities.chipText(working, now))
        assertEquals(now.toEpochMilli() - 90_000, LiveActivities.chronometer(working)!!.at)
        assertFalse(LiveActivities.chronometer(working)!!.countDown)

        val retrying = summary(activity(busy = setOf("busy"), retries = listOf(retry)))
        assertEquals("0:42", LiveActivities.chipText(retrying, now))
        assertTrue(LiveActivities.chronometer(retrying)!!.countDown)

        val waiting = summary(activity(retries = listOf(retry), approvals = listOf(permission)))
        assertEquals("1 waiting", LiveActivities.chipText(waiting, now))
        assertNull(LiveActivities.chronometer(waiting))
    }
}
