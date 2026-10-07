package com.agentx.app.tools

/**
 * The single entry point for running a tool. It resolves a tool by name,
 * validates its arguments against the declared schema, checks the permission
 * policy, and only then executes the tool through [ToolExecutor]. The model
 * layer and the agent core must always go through this flow; they never call a
 * [Tool] directly.
 */
interface ToolRouter {
    suspend fun invoke(
        toolName: String,
        input: ToolInput = ToolInput(),
        context: ToolExecutionContext = ToolExecutionContext.EMPTY,
    ): ToolResult

    suspend fun invoke(
        call: ToolCall,
        context: ToolExecutionContext = ToolExecutionContext.EMPTY,
    ): ToolResult = invoke(
        toolName = call.toolId.value,
        input = call.input,
        context = context.copy(callId = context.callId ?: call.id),
    )
}

class DefaultToolRouter(
    private val registry: ToolRegistry,
    private val policy: ToolPermissionPolicy = ToolPermissionPolicy.default(),
    private val executor: ToolExecutor = DefaultToolExecutor(),
    private val connections: ToolConnectionAuthorizer = MissingToolConnectionAuthorizer,
    /**
     * The user-owned enablement, read live so a tool turned off in Settings is
     * refused here even if a caller assembled its own tool list. Defaults to
     * "everything enabled", so a caller that passes nothing is unchanged.
     */
    private val preferences: ToolPreferences = AllowAllToolPreferences,
) : ToolRouter {

    override suspend fun invoke(
        toolName: String,
        input: ToolInput,
        context: ToolExecutionContext,
    ): ToolResult {
        val tool = registry.find(toolName)
            ?: return unknownTool(toolName)

        // 0. The user's own switch, checked before anything else: a disabled tool
        // must never run, regardless of who assembled the request. Reported with
        // its own code so "turned off" is distinguishable from "not permitted".
        if (!preferences.isEnabled(toolName)) {
            return ToolResult.Failure(
                toolName = toolName,
                error = ToolExecutionError(
                    code = ToolErrorCode.TOOL_DISABLED,
                    message = "Tool '$toolName' is disabled in Settings",
                    toolName = toolName,
                    details = mapOf("toolId" to Json.of(toolName)),
                ),
            )
        }

        val executionContext = context.copy(
            callId = context.callId ?: "tool-$toolName",
        )

        val validationErrors = tool.definition.inputSchema.validate(input.arguments)
        if (validationErrors.isNotEmpty()) {
            return ToolResult.Failure(
                toolName = toolName,
                error = ToolExecutionError(
                    code = ToolErrorCode.INVALID_ARGUMENTS,
                    message = "Invalid arguments for '$toolName'",
                    toolName = toolName,
                    details = mapOf("errors" to Json.array(validationErrors.map { Json.of(it) })),
                ),
            )
        }

        val missing = tool.definition.requiredPermissions - executionContext.grantedPermissions
        if (missing.isNotEmpty()) {
            return ToolResult.Failure(
                toolName = toolName,
                error = ToolExecutionError(
                    code = ToolErrorCode.PERMISSION_DENIED,
                    message = "Tool '$toolName' requires ${missing.joinToString { it.name }}",
                    toolName = toolName,
                    details = mapOf(
                        "missing" to Json.array(missing.map { Json.of(it.name) }),
                    ),
                ),
            )
        }

        val requirement = tool.definition.connectionRequirement
        if (requirement != null) {
            val authorization = connections.authorize(requirement, connectionId = null)
            if (authorization is ToolConnectionAuthorization.Denied) {
                val error = authorization.error
                return ToolResult.Failure(
                    toolName = toolName,
                    error = ToolExecutionError(
                        code = ToolErrorCode.CONNECTION_UNAUTHORIZED,
                        message = error.message,
                        toolName = toolName,
                        details = mapOf(
                            "denial" to Json.of(error.denial.name),
                            "type" to Json.of(error.type.name),
                            "capability" to Json.of(error.capability.id),
                        ),
                    ),
                )
            }
        }

        val permission = policy.evaluate(ToolPermissionRequest(tool.definition, input, executionContext))
        when (permission.decision) {
            ToolPermissionDecision.ALLOW -> Unit

            ToolPermissionDecision.DENY -> return ToolResult.Failure(
                toolName = toolName,
                error = ToolExecutionError(
                    code = ToolErrorCode.PERMISSION_DENIED,
                    message = permission.reason ?: "Permission denied for '$toolName'",
                    toolName = toolName,
                    details = mapOf("decision" to Json.of(permission.decision.name)),
                ),
            )

            ToolPermissionDecision.ASK -> {
                val approval = executionContext.approval
                if (approval == null) {
                    return ToolResult.ApprovalRequired(
                        toolName = toolName,
                        request = ToolApprovalRequest(
                            toolName = toolName,
                            input = input,
                            reason = permission.reason ?: "Tool '$toolName' requires approval",
                        ),
                    )
                }
                if (!approval.approved) {
                    return ToolResult.Failure(
                        toolName = toolName,
                        error = ToolExecutionError(
                            code = ToolErrorCode.PERMISSION_DENIED,
                            message = approval.reason ?: "The user denied '$toolName'",
                            toolName = toolName,
                            details = mapOf("decision" to Json.of(ToolPermissionDecision.DENY.name)),
                        ),
                    )
                }
            }
        }

        return try {
            executor.execute(tool, input, executionContext)
        } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            ToolResult.Failure(
                toolName = toolName,
                error = ToolExecutionError(
                    code = ToolErrorCode.EXECUTION_FAILED,
                    message = error.message ?: "Tool '$toolName' failed unexpectedly",
                    toolName = toolName,
                    cause = error,
                ),
            )
        }
    }

    private fun unknownTool(toolName: String): ToolResult.Failure = ToolResult.Failure(
        toolName = toolName,
        error = ToolExecutionError(
            code = ToolErrorCode.UNKNOWN_TOOL,
            message = "No tool named '$toolName' is registered",
            toolName = toolName,
        ),
    )
}
