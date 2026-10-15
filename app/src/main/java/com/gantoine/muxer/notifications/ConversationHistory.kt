package com.gantoine.muxer.notifications

enum class Sender { Agent, You, App }

data class ConversationMessage(val id: String, val text: String, val sender: Sender, val time: Long)

data class ConversationTarget(val macId: String, val workspaceId: String, val surfaceId: String) {
    val key: String get() = "$macId:$surfaceId"
}

class ConversationHistory(private val limit: Int = DEFAULT_LIMIT) {
    private val conversations = mutableMapOf<String, List<ConversationMessage>>()

    @Synchronized
    fun messages(conversation: String): List<ConversationMessage> = conversations[conversation].orEmpty()

    @Synchronized
    fun add(conversation: String, message: ConversationMessage): List<ConversationMessage> {
        val updated = (conversations[conversation].orEmpty().filterNot { it.id == message.id } + message).takeLast(limit)
        conversations[conversation] = updated
        return updated
    }

    @Synchronized
    fun restore(conversation: String, messages: List<ConversationMessage>) {
        if (conversations[conversation].isNullOrEmpty() && messages.isNotEmpty()) conversations[conversation] = messages.takeLast(limit)
    }

    @Synchronized
    fun remove(messageId: String): Pair<String, List<ConversationMessage>>? {
        val conversation = conversations.entries.firstOrNull { entry -> entry.value.any { it.id == messageId } }?.key ?: return null
        val remaining = conversations[conversation].orEmpty().filterNot { it.id == messageId }
        conversations[conversation] = remaining
        return conversation to remaining
    }

    @Synchronized
    fun clear(conversation: String) {
        conversations.remove(conversation)
    }

    companion object {
        const val DEFAULT_LIMIT = 10

        fun hasAgentMessages(messages: List<ConversationMessage>): Boolean = messages.any { it.sender == Sender.Agent }
    }
}
