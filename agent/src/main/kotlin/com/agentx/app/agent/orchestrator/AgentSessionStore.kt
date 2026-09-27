package com.agentx.app.agent.orchestrator

import com.agentx.app.agent.domain.AgentSession
import com.agentx.app.agent.domain.AgentStatus

interface AgentSessionStore {
    fun save(session: AgentSession)

    fun find(id: String): AgentSession?

    fun update(id: String, transform: (AgentSession) -> AgentSession): AgentSession?

    fun all(): List<AgentSession>
}

class InMemoryAgentSessionStore : AgentSessionStore {
    private val sessions = LinkedHashMap<String, AgentSession>()

    @Synchronized
    override fun save(session: AgentSession) {
        sessions[session.id] = session
    }

    @Synchronized
    override fun find(id: String): AgentSession? = sessions[id]

    @Synchronized
    override fun update(id: String, transform: (AgentSession) -> AgentSession): AgentSession? {
        val current = sessions[id] ?: return null
        val next = transform(current)
        sessions[id] = next
        return next
    }

    @Synchronized
    override fun all(): List<AgentSession> = sessions.values.toList()
}

fun AgentSession.withStatus(status: AgentStatus, now: Long): AgentSession =
    copy(status = status, updatedAtMillis = now)
