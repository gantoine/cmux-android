package dev.cmuxandroid

import dev.cmuxandroid.chat.ChatModel
import dev.cmuxandroid.chat.ChatState
import dev.cmuxandroid.protocol.ChatEntry
import dev.cmuxandroid.protocol.ChatMessage
import dev.cmuxandroid.protocol.ChatTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ChatModelTest {
    private val hello = ChatEntry("u1", "user", text = "hello")
    private val running = ChatEntry("t1", "tool", tool = ChatTool("Bash", "ls", "running"))

    @Test
    fun sessionsStartEmptyAndEndWithNull() {
        val started = ChatModel.startSession(null, "s1")
        assertEquals(ChatState("s1"), started)
        assertSame(started, ChatModel.startSession(started, "s1"))
        assertEquals(ChatState("s2"), ChatModel.startSession(started, "s2"))
        assertNull(ChatModel.startSession(started, null))
    }

    @Test
    fun resetReplacesAndUpdatesUpsertById() {
        val loaded = ChatModel.apply(ChatState("s1"), ChatMessage("t", "s1", reset = true, entries = listOf(hello, running)))!!
        assertEquals(listOf(hello, running), loaded.entries)
        val done = running.copy(tool = running.tool!!.copy(status = "done"))
        val reply = ChatEntry("a1", "assistant", text = "done")
        val updated = ChatModel.apply(loaded, ChatMessage("t", "s1", entries = listOf(done, reply)))!!
        assertEquals(listOf(hello, done, reply), updated.entries)
    }

    @Test
    fun messagesForAnotherSessionAreIgnored() {
        val state = ChatState("s1")
        assertSame(state, ChatModel.apply(state, ChatMessage("t", "old", reset = true, entries = listOf(hello))))
        assertNull(ChatModel.apply(null, ChatMessage("t", "s1", entries = listOf(hello))))
    }
}
