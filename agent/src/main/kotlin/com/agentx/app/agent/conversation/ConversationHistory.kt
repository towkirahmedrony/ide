package com.agentx.app.agent.conversation

import com.agentx.app.agent.domain.AgentResult
import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.agent.domain.AgentSession
import com.agentx.app.agent.domain.AgentStatus
import com.agentx.app.agent.domain.AgentTask
import com.agentx.app.agent.orchestrator.AgentSessionStore
import com.agentx.app.agent.orchestrator.InMemoryAgentSessionStore
import com.agentx.app.agent.orchestrator.withStatus
import com.agentx.app.context.ContextBudget
import com.agentx.app.model.ModelMessage
import java.util.UUID

/**
 * Session-scoped conversation history. Each [AgentSession] owns its own
 * transcript, summary and task state. Histories never mix.
 */
class ConversationHistory(
    private val conversations: ConversationStore = InMemoryConversationStore(),
    private val sessions: AgentSessionStore = InMemoryAgentSessionStore(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val ids: () -> String = { UUID.randomUUID().toString() },
) {

    fun createSession(
        workspaceId: String?,
        role: AgentRole = AgentRole.MAIN,
        parentSessionId: String? = null,
        title: String? = null,
        modelProviderId: String? = null,
        modelId: String? = null,
        sessionId: String? = null,
        prompt: String = "",
    ): AgentConversation {
        val now = clock()
        val id = sessionId?.takeIf { it.isNotBlank() } ?: ids()
        val existing = conversations.find(id)
        if (existing != null) return existing
        val session = AgentSession(
            id = id,
            parentSessionId = parentSessionId,
            role = role,
            status = AgentStatus.IDLE,
            task = AgentTask(id = ids(), prompt = prompt, workspaceId = workspaceId),
            createdAtMillis = now,
            updatedAtMillis = now,
            workspaceId = workspaceId,
            title = title?.takeIf { it.isNotBlank() } ?: SessionTitle.DEFAULT,
            modelProviderId = modelProviderId,
            modelId = modelId,
        )
        val conversation = AgentConversation(session = session)
        persist(conversation)
        return conversation
    }

    fun conversation(sessionId: String): AgentConversation? = conversations.find(sessionId)

    fun conversations(workspaceId: String? = null): List<AgentConversation> {
        val all = if (workspaceId == null) {
            conversations.all()
        } else {
            conversations.findByWorkspace(workspaceId)
        }
        return all
            .filter { it.session.parentSessionId == null }
            .sortedByDescending { it.session.updatedAtMillis }
    }

    fun open(sessionId: String): AgentConversation? = conversations.find(sessionId)

    fun rename(sessionId: String, title: String): AgentConversation? {
        val current = conversations.find(sessionId) ?: return null
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return current
        val updated = current.copy(
            session = current.session.copy(title = trimmed, updatedAtMillis = clock()),
        )
        persist(updated)
        return updated
    }

    fun delete(sessionId: String): Boolean {
        val target = conversations.find(sessionId) ?: return false
        val children = conversations.all().filter { it.session.parentSessionId == sessionId }
        children.forEach { conversations.delete(it.id) }
        conversations.delete(sessionId)
        sessions.delete(sessionId)
        children.forEach { sessions.delete(it.id) }
        return target.session.parentSessionId == null || true
    }

    fun clearHistory(sessionId: String): AgentConversation? {
        val current = conversations.find(sessionId) ?: return null
        val now = clock()
        val updated = current.copy(
            messages = emptyList(),
            summary = SessionSummary(),
            taskState = SessionTaskState(workspaceId = current.workspaceId),
            session = current.session.copy(
                status = AgentStatus.IDLE,
                updatedAtMillis = now,
                title = SessionTitle.DEFAULT,
                task = current.session.task.copy(prompt = ""),
            ),
        )
        persist(updated)
        return updated
    }

    fun ensureSession(
        sessionId: String?,
        workspaceId: String?,
        role: AgentRole = AgentRole.MAIN,
        parentSessionId: String? = null,
        modelProviderId: String? = null,
        modelId: String? = null,
        prompt: String = "",
    ): AgentConversation {
        if (!sessionId.isNullOrBlank()) {
            conversations.find(sessionId)?.let { existing ->
                return if (modelProviderId == null && modelId == null) {
                    existing
                } else {
                    val updated = existing.copy(
                        session = existing.session.copy(
                            modelProviderId = modelProviderId ?: existing.session.modelProviderId,
                            modelId = modelId ?: existing.session.modelId,
                            updatedAtMillis = clock(),
                        ),
                    )
                    persist(updated)
                    updated
                }
            }
        }
        return createSession(
            workspaceId = workspaceId,
            role = role,
            parentSessionId = parentSessionId,
            modelProviderId = modelProviderId,
            modelId = modelId,
            sessionId = sessionId,
            prompt = prompt,
        )
    }

    fun append(sessionId: String, message: ConversationMessage): AgentConversation? {
        val current = conversations.find(sessionId) ?: return null
        val redacted = ConversationSecrets.redact(message).copy(sessionId = sessionId)
        val withoutDuplicate = current.messages.filterNot { it.id == redacted.id }
        val messages = withoutDuplicate + redacted
        val session = maybeTitled(current.session, redacted)
        val updated = current.copy(
            session = session.copy(updatedAtMillis = clock()),
            messages = messages,
        )
        persist(updated)
        return updated
    }

    fun upsert(sessionId: String, message: ConversationMessage): AgentConversation? {
        val current = conversations.find(sessionId) ?: return null
        val redacted = ConversationSecrets.redact(message).copy(sessionId = sessionId)
        val messages = current.messages.toMutableList()
        val index = messages.indexOfFirst { it.id == redacted.id }
        if (index >= 0) {
            messages[index] = redacted
        } else {
            messages += redacted
        }
        val session = maybeTitled(current.session, redacted)
        val updated = current.copy(
            session = session.copy(updatedAtMillis = clock()),
            messages = messages,
        )
        persist(updated)
        return updated
    }

    fun recordUser(sessionId: String, text: String, messageId: String? = null): ConversationMessage {
        val now = clock()
        val message = ConversationMessage(
            id = messageId ?: ids(),
            sessionId = sessionId,
            role = MessageRole.USER,
            content = MessageContent(text = text),
            metadata = MessageMetadata(status = MessageStatus.COMPLETED, timestampMillis = now),
        )
        append(sessionId, message)
        return message
    }

    fun startAssistant(
        sessionId: String,
        messageId: String? = null,
        modelProviderId: String? = null,
        modelId: String? = null,
    ): ConversationMessage {
        val now = clock()
        val message = ConversationMessage(
            id = messageId ?: ids(),
            sessionId = sessionId,
            role = MessageRole.ASSISTANT,
            content = MessageContent(),
            metadata = MessageMetadata(
                status = MessageStatus.STARTED,
                timestampMillis = now,
                modelProviderId = modelProviderId,
                modelId = modelId,
            ),
        )
        append(sessionId, message)
        return message
    }

    fun streamAssistant(sessionId: String, messageId: String, delta: String): ConversationMessage? {
        val current = conversations.find(sessionId) ?: return null
        val existing = current.messages.firstOrNull { it.id == messageId } ?: return null
        val next = existing.copy(
            content = existing.content.copy(text = existing.content.text + delta),
            metadata = existing.metadata.copy(status = MessageStatus.STREAMING),
        )
        upsert(sessionId, next)
        return next
    }

    fun completeAssistant(
        sessionId: String,
        messageId: String,
        text: String,
        status: MessageStatus = MessageStatus.COMPLETED,
    ): ConversationMessage? {
        val current = conversations.find(sessionId) ?: return null
        val existing = current.messages.firstOrNull { it.id == messageId } ?: return null
        val next = existing.copy(
            content = existing.content.copy(text = text),
            metadata = existing.metadata.copy(status = status, timestampMillis = clock()),
        )
        upsert(sessionId, next)
        return next
    }

    fun failAssistant(sessionId: String, messageId: String, error: String, code: String? = null): ConversationMessage? {
        val current = conversations.find(sessionId) ?: return null
        val existing = current.messages.firstOrNull { it.id == messageId }
        val message = if (existing != null && existing.role == MessageRole.ASSISTANT) {
            existing.copy(
                role = MessageRole.ERROR,
                content = existing.content.copy(text = error, errorCode = code),
                metadata = existing.metadata.copy(status = MessageStatus.ERROR, timestampMillis = clock()),
            )
        } else {
            ConversationMessage(
                id = messageId,
                sessionId = sessionId,
                role = MessageRole.ERROR,
                content = MessageContent(text = error, errorCode = code),
                metadata = MessageMetadata(status = MessageStatus.ERROR, timestampMillis = clock()),
            )
        }
        upsert(sessionId, message)
        return message
    }

    fun recordTool(
        sessionId: String,
        toolName: String,
        arguments: String? = null,
        result: String? = null,
        success: Boolean? = null,
        toolCallId: String? = null,
        messageId: String? = null,
        status: MessageStatus = MessageStatus.COMPLETED,
    ): ConversationMessage {
        val now = clock()
        val text = buildString {
            append(toolName)
            arguments?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
            result?.takeIf { it.isNotBlank() }?.let { append('\n').append(it) }
        }
        val message = ConversationMessage(
            id = messageId ?: ids(),
            sessionId = sessionId,
            role = MessageRole.TOOL,
            content = MessageContent(
                text = text,
                toolName = toolName,
                toolArguments = arguments,
                toolResult = result,
                toolCallId = toolCallId,
            ),
            metadata = MessageMetadata(
                status = status,
                timestampMillis = now,
                toolSuccess = success,
            ),
        )
        append(sessionId, message)
        return message
    }

    fun recordSubAgent(
        sessionId: String,
        childSessionId: String,
        role: AgentRole,
        summary: String,
        success: Boolean,
    ): ConversationMessage {
        val now = clock()
        val message = ConversationMessage(
            id = ids(),
            sessionId = sessionId,
            role = MessageRole.SUB_AGENT,
            content = MessageContent(
                text = summary,
                subAgentRole = role.name,
                subAgentSessionId = childSessionId,
            ),
            metadata = MessageMetadata(
                status = if (success) MessageStatus.COMPLETED else MessageStatus.ERROR,
                timestampMillis = now,
                toolSuccess = success,
            ),
        )
        append(sessionId, message)
        return message
    }

    fun applyTurn(
        sessionId: String,
        userPrompt: String,
        result: AgentResult,
        assistantMessageId: String? = null,
    ): AgentConversation? {
        val current = conversations.find(sessionId) ?: return null
        val now = clock()
        val summary = SessionSummaryBuilder.update(current.summary, userPrompt, result, now)
        val taskState = SessionSummaryBuilder.taskState(current.taskState, userPrompt, result, current.workspaceId)
        val session = current.session.copy(
            status = result.status,
            plan = result.plan ?: current.session.plan,
            updatedAtMillis = now,
            task = current.session.task.copy(prompt = userPrompt),
        )
        assistantMessageId?.let { id ->
            val existing = current.messages.firstOrNull { it.id == id }
            if (existing != null && existing.metadata.status != MessageStatus.COMPLETED) {
                val status = if (result.status == AgentStatus.FAILED || result.status == AgentStatus.CANCELLED) {
                    MessageStatus.ERROR
                } else {
                    MessageStatus.COMPLETED
                }
                val text = if (status == MessageStatus.ERROR) {
                    result.errors.firstOrNull()?.message ?: result.summary
                } else {
                    result.summary.ifBlank { existing.content.text }
                }
                completeAssistant(sessionId, id, text, status)
            }
        }
        val latest = conversations.find(sessionId) ?: current
        val updated = latest.copy(session = session, summary = summary, taskState = taskState)
        persist(updated)
        return updated
    }

    fun markStatus(sessionId: String, status: AgentStatus): AgentConversation? {
        val current = conversations.find(sessionId) ?: return null
        val updated = current.copy(session = current.session.withStatus(status, clock()))
        persist(updated)
        return updated
    }

    fun modelMessages(sessionId: String, budget: ContextBudget = ContextBudget.DEFAULT): List<ModelMessage> {
        val conversation = conversations.find(sessionId) ?: return emptyList()
        return ConversationAssembler.modelConversation(conversation, budget)
    }

    private fun maybeTitled(session: AgentSession, message: ConversationMessage): AgentSession {
        if (message.role != MessageRole.USER) return session
        if (!SessionTitle.isPlaceholder(session.title.orEmpty())) return session
        val derived = SessionTitle.derive(message.content.text)
        return session.copy(title = derived)
    }

    private fun persist(conversation: AgentConversation) {
        conversations.save(conversation)
        sessions.save(conversation.session)
    }
}
