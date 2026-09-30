package com.agentx.app.agent.conversation

/**
 * Durable store for session-scoped conversations. Implementations must never
 * mix messages between session ids.
 */
interface ConversationStore {
    fun save(conversation: AgentConversation)

    fun find(sessionId: String): AgentConversation?

    fun all(): List<AgentConversation>

    fun delete(sessionId: String): Boolean

    fun findByWorkspace(workspaceId: String?): List<AgentConversation> =
        all().filter { conversation -> conversation.workspaceId == workspaceId }
}

class InMemoryConversationStore : ConversationStore {
    private val conversations = LinkedHashMap<String, AgentConversation>()

    @Synchronized
    override fun save(conversation: AgentConversation) {
        conversations[conversation.id] = conversation
    }

    @Synchronized
    override fun find(sessionId: String): AgentConversation? = conversations[sessionId]

    @Synchronized
    override fun all(): List<AgentConversation> = conversations.values.toList()

    @Synchronized
    override fun delete(sessionId: String): Boolean = conversations.remove(sessionId) != null
}
