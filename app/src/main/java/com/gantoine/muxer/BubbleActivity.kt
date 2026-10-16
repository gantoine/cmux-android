package com.gantoine.muxer

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.gantoine.muxer.notifications.ConversationTarget
import com.gantoine.muxer.notifications.Notifier
import com.gantoine.muxer.ui.CmuxTheme
import com.gantoine.muxer.ui.TerminalScreen

class BubbleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as MuxerApplication
        val macId = intent.getStringExtra(Notifier.EXTRA_MAC_ID)
        val workspaceId = intent.getStringExtra(Notifier.EXTRA_WORKSPACE_ID)
        val surfaceId = intent.getStringExtra(Notifier.EXTRA_SURFACE_ID)
        if (macId == null || workspaceId == null || surfaceId == null) {
            finish()
            return
        }
        setContent {
            CmuxTheme {
                TerminalScreen(app, macId, workspaceId, surfaceId, onBack = ::finish)
            }
        }
    }

    companion object {
        private const val ACTION_BUBBLE = "com.gantoine.muxer.BUBBLE"

        fun intent(context: Context, target: ConversationTarget): Intent =
            Intent(context, BubbleActivity::class.java).apply {
                action = ACTION_BUBBLE
                putExtra(Notifier.EXTRA_MAC_ID, target.macId)
                putExtra(Notifier.EXTRA_WORKSPACE_ID, target.workspaceId)
                putExtra(Notifier.EXTRA_SURFACE_ID, target.surfaceId)
            }
    }
}
