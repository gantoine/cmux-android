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
                val connection = app.connections.connection(target.macId)
                val result = when {
                    connection == null -> Result.failure(IllegalStateException("This computer is not paired anymore."))
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

        fun intent(context: Context, target: ApprovalTarget, mode: String? = null, answer: String? = null, plan: Boolean = false): PendingIntent {
            val intent = Intent(context, ApprovalActionReceiver::class.java).apply {
                action = ACTION_ANSWER
                putExtra(EXTRA_MAC_ID, target.macId)
                putExtra(EXTRA_APPROVAL_ID, target.approvalId)
                putExtra(EXTRA_WORKSPACE_ID, target.workspaceId)
                putExtra(EXTRA_SURFACE_ID, target.surfaceId)
                putExtra(EXTRA_MODE, mode)
                putExtra(EXTRA_ANSWER, answer)
                putExtra(EXTRA_PLAN, plan)
            }
            val requestCode = listOf(target.approvalId, mode, answer).hashCode()
            return PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }
}
