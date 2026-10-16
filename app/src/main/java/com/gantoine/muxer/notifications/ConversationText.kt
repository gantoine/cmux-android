package com.gantoine.muxer.notifications

import com.gantoine.muxer.protocol.RelayNotification

object ConversationText {
    private val completed = Regex("^completed(?: in (.+))?$", RegexOption.IGNORE_CASE)

    fun message(notification: RelayNotification, agentName: String): String {
        val title = notification.title?.trim()?.takeIf { it.isNotEmpty() && !namesAgent(it, agentName) && it != notification.tabTitle }
        val status = notification.subtitle?.trim()?.takeIf { it.isNotEmpty() }?.let(::status)
        val body = notification.body?.trim()?.takeIf { it.isNotEmpty() }
        val detail = listOfNotNull(status, body).joinToString(" · ")
        val lines = listOfNotNull(title, detail.takeIf { it.isNotEmpty() })
        if (lines.isNotEmpty()) return lines.joinToString("\n")
        return if (NotificationFilter.kind(notification) == NotificationFilter.Kind.Completed) "Finished" else notification.tabTitle?.takeIf { it.isNotBlank() } ?: "Needs a look"
    }

    private fun status(subtitle: String): String {
        val match = completed.matchEntire(subtitle) ?: return subtitle
        val duration = match.groupValues[1].takeIf { it.isNotBlank() }
        return if (duration == null) "Finished" else "Finished in $duration"
    }

    private fun namesAgent(title: String, agentName: String): Boolean =
        title.equals(agentName, ignoreCase = true) || title.startsWith("$agentName ", ignoreCase = true)
}
