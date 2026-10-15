package com.gantoine.muxer

import com.gantoine.muxer.notifications.ComputerActivity
import com.gantoine.muxer.notifications.LiveActivities
import com.gantoine.muxer.protocol.Retry
import com.gantoine.muxer.protocol.TerminalInfo
import com.gantoine.muxer.protocol.WorkspaceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

    private fun activity(busy: Set<String> = setOf("busy"), retries: List<Retry> = emptyList()) =
        ComputerActivity(macId = "mac", workspaces = workspaces, busySurfaces = busy, retries = retries)

    @Test
    fun busyAndRunningTerminalsGetOneNotificationEach() {
        val working = LiveActivities.working(activity())
        assertEquals(listOf("busy", "running"), working.map { it.surfaceId })
        assertEquals("Claude is working in API refactor", LiveActivities.workingTitle(working[0]))
        assertEquals("Codex is working in API refactor", LiveActivities.workingTitle(working[1]))
        assertEquals("activity:mac:busy", working[0].key)
    }

    @Test
    fun theTerminalOnScreenAndRetryingTerminalsAreLeftOut() {
        val retry = Retry(surfaceId = "retrying", workspaceId = "w2", agent = "claude", dueAt = "2026-10-15T12:00:42Z")
        val working = LiveActivities.working(activity(busy = setOf("busy", "retrying"), retries = listOf(retry))) { it == "busy" }
        assertEquals(listOf("running"), working.map { it.surfaceId })
    }

    @Test
    fun blankWorkspaceTitlesFallBackToTheTerminal() {
        val working = LiveActivities.working(activity(busy = setOf("retrying")))
        assertEquals("Claude is working in docs", LiveActivities.workingTitle(working.single { it.surfaceId == "retrying" }))
    }

    @Test
    fun retriesCountDownUntilTheyFire() {
        val pending = Retry(surfaceId = "retrying", agent = "claude", attempt = 2, dueAt = "2026-10-15T12:00:42Z")
        val fired = Retry(surfaceId = "busy", agent = "claude", dueAt = "2026-10-15T11:59:59Z")
        val broken = Retry(surfaceId = "running", dueAt = "soon")
        val retries = LiveActivities.retries(activity(retries = listOf(pending, fired, broken)), now)
        val retry = retries.single()
        assertEquals("w2", retry.workspaceId)
        assertEquals(2, retry.attempt)
        assertEquals("Claude retries in 0:42", LiveActivities.retryTitle(retry, now))
        assertEquals("retry:mac:retrying", retry.key)
        assertTrue(LiveActivities.retries(activity(retries = listOf(pending)), now) { it == "retrying" }.isEmpty())
    }

    @Test
    fun retryWithoutAgentUsesTheTerminalsAgent() {
        val retry = Retry(surfaceId = "running", dueAt = "2026-10-15T12:01:05Z")
        val countdown = LiveActivities.retries(activity(retries = listOf(retry)), now).single()
        assertEquals("Codex retries in 1:05", LiveActivities.retryTitle(countdown, now))
    }

    @Test
    fun countdownsReadLikeAClock() {
        assertEquals("0:00", LiveActivities.countdown(-3))
        assertEquals("0:09", LiveActivities.countdown(9))
        assertEquals("2:05", LiveActivities.countdown(125))
    }

    @Test
    fun progressGrowsAsTheRetryApproaches() {
        assertEquals(0, LiveActivities.progressPercent(60, 60))
        assertEquals(50, LiveActivities.progressPercent(60, 30))
        assertEquals(100, LiveActivities.progressPercent(60, 0))
        assertEquals(100, LiveActivities.progressPercent(0, 0))
        assertEquals(0, LiveActivities.progressPercent(60, 90))
    }

    @Test
    fun agentNamesAreReadable() {
        assertEquals("Claude", LiveActivities.agentName("claude_code"))
        assertEquals("Gemini cli", LiveActivities.agentName("gemini-cli"))
        assertNull(LiveActivities.agentName(" "))
    }

    @Test
    fun notificationKeysDoNotCollideWithTheServiceId() {
        val keys = LiveActivities.working(activity(busy = setOf("busy", "retrying"))).map { it.key.hashCode() }
        keys.forEach { assertNotEquals(1, it) }
        assertEquals(keys.size, keys.toSet().size)
    }
}
