package com.gantoine.muxer

import android.Manifest
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

object RecordedCmuxScenario {
    const val MAC_NAME = "Replay Mac"
    const val WORKSPACE = "Payments API"
    const val OTHER_WORKSPACE = "Docs site"
    const val OUTPUT = "Payments API tests passed"
    const val DONE_ITEM = "Write the migration"
    const val OPEN_ITEM = "Ship the release"
    const val NOTIFICATION = "Build finished"
    const val NOTIFICATION_BODY = "Docs site deployed"
    const val COMMAND = "npm test"
}

@RunWith(AndroidJUnit4::class)
class RecordedCmuxTest {
    @get:Rule
    val permissions: TestRule =
        if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain()

    private val arguments = InstrumentationRegistry.getArguments()
    private val relayUrl = arguments.getString("relayUrl") ?: "http://10.0.2.2:8771"
    private val controlUrl = arguments.getString("controlUrl") ?: "http://10.0.2.2:8790"
    private val token = arguments.getString("relayToken") ?: error("Pass the relay token with -e relayToken")
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val app: MuxerApplication get() = ApplicationProvider.getApplicationContext()
    private lateinit var activity: ActivityScenario<MainActivity>

    @Before
    fun pairWithTheRecordedRelay() = runBlocking {
        app.store.current().forEach { app.store.remove(it.id) }
        app.store.add("replay", RecordedCmuxScenario.MAC_NAME, relayUrl, token, 0)
        activity = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun close() {
        activity.close()
        device.pressHome()
    }

    @Test
    fun workspacesFromCmuxAreListed() {
        openMac()
        await(By.text(RecordedCmuxScenario.OTHER_WORKSPACE))
    }

    @Test
    fun theTerminalShowsWhatCmuxDrew() {
        openMac()
        await(By.text(RecordedCmuxScenario.WORKSPACE)).click()
        await(By.descContains(RecordedCmuxScenario.OUTPUT))
    }

    @Test
    fun aPermissionRequestIsAnsweredFromTheTerminal() {
        openMac()
        await(By.text(RecordedCmuxScenario.WORKSPACE)).click()
        await(By.descContains(RecordedCmuxScenario.OUTPUT))
        emit("agent.hook.PermissionRequest")
        await(By.textContains(RecordedCmuxScenario.COMMAND))
        await(By.text("Allow")).click()
        awaitCall("feed.permission.reply") { params -> params["mode"]?.jsonPrimitive?.content == "once" }
    }

    @Test
    fun theChecklistComesFromCmux() {
        openMac()
        await(By.text(RecordedCmuxScenario.WORKSPACE)).longClick()
        await(By.textStartsWith("Checklist")).click()
        await(By.text(RecordedCmuxScenario.DONE_ITEM))
        await(By.text(RecordedCmuxScenario.OPEN_ITEM))
    }

    @Test
    fun cmuxNotificationsAreListed() {
        openMac()
        await(By.descStartsWith("Notifications from")).click()
        await(By.text(RecordedCmuxScenario.NOTIFICATION))
        await(By.text(RecordedCmuxScenario.NOTIFICATION_BODY))
    }

    @Test
    fun aNewCmuxNotificationReachesTheShade() {
        openMac()
        await(By.text(RecordedCmuxScenario.OTHER_WORKSPACE))
        emit("notification.created")
        device.openNotification()
        val shown = device.wait(Until.hasObject(By.textContains(RecordedCmuxScenario.NOTIFICATION)), TIMEOUT_MS)
        device.pressBack()
        assertTrue("the notification never reached the shade", shown == true)
    }

    @Test
    fun metricsDescribeTheRecordedWorkspaces() {
        openMac()
        await(By.descStartsWith("Settings for")).click()
        await(By.text("Metrics")).click()
        await(By.text(RecordedCmuxScenario.WORKSPACE))
    }

    @Test
    fun keepAwakeIsSentToCmux() {
        openMac()
        await(By.descStartsWith("Settings for")).click()
        await(By.text("Keep this computer awake")).click()
        awaitCall("caffeine.set") { params -> params["enabled"]?.jsonPrimitive?.content == "true" }
    }

    private fun openMac() {
        await(By.text(RecordedCmuxScenario.MAC_NAME)).click()
        await(By.text(RecordedCmuxScenario.WORKSPACE))
    }

    private fun await(selector: BySelector): UiObject2 {
        val found = device.wait(Until.findObject(selector), TIMEOUT_MS)
        assertNotNull("nothing on screen matches $selector", found)
        return found
    }

    private fun emit(event: String) {
        val emitted = http("POST", "$controlUrl/emit?name=$event")
        assertTrue("the recording has no $event: $emitted", !emitted.contains("\"emitted\":0"))
    }

    private fun awaitCall(method: String, matches: (Map<String, JsonElement>) -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val calls = Json.parseToJsonElement(http("GET", "$controlUrl/calls?method=$method")) as JsonArray
            if (calls.any { call -> call.jsonObject["params"]?.jsonObject?.let(matches) == true }) return
            Thread.sleep(250)
        }
        throw AssertionError("cmux never received $method")
    }

    private fun http(method: String, url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        return try {
            connection.inputStream.bufferedReader().readText()
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 20_000L
    }
}
