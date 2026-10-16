package com.gantoine.muxer

import com.gantoine.muxer.protocol.ChecklistProgress
import com.gantoine.muxer.protocol.LaneStatus
import com.gantoine.muxer.protocol.RelayJson
import com.gantoine.muxer.protocol.RemoteState
import com.gantoine.muxer.protocol.WorkspaceGroupInfo
import com.gantoine.muxer.protocol.WorkspaceInfo
import com.gantoine.muxer.protocol.WorkspacesMessage
import com.gantoine.muxer.ui.Organizing
import com.gantoine.muxer.ui.WorkspaceFilter
import com.gantoine.muxer.ui.WorkspaceListing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrganizingTest {
    @Test
    fun anAutomaticLaneSaysSo() {
        assertEquals("Review", Organizing.laneSummary(LaneStatus("review", "working", override = true)))
        assertEquals("Working (automatic)", Organizing.laneSummary(LaneStatus("working", "working")))
        assertNull(Organizing.laneSummary(LaneStatus("someday")))
    }

    @Test
    fun theRestingLaneIsOnlyShownWhenSetByHand() {
        assertNull(Organizing.shownLane(LaneStatus("todo", "todo")))
        assertEquals("To do", Organizing.shownLane(LaneStatus("todo", "working", override = true))?.label)
        assertEquals("Working", Organizing.shownLane(LaneStatus("working", "working"))?.label)
        assertNull(Organizing.shownLane(null))
    }

    @Test
    fun anEmptyChecklistHasNoSummary() {
        assertEquals("1/3", Organizing.checklistSummary(ChecklistProgress(1, 3)))
        assertNull(Organizing.checklistSummary(ChecklistProgress(0, 0)))
        assertNull(Organizing.checklistSummary(null))
    }

    @Test
    fun onlyABrokenSshConnectionIsShownAndOffersReconnect() {
        assertNull(Organizing.remoteLabel(RemoteState("connected", "devbox")))
        assertEquals("devbox · Reconnecting", Organizing.remoteLabel(RemoteState("reconnecting", "devbox")))
        assertEquals("Connection failed", Organizing.remoteLabel(RemoteState("error")))
        assertTrue(Organizing.canReconnect(RemoteState("error")))
        assertFalse(Organizing.canReconnect(RemoteState("connecting")))
        assertFalse(Organizing.canReconnect(null))
    }

    @Test
    fun theAnchorCannotLeaveItsGroup() {
        val group = WorkspaceGroupInfo("g1", "Q4", anchorWorkspaceId = "w1", memberWorkspaceIds = listOf("w1", "w2"))
        assertEquals(group, Organizing.groupOf(WorkspaceInfo("w2"), listOf(group)))
        assertTrue(Organizing.isAnchor(WorkspaceInfo("w1"), group))
        assertFalse(Organizing.isAnchor(WorkspaceInfo("w2"), group))
        assertNull(Organizing.groupOf(WorkspaceInfo("w3"), listOf(group)))
    }

    @Test
    fun laneFiltersUseTheEffectiveLane() {
        val review = WorkspaceInfo("w1", status = LaneStatus("review", "working", override = true))
        val working = WorkspaceInfo("w2", status = LaneStatus("working", "working"))
        assertTrue(WorkspaceListing.passes(review, WorkspaceFilter.Review))
        assertFalse(WorkspaceListing.passes(working, WorkspaceFilter.Review))
        assertFalse(WorkspaceListing.passes(WorkspaceInfo("w3"), WorkspaceFilter.Done))
    }

    @Test
    fun relayDecorationsDecode() {
        val message = RelayJson.decodeFromString(
            WorkspacesMessage.serializer(),
            """{"workspaces":[{"id":"w1","status":{"effective":"review","inferred":"working","override":true},"checklist":{"completed":1,"total":3,"next":"ship"},"remote":{"state":"error","destination":"devbox"}}],"groups":[{"id":"g1","custom_color":"#1565C0"}]}""",
        )
        val workspace = message.workspaces.single()
        assertEquals("review", workspace.status?.effective)
        assertEquals("ship", workspace.checklist?.next)
        assertEquals("devbox", workspace.remote?.destination)
        assertEquals("#1565C0", message.groups.single().customColor)
        assertEquals("Blue", Organizing.colorName("#1565c0"))
    }
}
