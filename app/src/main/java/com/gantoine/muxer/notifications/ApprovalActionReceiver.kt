package com.gantoine.muxer.notifications

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.gantoine.muxer.MuxerApplication
import kotlinx.coroutines.launch

data class ApprovalTarget(val macId: String, val approvalId: String, val workspaceId: String, val surfaceId: String)

class ApprovalActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as MuxerApplication
        val target = ApprovalTarget(
            macId = intent.getStringExtra(Notifier.EXTRA_MAC_ID) ?: return,
            approvalId = intent.getStringExtra(EXTRA_APPROVAL_ID) ?: return,
            workspaceId = intent.getStringExtra(Notifier.EXTRA_WORKSPACE_ID).orEmpty(),
            surfaceId = intent.getStringExtra(Notifier.EXTRA_SURFACE_ID).orEmpty(),
        )
        val typed = intent.getBooleanExtra(EXTRA_TYPED, false)
        val answer = if (typed) typedAnswer(intent) ?: return else intent.getStringExtra(EXTRA_ANSWER)
        val decision = ApprovalDecision(mode = intent.getStringExtra(EXTRA_MODE), answer = answer)
        val pending = goAsync()
        app.scope.launch {
            try {
                app.answerApproval(target, decision).onFailure { error ->
                    val macName = app.store.find(target.macId)?.name ?: "your computer"
                    app.notifier.showApprovalFailed(macName, target, error.message ?: "The computer did not accept it.")
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun typedAnswer(intent: Intent): String? =
        RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_TYPED_ANSWER)?.toString()?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        const val KEY_TYPED_ANSWER = "typed_answer"
        private const val ACTION_ANSWER = "com.gantoine.muxer.ANSWER_APPROVAL"
        private const val ACTION_TYPED_ANSWER = "com.gantoine.muxer.TYPE_APPROVAL_ANSWER"
        private const val EXTRA_TYPED = "typed"
        private const val EXTRA_APPROVAL_ID = "approval_id"
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_ANSWER = "answer"

        fun intent(context: Context, target: ApprovalTarget, mode: String? = null, answer: String? = null): PendingIntent {
            val requestCode = listOf(target.approvalId, mode, answer).hashCode()
            return PendingIntent.getBroadcast(
                context,
                requestCode,
                broadcastIntent(context, target, mode, answer),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        fun broadcastIntent(context: Context, target: ApprovalTarget, mode: String? = null, answer: String? = null): Intent =
            Intent(context, ApprovalActionReceiver::class.java).apply {
                action = ACTION_ANSWER
                putExtra(Notifier.EXTRA_MAC_ID, target.macId)
                putExtra(EXTRA_APPROVAL_ID, target.approvalId)
                putExtra(Notifier.EXTRA_WORKSPACE_ID, target.workspaceId)
                putExtra(Notifier.EXTRA_SURFACE_ID, target.surfaceId)
                putExtra(EXTRA_MODE, mode)
                putExtra(EXTRA_ANSWER, answer)
            }

        fun typedAnswerIntent(context: Context, target: ApprovalTarget): PendingIntent {
            val intent = Intent(context, ApprovalActionReceiver::class.java).apply {
                action = ACTION_TYPED_ANSWER
                putExtra(Notifier.EXTRA_MAC_ID, target.macId)
                putExtra(EXTRA_APPROVAL_ID, target.approvalId)
                putExtra(Notifier.EXTRA_WORKSPACE_ID, target.workspaceId)
                putExtra(Notifier.EXTRA_SURFACE_ID, target.surfaceId)
                putExtra(EXTRA_TYPED, true)
            }
            val requestCode = listOf(target.approvalId, ACTION_TYPED_ANSWER).hashCode()
            return PendingIntent.getBroadcast(context, requestCode, intent, Notifier.mutableFlag() or PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }
}
