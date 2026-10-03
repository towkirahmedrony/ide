package com.agentx.app.agent.runtime

import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.agent.domain.PermissionLevel
import com.agentx.app.agent.policy.AgentToolPolicy
import com.agentx.app.agent.protocol.AgentProtocol
import com.agentx.app.tools.Json
import com.agentx.app.tools.ToolDefinition
import com.agentx.app.tools.ToolErrorCode
import com.agentx.app.tools.ToolExecutionContext
import com.agentx.app.tools.ToolExecutionError
import com.agentx.app.tools.ToolInput
import com.agentx.app.tools.ToolResult
import com.agentx.app.tools.ToolRouter
import com.agentx.app.tools.effectiveAvailability

/**
 * The deterministic authorization gate in front of the Tool System.
 *
 * This is where the role's policy is enforced for real. The list the loop passed
 * in is treated as a *hint about what to offer the model*, never as permission:
 * the router re-derives the answer from [AgentToolPolicy] and the tool's own
 * declaration every time a call arrives. A tool that reaches this object without
 * being authorized for [role] — malformed model output, a replayed call, a caller
 * that assembled its own list — is refused before anything is executed, which is
 * what keeps the model from being the final authority on whether a tool runs.
 *
 * The checks run in order, each with its own structured error so a caller can
 * branch on the reason instead of parsing a message:
 *
 *  1. protocol tools belong to the agent loop, not the Tool System;
 *  2. an unknown tool is reported as unknown, not as a permission problem;
 *  3. a declared-but-unavailable tool is reported as unavailable;
 *  4. role authorization — the policy, which no caller can widen;
 *  5. visibility — the model was not told about this tool either;
 *  6. the capability ceiling of the role's permission level;
 *  7. then the real router, which applies the declared permission, the
 *     connection check, the approval gate for mutating tools and the executor.
 */
class ScopedToolRouter(
    private val inner: ToolRouter,
    private val role: AgentRole,
    /** The tools the model was offered. A hint, never a grant. */
    private val allowedTools: Set<String>,
    private val permissionLevel: PermissionLevel,
    /** Resolves a tool definition so availability is judged on the tool itself. */
    private val definitionOf: (String) -> ToolDefinition?,
    private val toolAllowed: (String) -> Boolean,
) : ToolRouter {

    override suspend fun invoke(
        toolName: String,
        input: ToolInput,
        context: ToolExecutionContext,
    ): ToolResult {
        // 1. The loop owns these; a model must not route them back through tools.
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

        // 2. Unknown before unauthorized: an unknown name is not a scope problem,
        // and reporting it as one would hide a typo behind a policy message.
        val definition = definitionOf(toolName)
            ?: return ToolResult.Failure(
                toolName = toolName,
                error = ToolExecutionError(
                    code = ToolErrorCode.UNKNOWN_TOOL,
                    message = "No tool named '$toolName' is registered",
                    toolName = toolName,
                ),
            )

        // 3. Declared but not implemented: unavailable, never "denied", so the
        // caller can tell "not built yet" apart from "not permitted".
        if (!definition.effectiveAvailability.isAvailable) {
            return ToolResult.Failure(
                toolName = toolName,
                error = ToolExecutionError(
                    code = ToolErrorCode.TOOL_UNAVAILABLE,
                    message = "Tool '$toolName' is not available in this build",
                    toolName = toolName,
                    details = mapOf("role" to Json.of(role.name)),
                ),
            )
        }

        // 4. The role's policy, re-decided here and not taken from the caller. Only a
        // tool the policy names can be denied on role grounds: a runtime-contributed
        // tool it does not name keeps its own declared requirements, but it still has
        // to be visible below, which is what stops a hidden tool from being called.
        if (AgentToolPolicy.governs(toolName) && !AgentToolPolicy.isAuthorized(role, toolName)) {
            return deny(
                toolName = toolName,
                toolId = toolName,
                message = "Tool '$toolName' is not allowed for the ${role.name} role",
                reason = "role-policy",
            )
        }

        // 5. Visibility: the model must not have been told about it either.
        if (toolName !in allowedTools) {
            return deny(
                toolName = toolName,
                toolId = toolName,
                message = "Tool '$toolName' is outside this agent's scope (${permissionLevel.name})",
                reason = "not-visible",
            )
        }

        // 6. The permission ceiling of the role's level, judged on declared capabilities.
        if (!toolAllowed(toolName)) {
            return deny(
                toolName = toolName,
                toolId = toolName,
                message = "Tool '$toolName' exceeds the ${permissionLevel.name} permission level",
                reason = "capability-ceiling",
            )
        }

        // 7. The real router applies the declared permission (including the
        // approval gate for mutating tools), the connection check and the executor.
        return inner.invoke(toolName, input, context)
    }

    private fun deny(toolName: String, toolId: String, message: String, reason: String): ToolResult.Failure =
        ToolResult.Failure(
            toolName = toolName,
            error = ToolExecutionError(
                code = ToolErrorCode.PERMISSION_DENIED,
                message = message,
                toolName = toolName,
                details = mapOf(
                    "role" to Json.of(role.name),
                    "permissionLevel" to Json.of(permissionLevel.name),
                    "reason" to Json.of(reason),
                    "toolId" to Json.of(toolId),
                ),
            ),
        )
}
