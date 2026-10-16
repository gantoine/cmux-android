package com.gantoine.muxer.widget

import com.gantoine.muxer.data.MacSnapshot
import com.gantoine.muxer.protocol.Approval
import com.gantoine.muxer.protocol.Retry
import com.gantoine.muxer.protocol.WorkspaceInfo
import com.gantoine.muxer.ui.AgentStatus
import com.gantoine.muxer.ui.AgentMessaging
import com.gantoine.muxer.ui.Approvals
import com.gantoine.muxer.ui.WorkspaceListing
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

enum class WorkspaceState(val label: String) {
    Waiting("Waiting for you"),
    NeedsYou("Needs you"),
    Retrying("Retrying"),
    Working("Working"),
    Idle("Idle"),
}

data class BoardRow(val macId: String, val workspace: WorkspaceInfo, val state: WorkspaceState, val surfaceId: String?)

data class BoardSection(val macId: String, val title: String?, val rows: List<BoardRow>)

data class BoardCounts(val waiting: Int, val retrying: Int, val working: Int, val idle: Int, val needsYou: Int = 0)

sealed interface ApprovalButton {
    val label: String

    data class Decide(override val label: String, val mode: String, val plan: Boolean) : ApprovalButton
    data class Choose(override val label: String) : ApprovalButton
    data class OpenTerminal(override val label: String = "Answer") : ApprovalButton
}

data class ApprovalItem(val macId: String, val macName: String, val approval: Approval, val buttons: List<ApprovalButton>)

data class RetryItem(val macId: String, val macName: String, val retry: Retry, val workspaceId: String?)

data class NeedsYouItem(val macId: String, val macName: String, val workspaceId: String, val surfaceId: String, val label: String, val workspaceTitle: String)

object WidgetData {
    const val STALE_AFTER_MS = 2 * 60_000L
    private const val MAX_OPTION_BUTTONS = 3

    fun paired(snapshots: Map<String, MacSnapshot>, macIds: List<String>): List<MacSnapshot> = macIds.mapNotNull(snapshots::get)

    fun isStale(snapshot: MacSnapshot, now: Long): Boolean = !snapshot.connected || now - snapshot.updatedAt > STALE_AFTER_MS

    fun asOf(
        snapshots: Collection<MacSnapshot>,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String? {
        val oldest = snapshots.filter { isStale(it, now) }.minOfOrNull { it.updatedAt } ?: return null
        val time = Instant.ofEpochMilli(oldest).atZone(zone).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))
        return "as of $time"
    }

    fun state(workspace: WorkspaceInfo, snapshot: MacSnapshot): WorkspaceState {
        val surfaces = workspace.terminals.map { it.id }.toSet()
        fun belongs(workspaceId: String?, surfaceId: String) = workspaceId == workspace.id || surfaceId in surfaces
        return when {
            snapshot.approvals.any { belongs(it.workspaceId, it.surfaceId) } -> WorkspaceState.Waiting
            needsYou(workspace, snapshot) -> WorkspaceState.NeedsYou
            snapshot.retries.any { belongs(it.workspaceId, it.surfaceId) } -> WorkspaceState.Retrying
            workspace.terminals.any { it.id in snapshot.busySurfaces || WorkspaceListing.agentStatus(it) is AgentStatus.Working } ->
                WorkspaceState.Working
            else -> WorkspaceState.Idle
        }
    }

    fun needsYou(workspace: WorkspaceInfo, snapshot: MacSnapshot): Boolean {
        val surfaces = workspace.terminals.map { it.id }.toSet()
        fun belongs(workspaceId: String?, surfaceId: String) = workspaceId == workspace.id || surfaceId in surfaces
        if (workspace.terminals.any { it.id in snapshot.busySurfaces }) return false
        if (snapshot.approvals.any { belongs(it.workspaceId, it.surfaceId) } || snapshot.retries.any { belongs(it.workspaceId, it.surfaceId) }) return false
        return WorkspaceListing.needsYou(workspace) || WorkspaceListing.needsAttention(workspace.preview)
    }

    fun needsYouLabel(workspace: WorkspaceInfo): String =
        (WorkspaceListing.agentStatus(workspace) as? AgentStatus.NeedsYou)?.label
            ?: workspace.preview?.trim()?.takeIf { it.isNotEmpty() }
            ?: "Needs you"

    fun needsYou(snapshots: List<MacSnapshot>): List<NeedsYouItem> =
        snapshots.flatMap { snapshot ->
            snapshot.workspaces.filter { needsYou(it, snapshot) }.mapNotNull { workspace ->
                val surfaceId = agentSurface(workspace) ?: return@mapNotNull null
                NeedsYouItem(
                    macId = snapshot.macId,
                    macName = snapshot.macName,
                    workspaceId = workspace.id,
                    surfaceId = surfaceId,
                    label = needsYouLabel(workspace),
                    workspaceTitle = workspace.title.ifBlank { "a workspace" },
                )
            }
        }

    fun agentSurface(workspace: WorkspaceInfo): String? =
        (workspace.terminals.firstOrNull { it.agentSource != null || it.agentState != null } ?: workspace.terminals.firstOrNull())?.id

    fun board(snapshots: List<MacSnapshot>, showComputerNames: Boolean): List<BoardSection> =
        snapshots.map { snapshot ->
            BoardSection(
                macId = snapshot.macId,
                title = snapshot.macName.takeIf { showComputerNames },
                rows = snapshot.workspaces.map { BoardRow(snapshot.macId, it, state(it, snapshot), agentSurface(it)) },
            )
        }

    fun counts(sections: List<BoardSection>): BoardCounts {
        val states = sections.flatMap { section -> section.rows.map { it.state } }
        return BoardCounts(
            waiting = states.count { it == WorkspaceState.Waiting },
            retrying = states.count { it == WorkspaceState.Retrying },
            working = states.count { it == WorkspaceState.Working },
            idle = states.count { it == WorkspaceState.Idle },
            needsYou = states.count { it == WorkspaceState.NeedsYou },
        )
    }

    fun countsLabel(counts: BoardCounts): String {
        val parts = listOf(counts.waiting to "waiting", counts.needsYou to "need you", counts.retrying to "retrying", counts.working to "working")
            .filter { (count, _) -> count > 0 }
            .map { (count, label) -> "$count $label" }
        return when {
            parts.isNotEmpty() -> parts.joinToString(" · ")
            counts.idle > 0 -> "All idle"
            else -> "No workspaces"
        }
    }

    fun buttons(approval: Approval): List<ApprovalButton> {
        if (approval.isPermission) {
            return listOf(
                ApprovalButton.Decide("Allow", Approvals.MODE_ONCE, plan = false),
                ApprovalButton.Decide("Always", Approvals.MODE_ALWAYS, plan = false),
                ApprovalButton.Decide("Deny", Approvals.MODE_DENY, plan = false),
            )
        }
        if (approval.isPlan) {
            return listOf(
                ApprovalButton.Decide("Approve", Approvals.PLAN_MANUAL, plan = true),
                ApprovalButton.Decide("Auto-edit", Approvals.PLAN_AUTO_ACCEPT, plan = true),
                ApprovalButton.Decide("Reject", Approvals.PLAN_DENY, plan = true),
            )
        }
        val question = approval.questions.singleOrNull()
        if (question == null || question.multiSelect || question.options.size !in 1..MAX_OPTION_BUTTONS) {
            return listOf(ApprovalButton.OpenTerminal())
        }
        return question.options.map { ApprovalButton.Choose(it.label) }
    }

    fun approvals(snapshots: List<MacSnapshot>): List<ApprovalItem> =
        snapshots
            .flatMap { snapshot -> snapshot.approvals.map { ApprovalItem(snapshot.macId, snapshot.macName, it, buttons(it)) } }
            .sortedByDescending { item -> item.approval.createdAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Instant.EPOCH }

    fun retries(snapshots: List<MacSnapshot>): List<RetryItem> =
        snapshots.flatMap { snapshot ->
            snapshot.retries.map { retry ->
                val workspaceId = retry.workspaceId ?: snapshot.workspaces.firstOrNull { w -> w.terminals.any { it.id == retry.surfaceId } }?.id
                RetryItem(snapshot.macId, snapshot.macName, retry, workspaceId)
            }
        }

    fun retryLabel(retry: Retry, now: Instant): String {
        val agent = retry.agent?.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() } ?: "The agent"
        val seconds = AgentMessaging.secondsUntil(retry, now)
        return if (seconds == null || seconds == 0L) "$agent retries now" else "$agent retries in %d:%02d".format(seconds / 60, seconds % 60)
    }
}
