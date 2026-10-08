package com.agentx.app.agent.prompt

import com.agentx.app.agent.catalog.AgentCatalog
import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.agent.domain.AgentStatus
import com.agentx.app.agent.domain.CollectingEventSink
import com.agentx.app.agent.domain.PermissionLevel
import com.agentx.app.agent.domain.SubAgentRequest
import com.agentx.app.agent.domain.SubAgentResult
import com.agentx.app.agent.policy.AgentToolPolicy
import com.agentx.app.agent.protocol.AgentProtocol
import com.agentx.app.agent.response
import com.agentx.app.agent.runAgent
import com.agentx.app.agent.runtime.AgentLoop
import com.agentx.app.agent.runtime.AgentLoopRequest
import com.agentx.app.agent.ScriptedModelProvider
import com.agentx.app.agent.specialized.SpecializedAgentFactory
import com.agentx.app.agent.testConfig
import com.agentx.app.agent.toolCall
import com.agentx.app.agent.tools.AgentToolBridge
import com.agentx.app.model.DefaultModelGateway
import com.agentx.app.model.ModelRole
import com.agentx.app.model.ModelToolSpec
import com.agentx.app.tools.DefaultToolRegistry
import com.agentx.app.tools.DefaultToolRouter
import com.agentx.app.tools.ToolCapability
import com.agentx.app.tools.codeintel.FindDefinitionTool
import com.agentx.app.tools.codeintel.FindReferencesTool
import com.agentx.app.tools.codeintel.GetFileOutlineTool
import com.agentx.app.tools.codeintel.GetFileSymbolsTool
import com.agentx.app.tools.execution.RunCommandTool
import com.agentx.app.tools.filesystem.ListDirectoryTool
import com.agentx.app.tools.filesystem.ReadFileTool
import com.agentx.app.tools.filesystem.SearchFilesTool
import com.agentx.app.tools.filesystem.WriteFileTool
import com.agentx.app.tools.git.GitBranchesTool
import com.agentx.app.tools.git.GitCommitTool
import com.agentx.app.tools.git.GitDiffTool
import com.agentx.app.tools.git.GitLogTool
import com.agentx.app.tools.git.GitPushTool
import com.agentx.app.tools.git.GitStatusTool
import com.agentx.app.tools.planning.TodoWriteTool
import com.agentx.app.tools.pullrequest.CreatePullRequestTool
import com.agentx.app.tools.verification.CiVerificationTool
import com.agentx.app.tools.web.WebFetchTool
import com.agentx.app.tools.web.WebSearchTool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Role-quality contracts: each production role receives its operational contract,
 * Main is told how to judge specialist results, specialists cannot nest-delegate,
 * and coding/review tool policy matches the prompt.
 */
class RoleContractTest {

    private val inspectTools = setOf(ListDirectoryTool.NAME, SearchFilesTool.NAME, ReadFileTool.NAME)
    private val writeTool = WriteFileTool.NAME
    private val shellTool = RunCommandTool.NAME
    private val webTools = setOf(WebSearchTool.NAME, WebFetchTool.NAME)
    private val codingRoles = setOf(AgentRole.CODER, AgentRole.FAST_CODER, AgentRole.DEBUGGER, AgentRole.TESTER, AgentRole.DOCS)
    private val readOnlyReviewRoles = setOf(AgentRole.REVIEWER, AgentRole.SECURITY_REVIEWER, AgentRole.EXPLORER, AgentRole.PLANNER)

    private fun finishProvider(role: AgentRole) = ScriptedModelProvider(
        mapOf(role to mutableListOf(response("", toolCall(AgentProtocol.FINISH_TOOL, AgentProtocol.ARG_SUMMARY to "done")))),
    )

    private fun loopFor(provider: ScriptedModelProvider): AgentLoop {
        val registry = DefaultToolRegistry()
        return AgentLoop(
            gateway = DefaultModelGateway().also { it.register(provider) },
            toolRouter = DefaultToolRouter(registry),
            bridge = AgentToolBridge(registry),
            prompts = PromptManager(),
        )
    }

    private fun request(
        role: AgentRole,
        prompt: String = "go",
        objective: String? = null,
        scopedContext: String = "",
        allowedTools: List<String> = listOf(AgentProtocol.FINISH_TOOL),
        workspaceId: String? = null,
    ) = AgentLoopRequest(
        sessionId = "s-$role",
        parentSessionId = if (role == AgentRole.MAIN) null else "parent",
        definition = AgentCatalog.definition(role),
        allowedTools = allowedTools,
        permissionLevel = AgentCatalog.definition(role).effectivePermission,
        maxSteps = 2,
        userPrompt = prompt,
        objective = objective,
        scopedContext = scopedContext,
        workspaceId = workspaceId,
        modelConfig = testConfig(),
    )

    private fun systemOf(provider: ScriptedModelProvider): String =
        provider.requests.first().messages.first { it.role == ModelRole.SYSTEM }.content

    private fun userOf(provider: ScriptedModelProvider): String =
        provider.requests.first().messages.first { it.role == ModelRole.USER }.content

    // A. Each production role receives its intended contract.

    @Test
    fun `every production role prompt states purpose input actions output and limits`() {
        AgentRole.entries.forEach { role ->
            val prompt = DefaultAgentPrompts.forRole(role)
            assertTrue(prompt.isNotBlank(), role.name)
            if (role == AgentRole.MAIN) {
                assertTrue(prompt.contains("primary orchestrator"), role.name)
                assertTrue(prompt.contains("remain responsible"), role.name)
            } else {
                assertTrue(prompt.contains("PURPOSE:"), role.name)
                assertTrue(prompt.contains("INPUT:"), role.name)
                assertTrue(prompt.contains("ALLOWED ACTIONS:"), role.name)
                assertTrue(prompt.contains("OUTPUT:"), role.name)
                assertTrue(prompt.contains("LIMITATIONS:"), role.name)
            }
        }
    }

    @Test
    fun `each specialist contract reaches the assembled system prompt`() = runAgent {
        AgentRole.entries.filter { it != AgentRole.MAIN }.forEach { role ->
            val provider = finishProvider(role)
            loopFor(provider).run(request(role), CollectingEventSink(), onCancelled = { false })
            val system = systemOf(provider)
            assertTrue(system.contains("PURPOSE:"), role.name)
            assertTrue(system.contains("Role: ${role.name}"), role.name)
            assertTrue(system.contains("You cannot delegate"), role.name)
        }
    }

    // B. Main receives intended orchestration instructions.

    @Test
    fun `main receives orchestration and specialist-result instructions`() = runAgent {
        val provider = finishProvider(AgentRole.MAIN)
        loopFor(provider).run(request(AgentRole.MAIN, prompt = "hi"), CollectingEventSink(), onCancelled = { false })
        val system = systemOf(provider)
        assertTrue(system.contains("primary orchestrator"), system)
        assertTrue(system.contains("remain responsible"), system)
        assertTrue(system.contains("evidence, not truth") || system.contains("evidence for you to judge"), system)
        assertTrue(system.contains("COMPLETED means the specialist finished"), system)
        assertTrue(system.contains("not successful findings"), system)
        assertTrue(system.contains("could not verify"), system)
        assertTrue(system.contains("Delegate at most one sub-agent"), system)
        assertFalse(system.contains("You cannot delegate"), system)
    }

    // C. Specialist receives task + objective + scoped context.

    @Test
    fun `a specialist user prompt carries task objective and scoped context`() = runAgent {
        val provider = finishProvider(AgentRole.EXPLORER)
        val factory = SpecializedAgentFactory(
            loop = loopFor(provider),
            bridge = AgentToolBridge(DefaultToolRegistry()),
            availableTools = { emptyList() },
        )
        factory.create(AgentRole.EXPLORER).run(
            request = SubAgentRequest(
                role = AgentRole.EXPLORER,
                task = "map the auth flow in Auth.kt",
                objective = "name the login entry point",
                scopedContext = "Auth.kt holds the login form",
                parentSessionId = "parent",
                sessionId = "child",
            ),
            modelConfig = testConfig(),
            sink = CollectingEventSink(),
            onCancelled = { false },
        )
        val user = userOf(provider)
        assertTrue(user.contains("Objective: name the login entry point"), user)
        assertTrue(user.contains("map the auth flow in Auth.kt"), user)
        assertTrue(user.contains("Scoped context:"), user)
        assertTrue(user.contains("Auth.kt holds the login form"), user)
    }

    @Test
    fun `main user prompt is not duplicated into the system prompt`() = runAgent {
        val provider = finishProvider(AgentRole.CODER)
        loopFor(provider).run(
            request = request(
                role = AgentRole.CODER,
                prompt = "UNIQUE-TASK-TOKEN",
                objective = "UNIQUE-OBJECTIVE-TOKEN",
                scopedContext = "UNIQUE-CONTEXT-TOKEN",
            ),
            sink = CollectingEventSink(),
            onCancelled = { false },
        )
        val system = systemOf(provider)
        val user = userOf(provider)
        assertFalse(system.contains("UNIQUE-TASK-TOKEN"), system)
        assertFalse(system.contains("UNIQUE-OBJECTIVE-TOKEN"), system)
        assertFalse(system.contains("UNIQUE-CONTEXT-TOKEN"), system)
        assertTrue(user.contains("UNIQUE-TASK-TOKEN"), user)
        assertTrue(user.contains("UNIQUE-OBJECTIVE-TOKEN"), user)
        assertTrue(user.contains("UNIQUE-CONTEXT-TOKEN"), user)
        assertEquals(1, user.split("UNIQUE-TASK-TOKEN").size - 1)
        assertEquals(1, system.split("Role: CODER").size - 1)
    }

    // D. Role/tool policy matches prompt expectations.

    @Test
    fun `prompt write and shell claims match the role policy`() {
        AgentRole.entries.forEach { role ->
            val prompt = DefaultAgentPrompts.forRole(role)
            val tools = AgentToolPolicy.toolIdsFor(role)
            val mayWrite = writeTool in tools
            val mayShell = shellTool in tools
            val mayDelegate = AgentProtocol.DELEGATE_TOOL in tools
            if (!mayWrite && role != AgentRole.MAIN) {
                assertTrue(
                    prompt.contains("Do not modify") || prompt.contains("do not modify") ||
                        prompt.contains("Do not implement source") || prompt.contains("Do not edit the workspace") ||
                        prompt.contains("Do not modify production") || prompt.contains("Do not implement feature"),
                    "$role prompt must forbid writes when policy has no write tool",
                )
            }
            if (mayWrite && role in codingRoles) {
                assertTrue(prompt.contains("write") || prompt.contains("Inspect and write") || prompt.contains("targeted change"), role.name)
            }
            if (mayShell) {
                assertTrue(prompt.contains("command") || prompt.contains("Run offered"), role.name)
            }
            if (!mayDelegate && role != AgentRole.MAIN) {
                assertTrue(
                    prompt.contains("Do not delegate") || prompt.contains("or delegate"),
                    role.name,
                )
            }
            if (role == AgentRole.REVIEWER || role == AgentRole.SECURITY_REVIEWER) {
                assertFalse(mayWrite)
                assertFalse(mayShell)
                assertFalse(mayDelegate)
            }
        }
    }

    @Test
    fun `reviewer policy never grants write shell or delegate`() {
        val tools = AgentToolPolicy.toolIdsFor(AgentRole.REVIEWER)
        assertFalse(writeTool in tools)
        assertFalse(shellTool in tools)
        assertFalse(AgentProtocol.DELEGATE_TOOL in tools)
        assertTrue(GitDiffTool.NAME in tools)
        val prompt = DefaultAgentPrompts.REVIEWER
        assertTrue(prompt.contains("Do not modify files"))
        assertFalse(prompt.contains("write_file"))
    }

    // E. Coding roles receive verification requirements.

    @Test
    fun `coding roles are instructed to verify without inventing a pass`() {
        codingRoles.forEach { role ->
            val prompt = DefaultAgentPrompts.forRole(role)
            assertTrue(prompt.contains("inspect → understand → targeted change → verify → report") || prompt.contains("Verify"), role.name)
            assertTrue(
                prompt.contains("not verified") || prompt.contains("Never claim") || prompt.contains("unless observed"),
                role.name,
            )
        }
        assertTrue(DefaultAgentPrompts.CODER.contains("Never claim tests or a build passed unless"))
        assertTrue(DefaultAgentPrompts.TESTER.contains("Never claim \"build passed\" unless"))
        assertTrue(DefaultAgentPrompts.DEBUGGER.contains("Never claim a build or test passed unless observed"))
    }

    // F. Reviewer does not receive accidental coding authority.

    @Test
    fun `reviewer effective tools stay read-only even when the registry has writers`() {
        val registry = DefaultToolRegistry()
        val bridge = AgentToolBridge(registry)
        val definition = AgentCatalog.REVIEWER
        assertEquals(PermissionLevel.READ_ONLY, definition.effectivePermission)
        val allowed = bridge.filterAllowed(
            names = definition.allowedTools + listOf(writeTool, shellTool, AgentProtocol.DELEGATE_TOOL),
            permission = definition.effectivePermission,
        )
        assertFalse(writeTool in allowed)
        assertFalse(shellTool in allowed)
        assertTrue(AgentProtocol.FINISH_TOOL in allowed)
        assertFalse(DefaultAgentPrompts.REVIEWER.contains(WriteFileTool.NAME))
    }

    // G. Specialist instructions do not permit nested delegation.

    @Test
    fun `specialists are told they cannot delegate and main is not`() = runAgent {
        AgentRole.entries.filter { it != AgentRole.MAIN }.forEach { role ->
            val provider = finishProvider(role)
            loopFor(provider).run(request(role), CollectingEventSink(), onCancelled = { false })
            val system = systemOf(provider)
            assertTrue(system.contains("You cannot delegate. Nested delegation is not permitted."), role.name)
            assertFalse(AgentProtocol.DELEGATE_TOOL in AgentToolPolicy.toolIdsFor(role), role.name)
            val prompt = DefaultAgentPrompts.forRole(role)
            assertTrue(prompt.contains("Do not delegate") || prompt.contains("or delegate"), role.name)
        }
        val main = finishProvider(AgentRole.MAIN)
        loopFor(main).run(request(AgentRole.MAIN, prompt = "hello"), CollectingEventSink(), onCancelled = { false })
        assertFalse(systemOf(main).contains("You cannot delegate"), systemOf(main))
        assertTrue(AgentProtocol.DELEGATE_TOOL in AgentToolPolicy.toolIdsFor(AgentRole.MAIN))
    }

    // H. Failure and cancellation stay structured, not successful findings.

    @Test
    fun `non-completed specialist statuses are labeled not successful findings`() {
        val loop = loopFor(finishProvider(AgentRole.MAIN))
        val render = loop.javaClass.getDeclaredMethod("renderSubAgentResult", SubAgentResult::class.java).apply {
            isAccessible = true
        }
        fun rendered(status: AgentStatus): String = render.invoke(
            loop,
            SubAgentResult(sessionId = "c", role = AgentRole.CODER, status = status, summary = "note"),
        ) as String

        val completed = rendered(AgentStatus.COMPLETED)
        assertTrue(completed.contains("status=COMPLETED"), completed)
        assertTrue(completed.contains("evidence"), completed)
        assertFalse(completed.contains("not a successful finding"), completed)

        listOf(AgentStatus.FAILED, AgentStatus.CANCELLED, AgentStatus.MAX_STEPS_REACHED).forEach { status ->
            val text = rendered(status)
            assertTrue(text.contains("status=$status"), text)
            assertTrue(text.contains("not a successful finding"), text)
            assertFalse(text.contains("status=COMPLETED"), text)
        }
    }

    @Test
    fun `researcher runtime ceiling keeps web tools instead of collapsing to read-only`() {
        val definition = AgentCatalog.RESEARCHER
        assertTrue(definition.isReadOnly)
        assertEquals(PermissionLevel.NETWORK, definition.permissionLevel)
        assertEquals(PermissionLevel.NETWORK, definition.effectivePermission)
        assertTrue(WebSearchTool.NAME in definition.allowedTools)
        assertTrue(WebFetchTool.NAME in definition.allowedTools)
        assertFalse(writeTool in definition.allowedTools)
        val networkCaps = PermissionLevel.NETWORK.allowedCapabilities()
        assertTrue(ToolCapability.NETWORK in networkCaps)
        assertTrue(ToolCapability.FILESYSTEM in networkCaps)
        assertFalse(ToolCapability.MUTATING in networkCaps)
        assertTrue(PermissionLevel.NETWORK.allows(setOf(ToolCapability.NETWORK, ToolCapability.READ_ONLY)))
        assertTrue(PermissionLevel.NETWORK.allows(setOf(ToolCapability.READ_ONLY, ToolCapability.FILESYSTEM)))
        assertFalse(PermissionLevel.NETWORK.allows(setOf(ToolCapability.MUTATING, ToolCapability.FILESYSTEM)))
    }

    @Test
    fun `commit pr ceiling allows inspect git write and optional pull request network`() {
        val gitWrite = PermissionLevel.GIT_WRITE
        assertTrue(gitWrite.allows(setOf(ToolCapability.READ_ONLY, ToolCapability.FILESYSTEM)))
        assertTrue(gitWrite.allows(setOf(ToolCapability.GIT, ToolCapability.MUTATING)))
        assertTrue(gitWrite.allows(setOf(ToolCapability.GIT, ToolCapability.MUTATING, ToolCapability.NETWORK)))
        assertFalse(gitWrite.allows(setOf(ToolCapability.SHELL, ToolCapability.MUTATING)))
        assertFalse(gitWrite.allows(setOf(ToolCapability.MUTATING, ToolCapability.FILESYSTEM)))
        val tools = AgentToolPolicy.toolIdsFor(AgentRole.COMMIT_PR)
        assertTrue(GitCommitTool.NAME in tools)
        assertTrue(GitPushTool.NAME in tools)
        assertTrue(CreatePullRequestTool.NAME in tools)
        assertFalse(writeTool in tools)
    }

    @Test
    fun `delegate tool schema requires a concrete task and objective`() {
        val bridge = AgentToolBridge(DefaultToolRegistry())
        val specs = bridge.toModelSpecs(listOf(AgentProtocol.DELEGATE_TOOL, AgentProtocol.FINISH_TOOL))
        val delegate = specs.single { it.name == AgentProtocol.DELEGATE_TOOL }
        assertTrue(delegate.description.contains("evidence, not automatic truth"), delegate.description)
        assertTrue(delegate.parameter(AgentProtocol.ARG_TASK).description.contains("Never a vague request"), "task")
        assertTrue(delegate.parameter(AgentProtocol.ARG_OBJECTIVE).description.contains("successful specialist result"), "objective")
        assertTrue(delegate.parameter(AgentProtocol.ARG_CONTEXT).description.contains("Scoped context"), "context")
        val finish = specs.single { it.name == AgentProtocol.FINISH_TOOL }
        assertTrue(finish.description.contains("Do not claim verification"), finish.description)
    }

    private fun ModelToolSpec.parameter(name: String) = parameters.single { it.name == name }

    @Test
    fun `inspect-only roles keep inspect tools and never write or shell`() {
        readOnlyReviewRoles.forEach { role ->
            val tools = AgentToolPolicy.toolIdsFor(role)
            assertTrue(inspectTools.all { it in tools }, role.name)
            assertFalse(writeTool in tools, role.name)
            assertFalse(shellTool in tools, role.name)
            assertFalse(webTools.any { it in tools }, role.name)
        }
        assertTrue(webTools.all { it in AgentToolPolicy.toolIdsFor(AgentRole.RESEARCHER) })
        assertTrue(GetFileSymbolsTool.NAME in AgentToolPolicy.toolIdsFor(AgentRole.EXPLORER))
        assertTrue(GetFileOutlineTool.NAME in AgentToolPolicy.toolIdsFor(AgentRole.EXPLORER))
        assertTrue(FindDefinitionTool.NAME in AgentToolPolicy.toolIdsFor(AgentRole.EXPLORER))
        assertTrue(FindReferencesTool.NAME in AgentToolPolicy.toolIdsFor(AgentRole.EXPLORER))
        assertTrue(GitStatusTool.NAME in AgentToolPolicy.toolIdsFor(AgentRole.REVIEWER))
        assertTrue(GitDiffTool.NAME in AgentToolPolicy.toolIdsFor(AgentRole.REVIEWER))
        assertTrue(GitLogTool.NAME in AgentToolPolicy.toolIdsFor(AgentRole.REVIEWER))
        assertTrue(GitBranchesTool.NAME in AgentToolPolicy.toolIdsFor(AgentRole.REVIEWER))
        assertTrue(TodoWriteTool.NAME in AgentToolPolicy.toolIdsFor(AgentRole.MAIN))
        assertTrue(CiVerificationTool.NAME in AgentToolPolicy.toolIdsFor(AgentRole.DEBUGGER))
    }
}
