package com.gantoine.muxer.notifications

import com.gantoine.muxer.model.AgentNames
import com.gantoine.muxer.model.AgentStatus
import com.gantoine.muxer.model.WorkspaceListing
import com.gantoine.muxer.model.parseInstant
import com.gantoine.muxer.model.secondsBetween
import com.gantoine.muxer.protocol.Approval
import com.gantoine.muxer.protocol.Retry
import com.gantoine.muxer.protocol.TerminalInfo
import com.gantoine.muxer.protocol.WorkspaceInfo
import java.time.Instant

data class ComputerActivity(
    val macId: String,
    val workspaces: List<WorkspaceInfo>,
    val busySurfaces: Set<String>,
    val retries: List<Retry>,
    val approvals: List<Approval> = emptyList(),
)

sealed interface LiveActivity {
    val key: String
    val macId: String
    val workspaceId: String?
    val surfaceId: String
    val agent: String?
    val workspaceTitle: String
}

data class WorkingAgent(
    override val macId: String,
    override val workspaceId: String?,
    override val surfaceId: String,
    override val agent: String?,
    override val workspaceTitle: String,
    val since: Long? = null,
) : LiveActivity {
    override val key get() = "activity:$macId:$surfaceId"
}

data class WaitingAgent(
    override val macId: String,
    override val workspaceId: String?,
    override val surfaceId: String,
    override val agent: String?,
    override val workspaceTitle: String,
    val request: String,
) : LiveActivity {
    override val key get() = "waiting:$macId:$surfaceId"
}

data class RetryCountdown(
    override val macId: String,
    override val workspaceId: String?,
    override val surfaceId: String,
    override val agent: String?,
    override val workspaceTitle: String,
    val attempt: Int,
    val dueAt: Instant,
) : LiveActivity {
    override val key get() = "retry:$macId:$surfaceId"
}

data class ActivitySummary(
    val waiting: List<WaitingAgent> = emptyList(),
    val retries: List<RetryCountdown> = emptyList(),
    val working: List<WorkingAgent> = emptyList(),
    val connected: Int = 0,
    val total: Int = 0,
    val macNames: Map<String, String> = emptyMap(),
) {
    val activities: List<LiveActivity> get() = waiting + retries + working
}

data class Chronometer(val at: Long, val countDown: Boolean)

object LiveActivities {
    fun working(activity: ComputerActivity): List<WorkingAgent> {
        val retrying = activity.retries.map { it.surfaceId }.toSet()
        val waiting = activity.approvals.map { it.surfaceId }.toSet()
        return activity.workspaces.flatMap { workspace ->
            workspace.terminals
                .filter { isWorking(it, activity.busySurfaces) && it.id !in retrying && it.id !in waiting }
                .map { terminal ->
                    WorkingAgent(
                        macId = activity.macId,
                        workspaceId = workspace.id,
                        surfaceId = terminal.id,
                        agent = AgentNames.display(terminal.agentSource),
                        workspaceTitle = title(workspace, terminal),
                    )
                }
        }.distinctBy { it.surfaceId }
    }

    fun waiting(activity: ComputerActivity): List<WaitingAgent> =
        activity.approvals.map { approval ->
            val workspace = WorkspaceListing.workspaceFor(activity.workspaces, approval.workspaceId, approval.surfaceId)
            val terminal = workspace?.terminals?.firstOrNull { it.id == approval.surfaceId }
            WaitingAgent(
                macId = activity.macId,
                workspaceId = approval.workspaceId,
                surfaceId = approval.surfaceId,
                agent = AgentNames.display(approval.agent) ?: AgentNames.display(terminal?.agentSource),
                workspaceTitle = title(workspace, terminal),
                request = request(approval),
            )
        }.distinctBy { it.surfaceId }

    fun retries(activity: ComputerActivity, now: Instant): List<RetryCountdown> =
        activity.retries.mapNotNull { retry ->
            val dueAt = parseInstant(retry.dueAt) ?: return@mapNotNull null
            if (!dueAt.isAfter(now)) return@mapNotNull null
            val workspace = WorkspaceListing.workspaceFor(activity.workspaces, retry.workspaceId, retry.surfaceId)
            val terminal = workspace?.terminals?.firstOrNull { it.id == retry.surfaceId }
            RetryCountdown(
                macId = activity.macId,
                workspaceId = retry.workspaceId ?: workspace?.id,
                surfaceId = retry.surfaceId,
                agent = AgentNames.display(retry.agent) ?: AgentNames.display(terminal?.agentSource),
                workspaceTitle = title(workspace, terminal),
                attempt = retry.attempt,
                dueAt = dueAt,
            )
        }.distinctBy { it.surfaceId }

    fun isWorking(terminal: TerminalInfo, busySurfaces: Set<String>): Boolean =
        terminal.id in busySurfaces || WorkspaceListing.agentStatus(terminal) is AgentStatus.Working

    fun request(approval: Approval): String = when {
        approval.isPermission -> "wants to use ${approval.tool ?: "a tool"}"
        approval.isPlan -> "has a plan ready"
        else -> "is asking you something"
    }

    fun title(summary: ActivitySummary): String {
        val parts = listOfNotNull(
            summary.waiting.size.takeIf { it > 0 }?.let { "$it waiting for you" },
            summary.retries.size.takeIf { it > 0 }?.let { "$it retrying" },
            summary.working.size.takeIf { it > 0 }?.let { if (it == 1) "1 agent working" else "$it agents working" },
        )
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ") ?: connectionText(summary)
    }

    fun connectionText(summary: ActivitySummary): String = when {
        summary.total == 0 -> "No computers to watch"
        summary.connected < summary.total -> "Connected to ${summary.connected} of ${summary.total} computers"
        summary.total == 1 -> "Connected to ${summary.macNames.values.singleOrNull() ?: "your computer"}"
        else -> "Connected to ${summary.total} computers"
    }

    fun lines(summary: ActivitySummary, now: Instant): List<String> = summary.activities.map { activity ->
        val state = when (activity) {
            is WaitingAgent -> activity.request
            is RetryCountdown -> "retries in ${countdown(secondsUntil(activity, now))}, attempt ${activity.attempt}"
            is WorkingAgent -> "working"
        }
        val place = if (summary.total > 1) "${summary.macNames[activity.macId] ?: "?"} › ${activity.workspaceTitle}" else activity.workspaceTitle
        "${activity.agent ?: "An agent"} · $place · $state"
    }

    fun chipText(summary: ActivitySummary, now: Instant): String? = when {
        summary.waiting.isNotEmpty() -> "${summary.waiting.size} waiting"
        summary.retries.isNotEmpty() -> countdown(secondsUntil(summary.retries.minBy { it.dueAt }, now))
        summary.working.isNotEmpty() -> "${summary.working.size} working"
        else -> null
    }

    fun chronometer(summary: ActivitySummary): Chronometer? = when {
        summary.waiting.isNotEmpty() -> null
        summary.retries.isNotEmpty() -> Chronometer(summary.retries.minBy { it.dueAt }.dueAt.toEpochMilli(), countDown = true)
        summary.working.any { it.since != null } -> Chronometer(summary.working.mapNotNull { it.since }.min(), countDown = false)
        else -> null
    }

    fun secondsUntil(retry: RetryCountdown, now: Instant): Long = secondsBetween(now, retry.dueAt)

    fun countdown(seconds: Long): String {
        val clamped = seconds.coerceAtLeast(0)
        return "%d:%02d".format(clamped / 60, clamped % 60)
    }

    private fun title(workspace: WorkspaceInfo?, terminal: TerminalInfo?): String =
        workspace?.title?.takeIf { it.isNotBlank() } ?: terminal?.title?.takeIf { it.isNotBlank() } ?: "a workspace"
}
