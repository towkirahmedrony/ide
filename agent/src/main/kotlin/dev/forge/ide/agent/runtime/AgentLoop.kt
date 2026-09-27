package dev.forge.ide.agent.runtime

import dev.forge.ide.agent.domain.AgentDefinition
import dev.forge.ide.agent.domain.AgentError
import dev.forge.ide.agent.domain.AgentErrorCode
import dev.forge.ide.agent.domain.AgentEvent
import dev.forge.ide.agent.domain.AgentEventSink
import dev.forge.ide.agent.domain.AgentResult
import dev.forge.ide.agent.domain.AgentRole
import dev.forge.ide.agent.domain.AgentStatus
import dev.forge.ide.agent.domain.AgentStep
import dev.forge.ide.agent.domain.AgentStepStats
import dev.forge.ide.agent.domain.PermissionLevel
import dev.forge.ide.agent.domain.PendingPermission
import dev.forge.ide.agent.domain.SubAgentRequest
import dev.forge.ide.agent.domain.SubAgentResult
import dev.forge.ide.agent.domain.ToolActionRecord
import dev.forge.ide.agent.protocol.AgentProtocol
import dev.forge.ide.agent.tools.AgentToolBridge
import dev.forge.ide.agent.tools.intOrNull
import dev.forge.ide.agent.tools.stringOrNull
import dev.forge.ide.model.ModelConfig
import dev.forge.ide.model.ModelGateway
import dev.forge.ide.model.ModelMessage
import dev.forge.ide.model.ModelProviderError
import dev.forge.ide.model.ModelProviderErrorCode
import dev.forge.ide.model.ModelRequest
import dev.forge.ide.model.ModelResponse
import dev.forge.ide.model.ModelStreamEvent
import dev.forge.ide.model.ModelToolCall
import dev.forge.ide.model.ModelToolChoice
import dev.forge.ide.model.ModelToolSpec
import dev.forge.ide.model.json.JsonObject
import dev.forge.ide.model.json.JsonValue
import dev.forge.ide.tools.ToolApproval
import dev.forge.ide.tools.ToolErrorCode
import dev.forge.ide.tools.ToolExecutionContext
import dev.forge.ide.tools.ToolExecutionError
import dev.forge.ide.tools.ToolInput
import dev.forge.ide.tools.ToolResult
import dev.forge.ide.tools.ToolRouter
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
    /** Conversation snapshot to restore when resuming a permission pause. */
    val resumeContext: List<ModelMessage> = emptyList(),
    /** Tool call awaiting approval with the user's decision; when present the loop is resuming. */
    val resumePermission: ResumedPermission? = null,
)

/**
 * A parked tool call plus the user's decision, used to resume a run that
 * stopped in [dev.forge.ide.agent.domain.AgentStatus.WAITING_FOR_PERMISSION].
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
    data class Completed(val resultText: String) : ToolOutcome

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
) {

    suspend fun run(
        request: AgentLoopRequest,
        sink: AgentEventSink,
        subAgentInvoker: SubAgentInvoker? = null,
        onCancelled: () -> Boolean = { false },
    ): AgentResult {
        val startedAt = clock()
        val context = BoundedAgentContext()
        if (request.resumeContext.isNotEmpty()) {
            // Resuming a run that paused for a permission: restore the saved
            // conversation instead of a fresh system+user seed.
            context.restore(request.resumeContext)
        } else {
            context.start(buildSystemPrompt(request), buildUserPrompt(request))
        }

        val toolActions = mutableListOf<ToolActionRecord>()
        val findings = mutableListOf<String>()
        val filesInspected = mutableListOf<String>()
        val filesChanged = mutableListOf<String>()
        val errors = mutableListOf<AgentError>()
        val steps = mutableListOf<AgentStep>()
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
            val resumeText = (outcome as? ToolOutcome.Completed)?.resultText.orEmpty()
            context.addToolResult(resumed.toolCallId, resumed.toolName, resumeText)
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
            val step = AgentStep(
                index = stepIndex,
                title = "Model step $stepIndex",
                role = request.definition.role,
                status = AgentStatus.RUNNING,
            )
            steps += step
            sink.emit(AgentEvent.StepProgress(request.sessionId, step, clock()))
            sink.emit(
                AgentEvent.StatsUpdated(
                    request.sessionId,
                    stats(startedAt, stepIndex, request.maxSteps, modelCalls, toolCalls, subAgentCalls),
                    clock(),
                ),
            )

            val response = try {
                complete(request.modelConfig, context.bounded(), toolSpecs, sink, request.sessionId)
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
                        val resultText = handleDelegate(
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
                        context.addToolResult(call.id, call.name, resultText)
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
                                // Park the call and end this run in a
                                // WAITING_FOR_PERMISSION state; the orchestrator
                                // resumes with the user's decision later.
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
                                    resumeContext = context.bounded(),
                                )
                            }
                            is ToolOutcome.Completed -> {
                                toolCalls += 1
                                context.addToolResult(call.id, call.name, outcome.resultText)
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

    /**
     * Executes one tool call strictly through the scoped router: allow-list and
     * permission-ceiling check, then permission policy, then executor. Tool
     * failures are captured as data; they never crash the loop.
     */
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
        // A denied resume never reaches the tool: the decision is final.
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
            return ToolOutcome.Completed("ERROR: $message")
        }
        val result = try {
            router.invoke(
                toolName = call.name,
                input = ToolInput(bridge.toToolArguments(call.arguments)),
                context = if (forcedApproval == true) {
                    context.copy(approval = ToolApproval.granted("resumed with approval"))
                } else {
                    context
                },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            // A misbehaving tool must never take down the orchestration.
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
                // Not an error: the call is parked for the user to decide and
                // the loop ends in WAITING_FOR_PERMISSION.
                return ToolOutcome.Paused(
                    PendingPermission(
                        toolName = call.name,
                        arguments = call.arguments,
                        reason = result.request.reason,
                        toolCallId = call.id,
                    ),
                )
            }
        }
        toolActions += record
        sink.emit(
            AgentEvent.ToolCallFinished(
                sessionId = request.sessionId,
                toolName = call.name,
                success = record.success,
                summary = record.summary,
                timestampMillis = clock(),
            ),
        )
        return ToolOutcome.Completed(resultText)
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
    ): String {
        if (request.definition.role != AgentRole.MAIN) {
            val message = "Only the Main Agent may delegate"
            errors += AgentError(
                code = AgentErrorCode.INVALID_DELEGATION,
                message = message,
                role = request.definition.role,
                sessionId = request.sessionId,
            )
            return "ERROR: $message"
        }
        if (invoker == null) {
            return "ERROR: No sub-agent invoker is configured"
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
            return "ERROR: $message"
        }
        val task = call.arguments.stringOrNull(AgentProtocol.ARG_TASK).orEmpty()
        val objective = call.arguments.stringOrNull(AgentProtocol.ARG_OBJECTIVE).orEmpty()
        if (task.isBlank() || objective.isBlank()) {
            return "ERROR: Delegation requires task and objective"
        }
        val childId = AgentIds.newId()
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
            return "ERROR: Sub-agent '${role.name}' failed: ${error.message}"
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
        return buildString {
            append("status=${result.status}\n")
            append("summary=${result.summary}\n")
            if (result.findings.isNotEmpty()) append("findings:\n").append(result.findings.joinToString("\n")).append('\n')
            if (result.filesInspected.isNotEmpty()) append("filesInspected=").append(result.filesInspected.joinToString(",")).append('\n')
            if (result.filesChanged.isNotEmpty()) append("filesChanged=").append(result.filesChanged.joinToString(",")).append('\n')
            if (result.errors.isNotEmpty()) append("errors=").append(result.errors.joinToString { it.message }).append('\n')
        }
    }

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

    private fun modelFailure(error: Throwable, request: AgentLoopRequest): AgentError {
        val provider = error as? ModelProviderError
        val code = when (provider?.code) {
            ModelProviderErrorCode.TIMEOUT -> AgentErrorCode.TIMEOUT
            ModelProviderErrorCode.INVALID_RESPONSE -> AgentErrorCode.MALFORMED_RESPONSE
            ModelProviderErrorCode.CONNECTION_FAILED,
            ModelProviderErrorCode.NETWORK_ERROR,
            -> AgentErrorCode.MODEL_FAILURE
            else -> AgentErrorCode.MODEL_FAILURE
        }
        return AgentError(
            code = code,
            message = provider?.message ?: error.message ?: "Model gateway failed",
            role = request.definition.role,
            sessionId = request.sessionId,
            cause = error,
            details = mapOf(
                "providerError" to (provider?.code?.name ?: error::class.simpleName.orEmpty()),
                "httpStatus" to (provider?.httpStatus?.toString().orEmpty()),
            ).filterValues { it.isNotBlank() },
        )
    }

    /**
     * Sends one request through the Model Gateway. Provider-agnostic: the
     * request carries only gateway types (config, messages, tool specs). When
     * the model config enables streaming, text deltas are surfaced to the UI
     * as [AgentEvent.OutputDelta] while the final response is still returned
     * as a single normalized [ModelResponse].
     */
    private suspend fun complete(
        config: ModelConfig,
        messages: List<ModelMessage>,
        tools: List<ModelToolSpec>,
        sink: AgentEventSink,
        sessionId: String,
    ): ModelResponse {
        val request = ModelRequest(config = config, messages = messages, tools = tools)
        return if (config.stream) {
            gateway.stream(request) { event ->
                val delta = event as? ModelStreamEvent.TextDelta
                if (delta != null && delta.text.isNotEmpty()) {
                    sink.emit(
                        AgentEvent.OutputDelta(
                            sessionId = sessionId,
                            text = delta.text,
                            timestampMillis = clock(),
                        ),
                    )
                }
            }
        } else {
            gateway.complete(request)
        }
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

    private fun buildSystemPrompt(request: AgentLoopRequest): String = buildString {
        append(request.definition.systemInstructions.trim())
        append("\n\nRole: ").append(request.definition.role.name)
        append("\nPermission: ").append(request.permissionLevel.name)
        append("\nMax steps: ").append(request.maxSteps)
        append("\nAllowed tools: ").append(request.allowedTools.joinToString(", ").ifBlank { "(none)" })
        if (request.definition.role == AgentRole.MAIN) {
            append("\nDelegate at most one sub-agent per turn and wait for its result.")
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
}
