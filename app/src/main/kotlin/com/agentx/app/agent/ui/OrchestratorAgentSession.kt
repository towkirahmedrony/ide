package com.agentx.app.agent.ui

import android.util.Log
import com.agentx.app.agent.domain.AgentError
import com.agentx.app.agent.domain.AgentErrorCode
import com.agentx.app.agent.domain.AgentEvent
import com.agentx.app.agent.domain.AgentEventSink
import com.agentx.app.agent.domain.AgentResult
import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.agent.domain.AgentRunRequest
import com.agentx.app.agent.domain.AgentStatus
import com.agentx.app.agent.domain.PendingPermission
import com.agentx.app.agent.orchestrator.AgentOrchestrator
import com.agentx.app.context.ContextBudget
import com.agentx.app.model.ModelConfig
import com.agentx.app.model.ModelMessage
import com.agentx.app.model.ModelProviderError
import com.agentx.app.model.ModelProviderErrorCode
import com.agentx.app.model.json.JsonObject
import com.agentx.app.model.json.JsonValue
import com.agentx.app.ui.ide.data.AgentFailureKind
import com.agentx.app.ui.ide.data.AgentSession
import com.agentx.app.ui.ide.data.AgentStreamEvent
import com.agentx.app.ui.ide.model.AgentActivity
import com.agentx.app.ui.ide.model.AgentActivityStatus

/**
 * Bridges the Agent Core orchestrator to the IDE [AgentSession] port.
 * UI stays free of model, tool, and orchestrator types.
 */
class OrchestratorAgentSession(
    private val orchestrator: AgentOrchestrator,
    private val modelConfig: () -> ModelConfig,
    private val workspaceId: String? = null,
) : AgentSession {

    /**
     * Compact record of previous turns, oldest first. It is handed to the
     * Context Engine on every run, which is what gives a follow-up request the
     * current task, earlier decisions, tool activity and progress. It is
     * bounded here and budgeted again by the engine.
     */
    private val conversation = mutableListOf<ModelMessage>()

    /** Same ceiling the Context Engine applies to conversation context. */
    private val conversationLimit = ContextBudget.DEFAULT.maxConversationMessages

    override suspend fun run(
        input: String,
        onEvent: (AgentStreamEvent) -> Unit,
        workspaceId: String?,
        selectedFile: String?,
    ) {
        val config = modelConfig()
        if (config.validate().isNotEmpty()) {
            onEvent(AgentStreamEvent.Failed(NO_MODEL_ONLINE, AgentFailureKind.NOT_CONFIGURED))
            return
        }

        onEvent(
            AgentStreamEvent.Activity(
                AgentActivity(AgentActivityStatus.THINKING, "AI responding"),
            ),
        )

        val activeWorkspaceId = workspaceId ?: this.workspaceId
        Log.d(
            TAG,
            "Agent run workspaceId=${activeWorkspaceId ?: "(none)"} " +
                "selectedFile=${selectedFile ?: "(none)"} promptChars=${input.length}",
        )

        val sink = AgentEventSink { event ->
            mapEvent(event)?.let(onEvent)
        }
        val result = orchestrator.run(
            request = AgentRunRequest(
                prompt = input,
                workspaceId = activeWorkspaceId,
                selectedFile = selectedFile,
                conversation = conversation.toList(),
            ),
            modelConfig = config,
            sink = sink,
        )
        record(input, result)
        emitOutcome(result, sessionId = null, onEvent = onEvent)
    }

    /**
     * Remembers this turn for the next one: the request, the outcome, and a
     * compact line of what the agent actually did with its tools. Nothing is
     * stored beyond the conversation budget, and nothing is persisted.
     */
    private fun record(input: String, result: AgentResult) {
        conversation += ModelMessage.user(input.trim())
        val tools = result.toolActions.joinToString("; ") { action ->
            "${action.toolName}${if (action.success) "" else " (failed)"}: ${action.summary}"
        }
        val body = buildString {
            append(result.summary.trim())
            if (tools.isNotBlank()) append("\n[tools: ").append(tools).append(']')
        }
        conversation += ModelMessage.assistant(body.take(MAX_ENTRY_CHARS))
        while (conversation.size > conversationLimit) conversation.removeAt(0)
    }

    override suspend fun resolvePermission(
        sessionId: String,
        approved: Boolean,
        onEvent: (AgentStreamEvent) -> Unit,
    ) {
        if (sessionId.isBlank()) {
            onEvent(AgentStreamEvent.Failed("No pending permission to resolve", AgentFailureKind.UNKNOWN))
            return
        }
        val sink = AgentEventSink { event -> mapEvent(event)?.let(onEvent) }
        val resumed = orchestrator.resumePermission(sessionId, approved, sink)
        if (resumed == null) {
            onEvent(AgentStreamEvent.Failed("The paused agent turn is no longer available", AgentFailureKind.UNKNOWN))
            return
        }
        emitOutcome(resumed, sessionId = sessionId, onEvent = onEvent)
    }

    /**
     * Maps a finished (or newly paused) run onto UI events. [sessionId] is only
     * needed to make a freshly parked permission resumable from the UI.
     */
    private fun emitOutcome(
        result: AgentResult,
        sessionId: String?,
        onEvent: (AgentStreamEvent) -> Unit,
    ) {
        when (result.status) {
            AgentStatus.COMPLETED ->
                onEvent(AgentStreamEvent.Completed(result.summary))
            AgentStatus.CANCELLED ->
                onEvent(AgentStreamEvent.Failed("Cancelled", AgentFailureKind.CANCELLED))
            AgentStatus.WAITING_FOR_PERMISSION -> {
                val pending = result.pendingPermission
                if (pending != null) {
                    onEvent(permissionEvent(pending, sessionId ?: result.sessionId))
                } else {
                    onEvent(
                        AgentStreamEvent.Activity(
                            AgentActivity(AgentActivityStatus.PERMISSION_REQUIRED, result.summary),
                        ),
                    )
                }
            }
            else -> {
                val error = result.errors.firstOrNull()
                val message = error?.message ?: result.summary
                val kind = failureKind(error)
                Log.e(TAG, "Agent turn failed kind=$kind code=${error?.code} provider=${providerCode(error)}", error?.cause)
                onEvent(AgentStreamEvent.Failed(message, kind))
            }
        }
    }

    private companion object {
        const val TAG = "ForgeAgent"
        const val NO_MODEL_ONLINE =
            "No model is online. Open Settings → Models, select a model and connect it first."

        /** Upper bound for one recorded turn; the engine trims the rest. */
        const val MAX_ENTRY_CHARS = 1_000
    }
}

internal fun permissionEvent(pending: PendingPermission, sessionId: String): AgentStreamEvent.PermissionRequired =
    AgentStreamEvent.PermissionRequired(
        toolName = pending.toolName,
        reason = pending.reason,
        sessionId = sessionId,
        toolCallId = pending.toolCallId,
        detail = describeToolArguments(pending.arguments),
        requiredPermission = pending.requiredPermissions.joinToString(", "),
    )

/** Compact, bounded rendering of tool arguments for the approval prompt. */
internal fun describeToolArguments(arguments: JsonObject): String {
    if (arguments.isEmpty()) return "(no arguments)"
    val preview = arguments.entries.take(4).joinToString(", ") { (key, value) ->
        "$key=${describeToolValue(value)}"
    }
    return if (arguments.size > 4) "$preview, …" else preview
}

private fun describeToolValue(value: JsonValue): String = when (value) {
    is JsonValue.Str -> value.value.take(80)
    is JsonValue.Num -> value.value.toString()
    is JsonValue.Bool -> value.value.toString()
    is JsonValue.Null -> "null"
    is JsonValue.Arr -> "[${value.items.size} items]"
    is JsonValue.Obj -> "{…}"
}

internal fun mapEvent(event: AgentEvent): AgentStreamEvent? = when (event) {
    is AgentEvent.Thinking -> AgentStreamEvent.Activity(
        AgentActivity(AgentActivityStatus.THINKING, event.detail ?: "AI responding"),
    )

    is AgentEvent.ToolRequested -> AgentStreamEvent.ToolRequested(
        toolName = event.toolName,
        detail = describeToolArguments(event.arguments),
    )

    is AgentEvent.ToolCallStarted -> AgentStreamEvent.ToolRunning(event.toolName)

    is AgentEvent.ToolProgress -> AgentStreamEvent.Activity(
        AgentActivity(AgentActivityStatus.USING_TOOL, event.detail),
    )

    is AgentEvent.ToolCallFinished -> AgentStreamEvent.ToolFinished(
        toolName = event.toolName,
        success = event.success,
        summary = event.summary,
    )

    is AgentEvent.ToolCancelled -> AgentStreamEvent.ToolFinished(
        toolName = event.toolName,
        success = false,
        summary = event.reason,
    )

    is AgentEvent.PermissionRequested -> permissionEvent(event.pending, event.sessionId)

    is AgentEvent.SubAgentStarted -> AgentStreamEvent.AgentChanged(
        role = event.role.name,
        label = displayName(event.role),
    )

    is AgentEvent.SubAgentCompleted -> AgentStreamEvent.AgentChanged(
        role = AgentRole.MAIN.name,
        label = displayName(AgentRole.MAIN),
    )

    is AgentEvent.OutputDelta -> AgentStreamEvent.Chunk(event.text)

    else -> null
}

internal fun failureKind(error: AgentError?): AgentFailureKind {
    if (error == null) return AgentFailureKind.UNKNOWN
    return when (error.code) {
        AgentErrorCode.TIMEOUT -> AgentFailureKind.TIMEOUT
        AgentErrorCode.MALFORMED_RESPONSE -> AgentFailureKind.INVALID_RESPONSE
        AgentErrorCode.NOT_CONFIGURED -> AgentFailureKind.NOT_CONFIGURED
        AgentErrorCode.CANCELLED -> AgentFailureKind.CANCELLED
        AgentErrorCode.MODEL_FAILURE -> when (providerCode(error)) {
            ModelProviderErrorCode.TIMEOUT -> AgentFailureKind.TIMEOUT
            ModelProviderErrorCode.INVALID_RESPONSE -> AgentFailureKind.INVALID_RESPONSE
            ModelProviderErrorCode.CONNECTION_FAILED,
            ModelProviderErrorCode.NETWORK_ERROR,
            -> AgentFailureKind.CONNECTION
            else -> AgentFailureKind.UNKNOWN
        }
        else -> AgentFailureKind.UNKNOWN
    }
}

private fun providerCode(error: AgentError?): ModelProviderErrorCode? {
    val named = error?.details?.get("providerError")
    named?.let { raw ->
        return runCatching { ModelProviderErrorCode.valueOf(raw) }.getOrNull()
    }
    return (error?.cause as? ModelProviderError)?.code
}

private fun displayName(role: AgentRole): String = when (role) {
    AgentRole.MAIN -> "Main"
    AgentRole.EXPLORER -> "Explorer"
    AgentRole.RESEARCHER -> "Researcher"
    AgentRole.CODER -> "Coder"
    AgentRole.DEBUGGER -> "Debugger"
    AgentRole.REVIEWER -> "Reviewer"
    AgentRole.TESTER -> "Tester"
}
