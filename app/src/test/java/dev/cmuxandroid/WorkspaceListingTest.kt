package dev.cmuxandroid

import dev.cmuxandroid.protocol.TerminalInfo
import dev.cmuxandroid.protocol.WorkspaceInfo
import dev.cmuxandroid.ui.WorkspaceListing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class WorkspaceListingTest {
    private val workspace = WorkspaceInfo(
        id = "w1",
        title = "API refactor",
        currentDirectory = "/Users/me/code/api",
        terminals = listOf(TerminalInfo(id = "t1", title = "claude", agentState = "Waiting for input")),
    )

    @Test
    fun searchMatchesTitlesFoldersAndTerminalsIgnoringCase() {
        assertTrue(WorkspaceListing.matches(workspace, ""))
        assertTrue(WorkspaceListing.matches(workspace, "refactor"))
        assertTrue(WorkspaceListing.matches(workspace, "CODE/API"))
        assertTrue(WorkspaceListing.matches(workspace, "  claude "))
        assertTrue(WorkspaceListing.matches(workspace, "waiting"))
        assertFalse(WorkspaceListing.matches(workspace, "billing"))
    }

    @Test
    fun timesReadLikeAMessagingApp() {
        val zone = ZoneId.of("America/New_York")
        val now = Instant.parse("2026-10-14T18:00:00Z")
        fun at(iso: String) = Instant.parse(iso).epochSecond.toDouble()

        assertEquals("1:05 PM", WorkspaceListing.time(at("2026-10-14T17:05:00Z"), now, zone, Locale.US))
        assertEquals("Yesterday", WorkspaceListing.time(at("2026-10-13T15:00:00Z"), now, zone, Locale.US))
        assertEquals("Sun", WorkspaceListing.time(at("2026-10-11T15:00:00Z"), now, zone, Locale.US))
        assertEquals("Sep 30", WorkspaceListing.time(at("2026-09-30T15:00:00Z"), now, zone, Locale.US))
        assertNull(WorkspaceListing.time(null, now, zone, Locale.US))
    }

    @Test
    fun workspacesWithoutAColorKeepTheSameFallbackColor() {
        assertEquals(WorkspaceListing.color(workspace), WorkspaceListing.color(workspace.copy(title = "renamed")))
        assertEquals("#123456", WorkspaceListing.color(workspace.copy(customColor = "#123456")))
        assertTrue(WorkspaceListing.isAgent(workspace))
        assertFalse(WorkspaceListing.isAgent(workspace.copy(terminals = listOf(TerminalInfo(id = "t2")))))
    }
}
