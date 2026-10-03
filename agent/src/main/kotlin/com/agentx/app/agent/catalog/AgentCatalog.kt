package com.agentx.app.agent.catalog

import com.agentx.app.agent.domain.AgentDefinition
import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.agent.domain.PermissionLevel
import com.agentx.app.agent.model.AgentModelIds
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
        modelPreference = AgentModelIds.GEMINI,
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
        modelPreference = AgentModelIds.GROQ,
    )

    val RESEARCHER: AgentDefinition = AgentDefinition(
        role = AgentRole.RESEARCHER,
        name = "Researcher",
        systemInstructions = DefaultAgentPrompts.RESEARCHER,
        allowedTools = emptyList(),
        permissionLevel = PermissionLevel.NETWORK,
        isReadOnly = true,
        maxSteps = DEFAULT_SUB_MAX_STEPS,
        modelPreference = AgentModelIds.GEMINI,
    )

    val CODER: AgentDefinition = AgentDefinition(
        role = AgentRole.CODER,
        name = "Coder",
        systemInstructions = DefaultAgentPrompts.CODER,
        allowedTools = emptyList(),
        permissionLevel = PermissionLevel.WORKSPACE_WRITE,
        isReadOnly = false,
        maxSteps = DEFAULT_SUB_MAX_STEPS,
        modelPreference = AgentModelIds.QWEN_CODER,
    )

    val DEBUGGER: AgentDefinition = AgentDefinition(
        role = AgentRole.DEBUGGER,
        name = "Debugger",
        systemInstructions = DefaultAgentPrompts.DEBUGGER,
        allowedTools = emptyList(),
        permissionLevel = PermissionLevel.COMMAND_EXECUTION,
        isReadOnly = false,
        maxSteps = DEFAULT_SUB_MAX_STEPS,
        modelPreference = AgentModelIds.QWEN_CODER,
    )

    val REVIEWER: AgentDefinition = AgentDefinition(
        role = AgentRole.REVIEWER,
        name = "Reviewer",
        systemInstructions = DefaultAgentPrompts.REVIEWER,
        allowedTools = emptyList(),
        permissionLevel = PermissionLevel.READ_ONLY,
        isReadOnly = true,
        maxSteps = DEFAULT_SUB_MAX_STEPS,
        modelPreference = AgentModelIds.GROQ,
    )

    val TESTER: AgentDefinition = AgentDefinition(
        role = AgentRole.TESTER,
        name = "Tester",
        systemInstructions = DefaultAgentPrompts.TESTER,
        allowedTools = emptyList(),
        permissionLevel = PermissionLevel.COMMAND_EXECUTION,
        isReadOnly = false,
        maxSteps = DEFAULT_SUB_MAX_STEPS,
        modelPreference = AgentModelIds.GROQ,
    )

    val PLANNER: AgentDefinition = AgentDefinition(
        role = AgentRole.PLANNER,
        name = "Planner",
        systemInstructions = DefaultAgentPrompts.PLANNER,
        allowedTools = listOf(
            ListDirectoryTool.NAME,
            SearchFilesTool.NAME,
            ReadFileTool.NAME,
        ),
        permissionLevel = PermissionLevel.READ_ONLY,
        isReadOnly = true,
        maxSteps = DEFAULT_SUB_MAX_STEPS,
    )

    val FAST_CODER: AgentDefinition = AgentDefinition(
        role = AgentRole.FAST_CODER,
        name = "Fast Coder",
        systemInstructions = DefaultAgentPrompts.FAST_CODER,
        allowedTools = listOf(
            ListDirectoryTool.NAME,
            SearchFilesTool.NAME,
            ReadFileTool.NAME,
            WriteFileTool.NAME,
        ),
        permissionLevel = PermissionLevel.WORKSPACE_WRITE,
        isReadOnly = false,
        maxSteps = DEFAULT_SUB_MAX_STEPS,
    )

    val SECURITY_REVIEWER: AgentDefinition = AgentDefinition(
        role = AgentRole.SECURITY_REVIEWER,
        name = "Security Reviewer",
        systemInstructions = DefaultAgentPrompts.SECURITY_REVIEWER,
        allowedTools = listOf(
            ListDirectoryTool.NAME,
            SearchFilesTool.NAME,
            ReadFileTool.NAME,
        ),
        permissionLevel = PermissionLevel.READ_ONLY,
        isReadOnly = true,
        maxSteps = DEFAULT_SUB_MAX_STEPS,
    )

    val DOCS: AgentDefinition = AgentDefinition(
        role = AgentRole.DOCS,
        name = "Docs",
        systemInstructions = DefaultAgentPrompts.DOCS,
        allowedTools = listOf(
            ListDirectoryTool.NAME,
            SearchFilesTool.NAME,
            ReadFileTool.NAME,
            WriteFileTool.NAME,
        ),
        permissionLevel = PermissionLevel.WORKSPACE_WRITE,
        isReadOnly = false,
        maxSteps = DEFAULT_SUB_MAX_STEPS,
    )

    val COMMIT_PR: AgentDefinition = AgentDefinition(
        role = AgentRole.COMMIT_PR,
        name = "Commit/PR",
        systemInstructions = DefaultAgentPrompts.COMMIT_PR,
        allowedTools = emptyList(),
        permissionLevel = PermissionLevel.GIT_WRITE,
        isReadOnly = false,
        maxSteps = DEFAULT_SUB_MAX_STEPS,
    )

    fun all(): List<AgentDefinition> = listOf(
        MAIN,
        EXPLORER,
        RESEARCHER,
        CODER,
        DEBUGGER,
        REVIEWER,
        TESTER,
        PLANNER,
        FAST_CODER,
        SECURITY_REVIEWER,
        DOCS,
        COMMIT_PR,
    )

    fun definition(role: AgentRole): AgentDefinition = when (role) {
        AgentRole.MAIN -> MAIN
        AgentRole.EXPLORER -> EXPLORER
        AgentRole.RESEARCHER -> RESEARCHER
        AgentRole.CODER -> CODER
        AgentRole.DEBUGGER -> DEBUGGER
        AgentRole.REVIEWER -> REVIEWER
        AgentRole.TESTER -> TESTER
        AgentRole.PLANNER -> PLANNER
        AgentRole.FAST_CODER -> FAST_CODER
        AgentRole.SECURITY_REVIEWER -> SECURITY_REVIEWER
        AgentRole.DOCS -> DOCS
        AgentRole.COMMIT_PR -> COMMIT_PR
    }
}
