package com.gantoine.muxer.notifications

import com.gantoine.muxer.data.Mac
import com.gantoine.muxer.protocol.RelayNotification

object NotificationFilter {
    enum class Kind { Waiting, Completed, Other }

    private const val WAITING_SUBTITLE = "Waiting"
    private const val COMPLETED_SUBTITLE = "Completed"
    private const val WAITING_BODY = "waiting for your input"

    fun kind(notification: RelayNotification): Kind {
        val subtitle = notification.subtitle?.trim().orEmpty()
        return when {
            subtitle == WAITING_SUBTITLE -> Kind.Waiting
            subtitle.isEmpty() && notification.body?.contains(WAITING_BODY, ignoreCase = true) == true -> Kind.Waiting
            subtitle == COMPLETED_SUBTITLE || subtitle.startsWith("$COMPLETED_SUBTITLE in ") -> Kind.Completed
            else -> Kind.Other
        }
    }

    fun shouldShow(mac: Mac, notification: RelayNotification): Boolean = when (kind(notification)) {
        Kind.Waiting -> false
        Kind.Completed -> mac.completionNotifications
        Kind.Other -> true
    }

    fun channel(notification: RelayNotification): String = when (kind(notification)) {
        Kind.Completed -> Notifier.COMPLETIONS_CHANNEL
        else -> Notifier.ALERTS_CHANNEL
    }

    fun isConversation(notification: RelayNotification): Boolean =
        !notification.workspaceId.isNullOrBlank() && !notification.surfaceId.isNullOrBlank()
}
