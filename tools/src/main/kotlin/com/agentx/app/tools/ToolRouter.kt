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
}

class DefaultToolRouter(
    private val registry: ToolRegistry,
    private val policy: ToolPermissionPolicy = ToolPermissionPolicy.default(),
    private val executor: ToolExecutor = DefaultToolExecutor(),
) : ToolRouter {

    override suspend fun invoke(
        toolName: String,
        input: ToolInput,
        context: ToolExecutionContext,
    ): ToolResult {
        val tool = registry.find(toolName)
            ?: return unknownTool(toolName)

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

        val permission = policy.evaluate(ToolPermissionRequest(tool.definition, input, context))
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
                val approval = context.approval
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

        return executor.execute(tool, input, context)
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
