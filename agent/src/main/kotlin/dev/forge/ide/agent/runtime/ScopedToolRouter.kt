package dev.forge.ide.agent.runtime

import dev.forge.ide.agent.domain.PermissionLevel
import dev.forge.ide.agent.protocol.AgentProtocol
import dev.forge.ide.tools.Json
import dev.forge.ide.tools.ToolErrorCode
import dev.forge.ide.tools.ToolExecutionContext
import dev.forge.ide.tools.ToolExecutionError
import dev.forge.ide.tools.ToolInput
import dev.forge.ide.tools.ToolResult
import dev.forge.ide.tools.ToolRouter

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
