package com.agentx.app.agent.policy

import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.agent.domain.PermissionLevel
import com.agentx.app.agent.domain.toToolGrants
import com.agentx.app.agent.runtime.ScopedToolRouter
import com.agentx.app.tools.DefaultToolRegistry
import com.agentx.app.tools.DefaultToolRouter
import com.agentx.app.tools.Tool
import com.agentx.app.tools.ToolApproval
import com.agentx.app.tools.ToolCapability
import com.agentx.app.tools.ToolDefinition
import com.agentx.app.tools.ToolErrorCode
import com.agentx.app.tools.ToolExecutionContext
import com.agentx.app.tools.ToolExecutionError
import com.agentx.app.tools.ToolInput
import com.agentx.app.tools.ToolOutput
import com.agentx.app.tools.ToolPermissionDecision
import com.agentx.app.tools.ToolPermissionLevel
import com.agentx.app.tools.ToolResult
import com.agentx.app.tools.execution.RunCommandTool
import com.agentx.app.tools.filesystem.ReadFileTool
import com.agentx.app.tools.filesystem.WriteFileTool
import com.agentx.app.tools.git.GitCommitTool
import com.agentx.app.tools.git.GitDiffTool
import com.agentx.app.tools.git.GitStatusTool
import com.agentx.app.tools.web.WebFetchTool
import com.agentx.app.tools.web.WebSearchTool
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The deterministic authorization gate, exercised for the new tool families.
 *
 * The registry holds *every* family — the exact situation a single global tool
 * list would create — and the assertions prove that registering a tool globally
 * does not make it available to every role. The ScopedToolRouter re-derives the
 * answer from [AgentToolPolicy], so a role that was never granted a tool is
 * refused even when it is offered the tool directly.
 */
class ToolFamilyAuthorizationTest {

    private val readOnlyCaps = setOf(ToolCapability.READ_ONLY, ToolCapability.FILESYSTEM)

    /** Throws once it runs, so reaching the executor is observable. */
    private class Probe(
        private val name: String,
        capabilities: Set<ToolCapability>,
        required: Set<ToolPermissionLevel> = emptySet(),
        permission: ToolPermissionDecision = ToolPermissionDecision.ALLOW,
    ) : Tool {
        override val definition = ToolDefinition(
            name = name,
            description = "Probe tool $name",
            permission = permission,
            capabilities = capabilities,
            requiredPermissions = required,
        )

        override suspend fun execute(input: ToolInput, context: ToolExecutionContext): ToolOutput =
            throw ToolExecutionError(ToolErrorCode.EXECUTION_FAILED, "reached $name", name)
    }

    /** A registry containing the real names of every implemented family. */
    private fun fullRegistry(): DefaultToolRegistry = DefaultToolRegistry().apply {
        register(Probe(ReadFileTool.NAME, readOnlyCaps, setOf(ToolPermissionLevel.READ_ONLY)))
        register(
            Probe(
                WriteFileTool.NAME,
                setOf(ToolCapability.MUTATING, ToolCapability.FILESYSTEM),
                setOf(ToolPermissionLevel.WORKSPACE_WRITE),
                ToolPermissionDecision.ASK,
            ),
        )
        register(
            Probe(
                RunCommandTool.NAME,
                setOf(ToolCapability.SHELL, ToolCapability.MUTATING),
                setOf(ToolPermissionLevel.COMMAND_EXECUTION),
                ToolPermissionDecision.ASK,
            ),
        )
        register(Probe(GitStatusTool.NAME, setOf(ToolCapability.READ_ONLY), setOf(ToolPermissionLevel.READ_ONLY)))
        register(Probe(GitDiffTool.NAME, setOf(ToolCapability.READ_ONLY), setOf(ToolPermissionLevel.READ_ONLY)))
        register(
            Probe(
                GitCommitTool.NAME,
                setOf(ToolCapability.GIT, ToolCapability.MUTATING),
                setOf(ToolPermissionLevel.GIT_WRITE),
                ToolPermissionDecision.ASK,
            ),
        )
        register(
            Probe(
                WebSearchTool.NAME,
                setOf(ToolCapability.NETWORK, ToolCapability.READ_ONLY),
                setOf(ToolPermissionLevel.NETWORK),
            ),
        )
        register(
            Probe(
                WebFetchTool.NAME,
                setOf(ToolCapability.NETWORK, ToolCapability.READ_ONLY),
                setOf(ToolPermissionLevel.NETWORK),
            ),
        )
    }

    /** The permissions a run at [level] holds, so the inner router sees a real grant. */
    private fun contextFor(level: PermissionLevel, approval: ToolApproval? = null) =
        ToolExecutionContext(grantedPermissions = level.toToolGrants()).copy(approval = approval)

    private fun scoped(
        registry: DefaultToolRegistry,
        role: AgentRole,
        offered: Set<String>,
        level: PermissionLevel,
    ): ScopedToolRouter = ScopedToolRouter(
        inner = DefaultToolRouter(registry),
        role = role,
        allowedTools = offered,
        permissionLevel = level,
        definitionOf = { name -> registry.find(name)?.definition },
        toolAllowed = { name ->
            val definition = registry.find(name)?.definition
            definition == null || level.allows(definition.capabilities)
        },
    )

    @Test
    fun `registering a tool globally does not make it available to every role`() = runBlocking {
        val registry = fullRegistry()
        // Explorer is offered the whole registry but may only read.
        val explorer = scoped(
            registry = registry,
            role = AgentRole.EXPLORER,
            offered = registry.names().toSet(),
            level = PermissionLevel.READ_ONLY,
        )
        listOf(RunCommandTool.NAME, WriteFileTool.NAME, GitCommitTool.NAME, WebSearchTool.NAME).forEach { name ->
            val failure = explorer.invoke(name, ToolInput()) as? ToolResult.Failure
                ?: error("$name must be denied for Explorer")
            assertEquals(ToolErrorCode.PERMISSION_DENIED, failure.error.code, name)
        }
        // Reading still reaches the executor.
        val read = explorer.invoke(
            ReadFileTool.NAME,
            ToolInput(),
            contextFor(PermissionLevel.READ_ONLY),
        ) as? ToolResult.Failure ?: error("read_file should have run")
        assertEquals(ToolErrorCode.EXECUTION_FAILED, read.error.code)
    }

    @Test
    fun `only the researcher holds the web tools`() = runBlocking {
        val registry = fullRegistry()
        val researcher = scoped(
            registry = registry,
            role = AgentRole.RESEARCHER,
            offered = registry.names().toSet(),
            level = PermissionLevel.NETWORK,
        )
        val reached = researcher.invoke(
            WebSearchTool.NAME,
            ToolInput(),
            contextFor(PermissionLevel.NETWORK),
        ) as? ToolResult.Failure ?: error("web_search should have reached the executor")
        assertEquals(ToolErrorCode.EXECUTION_FAILED, reached.error.code)

        // The explorer is granted neither web tool, even when offered them.
        val explorer = scoped(registry, AgentRole.EXPLORER, setOf(WebSearchTool.NAME), PermissionLevel.READ_ONLY)
        val denied = explorer.invoke(WebSearchTool.NAME, ToolInput()) as? ToolResult.Failure
            ?: error("Explorer must not search the web")
        assertEquals(ToolErrorCode.PERMISSION_DENIED, denied.error.code)
    }

    @Test
    fun `the debugger may execute commands and a read-only role may not`() = runBlocking {
        val registry = fullRegistry()
        val debugger = scoped(
            registry = registry,
            role = AgentRole.DEBUGGER,
            offered = setOf(RunCommandTool.NAME),
            level = PermissionLevel.COMMAND_EXECUTION,
        )
        // ASK pauses; it does not deny. Providing approval proves the call reaches
        // the executor rather than being refused for lack of permission.
        val paused = debugger.invoke(RunCommandTool.NAME, ToolInput(), contextFor(PermissionLevel.COMMAND_EXECUTION))
        assertEquals(ToolResult.ApprovalRequired::class, paused::class)

        val approved = debugger.invoke(
            RunCommandTool.NAME,
            ToolInput(),
            contextFor(PermissionLevel.COMMAND_EXECUTION, approval = ToolApproval.granted()),
        ) as? ToolResult.Failure ?: error("run_command should have reached the executor")
        assertEquals(ToolErrorCode.EXECUTION_FAILED, approved.error.code)

        // Reviewer is read-only and was never granted shell.
        val reviewer = scoped(registry, AgentRole.REVIEWER, setOf(RunCommandTool.NAME), PermissionLevel.READ_ONLY)
        val denied = reviewer.invoke(RunCommandTool.NAME, ToolInput()) as? ToolResult.Failure
            ?: error("Reviewer must not execute commands")
        assertEquals(ToolErrorCode.PERMISSION_DENIED, denied.error.code)
    }

    @Test
    fun `commit and push role may write git and no source role can`() = runBlocking {
        val registry = fullRegistry()
        val commitPr = scoped(
            registry = registry,
            role = AgentRole.COMMIT_PR,
            offered = registry.names().toSet(),
            level = PermissionLevel.GIT_WRITE,
        )
        val context = ToolExecutionContext(grantedPermissions = setOf(ToolPermissionLevel.GIT_WRITE))
            .copy(approval = ToolApproval.granted())
        val reached = commitPr.invoke(GitCommitTool.NAME, ToolInput(), context) as? ToolResult.Failure
            ?: error("git_commit should have reached the executor")
        assertEquals(ToolErrorCode.EXECUTION_FAILED, reached.error.code)

        // Commit/PR still holds no source-editing tool.
        val writeDenied = commitPr.invoke(WriteFileTool.NAME, ToolInput(), context) as? ToolResult.Failure
            ?: error("COMMIT_PR must not edit source")
        assertEquals(ToolErrorCode.PERMISSION_DENIED, writeDenied.error.code)
    }

    @Test
    fun `reviewer reads git diffs without a write tool`() = runBlocking {
        val registry = fullRegistry()
        val reviewer = scoped(registry, AgentRole.REVIEWER, registry.names().toSet(), PermissionLevel.READ_ONLY)
        val reached = reviewer.invoke(
            GitDiffTool.NAME,
            ToolInput(),
            contextFor(PermissionLevel.READ_ONLY),
        ) as? ToolResult.Failure ?: error("git_diff should have reached the executor")
        assertEquals(ToolErrorCode.EXECUTION_FAILED, reached.error.code)

        val writeDenied = reviewer.invoke(WriteFileTool.NAME, ToolInput()) as? ToolResult.Failure
            ?: error("Reviewer must not write")
        assertEquals(ToolErrorCode.PERMISSION_DENIED, writeDenied.error.code)
    }
}
