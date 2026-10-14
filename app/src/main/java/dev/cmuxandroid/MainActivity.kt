package dev.cmuxandroid

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.cmuxandroid.notifications.Notifier
import dev.cmuxandroid.ui.AppNavigation
import dev.cmuxandroid.ui.CmuxTheme
import dev.cmuxandroid.ui.LaunchTarget

class MainActivity : ComponentActivity() {
    private var launchTarget by mutableStateOf<LaunchTarget?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as CmuxApplication
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
        val data = intent.data
        if (intent.action == Intent.ACTION_VIEW && data?.scheme == "cmuxandroid" && data.host == "pair") {
            return LaunchTarget.Pair(data.getQueryParameter("url").orEmpty(), data.getQueryParameter("code").orEmpty())
        }
        if (intent.action == Notifier.ACTION_OPEN_TERMINAL) {
            val macId = intent.getStringExtra(Notifier.EXTRA_MAC_ID) ?: return null
            val workspaceId = intent.getStringExtra(Notifier.EXTRA_WORKSPACE_ID) ?: return null
            val surfaceId = intent.getStringExtra(Notifier.EXTRA_SURFACE_ID) ?: return null
            return LaunchTarget.Terminal(macId, workspaceId, surfaceId)
        }
        return null
    }
}
