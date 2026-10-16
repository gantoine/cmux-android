package com.gantoine.muxer

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.pm.ShortcutManagerCompat
import com.gantoine.muxer.notifications.Notifier
import com.gantoine.muxer.share.ShareRouting
import com.gantoine.muxer.ui.AppNavigation
import com.gantoine.muxer.ui.CmuxTheme
import com.gantoine.muxer.ui.LaunchTarget

class MainActivity : ComponentActivity() {
    private var launchTarget by mutableStateOf<LaunchTarget?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as MuxerApplication
        if (savedInstanceState == null) launchTarget = targetFrom(intent)
        setContent {
            CmuxTheme {
                AppNavigation(app, launchTarget, onLaunchTargetHandled = { launchTarget = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        targetFrom(intent)?.let { launchTarget = it }
    }

    private fun targetFrom(intent: Intent?): LaunchTarget? {
        intent ?: return null
        if (intent.action == Intent.ACTION_VIEW) PairLink.parse(intent.data)?.let { return it }
        if (intent.action == ACTION_PAIR) return LaunchTarget.PairComputer
        if (intent.action == Intent.ACTION_SEND) return shareTarget(intent)
        if (intent.action == Notifier.ACTION_OPEN_TERMINAL) {
            val macId = intent.getStringExtra(Notifier.EXTRA_MAC_ID) ?: return null
            val workspaceId = intent.getStringExtra(Notifier.EXTRA_WORKSPACE_ID) ?: return null
            val surfaceId = intent.getStringExtra(Notifier.EXTRA_SURFACE_ID) ?: return null
            return LaunchTarget.Terminal(macId, workspaceId, surfaceId)
        }
        return null
    }

    private fun shareTarget(intent: Intent): LaunchTarget? {
        val text = ShareRouting.sharedText(intent.action, intent.type, intent.getCharSequenceExtra(Intent.EXTRA_TEXT))
            ?: return null
        return LaunchTarget.Share(text, intent.getStringExtra(ShortcutManagerCompat.EXTRA_SHORTCUT_ID))
    }

    companion object {
        const val ACTION_PAIR = "com.gantoine.muxer.PAIR"
    }
}
