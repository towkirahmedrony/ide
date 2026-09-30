package com.agentx.app.agent.orchestrator

import com.agentx.app.agent.domain.AgentSession
import com.agentx.app.agent.domain.AgentStatus

interface AgentSessionStore {
    fun save(session: AgentSession)

    fun find(id: String): AgentSession?

    fun update(id: String, transform: (AgentSession) -> AgentSession): AgentSession?

    fun all(): List<AgentSession>

    fun delete(id: String): Boolean = false

    fun findByWorkspace(workspaceId: String?): List<AgentSession> =
        all().filter { session -> session.workspaceId == workspaceId && session.parentSessionId == null }
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

    @Synchronized
    override fun delete(id: String): Boolean = sessions.remove(id) != null
}

fun AgentSession.withStatus(status: AgentStatus, now: Long): AgentSession =
    copy(status = status, updatedAtMillis = now)

fun AgentSession.withTitle(title: String, now: Long): AgentSession =
    copy(title = title, updatedAtMillis = now)
