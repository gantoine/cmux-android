package com.gantoine.cmux.net

import com.gantoine.cmux.chat.ChatModel
import com.gantoine.cmux.chat.ChatState
import com.gantoine.cmux.protocol.ChatMessage
import com.gantoine.cmux.protocol.ChatSessionMessage
import com.gantoine.cmux.protocol.DetachedMessage
import com.gantoine.cmux.protocol.HistoryResponse
import com.gantoine.cmux.protocol.NotificationMessage
import com.gantoine.cmux.protocol.RelayJson
import com.gantoine.cmux.protocol.RelayNotification
import com.gantoine.cmux.protocol.RequestError
import com.gantoine.cmux.protocol.ScreenFrame
import com.gantoine.cmux.protocol.Welcome
import com.gantoine.cmux.protocol.WorkspaceInfo
import com.gantoine.cmux.protocol.WorkspacesMessage
import com.gantoine.cmux.terminal.TerminalModel
import com.gantoine.cmux.terminal.TerminalScreen
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
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

data class Attachment(val id: Long, val workspaceId: String, val surfaceId: String, val fit: Pair<Int, Int>?)

class RelayConnection(
    private val macId: String,
    private val baseUrl: String,
    private val token: String,
    private val client: OkHttpClient,
    private val scope: CoroutineScope,
    private val notificationsAfter: suspend () -> Long,
    private val onNotification: suspend (String, RelayNotification) -> Unit,
    private val onWelcome: suspend (String, Welcome) -> Unit = { _, _ -> },
) {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Stopped)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _workspaces = MutableStateFlow<List<WorkspaceInfo>?>(null)
    val workspaces: StateFlow<List<WorkspaceInfo>?> = _workspaces.asStateFlow()

    private val _screen = MutableStateFlow<TerminalScreen?>(null)
    val screen: StateFlow<TerminalScreen?> = _screen.asStateFlow()

    private val _chat = MutableStateFlow<ChatState?>(null)
    val chat: StateFlow<ChatState?> = _chat.asStateFlow()

    private val _detached = MutableSharedFlow<DetachedMessage>(extraBufferCapacity = 8)
    val detached: SharedFlow<DetachedMessage> = _detached.asSharedFlow()

    private val pending = ConcurrentHashMap<String, CompletableDeferred<Result<JsonElement?>>>()
    private val nextId = AtomicLong()
    private val nextAttachmentId = AtomicLong()
    private val notifications = Channel<RelayNotification>(Channel.UNLIMITED)
    @Volatile private var socket: WebSocket? = null
    private var loop: Job? = null
    @Volatile private var attachment: Attachment? = null

    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            val delivery = launch { for (notification in notifications) onNotification(macId, notification) }
            try {
                runLoop()
            } finally {
                delivery.cancel()
            }
        }
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
                if (socket === webSocket) {
                    socket = null
                    failPending("The connection to the computer dropped.")
                }
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
            scope.launch { onOpened(webSocket) }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleMessage(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            val message = if (code == 1008) "This phone was removed on the computer. Pair it again." else "The computer closed the connection."
            closed.complete(CloseReason(message, unauthorized = code == 1008, wasConnected = opened))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            closed.complete(CloseReason("The computer closed the connection.", wasConnected = opened))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (response?.code == 401) {
                closed.complete(CloseReason("This phone is no longer paired with the computer.", unauthorized = true))
                return
            }
            closed.complete(CloseReason(connectionFailureReason(t, opened), wasConnected = opened))
        }
    }

    private suspend fun onOpened(webSocket: WebSocket) {
        val hello = request("hello", buildJsonObject { put("notifications_after", notificationsAfter()) })
        if (socket !== webSocket) return
        hello.onSuccess { data ->
            val welcome = data?.let { RelayJson.decodeFromJsonElement(Welcome.serializer(), it) } ?: return
            _state.value = ConnectionState.Connected(welcome)
            attachment?.let { sendAttach(it) }
            onWelcome(macId, welcome)
        }.onFailure {
            _state.value = ConnectionState.Disconnected(it.message ?: "The computer rejected the connection.")
            webSocket.close(1000, "hello failed")
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
            "chat_session" -> {
                val session = RelayJson.decodeFromJsonElement(ChatSessionMessage.serializer(), message)
                if (session.surfaceId == attachment?.surfaceId) _chat.value = ChatModel.startSession(_chat.value, session.sessionId)
            }
            "chat" -> {
                val chat = RelayJson.decodeFromJsonElement(ChatMessage.serializer(), message)
                if (chat.surfaceId == attachment?.surfaceId) _chat.value = ChatModel.apply(_chat.value, chat)
            }
            "detached" -> {
                val detached = RelayJson.decodeFromJsonElement(DetachedMessage.serializer(), message)
                if (detached.surfaceId == attachment?.surfaceId) {
                    attachment = null
                    _screen.value = null
                    _chat.value = null
                }
                _detached.tryEmit(detached)
            }
            "notification" -> {
                notifications.trySend(RelayJson.decodeFromJsonElement(NotificationMessage.serializer(), message).notification)
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
            deferred.complete(Result.failure(RelayException(error?.code ?: "error", error?.message ?: "The computer could not do that.")))
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
            return Result.failure(RelayException("disconnected", "Not connected to the computer."))
        }
        val result = withTimeoutOrNull(REQUEST_TIMEOUT_MS) { deferred.await() }
        if (result != null) return result
        pending.remove(id)
        return Result.failure(RelayException("timeout", "The computer took too long to answer."))
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

    fun attach(workspaceId: String, surfaceId: String, fit: Pair<Int, Int>? = null): Long {
        val next = Attachment(nextAttachmentId.incrementAndGet(), workspaceId, surfaceId, fit)
        if (attachment?.surfaceId != surfaceId) {
            _screen.value = null
            _chat.value = null
        }
        attachment = next
        sendAttach(next)
        return next.id
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

    fun detach(attachmentId: Long) {
        if (attachment?.id != attachmentId) return
        attachment = null
        _screen.value = null
        _chat.value = null
        send("detach")
    }

    fun resync() = send("resync")

    suspend fun history(lines: Int = HISTORY_LINES): Result<HistoryResponse> =
        request("history", buildJsonObject { put("lines", lines) }).mapCatching { data ->
            data?.let { RelayJson.decodeFromJsonElement(HistoryResponse.serializer(), it) } ?: HistoryResponse()
        }

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
        const val HISTORY_LINES = 2_000
    }
}
