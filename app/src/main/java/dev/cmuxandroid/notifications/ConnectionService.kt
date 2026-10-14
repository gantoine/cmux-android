package dev.cmuxandroid.notifications

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.cmuxandroid.CmuxApplication
import dev.cmuxandroid.MainActivity
import dev.cmuxandroid.R
import dev.cmuxandroid.net.ConnectionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

class ConnectionService : LifecycleService() {
    override fun onCreate() {
        super.onCreate()
        startInForeground(connected = 0, total = 0)
        watchConnections()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun watchConnections() {
        val manager = (application as CmuxApplication).connections
        lifecycleScope.launch {
            manager.connectionsFlow.flatMapLatest { connections ->
                if (connections.isEmpty()) {
                    flowOf(0 to 0)
                } else {
                    combine(connections.values.map { it.state }) { states ->
                        states.count { it is ConnectionState.Connected } to states.size
                    }
                }
            }.collect { (connected, total) -> startInForeground(connected, total) }
        }
    }

    private fun startInForeground(connected: Int, total: Int) {
        val text = when {
            total == 0 -> "No computers paired"
            connected == total -> if (total == 1) "Connected to your computer" else "Connected to $total computers"
            else -> "Connected to $connected of $total computers"
        }
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, Notifier.CONNECTION_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Watching for terminal notifications")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openApp)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    companion object {
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ConnectionService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ConnectionService::class.java))
        }
    }
}
