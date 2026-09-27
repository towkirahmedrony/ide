package com.agentx.app.tools

/**
 * Access a tool requires, aligned with the Agent [com.agentx.app.agent.domain.PermissionLevel]
 * names. Declared here so the Tool System does not depend on Agent Core.
 *
 * A tool lists the levels it needs. The router compares them to the grants on
 * [ToolExecutionContext] and never runs when a grant is missing.
 */
enum class ToolPermissionLevel {
    READ_ONLY,
    WORKSPACE_WRITE,
    COMMAND_EXECUTION,
    NETWORK,
    GIT_WRITE,
}
