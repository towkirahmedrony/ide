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
import dev.forge.ide.agent.domain.PermissionLevel
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
import dev.forge.ide.model.ModelRequest
import dev.forge.ide.model.ModelResponse
import dev.forge.ide.model.ModelStreamEvent
import dev.forge.ide.model.ModelToolCall
import dev.forge.ide.model.ModelToolChoice
import dev.forge.ide.tools.ToolErrorCode
import dev.forge.ide.tools.ToolExecutionContext
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
)

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
        val messages = mutableListOf<ModelMessage>()
        messages += ModelMessage.system(buildSystemPrompt(request))
        messages += ModelMessage.user(buildUserPrompt(request))

        val toolActions = mutableListOf<ToolActionRecord>()
        val findings = mutableListOf<String>()
        val filesInspected = mutableListOf<String>()
        val filesChanged = mutableListOf<String>()
        val errors = mutableListOf<AgentError>()
        val steps = mutableListOf<AgentStep>()
        val output = StringBuilder()
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

        var stepIndex = 0
        while (stepIndex < request.maxSteps) {
            if (onCancelled()) {
                return cancelled(request, output, findings, filesInspected, filesChanged, toolActions, errors, sink)
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

            val response = try {
                complete(request.modelConfig, messages, toolSpecs, sink, request.sessionId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val agentError = AgentError(
                    code = AgentErrorCode.MODEL_FAILURE,
                    message = error.message ?: "Model gateway failed",
                    role = request.definition.role,
                    sessionId = request.sessionId,
                    cause = error,
                )
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
                )
            }

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
                )
                break
            }

            messages += ModelMessage.assistant(response.content, response.toolCalls)

            for (call in response.toolCalls) {
                if (onCancelled()) {
                    return cancelled(request, output, findings, filesInspected, filesChanged, toolActions, errors, sink)
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
                        )
                    }

                    AgentProtocol.DELEGATE_TOOL -> {
                        val result = handleDelegate(
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
                        messages += ModelMessage.tool(call.id, result, call.name)
                    }

                    else -> {
                        val resultText = handleTool(
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
                        messages += ModelMessage.tool(call.id, resultText, call.name)
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
                status = AgentStatus.FAILED,
                summary = agentError.message,
                findings = findings.toList(),
                filesInspected = filesInspected.toList(),
                filesChanged = filesChanged.toList(),
                toolActions = toolActions.toList(),
                errors = errors.toList(),
                role = request.definition.role,
            )
            return finished
        }

        if (finished.status == AgentStatus.COMPLETED) {
            sink.emit(AgentEvent.Completed(request.sessionId, finished, clock()))
        }
        return finished
    }

    private suspend fun complete(
        config: ModelConfig,
        messages: List<ModelMessage>,
        tools: List<dev.forge.ide.model.ModelToolSpec>,
        sink: AgentEventSink,
        sessionId: String,
    ): ModelResponse {
        val request = ModelRequest(
            config = config,
            messages = messages,
            tools = tools,
            toolChoice = if (tools.isEmpty()) ModelToolChoice.None else ModelToolChoice.Auto,
        )
        val capabilities = runCatching { gateway.capabilities(request) }.getOrNull()
        return if (config.stream && capabilities?.streaming == true) {
            gateway.stream(request) { event ->
                if (event is ModelStreamEvent.TextDelta && event.text.isNotEmpty()) {
                    sink.emit(AgentEvent.OutputDelta(sessionId, event.text, clock()))
                }
            }
        } else {
            val response = gateway.complete(request)
            if (response.content.isNotEmpty() && response.toolCalls.isEmpty()) {
                sink.emit(AgentEvent.OutputDelta(sessionId, response.content, clock()))
            }
            response
        }
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
    ): String {
        sink.emit(
            AgentEvent.ToolCallStarted(
                sessionId = request.sessionId,
                toolName = call.name,
                role = request.definition.role,
                timestampMillis = clock(),
            ),
        )
        val result = router.invoke(
            toolName = call.name,
            input = ToolInput(bridge.toToolArguments(call.arguments)),
            context = context,
        )
        val record = when (result) {
            is ToolResult.Success -> {
                val path = result.output.content["path"]?.let { value ->
                    (value as? dev.forge.ide.tools.JsonValue.Str)?.value
                } ?: call.arguments.stringOrNull("path")
                path?.let { rememberPath(call.name, it, filesInspected, filesChanged, request.permissionLevel) }
                ToolActionRecord(
                    toolName = call.name,
                    success = true,
                    summary = result.output.displayText ?: "ok",
                    path = path,
                )
            }

            is ToolResult.Failure -> {
                errors += AgentError(
                    code = if (result.error.code == ToolErrorCode.PERMISSION_DENIED) {
                        AgentErrorCode.PERMISSION_DENIED
                    } else {
                        AgentErrorCode.TOOL_FAILURE
                    },
                    message = result.error.message ?: "Tool '${call.name}' failed",
                    role = request.definition.role,
                    sessionId = request.sessionId,
                    details = mapOf("tool" to call.name),
                )
                ToolActionRecord(call.name, false, result.error.message ?: "failed")
            }

            is ToolResult.ApprovalRequired -> {
                errors += AgentError(
                    code = AgentErrorCode.PERMISSION_DENIED,
                    message = result.request.reason,
                    role = request.definition.role,
                    sessionId = request.sessionId,
                    details = mapOf("tool" to call.name),
                )
                ToolActionRecord(call.name, false, result.request.reason)
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
        return when (result) {
            is ToolResult.Success -> bridge.renderToolOutput(result.output.content, result.output.displayText)
            is ToolResult.Failure -> "ERROR: ${result.error.message}"
            is ToolResult.ApprovalRequired -> "APPROVAL_REQUIRED: ${result.request.reason}"
        }
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
        val result = invoker.invoke(childRequest)
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
        )
    }

    private fun cancelled(
        request: AgentLoopRequest,
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
        )
    }

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
