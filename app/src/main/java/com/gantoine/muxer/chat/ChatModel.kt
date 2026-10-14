package com.gantoine.muxer.chat

import com.gantoine.muxer.protocol.ChatEntry
import com.gantoine.muxer.protocol.ChatMessage

data class ChatState(val sessionId: String, val entries: List<ChatEntry> = emptyList(), val loaded: Boolean = false)

object ChatModel {
    fun startSession(previous: ChatState?, sessionId: String?): ChatState? = when {
        sessionId == null -> null
        previous?.sessionId == sessionId -> previous
        else -> ChatState(sessionId)
    }

    fun apply(previous: ChatState?, message: ChatMessage): ChatState? {
        if (previous == null || previous.sessionId != message.sessionId) return previous
        if (message.reset) return previous.copy(entries = message.entries, loaded = true)
        val entries = previous.entries.toMutableList()
        val positions = entries.withIndex().associate { (index, entry) -> entry.id to index }
        for (entry in message.entries) {
            val position = positions[entry.id]
            if (position != null) entries[position] = entry else entries.add(entry)
        }
        return previous.copy(entries = entries, loaded = true)
    }
}
