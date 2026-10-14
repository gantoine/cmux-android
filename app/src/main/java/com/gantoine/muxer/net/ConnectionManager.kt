package com.gantoine.muxer.net

import com.gantoine.muxer.data.Mac
import com.gantoine.muxer.data.MacStore
import com.gantoine.muxer.protocol.RelayNotification
import com.gantoine.muxer.protocol.Welcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

data class VisibleTerminal(val macId: String, val surfaceId: String)

class ConnectionManager(
    private val store: MacStore,
    private val client: OkHttpClient,
    private val scope: CoroutineScope,
    private val notify: (Mac, RelayNotification) -> Unit,
    private val dismiss: (RelayNotification) -> Unit,
) {
    private val connections = mutableMapOf<String, RelayConnection>()
    private val _connectionsFlow = MutableStateFlow<Map<String, RelayConnection>>(emptyMap())
    val connectionsFlow: StateFlow<Map<String, RelayConnection>> = _connectionsFlow.asStateFlow()

    private val appVisible = MutableStateFlow(false)
    val visibleTerminal = MutableStateFlow<VisibleTerminal?>(null)

    fun start() {
        scope.launch {
            combine(store.macs, appVisible) { macs, visible -> macs to visible }
                .collect { (macs, visible) -> reconcile(macs, visible) }
        }
    }

    fun setAppVisible(visible: Boolean) {
        appVisible.value = visible
    }

    fun connection(macId: String): RelayConnection? = synchronized(connections) { connections[macId] }

    private fun reconcile(macs: List<Mac>, appVisible: Boolean) {
        synchronized(connections) {
            val ids = macs.map { it.id }.toSet()
            connections.keys.filterNot { it in ids }.forEach { connections.remove(it)?.stop() }
            for (mac in macs) {
                val existing = connections[mac.id]
                if (existing == null) {
                    val token = store.token(mac) ?: continue
                    connections[mac.id] = RelayConnection(
                        macId = mac.id,
                        baseUrl = mac.url,
                        token = token,
                        client = client,
                        scope = scope,
                        notificationsAfter = { store.current().firstOrNull { it.id == mac.id }?.lastNotificationSeq ?: 0 },
                        onNotification = ::handleNotification,
                        onWelcome = ::handleWelcome,
                    )
                }
            }
            for (mac in macs) {
                val connection = connections[mac.id] ?: continue
                if (appVisible || mac.backgroundNotifications) connection.start() else connection.stop()
            }
            _connectionsFlow.value = connections.toMap()
        }
    }

    private suspend fun handleWelcome(macId: String, welcome: Welcome) {
        val mac = store.current().firstOrNull { it.id == macId } ?: return
        if (mac.lastNotificationSeq == 0L) store.recordNotificationSeq(macId, welcome.notificationSeq)
    }

    private suspend fun handleNotification(macId: String, notification: RelayNotification) {
        val mac = store.macs.first().firstOrNull { it.id == macId } ?: return
        if (notification.seq <= mac.lastNotificationSeq) return
        store.recordNotificationSeq(macId, notification.seq)
        when (notification.kind) {
            "created" -> {
                val visible = visibleTerminal.value
                val looking = appVisible.value && visible?.macId == macId && visible.surfaceId == notification.surfaceId
                if (!looking) notify(mac, notification)
            }
            "dismissed" -> dismiss(notification)
        }
    }
}
