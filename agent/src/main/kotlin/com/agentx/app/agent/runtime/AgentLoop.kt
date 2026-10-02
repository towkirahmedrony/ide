package com.agentx.app.agent.runtime

import com.agentx.app.agent.domain.AgentDefinition
import com.agentx.app.agent.domain.AgentError
import com.agentx.app.agent.domain.AgentErrorCode
import com.agentx.app.agent.domain.AgentEvent
import com.agentx.app.agent.domain.AgentEventSink
import com.agentx.app.agent.domain.AgentResult
import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.agent.domain.AgentStatus
import com.agentx.app.agent.domain.AgentStepStats
import com.agentx.app.agent.domain.PermissionLevel
import com.agentx.app.agent.domain.PendingPermission
import com.agentx.app.agent.domain.SubAgentRequest
import com.agentx.app.agent.domain.SubAgentResult
import com.agentx.app.agent.domain.ToolActionRecord
import com.agentx.app.agent.prompt.PromptManager
import com.agentx.app.agent.prompt.PromptVariables
import com.agentx.app.agent.protocol.AgentProtocol
import com.agentx.app.agent.tools.AgentToolBridge
import com.agentx.app.agent.tools.intOrNull
import com.agentx.app.agent.tools.stringOrNull
import com.agentx.app.context.ContextBudget
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
import com.agentx.app.model.json.JsonObject
import com.agentx.app.model.json.JsonValue
import com.agentx.app.agent.domain.toToolGrants
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
)

/**
 * A parked tool call plus the user's decision, used to resume a run that
 * stopped in [com.agentx.app.agent.domain.AgentStatus.WAITING_FOR_PERMISSION].
 */
data class ResumedPermission(
    val toolName: String,
    val arguments: JsonObject,
    val reason: String,
    val toolCallId: String,
    val approved: Boolean,
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
        // Conversation and tool-result context are built by the Context Engine;
        // the loop only drives them. [context.messages] is the budgeted,
        // model-ready form of that context.
        val context = runContexts.create(request.sessionId, request.contextBudget)
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

        val scopedRouter = ScopedToolRouter(
            inner = toolRouter,
            allowedTools = request.allowedTools.toSet(),
            permissionLevel = request.permissionLevel,
            toolAllowed = { name ->
                val definition = bridge.definitionsFor(listOf(name)).firstOrNull()
                definition == null || request.permissionLevel.allows(definition.capabilities)
            },
        )

        val toolSpecs = bridge.toModelSpecs(request.allowedTools)
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

        // When resuming, the parked permission call is re-dispatched first with
        // the user's decision, before the next model call.
        request.resumePermission?.let { resumed ->
            sink.emit(
                AgentEvent.PermissionResolved(
                    sessionId = request.sessionId,
                    toolName = resumed.toolName,
                    approved = resumed.approved,
                    timestampMillis = clock(),
                ),
            )
            val outcome = executeScopedTool(
                request = request,
                call = ModelToolCall(
                    id = resumed.toolCallId,
                    name = resumed.toolName,
                    arguments = resumed.arguments,
                ),
                router = scopedRouter,
                context = executionContext,
                sink = sink,
                toolActions = toolActions,
                filesInspected = filesInspected,
                filesChanged = filesChanged,
                errors = errors,
                forcedApproval = resumed.approved,
            )
            toolCalls += 1
            // forcedApproval != null means the outcome is always Completed.
            val resumedOutcome = outcome as? ToolOutcome.Completed
            context.addToolResult(
                callId = resumed.toolCallId,
                toolName = resumed.toolName,
                content = resumedOutcome?.resultText.orEmpty(),
                path = resumedOutcome?.path,
                status = statusOf(resumedOutcome?.success == true),
            )
            sink.emit(
                AgentEvent.StatsUpdated(
                    request.sessionId,
                    stats(startedAt, 0, request.maxSteps, modelCalls, toolCalls, subAgentCalls),
                    clock(),
                ),
            )
        }

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
                )
            }

            stepIndex += 1
            sink.emit(
                AgentEvent.StatsUpdated(
                    request.sessionId,
                    stats(startedAt, stepIndex, request.maxSteps, modelCalls, toolCalls, subAgentCalls),
                    clock(),
                ),
            )

            val response = try {
                ContentToolCallParser.normalize(
                    complete(request.modelConfig, context.messages(), toolSpecs, sink, request.sessionId),
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

            for (call in response.toolCalls) {
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
                        )
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
                                sink.emit(
                                    AgentEvent.PermissionRequested(
                                        sessionId = request.sessionId,
                                        pending = outcome.pending,
                                        timestampMillis = clock(),
                                    ),
                                )
                                return AgentResult(
                                    sessionId = request.sessionId,
                                    status = AgentStatus.WAITING_FOR_PERMISSION,
                                    summary = "Waiting for approval to run '${outcome.pending.toolName}'",
                                    findings = findings.toList(),
                                    filesInspected = filesInspected.toList(),
                                    filesChanged = filesChanged.toList(),
                                    toolActions = toolActions.toList(),
                                    errors = errors.toList(),
                                    role = request.definition.role,
                                    stepStats = stats(startedAt, stepIndex, request.maxSteps, modelCalls, toolCalls, subAgentCalls),
                                    pendingPermission = outcome.pending,
                                    resumeContext = context.messages(),
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
    ): ToolOutcome.Completed {
        if (request.definition.role != AgentRole.MAIN) {
            val message = "Only the Main Agent may delegate"
            errors += AgentError(
                code = AgentErrorCode.INVALID_DELEGATION,
                message = message,
                role = request.definition.role,
                sessionId = request.sessionId,
            )
            return ToolOutcome.Completed("ERROR: $message", success = false)
        }
        if (invoker == null) {
            return ToolOutcome.Completed("ERROR: No sub-agent invoker is configured", success = false)
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
            return ToolOutcome.Completed("ERROR: $message", success = false)
        }
        val task = call.arguments.stringOrNull(AgentProtocol.ARG_TASK).orEmpty()
        val objective = call.arguments.stringOrNull(AgentProtocol.ARG_OBJECTIVE).orEmpty()
        if (task.isBlank() || objective.isBlank()) {
            return ToolOutcome.Completed(
                "ERROR: Delegation requires task and objective",
                success = false,
            )
        }
        val childId = com.agentx.app.agent.runtime.AgentIds.newId()
        val childRequest = SubAgentRequest(
            role = role,
            task = task,
            objective = objective,
            scopedContext = call.arguments.stringOrNull(AgentProtocol.ARG_CONTEXT).orEmpty(),
            permissionLevel = AgentProtocol.parsePermission(call.arguments.stringOrNull(AgentProtocol.ARG_PERMISSION)),
            maxSteps = call.arguments.intOrNull(AgentProtocol.ARG_MAX_STEPS),
            parentSessionId = request.sessionId,
            workspaceId = request.workspaceId,
            sessionId = childId,
            promptVariables = request.promptVariables,
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
            return ToolOutcome.Completed(
                "ERROR: Sub-agent '${role.name}' failed: ${error.message}",
                success = false,
            )
        }
        findings += result.findings
        filesInspected += result.filesInspected
        filesChanged += result.filesChanged
        toolActions += result.toolActions
        errors += result.errors
        toolActions += ToolActionRecord(
            toolName = AgentProtocol.DELEGATE_TOOL,
            success = result.status == AgentStatus.COMPLETED,
            summary = "${role.name}: ${result.summary}",
        )
        sink.emit(
            AgentEvent.SubAgentCompleted(
                sessionId = childId,
                parentSessionId = request.sessionId,
                role = role,
                status = result.status,
                summary = result.summary,
                timestampMillis = clock(),
            ),
        )
        val rendered = buildString {
            append("status=${result.status}\n")
            append("summary=${result.summary}\n")
            if (result.findings.isNotEmpty()) append("findings:\n").append(result.findings.joinToString("\n")).append('\n')
            if (result.filesInspected.isNotEmpty()) append("filesInspected=").append(result.filesInspected.joinToString(",")).append('\n')
            if (result.filesChanged.isNotEmpty()) append("filesChanged=").append(result.filesChanged.joinToString(",")).append('\n')
            if (result.errors.isNotEmpty()) append("errors=").append(result.errors.joinToString { it.message }).append('\n')
        }
        return ToolOutcome.Completed(
            resultText = rendered,
            path = result.filesChanged.firstOrNull() ?: result.filesInspected.firstOrNull(),
            success = result.status == AgentStatus.COMPLETED,
        )
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
    ): AgentResult {
        val error = AgentError(
            code = AgentErrorCode.CANCELLED,
            message = "Cancelled",
            role = request.definition.role,
            sessionId = request.sessionId,
        )
        errors += error
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
        messages: List<ModelMessage>,
        tools: List<ModelToolSpec>,
        sink: AgentEventSink,
        sessionId: String,
    ): ModelResponse = withExecutionBudget(timeouts.modelRequestMillis) {
        completeWithinBudget(config, messages, tools, sink, sessionId)
    }

    private suspend fun completeWithinBudget(
        config: ModelConfig,
        messages: List<ModelMessage>,
        tools: List<ModelToolSpec>,
        sink: AgentEventSink,
        sessionId: String,
    ): ModelResponse {
        val request = ModelRequest(config = config, messages = messages, tools = tools)
        return if (config.stream) {
            val streamed = StringBuilder()
            var withheld = false
            val response = gateway.stream(request) { event ->
                val delta = event as? ModelStreamEvent.TextDelta
                if (delta != null && delta.text.isNotEmpty()) {
                    streamed.append(delta.text)
                    if (withheld || shouldWithholdStreaming(streamed.toString())) {
                        withheld = true
                    } else {
                        sink.emit(
                            AgentEvent.OutputDelta(
                                sessionId = sessionId,
                                text = delta.text,
                                timestampMillis = clock(),
                            ),
                        )
                    }
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
