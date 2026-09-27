package com.agentx.app.tools.extensions

import com.agentx.app.tools.Tool
import com.agentx.app.tools.ToolCapability
import com.agentx.app.tools.ToolCategory
import com.agentx.app.tools.ToolDefinition
import com.agentx.app.tools.ToolErrorCode
import com.agentx.app.tools.ToolExecutionContext
import com.agentx.app.tools.ToolExecutionError
import com.agentx.app.tools.ToolInput
import com.agentx.app.tools.ToolOutput
import com.agentx.app.tools.ToolPermissionDecision
import com.agentx.app.tools.ToolPermissionLevel

/**
 * Placeholder tools for categories that must not run yet. They exist so the
 * registry, router, and agent loop already know the extension points.
 *
 * Real shell, git-write, browser, MCP, and unrestricted network access are
 * intentionally not implemented here.
 */
abstract class UnavailableCategoryTool(
    name: String,
    description: String,
    category: ToolCategory,
    capabilities: Set<ToolCapability>,
    required: Set<ToolPermissionLevel>,
) : Tool {

    override val definition = ToolDefinition(
        name = name,
        description = description,
        permission = ToolPermissionDecision.DENY,
        capabilities = capabilities,
        category = category,
        requiredPermissions = required,
        metadata = mapOf("implemented" to "false"),
    )

    override suspend fun execute(input: ToolInput, context: ToolExecutionContext): ToolOutput {
        throw ToolExecutionError(
            code = ToolErrorCode.PERMISSION_DENIED,
            message = "'${definition.name}' is not enabled yet",
            toolName = definition.name,
        )
    }
}

class RunCommandToolStub : UnavailableCategoryTool(
    name = "run_command",
    description = "Reserved for workspace command execution. Not enabled.",
    category = ToolCategory.COMMAND,
    capabilities = setOf(ToolCapability.SHELL, ToolCapability.MUTATING),
    required = setOf(ToolPermissionLevel.COMMAND_EXECUTION),
)

class GitWriteToolStub : UnavailableCategoryTool(
    name = "git_write",
    description = "Reserved for git write operations. Not enabled.",
    category = ToolCategory.GIT,
    capabilities = setOf(ToolCapability.GIT, ToolCapability.MUTATING),
    required = setOf(ToolPermissionLevel.GIT_WRITE),
)

class WebFetchToolStub : UnavailableCategoryTool(
    name = "web_fetch",
    description = "Reserved for network fetch. Not enabled.",
    category = ToolCategory.WEB,
    capabilities = setOf(ToolCapability.NETWORK, ToolCapability.READ_ONLY),
    required = setOf(ToolPermissionLevel.NETWORK),
)

class BrowserToolStub : UnavailableCategoryTool(
    name = "browser",
    description = "Reserved for browser automation. Not enabled.",
    category = ToolCategory.BROWSER,
    capabilities = setOf(ToolCapability.NETWORK, ToolCapability.USER_INTERACTION),
    required = setOf(ToolPermissionLevel.NETWORK),
)

class McpToolStub : UnavailableCategoryTool(
    name = "mcp",
    description = "Reserved for MCP servers. Not enabled.",
    category = ToolCategory.MCP,
    capabilities = setOf(ToolCapability.NETWORK),
    required = setOf(ToolPermissionLevel.NETWORK),
)
