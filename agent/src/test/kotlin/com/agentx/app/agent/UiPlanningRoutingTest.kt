package com.agentx.app.agent

import com.agentx.app.agent.catalog.AgentCatalog
import com.agentx.app.agent.delegation.DelegationRejection
import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.agent.domain.AgentStatus
import com.agentx.app.agent.domain.CollectingEventSink
import com.agentx.app.agent.domain.PermissionLevel
import com.agentx.app.agent.domain.SubAgentRequest
import com.agentx.app.agent.domain.SubAgentResult
import com.agentx.app.agent.policy.AgentToolPolicy
import com.agentx.app.agent.protocol.AgentProtocol
import com.agentx.app.agent.runtime.AgentLoop
import com.agentx.app.agent.runtime.AgentLoopRequest
import com.agentx.app.agent.prompt.PromptManager
import com.agentx.app.agent.tools.AgentToolBridge
import com.agentx.app.context.ContextBudget
import com.agentx.app.context.DefaultContextEngine
import com.agentx.app.context.DesignContextResolver
import com.agentx.app.context.PlatformProfileResolver
import com.agentx.app.context.ProjectDesign
import com.agentx.app.context.ProjectDesignContextResolver
import com.agentx.app.context.ProjectPlatformProfileResolver
import com.agentx.app.context.PlatformProfile
import com.agentx.app.context.WorkspaceContextProvider
import com.agentx.app.context.WorkspaceSnapshot
import com.agentx.app.model.DefaultModelGateway
import com.agentx.app.model.ModelRole
import com.agentx.app.model.ModelToolCall
import com.agentx.app.tools.DefaultToolRegistry
import com.agentx.app.tools.DefaultToolRouter
import com.agentx.app.tools.ToolCapability
import com.agentx.app.workspace.WorkspaceFileSystem
import com.agentx.app.workspace.memory.InMemoryWorkspaceFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 4 — UI-aware delegation end-to-end through the real
 * [com.agentx.app.agent.runtime.AgentLoop].
 *
 * These prove the classification actually changes routing: a UI design task is
 * forced through PLANNER before CODER/FAST_CODER, the planner's result reaches the
 * implementation and review agents through the existing scoped-context channel,
 * simple UI and non-UI tasks keep their existing path, and a planner that fails or
 * produces nothing never silently unblocks implementation.
 */
class UiPlanningRoutingTest {

    private fun registry() = DefaultToolRegistry().also {
        it.register(RecordingTool("read_file", setOf(ToolCapability.READ_ONLY, ToolCapability.FILESYSTEM)))
        it.register(RecordingTool("write_file", setOf(ToolCapability.MUTATING, ToolCapability.FILESYSTEM)))
    }

    private fun loop(
        provider: ScriptedModelProvider,
        design: DesignContextResolver? = null,
        platform: PlatformProfileResolver? = null,
    ): AgentLoop {
        val registry = registry()
        return AgentLoop(
            gateway = DefaultModelGateway().also { it.register(provider) },
            toolRouter = DefaultToolRouter(registry),
            bridge = AgentToolBridge(registry),
            prompts = PromptManager(),
            designContext = design,
            platformProfile = platform,
        )
    }

    private fun mainRequest(prompt: String, budget: ContextBudget = ContextBudget.DEFAULT) = AgentLoopRequest(
        sessionId = "s",
        parentSessionId = null,
        definition = AgentCatalog.MAIN,
        allowedTools = listOf(AgentProtocol.DELEGATE_TOOL, AgentProtocol.FINISH_TOOL),
        permissionLevel = PermissionLevel.WORKSPACE_WRITE,
        maxSteps = 8,
        userPrompt = prompt,
        objective = null,
        scopedContext = "",
        workspaceId = "ws",
        modelConfig = testConfig(),
        contextBudget = budget,
    )

    private fun delegate(role: AgentRole, task: String, context: String = ""): ModelToolCall =
        if (context.isBlank()) {
            toolCall(
                AgentProtocol.DELEGATE_TOOL,
                AgentProtocol.ARG_ROLE to role.name,
                AgentProtocol.ARG_TASK to task,
                AgentProtocol.ARG_OBJECTIVE to task,
            )
        } else {
            toolCall(
                AgentProtocol.DELEGATE_TOOL,
                AgentProtocol.ARG_ROLE to role.name,
                AgentProtocol.ARG_TASK to task,
                AgentProtocol.ARG_OBJECTIVE to task,
                AgentProtocol.ARG_CONTEXT to context,
            )
        }

    private fun finish(summary: String = "Done") =
        toolCall(AgentProtocol.FINISH_TOOL, AgentProtocol.ARG_SUMMARY to summary)

    private fun scriptedMain(vararg main: ModelToolCall) = ScriptedModelProvider(
        mapOf(AgentRole.MAIN to main.toMutableList()),
    )

    /**
     * A deterministic stand-in for the specialist layer. It records every
     * delegation it actually received and answers per role, so the loop's routing
     * decisions are observable without a real child model.
     */
    private class CapturingInvoker(
        private val planSummary: String = "PLAN: hero, feature grid, footer. Reuse the existing theme and type scale.",
        private val plannerStatus: AgentStatus = AgentStatus.COMPLETED,
    ) {
        val requests = mutableListOf<SubAgentRequest>()
        val roles: List<AgentRole> get() = requests.map { it.role }

        val invoker = com.agentx.app.agent.runtime.SubAgentInvoker { req ->
            requests += req
            if (req.role == AgentRole.PLANNER) {
                SubAgentResult(
                    sessionId = req.sessionId,
                    role = req.role,
                    status = plannerStatus,
                    summary = if (plannerStatus == AgentStatus.COMPLETED) planSummary else "",
                    findings = if (plannerStatus == AgentStatus.COMPLETED) listOf("Hero section", "Feature grid") else emptyList(),
                    filesInspected = listOf("DESIGN.md", "src/theme.css"),
                )
            } else {
                SubAgentResult(
                    sessionId = req.sessionId,
                    role = req.role,
                    status = AgentStatus.COMPLETED,
                    summary = "done",
                    filesChanged = listOf("src/App.kt"),
                )
            }
        }
    }

    // ───────────────────────── routing ─────────────────────────

    @Test
    fun `a design task is forced through the planner before implementation`() = runAgent {
        val invoker = CapturingInvoker()
        val result = loop(
            scriptedMain(
                delegate(AgentRole.CODER, "implement the redesign"),
                delegate(AgentRole.PLANNER, "produce a design plan"),
                delegate(AgentRole.CODER, "implement the plan"),
                finish(),
            ),
        ).run(
            request = mainRequest("Redesign the settings screen to make the hierarchy clearer and more distinctive"),
            sink = CollectingEventSink(),
            subAgentInvoker = invoker.invoker,
        )

        assertEquals(AgentStatus.COMPLETED, result.status)
        // The premature CODER was rejected, so only PLANNER then CODER actually ran.
        assertEquals(listOf(AgentRole.PLANNER, AgentRole.CODER), invoker.roles)
        assertTrue(
            result.errors.any { it.details["delegationRejection"] == DelegationRejection.PLANNING_REQUIRED.name },
            "the premature implementation must be reported as PLANNING_REQUIRED",
        )
    }

    @Test
    fun `a complex UI task is forced through the planner`() = runAgent {
        val invoker = CapturingInvoker()
        val result = loop(
            scriptedMain(
                delegate(AgentRole.FAST_CODER, "start the dashboard"),
                delegate(AgentRole.PLANNER, "plan the dashboard"),
                delegate(AgentRole.CODER, "build the dashboard"),
                finish(),
            ),
        ).run(
            request = mainRequest("Create an entire dashboard with sidebar navigation and multiple screens"),
            sink = CollectingEventSink(),
            subAgentInvoker = invoker.invoker,
        )

        assertEquals(AgentStatus.COMPLETED, result.status)
        assertEquals(listOf(AgentRole.PLANNER, AgentRole.CODER), invoker.roles)
    }

    @Test
    fun `the planner result reaches the implementation agent`() = runAgent {
        val invoker = CapturingInvoker(planSummary = "PLAN: identity, layout, states.")
        loop(
            scriptedMain(
                delegate(AgentRole.CODER, "implement"),
                delegate(AgentRole.PLANNER, "plan"),
                delegate(AgentRole.CODER, "implement the plan", context = "scoped notes from Main"),
                finish(),
            ),
        ).run(
            request = mainRequest("Redesign the settings screen to make it clearer"),
            sink = CollectingEventSink(),
            subAgentInvoker = invoker.invoker,
        )

        val coder = invoker.requests.single { it.role == AgentRole.CODER }
        assertTrue(coder.scopedContext.contains("PLAN: identity, layout, states."), coder.scopedContext)
        assertTrue(coder.scopedContext.contains("# Design Plan (from Planner)"), coder.scopedContext)
        // Main's own scoped notes are preserved alongside the plan.
        assertTrue(coder.scopedContext.contains("scoped notes from Main"), coder.scopedContext)
    }

    @Test
    fun `the reviewer receives the plan for a design task`() = runAgent {
        val invoker = CapturingInvoker(planSummary = "PLAN: sections and states.")
        loop(
            scriptedMain(
                delegate(AgentRole.PLANNER, "plan"),
                delegate(AgentRole.CODER, "implement"),
                delegate(AgentRole.REVIEWER, "review the change"),
                finish(),
            ),
        ).run(
            request = mainRequest("Build a landing page for the marketing site"),
            sink = CollectingEventSink(),
            subAgentInvoker = invoker.invoker,
        )

        val reviewer = invoker.requests.single { it.role == AgentRole.REVIEWER }
        assertTrue(reviewer.scopedContext.contains("PLAN: sections and states."), reviewer.scopedContext)
    }

    @Test
    fun `a simple UI task does not invoke the planner`() = runAgent {
        val invoker = CapturingInvoker()
        loop(
            scriptedMain(
                delegate(AgentRole.FAST_CODER, "fix the padding"),
                finish(),
            ),
        ).run(
            request = mainRequest("fix one padding issue in the settings screen"),
            sink = CollectingEventSink(),
            subAgentInvoker = invoker.invoker,
        )

        assertEquals(listOf(AgentRole.FAST_CODER), invoker.roles)
    }

    @Test
    fun `non-UI routing is unchanged`() = runAgent {
        val invoker = CapturingInvoker()
        val result = loop(
            scriptedMain(
                delegate(AgentRole.CODER, "implement the endpoint"),
                finish(),
            ),
        ).run(
            request = mainRequest("implement an API endpoint for password reset"),
            sink = CollectingEventSink(),
            subAgentInvoker = invoker.invoker,
        )

        assertEquals(AgentStatus.COMPLETED, result.status)
        assertEquals(listOf(AgentRole.CODER), invoker.roles)
        assertFalse(result.errors.any { it.details["delegationRejection"] == DelegationRejection.PLANNING_REQUIRED.name })
    }

    // ───────────────────────── failure handling ─────────────────────────

    @Test
    fun `a failed planner does not silently unblock implementation`() = runAgent {
        val invoker = CapturingInvoker(plannerStatus = AgentStatus.FAILED)
        val result = loop(
            scriptedMain(
                delegate(AgentRole.PLANNER, "plan"),
                delegate(AgentRole.CODER, "implement"),
                finish(),
            ),
        ).run(
            request = mainRequest("Redesign the settings screen to make it clearer"),
            sink = CollectingEventSink(),
            subAgentInvoker = invoker.invoker,
        )

        // CODER never ran: the plan was not usable.
        assertEquals(listOf(AgentRole.PLANNER), invoker.roles)
        assertTrue(
            result.errors.any { it.details["delegationRejection"] == DelegationRejection.PLANNING_REQUIRED.name },
            "a failed plan must still gate implementation",
        )
    }

    @Test
    fun `missing design and platform context never breaks planning`() = runAgent {
        val invoker = CapturingInvoker()
        val result = loop(scriptedMain(delegate(AgentRole.PLANNER, "plan"), delegate(AgentRole.CODER, "implement"), finish()))
            .run(
                request = mainRequest("Build a landing page for the marketing site"),
                sink = CollectingEventSink(),
                subAgentInvoker = invoker.invoker,
                // No design or platform resolver is wired.
            )

        assertEquals(AgentStatus.COMPLETED, result.status)
        assertEquals(listOf(AgentRole.PLANNER, AgentRole.CODER), invoker.roles)
    }

    @Test
    fun `context-budget pressure does not crash delegation`() = runAgent {
        val invoker = CapturingInvoker()
        val tight = ContextBudget.DEFAULT.copy(
            maxTotalChars = 1_200,
            maxFileChars = 400,
            maxToolResultChars = 400,
            maxConversationChars = 400,
            maxDesignChars = 200,
            maxPlatformChars = 200,
        )
        val result = loop(scriptedMain(delegate(AgentRole.PLANNER, "plan"), delegate(AgentRole.CODER, "implement"), finish()))
            .run(
                request = mainRequest("Redesign the settings screen to make it clearer", budget = tight),
                sink = CollectingEventSink(),
                subAgentInvoker = invoker.invoker,
            )

        assertEquals(AgentStatus.COMPLETED, result.status)
        assertEquals(listOf(AgentRole.PLANNER, AgentRole.CODER), invoker.roles)
    }

    // ───────────────────────── planner context + no recursion ─────────────────────────

    @Test
    fun `the planner receives the project design direction`() = runAgent {
        val body = "Editorial, typography-led, generous whitespace. Audience: studios."
        val design = ProjectDesignContextResolver(
            workspace = FileWorkspace(mapOf(ProjectDesign.FILE_NAME to body)),
            engine = DefaultContextEngine(),
        )
        val system = plannerSystemPrompt(design = design, platform = null)

        assertTrue(system.contains(ProjectDesign.HEADING), system)
        assertTrue(system.contains(body), system)
    }

    @Test
    fun `the planner receives the detected platform profile`() = runAgent {
        val platform = ProjectPlatformProfileResolver(
            workspace = FileWorkspace(
                mapOf(
                    "index.html" to "<html><body></body></html>",
                    "styles.css" to "body { margin: 0; }",
                ),
            ),
            engine = DefaultContextEngine(),
        )
        val system = plannerSystemPrompt(design = null, platform = platform)

        assertTrue(system.contains(PlatformProfile.HEADING_PREFIX), system)
    }

    @Test
    fun `the planner cannot delegate, so it can never recurse`() {
        val tools = AgentToolPolicy.toolIdsFor(AgentRole.PLANNER)
        assertFalse(tools.contains(AgentProtocol.DELEGATE_TOOL), "PLANNER must not hold the delegate tool")
        assertTrue(tools.contains(AgentProtocol.FINISH_TOOL))
    }

    // ───────────────────────── prompt guidance ─────────────────────────

    @Test
    fun `the main prompt states the planning requirement for design work`() = runAgent {
        val provider = scriptedMain(finish())
        val invoker = CapturingInvoker()
        loop(provider).run(
            request = mainRequest("Build a landing page for the marketing site"),
            sink = CollectingEventSink(),
            subAgentInvoker = invoker.invoker,
        )
        val system = provider.requests.first().messages.first { it.role == ModelRole.SYSTEM }.content
        assertTrue(system.contains("UI design work"), system)
        assertTrue(system.contains("PLANNER"), system)
    }

    // ───────────────────────── helpers ─────────────────────────

    private suspend fun plannerSystemPrompt(
        design: DesignContextResolver?,
        platform: PlatformProfileResolver?,
    ): String {
        val provider = ScriptedModelProvider(
            mapOf(
                AgentRole.PLANNER to mutableListOf(
                    response("", finish()),
                ),
            ),
        )
        loop(provider, design = design, platform = platform).run(
            request = AgentLoopRequest(
                sessionId = "p",
                parentSessionId = "parent",
                definition = AgentCatalog.PLANNER,
                allowedTools = listOf(AgentProtocol.FINISH_TOOL),
                permissionLevel = PermissionLevel.READ_ONLY,
                maxSteps = 2,
                userPrompt = "plan a landing page",
                objective = null,
                scopedContext = "",
                workspaceId = null,
                modelConfig = testConfig(),
            ),
            sink = CollectingEventSink(),
            onCancelled = { false },
        )
        return provider.requests.first().messages.first { it.role == ModelRole.SYSTEM }.content
    }

    /** A workspace backed by an in-memory file map, for the design/platform resolvers. */
    private class FileWorkspace(private val files: Map<String, String>) : WorkspaceContextProvider {
        override suspend fun snapshot(): WorkspaceSnapshot =
            WorkspaceSnapshot(name = "demo", rootPath = ".", workspaceId = "ws-1")

        override suspend fun fileSystem(): WorkspaceFileSystem = InMemoryWorkspaceFileSystem(files)
    }
}
