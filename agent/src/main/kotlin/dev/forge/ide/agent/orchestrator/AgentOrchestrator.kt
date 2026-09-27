package dev.forge.ide.agent.orchestrator

import dev.forge.ide.agent.domain.AgentContextSource
import dev.forge.ide.agent.domain.AgentError
import dev.forge.ide.agent.domain.AgentErrorCode
import dev.forge.ide.agent.domain.AgentEvent
import dev.forge.ide.agent.domain.AgentEventSink
import dev.forge.ide.agent.domain.AgentResult
import dev.forge.ide.agent.domain.AgentRole
import dev.forge.ide.agent.domain.AgentRunRequest
import dev.forge.ide.agent.domain.AgentSession
import dev.forge.ide.agent.domain.AgentStatus
import dev.forge.ide.agent.domain.AgentTask
import dev.forge.ide.agent.domain.SubAgentRequest
import dev.forge.ide.agent.domain.SubAgentResult
import dev.forge.ide.agent.main.MainAgent
import dev.forge.ide.agent.main.MainAgentRequest
import dev.forge.ide.agent.runtime.AgentIds
import dev.forge.ide.agent.runtime.SubAgentInvoker
import dev.forge.ide.agent.specialized.SpecializedAgentRegistry
import dev.forge.ide.agent.specialized.unknownSubAgent
import dev.forge.ide.model.ModelConfig
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

    fun session(id: String): AgentSession?

    fun sessions(): List<AgentSession>

    fun cancel(sessionId: String): Boolean
}

class DefaultAgentOrchestrator(
    private val mainAgent: MainAgent,
    private val specialized: SpecializedAgentRegistry,
    private val sessions: AgentSessionStore = InMemoryAgentSessionStore(),
    private val contextSource: AgentContextSource? = null,
    private val defaultTimeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : AgentOrchestrator {

    private val cancellations = ConcurrentHashMap<String, Boolean>()
    private val jobs = ConcurrentHashMap<String, Job>()

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

        return try {
            val assembled = assembleContext(request)
            val result = withTimeout(timeout) {
                mainAgent.run(
                    request = MainAgentRequest(
                        sessionId = sessionId,
                        task = task,
                        context = assembled,
                        modelConfig = modelConfig,
                    ),
                    sink = sink,
                    subAgentInvoker = SubAgentInvoker { child ->
                        runSubAgent(child, modelConfig, sink) { isCancelled(sessionId) || isCancelled(child.sessionId) }
                    },
                    onCancelled = { isCancelled(sessionId) },
                )
            }
            sessions.update(sessionId) { it.withStatus(result.status, clock()) }
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
        }
    }

    override fun session(id: String): AgentSession? = sessions.find(id)

    override fun sessions(): List<AgentSession> = sessions.all()

    override fun cancel(sessionId: String): Boolean {
        cancellations[sessionId] = true
        jobs[sessionId]?.cancel()
        sessions.update(sessionId) { current ->
            if (current.status == AgentStatus.RUNNING || current.status == AgentStatus.WAITING_FOR_SUBAGENT) {
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

    private suspend fun assembleContext(request: AgentRunRequest): String {
        val parts = mutableListOf<String>()
        if (request.context.isNotBlank()) parts += request.context
        val extra = contextSource?.assemble(request.prompt, request.workspaceId)
        extra?.snippets?.forEach { if (it.isNotBlank()) parts += it }
        extra?.workspaceId?.let { parts += "workspaceId=$it" }
        return parts.joinToString("\n\n")
    }

    private fun isCancelled(sessionId: String): Boolean = cancellations[sessionId] == true

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 120_000L
    }
}
