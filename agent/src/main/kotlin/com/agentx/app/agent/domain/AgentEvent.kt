package com.agentx.app.agent.domain

sealed interface AgentEvent {
    val sessionId: String
    val timestampMillis: Long

    data class SessionCreated(
        override val sessionId: String,
        val role: AgentRole,
        val parentSessionId: String?,
        override val timestampMillis: Long,
    ) : AgentEvent

    data class Thinking(
        override val sessionId: String,
        val role: AgentRole,
        val detail: String? = null,
        override val timestampMillis: Long,
    ) : AgentEvent

    data class PlanUpdated(
        override val sessionId: String,
        val plan: AgentPlan,
        override val timestampMillis: Long,
    ) : AgentEvent

    data class StepProgress(
        override val sessionId: String,
        val step: AgentStep,
        override val timestampMillis: Long,
    ) : AgentEvent

    data class SubAgentStarted(
        override val sessionId: String,
        val parentSessionId: String,
        val role: AgentRole,
        val objective: String,
        override val timestampMillis: Long,
    ) : AgentEvent

    data class SubAgentCompleted(
        override val sessionId: String,
        val parentSessionId: String,
        val role: AgentRole,
        val status: AgentStatus,
        val summary: String,
        override val timestampMillis: Long,
    ) : AgentEvent

    data class ToolCallStarted(
        override val sessionId: String,
        val toolName: String,
        val role: AgentRole,
        override val timestampMillis: Long,
    ) : AgentEvent

    data class ToolCallFinished(
        override val sessionId: String,
        val toolName: String,
        val success: Boolean,
        val summary: String,
        override val timestampMillis: Long,
    ) : AgentEvent

    data class OutputDelta(
        override val sessionId: String,
        val text: String,
        override val timestampMillis: Long,
    ) : AgentEvent

    data class PermissionRequested(
        override val sessionId: String,
        val pending: PendingPermission,
        override val timestampMillis: Long,
    ) : AgentEvent

    data class PermissionResolved(
        override val sessionId: String,
        val toolName: String,
        val approved: Boolean,
        override val timestampMillis: Long,
    ) : AgentEvent

    data class StatsUpdated(
        override val sessionId: String,
        val stats: AgentStepStats,
        override val timestampMillis: Long,
    ) : AgentEvent

    data class ContextTruncated(
        override val sessionId: String,
        val removedMessages: Int,
        override val timestampMillis: Long,
    ) : AgentEvent

    data class Completed(
        override val sessionId: String,
        val result: AgentResult,
        override val timestampMillis: Long,
    ) : AgentEvent

    data class Cancelled(
        override val sessionId: String,
        val reason: String,
        override val timestampMillis: Long,
    ) : AgentEvent

    data class Failed(
        override val sessionId: String,
        val error: AgentError,
        override val timestampMillis: Long,
    ) : AgentEvent
}

fun interface AgentEventSink {
    fun emit(event: AgentEvent)
}

class CollectingEventSink : AgentEventSink {
    private val items = mutableListOf<AgentEvent>()

    val events: List<AgentEvent> get() = synchronized(items) { items.toList() }

    override fun emit(event: AgentEvent) {
        synchronized(items) { items += event }
    }
}

class MultiplexingEventSink(private val sinks: List<AgentEventSink>) : AgentEventSink {
    override fun emit(event: AgentEvent) {
        sinks.forEach { it.emit(event) }
    }
}
