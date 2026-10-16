package com.gantoine.muxer.net

import com.gantoine.muxer.data.Mac
import com.gantoine.muxer.data.MacSnapshot
import com.gantoine.muxer.data.MacStore
import com.gantoine.muxer.data.SnapshotStore
import com.gantoine.muxer.notifications.ConversationTarget
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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.delay
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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

    private val awake = MutableStateFlow(false)
    private var awakeUntil = 0L
    private var sleepJob: Job? = null

    fun start() {
        scope.launch {
            combine(store.macs, appVisible, awake) { macs, visible, awake -> macs to (visible || awake) }
                .collect { (macs, wanted) -> reconcile(macs, wanted) }
        }
    }

    fun setAppVisible(visible: Boolean) {
        appVisible.value = visible
    }

    fun wake(durationMs: Long) {
        synchronized(awake) {
            awakeUntil = maxOf(awakeUntil, System.currentTimeMillis() + durationMs)
            awake.value = true
            sleepJob?.cancel()
            sleepJob = scope.launch {
                delay(awakeUntil - System.currentTimeMillis())
                awake.value = false
            }
        }
    }

    fun connection(macId: String): RelayConnection? = synchronized(connections) { connections[macId] }

    suspend fun awaitConnected(macId: String, timeoutMs: Long): RelayConnection? = withTimeoutOrNull(timeoutMs) {
        val connection = connectionsFlow.map { it[macId] }.filterNotNull().first()
        connection.state.first { it is ConnectionState.Connected }
        connection
    }

    suspend fun sendAgentMessage(target: ConversationTarget, text: String): Result<Unit> {
        val shared = connection(target.macId) ?: return Result.failure(IllegalStateException("This computer is not paired anymore."))
        val route = AgentReplyRoute.choose(
            sharedConnected = shared.state.value is ConnectionState.Connected,
            sharedAttachedToTarget = shared.isAttachedTo(target.surfaceId),
        )
        return when (route) {
            AgentReplyRoute.Shared -> deliverAgentMessage(shared, text)
            AgentReplyRoute.Separate -> withSeparateConnection(target.macId) { separate ->
                val attachment = separate.attach(target.workspaceId, target.surfaceId)
                deliverAgentMessage(separate, text).also { separate.detach(attachment) }
            }
        }
    }

    private suspend fun deliverAgentMessage(connection: RelayConnection, text: String): Result<Unit> {
        val messaged = connection.sendAgentMessage(text)
        val error = messaged.exceptionOrNull() ?: return Result.success(Unit)
        if (!AgentReplyRoute.shouldPasteInstead(error)) return Result.failure(error)
        return connection.request("paste", buildJsonObject {
            put("text", text)
            put("submit", true)
        }).map { }
    }

    private suspend fun <T> withSeparateConnection(macId: String, action: suspend (RelayConnection) -> Result<T>): Result<T> {
        val mac = store.current().firstOrNull { it.id == macId }
            ?: return Result.failure(IllegalStateException("This computer is not paired anymore."))
        val token = store.token(mac) ?: return Result.failure(IllegalStateException("Pair ${mac.name} again."))
        val separate = RelayConnection(
            macId = mac.id,
            baseUrl = mac.url,
            token = token,
            client = client,
            scope = scope,
            notificationsAfter = { mac.lastNotificationSeq },
            onNotification = { _, _ -> },
        )
        return try {
            separate.start()
            val state = withTimeoutOrNull(SEPARATE_CONNECT_TIMEOUT_MS) {
                separate.state.first { it is ConnectionState.Connected || it is ConnectionState.Unauthorized }
            }
            if (state is ConnectionState.Connected) action(separate) else Result.failure(IllegalStateException("Couldn't reach ${mac.name}."))
        } finally {
            separate.stop()
        }
    }

    private fun reconcile(macs: List<Mac>, connectAll: Boolean) {
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
                if (connectAll || mac.backgroundNotifications) connection.start() else connection.stop()
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

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
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
            .flatMapLatest { inputs -> repeatedWhileConnected(inputs) }
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

    private fun repeatedWhileConnected(inputs: SnapshotInputs) = flow {
        emit(inputs)
        while (inputs.connected) {
            delay(SNAPSHOT_HEARTBEAT_MS)
            emit(inputs)
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
        const val SNAPSHOT_HEARTBEAT_MS = 60_000L
        const val SEPARATE_CONNECT_TIMEOUT_MS = 6_000L
    }
}
