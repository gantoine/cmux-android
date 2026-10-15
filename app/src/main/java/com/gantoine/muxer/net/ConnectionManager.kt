package com.gantoine.muxer.net

import com.gantoine.muxer.data.Mac
import com.gantoine.muxer.data.MacStore
import com.gantoine.muxer.protocol.Approval
import com.gantoine.muxer.protocol.RelayNotification
import com.gantoine.muxer.protocol.Welcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
    private val notifyApproval: (Mac, Approval) -> Unit,
    private val dismissApproval: (String) -> Unit,
) {
    private val connections = mutableMapOf<String, RelayConnection>()
    private val approvalWatchers = mutableMapOf<String, Job>()
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
            connections.keys.filterNot { it in ids }.forEach {
                connections.remove(it)?.stop()
                approvalWatchers.remove(it)?.cancel()
            }
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
                    ).also { approvalWatchers[mac.id] = scope.launch { watchApprovals(mac.id, it) } }
                }
            }
            for (mac in macs) {
                val connection = connections[mac.id] ?: continue
                if (appVisible || mac.backgroundNotifications) connection.start() else connection.stop()
            }
            _connectionsFlow.value = connections.toMap()
        }
    }

    private fun isLookingAt(macId: String, surfaceId: String?): Boolean {
        val visible = visibleTerminal.value
        return appVisible.value && visible?.macId == macId && visible.surfaceId == surfaceId
    }

    private suspend fun watchApprovals(macId: String, connection: RelayConnection) {
        var shown = emptySet<String>()
        try {
            connection.approvals.collect { approvals ->
                val ids = approvals.map { it.id }.toSet()
                (shown - ids).forEach(dismissApproval)
                val mac = store.current().firstOrNull { it.id == macId }
                if (mac != null) {
                    approvals
                        .filter { it.id !in shown && !isLookingAt(macId, it.surfaceId) }
                        .forEach { notifyApproval(mac, it) }
                }
                shown = ids
            }
        } finally {
            shown.forEach(dismissApproval)
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
                val answerableHere = connection(macId)?.approvals?.value.orEmpty().any { it.surfaceId == notification.surfaceId }
                if (!isLookingAt(macId, notification.surfaceId) && !answerableHere) notify(mac, notification)
            }
            "dismissed" -> dismiss(notification)
        }
    }
}
