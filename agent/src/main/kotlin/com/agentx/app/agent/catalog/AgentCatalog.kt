package com.agentx.app.agent.catalog

import com.agentx.app.agent.domain.AgentDefinition
import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.agent.domain.PermissionLevel
import com.agentx.app.agent.prompt.DefaultAgentPrompts
import com.agentx.app.agent.protocol.AgentProtocol
import com.agentx.app.tools.filesystem.ListDirectoryTool
import com.agentx.app.tools.filesystem.ReadFileTool
import com.agentx.app.tools.filesystem.SearchFilesTool
import com.agentx.app.tools.filesystem.WriteFileTool

/**
 * The static shape of each agent: name, tools, permissions and step budget.
 *
 * The system prompt is not defined here any more. Each definition references the
 * centralized default from [DefaultAgentPrompts], and the active prompt is
 * resolved at run time by [com.agentx.app.agent.prompt.PromptManager] so a user
 * override in Settings takes effect without touching this catalog.
 */
object AgentCatalog {

    const val DEFAULT_MAIN_MAX_STEPS = 16
    const val DEFAULT_SUB_MAX_STEPS = 8

    val MAIN: AgentDefinition = AgentDefinition(
        role = AgentRole.MAIN,
        name = "Main Agent",
        systemInstructions = DefaultAgentPrompts.MAIN,
        allowedTools = listOf(
            AgentProtocol.DELEGATE_TOOL,
            AgentProtocol.FINISH_TOOL,
            ListDirectoryTool.NAME,
            SearchFilesTool.NAME,
            ReadFileTool.NAME,
            WriteFileTool.NAME,
        ),
        permissionLevel = PermissionLevel.WORKSPACE_WRITE,
        isReadOnly = false,
        maxSteps = DEFAULT_MAIN_MAX_STEPS,
    )

    val EXPLORER: AgentDefinition = AgentDefinition(
        role = AgentRole.EXPLORER,
        name = "Explorer",
        systemInstructions = DefaultAgentPrompts.EXPLORER,
        allowedTools = listOf(
            ListDirectoryTool.NAME,
            SearchFilesTool.NAME,
            ReadFileTool.NAME,
        ),
        permissionLevel = PermissionLevel.READ_ONLY,
        isReadOnly = true,
        maxSteps = DEFAULT_SUB_MAX_STEPS,
    )

    val RESEARCHER: AgentDefinition = AgentDefinition(
        role = AgentRole.RESEARCHER,
        name = "Researcher",
        systemInstructions = DefaultAgentPrompts.RESEARCHER,
        allowedTools = emptyList(),
        permissionLevel = PermissionLevel.NETWORK,
        isReadOnly = true,
        maxSteps = DEFAULT_SUB_MAX_STEPS,
    )

    val CODER: AgentDefinition = AgentDefinition(
        role = AgentRole.CODER,
        name = "Coder",
        systemInstructions = DefaultAgentPrompts.CODER,
        allowedTools = emptyList(),
        permissionLevel = PermissionLevel.WORKSPACE_WRITE,
        isReadOnly = false,
        maxSteps = DEFAULT_SUB_MAX_STEPS,
    )

    val DEBUGGER: AgentDefinition = AgentDefinition(
        role = AgentRole.DEBUGGER,
        name = "Debugger",
        systemInstructions = DefaultAgentPrompts.DEBUGGER,
        allowedTools = emptyList(),
        permissionLevel = PermissionLevel.COMMAND_EXECUTION,
        isReadOnly = false,
        maxSteps = DEFAULT_SUB_MAX_STEPS,
    )

    val REVIEWER: AgentDefinition = AgentDefinition(
        role = AgentRole.REVIEWER,
        name = "Reviewer",
        systemInstructions = DefaultAgentPrompts.REVIEWER,
        allowedTools = emptyList(),
        permissionLevel = PermissionLevel.READ_ONLY,
        isReadOnly = true,
        maxSteps = DEFAULT_SUB_MAX_STEPS,
    )

    val TESTER: AgentDefinition = AgentDefinition(
        role = AgentRole.TESTER,
        name = "Tester",
        systemInstructions = DefaultAgentPrompts.TESTER,
        allowedTools = emptyList(),
        permissionLevel = PermissionLevel.COMMAND_EXECUTION,
        isReadOnly = false,
        maxSteps = DEFAULT_SUB_MAX_STEPS,
    )

    fun all(): List<AgentDefinition> = listOf(MAIN, EXPLORER, RESEARCHER, CODER, DEBUGGER, REVIEWER, TESTER)

    fun definition(role: AgentRole): AgentDefinition = when (role) {
        AgentRole.MAIN -> MAIN
        AgentRole.EXPLORER -> EXPLORER
        AgentRole.RESEARCHER -> RESEARCHER
        AgentRole.CODER -> CODER
        AgentRole.DEBUGGER -> DEBUGGER
        AgentRole.REVIEWER -> REVIEWER
        AgentRole.TESTER -> TESTER
    }
}
