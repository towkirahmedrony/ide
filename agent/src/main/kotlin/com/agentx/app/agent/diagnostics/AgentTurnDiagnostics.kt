package com.agentx.app.agent.diagnostics

import com.agentx.app.agent.domain.AgentError
import com.agentx.app.agent.domain.AgentEvent
import com.agentx.app.agent.domain.AgentResult
import com.agentx.app.agent.domain.AgentRunRequest
import com.agentx.app.agent.domain.AgentStatus
import com.agentx.app.core.logging.ForgeLogger
import com.agentx.app.model.ModelConfig
import com.agentx.app.model.json.JsonValue
import com.agentx.app.tools.SecretRedactor
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Explicit lifecycle states recorded for one Agent turn. The set is deliberately
 * small and stable so the Developer Log can be read as a state machine instead of
 * a wall of free-form text.
 */
enum class AgentTurnState {
    STARTED,
    REQUEST_PREPARED,
    REQUEST_STARTED,
    STREAMING,
    TOOL_EXECUTION,
    SUBAGENT,
    PERMISSION,
    COMPLETED,
    FAILED,
    CANCELLED,
    INTERRUPTED,
}

/**
 * Structured, correlation-scoped diagnostics for a single Agent turn.
 *
 * One instance is created per user message and handed the raw [AgentEvent] stream and
 * the final [AgentResult]. Every record it writes carries the same
 * [correlationId] and [sessionId], so a turn's input, request, streaming progress,
 * tool executions, delegations, permission decisions, outcome and any exception are
 * traceable back to the one message. State is instance-local, so concurrent turns
 * cannot mix their events.
 *
 * Design rules:
 *  - Never logs prompt text, request bodies, credentials, headers or model reasoning;
 *    tool arguments and results are routed through [SecretRedactor] and truncated.
 *  - Every write is wrapped, so a failing sink can never abort an agent run.
 *  - Streaming chunks are coalesced into lifecycle/progress events rather than logged
 *    one per token.
 *  - [finish] is idempotent, so overlapping terminal events cannot double-log.
 */
class AgentTurnDiagnostics(
    logger: ForgeLogger,
    val sessionId: String,
    val correlationId: String = newCorrelationId(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val streamProgressMillis: Long = STREAM_PROGRESS_MILLIS,
    private val streamProgressChars: Long = STREAM_PROGRESS_CHARS,
) {

    private val log = logger.child(
        mapOf(
            COMPONENT_FIELD to COMPONENT,
            "correlationId" to correlationId,
            "sessionId" to sessionId,
        ),
    )

    private val finished = AtomicBoolean(false)
    private val startedAt = clock()

    // Streaming aggregation: counts, not contents.
    private val streamChunks = AtomicLong(0)
    private val streamChars = AtomicLong(0)
    private var streamOpened = false
    private var lastStreamProgressAt = startedAt
    private var lastStreamProgressChars = 0L

    /** Tool call id -> start time, so a finished call reports a real duration. */
    private val toolStarted = HashMap<String, Long>()

    /** Child session id -> start time, so a delegation reports a real duration. */
    private val subAgentStarted = HashMap<String, Long>()

    // ───────────────────────────── Turn lifecycle ─────────────────────────────

    /** The user message was accepted and a turn began. [request] metadata is logged, its prompt is not. */
    fun started(request: AgentRunRequest, config: ModelConfig, resumed: Boolean = false) {
        emit(
            "agent.turn.started",
            AgentTurnState.STARTED,
            "Agent turn started",
            mapOf(
                "role" to "MAIN",
                "workspaceId" to request.workspaceId,
                "selectedFile" to request.selectedFile,
                "promptChars" to request.prompt.length,
                "mentionedFiles" to request.mentionedFiles.size,
                "attachments" to request.attachments.size,
                "skills" to (request.skillIds?.size ?: 0),
                "contextMessages" to request.conversation.size,
                "provider" to config.providerId,
                "model" to config.model,
                "connection" to config.connectionId,
                "resumed" to resumed,
            ),
        )
    }

    /**
     * The turn was rejected before it could reach the model provider (no model online,
     * an invalid configuration). Logged as a failure with the real reason, and
     * idempotent with [finish].
     */
    fun rejected(reason: String, config: ModelConfig) {
        if (!finished.compareAndSet(false, true)) return
        emit(
            "agent.turn.failed",
            AgentTurnState.FAILED,
            "Agent turn rejected before reaching the model provider",
            mapOf(
                "status" to AgentStatus.FAILED.name,
                "reason" to boundedRedacted(reason),
                "provider" to config.providerId,
                "model" to config.model,
                "connection" to config.connectionId,
            ),
            level = "ERROR",
        )
    }

    /** A turn resumed after a permission decision; a new phase of the same conversation. */
    fun resumed(approved: Boolean) {
        emit(
            "agent.turn.resumed",
            AgentTurnState.STARTED,
            if (approved) "Agent turn resumed after approval" else "Agent turn resumed after denial",
            mapOf("approved" to approved),
        )
    }

    /** The raw runtime event stream for this turn, in emission order. */
    fun onEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.ModelSelected -> emit(
                "agent.model.selected",
                AgentTurnState.REQUEST_STARTED,
                "Model selected for ${event.role.name}",
                mapOf(
                    "role" to event.role.name,
                    "provider" to event.providerId,
                    "model" to event.modelId,
                    // The connection the execution runs on, and the connection the role's
                    // saved assignment names (null when it names none). Logging both is
                    // what lets a stale assignment be told apart from a family-scoped one
                    // that followed a replacement connection. Identifiers only.
                    "connection" to event.connectionId,
                    "assignedConnection" to event.assignedConnectionId,
                    "selection" to if (event.explicit) "explicit" else "policy",
                ),
            )

            is AgentEvent.SessionCreated -> emit(
                "agent.session.created",
                AgentTurnState.STARTED,
                "Agent session created",
                mapOf("role" to event.role.name, "parentSessionId" to event.parentSessionId),
            )

            is AgentEvent.Thinking -> debug(
                "agent.thinking",
                AgentTurnState.REQUEST_STARTED,
                event.detail ?: "Reasoning",
                mapOf("role" to event.role.name),
            )

            is AgentEvent.ActivityChanged -> debug(
                "agent.activity",
                AgentTurnState.REQUEST_STARTED,
                "Activity ${event.activity.name}",
                mapOf("role" to event.role.name, "detail" to bound(event.detail)),
            )

            is AgentEvent.PlanUpdated -> debug(
                "agent.plan.updated",
                AgentTurnState.REQUEST_STARTED,
                "Plan updated",
                mapOf("steps" to event.plan.steps.size, "revision" to event.plan.revision),
            )

            is AgentEvent.StepProgress -> debug(
                "agent.step.progress",
                AgentTurnState.REQUEST_STARTED,
                "Step ${event.step.index} ${event.step.status.name}",
                mapOf("title" to bound(event.step.title)),
            )

            is AgentEvent.OutputDelta -> onStreamChunk(event.text)

            is AgentEvent.ToolRequested -> {
                toolStarted[event.toolCallId] = clock()
                emit(
                    "agent.tool.requested",
                    AgentTurnState.TOOL_EXECUTION,
                    "Tool requested: ${event.toolName}",
                    mapOf(
                        "toolName" to event.toolName,
                        "toolCallId" to event.toolCallId,
                        "role" to event.role.name,
                        "arguments" to describeArguments(event.arguments),
                    ),
                )
            }

            is AgentEvent.ToolCallStarted -> debug(
                "agent.tool.started",
                AgentTurnState.TOOL_EXECUTION,
                "Tool started: ${event.toolName}",
                mapOf("toolName" to event.toolName, "role" to event.role.name),
            )

            is AgentEvent.ToolProgress -> debug(
                "agent.tool.progress",
                AgentTurnState.TOOL_EXECUTION,
                "Tool progress: ${event.toolName}",
                mapOf("toolName" to event.toolName, "detail" to bound(event.detail)),
            )

            is AgentEvent.ToolCallFinished -> {
                val duration = toolStarted.remove(event.toolName)
                    ?: toolStarted.entries.firstOrNull { it.key.endsWith(event.toolName) }?.let { entry ->
                        toolStarted.remove(entry.key)
                    }
                emitToolFinished(event.toolName, event.success, event.summary, duration)
            }

            is AgentEvent.ToolCancelled -> {
                toolStarted.remove(event.toolCallId)
                emit(
                    "agent.tool.cancelled",
                    AgentTurnState.TOOL_EXECUTION,
                    "Tool cancelled: ${event.toolName}",
                    mapOf("toolName" to event.toolName, "reason" to bound(event.reason)),
                    level = "WARN",
                )
            }

            is AgentEvent.PermissionRequested -> emit(
                "agent.permission.requested",
                AgentTurnState.PERMISSION,
                "Permission requested for ${event.pending.toolName}",
                mapOf(
                    "toolName" to event.pending.toolName,
                    "toolCallId" to event.pending.toolCallId,
                    "requiredPermissions" to event.pending.requiredPermissions.joinToString(","),
                    "reason" to bound(event.pending.reason),
                ),
            )

            is AgentEvent.PermissionResolved -> emit(
                "agent.permission.resolved",
                AgentTurnState.PERMISSION,
                if (event.approved) {
                    "Permission approved for ${event.toolName}"
                } else {
                    "Permission denied for ${event.toolName}"
                },
                mapOf("toolName" to event.toolName, "approved" to event.approved),
                level = if (event.approved) "INFO" else "WARN",
            )

            is AgentEvent.SubAgentStarted -> {
                subAgentStarted[event.sessionId] = clock()
                emit(
                    "agent.subagent.started",
                    AgentTurnState.SUBAGENT,
                    "Sub-agent started: ${event.role.name}",
                    mapOf(
                        "role" to event.role.name,
                        "childSessionId" to event.sessionId,
                        "objective" to bound(event.objective),
                    ),
                )
            }

            is AgentEvent.SubAgentCompleted -> {
                val duration = subAgentStarted.remove(event.sessionId)
                emit(
                    "agent.subagent.completed",
                    AgentTurnState.SUBAGENT,
                    "Sub-agent ${event.status.name}: ${event.role.name}",
                    mapOf(
                        "role" to event.role.name,
                        "childSessionId" to event.sessionId,
                        "status" to event.status.name,
                        "success" to (event.status == AgentStatus.COMPLETED),
                        "durationMs" to duration?.let { clock() - it },
                        "summary" to boundedRedacted(event.summary),
                    ),
                    level = if (event.status == AgentStatus.COMPLETED) "INFO" else "WARN",
                )
            }

            is AgentEvent.ModelFallbackStarted -> emit(
                "agent.model.fallback",
                AgentTurnState.REQUEST_STARTED,
                "Primary model failed; trying fallback (attempt ${event.attempt})",
                mapOf(
                    "role" to event.role.name,
                    "fromProvider" to event.fromProviderId,
                    "fromModel" to event.fromModelId,
                    "toProvider" to event.toProviderId,
                    "toModel" to event.toModelId,
                    "reason" to event.reason.name,
                    "attempt" to event.attempt,
                ),
                level = "WARN",
            )

            is AgentEvent.ModelFallbackSucceeded -> emit(
                "agent.model.fallback.succeeded",
                AgentTurnState.REQUEST_STARTED,
                "Fallback model responded after primary failure",
                mapOf(
                    "fromProvider" to event.fromProviderId,
                    "fromModel" to event.fromModelId,
                    "toProvider" to event.toProviderId,
                    "toModel" to event.toModelId,
                    "attempts" to event.attempts,
                ),
            )

            is AgentEvent.ModelFallbackExhausted -> emit(
                "agent.model.fallback.exhausted",
                AgentTurnState.REQUEST_STARTED,
                "All configured fallback models failed",
                mapOf(
                    "provider" to event.providerId,
                    "model" to event.modelId,
                    "attempts" to event.attempts,
                    "reason" to event.reason.name,
                ),
                level = "WARN",
            )

            is AgentEvent.StatsUpdated -> debug(
                "agent.stats",
                AgentTurnState.REQUEST_STARTED,
                "Execution stats updated",
                mapOf(
                    "modelCalls" to event.stats.modelCalls,
                    "toolCalls" to event.stats.toolCalls,
                    "subAgentCalls" to event.stats.subAgentCalls,
                    "currentStep" to event.stats.currentStep,
                    "maxSteps" to event.stats.maxSteps,
                    "elapsedMillis" to event.stats.elapsedMillis,
                ),
            )

            is AgentEvent.ContextTruncated -> emit(
                "agent.context.truncated",
                AgentTurnState.REQUEST_PREPARED,
                "Context truncated to fit the model window",
                mapOf("removedMessages" to event.removedMessages),
                level = "WARN",
            )

            // Terminal outcomes are written once by [finish]; ignoring them here
            // keeps one turn from producing duplicate terminal records.
            is AgentEvent.Completed,
            is AgentEvent.Cancelled,
            is AgentEvent.Failed,
            -> Unit
        }
    }

    // ───────────────────────────── Outcome ─────────────────────────────

    /** Records the terminal state of the turn. Idempotent. */
    fun finish(result: AgentResult) {
        if (!finished.compareAndSet(false, true)) return
        closeStream()
        val duration = clock() - startedAt
        when (result.status) {
            AgentStatus.COMPLETED -> emit(
                "agent.turn.completed",
                AgentTurnState.COMPLETED,
                "Agent turn completed",
                outcomeFields(result, duration),
            )

            AgentStatus.FAILED, AgentStatus.MAX_STEPS_REACHED -> {
                val error = result.errors.firstOrNull()
                emit(
                    "agent.turn.failed",
                    AgentTurnState.FAILED,
                    "Agent turn failed",
                    outcomeFields(result, duration) + errorFields(error),
                    level = "ERROR",
                )
            }

            AgentStatus.CANCELLED -> emit(
                "agent.turn.cancelled",
                AgentTurnState.CANCELLED,
                "Agent turn cancelled",
                outcomeFields(result, duration),
                level = "WARN",
            )

            AgentStatus.WAITING_FOR_PERMISSION -> emit(
                "agent.turn.paused",
                AgentTurnState.PERMISSION,
                "Agent turn paused for permission",
                outcomeFields(result, duration) + mapOf(
                    "toolName" to result.pendingPermission?.toolName,
                ),
            )

            else -> emit(
                "agent.turn.ended",
                AgentTurnState.INTERRUPTED,
                "Agent turn ended without a terminal outcome",
                outcomeFields(result, duration),
                level = "WARN",
            )
        }
    }

    /**
     * Records a failure that escaped the run (an exception the runtime did not turn
     * into a result). The original exception is preserved: class, message and a
     * bounded stack trace. Idempotent with [finish]; whichever happens first wins.
     */
    fun fail(throwable: Throwable, stage: String) {
        if (!finished.compareAndSet(false, true)) return
        closeStream()
        emit(
            "agent.turn.failed",
            AgentTurnState.FAILED,
            "Agent turn failed before a result was produced",
            errorFields(throwable) + mapOf("stage" to stage, "durationMs" to (clock() - startedAt)),
            level = "ERROR",
        )
    }

    // ───────────────────────────── Internals ─────────────────────────────

    private fun onStreamChunk(text: String) {
        val chars = text.length.toLong()
        val now = clock()
        if (!streamOpened) {
            streamOpened = true
            lastStreamProgressAt = now
            emit(
                "agent.stream.started",
                AgentTurnState.STREAMING,
                "Assistant response streaming started",
                emptyMap(),
            )
        }
        val total = streamChars.addAndGet(chars)
        val chunks = streamChunks.incrementAndGet()
        // Coalesce: progress is emitted on a char or time threshold, never per token.
        if (total - lastStreamProgressChars >= streamProgressChars ||
            now - lastStreamProgressAt >= streamProgressMillis
        ) {
            lastStreamProgressChars = total
            lastStreamProgressAt = now
            debug(
                "agent.stream.progress",
                AgentTurnState.STREAMING,
                "Assistant response streaming",
                mapOf("chunks" to chunks, "chars" to total, "elapsedMillis" to (now - startedAt)),
            )
        }
    }

    private fun closeStream() {
        if (!streamOpened) return
        emit(
            "agent.stream.completed",
            AgentTurnState.STREAMING,
            "Assistant response streaming completed",
            mapOf(
                "chunks" to streamChunks.get(),
                "chars" to streamChars.get(),
                "durationMs" to (clock() - startedAt),
            ),
        )
    }

    private fun emitToolFinished(
        toolName: String,
        success: Boolean,
        summary: String,
        startedAtMillis: Long?,
    ) {
        emit(
            "agent.tool.finished",
            AgentTurnState.TOOL_EXECUTION,
            "Tool ${if (success) "completed" else "failed"}: $toolName",
            mapOf(
                "toolName" to toolName,
                "success" to success,
                "durationMs" to startedAtMillis?.let { clock() - it },
                "summary" to boundedRedacted(summary),
            ),
            level = if (success) "INFO" else "WARN",
        )
    }

    private fun outcomeFields(result: AgentResult, duration: Long): Map<String, Any?> = mapOf(
        "status" to result.status.name,
        "summary" to boundedRedacted(result.summary),
        "outputChars" to result.summary.length,
        "errors" to result.errors.size,
        "filesChanged" to result.filesChanged.size,
        "filesInspected" to result.filesInspected.size,
        "toolCalls" to result.stepStats.toolCalls,
        "modelCalls" to result.stepStats.modelCalls,
        "subAgentCalls" to result.stepStats.subAgentCalls,
        "durationMs" to duration,
    )

    private fun errorFields(error: AgentError?): Map<String, Any?> {
        if (error == null) return emptyMap()
        return mapOf(
            "errorCode" to error.code.name,
            "errorMessage" to boundedRedacted(error.message),
            "errorRole" to error.role?.name,
        ) + errorFields(error.cause)
    }

    private fun errorFields(throwable: Throwable?): Map<String, Any?> {
        if (throwable == null) return emptyMap()
        val stack = runCatching { throwable.stackTraceToString() }.getOrNull().orEmpty()
        return mapOf(
            "errorClass" to throwable.javaClass.name,
            "errorDetail" to boundedRedacted(throwable.message),
            "stackTrace" to stack.take(MAX_STACK_CHARS),
        )
    }

    private fun emit(
        event: String,
        state: AgentTurnState,
        message: String,
        fields: Map<String, Any?>,
        level: String = "INFO",
    ) {
        write(level, message, compact(fields) + mapOf("event" to event, "state" to state.name))
    }

    private fun debug(event: String, state: AgentTurnState, message: String, fields: Map<String, Any?>) {
        write("DEBUG", message, compact(fields) + mapOf("event" to event, "state" to state.name))
    }

    private fun write(level: String, message: String, fields: Map<String, Any?>) {
        // A logging failure must never break the run; the caller's outcome is unaffected.
        runCatching {
            val clean = fields.filterValues { it != null }
            when (level) {
                "ERROR" -> log.error(message, fields = clean)
                "WARN" -> log.warn(message, fields = clean)
                "DEBUG" -> log.debug(message, fields = clean)
                else -> log.info(message, fields = clean)
            }
        }
    }

    private fun compact(fields: Map<String, Any?>): Map<String, Any?> =
        fields.filterValues { it != null }

    private companion object {
        const val COMPONENT_FIELD = "component"
        const val COMPONENT = "agent"
        const val STREAM_PROGRESS_MILLIS = 1_000L
        const val STREAM_PROGRESS_CHARS = 2_000L
        const val MAX_SUMMARY_CHARS = 600
        const val MAX_STACK_CHARS = 2_000
        const val MAX_ARGUMENT_CHARS = 400

        fun newCorrelationId(): String = UUID.randomUUID().toString().replace("-", "").take(16)

        fun bound(text: String?, max: Int = MAX_SUMMARY_CHARS): String? {
            if (text == null) return null
            return if (text.length <= max) text else text.take(max) + "…"
        }

        fun boundedRedacted(text: String?, max: Int = MAX_SUMMARY_CHARS): String? {
            val value = text ?: return null
            return bound(SecretRedactor.redactText(value), max)
        }

        /**
         * Compact, redacted, bounded rendering of tool arguments for diagnostics.
         * A value whose key names a credential (an `authorization` header, a token, a
         * password…) is dropped entirely, and the rest is scrubbed for secrets; the
         * full argument body is never logged.
         */
        fun describeArguments(arguments: Map<String, JsonValue>): String {
            if (arguments.isEmpty()) return "(none)"
            val preview = arguments.entries.take(4).joinToString(", ") { (key, value) ->
                if (SecretRedactor.looksSecret(key)) {
                    "$key=${SecretRedactor.REDACTED}"
                } else {
                    val rendered = when (value) {
                        is JsonValue.Str -> value.value
                        is JsonValue.Num -> value.value.toString()
                        is JsonValue.Bool -> value.value.toString()
                        is JsonValue.Null -> "null"
                        is JsonValue.Arr -> "[${value.items.size} items]"
                        is JsonValue.Obj -> "{…}"
                    }
                    "$key=${SecretRedactor.redactText(rendered)}"
                }
            }
            val suffix = if (arguments.size > 4) ", …" else ""
            return bound(preview + suffix, MAX_ARGUMENT_CHARS).orEmpty()
        }
    }
}
