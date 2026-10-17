package com.gantoine.muxer.model

import com.gantoine.muxer.protocol.ChecklistProgress
import com.gantoine.muxer.protocol.LaneStatus
import com.gantoine.muxer.protocol.RemoteState
import com.gantoine.muxer.protocol.WorkspaceGroupInfo
import com.gantoine.muxer.protocol.WorkspaceInfo

data class Lane(val key: String, val label: String)

data class PaletteColor(val name: String, val hex: String)

object Organizing {
    const val AUTOMATIC = "auto"

    val lanes = listOf(
        Lane("todo", "To do"),
        Lane("working", "Working"),
        Lane("needs-attention", "Needs attention"),
        Lane("review", "Review"),
        Lane("done", "Done"),
    )

    val palette = listOf(
        PaletteColor("Red", "#C0392B"),
        PaletteColor("Crimson", "#922B21"),
        PaletteColor("Orange", "#A04000"),
        PaletteColor("Amber", "#7D6608"),
        PaletteColor("Olive", "#4A5C18"),
        PaletteColor("Green", "#196F3D"),
        PaletteColor("Teal", "#006B6B"),
        PaletteColor("Aqua", "#0E6B8C"),
        PaletteColor("Blue", "#1565C0"),
        PaletteColor("Navy", "#1A5276"),
        PaletteColor("Indigo", "#283593"),
        PaletteColor("Purple", "#6A1B9A"),
        PaletteColor("Magenta", "#AD1457"),
        PaletteColor("Rose", "#880E4F"),
        PaletteColor("Brown", "#7B3F00"),
        PaletteColor("Charcoal", "#3E4B5E"),
    )

    private val remoteLabels = mapOf(
        "disconnected" to "Disconnected",
        "connecting" to "Connecting",
        "reconnecting" to "Reconnecting",
        "error" to "Connection failed",
        "suspended" to "Suspended",
    )

    private val reconnectable = setOf("disconnected", "error", "suspended")

    fun laneLabel(key: String?): String? = lanes.firstOrNull { it.key == key }?.label

    fun shownLane(status: LaneStatus?): Lane? {
        if (status == null || status.effective == "todo" && !status.override) return null
        return lanes.firstOrNull { it.key == status.effective }
    }

    fun laneSummary(status: LaneStatus): String? {
        val label = laneLabel(status.effective) ?: return null
        return if (status.override) label else "$label (automatic)"
    }

    fun checklistSummary(progress: ChecklistProgress?): String? =
        progress?.takeIf { it.total > 0 }?.let { "${it.completed}/${it.total}" }

    fun remoteLabel(remote: RemoteState?): String? {
        val state = remote?.state ?: return null
        if (state == "connected") return null
        val label = remoteLabels[state] ?: state.replaceFirstChar { it.uppercase() }
        return remote.destination?.takeIf { it.isNotBlank() }?.let { "$it · $label" } ?: label
    }

    fun canReconnect(remote: RemoteState?): Boolean = remote?.state in reconnectable

    fun groupOf(workspace: WorkspaceInfo, groups: List<WorkspaceGroupInfo>): WorkspaceGroupInfo? =
        workspace.groupId?.let { id -> groups.firstOrNull { it.id == id } }
            ?: groups.firstOrNull { workspace.id in it.memberWorkspaceIds }

    fun isAnchor(workspace: WorkspaceInfo, group: WorkspaceGroupInfo?): Boolean = group?.anchorWorkspaceId == workspace.id

    fun colorName(hex: String?): String? = palette.firstOrNull { it.hex.equals(hex, ignoreCase = true) }?.name
}
