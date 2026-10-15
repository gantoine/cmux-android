package com.gantoine.muxer.tile

import com.gantoine.muxer.data.Mac

enum class TileState { Active, Inactive, Unavailable }

data class TileStatus(val state: TileState, val subtitle: String)

object NotificationsTile {
    fun status(macs: List<Mac>, connectedMacIds: Set<String>): TileStatus {
        if (macs.isEmpty()) return TileStatus(TileState.Unavailable, "No computers")
        val watched = macs.filter { it.backgroundNotifications }
        if (watched.isEmpty()) return TileStatus(TileState.Inactive, "Off")
        val connected = watched.count { it.id in connectedMacIds }
        return TileStatus(TileState.Active, "Connected to $connected of ${watched.size}")
    }

    fun backgroundAfterToggle(macs: List<Mac>): Boolean = macs.any { !it.backgroundNotifications }
}
