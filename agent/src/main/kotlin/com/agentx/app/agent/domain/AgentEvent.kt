package com.agentx.app.agent.domain

import com.agentx.app.model.json.JsonObject

sealed interface AgentEvent {
    val sessionId: String
    val timestampMillis: Long

    data class SessionCreated(
        override val sessionId: String,
        val role: AgentRole,
        val parentSessionId: String?,
        override val timestampMillis: Long,
    ) : AgentEvent

    /**
     * The model an execution actually resolved to, emitted once per resolution
     * so a substitution can never be hidden from the event stream.
     *
     * It carries the complete, credential-free identity: the role, the provider
     * family/protocol, the selected model, the saved connection it belongs to,
     * and whether the selection came from an explicit role assignment
     * (`explicit = true`) or from the role/active-model selection policy. It never
     * carries an endpoint, credential or request body.
     */
    data class ModelSelected(
        override val sessionId: String,
        val role: AgentRole,
        val providerId: String,
        val modelId: String,
        val connectionId: String,
        val explicit: Boolean,
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

    /**
     * The model asked for a tool. Emitted as soon as a structured tool call is
     * parsed, before it is validated, authorized, or routed; [arguments] are the
     * raw model arguments so the UI can show what was requested.
     */
    data class ToolRequested(
        override val sessionId: String,
        val toolCallId: String,
        val toolName: String,
        val role: AgentRole,
        val arguments: JsonObject = emptyMap(),
        override val timestampMillis: Long,
    ) : AgentEvent

    data class ToolCallStarted(
        override val sessionId: String,
        val toolName: String,
        val role: AgentRole,
        override val timestampMillis: Long,
    ) : AgentEvent

    /** A resolved, authorized tool call is about to execute. */
    data class ToolProgress(
        override val sessionId: String,
        val toolCallId: String,
        val toolName: String,
        val detail: String,
        override val timestampMillis: Long,
    ) : AgentEvent

    data class ToolCallFinished(
        override val sessionId: String,
        val toolName: String,
        val success: Boolean,
        val summary: String,
        override val timestampMillis: Long,
    ) : AgentEvent

    /** A tool call ended without completing because the run was cancelled. */
    data class ToolCancelled(
        override val sessionId: String,
        val toolCallId: String,
        val toolName: String,
        val reason: String,
        override val timestampMillis: Long,
    ) : AgentEvent

    data class OutputDelta(
        override val sessionId: String,
        val text: String,
        override val timestampMillis: Long,
    ) : AgentEvent

    /** A tool call is parked until the user decides; the run is paused. */
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

    /**
     * A temporary primary-model failure triggered a controlled fallback to a
     * configured candidate. Operational only: provider/model identifiers, the
     * role and the reason. It never carries a prompt or a request body.
     */
    data class ModelFallbackStarted(
        override val sessionId: String,
        val role: AgentRole,
        val fromProviderId: String,
        val fromModelId: String,
        /** Connection identity of the model being left, so two same-family connections stay distinct. */
        val fromConnectionId: String? = null,
        val toProviderId: String,
        val toModelId: String,
        /** Connection identity of the fallback candidate. */
        val toConnectionId: String? = null,
        val reason: ModelFallbackReason,
        /** 1-based fallback attempt; the primary attempt is never counted. */
        val attempt: Int,
        override val timestampMillis: Long,
    ) : AgentEvent

    /** The fallback candidate produced a usable response. */
    data class ModelFallbackSucceeded(
        override val sessionId: String,
        val role: AgentRole,
        val fromProviderId: String,
        val fromModelId: String,
        val fromConnectionId: String? = null,
        val toProviderId: String,
        val toModelId: String,
        val toConnectionId: String? = null,
        val attempts: Int,
        override val timestampMillis: Long,
    ) : AgentEvent

    /** Every configured, eligible fallback candidate failed; the original error stands. */
    data class ModelFallbackExhausted(
        override val sessionId: String,
        val role: AgentRole,
        val providerId: String,
        val modelId: String,
        val attempts: Int,
        val reason: ModelFallbackReason,
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
