package com.agentx.app.agent.runtime

import com.agentx.app.agent.domain.AgentDefinition
import com.agentx.app.agent.domain.AgentError
import com.agentx.app.agent.domain.AgentErrorCode
import com.agentx.app.agent.domain.AgentEvent
import com.agentx.app.agent.domain.AgentEventSink
import com.agentx.app.agent.domain.AgentResult
import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.agent.domain.AgentStatus
import com.agentx.app.agent.domain.AgentPlan
import com.agentx.app.agent.domain.AgentStep
import com.agentx.app.agent.domain.AgentStepStats
import com.agentx.app.agent.domain.DelegatedPermissionPause
import com.agentx.app.agent.domain.PermissionLevel
import com.agentx.app.agent.domain.PendingPermission
import com.agentx.app.agent.domain.PendingToolCall
import com.agentx.app.agent.domain.ResumedPermission
import com.agentx.app.agent.domain.SubAgentRequest
import com.agentx.app.agent.domain.SubAgentResult
import com.agentx.app.agent.domain.ToolActionRecord
import com.agentx.app.agent.model.ModelFallback
import com.agentx.app.agent.prompt.PromptManager
import com.agentx.app.agent.prompt.PromptVariables
import com.agentx.app.agent.protocol.AgentProtocol
import com.agentx.app.agent.tools.AgentToolBridge
import com.agentx.app.agent.tools.intOrNull
import com.agentx.app.agent.tools.stringOrNull
import com.agentx.app.context.ContextBudget
import com.agentx.app.context.ModelContextBudget
import com.agentx.app.context.RunContext
import com.agentx.app.context.RunContextFactory
import com.agentx.app.context.SkillContext
import com.agentx.app.context.SkillContextResolver
import com.agentx.app.context.ToolContextStatus
import com.agentx.app.model.ContentToolCallParser
import com.agentx.app.model.ModelConfig
import com.agentx.app.model.ModelGateway
import com.agentx.app.model.ModelMessage
import com.agentx.app.model.ModelProviderError
import com.agentx.app.model.ModelProviderErrorCode
import com.agentx.app.model.ModelRequest
import com.agentx.app.model.ModelResponse
import com.agentx.app.model.ModelStreamEvent
import com.agentx.app.model.ModelToolCall
import com.agentx.app.model.ModelToolSpec
import com.agentx.app.model.json.JsonValue
import com.agentx.app.agent.domain.toToolGrants
import com.agentx.app.agent.delegation.DelegationDecision
import com.agentx.app.agent.delegation.DelegationPolicy
import com.agentx.app.agent.delegation.DelegationRecord
import com.agentx.app.agent.delegation.DelegationState
import com.agentx.app.core.logging.ForgeLogger
import com.agentx.app.core.logging.ForgeLoggers
import com.agentx.app.core.logging.LogLevel
import com.agentx.app.core.timeout.AgentTimeouts
import com.agentx.app.core.timeout.withExecutionBudget
import com.agentx.app.tools.ToolApproval
import com.agentx.app.tools.ToolErrorCode
import com.agentx.app.tools.ToolExecutionContext
import com.agentx.app.tools.ToolExecutionError
import com.agentx.app.tools.ToolInput
import com.agentx.app.tools.ToolResult
import com.agentx.app.tools.ToolRouter
import com.agentx.app.tools.ToolTimeouts
import kotlinx.coroutines.TimeoutCancellationException
import kotlin.coroutines.cancellation.CancellationException

fun interface SubAgentInvoker {
    suspend fun invoke(request: SubAgentRequest): SubAgentResult
}

data class AgentLoopRequest(
    val sessionId: String,
    val parentSessionId: String?,
    val definition: AgentDefinition,
    val allowedTools: List<String>,
    val permissionLevel: PermissionLevel,
    val maxSteps: Int,
    val userPrompt: String,
    val objective: String?,
    val scopedContext: String,
    val workspaceId: String?,
    val modelConfig: ModelConfig,
    /** Limits applied to the run's conversation and tool-result context. */
    val contextBudget: ContextBudget = ContextBudget.DEFAULT,
    /** Conversation snapshot to restore when resuming a permission pause. */
    val resumeContext: List<ModelMessage> = emptyList(),
    /** Tool call awaiting approval with the user's decision; when present the loop is resuming. */
    val resumePermission: ResumedPermission? = null,
    /**
     * A delegation that finished while the parent was parked for the specialist's
     * permission. When present the loop records it as the delegate tool result and
     * continues its own model loop — the parent is never resumed as though it had
     * issued the specialist's tool.
     */
    val resolvedDelegation: ResolvedDelegation? = null,
    /** Template variables used to resolve this run's system prompt. */
    val promptVariables: PromptVariables = PromptVariables.EMPTY,
    /**
     * Whether this turn is about the project, so the workspace may be inspected
     * on the agent's own initiative.
     *
     * Defaults to `true` so a caller that does not classify its turns keeps the
     * previous, permissive behaviour. A conversational turn passes `false`, and
     * the system prompt then says so plainly instead of ordering an inspection
     * the user did not ask for. It never widens or narrows tool permissions.
     */
    val requiresWorkspace: Boolean = true,
    /**
     * Delegation accounting inherited from the parent. The Main Agent starts a
     * run at depth 0; a specialist is handed depth+1 so the authoritative
     * [com.agentx.app.agent.delegation.DelegationPolicy] limits apply across the
     * whole run, not just one level.
     */
    val delegationState: DelegationState = DelegationState(),
)

/**
 * A delegation that finished while the parent was parked for the specialist's
 * permission. The parent loop turns it into the delegate tool result and then
 * continues its model loop, so the child is never resumed as if it were the
 * parent and the delegation is never run twice.
 */
data class ResolvedDelegation(
    /** The parent's delegate tool call whose result this is. */
    val toolCallId: String,
    /** The specialist's final result. */
    val result: SubAgentResult,
    /** The delegated task, recorded so delegation accounting stays meaningful. */
    val task: String = "",
)

/** Outcome of dispatching one tool call requested by the model. */
internal sealed interface ToolOutcome {
    /** The tool ran (or failed safely); [resultText] is fed back to the model. */
    data class Completed(
        val resultText: String,
        /** Related path, when the tool reported one. */
        val path: String? = null,
        val success: Boolean = false,
    ) : ToolOutcome

    /** The permission layer requires the user's decision; the loop pauses. */
    data class Paused(val pending: PendingPermission) : ToolOutcome
}

/**
 * The agent execution loop: model → decision → tool / sub-agent → result →
 * model again → final answer, with explicit terminal states and a hard step
 * budget. The model never executes anything directly: every tool call goes
 * through the [ToolRouter] (scope check → permission → executor) and every
 * delegation through the [SubAgentInvoker].
 */
class AgentLoop(
    private val gateway: ModelGateway,
    private val toolRouter: ToolRouter,
    private val bridge: AgentToolBridge,
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** Supplies the conversation/tool-result context for one run. */
    private val runContexts: RunContextFactory = RunContextFactory.default(),
    /** Resolves the active system prompt for a role; defaults when absent. */
    private val prompts: PromptManager? = null,
    /** Resolves the enabled skills for a role as structured context. */
    private val skillContext: SkillContextResolver? = null,
    /**
     * Central, per-operation execution budgets. A model request and a shell
     * command do not share a value: see [AgentTimeouts].
     */
    private val timeouts: AgentTimeouts = AgentTimeouts.DEFAULT,
    private val logger: ForgeLogger = ForgeLoggers.create(LogLevel.INFO, baseFields = mapOf("layer" to "agent")),
    /**
     * Optional controlled model fallback. When absent (the default) a failed
     * model request is returned unchanged, exactly as before this phase.
     */
    private val modelFallback: ModelFallback? = null,
    /**
     * The declared context window of a model, read from descriptor metadata.
     *
     * Injected rather than looked up here so the loop stays free of capability
     * resolution: the assembled agent passes the authoritative profile lookup, and a
     * test passes whatever capacity it wants to exercise. Returning `null` means "not
     * declared", which is budgeted conservatively — never as unlimited. The default
     * reads the capabilities already carried on the resolved configuration.
     */
    private val windowTokensOf: (ModelConfig) -> Int? = { it.capabilities?.contextWindowTokens },
) {

    suspend fun run(
        request: AgentLoopRequest,
        sink: AgentEventSink,
        subAgentInvoker: SubAgentInvoker? = null,
        onCancelled: () -> Boolean = { false },
    ): AgentResult {
        val startedAt = clock()
        logger.info(
            "Agent loop starting",
            mapOf(
                "sessionId" to request.sessionId,
                "role" to request.definition.role.name,
                "workspaceId" to request.workspaceId,
                "hasScopedContext" to request.scopedContext.isNotBlank(),
                "scopedContextChars" to request.scopedContext.length,
                "allowedTools" to request.allowedTools.size,
            ),
        )
        // Resolved once per run, never per loop iteration: the system instruction
        // must stay identical for every model call of this run.
        val basePrompt = resolveBasePrompt(request)
        val skillContext = resolveSkillContext(request)
        val systemPrompt = buildSystemPrompt(
            request = request,
            basePrompt = basePrompt.text,
            skillBlock = skillContext.rendered,
        )
        logPromptAssembly(request, basePrompt, skillContext, systemPrompt)
        val userPrompt = buildUserPrompt(request)
        // The role's own tool schemas, already scoped by authorization. They are sent
        // on every call, so they are part of the request's cost — never free.
        val toolSpecs = bridge.toModelSpecs(request.allowedTools)
        // The context ceiling comes from the model that will read it, not from a
        // global default. Everything that is not ranked context is measured here and
        // subtracted, output is reserved, and only what is left is offered to the
        // engine. A model that does not declare a window gets the conservative
        // default rather than an unlimited one.
        val contextPlan = ModelContextBudget.forModel(
            windowTokens = windowTokensOf(request.modelConfig),
            maxOutputTokens = request.modelConfig.generation.maxOutputTokens,
            overheadChars = systemPrompt.length + userPrompt.length + toolSchemaChars(toolSpecs),
            base = request.contextBudget,
        )
        logContextBudget(request, contextPlan)
        // Conversation and tool-result context are built by the Context Engine;
        // the loop only drives them. [context.messages] is the budgeted,
        // model-ready form of that context.
        val context = runContexts.create(request.sessionId, contextPlan.budget)
        if (request.resumeContext.isNotEmpty()) {
            // Resuming a run that paused for a permission: restore the saved
            // conversation and keep its tool/call state, but refresh the system
            // instruction so a Main Agent prompt or skill change made while the
            // run was paused reaches the next model request. Exactly one system
            // message survives, still first.
            context.restore(request.resumeContext)
            context.updateSystemPrompt(systemPrompt)
        } else {
            context.start(systemPrompt, buildUserPrompt(request))
        }

        val toolActions = mutableListOf<ToolActionRecord>()
        val findings = mutableListOf<String>()
        val filesInspected = mutableListOf<String>()
        val filesChanged = mutableListOf<String>()
        val errors = mutableListOf<AgentError>()
        val output = StringBuilder()
        var modelCalls = 0
        var toolCalls = 0
        var subAgentCalls = 0
        var finished: AgentResult? = null
        // The delegation history for this run, updated after every delegation so
        // the authoritative policy sees depth, counts and redundancy as they grow.
        var delegationState = request.delegationState

        // The role and its policy are handed to the router, so it re-decides every
        // call instead of trusting the list this loop passed in.
        val scopedRouter = ScopedToolRouter(
            inner = toolRouter,
            role = request.definition.role,
            allowedTools = request.allowedTools.toSet(),
            permissionLevel = request.permissionLevel,
            definitionOf = { name -> bridge.definitionsFor(listOf(name)).firstOrNull() },
            toolAllowed = { name ->
                val definition = bridge.definitionsFor(listOf(name)).firstOrNull()
                definition == null || request.permissionLevel.allows(definition.capabilities)
            },
        )

        val executionContext = ToolExecutionContext(
            sessionId = request.sessionId,
            agentId = request.definition.role.name,
            workspaceId = request.workspaceId,
            grantedPermissions = request.permissionLevel.toToolGrants(),
        )

        sink.emit(
            AgentEvent.Thinking(
                sessionId = request.sessionId,
                role = request.definition.role,
                detail = "Starting ${request.definition.name}",
                timestampMillis = clock(),
            ),
        )

        // A delegation that finished while the parent was parked for the specialist's
        // permission: the child has now completed, so its result becomes the delegate
        // tool result here and the parent continues its own model loop. The parent is
        // never resumed as if it had issued the specialist's own tool.
        request.resolvedDelegation?.let { resolved ->
            val outcome = consumeSubAgentResult(
                request = request,
                role = resolved.result.role,
                result = resolved.result,
                task = resolved.task,
                sink = sink,
                findings = findings,
                filesInspected = filesInspected,
                filesChanged = filesChanged,
                toolActions = toolActions,
                errors = errors,
                state = delegationState,
            )
            delegationState = outcome.state
            context.addToolResult(
                callId = resolved.toolCallId,
                toolName = AgentProtocol.DELEGATE_TOOL,
                content = outcome.resultText,
                path = outcome.path,
                status = statusOf(outcome.success),
            )
        }

        // Resume continues the SAME assistant message: the parked call gets the user's
        // decision, and every sibling that had not run yet is dispatched in its original
        // order. Only after the whole message has results may the model be asked again —
        // otherwise the transcript would carry an assistant message with more tool calls
        // than tool results, which is not a valid request.
        request.resumePermission?.let { resumed ->
            sink.emit(
                AgentEvent.PermissionResolved(
                    sessionId = request.sessionId,
                    toolName = resumed.toolName,
                    approved = resumed.approved,
                    timestampMillis = clock(),
                ),
            )
            val batch = resumed.batch.ifEmpty {
                listOf(
                    PendingToolCall(
                        toolCallId = resumed.toolCallId,
                        toolName = resumed.toolName,
                        arguments = resumed.arguments,
                    ),
                )
            }
            val toDispatch = batch
                .filter { !it.completed && it.index >= resumed.pendingIndex }
                .sortedBy { it.index }
            for (entry in toDispatch) {
                if (onCancelled()) {
                    return cancelled(
                        request = request,
                        startedAt = startedAt,
                        stepIndex = 0,
                        modelCalls = modelCalls,
                        toolCalls = toolCalls,
                        subAgentCalls = subAgentCalls,
                        output = output,
                        findings = findings,
                        filesInspected = filesInspected,
                        filesChanged = filesChanged,
                        toolActions = toolActions,
                        errors = errors,
                        sink = sink,
                        delegations = delegationState.records,
                    )
                }
                val call = ModelToolCall(id = entry.toolCallId, name = entry.toolName, arguments = entry.arguments)
                sink.emit(
                    AgentEvent.ToolRequested(
                        sessionId = request.sessionId,
                        toolCallId = call.id,
                        toolName = call.name,
                        role = request.definition.role,
                        arguments = call.arguments,
                        timestampMillis = clock(),
                    ),
                )
                // Only the call the user actually decided on is forced. A sibling that
                // was never asked about goes through the ordinary permission path, so a
                // second ASK parks the run again instead of running unapproved work.
                val decided = entry.index == resumed.pendingIndex
                when {
                    call.name == AgentProtocol.FINISH_TOOL -> {
                        finished = finishFromCall(
                            request = request,
                            call = call,
                            findings = findings,
                            filesInspected = filesInspected,
                            filesChanged = filesChanged,
                            toolActions = toolActions,
                            errors = errors,
                            fallback = output.toString().trim(),
                            stepStats = stats(startedAt, 0, request.maxSteps, modelCalls, toolCalls, subAgentCalls),
                        )
                    }

                    call.name == AgentProtocol.DELEGATE_TOOL -> {
                        subAgentCalls += 1
                        val delegated = handleDelegate(
                            request = request,
                            call = call,
                            invoker = subAgentInvoker,
                            sink = sink,
                            findings = findings,
                            filesInspected = filesInspected,
                            filesChanged = filesChanged,
                            toolActions = toolActions,
                            errors = errors,
                            state = delegationState,
                        )
                        delegationState = delegated.state
                        val pausedDelegation = delegated.paused
                        if (pausedDelegation != null) {
                            return parkedForDelegation(
                                request = request,
                                startedAt = startedAt,
                                stepIndex = 0,
                                modelCalls = modelCalls,
                                toolCalls = toolCalls,
                                subAgentCalls = subAgentCalls,
                                findings = findings,
                                filesInspected = filesInspected,
                                filesChanged = filesChanged,
                                toolActions = toolActions,
                                errors = errors,
                                delegated = pausedDelegation.copy(
                                    parentBatch = markCompleted(batch, pendingIndex = entry.index),
                                    parentPendingIndex = entry.index,
                                ),
                                context = context,
                                sink = sink,
                            )
                        }
                        context.addToolResult(
                            callId = call.id,
                            toolName = call.name,
                            content = delegated.resultText,
                            path = delegated.path,
                            status = statusOf(delegated.success),
                        )
                    }

                    else -> {
                        val outcome = if (decided) {
                            executeScopedTool(
                                request = request,
                                call = call,
                                router = scopedRouter,
                                context = executionContext,
                                sink = sink,
                                toolActions = toolActions,
                                filesInspected = filesInspected,
                                filesChanged = filesChanged,
                                errors = errors,
                                forcedApproval = resumed.approved,
                            )
                        } else {
                            handleTool(
                                request = request,
                                call = call,
                                router = scopedRouter,
                                context = executionContext,
                                sink = sink,
                                toolActions = toolActions,
                                filesInspected = filesInspected,
                                filesChanged = filesChanged,
                                errors = errors,
                            )
                        }
                        when (outcome) {
                            is ToolOutcome.Paused -> {
                                // A sibling needs its own decision: park again with the
                                // same batch and the new position, so the calls that still
                                // have no result are never dropped.
                                return parked(
                                    request = request,
                                    startedAt = startedAt,
                                    stepIndex = 0,
                                    modelCalls = modelCalls,
                                    toolCalls = toolCalls,
                                    subAgentCalls = subAgentCalls,
                                    findings = findings,
                                    filesInspected = filesInspected,
                                    filesChanged = filesChanged,
                                    toolActions = toolActions,
                                    errors = errors,
                                    pending = outcome.pending.copy(
                                        batch = markCompleted(batch, pendingIndex = entry.index),
                                        pendingIndex = entry.index,
                                    ),
                                    context = context,
                                    sink = sink,
                                )
                            }

                            is ToolOutcome.Completed -> {
                                // Counted only now, when the result actually exists: a
                                // call that parked has not produced anything yet.
                                toolCalls += 1
                                context.addToolResult(
                                    callId = call.id,
                                    toolName = call.name,
                                    content = outcome.resultText,
                                    path = outcome.path,
                                    status = statusOf(outcome.success),
                                )
                            }
                        }
                    }
                }
                if (finished != null) break
            }
            sink.emit(
                AgentEvent.StatsUpdated(
                    request.sessionId,
                    stats(startedAt, 0, request.maxSteps, modelCalls, toolCalls, subAgentCalls),
                    clock(),
                ),
            )
        }
        if (finished != null) return finished

        var stepIndex = 0
        while (stepIndex < request.maxSteps) {
            if (onCancelled()) {
                return cancelled(
                    request = request,
                    startedAt = startedAt,
                    stepIndex = stepIndex,
                    modelCalls = modelCalls,
                    toolCalls = toolCalls,
                    subAgentCalls = subAgentCalls,
                    output = output,
                    findings = findings,
                    filesInspected = filesInspected,
                    filesChanged = filesChanged,
                    toolActions = toolActions,
                    errors = errors,
                    sink = sink,
                    delegations = delegationState.records,
                )
            }

            stepIndex += 1
            // A real plan, emitted by the runtime rather than inferred by the UI: one
            // step per loop iteration, the finished ones behind it and the current one
            // active. Steps are concise task units for the operator — never model
            // reasoning, and never one event per token.
            sink.emit(
                AgentEvent.PlanUpdated(
                    request.sessionId,
                    planFor(request.definition.role, stepIndex, delegations = delegationState.records),
                    clock(),
                ),
            )
            sink.emit(
                AgentEvent.StatsUpdated(
                    request.sessionId,
                    stats(startedAt, stepIndex, request.maxSteps, modelCalls, toolCalls, subAgentCalls),
                    clock(),
                ),
            )

            val response = try {
                ContentToolCallParser.normalize(
                    complete(
                        request.modelConfig,
                        request.definition.role,
                        context.messages(),
                        toolSpecs,
                        sink,
                        request.sessionId,
                    ),
                )
            } catch (timeout: TimeoutCancellationException) {
                val agentError = modelTimeout(timeout, request)
                errors += agentError
                sink.emit(AgentEvent.Failed(request.sessionId, agentError, clock()))
                return AgentResult(
                    sessionId = request.sessionId,
                    status = AgentStatus.FAILED,
                    summary = agentError.message,
                    findings = findings.toList(),
                    filesInspected = filesInspected.toList(),
                    filesChanged = filesChanged.toList(),
                    toolActions = toolActions.toList(),
                    errors = errors.toList(),
                    role = request.definition.role,
                    stepStats = stats(startedAt, stepIndex, request.maxSteps, modelCalls, toolCalls, subAgentCalls),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val agentError = modelFailure(error, request)
                errors += agentError
                sink.emit(AgentEvent.Failed(request.sessionId, agentError, clock()))
                return AgentResult(
                    sessionId = request.sessionId,
                    status = AgentStatus.FAILED,
                    summary = agentError.message,
                    findings = findings.toList(),
                    filesInspected = filesInspected.toList(),
                    filesChanged = filesChanged.toList(),
                    toolActions = toolActions.toList(),
                    errors = errors.toList(),
                    role = request.definition.role,
                    stepStats = stats(startedAt, stepIndex, request.maxSteps, modelCalls, toolCalls, subAgentCalls),
                )
            }
            modelCalls += 1

            if (response.content.isNotBlank()) {
                output.append(response.content)
                if (!output.endsWith('\n') && response.toolCalls.isEmpty()) output.append('\n')
            }

            if (response.toolCalls.isEmpty()) {
                val summary = response.content.ifBlank { output.toString().trim() }.ifBlank { "No output" }
                finished = AgentResult(
                    sessionId = request.sessionId,
                    status = AgentStatus.COMPLETED,
                    summary = summary.trim(),
                    findings = findings.toList(),
                    filesInspected = filesInspected.toList(),
                    filesChanged = filesChanged.toList(),
                    toolActions = toolActions.toList(),
                    errors = errors.toList(),
                    role = request.definition.role,
                    stepStats = stats(startedAt, stepIndex, request.maxSteps, modelCalls, toolCalls, subAgentCalls),
                )
                break
            }

            context.addAssistant(response.content, response.toolCalls)

            for ((index, call) in response.toolCalls.withIndex()) {
                if (onCancelled()) {
                    return cancelled(
                        request = request,
                        startedAt = startedAt,
                        stepIndex = stepIndex,
                        modelCalls = modelCalls,
                        toolCalls = toolCalls,
                        subAgentCalls = subAgentCalls,
                        output = output,
                        findings = findings,
                        filesInspected = filesInspected,
                        filesChanged = filesChanged,
                        toolActions = toolActions,
                        errors = errors,
                        sink = sink,
                        delegations = delegationState.records,
                    )
                }
                sink.emit(
                    AgentEvent.ToolRequested(
                        sessionId = request.sessionId,
                        toolCallId = call.id,
                        toolName = call.name,
                        role = request.definition.role,
                        arguments = call.arguments,
                        timestampMillis = clock(),
                    ),
                )
                when (call.name) {
                    AgentProtocol.FINISH_TOOL -> {
                        finished = finishFromCall(
                            request = request,
                            call = call,
                            findings = findings,
                            filesInspected = filesInspected,
                            filesChanged = filesChanged,
                            toolActions = toolActions,
                            errors = errors,
                            fallback = output.toString().trim(),
                            stepStats = stats(startedAt, stepIndex, request.maxSteps, modelCalls, toolCalls, subAgentCalls),
                        )
                    }

                    AgentProtocol.DELEGATE_TOOL -> {
                        subAgentCalls += 1
                        val delegated = handleDelegate(
                            request = request,
                            call = call,
                            invoker = subAgentInvoker,
                            sink = sink,
                            findings = findings,
                            filesInspected = filesInspected,
                            filesChanged = filesChanged,
                            toolActions = toolActions,
                            errors = errors,
                            state = delegationState,
                        )
                        delegationState = delegated.state
                        val pausedDelegation = delegated.paused
                        if (pausedDelegation != null) {
                            return parkedForDelegation(
                                request = request,
                                startedAt = startedAt,
                                stepIndex = stepIndex,
                                modelCalls = modelCalls,
                                toolCalls = toolCalls,
                                subAgentCalls = subAgentCalls,
                                findings = findings,
                                filesInspected = filesInspected,
                                filesChanged = filesChanged,
                                toolActions = toolActions,
                                errors = errors,
                                delegated = pausedDelegation.copy(
                                    parentBatch = batchOf(response.toolCalls, pendingIndex = index),
                                    parentPendingIndex = index,
                                ),
                                context = context,
                                sink = sink,
                            )
                        }
                        context.addToolResult(
                            callId = call.id,
                            toolName = call.name,
                            content = delegated.resultText,
                            path = delegated.path,
                            status = statusOf(delegated.success),
                        )
                    }

                    else -> {
                        val outcome = handleTool(
                            request = request,
                            call = call,
                            router = scopedRouter,
                            context = executionContext,
                            sink = sink,
                            toolActions = toolActions,
                            filesInspected = filesInspected,
                            filesChanged = filesChanged,
                            errors = errors,
                        )
                        when (outcome) {
                            is ToolOutcome.Paused -> {
                                // Park with the *whole* assistant message. The calls
                                // before this one already have results; this one is
                                // awaiting a decision; the rest have not run. Recording
                                // all three is what lets resume finish the message
                                // instead of dropping the siblings.
                                return parked(
                                    request = request,
                                    startedAt = startedAt,
                                    stepIndex = stepIndex,
                                    modelCalls = modelCalls,
                                    toolCalls = toolCalls,
                                    subAgentCalls = subAgentCalls,
                                    findings = findings,
                                    filesInspected = filesInspected,
                                    filesChanged = filesChanged,
                                    toolActions = toolActions,
                                    errors = errors,
                                    pending = outcome.pending.copy(
                                        batch = batchOf(response.toolCalls, pendingIndex = index),
                                        pendingIndex = index,
                                    ),
                                    context = context,
                                    sink = sink,
                                )
                            }
                            is ToolOutcome.Completed -> {
                                toolCalls += 1
                                context.addToolResult(
                                    callId = call.id,
                                    toolName = call.name,
                                    content = outcome.resultText,
                                    path = outcome.path,
                                    status = statusOf(outcome.success),
                                )
                            }
                        }
                    }
                }
                if (finished != null) break
            }
            if (finished != null) break
        }

        if (finished == null) {
            val agentError = AgentError(
                code = AgentErrorCode.MAX_STEPS_EXCEEDED,
                message = "Agent '${request.definition.name}' exceeded maxSteps=${request.maxSteps}",
                role = request.definition.role,
                sessionId = request.sessionId,
            )
            errors += agentError
            sink.emit(AgentEvent.Failed(request.sessionId, agentError, clock()))
            finished = AgentResult(
                sessionId = request.sessionId,
                status = AgentStatus.MAX_STEPS_REACHED,
                summary = agentError.message,
                findings = findings.toList(),
                filesInspected = filesInspected.toList(),
                filesChanged = filesChanged.toList(),
                toolActions = toolActions.toList(),
                errors = errors.toList(),
                role = request.definition.role,
                stepStats = stats(startedAt, stepIndex, request.maxSteps, modelCalls, toolCalls, subAgentCalls),
            )
            return finished
        }

        if (finished.status == AgentStatus.COMPLETED) {
            sink.emit(AgentEvent.Completed(request.sessionId, finished, clock()))
        }
        return finished
    }

    private suspend fun handleTool(
        request: AgentLoopRequest,
        call: ModelToolCall,
        router: ToolRouter,
        context: ToolExecutionContext,
        sink: AgentEventSink,
        toolActions: MutableList<ToolActionRecord>,
        filesInspected: MutableList<String>,
        filesChanged: MutableList<String>,
        errors: MutableList<AgentError>,
    ): ToolOutcome = executeScopedTool(
        request = request,
        call = call,
        router = router,
        context = context,
        sink = sink,
        toolActions = toolActions,
        filesInspected = filesInspected,
        filesChanged = filesChanged,
        errors = errors,
        forcedApproval = null,
    )

    /**
     * The assistant message's tool calls as parked state, in order.
     *
     * [pendingIndex] is the call awaiting a decision; because the loop dispatches
     * sequentially, everything before it has already produced a result. Recording that
     * makes resume able to tell "already done" from "never ran" instead of re-executing
     * anything.
     */
    private fun batchOf(calls: List<ModelToolCall>, pendingIndex: Int): List<PendingToolCall> =
        calls.mapIndexed { position, call ->
            PendingToolCall(
                toolCallId = call.id,
                toolName = call.name,
                arguments = call.arguments,
                index = position,
                completed = position < pendingIndex,
            )
        }

    /** [batch] re-marked for a pause at [pendingIndex]; already-completed entries stay so. */
    private fun markCompleted(batch: List<PendingToolCall>, pendingIndex: Int): List<PendingToolCall> =
        batch.map { it.copy(completed = it.completed || it.index < pendingIndex) }.sortedBy { it.index }

    /**
     * Parks the run in [AgentStatus.WAITING_FOR_PERMISSION].
     *
     * [pending] carries the complete assistant message, and [RunContext.messages] is
     * snapshotted as-is, so the transcript at the moment of the pause is exactly what a
     * resume restores — never a reconstruction from the blocked call alone.
     */
    private suspend fun parked(
        request: AgentLoopRequest,
        startedAt: Long,
        stepIndex: Int,
        modelCalls: Int,
        toolCalls: Int,
        subAgentCalls: Int,
        findings: List<String>,
        filesInspected: List<String>,
        filesChanged: List<String>,
        toolActions: List<ToolActionRecord>,
        errors: List<AgentError>,
        pending: PendingPermission,
        context: RunContext,
        sink: AgentEventSink,
    ): AgentResult {
        sink.emit(
            AgentEvent.PermissionRequested(
                sessionId = request.sessionId,
                pending = pending,
                timestampMillis = clock(),
            ),
        )
        return AgentResult(
            sessionId = request.sessionId,
            status = AgentStatus.WAITING_FOR_PERMISSION,
            summary = "Waiting for approval to run '${pending.toolName}'",
            findings = findings.toList(),
            filesInspected = filesInspected.toList(),
            filesChanged = filesChanged.toList(),
            toolActions = toolActions.toList(),
            errors = errors.toList(),
            role = request.definition.role,
            stepStats = stats(startedAt, stepIndex, request.maxSteps, modelCalls, toolCalls, subAgentCalls),
            pendingPermission = pending,
            resumeContext = context.messages(),
        )
    }

    /**
     * Parks the parent because a delegated specialist parked for permission.
     *
     * The permission belongs to the child, so the parent records the child's session
     * identity and resume context and never converts the pause into a failure. The UI
     * still receives a permission request — for the specialist's tool, naming the role
     * — and a later decision resumes that same child.
     */
    private suspend fun parkedForDelegation(
        request: AgentLoopRequest,
        startedAt: Long,
        stepIndex: Int,
        modelCalls: Int,
        toolCalls: Int,
        subAgentCalls: Int,
        findings: List<String>,
        filesInspected: List<String>,
        filesChanged: List<String>,
        toolActions: List<ToolActionRecord>,
        errors: List<AgentError>,
        delegated: DelegatedPermissionPause,
        context: RunContext,
        sink: AgentEventSink,
    ): AgentResult {
        val display = delegated.pendingPermission.copy(
            reason = "${delegated.pendingPermission.reason} (requested by ${delegated.childRole.name})",
        )
        sink.emit(
            AgentEvent.PermissionRequested(
                sessionId = request.sessionId,
                pending = display,
                timestampMillis = clock(),
            ),
        )
        return AgentResult(
            sessionId = request.sessionId,
            status = AgentStatus.WAITING_FOR_PERMISSION,
            summary = "Waiting for approval to run '${delegated.pendingPermission.toolName}' " +
                "(${delegated.childRole.name})",
            findings = findings.toList(),
            filesInspected = filesInspected.toList(),
            filesChanged = filesChanged.toList(),
            toolActions = toolActions.toList(),
            errors = errors.toList(),
            role = request.definition.role,
            stepStats = stats(startedAt, stepIndex, request.maxSteps, modelCalls, toolCalls, subAgentCalls),
            pendingPermission = display,
            resumeContext = context.messages(),
            delegatedPermissionPause = delegated,
        )
    }

    private suspend fun executeScopedTool(
        request: AgentLoopRequest,
        call: ModelToolCall,
        router: ToolRouter,
        context: ToolExecutionContext,
        sink: AgentEventSink,
        toolActions: MutableList<ToolActionRecord>,
        filesInspected: MutableList<String>,
        filesChanged: MutableList<String>,
        errors: MutableList<AgentError>,
        forcedApproval: Boolean? = null,
    ): ToolOutcome {
        sink.emit(
            AgentEvent.ToolCallStarted(
                sessionId = request.sessionId,
                toolName = call.name,
                role = request.definition.role,
                timestampMillis = clock(),
            ),
        )
        if (forcedApproval == false) {
            val message = "The user denied '${call.name}'"
            errors += AgentError(
                code = AgentErrorCode.PERMISSION_DENIED,
                message = message,
                role = request.definition.role,
                sessionId = request.sessionId,
                details = mapOf("tool" to call.name),
            )
            toolActions += ToolActionRecord(call.name, false, message)
            sink.emit(
                AgentEvent.ToolCallFinished(
                    sessionId = request.sessionId,
                    toolName = call.name,
                    success = false,
                    summary = message,
                    timestampMillis = clock(),
                ),
            )
            return ToolOutcome.Completed("ERROR: $message", success = false)
        }
        sink.emit(
            AgentEvent.ToolProgress(
                sessionId = request.sessionId,
                toolCallId = call.id,
                toolName = call.name,
                detail = "Running ${call.name}",
                timestampMillis = clock(),
            ),
        )
        val callContext = context.copy(
            timeoutMillis = context.timeoutMillis ?: toolTimeout(call.name),
            approval = if (forcedApproval == true) {
                ToolApproval.granted("resumed with approval")
            } else {
                context.approval
            },
        )
        val result = try {
            router.invoke(
                toolName = call.name,
                input = ToolInput(bridge.toToolArguments(call.arguments)),
                context = callContext,
            )
        } catch (cancelled: CancellationException) {
            sink.emit(
                AgentEvent.ToolCancelled(
                    sessionId = request.sessionId,
                    toolCallId = call.id,
                    toolName = call.name,
                    reason = "Cancelled",
                    timestampMillis = clock(),
                ),
            )
            throw cancelled
        } catch (error: Throwable) {
            ToolResult.Failure(
                toolName = call.name,
                error = ToolExecutionError(
                    code = ToolErrorCode.EXECUTION_FAILED,
                    message = error.message ?: "Tool '${call.name}' failed unexpectedly",
                    toolName = call.name,
                    cause = error,
                ),
            )
        }
        val record: ToolActionRecord
        val resultText: String
        when (result) {
            is ToolResult.Success -> {
                val path = result.output.content["path"]?.let { value ->
                    (value as? JsonValue.Str)?.value
                } ?: call.arguments.stringOrNull("path")
                path?.let { rememberPath(call.name, it, filesInspected, filesChanged, request.permissionLevel) }
                record = ToolActionRecord(
                    toolName = call.name,
                    success = true,
                    summary = result.output.displayText ?: "ok",
                    path = path,
                )
                resultText = bridge.renderToolOutput(result.output.content, result.output.displayText)
            }

            is ToolResult.Failure -> {
                val code = when (result.error.code) {
                    ToolErrorCode.PERMISSION_DENIED -> AgentErrorCode.PERMISSION_DENIED
                    ToolErrorCode.UNKNOWN_TOOL -> AgentErrorCode.TOOL_UNKNOWN
                    ToolErrorCode.INVALID_ARGUMENTS -> AgentErrorCode.TOOL_ARGUMENTS_INVALID
                    ToolErrorCode.TIMEOUT -> AgentErrorCode.TIMEOUT
                    ToolErrorCode.CANCELLED -> AgentErrorCode.CANCELLED
                    else -> AgentErrorCode.TOOL_FAILURE
                }
                errors += AgentError(
                    code = code,
                    message = result.error.message ?: "Tool '${call.name}' failed",
                    role = request.definition.role,
                    sessionId = request.sessionId,
                    details = mapOf("tool" to call.name),
                )
                record = ToolActionRecord(call.name, false, result.error.message ?: "failed")
                resultText = "ERROR: ${result.error.message}"
            }

            is ToolResult.ApprovalRequired -> {
                return ToolOutcome.Paused(
                    PendingPermission(
                        toolName = call.name,
                        arguments = call.arguments,
                        reason = result.request.reason,
                        toolCallId = call.id,
                        requiredPermissions = bridge.definitionsFor(listOf(call.name))
                            .firstOrNull()
                            ?.requiredPermissions
                            ?.map { it.name }
                            ?.toSet()
                            .orEmpty(),
                    ),
                )
            }
        }
        toolActions += record
        if (result is ToolResult.Failure && result.error.code == ToolErrorCode.CANCELLED) {
            sink.emit(
                AgentEvent.ToolCancelled(
                    sessionId = request.sessionId,
                    toolCallId = call.id,
                    toolName = call.name,
                    reason = record.summary,
                    timestampMillis = clock(),
                ),
            )
        } else {
            sink.emit(
                AgentEvent.ToolCallFinished(
                    sessionId = request.sessionId,
                    toolName = call.name,
                    success = record.success,
                    summary = record.summary,
                    timestampMillis = clock(),
                ),
            )
        }
        return ToolOutcome.Completed(resultText, path = record.path, success = record.success)
    }

    /**
     * A delegation outcome plus the delegation accounting after it. The loop
     * threads [state] forward so [DelegationPolicy] limits apply across the whole
     * run.
     */
    private data class DelegateOutcome(
        val resultText: String,
        val path: String?,
        val success: Boolean,
        val state: DelegationState,
        /**
         * Set when the specialist parked for permission. The child's own state is
         * carried out so the parent parks too instead of reporting a failure; the
         * parent's assistant batch is filled in by the caller that knows it.
         */
        val paused: DelegatedPermissionPause? = null,
    )

    private suspend fun handleDelegate(
        request: AgentLoopRequest,
        call: ModelToolCall,
        invoker: SubAgentInvoker?,
        sink: AgentEventSink,
        findings: MutableList<String>,
        filesInspected: MutableList<String>,
        filesChanged: MutableList<String>,
        toolActions: MutableList<ToolActionRecord>,
        errors: MutableList<AgentError>,
        state: DelegationState,
    ): DelegateOutcome {
        if (request.definition.role != AgentRole.MAIN) {
            val message = "Only the Main Agent may delegate"
            errors += AgentError(
                code = AgentErrorCode.INVALID_DELEGATION,
                message = message,
                role = request.definition.role,
                sessionId = request.sessionId,
            )
            return DelegateOutcome("ERROR: $message", null, success = false, state = state)
        }
        if (invoker == null) {
            return DelegateOutcome("ERROR: No sub-agent invoker is configured", null, success = false, state = state)
        }
        val role = AgentProtocol.parseRole(call.arguments.stringOrNull(AgentProtocol.ARG_ROLE))
        if (role == null || role == AgentRole.MAIN) {
            val message = "Invalid sub-agent role"
            errors += AgentError(
                code = AgentErrorCode.INVALID_DELEGATION,
                message = message,
                role = request.definition.role,
                sessionId = request.sessionId,
            )
            return DelegateOutcome("ERROR: $message", null, success = false, state = state)
        }
        val task = call.arguments.stringOrNull(AgentProtocol.ARG_TASK).orEmpty()
        val objective = call.arguments.stringOrNull(AgentProtocol.ARG_OBJECTIVE).orEmpty()
        if (task.isBlank() || objective.isBlank()) {
            return DelegateOutcome(
                "ERROR: Delegation requires task and objective",
                null,
                success = false,
                state = state,
            )
        }

        // The authoritative, deterministic gate: depth, total count, per-role
        // repeats and redundant re-delegation are all decided here, before any
        // child is created. A rejection is reported back to the Main Agent as a
        // tool result so it can adapt — it is never a silent no-op.
        val decision = DelegationPolicy.evaluate(role, task, state)
        if (decision is DelegationDecision.Reject) {
            errors += AgentError(
                code = AgentErrorCode.INVALID_DELEGATION,
                message = decision.message,
                role = request.definition.role,
                sessionId = request.sessionId,
                details = mapOf("delegationRejection" to decision.reason.name),
            )
            toolActions += ToolActionRecord(AgentProtocol.DELEGATE_TOOL, false, decision.message)
            return DelegateOutcome("ERROR: ${decision.message}", null, success = false, state = state)
        }

        // Scoped context is capped so one delegation can never hand a specialist
        // more than a bounded slice; the whole repo/conversation is never passed.
        val scopedContext = call.arguments.stringOrNull(AgentProtocol.ARG_CONTEXT).orEmpty()
            .let { if (it.length > DelegationPolicy.MAX_SCOPED_CONTEXT_CHARS) it.take(DelegationPolicy.MAX_SCOPED_CONTEXT_CHARS) else it }

        val childId = com.agentx.app.agent.runtime.AgentIds.newId()
        val childRequest = SubAgentRequest(
            role = role,
            task = task,
            objective = objective,
            scopedContext = scopedContext,
            permissionLevel = AgentProtocol.parsePermission(call.arguments.stringOrNull(AgentProtocol.ARG_PERMISSION)),
            maxSteps = call.arguments.intOrNull(AgentProtocol.ARG_MAX_STEPS),
            parentSessionId = request.sessionId,
            workspaceId = request.workspaceId,
            sessionId = childId,
            promptVariables = request.promptVariables,
            delegationState = state.copy(depth = state.depth + 1),
            contextBudget = com.agentx.app.agent.delegation.SpecialistContextBudgets.forRole(role, request.contextBudget),
        )
        sink.emit(
            AgentEvent.SubAgentStarted(
                sessionId = childId,
                parentSessionId = request.sessionId,
                role = role,
                objective = objective,
                timestampMillis = clock(),
            ),
        )
        val result = try {
            invoker.invoke(childRequest)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            errors += AgentError(
                code = AgentErrorCode.SUB_AGENT_FAILURE,
                message = error.message ?: "Sub-agent '${role.name}' failed",
                role = role,
                sessionId = childId,
                cause = error,
            )
            toolActions += ToolActionRecord(AgentProtocol.DELEGATE_TOOL, false, "sub-agent failed")
            return DelegateOutcome(
                "ERROR: Sub-agent '${role.name}' failed: ${error.message}",
                null,
                success = false,
                state = state.record(role, task, succeeded = false),
            )
        }
        // A specialist that parked for permission is NOT a failure: return the pause
        // to the parent, preserving the child's session identity and its own resume
        // state, so the user is prompted and the same child can be resumed later.
        if (result.status == AgentStatus.WAITING_FOR_PERMISSION && result.pendingPermission != null) {
            return DelegateOutcome(
                resultText = "",
                path = null,
                success = false,
                state = state,
                paused = DelegatedPermissionPause(
                    childSessionId = result.sessionId,
                    childRole = role,
                    pendingPermission = result.pendingPermission,
                    childResumeContext = result.resumeContext,
                ),
            )
        }
        return consumeSubAgentResult(
            request = request,
            role = role,
            result = result,
            task = task,
            sink = sink,
            findings = findings,
            filesInspected = filesInspected,
            filesChanged = filesChanged,
            toolActions = toolActions,
            errors = errors,
            state = state,
        )
    }

    /**
     * Merges a finished specialist's result into the parent run and renders the
     * delegate tool result. Shared by a live delegation and a delegation that
     * finished while the parent was parked, so both paths stay identical.
     */
    private fun consumeSubAgentResult(
        request: AgentLoopRequest,
        role: AgentRole,
        result: SubAgentResult,
        task: String,
        sink: AgentEventSink,
        findings: MutableList<String>,
        filesInspected: MutableList<String>,
        filesChanged: MutableList<String>,
        toolActions: MutableList<ToolActionRecord>,
        errors: MutableList<AgentError>,
        state: DelegationState,
    ): DelegateOutcome {
        findings += result.findings
        filesInspected += result.filesInspected
        filesChanged += result.filesChanged
        toolActions += result.toolActions
        errors += result.errors
        val succeeded = result.status == AgentStatus.COMPLETED
        // Preserve a non-completed specialist as structured runtime state even when it
        // reported no error of its own: the Main Agent still needs to see the failure
        // and decide whether to retry, fall back, continue or stop.
        if (!succeeded && result.errors.isEmpty()) {
            errors += AgentError(
                code = AgentErrorCode.SUB_AGENT_FAILURE,
                message = "Sub-agent '${role.name}' ended with status ${result.status}",
                role = role,
                sessionId = result.sessionId,
                details = mapOf("subAgentStatus" to result.status.name),
            )
        }
        toolActions += ToolActionRecord(
            toolName = AgentProtocol.DELEGATE_TOOL,
            success = succeeded,
            summary = "${role.name}: ${result.summary}",
        )
        sink.emit(
            AgentEvent.SubAgentCompleted(
                sessionId = result.sessionId,
                parentSessionId = request.sessionId,
                role = role,
                status = result.status,
                summary = result.summary,
                timestampMillis = clock(),
            ),
        )
        return DelegateOutcome(
            resultText = renderSubAgentResult(result),
            path = result.filesChanged.firstOrNull() ?: result.filesInspected.firstOrNull(),
            success = succeeded,
            state = state.record(
                role = role,
                task = task,
                succeeded = succeeded,
                changedFiles = result.filesChanged,
                inspectedFiles = result.filesInspected,
            ),
        )
    }

    private fun renderSubAgentResult(result: SubAgentResult): String = buildString {
        append("status=${result.status}\n")
        append("summary=${result.summary}\n")
        if (result.findings.isNotEmpty()) append("findings:\n").append(result.findings.joinToString("\n")).append('\n')
        if (result.filesInspected.isNotEmpty()) append("filesInspected=").append(result.filesInspected.joinToString(",")).append('\n')
        if (result.filesChanged.isNotEmpty()) append("filesChanged=").append(result.filesChanged.joinToString(",")).append('\n')
        if (result.errors.isNotEmpty()) append("errors=").append(result.errors.joinToString { it.message }).append('\n')
    }

    private fun toolTimeout(toolName: String): Long {
        val definition = bridge.definitionsFor(listOf(toolName)).firstOrNull()
        return ToolTimeouts.forCall(
            category = definition?.category,
            capabilities = definition?.capabilities.orEmpty(),
            timeouts = timeouts,
        )
    }

    private fun statusOf(success: Boolean): ToolContextStatus =
        if (success) ToolContextStatus.SUCCESS else ToolContextStatus.FAILURE

    private fun finishFromCall(
        request: AgentLoopRequest,
        call: ModelToolCall,
        findings: MutableList<String>,
        filesInspected: MutableList<String>,
        filesChanged: MutableList<String>,
        toolActions: MutableList<ToolActionRecord>,
        errors: MutableList<AgentError>,
        fallback: String,
        stepStats: AgentStepStats,
    ): AgentResult {
        val extraFindings = AgentProtocol.splitList(call.arguments.stringOrNull(AgentProtocol.ARG_FINDINGS))
        val extraInspected = AgentProtocol.splitList(call.arguments.stringOrNull(AgentProtocol.ARG_FILES_INSPECTED))
        val extraChanged = AgentProtocol.splitList(call.arguments.stringOrNull(AgentProtocol.ARG_FILES_CHANGED))
        findings += extraFindings
        filesInspected += extraInspected
        filesChanged += extraChanged
        val summary = call.arguments.stringOrNull(AgentProtocol.ARG_SUMMARY)?.trim().orEmpty()
            .ifBlank { fallback.ifBlank { "Completed" } }
        toolActions += ToolActionRecord(AgentProtocol.FINISH_TOOL, true, summary)
        return AgentResult(
            sessionId = request.sessionId,
            status = AgentStatus.COMPLETED,
            summary = summary,
            findings = findings.toList(),
            filesInspected = filesInspected.toList(),
            filesChanged = filesChanged.toList(),
            toolActions = toolActions.toList(),
            errors = errors.toList(),
            role = request.definition.role,
            stepStats = stepStats,
        )
    }

    private fun cancelled(
        request: AgentLoopRequest,
        startedAt: Long,
        stepIndex: Int,
        modelCalls: Int,
        toolCalls: Int,
        subAgentCalls: Int,
        output: StringBuilder,
        findings: List<String>,
        filesInspected: List<String>,
        filesChanged: List<String>,
        toolActions: List<ToolActionRecord>,
        errors: MutableList<AgentError>,
        sink: AgentEventSink,
        delegations: List<DelegationRecord> = emptyList(),
    ): AgentResult {
        val error = AgentError(
            code = AgentErrorCode.CANCELLED,
            message = "Cancelled",
            role = request.definition.role,
            sessionId = request.sessionId,
        )
        errors += error
        // The plan's own terminal form, so a consumer never has to infer it: the step
        // that was running is cancelled together with the run.
        sink.emit(
            AgentEvent.PlanUpdated(
                request.sessionId,
                planFor(request.definition.role, stepIndex, terminal = AgentStatus.CANCELLED, delegations = delegations),
                clock(),
            ),
        )
        sink.emit(AgentEvent.Cancelled(request.sessionId, "Cancelled", clock()))
        return AgentResult(
            sessionId = request.sessionId,
            status = AgentStatus.CANCELLED,
            summary = output.toString().ifBlank { "Cancelled" },
            findings = findings,
            filesInspected = filesInspected,
            filesChanged = filesChanged,
            toolActions = toolActions,
            errors = errors.toList(),
            role = request.definition.role,
            stepStats = stats(startedAt, stepIndex, request.maxSteps, modelCalls, toolCalls, subAgentCalls),
        )
    }

    /**
     * The plan the runtime actually followed: one step per loop iteration, plus a
     * named step for every specialist this run delegated to.
     *
     * Steps are concise task units, not reasoning, and the plan is rebuilt once per
     * iteration rather than per token so a consumer is not flooded. The state of a step
     * reuses [AgentStatus], the project's existing run state, instead of a second enum.
     * [terminal] marks the step that was running when the run ended, so a cancelled run
     * carries its own closing plan. Delegated specialist work appears as its own step
     * (role-labelled, with the specialist's status) so the plan reflects *delegated*
     * work rather than hiding it behind an opaque "work step".
     */
    private fun planFor(
        role: AgentRole,
        stepIndex: Int,
        terminal: AgentStatus? = null,
        delegations: List<DelegationRecord> = emptyList(),
    ): AgentPlan {
        val steps = mutableListOf<AgentStep>()
        // Delegated specialist runs, in order, each its own step.
        delegations.forEach { record ->
            steps += AgentStep(
                index = steps.size + 1,
                title = "${record.role.name}: ${record.task.lineSequence().first().take(72)}",
                role = record.role,
                status = if (record.succeeded) AgentStatus.COMPLETED else AgentStatus.FAILED,
                detail = if (record.succeeded) null else "Specialist did not complete",
            )
        }
        val running = steps.size + 1
        steps += AgentStep(
            index = running,
            title = when {
                running == 1 -> "Plan the task"
                delegations.isNotEmpty() -> "Integrate results"
                else -> "Work step $running"
            },
            role = role,
            // A closing plan marks its current step with the terminal state; a
            // mid-run plan shows the current step as running.
            status = terminal ?: AgentStatus.RUNNING,
        )
        return AgentPlan(steps = steps, revision = maxOf(running, stepIndex))
    }

    private fun modelTimeout(error: TimeoutCancellationException, request: AgentLoopRequest): AgentError =
        AgentError(
            code = AgentErrorCode.TIMEOUT,
            message = "The model did not answer within ${timeouts.modelRequestMillis}ms",
            role = request.definition.role,
            sessionId = request.sessionId,
            cause = error,
            details = mapOf("stage" to "model_request"),
        )

    private fun modelFailure(error: Throwable, request: AgentLoopRequest): AgentError {
        val provider = error as? ModelProviderError
        val code = when (provider?.code) {
            ModelProviderErrorCode.TIMEOUT -> AgentErrorCode.TIMEOUT
            ModelProviderErrorCode.INVALID_RESPONSE -> AgentErrorCode.MALFORMED_RESPONSE
            ModelProviderErrorCode.CONNECTION_FAILED,
            ModelProviderErrorCode.NETWORK_ERROR,
            -> AgentErrorCode.MODEL_FAILURE
            ModelProviderErrorCode.UNSUPPORTED ->
                if (provider.providerErrorType == "MODEL_CAPABILITY_UNSUPPORTED") {
                    AgentErrorCode.MODEL_CAPABILITY_UNSUPPORTED
                } else {
                    AgentErrorCode.MODEL_FAILURE
                }
            else -> AgentErrorCode.MODEL_FAILURE
        }
        val extra = mutableMapOf<String, String>()
        provider?.details?.forEach { (key, value) ->
            if (value != null) extra[key] = value.toString()
        }
        return AgentError(
            code = code,
            message = provider?.message ?: error.message ?: "Model gateway failed",
            role = request.definition.role,
            sessionId = request.sessionId,
            cause = error,
            details = (mapOf(
                "providerError" to (provider?.code?.name ?: error::class.simpleName.orEmpty()),
                "httpStatus" to (provider?.httpStatus?.toString().orEmpty()),
            ) + extra).filterValues { it.isNotBlank() },
        )
    }

    private suspend fun complete(
        config: ModelConfig,
        role: AgentRole,
        messages: List<ModelMessage>,
        tools: List<ModelToolSpec>,
        sink: AgentEventSink,
        sessionId: String,
    ): ModelResponse = withExecutionBudget(timeouts.modelRequestMillis) {
        completeWithinBudget(config, role, messages, tools, sink, sessionId)
    }

    /**
     * Runs one model request, optionally through the controlled fallback layer.
     *
     * The primary request is attempted first; only when the configured policy is
     * enabled and the failure is temporary may an eligible fallback candidate run.
     * [outputProduced] tracks streaming safety: once the current attempt has
     * emitted meaningful output, fallback stops rather than duplicating it.
     */
    private suspend fun completeWithinBudget(
        config: ModelConfig,
        role: AgentRole,
        messages: List<ModelMessage>,
        tools: List<ModelToolSpec>,
        sink: AgentEventSink,
        sessionId: String,
    ): ModelResponse {
        val fallback = modelFallback
            ?: return invokeModel(config, messages, tools, sink, sessionId) {}
        var outputProduced = false
        return fallback.execute(
            role = role,
            sessionId = sessionId,
            primary = config,
            default = config,
            sink = sink,
            outputProduced = { outputProduced },
        ) { target ->
            outputProduced = false
            invokeModel(target, messages, tools, sink, sessionId) { outputProduced = true }
        }
    }

    /**
     * One raw model attempt for [config]. [onOutput] is invoked on the first
     * meaningful streaming delta (text or tool call), so the fallback layer can
     * refuse to restart a partially produced response. The same [messages],
     * [tools] and system instruction are sent for every attempt — only the
     * configuration differs — so a fallback request is the same logical request.
     */
    private suspend fun invokeModel(
        config: ModelConfig,
        messages: List<ModelMessage>,
        tools: List<ModelToolSpec>,
        sink: AgentEventSink,
        sessionId: String,
        onOutput: () -> Unit,
    ): ModelResponse {
        val request = ModelRequest(config = config, messages = messages, tools = tools)
        return if (config.stream) {
            val streamed = StringBuilder()
            var withheld = false
            val response = gateway.stream(request) { event ->
                when (event) {
                    is ModelStreamEvent.TextDelta -> {
                        if (event.text.isEmpty()) return@stream
                        onOutput()
                        streamed.append(event.text)
                        if (withheld || shouldWithholdStreaming(streamed.toString())) {
                            withheld = true
                        } else {
                            sink.emit(
                                AgentEvent.OutputDelta(
                                    sessionId = sessionId,
                                    text = event.text,
                                    timestampMillis = clock(),
                                ),
                            )
                        }
                    }

                    is ModelStreamEvent.ToolCallDelta -> onOutput()
                    else -> Unit
                }
            }
            val normalized = ContentToolCallParser.normalize(response)
            if (normalized.toolCalls.isEmpty() && withheld && normalized.content.isNotBlank()) {
                sink.emit(
                    AgentEvent.OutputDelta(
                        sessionId = sessionId,
                        text = normalized.content,
                        timestampMillis = clock(),
                    ),
                )
            }
            normalized
        } else {
            ContentToolCallParser.normalize(gateway.complete(request))
        }
    }

    private fun shouldWithholdStreaming(text: String): Boolean {
        if (ContentToolCallParser.parse(text).isNotEmpty()) return true
        if (ContentToolCallParser.isLikelyToolCallText(text)) return true
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        return trimmed.startsWith("{") || trimmed.startsWith("[") || trimmed.startsWith("```")
    }

    private fun stats(
        startedAt: Long,
        stepIndex: Int,
        maxSteps: Int,
        modelCalls: Int,
        toolCalls: Int,
        subAgentCalls: Int,
    ): AgentStepStats = AgentStepStats(
        currentStep = stepIndex,
        maxSteps = maxSteps,
        modelCalls = modelCalls,
        toolCalls = toolCalls,
        subAgentCalls = subAgentCalls,
        elapsedMillis = clock() - startedAt,
    )

    private fun rememberPath(
        toolName: String,
        path: String,
        filesInspected: MutableList<String>,
        filesChanged: MutableList<String>,
        permission: PermissionLevel,
    ) {
        val mutating = toolName.contains("write", ignoreCase = true) ||
            toolName.contains("edit", ignoreCase = true) ||
            toolName.contains("patch", ignoreCase = true) ||
            toolName.contains("create", ignoreCase = true) ||
            permission != PermissionLevel.READ_ONLY && toolName.contains("file", ignoreCase = true) &&
            !toolName.contains("read", ignoreCase = true) && !toolName.contains("list", ignoreCase = true)
        if (mutating) {
            if (path !in filesChanged) filesChanged += path
        } else if (path !in filesInspected) {
            filesInspected += path
        }
    }

    /** The system instruction of a run plus the layer that supplied it. */
    private data class BasePrompt(val text: String, val source: String)

    private suspend fun resolveBasePrompt(request: AgentLoopRequest): BasePrompt {
        val fallback = request.definition.systemInstructions
        val manager = prompts ?: return BasePrompt(fallback, PROMPT_SOURCE_DEFINITION)
        return runCatching {
            val resolved = manager.resolve(request.definition.role, request.promptVariables)
            BasePrompt(
                text = resolved.text.ifBlank { fallback },
                source = if (resolved.text.isBlank()) PROMPT_SOURCE_DEFINITION else resolved.source.name,
            )
        }.getOrDefault(BasePrompt(fallback, PROMPT_SOURCE_DEFINITION))
    }

    private suspend fun resolveSkillContext(request: AgentLoopRequest): SkillContext {
        val resolver = skillContext ?: return SkillContext.EMPTY
        return runCatching { resolver.resolve(request.definition.role.name, request.contextBudget) }
            .getOrDefault(SkillContext.EMPTY)
    }

    /**
     * Records what this run's system instruction is made of: which layer supplied
     * the prompt, how large each part is, and how every installed skill was
     * resolved (included, shortened, or withheld and why).
     *
     * Skill ids, statuses and sizes only — no instruction text is ever logged.
     */
    /**
     * Characters consumed by the tool schemas this run will send.
     *
     * Every field the provider serializes is counted — name, description and each
     * parameter — because a tool definition is prompt text like any other. The
     * caller's role-scoped list is what is measured, so a role that may use three
     * tools pays for three, not for every tool the process knows about.
     */
    private fun toolSchemaChars(specs: List<ModelToolSpec>): Int =
        specs.sumOf { spec ->
            spec.name.length + spec.description.length +
                spec.parameters.sumOf { parameter -> parameter.name.length + parameter.description.length }
        }

    /**
     * Records how the context budget was derived, so "why did this run send so
     * little context?" is answerable from the log instead of by guessing. Counts,
     * sizes and descriptor metadata only: no prompt text, no file contents and no
     * credential is written.
     */
    private fun logContextBudget(request: AgentLoopRequest, plan: ModelContextBudget.Plan) {
        val report = plan.report
        logger.info(
            "Model context budget derived",
            mapOf(
                "sessionId" to request.sessionId,
                "role" to request.definition.role.name,
                "providerId" to request.modelConfig.providerId,
                "model" to request.modelConfig.model,
                "contextWindowTokens" to report.windowTokens,
                "contextWindowKnown" to report.windowKnown,
                "reservedOutputTokens" to report.reservedOutputTokens,
                "promptOverheadTokens" to report.overheadTokens,
                "safetyMarginTokens" to report.safetyMarginTokens,
                "availableInputTokens" to report.availableInputTokens,
                "contextCharBudget" to plan.budget.maxTotalChars,
                "maxFileChars" to plan.budget.maxFileChars,
                "maxToolResultChars" to plan.budget.maxToolResultChars,
                "maxConversationChars" to plan.budget.maxConversationChars,
            ),
        )
    }

    private fun logPromptAssembly(
        request: AgentLoopRequest,
        basePrompt: BasePrompt,
        skills: SkillContext,
        systemPrompt: String,
    ) {
        val fields = linkedMapOf<String, Any?>(
            "sessionId" to request.sessionId,
            "role" to request.definition.role.name,
            "resuming" to request.resumeContext.isNotEmpty(),
            "promptSource" to basePrompt.source,
            "promptChars" to basePrompt.text.length,
            "systemPromptChars" to systemPrompt.length,
        )
        fields.putAll(skills.diagnosticFields())
        logger.info("System prompt assembled", fields)
    }

    private fun buildSystemPrompt(
        request: AgentLoopRequest,
        basePrompt: String,
        skillBlock: String,
    ): String = buildString {
        append(basePrompt.trim())
        append("\n\nRole: ").append(request.definition.role.name)
        append("\nPermission: ").append(request.permissionLevel.name)
        append("\nMax steps: ").append(request.maxSteps)
        append("\nAllowed tools: ").append(request.allowedTools.joinToString(", ").ifBlank { "(none)" })
        request.workspaceId?.takeIf { it.isNotBlank() }?.let { id ->
            append("\nWorkspace id: ").append(id)
            if (request.requiresWorkspace) {
                append("\nThis task is about the workspace. Inspect it with the filesystem tools before answering.")
                append("\nDo not guess the project type or invent files; list the workspace first.")
            } else {
                append("\nThis message is not about the workspace. Reply conversationally.")
                append("\nDo not list, read or summarise the project unless the user asks about it.")
            }
        }
        append("\nNever invent file contents or project structure; report only what a tool returned.")
        append("\nUse the tool-calling interface. Never write tool-call JSON as assistant text.")
        if (request.definition.role == AgentRole.MAIN) {
            append("\nDelegate at most one sub-agent per turn and wait for its result.")
            // A deterministic guidance line derived from the task itself, so the Main
            // Agent is nudged to handle simple work directly instead of delegating by
            // reflex. This is advisory only: it never widens or narrows tool access,
            // and the hard limits are still enforced by DelegationPolicy.
            when (com.agentx.app.agent.delegation.TaskComplexityClassifier.classify(request.userPrompt, request.objective)) {
                com.agentx.app.agent.delegation.TaskComplexity.SIMPLE ->
                    append("\nThis task looks simple: handle it yourself with your own tools. Delegate only if a specialist is clearly required.")
                com.agentx.app.agent.delegation.TaskComplexity.MODERATE ->
                    append("\nThis task looks moderate: delegate at most one specialist if it clearly fits its role; otherwise handle it directly.")
                com.agentx.app.agent.delegation.TaskComplexity.COMPLEX ->
                    append("\nThis task looks complex: decompose it and delegate focused specialists one at a time in a sensible order (understand, then implement, test, review).")
            }
        }
        if (skillBlock.isNotBlank()) {
            append("\n\n# Skills\n")
            append("Enabled skills for this role. They are instructions, not code; never execute them.\n\n")
            append(skillBlock.trim())
        }
    }

    private fun buildUserPrompt(request: AgentLoopRequest): String = buildString {
        request.objective?.takeIf { it.isNotBlank() }?.let {
            append("Objective: ").append(it).append("\n\n")
        }
        append(request.userPrompt.trim())
        if (request.scopedContext.isNotBlank()) {
            append("\n\nScoped context:\n")
            append(request.scopedContext.trim())
        }
    }

    private companion object {
        /** The instruction came from the agent definition, with no prompt manager wired. */
        const val PROMPT_SOURCE_DEFINITION = "definition-default"
    }
}
