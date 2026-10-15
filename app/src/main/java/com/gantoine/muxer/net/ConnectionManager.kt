package com.gantoine.muxer.net

import com.gantoine.muxer.data.Mac
import com.gantoine.muxer.data.MacSnapshot
import com.gantoine.muxer.data.MacStore
import com.gantoine.muxer.data.SnapshotStore
import com.gantoine.muxer.notifications.NotificationFilter
import com.gantoine.muxer.protocol.Approval
import com.gantoine.muxer.protocol.RelayNotification
import com.gantoine.muxer.protocol.Retry
import com.gantoine.muxer.protocol.Welcome
import com.gantoine.muxer.protocol.WorkspaceInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

data class VisibleTerminal(val macId: String, val surfaceId: String)

class ConnectionManager(
    private val store: MacStore,
    private val snapshots: SnapshotStore,
    private val client: OkHttpClient,
    private val scope: CoroutineScope,
    private val notify: (Mac, RelayNotification) -> Unit,
    private val dismiss: (RelayNotification) -> Unit,
    private val notifyApproval: (Mac, Approval) -> Unit,
    private val dismissApproval: (String) -> Unit,
) {
    private val connections = mutableMapOf<String, RelayConnection>()
    private val watchers = mutableMapOf<String, Job>()
    private val shownBySurface = mutableMapOf<Pair<String, String>, List<RelayNotification>>()
    private val _connectionsFlow = MutableStateFlow<Map<String, RelayConnection>>(emptyMap())
    val connectionsFlow: StateFlow<Map<String, RelayConnection>> = _connectionsFlow.asStateFlow()

    private val appVisible = MutableStateFlow(false)
    val visibleTerminal = MutableStateFlow<VisibleTerminal?>(null)
    val lookingAt: Flow<VisibleTerminal?> = combine(appVisible, visibleTerminal) { visible, terminal -> terminal.takeIf { visible } }

    @OptIn(ExperimentalCoroutinesApi::class)
    val connectedMacIds: Flow<Set<String>> = connectionsFlow.flatMapLatest { connections ->
        if (connections.isEmpty()) {
            flowOf(emptySet())
        } else {
            combine(connections.map { (macId, connection) -> connection.state.map { macId to (it is ConnectionState.Connected) } }) { states ->
                states.filter { it.second }.map { it.first }.toSet()
            }
        }
    }.distinctUntilChanged()

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
            connections.keys.filterNot { it in ids }.forEach { macId ->
                connections.remove(macId)?.stop()
                watchers.remove(macId)?.cancel()
                scope.launch { snapshots.remove(macId) }
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
                    ).also { connection ->
                        watchers[mac.id] = scope.launch {
                            launch { watchApprovals(mac.id, connection) }
                            launch { watchRetries(mac.id, connection) }
                            launch { writeSnapshots(mac.id, connection) }
                        }
                    }
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

    @OptIn(FlowPreview::class)
    private suspend fun writeSnapshots(macId: String, connection: RelayConnection) {
        combine(
            connection.state,
            connection.workspaces,
            connection.approvals,
            connection.retries,
            connection.busySurfaces,
        ) { state, workspaces, approvals, retries, busy ->
            SnapshotInputs(state is ConnectionState.Connected, workspaces, approvals, retries, busy)
        }
            .sample(SNAPSHOT_INTERVAL_MS)
            .collect { inputs ->
                val mac = store.current().firstOrNull { it.id == macId } ?: return@collect
                if (!inputs.connected) {
                    snapshots.markDisconnected(macId)
                    return@collect
                }
                snapshots.write(
                    MacSnapshot(
                        macId = macId,
                        macName = mac.name,
                        connected = true,
                        updatedAt = System.currentTimeMillis(),
                        workspaces = inputs.workspaces.orEmpty(),
                        approvals = inputs.approvals,
                        retries = inputs.retries,
                        busySurfaces = inputs.busy,
                    ),
                )
            }
    }

    private data class SnapshotInputs(
        val connected: Boolean,
        val workspaces: List<WorkspaceInfo>?,
        val approvals: List<Approval>,
        val retries: List<Retry>,
        val busy: Set<String>,
    )

    private suspend fun watchRetries(macId: String, connection: RelayConnection) {
        connection.retries.collect { retries ->
            retries.forEach { retry -> dismissShown(macId, retry.surfaceId) }
        }
    }

    private fun dismissShown(macId: String, surfaceId: String) {
        val shown = synchronized(shownBySurface) { shownBySurface.remove(macId to surfaceId) }.orEmpty()
        shown.forEach(dismiss)
    }

    private fun recordShown(macId: String, notification: RelayNotification) {
        val surfaceId = notification.surfaceId ?: return
        synchronized(shownBySurface) {
            val key = macId to surfaceId
            shownBySurface[key] = (shownBySurface[key].orEmpty() + notification).takeLast(MAX_TRACKED_PER_TERMINAL)
        }
    }

    private fun forgetShown(macId: String, notification: RelayNotification) {
        synchronized(shownBySurface) {
            shownBySurface.keys.filter { it.first == macId }.forEach { key ->
                val remaining = shownBySurface[key].orEmpty().filterNot { it.id == notification.id }
                if (remaining.isEmpty()) shownBySurface.remove(key) else shownBySurface[key] = remaining
            }
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
                val connection = connection(macId)
                val answerableHere = connection?.approvals?.value.orEmpty().any { it.surfaceId == notification.surfaceId }
                val retrying = connection?.retries?.value.orEmpty().any { it.surfaceId == notification.surfaceId }
                val wanted = NotificationFilter.shouldShow(mac, notification)
                if (wanted && !isLookingAt(macId, notification.surfaceId) && !answerableHere && !retrying) {
                    notify(mac, notification)
                    recordShown(macId, notification)
                }
            }
            "dismissed" -> {
                dismiss(notification)
                forgetShown(macId, notification)
            }
        }
    }

    private companion object {
        const val MAX_TRACKED_PER_TERMINAL = 10
        const val SNAPSHOT_INTERVAL_MS = 500L
    }
}
