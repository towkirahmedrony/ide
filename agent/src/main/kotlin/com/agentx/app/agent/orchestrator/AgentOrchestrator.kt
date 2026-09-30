package com.agentx.app.agent.orchestrator

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
import com.agentx.app.agent.runtime.ResumedPermission
import com.agentx.app.agent.runtime.SubAgentInvoker
import com.agentx.app.agent.specialized.SpecializedAgentRegistry
import com.agentx.app.agent.specialized.unknownSubAgent
import com.agentx.app.context.ContextBudget
import com.agentx.app.context.ContextEngine
import com.agentx.app.context.ContextRequest
import com.agentx.app.core.logging.ForgeLogger
import com.agentx.app.core.logging.ForgeLoggers
import com.agentx.app.core.logging.LogLevel
import com.agentx.app.model.ModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
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
}

class DefaultAgentOrchestrator(
    private val mainAgent: MainAgent,
    private val specialized: SpecializedAgentRegistry,
    private val sessions: AgentSessionStore = InMemoryAgentSessionStore(),
    /** Supplies the workspace, conversation and tool-result context of a task. */
    private val contextEngine: ContextEngine? = null,
    private val defaultTimeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
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
        val timeoutMillis: Long,
        val contextBudget: ContextBudget,
        val promptVariables: PromptVariables,
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
        val task = AgentTask(
            id = AgentIds.newId(),
            prompt = request.prompt,
            workspaceId = request.workspaceId,
        )
        val session = AgentSession(
            id = sessionId,
            parentSessionId = null,
            role = AgentRole.MAIN,
            status = AgentStatus.RUNNING,
            task = task,
            createdAtMillis = now,
            updatedAtMillis = now,
            workspaceId = request.workspaceId,
        )
        sessions.save(session)
        sink.emit(
            AgentEvent.SessionCreated(
                sessionId = sessionId,
                role = AgentRole.MAIN,
                parentSessionId = null,
                timestampMillis = now,
            ),
        )

        val timeout = request.timeoutMillis ?: defaultTimeoutMillis
        val job = coroutineContext[Job]
        if (job != null) jobs[sessionId] = job

        val variables = promptVariables(request)
        return try {
            val assembled = assembleContext(request, sessionId)
            val result = withTimeout(timeout) {
                mainAgent.run(
                    request = MainAgentRequest(
                        sessionId = sessionId,
                        task = task,
                        context = assembled,
                        modelConfig = modelConfig,
                        contextBudget = request.contextBudget,
                        promptVariables = variables,
                    ),
                    sink = sink,
                    subAgentInvoker = SubAgentInvoker { child ->
                        runSubAgent(child, modelConfig, sink) { isCancelled(sessionId) || isCancelled(child.sessionId) }
                    },
                    onCancelled = { isCancelled(sessionId) },
                )
            }
            sessions.update(sessionId) { it.withStatus(result.status, clock()) }
            if (result.status == AgentStatus.WAITING_FOR_PERMISSION && result.pendingPermission != null) {
                pausedPermissions[sessionId] = PausedRun(
                    pending = result.pendingPermission,
                    resumeContext = result.resumeContext,
                    context = assembled,
                    modelConfig = modelConfig,
                    timeoutMillis = timeout,
                    contextBudget = request.contextBudget,
                    promptVariables = variables,
                )
            }
            result
        } catch (error: TimeoutCancellationException) {
            val agentError = AgentError(
                code = AgentErrorCode.TIMEOUT,
                message = "Agent session timed out after ${timeout}ms",
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
            val result = withTimeout(paused.timeoutMillis) {
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
                    ),
                    sink = sink,
                    subAgentInvoker = SubAgentInvoker { child ->
                        runSubAgent(child, modelConfig, sink) { isCancelled(sessionId) || isCancelled(child.sessionId) }
                    },
                    onCancelled = { isCancelled(sessionId) },
                )
            }
            sessions.update(sessionId) { it.withStatus(result.status, clock()) }
            if (result.status == AgentStatus.WAITING_FOR_PERMISSION && result.pendingPermission != null) {
                pausedPermissions[sessionId] = PausedRun(
                    pending = result.pendingPermission,
                    resumeContext = result.resumeContext,
                    context = paused.context,
                    modelConfig = modelConfig,
                    timeoutMillis = paused.timeoutMillis,
                    contextBudget = paused.contextBudget,
                    promptVariables = paused.promptVariables,
                )
            }
            result
        } catch (error: TimeoutCancellationException) {
            val agentError = AgentError(
                code = AgentErrorCode.TIMEOUT,
                message = "Agent session timed out after ${paused.timeoutMillis}ms",
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

    override fun session(id: String): AgentSession? = sessions.find(id)

    override fun sessions(): List<AgentSession> = sessions.all()

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
            ),
        )
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
            coroutineScope {
                agent.run(request, modelConfig, sink, onCancelled)
            }
        }
        sessions.update(request.sessionId) { it.withStatus(result.status, clock()) }
        sessions.update(request.parentSessionId) { it.withStatus(AgentStatus.RUNNING, clock()) }
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
    private suspend fun assembleContext(request: AgentRunRequest, sessionId: String): String {
        val parts = mutableListOf<String>()
        if (request.context.isNotBlank()) parts += request.context
        val engine = contextEngine ?: return parts.joinToString("\n\n")
        val assembled = engine.buildContext(
            ContextRequest(
                task = request.prompt,
                sessionId = sessionId,
                workspaceId = request.workspaceId,
                includeTask = false,
                mentionedFiles = request.mentionedFiles,
                selectedFile = request.selectedFile,
                conversation = request.conversation,
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
            ),
        )
        return parts.joinToString("\n\n")
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

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 120_000L
    }
}
