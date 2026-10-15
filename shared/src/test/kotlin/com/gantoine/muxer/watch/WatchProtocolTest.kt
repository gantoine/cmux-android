package com.gantoine.muxer.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchProtocolTest {
    private val permission = approval(kind = WatchProtocol.KIND_PERMISSION)

    @Test
    fun summaryRoundTrips() {
        val summary = WatchSummary(
            computers = listOf(WatchComputer("mac-1", "Ada's MacBook", connected = true)),
            approvals = listOf(permission, approval(kind = "question", options = listOf("Yes", "No"))),
            working = listOf(WatchAgent("mac-1", "Codex", "api")),
            updatedAt = 1_700_000_000_000,
        )

        assertEquals(summary, WatchProtocol.decodeSummary(WatchProtocol.encodeSummary(summary)))
    }

    @Test
    fun unreadableSummaryIsNull() {
        assertNull(WatchProtocol.decodeSummary("not json"))
        assertNull(WatchProtocol.decodeSummary(null))
    }

    @Test
    fun summaryIgnoresUnknownFields() {
        val decoded = WatchProtocol.decodeSummary("""{"updatedAt":5,"future":true}""")

        assertEquals(WatchSummary(updatedAt = 5), decoded)
    }

    @Test
    fun permissionChoicesAllowAlwaysAndDeny() {
        val choices = WatchProtocol.choices(permission)

        assertEquals(listOf("Allow", "Always", "Deny"), choices.map { it.label })
        assertEquals(listOf("once", "always", "deny"), choices.map { it.mode })
        assertTrue(choices.none { it.plan })
    }

    @Test
    fun planChoicesAreDecisions() {
        val choices = WatchProtocol.choices(approval(kind = WatchProtocol.KIND_PLAN))

        assertEquals(listOf("Approve", "Auto-edit", "Reject"), choices.map { it.label })
        assertEquals(listOf("manual", "autoAccept", "deny"), choices.map { it.mode })
        assertTrue(choices.all { it.plan })
    }

    @Test
    fun questionChoicesAnswerWithTheOptionLabel() {
        val choices = WatchProtocol.choices(approval(kind = "question", options = listOf("Red", "Blue")))

        assertEquals(listOf(WatchChoice("Red", answer = "Red"), WatchChoice("Blue", answer = "Blue")), choices)
    }

    @Test
    fun questionWithoutOptionsHasNoChoices() {
        assertTrue(WatchProtocol.choices(approval(kind = "question")).isEmpty())
    }

    @Test
    fun answerCarriesTheTargetAndRoundTrips() {
        val answer = WatchProtocol.answer(permission, WatchProtocol.choices(permission).first())

        assertEquals(WatchAnswer("mac-1", "a-1", "ws-1", "sf-1", mode = "once"), answer)
        assertEquals(answer, WatchProtocol.decodeAnswer(WatchProtocol.encodeAnswer(answer)))
    }

    @Test
    fun resultRoundTripsAndUnreadableRepliesFail() {
        val failure = WatchAnswerResult(ok = false, message = "This computer is not paired anymore.")

        assertEquals(failure, WatchProtocol.decodeResult(WatchProtocol.encodeResult(failure)))
        assertEquals(WatchAnswerResult(ok = true), WatchProtocol.decodeResult(WatchProtocol.encodeResult(WatchAnswerResult(ok = true))))
        assertFalse(WatchProtocol.decodeResult("{".encodeToByteArray()).ok)
        assertFalse(WatchProtocol.decodeResult(null).ok)
    }

    private fun approval(kind: String, options: List<String> = emptyList()) = WatchApproval(
        id = "a-1",
        macId = "mac-1",
        workspaceId = "ws-1",
        surfaceId = "sf-1",
        kind = kind,
        agent = "Claude",
        workspace = "muxer",
        title = "Claude wants to use Bash",
        summary = "ls",
        options = options,
    )
}
