package com.gantoine.muxer.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.gantoine.muxer.MainActivity
import com.gantoine.muxer.R
import com.gantoine.muxer.data.Mac
import com.gantoine.muxer.protocol.RelayNotification

class Notifier(private val context: Context) {
    fun createChannels() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(AGENT_CHANNEL, context.getString(R.string.channel_agents), NotificationManager.IMPORTANCE_HIGH)
                .apply { description = context.getString(R.string.channel_agents_description) },
        )
        manager.createNotificationChannel(
            NotificationChannel(CONNECTION_CHANNEL, context.getString(R.string.channel_connection), NotificationManager.IMPORTANCE_MIN)
                .apply { description = context.getString(R.string.channel_connection_description) },
        )
    }

    fun canNotify(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun show(mac: Mac, notification: RelayNotification) {
        if (!canNotify()) return
        val title = notification.title?.takeIf { it.isNotBlank() } ?: notification.tabTitle ?: mac.name
        val context = listOfNotNull(mac.name, notification.tabTitle?.takeIf { it != title }).joinToString(" · ")
        val body = listOfNotNull(notification.subtitle?.takeIf { it.isNotBlank() }, notification.body).joinToString("\n")
        val built = NotificationCompat.Builder(this.context, AGENT_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setSubText(context)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setGroup(mac.id)
            .setContentIntent(openTerminalIntent(mac.id, notification))
            .build()
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(this.context).notify(notification.id.hashCode(), built)
    }

    fun dismiss(notification: RelayNotification) {
        NotificationManagerCompat.from(context).cancel(notification.id.hashCode())
    }

    private fun openTerminalIntent(macId: String, notification: RelayNotification): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_TERMINAL
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_MAC_ID, macId)
            putExtra(EXTRA_WORKSPACE_ID, notification.workspaceId)
            putExtra(EXTRA_SURFACE_ID, notification.surfaceId)
        }
        return PendingIntent.getActivity(
            context,
            notification.id.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        const val AGENT_CHANNEL = "agents"
        const val CONNECTION_CHANNEL = "connection"
        const val ACTION_OPEN_TERMINAL = "com.gantoine.muxer.OPEN_TERMINAL"
        const val EXTRA_MAC_ID = "mac_id"
        const val EXTRA_WORKSPACE_ID = "workspace_id"
        const val EXTRA_SURFACE_ID = "surface_id"
    }
}
