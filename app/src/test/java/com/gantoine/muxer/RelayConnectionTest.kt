package com.gantoine.muxer

import com.gantoine.muxer.net.ConnectionState
import com.gantoine.muxer.net.PairingClient
import com.gantoine.muxer.net.RelayConnection
import com.gantoine.muxer.protocol.RelayNotification
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class RelayConnectionTest {
    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val client = OkHttpClient()
    private val received = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
    }

    private fun fakeRelay() = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            received += text
            val message = Json.parseToJsonElement(text).jsonObject
            val id = message["id"]?.jsonPrimitive?.content
            when (message["type"]?.jsonPrimitive?.content) {
                "hello" -> {
                    webSocket.send(
                        """{"type":"result","id":"$id","ok":true,"data":{"mac_name":"Studio","relay_version":"1","protocol_version":1,"cmux_version":"0.65.1","device_id":"d1","notification_seq":5}}""",
                    )
                    webSocket.send("""{"type":"workspaces","workspaces":[{"id":"w1","title":"api","has_unread":true,"unread_count":2,"terminals":[{"id":"t1","title":"zsh","agent_state":"Running"}]}]}""")
                    webSocket.send("""{"type":"notification","notification":{"seq":7,"kind":"created","id":"n1","title":"Claude Code","body":"Waiting for input"}}""")
                }
                "attach" -> webSocket.send(
                    """{"type":"screen","workspace_id":"w1","surface_id":"t1","full":true,"columns":80,"rows":2,"foreground":"#FFFFFF","background":"#000000","styles":[{"id":0,"fg":"#FFFFFF","bg":"#000000"}],"changed":[{"row":0,"spans":[{"column":0,"width":2,"style":0,"text":"$ "}]}]}""",
                )
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, null)
        }
    }

    @Test
    fun connectsAttachesAndForwardsNotifications() = runBlocking {
        server.enqueue(MockResponse.Builder().webSocketUpgrade(fakeRelay()).build())
        val notifications = CopyOnWriteArrayList<RelayNotification>()
        val connection = RelayConnection(
            macId = "mac",
            baseUrl = server.url("/").toString().trimEnd('/'),
            token = "secret-token",
            client = client,
            scope = scope,
            notificationsAfter = { 3L },
            onNotification = { _, notification -> notifications += notification },
        )
        connection.start()

        val connected = withTimeout(5_000) { connection.state.first { it is ConnectionState.Connected } }
        assertEquals("0.65.1", (connected as ConnectionState.Connected).welcome.cmuxVersion)
        assertEquals("Bearer secret-token", server.takeRequest().headers["Authorization"])

        val workspaces = withTimeout(5_000) { connection.workspaces.first { it != null } }!!
        assertEquals("Running", workspaces.single().terminals.single().agentState)

        val attachment = connection.attach("w1", "t1")
        val screen = withTimeout(5_000) { connection.screen.first { it != null } }!!
        assertEquals("$", screen.text(0))

        withTimeout(5_000) { while (notifications.isEmpty()) kotlinx.coroutines.delay(10) }
        assertEquals("Waiting for input", notifications.single().body)
        assertTrue(received.first().contains("\"notifications_after\":3"))

        val reopened = connection.attach("w1", "t1")
        connection.detach(attachment)
        assertNotNull(connection.screen.value)
        connection.detach(reopened)
        assertNull(connection.screen.value)
        connection.stop()
    }

    private fun relayWithApproval(relaySocket: CompletableDeferred<WebSocket>) = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = Json.parseToJsonElement(text).jsonObject
            if (message["type"]?.jsonPrimitive?.content != "hello") return
            val id = message["id"]?.jsonPrimitive?.content
            webSocket.send(
                """{"type":"result","id":"$id","ok":true,"data":{"mac_name":"Studio","relay_version":"1","protocol_version":1,"cmux_version":"0.65.1","device_id":"d1","notification_seq":5}}""",
            )
            webSocket.send("""{"type":"approvals","approvals":[{"id":"r1","kind":"permission","workspace_id":"w1","surface_id":"t1","tool":"Bash"}]}""")
            relaySocket.complete(webSocket)
        }
    }

    private fun connection() = RelayConnection(
        macId = "mac",
        baseUrl = server.url("/").toString().trimEnd('/'),
        token = "secret-token",
        client = client,
        scope = scope,
        notificationsAfter = { 0L },
        onNotification = { _, _ -> },
    )

    @Test
    fun approvalsClearWhenTheConnectionDrops() = runBlocking {
        val relaySocket = CompletableDeferred<WebSocket>()
        server.enqueue(MockResponse.Builder().webSocketUpgrade(relayWithApproval(relaySocket)).build())
        val connection = connection()
        connection.start()
        withTimeout(5_000) { connection.approvals.first { !it.isNullOrEmpty() } }
        relaySocket.await().close(1001, "going away")
        withTimeout(5_000) { connection.state.first { it is ConnectionState.Disconnected } }
        assertNull(connection.approvals.value)
        connection.stop()
    }

    @Test
    fun suspendedAttachmentResumesWithItsFit() = runBlocking {
        server.enqueue(MockResponse.Builder().webSocketUpgrade(fakeRelay()).build())
        val connection = connection()
        connection.start()
        withTimeout(5_000) { connection.state.first { it is ConnectionState.Connected } }
        connection.attach("w1", "t1")
        connection.setFit(40 to 20)
        withTimeout(5_000) { connection.screen.first { it != null } }

        connection.suspendAttachment()
        assertNull(connection.screen.value)
        assertFalse(connection.isAttachedTo("t1"))
        assertEquals("t1", connection.attachedSurfaceId)

        connection.resumeAttachment()
        withTimeout(5_000) { connection.screen.first { it != null } }
        assertTrue(connection.isAttachedTo("t1"))
        val types = received.map { Json.parseToJsonElement(it).jsonObject["type"]?.jsonPrimitive?.content }
        assertEquals(listOf("hello", "attach", "fit", "detach", "attach"), types)
        assertTrue(received.last().contains("\"fit\":{\"columns\":40,\"rows\":20}"))
        connection.stop()
    }

    @Test
    fun revokedPhoneStopsRetrying() = runBlocking {
        server.enqueue(MockResponse.Builder().code(401).body("""{"error":{"code":"unauthorized"}}""").build())
        val connection = RelayConnection(
            macId = "mac",
            baseUrl = server.url("/").toString().trimEnd('/'),
            token = "revoked",
            client = client,
            scope = scope,
            notificationsAfter = { 0L },
            onNotification = { _, _ -> },
        )
        connection.start()
        withTimeout(5_000) { connection.state.first { it == ConnectionState.Unauthorized } }
        kotlinx.coroutines.delay(1_500)
        assertEquals(1, server.requestCount)
        connection.stop()
    }

    @Test
    fun relayAddressesMustUseHttpsExceptForLocalDevelopment() {
        assertEquals("https://mac.tail1234.ts.net:8765/", PairingClient.normalizeRelayUrl("mac.tail1234.ts.net:8765/v1/pair?x=1").toString())
        assertNull(PairingClient.normalizeRelayUrl("http://mac.tail1234.ts.net:8765"))
        assertEquals("http://10.0.2.2:18765/", PairingClient.normalizeRelayUrl("http://10.0.2.2:18765").toString())
        assertNull(PairingClient.normalizeRelayUrl("   "))
    }
}
