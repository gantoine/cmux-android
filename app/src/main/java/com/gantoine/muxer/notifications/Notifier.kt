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
import com.gantoine.muxer.protocol.Approval
import com.gantoine.muxer.protocol.RelayNotification
import com.gantoine.muxer.ui.Approvals

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

    fun showApproval(mac: Mac, approval: Approval) {
        if (!canNotify()) return
        val summary = Approvals.summary(approval)
        val body = listOfNotNull(
            approval.description?.takeIf { approval.isPermission && it.isNotBlank() && it != approval.detail },
            summary,
        ).joinToString("\n")
        val builder = NotificationCompat.Builder(context, AGENT_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(Approvals.title(approval))
            .setSubText(mac.name)
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setGroup(mac.id)
            .setContentIntent(openTerminalIntent(mac.id, approval.workspaceId, approval.surfaceId, approvalNotificationId(approval.id)))
        approvalActions(mac.id, approval).forEach { (label, intent) -> builder.addAction(0, label, intent) }
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(context).notify(approvalNotificationId(approval.id), builder.build())
    }

    fun showApprovalFailed(macName: String, approval: ApprovalTarget, message: String) {
        if (!canNotify()) return
        val built = NotificationCompat.Builder(context, AGENT_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Your answer didn't reach $macName")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$message\nOpen the terminal to answer there."))
            .setAutoCancel(true)
            .setGroup(approval.macId)
            .setContentIntent(openTerminalIntent(approval.macId, approval.workspaceId, approval.surfaceId, approvalNotificationId(approval.approvalId)))
            .build()
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(context).notify(approvalNotificationId(approval.approvalId), built)
    }

    fun dismissApproval(approvalId: String) {
        NotificationManagerCompat.from(context).cancel(approvalNotificationId(approvalId))
    }

    private fun approvalActions(macId: String, approval: Approval): List<Pair<String, PendingIntent>> {
        val target = ApprovalTarget(macId, approval.id, approval.workspaceId, approval.surfaceId)
        if (approval.isPermission) {
            return listOf(
                "Allow" to ApprovalActionReceiver.intent(context, target, mode = Approvals.MODE_ONCE),
                "Always allow" to ApprovalActionReceiver.intent(context, target, mode = Approvals.MODE_ALWAYS),
                "Deny" to ApprovalActionReceiver.intent(context, target, mode = Approvals.MODE_DENY),
            )
        }
        if (approval.isPlan) {
            return listOf(
                "Approve" to ApprovalActionReceiver.intent(context, target, mode = Approvals.PLAN_MANUAL, plan = true),
                "Auto-edit" to ApprovalActionReceiver.intent(context, target, mode = Approvals.PLAN_AUTO_ACCEPT, plan = true),
                "Reject" to ApprovalActionReceiver.intent(context, target, mode = Approvals.PLAN_DENY, plan = true),
            )
        }
        val question = approval.questions.singleOrNull() ?: return emptyList()
        if (question.multiSelect || question.options.size !in 1..MAX_NOTIFICATION_ACTIONS) return emptyList()
        return question.options.map { option -> option.label to ApprovalActionReceiver.intent(context, target, answer = option.label) }
    }

    private fun openTerminalIntent(macId: String, notification: RelayNotification): PendingIntent =
        openTerminalIntent(macId, notification.workspaceId, notification.surfaceId, notification.id.hashCode())

    private fun openTerminalIntent(macId: String, workspaceId: String?, surfaceId: String?, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_TERMINAL
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_MAC_ID, macId)
            putExtra(EXTRA_WORKSPACE_ID, workspaceId)
            putExtra(EXTRA_SURFACE_ID, surfaceId)
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun approvalNotificationId(approvalId: String): Int = "approval:$approvalId".hashCode()

    companion object {
        const val AGENT_CHANNEL = "agents"
        const val CONNECTION_CHANNEL = "connection"
        const val ACTION_OPEN_TERMINAL = "com.gantoine.muxer.OPEN_TERMINAL"
        const val EXTRA_MAC_ID = "mac_id"
        const val EXTRA_WORKSPACE_ID = "workspace_id"
        const val EXTRA_SURFACE_ID = "surface_id"
        private const val MAX_NOTIFICATION_ACTIONS = 3
    }
}
