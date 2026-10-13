package dev.cmuxandroid.net

import dev.cmuxandroid.protocol.DetachedMessage
import dev.cmuxandroid.protocol.NotificationMessage
import dev.cmuxandroid.protocol.RelayJson
import dev.cmuxandroid.protocol.RelayNotification
import dev.cmuxandroid.protocol.RequestError
import dev.cmuxandroid.protocol.ScreenFrame
import dev.cmuxandroid.protocol.Welcome
import dev.cmuxandroid.protocol.WorkspaceInfo
import dev.cmuxandroid.protocol.WorkspacesMessage
import dev.cmuxandroid.terminal.TerminalModel
import dev.cmuxandroid.terminal.TerminalScreen
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

sealed interface ConnectionState {
    data object Connecting : ConnectionState
    data class Connected(val welcome: Welcome) : ConnectionState
    data class Disconnected(val reason: String) : ConnectionState
    data object Unauthorized : ConnectionState
    data object Stopped : ConnectionState
}

class RelayException(val code: String, message: String) : Exception(message)

data class Attachment(val workspaceId: String, val surfaceId: String, val fit: Pair<Int, Int>?)

class RelayConnection(
    private val macId: String,
    private val baseUrl: String,
    private val token: String,
    private val client: OkHttpClient,
    private val scope: CoroutineScope,
    private val notificationsAfter: suspend () -> Long,
    private val onNotification: suspend (String, RelayNotification) -> Unit,
) {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Stopped)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _workspaces = MutableStateFlow<List<WorkspaceInfo>?>(null)
    val workspaces: StateFlow<List<WorkspaceInfo>?> = _workspaces.asStateFlow()

    private val _screen = MutableStateFlow<TerminalScreen?>(null)
    val screen: StateFlow<TerminalScreen?> = _screen.asStateFlow()

    private val _detached = MutableSharedFlow<DetachedMessage>(extraBufferCapacity = 8)
    val detached: SharedFlow<DetachedMessage> = _detached.asSharedFlow()

    private val pending = ConcurrentHashMap<String, CompletableDeferred<Result<JsonElement?>>>()
    private val nextId = AtomicLong()
    private var socket: WebSocket? = null
    private var loop: Job? = null
    @Volatile private var attachment: Attachment? = null

    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch { runLoop() }
    }

    fun stop() {
        loop?.cancel()
        loop = null
        socket?.close(1000, "closing")
        socket = null
        failPending("The connection closed.")
        _state.value = ConnectionState.Stopped
    }

    private suspend fun runLoop() {
        var backoffMs = 1_000L
        while (true) {
            _state.value = ConnectionState.Connecting
            val closed = CompletableDeferred<CloseReason>()
            val request = Request.Builder()
                .url("$baseUrl/v1/ws")
                .header("Authorization", "Bearer $token")
                .build()
            val webSocket = client.newWebSocket(request, Listener(closed))
            socket = webSocket
            val reason = try {
                closed.await()
            } finally {
                webSocket.cancel()
                socket = null
                failPending("The connection to the Mac dropped.")
            }
            if (reason.unauthorized) {
                _state.value = ConnectionState.Unauthorized
                return
            }
            if (reason.wasConnected) backoffMs = 1_000L
            _state.value = ConnectionState.Disconnected(reason.message)
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
        }
    }

    private data class CloseReason(val message: String, val unauthorized: Boolean = false, val wasConnected: Boolean = false)

    private inner class Listener(private val closed: CompletableDeferred<CloseReason>) : WebSocketListener() {
        @Volatile private var opened = false

        override fun onOpen(webSocket: WebSocket, response: Response) {
            opened = true
            scope.launch { onOpened() }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleMessage(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            val message = if (code == 1008) "This phone was removed on the Mac. Pair it again." else "The Mac closed the connection."
            closed.complete(CloseReason(message, unauthorized = code == 1008, wasConnected = opened))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            closed.complete(CloseReason("The Mac closed the connection.", wasConnected = opened))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (response?.code == 401) {
                closed.complete(CloseReason("This phone is no longer paired with the Mac.", unauthorized = true))
                return
            }
            closed.complete(CloseReason(t.message ?: "Could not reach the Mac.", wasConnected = opened))
        }
    }

    private suspend fun onOpened() {
        val hello = request("hello", buildJsonObject { put("notifications_after", notificationsAfter()) })
        hello.onSuccess { data ->
            val welcome = data?.let { RelayJson.decodeFromJsonElement(Welcome.serializer(), it) } ?: return
            _state.value = ConnectionState.Connected(welcome)
            attachment?.let { sendAttach(it) }
        }.onFailure {
            _state.value = ConnectionState.Disconnected(it.message ?: "The Mac rejected the connection.")
            socket?.close(1000, "hello failed")
        }
    }

    private fun handleMessage(text: String) {
        val message = runCatching { RelayJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        when (message["type"]?.jsonPrimitive?.contentOrNull) {
            "result" -> completeRequest(message)
            "workspaces" -> _workspaces.value = RelayJson.decodeFromJsonElement(WorkspacesMessage.serializer(), message).workspaces
            "screen" -> {
                val frame = RelayJson.decodeFromJsonElement(ScreenFrame.serializer(), message)
                if (frame.surfaceId != attachment?.surfaceId) return
                val next = TerminalModel.apply(_screen.value, frame)
                if (next == null) send("resync") else _screen.value = next
            }
            "detached" -> {
                val detached = RelayJson.decodeFromJsonElement(DetachedMessage.serializer(), message)
                if (detached.surfaceId == attachment?.surfaceId) {
                    attachment = null
                    _screen.value = null
                }
                _detached.tryEmit(detached)
            }
            "notification" -> {
                val notification = RelayJson.decodeFromJsonElement(NotificationMessage.serializer(), message).notification
                scope.launch { onNotification(macId, notification) }
            }
        }
    }

    private fun completeRequest(message: JsonObject) {
        val id = message["id"]?.jsonPrimitive?.contentOrNull ?: return
        val deferred = pending.remove(id) ?: return
        if (message["ok"]?.jsonPrimitive?.booleanOrNull == true) {
            deferred.complete(Result.success(message["data"]))
        } else {
            val error = message["error"]?.let { RelayJson.decodeFromJsonElement(RequestError.serializer(), it) }
            deferred.complete(Result.failure(RelayException(error?.code ?: "error", error?.message ?: "The Mac could not do that.")))
        }
    }

    private fun failPending(message: String) {
        val failures = pending.keys.mapNotNull { pending.remove(it) }
        failures.forEach { it.complete(Result.failure(RelayException("disconnected", message))) }
    }

    suspend fun request(type: String, fields: JsonObject = JsonObject(emptyMap())): Result<JsonElement?> {
        val id = nextId.incrementAndGet().toString()
        val deferred = CompletableDeferred<Result<JsonElement?>>()
        pending[id] = deferred
        val sent = socket?.send(message(type, fields, id).toString()) ?: false
        if (!sent) {
            pending.remove(id)
            return Result.failure(RelayException("disconnected", "Not connected to the Mac."))
        }
        val result = withTimeoutOrNull(REQUEST_TIMEOUT_MS) { deferred.await() }
        if (result != null) return result
        pending.remove(id)
        return Result.failure(RelayException("timeout", "The Mac took too long to answer."))
    }

    fun send(type: String, fields: JsonObject = JsonObject(emptyMap())) {
        socket?.send(message(type, fields, id = null).toString())
    }

    private fun message(type: String, fields: JsonObject, id: String?): JsonObject = JsonObject(
        buildMap<String, JsonElement> {
            putAll(fields)
            put("type", JsonPrimitive(type))
            if (id != null) put("id", JsonPrimitive(id))
        },
    )

    fun attach(workspaceId: String, surfaceId: String, fit: Pair<Int, Int>? = null) {
        val next = Attachment(workspaceId, surfaceId, fit)
        if (attachment?.surfaceId != surfaceId) _screen.value = null
        attachment = next
        sendAttach(next)
    }

    fun setFit(fit: Pair<Int, Int>?) {
        val current = attachment ?: return
        attachment = current.copy(fit = fit)
        if (fit == null) {
            send("unfit")
        } else {
            send("fit", buildJsonObject {
                put("columns", fit.first)
                put("rows", fit.second)
            })
        }
    }

    fun detach() {
        attachment = null
        _screen.value = null
        send("detach")
    }

    fun resync() = send("resync")

    private fun sendAttach(target: Attachment) {
        send("attach", buildJsonObject {
            put("workspace_id", target.workspaceId)
            put("surface_id", target.surfaceId)
            target.fit?.let { (columns, rows) ->
                put("fit", buildJsonObject {
                    put("columns", columns)
                    put("rows", rows)
                })
            }
        })
    }

    private companion object {
        const val REQUEST_TIMEOUT_MS = 20_000L
    }
}
