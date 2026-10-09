package com.agentx.app.agent.ui

import android.util.Log
import com.agentx.app.agent.conversation.AgentConversation
import com.agentx.app.agent.conversation.ConversationHistory
import com.agentx.app.agent.conversation.ConversationMessage
import com.agentx.app.agent.conversation.MessageRole
import com.agentx.app.agent.conversation.SessionTitle
import com.agentx.app.agent.diagnostics.AgentTurnDiagnostics
import com.agentx.app.agent.domain.AgentError
import com.agentx.app.agent.domain.AgentErrorCode
import com.agentx.app.agent.domain.AgentEvent
import com.agentx.app.agent.domain.AgentEventSink
import com.agentx.app.agent.domain.AgentResult
import com.agentx.app.agent.domain.ModelFallbackReason
import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.agent.domain.AgentRunRequest
import com.agentx.app.context.AgentAttachment
import com.agentx.app.agent.domain.AgentStatus
import com.agentx.app.agent.domain.PendingPermission
import com.agentx.app.agent.orchestrator.AgentOrchestrator
import com.agentx.app.context.ContextBudget
import com.agentx.app.core.logging.ForgeLogger
import com.agentx.app.core.logging.ForgeLoggers
import com.agentx.app.core.logging.LogLevel
import com.agentx.app.model.ModelConfig
import com.agentx.app.model.ModelMessage
import com.agentx.app.model.ModelProviderError
import com.agentx.app.model.ModelProviderErrorCode
import com.agentx.app.model.json.JsonObject
import com.agentx.app.model.json.JsonValue
import com.agentx.app.ui.ide.data.AgentFailureKind
import com.agentx.app.ui.ide.data.AgentSession
import com.agentx.app.ui.ide.data.AgentSessionInfo
import com.agentx.app.ui.ide.data.AgentStreamEvent
import com.agentx.app.tools.planning.TodoList
import com.agentx.app.tools.planning.TodoStatus
import com.agentx.app.tools.planning.TodoWriteTool
import com.agentx.app.ui.ide.data.PersistedAgentMessage
import com.agentx.app.ui.ide.data.PersistedMessageKind
import com.agentx.app.ui.ide.model.AgentActivity
import com.agentx.app.ui.ide.model.AgentActivityStatus

/**
 * Bridges the Agent Core orchestrator to the IDE [AgentSession] port.
 * UI stays free of model, tool, and orchestrator types.
 *
 * Session history is not reimplemented here: every session, message and tool
 * record is read from and written to the Agent Core's own [ConversationHistory],
 * which persists through the conversation store configured at boot.
 */
class OrchestratorAgentSession(
    private val orchestrator: AgentOrchestrator,
    private val modelConfig: () -> ModelConfig,
    /**
     * Project used only when a caller does not name one. Production always names
     * the active project explicitly, so this stays null there; it exists so a
     * scripted or test caller without a project keeps working.
     */
    private val defaultProjectId: String? = null,
    /**
     * Structured logger the turn diagnostics write to. The composition root passes
     * the app's shared logger (which the app-level sink mirrors into the Developer
     * Log); a test or preview keeps the default console logger.
     */
    logger: ForgeLogger? = null,
) : AgentSession {

    private val diagnosticsLogger: ForgeLogger = logger ?: ForgeLoggers.create(
        LogLevel.INFO,
        baseFields = mapOf("layer" to "agent-bridge"),
    )

    /**
     * The session selected per project, keyed by project id. Chat is
     * project-scoped: selecting a session in one project never selects one in
     * another, and this is the only mutable chat state the bridge keeps.
     */
    private val activeByProject = LinkedHashMap<String, String>()

    /** The Agent Core's persistent history, when the orchestrator owns one. */
    private val history: ConversationHistory?
        get() = orchestrator.history()

    /**
     * Compact record of previous turns, oldest first. It is only used as a
     * fallback when the orchestrator has no persistent history (tests and
     * previews); with history wired, [ConversationHistory] is the source of
     * truth and this list stays empty.
     */
    private val conversation = mutableListOf<ModelMessage>()

    /** Same ceiling the Context Engine applies to conversation context. */
    private val conversationLimit = ContextBudget.DEFAULT.maxConversationMessages

    /**
     * Reaches the run itself rather than only the coroutine waiting on it.
     *
     * The orchestrator marks the session cancelled, flags it so the loop stops at its
     * next observation point, and cancels the job the run is executing in — so a model
     * request, stream, tool call or sub-agent in flight observes the stop and no
     * queued work starts afterwards.
     */
    override fun cancel(sessionId: String) {
        orchestrator.cancel(sessionId)
    }

    override suspend fun run(
        input: String,
        onEvent: (AgentStreamEvent) -> Unit,
        workspaceId: String?,
        selectedFile: String?,
        attachments: List<AgentAttachment>,
        skillIds: Set<String>?,
    ) {
        val projectId = workspaceId ?: defaultProjectId
        if (projectId == null) {
            // A caller with no owning project runs without touching any project's
            // persisted history; the bounded in-memory fallback is used instead.
            runTurn(null, input, onEvent, null, selectedFile, attachments, skillIds)
            return
        }
        val sessionId = activeByProject[projectId]
            ?: history?.createSession(workspaceId = projectId)?.id?.also { created ->
                activeByProject[projectId] = created
            }
        runTurn(sessionId, input, onEvent, projectId, selectedFile, attachments, skillIds)
    }

    override suspend fun runInSession(
        sessionId: String,
        input: String,
        onEvent: (AgentStreamEvent) -> Unit,
        workspaceId: String?,
        selectedFile: String?,
        attachments: List<AgentAttachment>,
        skillIds: Set<String>?,
    ) {
        val projectId = workspaceId ?: defaultProjectId
        // Refuse to run a project's turn inside another project's transcript: that
        // would rebuild the agent context from the wrong conversation. A persisted
        // session is owned by exactly one project, and only its owner may use it.
        val storedOwner = history?.open(sessionId)?.workspaceId
        if (projectId != null && storedOwner != null && storedOwner != projectId) {
            onEvent(
                AgentStreamEvent.Failed(
                    "That chat session belongs to a different project.",
                    AgentFailureKind.UNKNOWN,
                ),
            )
            return
        }
        if (projectId != null) activeByProject[projectId] = sessionId
        runTurn(sessionId, input, onEvent, workspaceId ?: storedOwner, selectedFile, attachments, skillIds)
    }

    // ───────────────────────────── Session management ─────────────────────────────

    override suspend fun listSessions(projectId: String): List<AgentSessionInfo> {
        val store = history ?: return emptyList()
        val conversations = store.conversations(projectId)
        val active = activeByProject[projectId] ?: conversations.firstOrNull()?.id
        return conversations.map { conversation -> conversation.toInfo(active) }
    }

    override suspend fun activeSessionId(projectId: String): String? {
        activeByProject[projectId]?.let { return it }
        return history?.conversations(projectId)?.firstOrNull()?.id
    }

    override suspend fun createSession(projectId: String): AgentSessionInfo? {
        val store = history ?: return null
        val owner = projectId.trim()
        // A session must have an owning project; without one nothing is persisted.
        if (owner.isEmpty()) return null
        val created = store.createSession(workspaceId = owner)
        activeByProject[owner] = created.id
        return created.toInfo(created.id)
    }

    override suspend fun restoreSession(projectId: String, sessionId: String): List<PersistedAgentMessage> {
        val store = history ?: return emptyList()
        val conversation = store.owned(projectId, sessionId) ?: return emptyList()
        activeByProject[projectId] = sessionId
        return conversation.messages.map { message -> message.toPersisted() }
    }

    override suspend fun renameSession(projectId: String, sessionId: String, title: String): Boolean {
        val store = history ?: return false
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return false
        if (store.owned(projectId, sessionId) == null) return false
        val updated = store.rename(sessionId, trimmed) ?: return false
        return updated.session.title == trimmed
    }

    override suspend fun deleteSession(projectId: String, sessionId: String): Boolean {
        val store = history ?: return false
        if (store.owned(projectId, sessionId) == null) return false
        val removed = store.delete(sessionId)
        if (removed) activeByProject.entries.removeAll { it.value == sessionId }
        return removed
    }

    // ───────────────────────────── Turn ─────────────────────────────

    private suspend fun runTurn(
        sessionId: String?,
        input: String,
        onEvent: (AgentStreamEvent) -> Unit,
        workspaceId: String?,
        selectedFile: String?,
        attachments: List<AgentAttachment>,
        skillIds: Set<String>?,
    ) {
        val config = modelConfig()
        // One correlation id per user message: input, request, streaming, tools,
        // sub-agents, permissions and the outcome all carry it, so a turn reads as a
        // single traceable story and concurrent turns cannot mix.
        val diagnostics = AgentTurnDiagnostics(
            logger = diagnosticsLogger,
            sessionId = sessionId ?: UNBOUND_SESSION_ID,
        )
        val request = AgentRunRequest(
            prompt = input,
            sessionId = sessionId,
            workspaceId = workspaceId ?: defaultProjectId,
            selectedFile = selectedFile,
            // Attachments are part of this turn's request, not of the stored transcript: the
            // context engine loads each one from the workspace, like a mentioned path.
            attachments = attachments,
            skillIds = skillIds,
            // With persistent history the core rebuilds the conversation from
            // the stored transcript; the fallback list is only for no-history runs.
            conversation = if (sessionId == null) conversation.toList() else emptyList(),
        )
        diagnostics.started(request, config)
        if (config.validate().isNotEmpty()) {
            // Fails before the model provider is reached; the real reason is logged.
            diagnostics.rejected(NO_MODEL_ONLINE, config)
            onEvent(AgentStreamEvent.Failed(NO_MODEL_ONLINE, AgentFailureKind.NOT_CONFIGURED))
            return
        }

        onEvent(
            AgentStreamEvent.Activity(
                AgentActivity(AgentActivityStatus.THINKING, "AI responding"),
            ),
        )

        val activeWorkspaceId = request.workspaceId
        Log.d(
            TAG,
            "Agent run sessionId=${sessionId ?: "(none)"} workspaceId=${activeWorkspaceId ?: "(none)"} " +
                "selectedFile=${selectedFile ?: "(none)"} promptChars=${input.length} " +
                "attachments=${attachments.size} skills=${skillIds?.size ?: 0}",
        )

        // The diagnostics observe the RAW runtime event stream (before UI mapping), so a
        // model selection or fallback that the UI does not render is still recorded.
        val sink = AgentEventSink { event ->
            diagnostics.onEvent(event)
            mapEvent(event)?.let(onEvent)
        }
        val result = try {
            orchestrator.run(
                request = request,
                modelConfig = config,
                sink = sink,
            )
        } catch (error: Throwable) {
            // The original exception is preserved: it is recorded and rethrown, never
            // swallowed to manufacture a log line.
            diagnostics.fail(error, stage = "orchestrator.run")
            throw error
        }
        diagnostics.finish(result)
        if (sessionId == null) record(input, result)
        emitOutcome(result, sessionId = sessionId, onEvent = onEvent)
    }

    /**
     * Remembers this turn for the next one when there is no persistent history:
     * the request, the outcome, and a compact line of what the agent actually
     * did with its tools. Nothing is stored beyond the conversation budget.
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
        // A resume is a new phase of the parked turn; it gets its own correlation id
        // but the same session id, so the pause and its continuation can be linked.
        val diagnostics = AgentTurnDiagnostics(logger = diagnosticsLogger, sessionId = sessionId)
        diagnostics.resumed(approved)
        val sink = AgentEventSink { event ->
            diagnostics.onEvent(event)
            mapEvent(event)?.let(onEvent)
        }
        val resumed = try {
            orchestrator.resumePermission(sessionId, approved, sink)
        } catch (error: Throwable) {
            diagnostics.fail(error, stage = "orchestrator.resumePermission")
            throw error
        }
        if (resumed == null) {
            diagnostics.fail(
                IllegalStateException("The paused agent turn is no longer available"),
                stage = "resume.unavailable",
            )
            onEvent(AgentStreamEvent.Failed("The paused agent turn is no longer available", AgentFailureKind.UNKNOWN))
            return
        }
        diagnostics.finish(resumed)
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
                onEvent(
                    AgentStreamEvent.Completed(
                        text = result.summary,
                        filesChanged = result.filesChanged,
                        filesInspected = result.filesInspected,
                    ),
                )
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

        /** Placeholder session id for a turn with no owning project; never persisted. */
        const val UNBOUND_SESSION_ID = "unbound"
        const val NO_MODEL_ONLINE =
            "No model is online. Open Settings → Models, select a model and connect it first."

        /** Upper bound for one recorded turn; the engine trims the rest. */
        const val MAX_ENTRY_CHARS = 1_000
    }
}

// ───────────────────────────── Mapping ─────────────────────────────

private fun AgentConversation.toInfo(activeId: String?): AgentSessionInfo = AgentSessionInfo(
    id = id,
    title = session.title?.takeIf { it.isNotBlank() } ?: SessionTitle.DEFAULT,
    updatedAtMillis = session.updatedAtMillis,
    messageCount = messages.size,
    active = id == activeId,
)

private fun ConversationMessage.toPersisted(): PersistedAgentMessage = PersistedAgentMessage(
    id = id,
    kind = when (role) {
        MessageRole.USER -> PersistedMessageKind.USER
        MessageRole.ASSISTANT -> PersistedMessageKind.ASSISTANT
        MessageRole.ERROR -> PersistedMessageKind.ERROR
        MessageRole.TOOL -> PersistedMessageKind.TOOL
        MessageRole.SUB_AGENT -> PersistedMessageKind.SUB_AGENT
        MessageRole.SYSTEM -> PersistedMessageKind.SYSTEM
    },
    text = content.text,
    toolName = content.toolName,
    toolArguments = content.toolArguments,
    toolResult = content.toolResult,
    toolSuccess = metadata.toolSuccess,
    timestampMillis = metadata.timestampMillis,
    errorCode = content.errorCode,
    subAgentRole = content.subAgentRole,
    modelId = metadata.modelId,
)

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

private fun mapEventCore(event: AgentEvent): AgentStreamEvent? = when (event) {
    is AgentEvent.Thinking -> AgentStreamEvent.Activity(
        AgentActivity(AgentActivityStatus.THINKING, event.detail ?: "AI responding"),
    )

    // The runtime's own plan, forwarded as-is: same steps, same titles, same
    // statuses. Nothing is added, renamed or advanced on the way to the UI.
    is AgentEvent.PlanUpdated -> AgentStreamEvent.Plan(
        event.plan.steps.map { step ->
            AgentStreamEvent.PlanStep(
                index = step.index,
                title = step.title,
                status = step.status.name,
            )
        },
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
        // The bridge's rendered result is the same text the model saw; it is
        // redacted again at the presentation layer before display.
        output = event.summary,
    )

    is AgentEvent.ToolCancelled -> AgentStreamEvent.ToolFinished(
        toolName = event.toolName,
        success = false,
        summary = event.reason,
        output = event.reason,
    )

    is AgentEvent.PermissionRequested -> permissionEvent(event.pending, event.sessionId)

    is AgentEvent.SubAgentStarted -> AgentStreamEvent.SubAgentStarted(
        role = event.role.name,
        label = displayName(event.role),
        detail = event.objective,
    )

    is AgentEvent.SubAgentCompleted -> {
        AgentStreamEvent.SubAgentFinished(
            role = event.role.name,
            label = displayName(event.role),
            success = event.status == AgentStatus.COMPLETED,
            summary = event.summary,
        )
    }

    // Fallback must never be invisible: a user needs to be able to tell that the
    // answer came from a configured alternate model rather than the primary, and
    // whether the configured chain was exhausted. These carry provider/model
    // identifiers and a reason only — never a prompt or a credential.
    is AgentEvent.ModelFallbackStarted -> AgentStreamEvent.Activity(
        AgentActivity(
            AgentActivityStatus.WAITING,
            "Primary ${label(event.fromProviderId, event.fromModelId)} failed " +
                "(${describeReason(event.reason)}); using configured fallback " +
                "${label(event.toProviderId, event.toModelId)} (attempt ${event.attempt})",
        ),
    )

    is AgentEvent.ModelFallbackSucceeded -> AgentStreamEvent.Activity(
        AgentActivity(
            AgentActivityStatus.WAITING,
            "Fallback ${label(event.toProviderId, event.toModelId)} responded after " +
                "${label(event.fromProviderId, event.fromModelId)} failed",
        ),
    )

    is AgentEvent.ModelFallbackExhausted -> AgentStreamEvent.Activity(
        AgentActivity(
            AgentActivityStatus.ERROR,
            "Configured fallback models were exhausted after ${event.attempts} " +
                "attempt(s); last tried ${label(event.providerId, event.modelId)} " +
                "(${describeReason(event.reason)})",
        ),
    )

    is AgentEvent.OutputDelta -> AgentStreamEvent.Chunk(event.text)

    // The runtime's safe, high-level workflow stage — inspecting, planning, reading,
    // editing, reviewing, verifying, fixing, committing, pushing, completed, blocked
    // or cancelled. It carries no reasoning, prompt text or tool arguments.
    is AgentEvent.ActivityChanged -> AgentStreamEvent.Activity(
        AgentActivity(
            status = activityStatusFor(event.activity),
            label = event.detail ?: activityLabel(event.activity),
        ),
    )

    else -> null
}

/** Maps the runtime's workflow stage onto the UI's activity status. */
private fun activityStatusFor(activity: com.agentx.app.agent.domain.AgentActivity): AgentActivityStatus =
    when (activity) {
        com.agentx.app.agent.domain.AgentActivity.COMPLETED -> AgentActivityStatus.COMPLETED
        com.agentx.app.agent.domain.AgentActivity.BLOCKED -> AgentActivityStatus.ERROR
        com.agentx.app.agent.domain.AgentActivity.CANCELLED -> AgentActivityStatus.IDLE
        com.agentx.app.agent.domain.AgentActivity.VERIFICATION_FAILED -> AgentActivityStatus.TOOL_FAILURE
        com.agentx.app.agent.domain.AgentActivity.VERIFYING,
        com.agentx.app.agent.domain.AgentActivity.RE_VERIFYING,
        com.agentx.app.agent.domain.AgentActivity.FIXING,
        com.agentx.app.agent.domain.AgentActivity.REVIEWING_CHANGES,
        com.agentx.app.agent.domain.AgentActivity.COMMITTING,
        com.agentx.app.agent.domain.AgentActivity.PUSHING,
        -> AgentActivityStatus.USING_TOOL

        com.agentx.app.agent.domain.AgentActivity.INSPECTING,
        com.agentx.app.agent.domain.AgentActivity.PLANNING,
        com.agentx.app.agent.domain.AgentActivity.READING,
        com.agentx.app.agent.domain.AgentActivity.EDITING,
        -> AgentActivityStatus.THINKING
    }

/** A short, human label for a workflow stage, used when the event carries no detail. */
private fun activityLabel(activity: com.agentx.app.agent.domain.AgentActivity): String = when (activity) {
    com.agentx.app.agent.domain.AgentActivity.INSPECTING -> "Inspecting"
    com.agentx.app.agent.domain.AgentActivity.PLANNING -> "Planning"
    com.agentx.app.agent.domain.AgentActivity.READING -> "Reading"
    com.agentx.app.agent.domain.AgentActivity.EDITING -> "Editing"
    com.agentx.app.agent.domain.AgentActivity.REVIEWING_CHANGES -> "Reviewing changes"
    com.agentx.app.agent.domain.AgentActivity.VERIFYING -> "Verifying"
    com.agentx.app.agent.domain.AgentActivity.VERIFICATION_FAILED -> "Verification failed"
    com.agentx.app.agent.domain.AgentActivity.FIXING -> "Fixing"
    com.agentx.app.agent.domain.AgentActivity.RE_VERIFYING -> "Re-verifying"
    com.agentx.app.agent.domain.AgentActivity.COMMITTING -> "Committing"
    com.agentx.app.agent.domain.AgentActivity.PUSHING -> "Pushing"
    com.agentx.app.agent.domain.AgentActivity.COMPLETED -> "Completed"
    com.agentx.app.agent.domain.AgentActivity.BLOCKED -> "Blocked"
    com.agentx.app.agent.domain.AgentActivity.CANCELLED -> "Cancelled"
}

/** `provider/model`, for an operator-readable fallback line. */
private fun label(providerId: String, modelId: String): String =
    if (modelId.isBlank()) providerId else "$providerId/$modelId"

/** A human phrase for a fallback trigger, kept separate from the enum name. */
private fun describeReason(reason: ModelFallbackReason): String = when (reason) {
    ModelFallbackReason.RATE_LIMITED -> "rate limited"
    ModelFallbackReason.TIMEOUT -> "timed out"
    ModelFallbackReason.NETWORK_FAILURE -> "network failure"
    ModelFallbackReason.PROVIDER_UNAVAILABLE -> "provider unavailable"
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
            // Server-side and transport faults are shown as a connection problem: the
            // request, not the user's setup, is what failed, and it is worth retrying.
            ModelProviderErrorCode.CONNECTION_FAILED,
            ModelProviderErrorCode.NETWORK_ERROR,
            ModelProviderErrorCode.SERVER_ERROR,
            ModelProviderErrorCode.SERVICE_UNAVAILABLE,
            ModelProviderErrorCode.PROVIDER_ERROR,
            ModelProviderErrorCode.RATE_LIMITED,
            ModelProviderErrorCode.QUOTA_EXHAUSTED,
            -> AgentFailureKind.CONNECTION
            // Rejected credentials, a refused permission and a model that does not
            // exist are all setup problems: repeating the identical request changes
            // nothing, so they are presented as "not configured" rather than as a
            // transient failure the UI would invite the user to retry.
            ModelProviderErrorCode.AUTHENTICATION_FAILED,
            ModelProviderErrorCode.AUTHORIZATION_FAILED,
            ModelProviderErrorCode.PERMISSION_DENIED,
            ModelProviderErrorCode.MODEL_NOT_FOUND,
            ModelProviderErrorCode.PROVIDER_NOT_FOUND,
            ModelProviderErrorCode.INVALID_CONFIG,
            -> AgentFailureKind.NOT_CONFIGURED
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
    AgentRole.PLANNER -> "Planner"
    AgentRole.FAST_CODER -> "Fast Coder"
    AgentRole.SECURITY_REVIEWER -> "Security Reviewer"
    AgentRole.DOCS -> "Docs"
    AgentRole.COMMIT_PR -> "Commit/PR"
}

/**
 * Runtime -> UI mapping entry point. The model's `todo_write` checklist becomes the
 * turn's plan (the same event the runtime plan uses); every other event maps as before.
 */
internal fun mapEvent(event: AgentEvent): AgentStreamEvent? =
    todoPlanEvent(event) ?: mapEventCore(event)

private fun todoPlanEvent(event: AgentEvent): AgentStreamEvent.Plan? {
    if (event !is AgentEvent.ToolRequested || event.toolName != TodoWriteTool.NAME) return null
    val text = (event.arguments["todos"] as? JsonValue.Str)?.value ?: return null
    val items = TodoList.parse(text)
    if (items.isEmpty()) return null
    return AgentStreamEvent.Plan(
        items.mapIndexed { index, item ->
            AgentStreamEvent.PlanStep(
                index = index,
                title = item.title,
                status = when (item.status) {
                    TodoStatus.COMPLETED -> "COMPLETED"
                    TodoStatus.IN_PROGRESS -> "RUNNING"
                    TodoStatus.PENDING -> "IDLE"
                },
            )
        },
    )
}
