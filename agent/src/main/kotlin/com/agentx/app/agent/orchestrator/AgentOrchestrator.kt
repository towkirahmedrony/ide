package com.agentx.app.agent.orchestrator

import com.agentx.app.agent.conversation.ConversationAssembler
import com.agentx.app.agent.conversation.ConversationHistory
import com.agentx.app.agent.conversation.ConversationMessage
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
import com.agentx.app.agent.domain.DelegatedPermissionPause
import com.agentx.app.agent.domain.ResumedPermission
import com.agentx.app.agent.domain.SubAgentRequest
import com.agentx.app.agent.domain.SubAgentResult
import com.agentx.app.agent.main.MainAgent
import com.agentx.app.agent.main.MainAgentRequest
import com.agentx.app.agent.model.AgentModelResolutionException
import com.agentx.app.agent.model.AgentModelResolver
import com.agentx.app.agent.prompt.PromptVariables
import com.agentx.app.agent.runtime.AgentIds
import com.agentx.app.agent.runtime.ConversationalTurn
import com.agentx.app.agent.runtime.ResolvedDelegation
import com.agentx.app.agent.runtime.SubAgentInvoker
import com.agentx.app.agent.specialized.SpecializedAgent
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

    /**
     * Every session owned by [workspaceId]. Scoped like the conversation store so
     * a caller can never enumerate (or leak) another project's sessions.
     */
    fun sessions(workspaceId: String): List<AgentSession>

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
    /**
     * Resolves each role's [ModelConfig] from the active model and the role
     * mapping. The default resolves every role to the active model, so an
     * orchestrator without an explicit resolver behaves exactly as before.
     */
    private val modelResolver: AgentModelResolver = AgentModelResolver(),
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
        /** The skill selection of the turn that parked, so resuming keeps it. */
        val skillIds: Set<String>?,
        /**
         * Set when this pause belongs to a delegated specialist. The parent run is
         * waiting because its child is waiting; the permission is the child's and
         * the decision must resume the child, never the parent.
         */
        val delegated: DelegatedPermissionPause? = null,
    )

    /** The original delegation of a specialist that parked, so it can be resumed. */
    private class PausedChild(
        val request: SubAgentRequest,
        val config: ModelConfig,
    )

    private val pausedPermissions = ConcurrentHashMap<String, PausedRun>()

    /** Paused specialists, keyed by child session id. */
    private val pausedChildren = ConcurrentHashMap<String, PausedChild>()

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
        // MAIN resolves its own model from the active configuration and the role
        // mapping; the loop and the session metadata then agree on one config.
        val mainConfig = try {
            val resolution = modelResolver.resolveForRole(mainAgent.definition, modelConfig)
            val resolved = resolution.eligibleConfigOrThrow()
            emitModelSelected(sink, sessionId, mainAgent.definition.role, resolved, resolution.explicit)
            resolved
        } catch (unsupported: AgentModelResolutionException) {
            val error = unsupported.error.copy(sessionId = sessionId)
            sink.emit(AgentEvent.Failed(sessionId, error, clock()))
            return AgentResult(
                sessionId = sessionId,
                status = AgentStatus.FAILED,
                summary = error.message,
                errors = listOf(error),
            )
        }
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
            modelProviderId = mainConfig.providerId,
            modelId = mainConfig.model,
        )
        sessions.save(session)
        history?.ensureSession(
            sessionId = sessionId,
            workspaceId = session.workspaceId,
            role = session.role,
            parentSessionId = session.parentSessionId,
            modelProviderId = mainConfig.providerId,
            modelId = mainConfig.model,
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
                        modelConfig = mainConfig,
                        contextBudget = request.contextBudget,
                        promptVariables = variables,
                        requiresWorkspace = requiresWorkspace,
                        skillIds = request.skillIds,
                    ),
                    sink = trackingSink(sink),
                    subAgentInvoker = SubAgentInvoker { child ->
                        runSubAgent(child, modelConfig, sink) { isCancelled(sessionId) || isCancelled(child.sessionId) }
                    },
                    onCancelled = { isCancelled(sessionId) },
                )
            }
            rememberTurn(sessionId, request.prompt, result)
            sessions.update(sessionId) { it.withStatus(result.status, clock()) }
            rememberPause(
                sessionId = sessionId,
                result = result,
                context = assembled,
                modelConfig = modelConfig,
                budgetMillis = budget,
                contextBudget = request.contextBudget,
                promptVariables = variables,
                requiresWorkspace = requiresWorkspace,
                skillIds = request.skillIds,
            )
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
        val mainConfig = try {
            val resolution = modelResolver.resolveForRole(mainAgent.definition, modelConfig)
            val resolved = resolution.eligibleConfigOrThrow()
            emitModelSelected(sink, sessionId, mainAgent.definition.role, resolved, resolution.explicit)
            resolved
        } catch (unsupported: AgentModelResolutionException) {
            val error = unsupported.error.copy(sessionId = sessionId)
            sink.emit(AgentEvent.Failed(sessionId, error, clock()))
            return AgentResult(
                sessionId = sessionId,
                status = AgentStatus.FAILED,
                summary = error.message,
                errors = listOf(error),
            )
        }
        sessions.update(sessionId) { it.withStatus(AgentStatus.RUNNING, clock()) }
        val job = coroutineContext[Job]
        if (job != null) jobs[sessionId] = job

        // The pause belongs to a delegated specialist: resume that child, then
        // continue the parent with the child's result. The parent must never be
        // resumed as though it had issued the specialist's tool.
        if (paused.delegated != null) {
            return resumeDelegatedPermission(
                session = session,
                sessionId = sessionId,
                paused = paused,
                mainConfig = mainConfig,
                approved = approved,
                sink = sink,
            )
        }

        return try {
            val result = withExecutionBudget(paused.budgetMillis) {
                mainAgent.run(
                    request = MainAgentRequest(
                        sessionId = sessionId,
                        task = session.task,
                        context = paused.context,
                        modelConfig = mainConfig,
                        contextBudget = paused.contextBudget,
                        resumeContext = paused.resumeContext,
                        resumePermission = ResumedPermission(
                            toolName = paused.pending.toolName,
                            arguments = paused.pending.arguments,
                            reason = paused.pending.reason,
                            toolCallId = paused.pending.toolCallId,
                            approved = approved,
                            // The whole assistant message travels with the decision, so the
                            // resumed run can finish its remaining calls rather than
                            // abandoning the ones the model asked for in the same turn.
                            batch = paused.pending.batchOrSelf,
                            pendingIndex = paused.pending.pendingIndex,
                        ),
                        promptVariables = paused.promptVariables,
                        requiresWorkspace = paused.requiresWorkspace,
                        skillIds = paused.skillIds,
                    ),
                    sink = trackingSink(sink),
                    subAgentInvoker = SubAgentInvoker { child ->
                        runSubAgent(child, modelConfig, sink) { isCancelled(sessionId) || isCancelled(child.sessionId) }
                    },
                    onCancelled = { isCancelled(sessionId) },
                )
            }
            rememberTurn(sessionId, session.task.prompt, result)
            sessions.update(sessionId) { it.withStatus(result.status, clock()) }
            rememberPause(
                sessionId = sessionId,
                result = result,
                context = paused.context,
                modelConfig = paused.modelConfig,
                budgetMillis = paused.budgetMillis,
                contextBudget = paused.contextBudget,
                promptVariables = paused.promptVariables,
                requiresWorkspace = paused.requiresWorkspace,
                skillIds = paused.skillIds,
            )
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

    /**
     * Records the model an execution actually resolved to.
     *
     * Emitted for every resolution — main, permission resume and each specialist —
     * so the event stream always names the role, provider family/protocol, model,
     * connection identity and whether the selection was an explicit assignment or
     * policy-derived. A substitution can therefore never be hidden; the event and
     * log carry no credential, endpoint or request body.
     *
     * The role's *saved* assignment connection is reported beside the resolved one.
     * The two differ legitimately: an assignment that names no connection resolves by
     * provider family, and one that names a connection resolves to that exact
     * connection. Logging both is what makes a stale assignment (one that names a
     * connection the runtime can no longer address) distinguishable from a
     * family-scoped one that legitimately followed a replacement connection — and
     * neither value is a secret.
     */
    private fun emitModelSelected(
        sink: AgentEventSink,
        sessionId: String,
        role: AgentRole,
        config: ModelConfig,
        explicit: Boolean,
    ) {
        // The connection the role's saved assignment names, when it names one; null for
        // a family-scoped assignment (and for a policy default). Read from the same
        // live mapping the resolution read, so it describes this run.
        val assignedConnectionId = modelResolver.preference(role)?.connectionId
            ?.takeIf { it.isNotBlank() }
        sink.emit(
            AgentEvent.ModelSelected(
                sessionId = sessionId,
                role = role,
                providerId = config.providerId,
                modelId = config.model,
                connectionId = config.connectionId,
                assignedConnectionId = assignedConnectionId,
                explicit = explicit,
                timestampMillis = clock(),
            ),
        )
        logger.info(
            "Model selected",
            mapOf(
                "sessionId" to sessionId,
                "role" to role.name,
                "provider" to config.providerId,
                "model" to config.model,
                // The connection actually resolved, and the connection the assignment
                // names (null when it names none). Identifiers only — never a credential
                // or an endpoint.
                "connection" to config.connectionId,
                "assignedConnection" to assignedConnectionId,
                "selection" to if (explicit) "explicit" else "policy",
            ),
        )
    }

    override fun session(id: String): AgentSession? =
        history?.conversation(id)?.session ?: sessions.find(id)

    override fun sessions(workspaceId: String): List<AgentSession> {
        val stored = history?.conversations(workspaceId)?.map { it.session }
        return stored ?: sessions.all().filter { it.workspaceId == workspaceId }
    }

    override fun history(): ConversationHistory? = history

    override fun cancel(sessionId: String): Boolean {
        cancellations[sessionId] = true
        jobs[sessionId]?.cancel()
        // Cancelling a parent that is parked on a specialist's permission also
        // cancels that child and drops the pause, so no WAITING_FOR_PERMISSION
        // state is left behind for a run that will never resume.
        val paused = pausedPermissions.remove(sessionId)
        paused?.delegated?.let { delegated ->
            val childId = delegated.childSessionId
            pausedChildren.remove(childId)
            cancellations[childId] = true
            jobs[childId]?.cancel()
            sessions.update(childId) { it.withStatus(AgentStatus.CANCELLED, clock()) }
            history?.markStatus(childId, AgentStatus.CANCELLED)
        }
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
        // Each child role resolves its own config from the active model and the
        // role mapping before it is invoked; the session records the same one.
        val agent = specialized.get(request.role)
        val childConfig = try {
            if (agent == null) {
                // An unknown role keeps the caller's config; nothing is resolved here.
                modelConfig
            } else {
                val resolution = modelResolver.resolveForRole(agent.definition, modelConfig)
                val resolved = resolution.eligibleConfigOrThrow()
                emitModelSelected(sink, request.sessionId, request.role, resolved, resolution.explicit)
                resolved
            }
        } catch (unsupported: AgentModelResolutionException) {
            val error = unsupported.error.copy(sessionId = request.sessionId)
            sink.emit(AgentEvent.Failed(request.sessionId, error, clock()))
            sessions.save(
                AgentSession(
                    id = request.sessionId,
                    parentSessionId = request.parentSessionId,
                    role = request.role,
                    status = AgentStatus.FAILED,
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
                ),
            )
            return SubAgentResult(
                sessionId = request.sessionId,
                role = request.role,
                status = AgentStatus.FAILED,
                summary = error.message,
                errors = listOf(error),
            )
        }
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
                modelProviderId = childConfig.providerId,
                modelId = childConfig.model,
            ),
        )
        history?.ensureSession(
            sessionId = request.sessionId,
            workspaceId = request.workspaceId,
            role = request.role,
            parentSessionId = request.parentSessionId,
            modelProviderId = childConfig.providerId,
            modelId = childConfig.model,
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

        val result = if (agent == null) {
            unknownSubAgent(request)
        } else {
            try {
                // A sub-agent gets its own, independent budget: a long delegated
                // task is not cut short by the Main Agent's remaining time.
                coroutineScope {
                    withExecutionBudget(timeouts.subAgentTaskMillis) {
                        agent.run(request, childConfig, childPermissionFilter(sink), onCancelled)
                    }
                }
            } catch (cancelled: CancellationException) {
                // The run was stopped while this specialist was working. The child has
                // not finished and has not failed — it was cancelled — so it is recorded
                // as cancelled instead of being left behind as a running specialist, and
                // the cancellation still travels up to end the parent run.
                sessions.update(request.sessionId) { it.withStatus(AgentStatus.CANCELLED, clock()) }
                history?.markStatus(request.sessionId, AgentStatus.CANCELLED)
                throw cancelled
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
        // A parked specialist is remembered whole — its original request and the
        // model config it already resolved — so a later decision resumes THIS
        // child with the same identity, provider and model.
        if (result.status == AgentStatus.WAITING_FOR_PERMISSION && result.pendingPermission != null) {
            pausedChildren[request.sessionId] = PausedChild(request = request, config = childConfig)
        }
        persistChildOutcome(request, result)
        return result
    }

    /**
     * Records a specialist's turn in its own transcript and its outcome in its
     * parent's, once the child has actually reached a terminal state.
     *
     * A specialist that merely parked for permission has neither finished nor
     * failed — the delegation policy and the loop both treat that pause as a pause —
     * so recording it as a failed delegation would leave the parent's transcript
     * claiming a specialist failed while the same specialist later completed. The
     * record is therefore written when the child ends: straight away for a child that
     * ends live, and when a resumed child ends. A re-park writes nothing.
     */
    private fun persistChildOutcome(request: SubAgentRequest, result: SubAgentResult) {
        val store = history ?: return
        // The child's own session state follows every outcome, including a pause: the
        // conversation store is what `session(childId)` and the session listing report.
        store.applyTurn(request.sessionId, request.task, result.toAgentResult())
        if (!result.status.isTerminal) return
        store.recordSubAgent(
            sessionId = request.parentSessionId,
            childSessionId = request.sessionId,
            role = request.role,
            summary = result.summary,
            success = result.status == AgentStatus.COMPLETED,
        )
    }

    /**
     * Resumes a delegated specialist that parked for permission, then continues the
     * parent run with the child's result. The decision belongs to the child, so the
     * child is re-entered with its own parked call and conversation; the parent is
     * resumed only once the child finishes, and never as though it had issued the
     * specialist's tool.
     */
    private suspend fun resumeDelegatedPermission(
        session: AgentSession,
        sessionId: String,
        paused: PausedRun,
        mainConfig: ModelConfig,
        approved: Boolean,
        sink: AgentEventSink,
    ): AgentResult {
        val delegated = requireNotNull(paused.delegated)
        val task = session.task.prompt
        val child = pausedChildren.remove(delegated.childSessionId)
        val childAgent = specialized.get(delegated.childRole)
        val resumedChild = if (child == null || childAgent == null) {
            SubAgentResult(
                sessionId = delegated.childSessionId,
                role = delegated.childRole,
                status = AgentStatus.FAILED,
                summary = "The paused specialist is no longer available",
                errors = listOf(
                    AgentError(
                        code = AgentErrorCode.SUB_AGENT_FAILURE,
                        message = "The paused specialist is no longer available",
                        role = delegated.childRole,
                        sessionId = delegated.childSessionId,
                    ),
                ),
            )
        } else {
            // The resumed child's outcome is persisted exactly like a child that ended
            // live: only now has this specialist actually finished, so this is where its
            // turn and its parent-visible result are recorded.
            resumeChild(childAgent, child, delegated, sessionId, approved, sink)
                .also { outcome -> persistChildOutcome(child.request, outcome) }
        }

        // A later sibling of the specialist asked for approval too: park the parent
        // again so the next decision resumes the SAME child, never the parent.
        if (resumedChild.status == AgentStatus.WAITING_FOR_PERMISSION && resumedChild.pendingPermission != null) {
            if (child != null) pausedChildren[delegated.childSessionId] = child
            val reParked = delegated.copy(
                pendingPermission = resumedChild.pendingPermission,
                childResumeContext = resumedChild.resumeContext,
            )
            sessions.update(sessionId) { it.withStatus(AgentStatus.WAITING_FOR_PERMISSION, clock()) }
            sessions.update(delegated.childSessionId) { it.withStatus(AgentStatus.WAITING_FOR_PERMISSION, clock()) }
            val display = displayPending(reParked)
            pausedPermissions[sessionId] = PausedRun(
                pending = display,
                resumeContext = paused.resumeContext,
                context = paused.context,
                modelConfig = paused.modelConfig,
                budgetMillis = paused.budgetMillis,
                contextBudget = paused.contextBudget,
                promptVariables = paused.promptVariables,
                requiresWorkspace = paused.requiresWorkspace,
                skillIds = paused.skillIds,
                delegated = reParked,
            )
            sink.emit(
                AgentEvent.PermissionRequested(
                    sessionId = sessionId,
                    pending = display,
                    timestampMillis = clock(),
                ),
            )
            return AgentResult(
                sessionId = sessionId,
                status = AgentStatus.WAITING_FOR_PERMISSION,
                summary = "Waiting for approval to run '${reParked.pendingPermission.toolName}' " +
                    "(${reParked.childRole.name})",
                role = session.role,
                pendingPermission = display,
                resumeContext = paused.resumeContext,
                delegatedPermissionPause = reParked,
            )
        }

        // The child finished: continue the parent's own model loop with the child's
        // result as the delegate tool result.
        val job = coroutineContext[Job]
        if (job != null) jobs[sessionId] = job
        return try {
            val result = withExecutionBudget(paused.budgetMillis) {
                mainAgent.run(
                    request = MainAgentRequest(
                        sessionId = sessionId,
                        task = session.task,
                        context = paused.context,
                        modelConfig = mainConfig,
                        contextBudget = paused.contextBudget,
                        resumeContext = paused.resumeContext,
                        resumePermission = ResumedPermission(
                            toolName = delegated.pendingPermission.toolName,
                            arguments = delegated.pendingPermission.arguments,
                            reason = delegated.pendingPermission.reason,
                            toolCallId = delegated.pendingPermission.toolCallId,
                            approved = approved,
                            // The parent's own batch, with the delegate call already
                            // satisfied, so only the siblings that never ran dispatch.
                            batch = delegated.parentBatchForResume,
                            pendingIndex = delegated.parentPendingIndex,
                        ),
                        resolvedDelegation = ResolvedDelegation(
                            toolCallId = delegated.delegateToolCallId,
                            result = resumedChild,
                            task = child?.request?.task.orEmpty(),
                        ),
                        promptVariables = paused.promptVariables,
                        requiresWorkspace = paused.requiresWorkspace,
                        skillIds = paused.skillIds,
                    ),
                    sink = trackingSink(sink),
                    subAgentInvoker = SubAgentInvoker { childRequest ->
                        runSubAgent(childRequest, paused.modelConfig, sink) {
                            isCancelled(sessionId) || isCancelled(childRequest.sessionId)
                        }
                    },
                    onCancelled = { isCancelled(sessionId) },
                )
            }
            rememberTurn(sessionId, task, result)
            sessions.update(sessionId) { it.withStatus(result.status, clock()) }
            rememberPause(
                sessionId = sessionId,
                result = result,
                context = paused.context,
                modelConfig = paused.modelConfig,
                budgetMillis = paused.budgetMillis,
                contextBudget = paused.contextBudget,
                promptVariables = paused.promptVariables,
                requiresWorkspace = paused.requiresWorkspace,
                skillIds = paused.skillIds,
            )
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
                AgentResult(sessionId = sessionId, status = AgentStatus.CANCELLED, summary = "Cancelled")
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

    /** Re-enters the parked specialist with its own parked call and conversation. */
    private suspend fun resumeChild(
        agent: SpecializedAgent,
        child: PausedChild,
        delegated: DelegatedPermissionPause,
        parentSessionId: String,
        approved: Boolean,
        sink: AgentEventSink,
    ): SubAgentResult {
        // The child's provider/connection/model are reused exactly as resolved when
        // the delegation started; they are never re-resolved here.
        val request = child.request.copy(
            resumeContext = delegated.childResumeContext,
            resumePermission = ResumedPermission(
                toolName = delegated.pendingPermission.toolName,
                arguments = delegated.pendingPermission.arguments,
                reason = delegated.pendingPermission.reason,
                toolCallId = delegated.pendingPermission.toolCallId,
                approved = approved,
                batch = delegated.pendingPermission.batchOrSelf,
                pendingIndex = delegated.pendingPermission.pendingIndex,
            ),
        )
        sessions.update(child.request.sessionId) { it.withStatus(AgentStatus.RUNNING, clock()) }
        val result = try {
            coroutineScope {
                withExecutionBudget(timeouts.subAgentTaskMillis) {
                    agent.run(request, child.config, childPermissionFilter(sink)) {
                        isCancelled(parentSessionId) || isCancelled(child.request.sessionId)
                    }
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            val error = AgentError(
                code = AgentErrorCode.TIMEOUT,
                message = "Sub-agent '${child.request.role.name}' exceeded its " +
                    "${timeouts.subAgentTaskMillis}ms budget",
                role = child.request.role,
                sessionId = child.request.sessionId,
                cause = timeout,
                details = mapOf("stage" to "sub_agent"),
            )
            sink.emit(AgentEvent.Failed(child.request.sessionId, error, clock()))
            SubAgentResult(
                sessionId = child.request.sessionId,
                role = child.request.role,
                status = AgentStatus.FAILED,
                summary = error.message.orEmpty(),
                errors = listOf(error),
            )
        } catch (cancelled: CancellationException) {
            // Same as a live child: a stopped specialist is cancelled, never left
            // registered as one that is still running.
            sessions.update(child.request.sessionId) { it.withStatus(AgentStatus.CANCELLED, clock()) }
            history?.markStatus(child.request.sessionId, AgentStatus.CANCELLED)
            throw cancelled
        } catch (error: Throwable) {
            val agentError = AgentError(
                code = AgentErrorCode.SUB_AGENT_FAILURE,
                message = error.message ?: "Sub-agent '${child.request.role.name}' failed",
                role = child.request.role,
                sessionId = child.request.sessionId,
                cause = error,
            )
            sink.emit(AgentEvent.Failed(child.request.sessionId, agentError, clock()))
            SubAgentResult(
                sessionId = child.request.sessionId,
                role = child.request.role,
                status = AgentStatus.FAILED,
                summary = agentError.message.orEmpty(),
                errors = listOf(agentError),
            )
        }
        sessions.update(child.request.sessionId) { it.withStatus(result.status, clock()) }
        return result
    }

    /**
     * Records a run parked in WAITING_FOR_PERMISSION so the user's decision can
     * re-enter it. A pause caused by a delegated specialist carries that child's
     * state too, so the decision resumes the child rather than the parent.
     */
    private fun rememberPause(
        sessionId: String,
        result: AgentResult,
        context: String,
        modelConfig: ModelConfig,
        budgetMillis: Long,
        contextBudget: ContextBudget,
        promptVariables: PromptVariables,
        requiresWorkspace: Boolean,
        skillIds: Set<String>?,
    ) {
        if (result.status != AgentStatus.WAITING_FOR_PERMISSION || result.pendingPermission == null) return
        pausedPermissions[sessionId] = PausedRun(
            pending = result.pendingPermission,
            resumeContext = result.resumeContext,
            context = context,
            modelConfig = modelConfig,
            budgetMillis = budgetMillis,
            contextBudget = contextBudget,
            promptVariables = promptVariables,
            requiresWorkspace = requiresWorkspace,
            skillIds = skillIds,
            delegated = result.delegatedPermissionPause,
        )
    }

    /**
     * A child's own permission pause is surfaced by the parent run, which owns the
     * resumable session. Forwarding the child's event too would offer a prompt whose
     * session id cannot be resumed, so it is filtered out here.
     */
    private fun childPermissionFilter(sink: AgentEventSink): AgentEventSink = AgentEventSink { event ->
        if (event !is AgentEvent.PermissionRequested) sink.emit(event)
    }

    /** The permission as shown to the user, naming the specialist that requested it. */
    private fun displayPending(pause: DelegatedPermissionPause): com.agentx.app.agent.domain.PendingPermission =
        pause.pendingPermission.copy(
            reason = "${pause.pendingPermission.reason} (requested by ${pause.childRole.name})",
        )

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
                attachments = request.attachments,
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
        val asked = prompt.trim()
        // This turn's user message is recorded once — but "once" is per turn, not per
        // session. An occurrence of this prompt that no answer follows yet *is* this
        // turn's message, already recorded: that is the resume case, where the pause
        // recorded it and resuming must not add a second. Any earlier identical prompt
        // has since been answered, so asking the same thing again is a new turn and is
        // recorded as one instead of being mistaken for the previous ask.
        val before = store.conversation(sessionId)
        val lastAnswer = before?.messages?.indexOfLast { it.isAnswer() } ?: -1
        val alreadyOpen = before?.messages.orEmpty()
            .drop(lastAnswer + 1)
            .any { message -> message.role == MessageRole.USER && message.content.text == asked }
        if (!alreadyOpen && asked.isNotBlank()) {
            store.recordUser(sessionId, asked)
        }
        val messages = store.conversation(sessionId)?.messages.orEmpty()
        // Has *this* turn been answered? Asked of the session as a whole — "is there any
        // assistant message" — the first turn's reply answers every later one: the
        // second turn's answer, and every later failure and cancellation, would never
        // reach the transcript. The turn is answered when an answer follows its own
        // user message.
        val thisTurn = messages.indexOfLast { it.role == MessageRole.USER && it.content.text == asked }
        val answered = thisTurn >= 0 && messages.drop(thisTurn + 1).any { it.isAnswer() }
        // A run that has not finished has no answer to store. Persisting the pause text
        // as the assistant's reply would leave it standing in for the real answer — the
        // transcript would say "waiting for approval" forever, because a completed resume
        // finds an assistant message already there and never records its summary.
        if (!answered && result.summary.isNotBlank() && result.status.isTerminal) {
            val ended = result.status == AgentStatus.FAILED || result.status == AgentStatus.CANCELLED
            val role = if (ended) MessageRole.ERROR else MessageRole.ASSISTANT
            val status = if (ended) {
                com.agentx.app.agent.conversation.MessageStatus.ERROR
            } else {
                com.agentx.app.agent.conversation.MessageStatus.COMPLETED
            }
            // What is stored is what this run reported. Its error list may be non-empty
            // even though it succeeded — a tool call that failed and was recovered from,
            // or a specialist that failed while the run carried on — and those errors
            // describe parts of the turn, not the turn's answer. Storing the first of
            // them would replace the reply the user was given, and the next run would
            // rebuild its context from that error text instead of the outcome.
            val failure = if (ended) result.errors.firstOrNull() else null
            store.append(
                sessionId,
                com.agentx.app.agent.conversation.ConversationMessage(
                    id = AgentIds.newId(),
                    sessionId = sessionId,
                    role = role,
                    content = com.agentx.app.agent.conversation.MessageContent(
                        text = failure?.message ?: result.summary,
                        errorCode = failure?.code?.name,
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

    /** A message that closes a turn: the assistant's reply, or the failure in its place. */
    private fun ConversationMessage.isAnswer(): Boolean =
        role == MessageRole.ASSISTANT || role == MessageRole.ERROR

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

    /**
     * Wraps [sink] so a successful model fallback updates the run's recorded
     * provider/model through the existing session and conversation persistence,
     * without adding a second store. Only operational identifiers are written —
     * never a prompt, a request body or a credential.
     */
    private fun trackingSink(sink: AgentEventSink): AgentEventSink = AgentEventSink { event ->
        if (event is AgentEvent.ModelFallbackSucceeded) {
            sessions.update(event.sessionId) { session ->
                if (session.modelProviderId == event.toProviderId && session.modelId == event.toModelId) {
                    session
                } else {
                    session.copy(
                        modelProviderId = event.toProviderId,
                        modelId = event.toModelId,
                        updatedAtMillis = clock(),
                    )
                }
            }
            history?.let { store ->
                if (store.conversation(event.sessionId) != null) {
                    store.ensureSession(
                        sessionId = event.sessionId,
                        workspaceId = null,
                        modelProviderId = event.toProviderId,
                        modelId = event.toModelId,
                    )
                }
            }
        }
        sink.emit(event)
    }
}
