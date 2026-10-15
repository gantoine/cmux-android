package com.gantoine.muxer.notifications

import com.gantoine.muxer.protocol.Retry
import com.gantoine.muxer.protocol.TerminalInfo
import com.gantoine.muxer.protocol.WorkspaceInfo
import com.gantoine.muxer.ui.AgentStatus
import com.gantoine.muxer.ui.WorkspaceListing
import java.time.Instant

data class ComputerActivity(
    val macId: String,
    val workspaces: List<WorkspaceInfo>,
    val busySurfaces: Set<String>,
    val retries: List<Retry>,
)

sealed interface LiveActivity {
    val key: String
    val macId: String
    val workspaceId: String?
    val surfaceId: String
}

data class WorkingAgent(
    override val macId: String,
    override val workspaceId: String?,
    override val surfaceId: String,
    val agent: String?,
    val workspaceTitle: String,
) : LiveActivity {
    override val key get() = "activity:$macId:$surfaceId"
}

data class RetryCountdown(
    override val macId: String,
    override val workspaceId: String?,
    override val surfaceId: String,
    val agent: String?,
    val workspaceTitle: String?,
    val attempt: Int,
    val dueAt: Instant,
) : LiveActivity {
    override val key get() = "retry:$macId:$surfaceId"
}

object LiveActivities {
    fun working(activity: ComputerActivity, isLookingAt: (String) -> Boolean = { false }): List<WorkingAgent> {
        val retrying = activity.retries.map { it.surfaceId }.toSet()
        return activity.workspaces.flatMap { workspace ->
            workspace.terminals
                .filter { isWorking(it, activity.busySurfaces) && it.id !in retrying && !isLookingAt(it.id) }
                .map { terminal ->
                    WorkingAgent(
                        macId = activity.macId,
                        workspaceId = workspace.id,
                        surfaceId = terminal.id,
                        agent = agentName(terminal.agentSource),
                        workspaceTitle = workspace.title.ifBlank { terminal.title }.ifBlank { "a workspace" },
                    )
                }
        }.distinctBy { it.surfaceId }
    }

    fun retries(activity: ComputerActivity, now: Instant, isLookingAt: (String) -> Boolean = { false }): List<RetryCountdown> =
        activity.retries.mapNotNull { retry ->
            val dueAt = runCatching { Instant.parse(retry.dueAt) }.getOrNull() ?: return@mapNotNull null
            if (!dueAt.isAfter(now) || isLookingAt(retry.surfaceId)) return@mapNotNull null
            val workspace = activity.workspaces.firstOrNull { it.id == retry.workspaceId }
                ?: activity.workspaces.firstOrNull { workspace -> workspace.terminals.any { it.id == retry.surfaceId } }
            val terminal = workspace?.terminals?.firstOrNull { it.id == retry.surfaceId }
            RetryCountdown(
                macId = activity.macId,
                workspaceId = retry.workspaceId ?: workspace?.id,
                surfaceId = retry.surfaceId,
                agent = agentName(retry.agent) ?: agentName(terminal?.agentSource),
                workspaceTitle = workspace?.title?.takeIf { it.isNotBlank() },
                attempt = retry.attempt,
                dueAt = dueAt,
            )
        }.distinctBy { it.surfaceId }

    fun isWorking(terminal: TerminalInfo, busySurfaces: Set<String>): Boolean =
        terminal.id in busySurfaces || WorkspaceListing.agentStatus(terminal) is AgentStatus.Working

    fun agentName(source: String?): String? {
        val trimmed = source?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (trimmed.contains("claude", ignoreCase = true)) return "Claude"
        return trimmed.replace('_', ' ').replace('-', ' ').replaceFirstChar { it.uppercase() }
    }

    fun workingTitle(agent: WorkingAgent): String = "${agent.agent ?: "An agent"} is working in ${agent.workspaceTitle}"

    fun retryTitle(retry: RetryCountdown, now: Instant): String =
        "${retry.agent ?: "The agent"} retries in ${countdown(secondsUntil(retry, now))}"

    fun secondsUntil(retry: RetryCountdown, now: Instant): Long =
        (retry.dueAt.epochSecond - now.epochSecond).coerceAtLeast(0)

    fun countdown(seconds: Long): String {
        val clamped = seconds.coerceAtLeast(0)
        return "%d:%02d".format(clamped / 60, clamped % 60)
    }

    fun progressPercent(totalSeconds: Long, remainingSeconds: Long): Int {
        if (totalSeconds <= 0) return 100
        val elapsed = (totalSeconds - remainingSeconds).coerceIn(0, totalSeconds)
        return (elapsed * 100 / totalSeconds).toInt()
    }
}
