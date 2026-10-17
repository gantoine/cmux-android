package com.gantoine.muxer

import com.gantoine.muxer.protocol.RecentSession
import com.gantoine.muxer.protocol.RecentSessionsResponse
import com.gantoine.muxer.protocol.RelayJson
import com.gantoine.muxer.ui.SessionListing
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionListingTest {
    @Test
    fun aSessionWithoutATitleIsNamedAfterItsFolder() {
        val session = RecentSession(agent = "claude", sessionId = "s1", cwd = "/Users/me/workspace/muxer/")
        assertEquals("muxer", SessionListing.label(session))
        assertEquals("Untitled session", SessionListing.label(RecentSession(agent = "pi", sessionId = "s2")))
        assertEquals("fix the relay", SessionListing.label(session.copy(title = "fix the relay")))
    }

    @Test
    fun theDetailLineNamesTheAgentFolderAndBranch() {
        val session = RecentSession(agent = "codex", sessionId = "s1", cwd = "/Users/me/workspace/api", gitBranch = "main")
        assertEquals("Codex · ~/workspace/api · main", SessionListing.detail(session))
        assertEquals("Newagent", SessionListing.detail(RecentSession(agent = "newagent", sessionId = "s2")))
    }

    @Test
    fun relaySessionsDecodeWithTheirTerminalWhenRunning() {
        val decoded = RelayJson.decodeFromString(
            RecentSessionsResponse.serializer(),
            """{"sessions":[{"agent":"claude","session_id":"s1","title":"","modified":"2026-10-16T20:00:00Z","workspace_id":"w1","surface_id":"t1"},{"agent":"pi","session_id":"s2","modified":"2026-10-15T09:00:00Z"}]}""",
        ).sessions
        assertTrue(SessionListing.isOpen(decoded[0]))
        assertFalse(SessionListing.isOpen(decoded[1]))
        assertEquals(Instant.parse("2026-10-16T20:00:00Z"), SessionListing.modifiedAt(decoded[0]))
    }
}
