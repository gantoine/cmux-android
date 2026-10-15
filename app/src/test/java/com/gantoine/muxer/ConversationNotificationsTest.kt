package com.gantoine.muxer

import com.gantoine.muxer.net.AgentReplyRoute
import com.gantoine.muxer.net.RelayException
import com.gantoine.muxer.notifications.ConversationHistory
import com.gantoine.muxer.notifications.ConversationMessage
import com.gantoine.muxer.notifications.NotificationActions
import com.gantoine.muxer.notifications.NotificationFilter
import com.gantoine.muxer.notifications.Notifier
import com.gantoine.muxer.notifications.Sender
import com.gantoine.muxer.protocol.ApprovalOption
import com.gantoine.muxer.protocol.ApprovalQuestion
import com.gantoine.muxer.protocol.RelayNotification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationNotificationsTest {
    private fun agent(id: String) = ConversationMessage(id, "text $id", Sender.Agent, 0)

    @Test
    fun keepsTheLatestTenMessagesPerConversation() {
        val history = ConversationHistory()
        repeat(12) { history.add("mac:s1", agent("n$it")) }
        history.add("mac:s2", agent("other"))
        val kept = history.messages("mac:s1")
        assertEquals(ConversationHistory.DEFAULT_LIMIT, kept.size)
        assertEquals("n2", kept.first().id)
        assertEquals("n11", kept.last().id)
        assertEquals(1, history.messages("mac:s2").size)
    }

    @Test
    fun replacesAMessageWithTheSameId() {
        val history = ConversationHistory()
        history.add("c", agent("n1"))
        history.add("c", agent("n2"))
        history.add("c", agent("n1").copy(text = "updated"))
        assertEquals(listOf("n2", "n1"), history.messages("c").map { it.id })
        assertEquals("updated", history.messages("c").last().text)
    }

    @Test
    fun removingAMessageReportsWhatRemains() {
        val history = ConversationHistory()
        history.add("c", agent("n1"))
        history.add("c", ConversationMessage("reply", "hi", Sender.You, 1))
        val (conversation, remaining) = history.remove("n1")!!
        assertEquals("c", conversation)
        assertEquals(listOf("reply"), remaining.map { it.id })
        assertFalse(ConversationHistory.hasAgentMessages(remaining))
        assertNull(history.remove("unknown"))
    }

    @Test
    fun restoresOnlyAnEmptyConversation() {
        val history = ConversationHistory(limit = 2)
        history.restore("c", listOf(agent("a"), agent("b"), agent("c")))
        assertEquals(listOf("b", "c"), history.messages("c").map { it.id })
        history.restore("c", listOf(agent("x")))
        assertEquals(listOf("b", "c"), history.messages("c").map { it.id })
        history.clear("c")
        assertTrue(history.messages("c").isEmpty())
    }

    @Test
    fun routesNotificationsToChannels() {
        fun notification(subtitle: String?, workspace: String? = "w", surface: String? = "s") =
            RelayNotification(seq = 1, kind = "created", id = "n", title = "Claude Code", subtitle = subtitle, body = "Body", workspaceId = workspace, surfaceId = surface)

        assertEquals(Notifier.COMPLETIONS_CHANNEL, NotificationFilter.channel(notification("Completed in muxer")))
        assertEquals(Notifier.ALERTS_CHANNEL, NotificationFilter.channel(notification("Permission")))
        assertEquals(Notifier.ALERTS_CHANNEL, NotificationFilter.channel(notification(null)))
        assertTrue(NotificationFilter.isConversation(notification("Completed")))
        assertFalse(NotificationFilter.isConversation(notification("Completed", workspace = null)))
        assertFalse(NotificationFilter.isConversation(notification("Completed", surface = "")))
    }

    @Test
    fun leavesRoomForATypedAnswer() {
        fun question(vararg labels: String, multiSelect: Boolean = false) =
            ApprovalQuestion(prompt = "Which?", multiSelect = multiSelect, options = labels.map { ApprovalOption(it) })

        assertEquals(listOf("Yes", "No"), NotificationActions.oneTapAnswers(question("Yes", "No")))
        assertEquals(emptyList<String>(), NotificationActions.oneTapAnswers(question("A", "B", "C")))
        assertEquals(emptyList<String>(), NotificationActions.oneTapAnswers(question("A", "B", multiSelect = true)))
        assertEquals(emptyList<String>(), NotificationActions.oneTapAnswers(question()))
    }

    @Test
    fun repliesUseTheSharedConnectionOnlyWhenItShowsThatTerminal() {
        assertEquals(AgentReplyRoute.Shared, AgentReplyRoute.choose(sharedConnected = true, sharedAttachedToTarget = true))
        assertEquals(AgentReplyRoute.Separate, AgentReplyRoute.choose(sharedConnected = true, sharedAttachedToTarget = false))
        assertEquals(AgentReplyRoute.Separate, AgentReplyRoute.choose(sharedConnected = false, sharedAttachedToTarget = true))
    }

    @Test
    fun pastesOnlyWhenTheRelayCannotMessageAgents() {
        assertTrue(AgentReplyRoute.shouldPasteInstead(RelayException("unsupported", "no")))
        assertTrue(AgentReplyRoute.shouldPasteInstead(RelayException("unknown_type", "no")))
        assertFalse(AgentReplyRoute.shouldPasteInstead(RelayException("not_attached", "no")))
        assertFalse(AgentReplyRoute.shouldPasteInstead(IllegalStateException("no")))
    }
}
