package com.agentx.app.agent.policy

import com.agentx.app.agent.catalog.AgentCatalog
import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.agent.domain.PermissionLevel
import com.agentx.app.agent.protocol.AgentProtocol
import com.agentx.app.agent.runtime.ScopedToolRouter
import com.agentx.app.tools.DefaultToolRegistry
import com.agentx.app.tools.DefaultToolRouter
import com.agentx.app.tools.Tool
import com.agentx.app.tools.ToolApproval
import com.agentx.app.tools.ToolAvailability
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
import com.agentx.app.tools.codeintel.FindDefinitionTool
import com.agentx.app.tools.codeintel.FindReferencesTool
import com.agentx.app.tools.codeintel.GetFileOutlineTool
import com.agentx.app.tools.codeintel.GetFileSymbolsTool
import com.agentx.app.tools.effectiveAvailability
import com.agentx.app.tools.execution.RunCommandTool
import com.agentx.app.tools.extensions.BrowserToolStub
import com.agentx.app.tools.filesystem.ListDirectoryTool
import com.agentx.app.tools.git.GitBranchesTool
import com.agentx.app.tools.git.GitCommitTool
import com.agentx.app.tools.git.GitDiffTool
import com.agentx.app.tools.git.GitLogTool
import com.agentx.app.tools.git.GitStatusTool
import com.agentx.app.tools.github.GitHubListReposTool
import com.agentx.app.tools.github.GitHubCloneRepoTool
import com.agentx.app.tools.planning.TodoWriteTool
import com.agentx.app.tools.verification.CiVerificationTool
import com.agentx.app.tools.web.WebFetchTool
import com.agentx.app.tools.web.WebSearchTool
import com.agentx.app.tools.filesystem.ReadFileTool
import com.agentx.app.tools.filesystem.SearchFilesTool
import com.agentx.app.tools.filesystem.WriteFileTool
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Part 3 permission boundary.
 *
 * Every case here is about the two questions staying apart: what a role may be
 * *told exists*, and what the runtime will *actually run*. The model is never the
 * authority in any of these tests — the policy and the router are.
 */
class AgentToolPolicyTest {

    private val readOnly = setOf(ToolCapability.READ_ONLY, ToolCapability.FILESYSTEM)
    private val mutating = setOf(ToolCapability.MUTATING, ToolCapability.FILESYSTEM)

    private val readOnlyRoles = listOf(
        AgentRole.EXPLORER,
        AgentRole.PLANNER,
        AgentRole.REVIEWER,
        AgentRole.SECURITY_REVIEWER,
        AgentRole.RESEARCHER,
    )

    private val writeRoles = listOf(
        AgentRole.MAIN,
        AgentRole.CODER,
        AgentRole.FAST_CODER,
        AgentRole.DEBUGGER,
        AgentRole.TESTER,
        AgentRole.DOCS,
    )

    /** Throws once it runs, so "did it reach the executor?" is observable. */
    private class ProbeTool(
        // A property, not a plain parameter: a non-property constructor parameter is
        // not in scope inside a function body, and execute() needs the name.
        private val name: String,
        capabilities: Set<ToolCapability>,
        required: Set<ToolPermissionLevel> = emptySet(),
        permission: ToolPermissionDecision = ToolPermissionDecision.ALLOW,
        availability: ToolAvailability = ToolAvailability.AVAILABLE,
    ) : Tool {
        override val definition = ToolDefinition(
            name = name,
            description = "Probe tool $name",
            permission = permission,
            capabilities = capabilities,
            requiredPermissions = required,
            availability = availability,
        )

        override suspend fun execute(input: ToolInput, context: ToolExecutionContext): ToolOutput =
            throw ToolExecutionError(
                ToolErrorCode.EXECUTION_FAILED,
                "reached the executor for $name",
                name,
            )
    }

    /** The real tool names, so the policy wiring is genuinely exercised. */
    private fun registry(): DefaultToolRegistry = DefaultToolRegistry().apply {
        register(ProbeTool(ListDirectoryTool.NAME, readOnly))
        register(ProbeTool(SearchFilesTool.NAME, readOnly))
        register(ProbeTool(ReadFileTool.NAME, readOnly))
        register(ProbeTool(GetFileSymbolsTool.NAME, readOnly))
        register(ProbeTool(GetFileOutlineTool.NAME, readOnly))
        register(ProbeTool(FindDefinitionTool.NAME, readOnly))
        register(ProbeTool(FindReferencesTool.NAME, readOnly))
        register(ProbeTool(TodoWriteTool.NAME, readOnly))
        register(ProbeTool(name = GitHubCloneRepoTool.NAME, capabilities = setOf(ToolCapability.NETWORK, ToolCapability.MUTATING), required = setOf(ToolPermissionLevel.WORKSPACE_WRITE), permission = ToolPermissionDecision.ASK))
        register(ProbeTool(GitHubListReposTool.NAME, setOf(ToolCapability.NETWORK, ToolCapability.READ_ONLY)))
        register(
            ProbeTool(
                name = WriteFileTool.NAME,
                capabilities = mutating,
                required = setOf(ToolPermissionLevel.WORKSPACE_WRITE),
                permission = ToolPermissionDecision.ASK,
            ),
        )
        // The newly implemented families are registered under their real names so
        // the policy is exercised against the same ids the agent sees.
        register(
            ProbeTool(
                name = RunCommandTool.NAME,
                capabilities = setOf(ToolCapability.SHELL, ToolCapability.MUTATING),
                required = setOf(ToolPermissionLevel.COMMAND_EXECUTION),
                permission = ToolPermissionDecision.ASK,
            ),
        )
        listOf(GitStatusTool.NAME, GitDiffTool.NAME, GitLogTool.NAME, GitBranchesTool.NAME).forEach { name ->
            register(ProbeTool(name, readOnly))
        }
        register(
            ProbeTool(
                name = GitCommitTool.NAME,
                capabilities = setOf(ToolCapability.GIT, ToolCapability.MUTATING),
                required = setOf(ToolPermissionLevel.GIT_WRITE),
                permission = ToolPermissionDecision.ASK,
            ),
        )
        listOf(WebSearchTool.NAME, WebFetchTool.NAME).forEach { name ->
            register(
                ProbeTool(
                    name = name,
                    capabilities = setOf(ToolCapability.NETWORK, ToolCapability.READ_ONLY),
                    required = setOf(ToolPermissionLevel.NETWORK),
                ),
            )
        }
        // CI verification is a real, registered tool family; register it under its
        // real name so the policy-derived catalog is exercised end to end.
        register(
            ProbeTool(
                name = CiVerificationTool.NAME,
                capabilities = setOf(ToolCapability.READ_ONLY),
                required = setOf(ToolPermissionLevel.READ_ONLY),
            ),
        )
    }

    private fun definitions(registry: DefaultToolRegistry): (String) -> ToolDefinition? =
        { name -> registry.find(name)?.definition }

    private fun scoped(
        registry: DefaultToolRegistry,
        role: AgentRole,
        offered: Set<String>,
        level: PermissionLevel,
    ) = ScopedToolRouter(
        inner = DefaultToolRouter(registry),
        role = role,
        allowedTools = offered,
        permissionLevel = level,
        definitionOf = definitions(registry),
        toolAllowed = { name ->
            val definition = registry.find(name)?.definition
            definition == null || level.allows(definition.capabilities)
        },
    )

    // --- 1. effective catalog per role -------------------------------------

    @Test
    fun `each role gets a deterministic effective catalog from the policy`() {
        val registry = registry()
        val definitionOf = definitions(registry)

        readOnlyRoles.forEach { role ->
            val tools = AgentToolPolicy.effectiveToolIds(role, definitionOf)
            assertTrue(ReadFileTool.NAME in tools, "$role must be able to inspect the workspace")
            assertFalse(WriteFileTool.NAME in tools, "$role must not hold a write tool")
        }

        writeRoles.forEach { role ->
            assertTrue(
                WriteFileTool.NAME in AgentToolPolicy.effectiveToolIds(role, definitionOf),
                "$role is expected to change code",
            )
        }
    }

    @Test
    fun `only the orchestrating role may delegate`() {
        assertTrue(AgentProtocol.DELEGATE_TOOL in AgentToolPolicy.toolIdsFor(AgentRole.MAIN))
        AgentRole.entries.filter { it != AgentRole.MAIN }.forEach { role ->
            assertFalse(
                AgentProtocol.DELEGATE_TOOL in AgentToolPolicy.toolIdsFor(role),
                "$role must not delegate",
            )
        }
        AgentRole.entries.forEach { role ->
            assertTrue(AgentProtocol.FINISH_TOOL in AgentToolPolicy.toolIdsFor(role))
        }
    }

    @Test
    fun `a role gains no capability merely because another role has it`() {
        val mainTools = AgentToolPolicy.toolIdsFor(AgentRole.MAIN).toSet()
        val explorerTools = AgentToolPolicy.toolIdsFor(AgentRole.EXPLORER).toSet()
        assertTrue(explorerTools.all { it in mainTools })
        assertFalse(WriteFileTool.NAME in explorerTools)
        // The orchestrator holds read-only git, but no shell and no git write.
        assertTrue(GitStatusTool.NAME in mainTools)
        assertFalse(RunCommandTool.NAME in mainTools)
        assertFalse(GitCommitTool.NAME in mainTools)
    }

    @Test
    fun `the new tool families are granted to exactly the intended roles`() {
        // Shell only where a role must run code, and never the orchestrator.
        listOf(AgentRole.DEBUGGER, AgentRole.TESTER).forEach { role ->
            assertTrue(RunCommandTool.NAME in AgentToolPolicy.toolIdsFor(role), "$role must execute commands")
        }
        listOf(AgentRole.EXPLORER, AgentRole.PLANNER, AgentRole.RESEARCHER, AgentRole.REVIEWER).forEach { role ->
            assertFalse(RunCommandTool.NAME in AgentToolPolicy.toolIdsFor(role), "$role must not run shell")
        }

        // Web research belongs to the researcher; no other role holds it.
        assertTrue(WebSearchTool.NAME in AgentToolPolicy.toolIdsFor(AgentRole.RESEARCHER))
        assertTrue(WebFetchTool.NAME in AgentToolPolicy.toolIdsFor(AgentRole.RESEARCHER))
        AgentRole.entries.filter { it != AgentRole.RESEARCHER }.forEach { role ->
            assertFalse(WebSearchTool.NAME in AgentToolPolicy.toolIdsFor(role), "$role must not search the web")
        }

        // Only commit/PR may write to the repository.
        assertTrue(GitCommitTool.NAME in AgentToolPolicy.toolIdsFor(AgentRole.COMMIT_PR))
        AgentRole.entries.filter { it != AgentRole.COMMIT_PR }.forEach { role ->
            assertFalse(GitCommitTool.NAME in AgentToolPolicy.toolIdsFor(role), "$role must not commit")
        }

        // Read-only inspection is available to the reviewing roles without any write.
        listOf(AgentRole.REVIEWER, AgentRole.SECURITY_REVIEWER).forEach { role ->
            val tools = AgentToolPolicy.toolIdsFor(role)
            assertTrue(GitDiffTool.NAME in tools && GitLogTool.NAME in tools, "$role reviews the change set")
            assertFalse(WriteFileTool.NAME in tools, "$role must not write")
        }

        // COMMIT_PR commits but never edits source.
        val commitPr = AgentToolPolicy.toolIdsFor(AgentRole.COMMIT_PR)
        assertFalse(WriteFileTool.NAME in commitPr)
        assertTrue(GitStatusTool.NAME in commitPr)
    }

    // --- 13. MAIN receives its intended tools, including code intelligence --

    @Test
    fun `MAIN receives the inspection and code-intelligence tools`() {
        val registry = registry()
        val main = AgentToolPolicy.effectiveToolIds(AgentRole.MAIN, definitions(registry))

        listOf(
            ListDirectoryTool.NAME,
            SearchFilesTool.NAME,
            ReadFileTool.NAME,
            GetFileSymbolsTool.NAME,
            GetFileOutlineTool.NAME,
            FindDefinitionTool.NAME,
            FindReferencesTool.NAME,
        ).forEach { toolId ->
            assertTrue(toolId in main, "MAIN must receive '$toolId'")
        }

        // The catalog derives from the policy, so the two can never drift again.
        assertEquals(main.sorted(), AgentCatalog.MAIN.allowedTools.sorted())
    }

    // --- 2, 3, 4, 5, 6. router enforcement ---------------------------------

    @Test
    fun `a tool outside the role policy is rejected before it can execute`() = runBlocking {
        val registry = registry()
        val router = scoped(
            registry = registry,
            role = AgentRole.EXPLORER,
            offered = setOf(ReadFileTool.NAME, WriteFileTool.NAME),
            level = PermissionLevel.READ_ONLY,
        )

        val failure = router.invoke(WriteFileTool.NAME, ToolInput()) as? ToolResult.Failure
            ?: error("a disallowed tool must not run")

        assertEquals(ToolErrorCode.PERMISSION_DENIED, failure.error.code)
        assertEquals(AgentRole.EXPLORER.name, failure.error.details["role"]?.let { JsonText.of(it) })
    }

    @Test
    fun `a tool the model was never offered is rejected even when requested directly`() = runBlocking {
        val registry = registry()
        // MAIN is authorized for the write tool, but it was not offered in this run.
        val router = scoped(
            registry = registry,
            role = AgentRole.MAIN,
            offered = setOf(ReadFileTool.NAME),
            level = PermissionLevel.WORKSPACE_WRITE,
        )

        val failure = router.invoke(WriteFileTool.NAME, ToolInput()) as? ToolResult.Failure
            ?: error("a tool that was not offered must not run")

        assertEquals(ToolErrorCode.PERMISSION_DENIED, failure.error.code)
    }

    @Test
    fun `an allowed tool call reaches the executor`() = runBlocking {
        val registry = registry()
        val router = scoped(
            registry = registry,
            role = AgentRole.EXPLORER,
            offered = setOf(ReadFileTool.NAME),
            level = PermissionLevel.READ_ONLY,
        )

        val result = router.invoke(ReadFileTool.NAME, ToolInput())

        // The probe throws once it runs, so this code proves the call got past the
        // policy, the visibility check and the capability ceiling.
        val failure = result as? ToolResult.Failure ?: error("the probe should have run and failed")
        assertEquals(ToolErrorCode.EXECUTION_FAILED, failure.error.code)
        assertTrue(failure.error.message?.contains("reached the executor") == true)
    }

    @Test
    fun `an unknown tool fails safely and is not reported as a permission problem`() = runBlocking {
        val registry = registry()
        val router = scoped(
            registry = registry,
            role = AgentRole.MAIN,
            offered = setOf("not_a_tool"),
            level = PermissionLevel.WORKSPACE_WRITE,
        )

        val failure = router.invoke("not_a_tool", ToolInput()) as? ToolResult.Failure
            ?: error("an unknown tool must fail")

        assertEquals(ToolErrorCode.UNKNOWN_TOOL, failure.error.code)
    }

    @Test
    fun `a protocol tool cannot be routed through the tool system`() = runBlocking {
        val registry = registry()
        val router = scoped(
            registry = registry,
            role = AgentRole.MAIN,
            offered = setOf(AgentProtocol.FINISH_TOOL, AgentProtocol.DELEGATE_TOOL),
            level = PermissionLevel.WORKSPACE_WRITE,
        )

        listOf(AgentProtocol.FINISH_TOOL, AgentProtocol.DELEGATE_TOOL).forEach { toolId ->
            val failure = router.invoke(toolId, ToolInput()) as? ToolResult.Failure
                ?: error("'$toolId' is handled by the loop, not the router")
            assertEquals(ToolErrorCode.PERMISSION_DENIED, failure.error.code)
        }
    }

    // --- 4. sub-agents cannot widen their scope ----------------------------

    @Test
    fun `a sub-agent request cannot widen the role scope`() {
        val registry = registry()
        val definitionOf = definitions(registry)

        // The old sub-agent fallback was exactly this: "no tools named, so take the
        // whole registry".
        val wholeRegistry = registry.names()
        val explorer = AgentToolPolicy.effectiveToolIds(AgentRole.EXPLORER, definitionOf, wholeRegistry)

        assertFalse(WriteFileTool.NAME in explorer, "a sub-agent must not gain a write tool by asking")
        assertTrue(explorer.all { it in AgentToolPolicy.toolIdsFor(AgentRole.EXPLORER) })
        assertEquals(emptyList(), AgentToolPolicy.restrictToRole(AgentRole.EXPLORER, listOf("not_a_tool")))

        // A caller can only ever narrow, never add.
        val narrowed = AgentToolPolicy.effectiveToolIds(
            role = AgentRole.EXPLORER,
            definitionOf = definitionOf,
            requested = listOf(ReadFileTool.NAME, WriteFileTool.NAME),
        )
        assertEquals(listOf(ReadFileTool.NAME), narrowed)
    }

    // --- 5, 6, 7, 8. permission categories are enforced --------------------

    @Test
    fun `privileged permission categories are enforced by the tool system`() = runBlocking {
        val registry = DefaultToolRegistry().apply {
            register(
                ProbeTool(
                    name = "probe_shell",
                    capabilities = setOf(ToolCapability.SHELL, ToolCapability.MUTATING),
                    required = setOf(ToolPermissionLevel.COMMAND_EXECUTION),
                ),
            )
            register(
                ProbeTool(
                    name = "probe_network",
                    capabilities = setOf(ToolCapability.NETWORK, ToolCapability.READ_ONLY),
                    required = setOf(ToolPermissionLevel.NETWORK),
                ),
            )
            register(
                ProbeTool(
                    name = "probe_git_write",
                    capabilities = setOf(ToolCapability.GIT, ToolCapability.MUTATING),
                    required = setOf(ToolPermissionLevel.GIT_WRITE),
                ),
            )
        }
        val router = DefaultToolRouter(registry)
        val readOnlyRun = ToolExecutionContext(grantedPermissions = setOf(ToolPermissionLevel.READ_ONLY))

        listOf("probe_shell", "probe_network", "probe_git_write").forEach { name ->
            val failure = router.invoke(name, ToolInput(), readOnlyRun) as? ToolResult.Failure
                ?: error("'$name' must not run without its grant")
            assertEquals(ToolErrorCode.PERMISSION_DENIED, failure.error.code, name)
        }

        // With the matching grant the same tool reaches the executor, so the denial
        // above was the grant and not the tool.
        val granted = ToolExecutionContext(grantedPermissions = setOf(ToolPermissionLevel.COMMAND_EXECUTION))
        val reached = router.invoke("probe_shell", ToolInput(), granted) as? ToolResult.Failure
            ?: error("the granted call should have run")
        assertEquals(ToolErrorCode.EXECUTION_FAILED, reached.error.code)
    }

    // --- 9, 10. approval ---------------------------------------------------

    @Test
    fun `an approval-required tool pauses and a denial prevents execution`() = runBlocking {
        val registry = DefaultToolRegistry().apply {
            register(
                ProbeTool(
                    name = "probe_write",
                    capabilities = setOf(ToolCapability.MUTATING, ToolCapability.FILESYSTEM),
                    required = setOf(ToolPermissionLevel.WORKSPACE_WRITE),
                    permission = ToolPermissionDecision.ASK,
                ),
            )
        }
        val router = DefaultToolRouter(registry)
        val context = ToolExecutionContext(grantedPermissions = setOf(ToolPermissionLevel.WORKSPACE_WRITE))

        // No decision yet: the call is parked, not executed.
        assertTrue(router.invoke("probe_write", ToolInput(), context) is ToolResult.ApprovalRequired)

        // A denial is remembered as a denial: the tool still does not run.
        val denied = router.invoke(
            "probe_write",
            ToolInput(),
            context.copy(approval = ToolApproval.denied("not now")),
        ) as? ToolResult.Failure ?: error("a denied approval must not execute")

        assertEquals(ToolErrorCode.PERMISSION_DENIED, denied.error.code)
    }

    // --- 11. unavailable / stub tools --------------------------------------

    @Test
    fun `a declared but unimplemented tool is unavailable rather than merely denied`() = runBlocking {
        val stub = BrowserToolStub()
        assertFalse(stub.definition.effectiveAvailability.isAvailable)

        val registry = DefaultToolRegistry().apply {
            register(stub)
            register(ProbeTool(ReadFileTool.NAME, readOnly))
        }
        val router = scoped(
            registry = registry,
            role = AgentRole.DEBUGGER,
            offered = setOf(stub.definition.name, ReadFileTool.NAME),
            level = PermissionLevel.COMMAND_EXECUTION,
        )

        val failure = router.invoke(stub.definition.name, ToolInput()) as? ToolResult.Failure
            ?: error("an unavailable tool must not run")
        assertEquals(ToolErrorCode.TOOL_UNAVAILABLE, failure.error.code)
        assertTrue(failure.error.message?.contains("not available") == true)
    }

    @Test
    fun `an unavailable tool is never offered to the model even when the role is granted it`() {
        // The role's grant is unchanged; only the tool's availability differs. The
        // visible catalog must shrink while the authorization answer stays the same.
        val unavailable = DefaultToolRegistry().apply {
            register(ProbeTool(ReadFileTool.NAME, readOnly, availability = ToolAvailability.UNAVAILABLE))
        }
        val available = registry()

        assertTrue(ReadFileTool.NAME in AgentToolPolicy.toolIdsFor(AgentRole.EXPLORER))
        assertTrue(AgentToolPolicy.isAuthorized(AgentRole.EXPLORER, ReadFileTool.NAME))
        assertFalse(ReadFileTool.NAME in AgentToolPolicy.visibleToolIds(AgentRole.EXPLORER, definitions(unavailable)))
        assertTrue(ReadFileTool.NAME in AgentToolPolicy.visibleToolIds(AgentRole.EXPLORER, definitions(available)))
    }

    // --- 14. determinism ---------------------------------------------------

    @Test
    fun `the policy is deterministic across recreation`() {
        val first = AgentRole.entries.associateWith { role ->
            AgentToolPolicy.effectiveToolIds(role, definitions(registry()))
        }
        val second = AgentRole.entries.associateWith { role ->
            AgentToolPolicy.effectiveToolIds(role, definitions(registry()))
        }

        assertEquals(first, second)
        // Rebuilding the catalog yields the same tools and the same levels: nothing
        // is decided per instance, so a restart cannot widen a role.
        assertEquals(AgentCatalog.all(), AgentCatalog.all())
        AgentRole.entries.forEach { role ->
            assertEquals(
                AgentCatalog.definition(role).allowedTools.sorted(),
                AgentToolPolicy.toolIdsFor(role).sorted(),
            )
        }
    }

    /** Reads a JSON detail value back for assertions. */
    private object JsonText {
        fun of(value: com.agentx.app.tools.JsonValue): String? = when (value) {
            is com.agentx.app.tools.JsonValue.Str -> value.value
            else -> null
        }
    }
}
