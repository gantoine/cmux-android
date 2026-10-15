package com.gantoine.muxer.notifications

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.gantoine.muxer.MuxerApplication
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class AgentReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as MuxerApplication
        val target = ConversationTarget(
            macId = intent.getStringExtra(EXTRA_MAC_ID) ?: return,
            workspaceId = intent.getStringExtra(EXTRA_WORKSPACE_ID) ?: return,
            surfaceId = intent.getStringExtra(EXTRA_SURFACE_ID) ?: return,
        )
        val agentName = intent.getStringExtra(EXTRA_AGENT_NAME) ?: "Agent"
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_TEXT)?.toString()?.trim()
        if (text.isNullOrEmpty()) return
        val pending = goAsync()
        val delivery = app.scope.launch {
            val result = app.connections.sendAgentMessage(target, text)
            val macName = app.store.current().firstOrNull { it.id == target.macId }?.name ?: "your computer"
            val failure = result.exceptionOrNull()?.let { it.message ?: "The computer did not accept it." }
            app.notifier.showReply(target, macName, agentName, text, failure)
        }
        app.scope.launch {
            withTimeoutOrNull(RECEIVER_BUDGET_MS) { delivery.join() }
            pending.finish()
        }
    }

    companion object {
        const val KEY_TEXT = "agent_message"
        private const val ACTION_MESSAGE = "com.gantoine.muxer.MESSAGE_AGENT"
        private const val EXTRA_MAC_ID = "mac_id"
        private const val EXTRA_WORKSPACE_ID = "workspace_id"
        private const val EXTRA_SURFACE_ID = "surface_id"
        private const val EXTRA_AGENT_NAME = "agent_name"
        private const val RECEIVER_BUDGET_MS = 8_000L

        fun intent(context: Context, target: ConversationTarget, agentName: String): PendingIntent {
            val intent = Intent(context, AgentReplyReceiver::class.java).apply {
                action = ACTION_MESSAGE
                putExtra(EXTRA_MAC_ID, target.macId)
                putExtra(EXTRA_WORKSPACE_ID, target.workspaceId)
                putExtra(EXTRA_SURFACE_ID, target.surfaceId)
                putExtra(EXTRA_AGENT_NAME, agentName)
            }
            return PendingIntent.getBroadcast(context, target.key.hashCode(), intent, Notifier.mutableFlag() or PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }
}
