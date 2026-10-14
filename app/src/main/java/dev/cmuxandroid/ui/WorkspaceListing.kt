package dev.cmuxandroid.ui

import dev.cmuxandroid.protocol.WorkspaceInfo
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

object WorkspaceListing {
    val fallbackColors = listOf("#8CAAEE", "#A6D189", "#EF9F76", "#CA9EE6", "#81C8BE", "#E5C890", "#F4B8E4", "#99D1DB")

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

    fun isAgent(workspace: WorkspaceInfo): Boolean = workspace.terminals.any { it.agentSource != null || it.agentState != null }

    fun color(workspace: WorkspaceInfo): String =
        workspace.customColor ?: fallbackColors[Math.floorMod(workspace.id.hashCode(), fallbackColors.size)]

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
}
