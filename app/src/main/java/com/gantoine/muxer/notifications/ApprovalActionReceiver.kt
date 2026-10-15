package com.gantoine.muxer.notifications

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.gantoine.muxer.MuxerApplication
import kotlinx.coroutines.launch

data class ApprovalTarget(val macId: String, val approvalId: String, val workspaceId: String, val surfaceId: String)

class ApprovalActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as MuxerApplication
        val target = ApprovalTarget(
            macId = intent.getStringExtra(EXTRA_MAC_ID) ?: return,
            approvalId = intent.getStringExtra(EXTRA_APPROVAL_ID) ?: return,
            workspaceId = intent.getStringExtra(EXTRA_WORKSPACE_ID).orEmpty(),
            surfaceId = intent.getStringExtra(EXTRA_SURFACE_ID).orEmpty(),
        )
        val mode = intent.getStringExtra(EXTRA_MODE)
        val plan = intent.getBooleanExtra(EXTRA_PLAN, false)
        val answer = intent.getStringExtra(EXTRA_ANSWER)
        app.notifier.dismissApproval(target.approvalId)
        val pending = goAsync()
        app.scope.launch {
            try {
                val paired = app.store.current().any { it.id == target.macId }
                if (paired) app.connections.wake(WAKE_MS)
                val connection = if (paired) app.connections.awaitConnected(target.macId, CONNECT_TIMEOUT_MS) else null
                val result = when {
                    !paired -> Result.failure(IllegalStateException("This computer is not paired anymore."))
                    connection == null -> Result.failure(IllegalStateException("Couldn't reach the computer."))
                    mode != null && plan -> connection.decidePlan(target.approvalId, mode)
                    mode != null -> connection.allow(target.approvalId, mode)
                    answer != null -> connection.answer(target.approvalId, listOf(answer))
                    else -> Result.success(Unit)
                }
                result.onFailure { error ->
                    val macName = app.store.current().firstOrNull { it.id == target.macId }?.name ?: "your computer"
                    app.notifier.showApprovalFailed(macName, target, error.message ?: "The computer did not accept it.")
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val ACTION_ANSWER = "com.gantoine.muxer.ANSWER_APPROVAL"
        private const val EXTRA_MAC_ID = "mac_id"
        private const val EXTRA_APPROVAL_ID = "approval_id"
        private const val EXTRA_WORKSPACE_ID = "workspace_id"
        private const val EXTRA_SURFACE_ID = "surface_id"
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_ANSWER = "answer"
        private const val EXTRA_PLAN = "plan"
        private const val WAKE_MS = 30_000L
        private const val CONNECT_TIMEOUT_MS = 8_000L

        fun intent(context: Context, target: ApprovalTarget, mode: String? = null, answer: String? = null, plan: Boolean = false): PendingIntent {
            val requestCode = listOf(target.approvalId, mode, answer).hashCode()
            return PendingIntent.getBroadcast(
                context,
                requestCode,
                broadcastIntent(context, target, mode, answer, plan),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        fun broadcastIntent(context: Context, target: ApprovalTarget, mode: String? = null, answer: String? = null, plan: Boolean = false): Intent =
            Intent(context, ApprovalActionReceiver::class.java).apply {
                action = ACTION_ANSWER
                putExtra(EXTRA_MAC_ID, target.macId)
                putExtra(EXTRA_APPROVAL_ID, target.approvalId)
                putExtra(EXTRA_WORKSPACE_ID, target.workspaceId)
                putExtra(EXTRA_SURFACE_ID, target.surfaceId)
                putExtra(EXTRA_MODE, mode)
                putExtra(EXTRA_ANSWER, answer)
                putExtra(EXTRA_PLAN, plan)
            }
    }
}
