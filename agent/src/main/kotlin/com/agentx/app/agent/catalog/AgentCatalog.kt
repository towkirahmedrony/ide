package com.agentx.app.agent.catalog

import com.agentx.app.agent.domain.AgentDefinition
import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.agent.domain.PermissionLevel
import com.agentx.app.agent.model.AgentModelIds
import com.agentx.app.agent.policy.AgentToolPolicy
import com.agentx.app.agent.prompt.DefaultAgentPrompts

/**
 * The static shape of each agent: name, tools, permissions and step budget.
 *
 * Tools are no longer written out here. Each definition takes its tool list from
 * [AgentToolPolicy], which is the single authoritative place a role's tools are
 * declared. The previous per-role literals are what let MAIN and the
 * code-intelligence tools drift apart, and what left several roles with no tools
 * at all — a gap the sub-agent factory then filled with the whole registry.
 *
 * The system prompt is not defined here either. Each definition references the
 * centralized default from [DefaultAgentPrompts], and the active prompt is
 * resolved at run time by [com.agentx.app.agent.prompt.PromptManager] so a user
 * override in Settings takes effect without touching this catalog.
 */
object AgentCatalog {

    const val DEFAULT_MAIN_MAX_STEPS = 16
    const val DEFAULT_SUB_MAX_STEPS = 8

    /** The tools a role may use, resolved from the authoritative policy. */
    private fun toolsFor(role: AgentRole): List<String> = AgentToolPolicy.toolIdsFor(role)

    val MAIN: AgentDefinition = AgentDefinition(
        role = AgentRole.MAIN,
        name = "Main Agent",
        systemInstructions = DefaultAgentPrompts.MAIN,
        allowedTools = toolsFor(AgentRole.MAIN),
        permissionLevel = PermissionLevel.WORKSPACE_WRITE,
        isReadOnly = false,
        maxSteps = DEFAULT_MAIN_MAX_STEPS,
        modelPreference = AgentModelIds.GEMINI,
    )

    val EXPLORER: AgentDefinition = AgentDefinition(
        role = AgentRole.EXPLORER,
        name = "Explorer",
        systemInstructions = DefaultAgentPrompts.EXPLORER,
        allowedTools = toolsFor(AgentRole.EXPLORER),
        permissionLevel = PermissionLevel.READ_ONLY,
        isReadOnly = true,
        maxSteps = DEFAULT_SUB_MAX_STEPS,
        modelPreference = AgentModelIds.GROQ,
    )

    val RESEARCHER: AgentDefinition = AgentDefinition(
        role = AgentRole.RESEARCHER,
        name = "Researcher",
        systemInstructions = DefaultAgentPrompts.RESEARCHER,
        allowedTools = toolsFor(AgentRole.RESEARCHER),
        permissionLevel = PermissionLevel.NETWORK,
        isReadOnly = true,
        maxSteps = DEFAULT_SUB_MAX_STEPS,
        modelPreference = AgentModelIds.GEMINI,
    )

    val CODER: AgentDefinition = AgentDefinition(
        role = AgentRole.CODER,
        name = "Coder",
        systemInstructions = DefaultAgentPrompts.CODER,
        allowedTools = toolsFor(AgentRole.CODER),
        permissionLevel = PermissionLevel.WORKSPACE_WRITE,
        isReadOnly = false,
        maxSteps = DEFAULT_SUB_MAX_STEPS,
        modelPreference = AgentModelIds.QWEN_CODER,
    )

    val DEBUGGER: AgentDefinition = AgentDefinition(
        role = AgentRole.DEBUGGER,
        name = "Debugger",
        systemInstructions = DefaultAgentPrompts.DEBUGGER,
        allowedTools = toolsFor(AgentRole.DEBUGGER),
        permissionLevel = PermissionLevel.COMMAND_EXECUTION,
        isReadOnly = false,
        maxSteps = DEFAULT_SUB_MAX_STEPS,
        modelPreference = AgentModelIds.QWEN_CODER,
    )

    val REVIEWER: AgentDefinition = AgentDefinition(
        role = AgentRole.REVIEWER,
        name = "Reviewer",
        systemInstructions = DefaultAgentPrompts.REVIEWER,
        allowedTools = toolsFor(AgentRole.REVIEWER),
        permissionLevel = PermissionLevel.READ_ONLY,
        isReadOnly = true,
        maxSteps = DEFAULT_SUB_MAX_STEPS,
        modelPreference = AgentModelIds.GROQ,
    )

    val TESTER: AgentDefinition = AgentDefinition(
        role = AgentRole.TESTER,
        name = "Tester",
        systemInstructions = DefaultAgentPrompts.TESTER,
        allowedTools = toolsFor(AgentRole.TESTER),
        permissionLevel = PermissionLevel.COMMAND_EXECUTION,
        isReadOnly = false,
        maxSteps = DEFAULT_SUB_MAX_STEPS,
        modelPreference = AgentModelIds.GROQ,
    )

    val PLANNER: AgentDefinition = AgentDefinition(
        role = AgentRole.PLANNER,
        name = "Planner",
        systemInstructions = DefaultAgentPrompts.PLANNER,
        allowedTools = toolsFor(AgentRole.PLANNER),
        permissionLevel = PermissionLevel.READ_ONLY,
        isReadOnly = true,
        maxSteps = DEFAULT_SUB_MAX_STEPS,
    )

    val FAST_CODER: AgentDefinition = AgentDefinition(
        role = AgentRole.FAST_CODER,
        name = "Fast Coder",
        systemInstructions = DefaultAgentPrompts.FAST_CODER,
        allowedTools = toolsFor(AgentRole.FAST_CODER),
        permissionLevel = PermissionLevel.WORKSPACE_WRITE,
        isReadOnly = false,
        maxSteps = DEFAULT_SUB_MAX_STEPS,
    )

    val SECURITY_REVIEWER: AgentDefinition = AgentDefinition(
        role = AgentRole.SECURITY_REVIEWER,
        name = "Security Reviewer",
        systemInstructions = DefaultAgentPrompts.SECURITY_REVIEWER,
        allowedTools = toolsFor(AgentRole.SECURITY_REVIEWER),
        permissionLevel = PermissionLevel.READ_ONLY,
        isReadOnly = true,
        maxSteps = DEFAULT_SUB_MAX_STEPS,
    )

    val DOCS: AgentDefinition = AgentDefinition(
        role = AgentRole.DOCS,
        name = "Docs",
        systemInstructions = DefaultAgentPrompts.DOCS,
        allowedTools = toolsFor(AgentRole.DOCS),
        permissionLevel = PermissionLevel.WORKSPACE_WRITE,
        isReadOnly = false,
        maxSteps = DEFAULT_SUB_MAX_STEPS,
    )

    val COMMIT_PR: AgentDefinition = AgentDefinition(
        role = AgentRole.COMMIT_PR,
        name = "Commit/PR",
        systemInstructions = DefaultAgentPrompts.COMMIT_PR,
        allowedTools = toolsFor(AgentRole.COMMIT_PR),
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
