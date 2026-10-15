package com.gantoine.muxer.wear

import com.gantoine.muxer.data.Mac
import com.gantoine.muxer.data.MacSnapshot
import com.gantoine.muxer.protocol.Approval
import com.gantoine.muxer.protocol.TerminalInfo
import com.gantoine.muxer.ui.AgentStatus
import com.gantoine.muxer.ui.Approvals
import com.gantoine.muxer.ui.WorkspaceListing
import com.gantoine.muxer.watch.WatchAgent
import com.gantoine.muxer.watch.WatchApproval
import com.gantoine.muxer.watch.WatchComputer
import com.gantoine.muxer.watch.WatchSummary

object WatchSummaries {
    const val MAX_OPTIONS = 4
    const val MAX_SUMMARY_LENGTH = 280

    fun build(macs: List<Mac>, snapshots: Map<String, MacSnapshot>, now: Long): WatchSummary {
        val live = macs.mapNotNull { mac -> snapshots[mac.id]?.takeIf { it.connected } }
        return WatchSummary(
            computers = macs.map { WatchComputer(it.id, it.name, snapshots[it.id]?.connected == true) },
            approvals = live.flatMap { snapshot -> snapshot.approvals.map { approval(snapshot, it) } },
            working = live.flatMap(::working),
            updatedAt = macs.mapNotNull { snapshots[it.id]?.updatedAt }.maxOrNull() ?: now,
        )
    }

    private fun approval(snapshot: MacSnapshot, approval: Approval) = WatchApproval(
        id = approval.id,
        macId = snapshot.macId,
        workspaceId = approval.workspaceId,
        surfaceId = approval.surfaceId,
        kind = approval.kind,
        agent = Approvals.agentName(approval),
        workspace = workspaceTitle(snapshot, approval.workspaceId),
        title = Approvals.title(approval),
        summary = Approvals.summary(approval).take(MAX_SUMMARY_LENGTH),
        options = options(approval),
    )

    private fun options(approval: Approval): List<String> {
        if (!approval.isQuestion) return emptyList()
        val question = approval.questions.singleOrNull() ?: return emptyList()
        if (question.multiSelect || question.options.size !in 1..MAX_OPTIONS) return emptyList()
        return question.options.map { it.label }
    }

    private fun working(snapshot: MacSnapshot): List<WatchAgent> {
        val waiting = snapshot.approvals.map { it.surfaceId }.toSet()
        return snapshot.workspaces.flatMap { workspace ->
            workspace.terminals
                .filter { it.id !in waiting && isWorking(snapshot, it) }
                .map { WatchAgent(snapshot.macId, agentName(it), workspace.title.ifBlank { snapshot.macName }) }
        }
    }

    private fun isWorking(snapshot: MacSnapshot, terminal: TerminalInfo): Boolean =
        terminal.id in snapshot.busySurfaces || WorkspaceListing.agentStatus(terminal) is AgentStatus.Working

    private fun agentName(terminal: TerminalInfo): String =
        terminal.agentSource?.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() } ?: "Agent"

    private fun workspaceTitle(snapshot: MacSnapshot, workspaceId: String): String =
        snapshot.workspaces.firstOrNull { it.id == workspaceId }?.title?.takeIf { it.isNotBlank() } ?: snapshot.macName
}
