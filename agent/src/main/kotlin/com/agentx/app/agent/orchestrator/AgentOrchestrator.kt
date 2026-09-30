package com.agentx.app.agent.orchestrator

import com.agentx.app.agent.conversation.ConversationAssembler
import com.agentx.app.agent.conversation.ConversationHistory
import com.agentx.app.agent.conversation.MessageRole
import com.agentx.app.agent.conversation.SessionTitle
import com.agentx.app.agent.domain.AgentError
import com.agentx.app.agent.domain.AgentErrorCode
import com.agentx.app.agent.domain.AgentEvent
import com.agentx.app.agent.domain.AgentEventSink
import com.agentx.app.agent.domain.AgentResult
import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.agent.domain.AgentRunRequest
import com.agentx.app.agent.domain.AgentSession
import com.agentx.app.agent.domain.AgentStatus
import com.agentx.app.agent.domain.AgentTask
import com.agentx.app.agent.domain.SubAgentRequest
import com.agentx.app.agent.domain.SubAgentResult
import com.agentx.app.agent.main.MainAgent
import com.agentx.app.agent.main.MainAgentRequest
import com.agentx.app.agent.prompt.PromptVariables
import com.agentx.app.agent.runtime.AgentIds
import com.agentx.app.agent.runtime.ConversationalTurn
import com.agentx.app.agent.runtime.ResumedPermission
import com.agentx.app.agent.runtime.SubAgentInvoker
import com.agentx.app.agent.specialized.SpecializedAgentRegistry
import com.agentx.app.agent.specialized.unknownSubAgent
import com.agentx.app.context.ContextAgentState
import com.agentx.app.context.ContextBudget
import com.agentx.app.context.ContextEngine
import com.agentx.app.context.ContextRequest
import com.agentx.app.core.logging.ForgeLogger
import com.agentx.app.core.logging.ForgeLoggers
import com.agentx.app.core.logging.LogLevel
import com.agentx.app.core.timeout.AgentTimeouts
import com.agentx.app.core.timeout.withExecutionBudget
import com.agentx.app.model.ModelConfig
import com.agentx.app.model.ModelMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/**
 * Session manager and execution bounds. Independent of Compose/UI.
 * Sequential only: Main → one Sub-Agent → Main.
 */
interface AgentOrchestrator {
    suspend fun run(
        request: AgentRunRequest,
        modelConfig: ModelConfig,
        sink: AgentEventSink,
    ): AgentResult

    /**
     * Resumes a session parked in [com.agentx.app.agent.domain.AgentStatus.WAITING_FOR_PERMISSION]
     * with the user's approval decision. Returns null when there is nothing to resume.
     */
    suspend fun resumePermission(sessionId: String, approved: Boolean, sink: AgentEventSink): AgentResult?

    fun session(id: String): AgentSession?

    fun sessions(): List<AgentSession>

    fun cancel(sessionId: String): Boolean

    /** Session-scoped conversation history, when persistence is wired. */
    fun history(): ConversationHistory? = null
}

class DefaultAgentOrchestrator(
    private val mainAgent: MainAgent,
    private val specialized: SpecializedAgentRegistry,
    private val sessions: AgentSessionStore = InMemoryAgentSessionStore(),
    /** Supplies the workspace, conversation and tool-result context of a task. */
    private val contextEngine: ContextEngine? = null,
    /** Session-scoped conversation history. Optional so tests can omit it. */
    private val history: ConversationHistory? = null,
    /**
     * Central execution budgets. The Main Agent and a delegated sub-agent have
     * separate, generous ceilings; stopping a long task is the user's Stop/Cancel,
     * not a short global timeout.
     */
    private val timeouts: AgentTimeouts = AgentTimeouts.DEFAULT,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val logger: ForgeLogger = ForgeLoggers.create(LogLevel.INFO, baseFields = mapOf("layer" to "orchestrator")),
) : AgentOrchestrator {

    private val cancellations = ConcurrentHashMap<String, Boolean>()
    private val jobs = ConcurrentHashMap<String, Job>()

    /** State needed to re-enter a run that paused for a permission decision. */
    private class PausedRun(
        val pending: com.agentx.app.agent.domain.PendingPermission,
        val resumeContext: List<com.agentx.app.model.ModelMessage>,
        val context: String,
        val modelConfig: ModelConfig,
        val budgetMillis: Long,
        val contextBudget: ContextBudget,
        val promptVariables: PromptVariables,
        val requiresWorkspace: Boolean,
    )

    private val pausedPermissions = ConcurrentHashMap<String, PausedRun>()

    override suspend fun run(
        request: AgentRunRequest,
        modelConfig: ModelConfig,
        sink: AgentEventSink,
    ): AgentResult {
        if (modelConfig.validate().isNotEmpty()) {
            val sessionId = request.sessionId ?: AgentIds.newId()
            val error = AgentError(
                code = AgentErrorCode.NOT_CONFIGURED,
                message = "Model gateway is not configured: ${modelConfig.validate().joinToString("; ")}",
                sessionId = sessionId,
            )
            sink.emit(AgentEvent.Failed(sessionId, error, clock()))
            return AgentResult(
                sessionId = sessionId,
                status = AgentStatus.FAILED,
                summary = error.message,
                errors = listOf(error),
            )
        }

        val sessionId = request.sessionId ?: AgentIds.newId()
        cancellations.remove(sessionId)
        val now = clock()
        val existing = sessions.find(sessionId)
        val task = AgentTask(
            id = existing?.task?.id ?: AgentIds.newId(),
            prompt = request.prompt,
            workspaceId = request.workspaceId ?: existing?.workspaceId,
        )
        val title = existing?.title?.takeIf { !SessionTitle.isPlaceholder(it) }
            ?: SessionTitle.derive(request.prompt)
        val session = AgentSession(
            id = sessionId,
            parentSessionId = existing?.parentSessionId,
            role = existing?.role ?: AgentRole.MAIN,
            status = AgentStatus.RUNNING,
            task = task,
            plan = existing?.plan,
            steps = existing?.steps.orEmpty(),
            createdAtMillis = existing?.createdAtMillis ?: now,
            updatedAtMillis = now,
            workspaceId = request.workspaceId ?: existing?.workspaceId,
            title = title,
            modelProviderId = modelConfig.providerId,
            modelId = modelConfig.model,
        )
        sessions.save(session)
        history?.ensureSession(
            sessionId = sessionId,
            workspaceId = session.workspaceId,
            role = session.role,
            parentSessionId = session.parentSessionId,
            modelProviderId = modelConfig.providerId,
            modelId = modelConfig.model,
            prompt = request.prompt,
        )
        history?.markStatus(sessionId, AgentStatus.RUNNING)
        if (existing == null) {
            sink.emit(
                AgentEvent.SessionCreated(
                    sessionId = sessionId,
                    role = session.role,
                    parentSessionId = session.parentSessionId,
                    timestampMillis = now,
                ),
            )
        }

        // A caller may still bound one run explicitly; otherwise the category
        // budget applies. Either way the task stays cancellable.
        val budget = request.timeoutMillis ?: timeouts.mainTaskMillis
        // "Hi" is not a request to describe the project: a conversational turn
        // contributes no workspace context and orders no inspection.
        val requiresWorkspace = ConversationalTurn.requiresWorkspace(request.prompt)
        val job = coroutineContext[Job]
        if (job != null) jobs[sessionId] = job

        val variables = promptVariables(request)
        return try {
            val assembled = assembleContext(request, sessionId, requiresWorkspace)
            val result = withExecutionBudget(budget) {
                mainAgent.run(
                    request = MainAgentRequest(
                        sessionId = sessionId,
                        task = task,
                        context = assembled,
                        modelConfig = modelConfig,
                        contextBudget = request.contextBudget,
                        promptVariables = variables,
                        requiresWorkspace = requiresWorkspace,
                    ),
                    sink = sink,
                    subAgentInvoker = SubAgentInvoker { child ->
                        runSubAgent(child, modelConfig, sink) { isCancelled(sessionId) || isCancelled(child.sessionId) }
                    },
                    onCancelled = { isCancelled(sessionId) },
                )
            }
            rememberTurn(sessionId, request.prompt, result)
            sessions.update(sessionId) { it.withStatus(result.status, clock()) }
            if (result.status == AgentStatus.WAITING_FOR_PERMISSION && result.pendingPermission != null) {
                pausedPermissions[sessionId] = PausedRun(
                    pending = result.pendingPermission,
                    resumeContext = result.resumeContext,
                    context = assembled,
                    modelConfig = modelConfig,
                    budgetMillis = budget,
                    contextBudget = request.contextBudget,
                    promptVariables = variables,
                    requiresWorkspace = requiresWorkspace,
                )
            }
            result
        } catch (error: TimeoutCancellationException) {
            val agentError = AgentError(
                code = AgentErrorCode.TIMEOUT,
                message = "Agent task exceeded its ${budget}ms budget",
                sessionId = sessionId,
                cause = error,
            )
            val failed = AgentResult(
                sessionId = sessionId,
                status = AgentStatus.FAILED,
                summary = agentError.message,
                errors = listOf(agentError),
            )
            rememberTurn(sessionId, request.prompt, failed)
            sessions.update(sessionId) { it.withStatus(AgentStatus.FAILED, clock()) }
            sink.emit(AgentEvent.Failed(sessionId, agentError, clock()))
            failed
        } catch (cancelled: CancellationException) {
            val cancelledResult = AgentResult(
                sessionId = sessionId,
                status = AgentStatus.CANCELLED,
                summary = "Cancelled",
                errors = listOf(
                    AgentError(
                        code = AgentErrorCode.CANCELLED,
                        message = "Cancelled",
                        sessionId = sessionId,
                    ),
                ),
            )
            rememberTurn(sessionId, request.prompt, cancelledResult)
            sessions.update(sessionId) { it.withStatus(AgentStatus.CANCELLED, clock()) }
            sink.emit(AgentEvent.Cancelled(sessionId, "Cancelled", clock()))
            if (isCancelled(sessionId)) cancelledResult else throw cancelled
        } catch (error: Throwable) {
            val agentError = AgentError(
                code = AgentErrorCode.UNKNOWN,
                message = error.message ?: "Agent failed",
                sessionId = sessionId,
                cause = error,
            )
            val failed = AgentResult(
                sessionId = sessionId,
                status = AgentStatus.FAILED,
                summary = agentError.message,
                errors = listOf(agentError),
            )
            rememberTurn(sessionId, request.prompt, failed)
            sessions.update(sessionId) { it.withStatus(AgentStatus.FAILED, clock()) }
            sink.emit(AgentEvent.Failed(sessionId, agentError, clock()))
            failed
        } finally {
            jobs.remove(sessionId)
            // The engine's per-run items are only needed while the run is live;
            // dropping them keeps a long-lived app from accumulating them.
            contextEngine?.clearSession(sessionId)
        }
    }

    override suspend fun resumePermission(
        sessionId: String,
        approved: Boolean,
        sink: AgentEventSink,
    ): AgentResult? {
        val session = sessions.find(sessionId) ?: return null
        val paused = pausedPermissions.remove(sessionId) ?: return null
        if (session.status != AgentStatus.WAITING_FOR_PERMISSION) return null

        val modelConfig = paused.modelConfig
        sessions.update(sessionId) { it.withStatus(AgentStatus.RUNNING, clock()) }
        val job = coroutineContext[Job]
        if (job != null) jobs[sessionId] = job

        return try {
            val result = withExecutionBudget(paused.budgetMillis) {
                mainAgent.run(
                    request = MainAgentRequest(
                        sessionId = sessionId,
                        task = session.task,
                        context = paused.context,
                        modelConfig = modelConfig,
                        contextBudget = paused.contextBudget,
                        resumeContext = paused.resumeContext,
                        resumePermission = ResumedPermission(
                            toolName = paused.pending.toolName,
                            arguments = paused.pending.arguments,
                            reason = paused.pending.reason,
                            toolCallId = paused.pending.toolCallId,
                            approved = approved,
                        ),
                        promptVariables = paused.promptVariables,
                        requiresWorkspace = paused.requiresWorkspace,
                    ),
                    sink = sink,
                    subAgentInvoker = SubAgentInvoker { child ->
                        runSubAgent(child, modelConfig, sink) { isCancelled(sessionId) || isCancelled(child.sessionId) }
                    },
                    onCancelled = { isCancelled(sessionId) },
                )
            }
            rememberTurn(sessionId, session.task.prompt, result)
            sessions.update(sessionId) { it.withStatus(result.status, clock()) }
            if (result.status == AgentStatus.WAITING_FOR_PERMISSION && result.pendingPermission != null) {
                pausedPermissions[sessionId] = PausedRun(
                    pending = result.pendingPermission,
                    resumeContext = result.resumeContext,
                    context = paused.context,
                    modelConfig = modelConfig,
                    budgetMillis = paused.budgetMillis,
                    contextBudget = paused.contextBudget,
                    promptVariables = paused.promptVariables,
                    requiresWorkspace = paused.requiresWorkspace,
                )
            }
            result
        } catch (error: TimeoutCancellationException) {
            val agentError = AgentError(
                code = AgentErrorCode.TIMEOUT,
                message = "Agent task exceeded its ${paused.budgetMillis}ms budget",
                sessionId = sessionId,
                cause = error,
            )
            sessions.update(sessionId) { it.withStatus(AgentStatus.FAILED, clock()) }
            sink.emit(AgentEvent.Failed(sessionId, agentError, clock()))
            AgentResult(
                sessionId = sessionId,
                status = AgentStatus.FAILED,
                summary = agentError.message,
                errors = listOf(agentError),
            )
        } catch (cancelled: CancellationException) {
            sessions.update(sessionId) { it.withStatus(AgentStatus.CANCELLED, clock()) }
            sink.emit(AgentEvent.Cancelled(sessionId, "Cancelled", clock()))
            if (isCancelled(sessionId)) {
                AgentResult(
                    sessionId = sessionId,
                    status = AgentStatus.CANCELLED,
                    summary = "Cancelled",
                )
            } else {
                throw cancelled
            }
        } catch (error: Throwable) {
            val agentError = AgentError(
                code = AgentErrorCode.UNKNOWN,
                message = error.message ?: "Agent failed",
                sessionId = sessionId,
                cause = error,
            )
            sessions.update(sessionId) { it.withStatus(AgentStatus.FAILED, clock()) }
            sink.emit(AgentEvent.Failed(sessionId, agentError, clock()))
            AgentResult(
                sessionId = sessionId,
                status = AgentStatus.FAILED,
                summary = agentError.message,
                errors = listOf(agentError),
            )
        } finally {
            jobs.remove(sessionId)
            contextEngine?.clearSession(sessionId)
        }
    }

    override fun session(id: String): AgentSession? =
        history?.conversation(id)?.session ?: sessions.find(id)

    override fun sessions(): List<AgentSession> {
        val stored = history?.conversations()?.map { it.session }
        return stored ?: sessions.all()
    }

    override fun history(): ConversationHistory? = history

    override fun cancel(sessionId: String): Boolean {
        cancellations[sessionId] = true
        jobs[sessionId]?.cancel()
        sessions.update(sessionId) { current ->
            if (
                current.status == AgentStatus.RUNNING ||
                current.status == AgentStatus.WAITING_FOR_SUBAGENT ||
                current.status == AgentStatus.WAITING_FOR_PERMISSION
            ) {
                current.withStatus(AgentStatus.CANCELLED, clock())
            } else {
                current
            }
        }
        history?.markStatus(sessionId, AgentStatus.CANCELLED)
        return true
    }

    private suspend fun runSubAgent(
        request: SubAgentRequest,
        modelConfig: ModelConfig,
        sink: AgentEventSink,
        onCancelled: () -> Boolean,
    ): SubAgentResult {
        val now = clock()
        sessions.save(
            AgentSession(
                id = request.sessionId,
                parentSessionId = request.parentSessionId,
                role = request.role,
                status = AgentStatus.RUNNING,
                task = AgentTask(
                    id = request.sessionId,
                    prompt = request.task,
                    objective = request.objective,
                    workspaceId = request.workspaceId,
                ),
                createdAtMillis = now,
                updatedAtMillis = now,
                workspaceId = request.workspaceId,
                title = SessionTitle.derive(request.task),
                modelProviderId = modelConfig.providerId,
                modelId = modelConfig.model,
            ),
        )
        history?.ensureSession(
            sessionId = request.sessionId,
            workspaceId = request.workspaceId,
            role = request.role,
            parentSessionId = request.parentSessionId,
            modelProviderId = modelConfig.providerId,
            modelId = modelConfig.model,
            prompt = request.task,
        )
        history?.recordUser(request.sessionId, request.task)
        sink.emit(
            AgentEvent.SessionCreated(
                sessionId = request.sessionId,
                role = request.role,
                parentSessionId = request.parentSessionId,
                timestampMillis = now,
            ),
        )
        sessions.update(request.parentSessionId) { it.withStatus(AgentStatus.WAITING_FOR_SUBAGENT, clock()) }

        val agent = specialized.get(request.role)
        val result = if (agent == null) {
            unknownSubAgent(request)
        } else {
            try {
                // A sub-agent gets its own, independent budget: a long delegated
                // task is not cut short by the Main Agent's remaining time.
                coroutineScope {
                    withExecutionBudget(timeouts.subAgentTaskMillis) {
                        agent.run(request, modelConfig, sink, onCancelled)
                    }
                }
            } catch (timeout: TimeoutCancellationException) {
                // Structured and recoverable: the Main Agent is told the delegate
                // ran out of budget and can continue, re-delegate or report it.
                // Never a raw cancellation, which would abort the whole run.
                val error = AgentError(
                    code = AgentErrorCode.TIMEOUT,
                    message = "Sub-agent '${request.role.name}' exceeded its " +
                        "${timeouts.subAgentTaskMillis}ms budget",
                    role = request.role,
                    sessionId = request.sessionId,
                    cause = timeout,
                    details = mapOf("stage" to "sub_agent"),
                )
                sink.emit(AgentEvent.Failed(request.sessionId, error, clock()))
                SubAgentResult(
                    sessionId = request.sessionId,
                    role = request.role,
                    status = AgentStatus.FAILED,
                    summary = error.message.orEmpty(),
                    errors = listOf(error),
                )
            }
        }
        sessions.update(request.sessionId) { it.withStatus(result.status, clock()) }
        sessions.update(request.parentSessionId) { it.withStatus(AgentStatus.RUNNING, clock()) }
        val asAgent = result.toAgentResult()
        history?.applyTurn(request.sessionId, request.task, asAgent)
        history?.recordSubAgent(
            sessionId = request.parentSessionId,
            childSessionId = request.sessionId,
            role = request.role,
            summary = result.summary,
            success = result.status == AgentStatus.COMPLETED,
        )
        return result
    }

    /**
     * Asks the Context Engine for the supporting context of this task: workspace
     * information, the files that matter, earlier conversation and tool results.
     *
     * The prompt itself is deliberately not echoed here — it is already the user
     * message of the run — and nothing in the loop talks to the engine directly.
     * A missing engine simply means "no supporting context", never a failure.
     */
    private suspend fun assembleContext(
        request: AgentRunRequest,
        sessionId: String,
        requiresWorkspace: Boolean,
    ): String {
        val parts = mutableListOf<String>()
        if (request.context.isNotBlank()) parts += request.context
        val stored = history?.conversation(sessionId)
        val conversation = reconstructConversation(request, stored)
        val summaryText = stored?.summary?.render()?.takeIf { it.isNotBlank() }
        val taskState = stored?.taskState?.let { state ->
            if (state.isEmpty()) {
                null
            } else {
                ContextAgentState(
                    role = stored.role.name,
                    status = stored.status.name,
                    progress = buildString {
                        state.activeTask?.let { append("task=").append(it).append('\n') }
                        state.currentStep?.let { append("step=").append(it).append('\n') }
                        if (state.relevantFiles.isNotEmpty()) {
                            append("files=").append(state.relevantFiles.joinToString(", ")).append('\n')
                        }
                        state.lastToolResult?.let { append("lastTool=").append(it).append('\n') }
                        state.lastError?.let { append("lastError=").append(it) }
                    }.trim().takeIf { it.isNotEmpty() },
                )
            }
        }
        val engine = contextEngine ?: return parts.joinToString("\n\n")
        val assembled = engine.buildContext(
            ContextRequest(
                task = request.prompt,
                sessionId = sessionId,
                workspaceId = request.workspaceId,
                includeTask = false,
                // A conversational turn contributes no workspace facts: no root
                // listing and no workspace descriptor. Anything the caller
                // supplied explicitly (mentioned file, open file, prior tool
                // results) still applies.
                includeWorkspace = requiresWorkspace,
                mentionedFiles = request.mentionedFiles,
                selectedFile = request.selectedFile,
                conversation = conversation,
                agentState = taskState,
                sessionSummary = summaryText,
                budget = request.contextBudget,
            ),
        )
        if (assembled.text.isNotBlank()) parts += assembled.text
        logger.info(
            "Run context assembled",
            mapOf(
                "sessionId" to sessionId,
                "workspaceId" to request.workspaceId,
                "selectedFile" to request.selectedFile,
                "contextChars" to parts.sumOf { it.length },
                "engineItems" to assembled.items.size,
                "engineEmpty" to assembled.isEmpty,
                "requiresWorkspace" to requiresWorkspace,
                "historyMessages" to (stored?.messages?.size ?: 0),
                "conversationMessages" to conversation.size,
            ),
        )
        return parts.joinToString("\n\n")
    }

    /**
     * Rebuilds the model-facing conversation for this turn. Stored history is
     * complete; only a budgeted slice is sent. Caller-supplied [AgentRunRequest.conversation]
     * is used when no history exists yet (tests, first turn).
     */
    private fun reconstructConversation(
        request: AgentRunRequest,
        stored: com.agentx.app.agent.conversation.AgentConversation?,
    ): List<ModelMessage> {
        if (stored != null && stored.messages.isNotEmpty()) {
            return ConversationAssembler.modelConversation(stored, request.contextBudget)
        }
        return request.conversation
    }

    private fun rememberTurn(sessionId: String, prompt: String, result: AgentResult) {
        val store = history ?: return
        val existing = store.conversation(sessionId)
        val alreadyRecorded = existing?.messages?.any { message ->
            message.role == MessageRole.USER && message.content.text == prompt.trim()
        } == true
        if (!alreadyRecorded && prompt.isNotBlank()) {
            store.recordUser(sessionId, prompt.trim())
        }
        val afterUser = store.conversation(sessionId)
        val hasAssistant = afterUser?.messages?.any { message ->
            message.role == MessageRole.ASSISTANT || message.role == MessageRole.ERROR
        } == true
        if (!hasAssistant && result.summary.isNotBlank()) {
            val role = if (result.status == AgentStatus.FAILED || result.status == AgentStatus.CANCELLED) {
                MessageRole.ERROR
            } else {
                MessageRole.ASSISTANT
            }
            val status = if (role == MessageRole.ERROR) {
                com.agentx.app.agent.conversation.MessageStatus.ERROR
            } else {
                com.agentx.app.agent.conversation.MessageStatus.COMPLETED
            }
            store.append(
                sessionId,
                com.agentx.app.agent.conversation.ConversationMessage(
                    id = AgentIds.newId(),
                    sessionId = sessionId,
                    role = role,
                    content = com.agentx.app.agent.conversation.MessageContent(
                        text = result.errors.firstOrNull()?.message ?: result.summary,
                        errorCode = result.errors.firstOrNull()?.code?.name,
                    ),
                    metadata = com.agentx.app.agent.conversation.MessageMetadata(
                        status = status,
                        timestampMillis = clock(),
                    ),
                ),
            )
        }
        store.applyTurn(sessionId, prompt, result)
    }

    /**
     * Template variables available to a run's system prompt. Only non-secret,
     * non-sensitive values are exposed; an unknown value is simply omitted and
     * left as a visible `{{token}}` in the prompt.
     */
    private fun promptVariables(request: AgentRunRequest): PromptVariables = PromptVariables(
        buildMap {
            request.workspaceId?.takeIf { it.isNotBlank() }?.let { put("workspace", it) }
            request.selectedFile?.takeIf { it.isNotBlank() }?.let { file ->
                put("current_file", file)
                languageOf(file)?.let { put("language", it) }
            }
        },
    )

    private fun languageOf(path: String): String? = when (path.substringAfterLast('.', "").lowercase()) {
        "kt", "kts" -> "Kotlin"
        "java" -> "Java"
        "ts", "tsx" -> "TypeScript"
        "js", "jsx" -> "JavaScript"
        "py" -> "Python"
        "rs" -> "Rust"
        "go" -> "Go"
        "swift" -> "Swift"
        "c", "h" -> "C"
        "cpp", "cc", "hpp" -> "C++"
        "json" -> "JSON"
        "xml" -> "XML"
        "md" -> "Markdown"
        "yml", "yaml" -> "YAML"
        "sh", "bash" -> "Shell"
        else -> null
    }

    private fun isCancelled(sessionId: String): Boolean = cancellations[sessionId] == true
}
