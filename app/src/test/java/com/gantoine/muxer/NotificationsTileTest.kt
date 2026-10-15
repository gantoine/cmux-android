package com.gantoine.muxer

import com.gantoine.muxer.data.Mac
import com.gantoine.muxer.tile.NotificationsTile
import com.gantoine.muxer.tile.TileState
import com.gantoine.muxer.tile.TileStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationsTileTest {
    private fun mac(id: String, background: Boolean) = Mac(id, id, "https://$id", "token", backgroundNotifications = background)

    @Test
    fun noComputersMakesTheTileUnavailable() {
        assertEquals(TileState.Unavailable, NotificationsTile.status(emptyList(), emptySet()).state)
    }

    @Test
    fun allComputersOffReadsOff() {
        assertEquals(TileStatus(TileState.Inactive, "Off"), NotificationsTile.status(listOf(mac("a", false)), setOf("a")))
    }

    @Test
    fun countsConnectionsAmongWatchedComputers() {
        val macs = listOf(mac("a", true), mac("b", true), mac("c", false))
        assertEquals(TileStatus(TileState.Active, "Connected to 1 of 2"), NotificationsTile.status(macs, setOf("a", "c")))
    }

    @Test
    fun toggleTurnsEverythingOnUnlessAllAreOn() {
        assertTrue(NotificationsTile.backgroundAfterToggle(listOf(mac("a", true), mac("b", false))))
        assertFalse(NotificationsTile.backgroundAfterToggle(listOf(mac("a", true), mac("b", true))))
    }
}
