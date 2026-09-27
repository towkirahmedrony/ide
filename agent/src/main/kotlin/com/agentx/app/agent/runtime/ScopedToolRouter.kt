package com.agentx.app.agent.runtime

import com.agentx.app.agent.domain.PermissionLevel
import com.agentx.app.agent.protocol.AgentProtocol
import com.agentx.app.tools.Json
import com.agentx.app.tools.ToolErrorCode
import com.agentx.app.tools.ToolExecutionContext
import com.agentx.app.tools.ToolExecutionError
import com.agentx.app.tools.ToolInput
import com.agentx.app.tools.ToolResult
import com.agentx.app.tools.ToolRouter

/**
 * Enforces the agent's tool allow-list and permission ceiling before any call
 * reaches the real Tool System router.
 */
class ScopedToolRouter(
    private val inner: ToolRouter,
    private val allowedTools: Set<String>,
    private val permissionLevel: PermissionLevel,
    private val toolAllowed: (String) -> Boolean,
) : ToolRouter {

    override suspend fun invoke(
        toolName: String,
        input: ToolInput,
        context: ToolExecutionContext,
    ): ToolResult {
        if (toolName == AgentProtocol.DELEGATE_TOOL || toolName == AgentProtocol.FINISH_TOOL) {
            return ToolResult.Failure(
                toolName = toolName,
                error = ToolExecutionError(
                    code = ToolErrorCode.PERMISSION_DENIED,
                    message = "Protocol tool '$toolName' is handled by the agent loop, not the tool router",
                    toolName = toolName,
                ),
            )
        }
        if (toolName !in allowedTools || !toolAllowed(toolName)) {
            return ToolResult.Failure(
                toolName = toolName,
                error = ToolExecutionError(
                    code = ToolErrorCode.PERMISSION_DENIED,
                    message = "Tool '$toolName' is outside this agent's scope (${permissionLevel.name})",
                    toolName = toolName,
                    details = mapOf(
                        "permissionLevel" to Json.of(permissionLevel.name),
                    ),
                ),
            )
        }
        return inner.invoke(toolName, input, context)
    }
}
