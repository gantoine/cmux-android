package com.gantoine.muxer.ui

import com.gantoine.muxer.protocol.TerminalInfo
import com.gantoine.muxer.protocol.WorkspaceInfo
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

enum class WorkspaceFilter(val label: String) { All("All"), NeedsYou("Needs you"), Agents("Agents"), Pinned("Pinned") }

sealed interface AgentStatus {
    val label: String

    data class NeedsYou(override val label: String) : AgentStatus
    data class Working(override val label: String) : AgentStatus
    data class Other(override val label: String) : AgentStatus
}

object WorkspaceListing {
    val fallbackColors = listOf("#8CAAEE", "#A6D189", "#EF9F76", "#CA9EE6", "#81C8BE", "#E5C890", "#F4B8E4", "#99D1DB")

    private val attentionWords = listOf("wait", "input", "attention", "permission", "approv", "question", "blocked", "need", "review")
    private val workingWords = listOf("run", "work", "think", "busy", "generat", "active", "progress", "tool")
    private val terminalPromptWords = listOf("permission", "approv", "question")

    fun needsTerminalAnswer(status: AgentStatus?): Boolean =
        status is AgentStatus.NeedsYou && terminalPromptWords.any { status.label.contains(it, ignoreCase = true) }

    fun matches(workspace: WorkspaceInfo, query: String): Boolean {
        val needle = query.trim()
        if (needle.isEmpty()) return true
        val haystack = buildList {
            add(workspace.title)
            workspace.currentDirectory?.let(::add)
            workspace.preview?.let(::add)
            workspace.description?.let(::add)
            workspace.terminals.forEach { terminal ->
                add(terminal.title)
                terminal.currentDirectory?.let(::add)
                terminal.agentState?.let(::add)
            }
        }
        return haystack.any { it.contains(needle, ignoreCase = true) }
    }

    fun passes(workspace: WorkspaceInfo, filter: WorkspaceFilter): Boolean = when (filter) {
        WorkspaceFilter.All -> true
        WorkspaceFilter.NeedsYou -> needsYou(workspace)
        WorkspaceFilter.Agents -> isAgent(workspace)
        WorkspaceFilter.Pinned -> workspace.isPinned
    }

    fun count(workspaces: List<WorkspaceInfo>, filter: WorkspaceFilter): Int = workspaces.count { passes(it, filter) }

    fun isAgent(workspace: WorkspaceInfo): Boolean = workspace.terminals.any { it.agentSource != null || it.agentState != null }

    fun needsYou(workspace: WorkspaceInfo): Boolean =
        workspace.unreadCount > 0 || workspace.hasUnread || agentStatus(workspace) is AgentStatus.NeedsYou

    fun agentStatus(workspace: WorkspaceInfo): AgentStatus? =
        agentStatus(workspace.terminals.mapNotNull { it.agentState?.trim()?.takeIf(String::isNotEmpty) }.distinct())

    fun agentStatus(terminal: TerminalInfo): AgentStatus? =
        agentStatus(listOfNotNull(terminal.agentState?.trim()?.takeIf(String::isNotEmpty)))

    private fun agentStatus(states: List<String>): AgentStatus? {
        if (states.isEmpty()) return null
        states.firstOrNull { state -> attentionWords.any { state.contains(it, ignoreCase = true) } }
            ?.let { return AgentStatus.NeedsYou(humanize(it)) }
        states.firstOrNull { state -> workingWords.any { state.contains(it, ignoreCase = true) } }
            ?.let { return AgentStatus.Working(humanize(it)) }
        return AgentStatus.Other(humanize(states.first()))
    }

    fun subtitle(workspace: WorkspaceInfo): String =
        workspace.preview?.trim()?.takeIf(String::isNotEmpty)
            ?: workspace.currentDirectory?.let(::shortPath)
            ?: when (val count = workspace.terminals.size) {
                0, 1 -> "Terminal"
                else -> "$count terminals"
            }

    fun color(workspace: WorkspaceInfo): String =
        workspace.customColor ?: fallbackColors[Math.floorMod(workspace.id.hashCode(), fallbackColors.size)]

    fun shortPath(path: String): String {
        val home = Regex("^/Users/[^/]+").find(path)?.value ?: return path
        return "~" + path.removePrefix(home)
    }

    fun time(
        epochSeconds: Double?,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String? {
        if (epochSeconds == null || epochSeconds <= 0) return null
        val moment = Instant.ofEpochMilli((epochSeconds * 1000).toLong()).atZone(zone)
        val today = now.atZone(zone).toLocalDate()
        val day = moment.toLocalDate()
        val daysAgo = ChronoUnit.DAYS.between(day, today)
        return when {
            daysAgo <= 0L -> moment.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))
            daysAgo == 1L -> "Yesterday"
            daysAgo < 7L -> day.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
            else -> moment.format(DateTimeFormatter.ofPattern("MMM d", locale))
        }
    }

    private fun humanize(state: String): String =
        state.replace('_', ' ').replace('-', ' ').trim().replaceFirstChar { it.uppercase() }
}
