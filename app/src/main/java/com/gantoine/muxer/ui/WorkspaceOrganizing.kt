package com.gantoine.muxer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FormatColorReset
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.outlined.AutoMode
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gantoine.muxer.protocol.ChecklistProgress
import com.gantoine.muxer.protocol.LaneStatus
import com.gantoine.muxer.protocol.RemoteState
import com.gantoine.muxer.protocol.WorkspaceGroupInfo
import com.gantoine.muxer.protocol.WorkspaceInfo
import com.gantoine.muxer.terminal.parseHexColor

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

@Composable
fun WorkspaceMetaLine(workspace: WorkspaceInfo) {
    val remote = Organizing.remoteLabel(workspace.remote) ?: return
    MetaItem(Icons.Filled.CloudOff, remote, "SSH $remote", MaterialTheme.colorScheme.error, Modifier.padding(top = 2.dp))
}

@Composable
fun laneColor(key: String): Color = when (key) {
    "needs-attention" -> MaterialTheme.colorScheme.error
    "review" -> MaterialTheme.colorScheme.primary
    "working" -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun MetaItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    description: String,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier: Modifier = Modifier,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.semantics { contentDescription = description }) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
fun LanePickerSheet(current: LaneStatus?, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    ActionSheet(onDismiss = onDismiss, header = { Text("Status", style = MaterialTheme.typography.titleMedium) }) {
        val inferred = Organizing.laneLabel(current?.inferred)
        PickRow(
            label = if (inferred != null) "Automatic ($inferred)" else "Automatic",
            selected = current != null && !current.override,
            leading = { Icon(Icons.Outlined.AutoMode, contentDescription = null) },
            onClick = { onPick(Organizing.AUTOMATIC) },
        )
        Organizing.lanes.forEach { lane ->
            PickRow(
                label = lane.label,
                selected = current?.override == true && current.effective == lane.key,
                leading = { Box(Modifier.size(12.dp).background(laneColor(lane.key), CircleShape)) },
                onClick = { onPick(lane.key) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorPickerSheet(title: String, current: String?, onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    ActionSheet(onDismiss = onDismiss, header = { Text(title, style = MaterialTheme.typography.titleMedium) }) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Organizing.palette.forEach { color ->
                val selected = color.hex.equals(current, ignoreCase = true)
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(parseHexColor(color.hex) ?: Color.Gray)
                        .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                        .clickable(onClickLabel = color.name) { onPick(color.hex) }
                        .semantics { contentDescription = if (selected) "${color.name}, selected" else color.name },
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White)
                }
            }
        }
        SheetAction(Icons.Filled.FormatColorReset, "No color") { onPick(null) }
    }
}

@Composable
fun GroupPickerSheet(groups: List<WorkspaceGroupInfo>, onDismiss: () -> Unit, onPick: (WorkspaceGroupInfo) -> Unit, onNew: () -> Unit) {
    ActionSheet(onDismiss = onDismiss, header = { Text("Add to group", style = MaterialTheme.typography.titleMedium) }) {
        groups.forEach { group ->
            PickRow(
                label = group.name.ifBlank { "Group" },
                selected = false,
                leading = {
                    Icon(
                        Icons.Filled.Folder,
                        contentDescription = null,
                        tint = group.customColor?.let(::parseHexColor) ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                onClick = { onPick(group) },
            )
        }
        SheetAction(Icons.Filled.CreateNewFolder, "New group") { onNew() }
    }
}

@Composable
private fun PickRow(label: String, selected: Boolean, leading: @Composable () -> Unit, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .semantics { if (selected) contentDescription = "$label, selected" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(40.dp), contentAlignment = Alignment.CenterStart) { leading() }
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    }
}
