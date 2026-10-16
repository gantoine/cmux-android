package com.gantoine.muxer.notifications

import com.gantoine.muxer.data.MacSnapshot
import com.gantoine.muxer.protocol.WorkspaceInfo
import com.gantoine.muxer.ui.WorkspaceListing

data class WorkspaceShortcut(
    val macId: String,
    val macName: String,
    val workspaceId: String,
    val surfaceId: String,
    val title: String,
    val color: String,
    val agentName: String?,
) {
    val id: String get() = ShortcutPlan.id(macId, workspaceId)
}

object ShortcutPlan {
    private const val PREFIX = "ws:"
    private const val FALLBACK_TITLE = "Workspace"

    fun id(macId: String, workspaceId: String): String = "$PREFIX$macId:$workspaceId"

    fun isWorkspaceShortcut(id: String): Boolean = id.startsWith(PREFIX)

    fun shortcut(macId: String, macName: String, workspace: WorkspaceInfo): WorkspaceShortcut? {
        val agentTerminal = workspace.terminals.firstOrNull { it.agentSource != null }
        val terminal = agentTerminal ?: workspace.terminals.firstOrNull() ?: return null
        return WorkspaceShortcut(
            macId = macId,
            macName = macName,
            workspaceId = workspace.id,
            surfaceId = terminal.id,
            title = workspace.title.ifBlank { terminal.title }.ifBlank { FALLBACK_TITLE },
            color = WorkspaceListing.color(workspace),
            agentName = AgentNames.display(agentTerminal?.agentSource),
        )
    }

    fun plan(snapshots: Collection<MacSnapshot>, limit: Int): List<WorkspaceShortcut> {
        if (limit <= 0) return emptyList()
        val candidates = snapshots.flatMap { snapshot ->
            snapshot.workspaces.mapNotNull { workspace ->
                shortcut(snapshot.macId, snapshot.macName, workspace)?.let { it to workspace.lastActivityAt }
            }
        }
        val agents = candidates.filter { (shortcut, _) -> shortcut.agentName != null }
        return byRecency(agents).take(limit)
    }

    fun gone(existingIds: Collection<String>, snapshots: Collection<MacSnapshot>): List<String> {
        val current = snapshots.flatMapTo(mutableSetOf()) { snapshot -> snapshot.workspaces.map { id(snapshot.macId, it.id) } }
        val unknownPrefixes = snapshots.filter { it.workspaces.isEmpty() }.map { "$PREFIX${it.macId}:" }
        return existingIds.filter { id ->
            isWorkspaceShortcut(id) && id !in current && unknownPrefixes.none { id.startsWith(it) }
        }
    }

    private fun byRecency(candidates: List<Pair<WorkspaceShortcut, Double?>>): List<WorkspaceShortcut> =
        candidates.sortedByDescending { (_, lastActivity) -> lastActivity ?: Double.NEGATIVE_INFINITY }.map { it.first }
}

object AgentNames {
    fun display(source: String?): String? {
        val first = source?.split('-', '_', ' ', '.')?.firstOrNull { it.isNotBlank() }?.trim() ?: return null
        return first.replaceFirstChar { it.uppercase() }
    }
}
