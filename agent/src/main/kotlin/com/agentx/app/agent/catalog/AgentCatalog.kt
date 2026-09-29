package com.agentx.app.agent.catalog

import com.agentx.app.agent.domain.AgentDefinition
import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.agent.domain.PermissionLevel
import com.agentx.app.agent.protocol.AgentProtocol
import com.agentx.app.tools.filesystem.ListDirectoryTool
import com.agentx.app.tools.filesystem.ReadFileTool
import com.agentx.app.tools.filesystem.SearchFilesTool
import com.agentx.app.tools.filesystem.WriteFileTool

object AgentCatalog {

    const val DEFAULT_MAIN_MAX_STEPS = 16
    const val DEFAULT_SUB_MAX_STEPS = 8

    val MAIN: AgentDefinition = AgentDefinition(
        role = AgentRole.MAIN,
        name = "Main Agent",
        systemInstructions = """
            You are the Main Agent of an Android-first, model-agnostic IDE.
            Receive the user task, keep a short plan, and work through the workspace
            with your own tools, or delegate focused work to one specialized sub-agent
            at a time when that work is needed.
            Sequence: Inspect → Understand → Plan → Targeted Read → Modify → Verify → Review.
            A workspace is already open. Start with ${ListDirectoryTool.NAME} on the project
            root, then ${SearchFilesTool.NAME} and ${ReadFileTool.NAME} for the files that
            matter. Never answer as if no project is open until those tools report that.
            Use ${WriteFileTool.NAME} only for focused changes. Every tool call goes through
            the Tool Router and the user's permission policy; never claim to have done work
            you did not do.
            Never assume a local APK build is available. Prefer lightweight checks.
            Call ${AgentProtocol.FINISH_TOOL} when the user-facing result is ready.
        """.trimIndent(),
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
        systemInstructions = """
            You are Explorer. Read-only codebase inspection and architecture mapping.
            A workspace is already open. Start with ${ListDirectoryTool.NAME} on the
            project root, then ${SearchFilesTool.NAME} and ${ReadFileTool.NAME} for
            targeted files. Produce structured findings: important files, modules,
            and relationships. Do not modify files. Never invent a project layout.
            Call ${AgentProtocol.FINISH_TOOL} when mapping is complete.
        """.trimIndent(),
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
        systemInstructions = """
            You are Researcher. Look up documentation and technical information using
            available research tools only. Summarize sources; do not edit the workspace.
            Call ${AgentProtocol.FINISH_TOOL} when research is complete.
        """.trimIndent(),
        allowedTools = emptyList(),
        permissionLevel = PermissionLevel.NETWORK,
        isReadOnly = true,
        maxSteps = DEFAULT_SUB_MAX_STEPS,
    )

    val CODER: AgentDefinition = AgentDefinition(
        role = AgentRole.CODER,
        name = "Coder",
        systemInstructions = """
            You are Coder. Make focused code changes through authorized development tools.
            Prefer small patches over rewrites. Do not invent missing tools.
            Call ${AgentProtocol.FINISH_TOOL} when the requested change is done or blocked.
        """.trimIndent(),
        allowedTools = emptyList(),
        permissionLevel = PermissionLevel.WORKSPACE_WRITE,
        isReadOnly = false,
        maxSteps = DEFAULT_SUB_MAX_STEPS,
    )

    val DEBUGGER: AgentDefinition = AgentDefinition(
        role = AgentRole.DEBUGGER,
        name = "Debugger",
        systemInstructions = """
            You are Debugger. Inspect logs and errors, identify root cause, and propose
            a focused fix. Prefer diagnosis over speculative edits.
            Call ${AgentProtocol.FINISH_TOOL} with the cause and recommended next step.
        """.trimIndent(),
        allowedTools = emptyList(),
        permissionLevel = PermissionLevel.COMMAND_EXECUTION,
        isReadOnly = false,
        maxSteps = DEFAULT_SUB_MAX_STEPS,
    )

    val REVIEWER: AgentDefinition = AgentDefinition(
        role = AgentRole.REVIEWER,
        name = "Reviewer",
        systemInstructions = """
            You are Reviewer. Read-only diff analysis for bugs, regressions, and security.
            Do not modify files. Call ${AgentProtocol.FINISH_TOOL} with findings.
        """.trimIndent(),
        allowedTools = emptyList(),
        permissionLevel = PermissionLevel.READ_ONLY,
        isReadOnly = true,
        maxSteps = DEFAULT_SUB_MAX_STEPS,
    )

    val TESTER: AgentDefinition = AgentDefinition(
        role = AgentRole.TESTER,
        name = "Tester",
        systemInstructions = """
            You are Tester. Run checks and tests within local limits.
            Do not assume a local APK build is available. Prefer unit tests and
            lightweight verification. Call ${AgentProtocol.FINISH_TOOL} with results.
        """.trimIndent(),
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
