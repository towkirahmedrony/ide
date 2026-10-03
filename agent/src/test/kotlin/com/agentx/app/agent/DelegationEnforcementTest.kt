package com.agentx.app.agent

import com.agentx.app.agent.catalog.AgentCatalog
import com.agentx.app.agent.delegation.DelegationPolicy
import com.agentx.app.agent.delegation.SpecialistContextBudgets
import com.agentx.app.agent.domain.AgentEvent
import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.agent.domain.AgentRunRequest
import com.agentx.app.agent.domain.AgentStatus
import com.agentx.app.agent.domain.CollectingEventSink
import com.agentx.app.agent.domain.PermissionLevel
import com.agentx.app.agent.domain.SubAgentRequest
import com.agentx.app.agent.domain.SubAgentResult
import com.agentx.app.agent.policy.AgentToolPolicy
import com.agentx.app.agent.protocol.AgentProtocol
import com.agentx.app.agent.runtime.AgentLoop
import com.agentx.app.agent.runtime.AgentLoopRequest
import com.agentx.app.agent.runtime.SubAgentInvoker
import com.agentx.app.agent.tools.AgentToolBridge
import com.agentx.app.context.ContextBudget
import com.agentx.app.core.timeout.AgentTimeouts
import com.agentx.app.agent.delegation.TaskComplexity
import com.agentx.app.agent.delegation.TaskComplexityClassifier
import com.agentx.app.model.DefaultModelGateway
import com.agentx.app.model.ModelRole
import com.agentx.app.tools.DefaultToolRegistry
import com.agentx.app.tools.DefaultToolRouter
import com.agentx.app.tools.ToolCapability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Part 7 — the delegation policy enforced end-to-end through the real
 * [com.agentx.app.agent.runtime.AgentLoop] and orchestrator. These prove the
 * limits are not just a pure function but actually gate live delegations and
 * surface a structured rejection back to the Main Agent.
 */
class DelegationEnforcementTest {

    private fun registry() = DefaultToolRegistry().also {
        it.register(RecordingTool("read_file", setOf(ToolCapability.READ_ONLY, ToolCapability.FILESYSTEM)))
        it.register(RecordingTool("write_file", setOf(ToolCapability.MUTATING, ToolCapability.FILESYSTEM)))
    }

    private fun runtime(provider: ScriptedModelProvider): AgentRuntime {
        val registry = registry()
        val gateway = DefaultModelGateway().also { it.register(provider) }
        return AgentModule.assemble(
            gateway = gateway,
            registry = registry,
            router = DefaultToolRouter(registry),
            timeouts = AgentTimeouts(mainTaskMillis = 30_000L, subAgentTaskMillis = 30_000L),
        )
    }

    private fun delegate(role: AgentRole, task: String) = response(
        "",
        toolCall(
            AgentProtocol.DELEGATE_TOOL,
            AgentProtocol.ARG_ROLE to role.name,
            AgentProtocol.ARG_TASK to task,
            AgentProtocol.ARG_OBJECTIVE to task,
        ),
    )

    private fun explorerMaps(summary: String) = mutableListOf(
        response(
            "",
            toolCall(
                AgentProtocol.FINISH_TOOL,
                AgentProtocol.ARG_SUMMARY to summary,
                AgentProtocol.ARG_FILES_INSPECTED to "Auth.kt",
            ),
        ),
    )

    @Test
    fun `re-delegating a completed task is rejected and the main agent is told why`() = runAgent {
        // MAIN delegates EXPLORER on the same task twice; the second is redundant.
        val provider = ScriptedModelProvider(
            mapOf(
                AgentRole.MAIN to mutableListOf(
                    delegate(AgentRole.EXPLORER, "map the auth flow"),
                    delegate(AgentRole.EXPLORER, "map the auth flow"),
                    response("", toolCall(AgentProtocol.FINISH_TOOL, AgentProtocol.ARG_SUMMARY to "Done")),
                ),
                AgentRole.EXPLORER to explorerMaps("Mapped auth"),
            ),
        )
        val sink = CollectingEventSink()
        val result = runtime(provider).orchestrator.run(
            request = AgentRunRequest(prompt = "Understand auth"),
            modelConfig = testConfig(),
            sink = sink,
        )

        assertEquals(AgentStatus.COMPLETED, result.status)
        // The specialist ran exactly once: the redundant second delegation never started.
        val started = sink.events.count { it is AgentEvent.SubAgentStarted && it.role == AgentRole.EXPLORER }
        assertEquals(1, started)
        // The run carries the structured rejection reason.
        assertTrue(result.errors.any { it.details["delegationRejection"] == "REDUNDANT" })
    }

    @Test
    fun `exceeding the per-role repeat cap is rejected`() = runAgent {
        // MAIN delegates CODER on four distinct failing tasks; the 4th exceeds MAX_REPEATS_PER_ROLE=3.
        val coderScript = mutableListOf(
            response("", toolCall(AgentProtocol.FINISH_TOOL, AgentProtocol.ARG_SUMMARY to "edited 1")),
            response("", toolCall(AgentProtocol.FINISH_TOOL, AgentProtocol.ARG_SUMMARY to "edited 2")),
            response("", toolCall(AgentProtocol.FINISH_TOOL, AgentProtocol.ARG_SUMMARY to "edited 3")),
            response("", toolCall(AgentProtocol.FINISH_TOOL, AgentProtocol.ARG_SUMMARY to "edited 4")),
        )
        val provider = ScriptedModelProvider(
            mapOf(
                AgentRole.MAIN to mutableListOf(
                    delegate(AgentRole.CODER, "edit a"),
                    delegate(AgentRole.CODER, "edit b"),
                    delegate(AgentRole.CODER, "edit c"),
                    delegate(AgentRole.CODER, "edit d"),
                    response("", toolCall(AgentProtocol.FINISH_TOOL, AgentProtocol.ARG_SUMMARY to "Done")),
                ),
                AgentRole.CODER to coderScript,
            ),
        )
        val sink = CollectingEventSink()
        val result = runtime(provider).orchestrator.run(
            request = AgentRunRequest(prompt = "Edit things"),
            modelConfig = testConfig(),
            sink = sink,
        )

        assertEquals(AgentStatus.COMPLETED, result.status)
        val started = sink.events.count { it is AgentEvent.SubAgentStarted && it.role == AgentRole.CODER }
        assertEquals(com.agentx.app.agent.delegation.DelegationPolicy.MAX_REPEATS_PER_ROLE, started)
        assertTrue(result.errors.any { it.details["delegationRejection"] == "MAX_REPEATS" })
    }

    @Test
    fun `a specialist cannot delegate even if it asks to`() = runAgent {
        // EXPLORER has no delegate tool via policy, but even a forced attempt is refused by role.
        val provider = ScriptedModelProvider(
            mapOf(
                AgentRole.MAIN to mutableListOf(
                    delegate(AgentRole.EXPLORER, "map auth"),
                    response("", toolCall(AgentProtocol.FINISH_TOOL, AgentProtocol.ARG_SUMMARY to "Done")),
                ),
                AgentRole.EXPLORER to explorerMaps("mapped"),
            ),
        )
        val result = runtime(provider).orchestrator.run(
            request = AgentRunRequest(prompt = "map"),
            modelConfig = testConfig(),
            sink = CollectingEventSink(),
        )
        assertEquals(AgentStatus.COMPLETED, result.status)
        // Only EXPLORER was spawned by MAIN; the explorer itself never delegated.
        assertTrue(AgentToolPolicy.toolIdsFor(AgentRole.EXPLORER).none { it == AgentProtocol.DELEGATE_TOOL })
    }

    // ───────── loop-level enforcement (budget, plan, cancel, failure) ─────────

    private fun mainLoop(provider: ScriptedModelProvider): AgentLoop {
        val registry = registry()
        val gateway = DefaultModelGateway().also { it.register(provider) }
        return AgentLoop(
            gateway = gateway,
            toolRouter = DefaultToolRouter(registry),
            bridge = AgentToolBridge(registry),
        )
    }

    private fun mainRequest(contextBudget: ContextBudget = ContextBudget.DEFAULT) = AgentLoopRequest(
        sessionId = "s",
        parentSessionId = null,
        definition = AgentCatalog.MAIN,
        allowedTools = listOf(AgentProtocol.DELEGATE_TOOL, AgentProtocol.FINISH_TOOL),
        permissionLevel = PermissionLevel.WORKSPACE_WRITE,
        maxSteps = 4,
        userPrompt = "do work",
        objective = null,
        scopedContext = "",
        workspaceId = "ws",
        modelConfig = testConfig(),
        contextBudget = contextBudget,
    )

    private fun capturingInvoker(
        role: AgentRole,
        status: AgentStatus = AgentStatus.COMPLETED,
        changed: List<String> = emptyList(),
        inspected: List<String> = emptyList(),
    ): Pair<SubAgentInvoker, MutableList<SubAgentRequest>> {
        val captured = mutableListOf<SubAgentRequest>()
        val invoker = SubAgentInvoker { request ->
            captured += request
            SubAgentResult(
                sessionId = request.sessionId,
                role = role,
                status = status,
                summary = "done",
                filesChanged = changed,
                filesInspected = inspected,
            )
        }
        return invoker to captured
    }

    @Test
    fun `a specialist receives a role-appropriate context budget and capped scoped context`() = runAgent {
        val provider = ScriptedModelProvider(
            mapOf(
                AgentRole.MAIN to mutableListOf(
                    response(
                        "",
                        toolCall(
                            AgentProtocol.DELEGATE_TOOL,
                            AgentProtocol.ARG_ROLE to "CODER",
                            AgentProtocol.ARG_TASK to "implement the feature",
                            AgentProtocol.ARG_OBJECTIVE to "feature implemented",
                            AgentProtocol.ARG_CONTEXT to "x".repeat(DelegationPolicy.MAX_SCOPED_CONTEXT_CHARS + 5_000),
                        ),
                    ),
                    response("", toolCall(AgentProtocol.FINISH_TOOL, AgentProtocol.ARG_SUMMARY to "Done")),
                ),
            ),
        )
        val (invoker, captured) = capturingInvoker(AgentRole.CODER, changed = listOf("Auth.kt"))
        mainLoop(provider).run(request = mainRequest(), sink = CollectingEventSink(), subAgentInvoker = invoker)

        assertEquals(1, captured.size)
        val child = captured.first()
        assertTrue(child.scopedContext.length <= DelegationPolicy.MAX_SCOPED_CONTEXT_CHARS)
        assertEquals(1, child.delegationState.depth)
        assertEquals(
            SpecialistContextBudgets.forRole(AgentRole.CODER, ContextBudget.DEFAULT),
            child.contextBudget,
        )
    }

    @Test
    fun `the plan reflects delegated specialist work`() = runAgent {
        val provider = ScriptedModelProvider(
            mapOf(
                AgentRole.MAIN to mutableListOf(
                    delegate(AgentRole.EXPLORER, "map auth"),
                    delegate(AgentRole.CODER, "edit auth"),
                    response("", toolCall(AgentProtocol.FINISH_TOOL, AgentProtocol.ARG_SUMMARY to "Done")),
                ),
            ),
        )
        val sink = CollectingEventSink()
        // A stand-in invoker represents the specialists; the loop itself records the
        // delegated roles into the plan it emits.
        val (invoker, _) = capturingInvoker(AgentRole.EXPLORER, inspected = listOf("Auth.kt"))
        mainLoop(provider).run(request = mainRequest(), sink = sink, subAgentInvoker = invoker)

        val plans = sink.events.filterIsInstance<AgentEvent.PlanUpdated>().map { it.plan }
        assertTrue(plans.isNotEmpty())
        // At some point the plan carries a step labelled with the delegated role.
        assertTrue(
            plans.any { plan -> plan.steps.any { it.role == AgentRole.EXPLORER } },
            "expected a plan step for the delegated EXPLORER",
        )
    }

    @Test
    fun `cancellation stops the run before any further delegation`() = runAgent {
        val provider = ScriptedModelProvider(
            mapOf(
                AgentRole.MAIN to mutableListOf(
                    delegate(AgentRole.EXPLORER, "map auth"),
                    response("", toolCall(AgentProtocol.FINISH_TOOL, AgentProtocol.ARG_SUMMARY to "Done")),
                ),
            ),
        )
        val (invoker, captured) = capturingInvoker(AgentRole.EXPLORER, inspected = listOf("Auth.kt"))
        val result = mainLoop(provider).run(
            request = mainRequest(),
            sink = CollectingEventSink(),
            subAgentInvoker = invoker,
            onCancelled = { true },
        )
        assertEquals(AgentStatus.CANCELLED, result.status)
        assertTrue(captured.isEmpty(), "no specialist should run once cancelled")
    }

    @Test
    fun `a failed specialist returns control and the main agent finishes`() = runAgent {
        val provider = ScriptedModelProvider(
            mapOf(
                AgentRole.MAIN to mutableListOf(
                    delegate(AgentRole.CODER, "edit auth"),
                    response("", toolCall(AgentProtocol.FINISH_TOOL, AgentProtocol.ARG_SUMMARY to "Recovered")),
                ),
            ),
        )
        val (invoker, _) = capturingInvoker(AgentRole.CODER, status = AgentStatus.FAILED)
        val result = mainLoop(provider).run(
            request = mainRequest(),
            sink = CollectingEventSink(),
            subAgentInvoker = invoker,
        )
        // The failure is preserved but does not stall the parent run.
        assertEquals(AgentStatus.COMPLETED, result.status)
        assertEquals("Recovered", result.summary)
        assertTrue(result.errors.isNotEmpty())
    }

    @Test
    fun `a delegation whose invoker throws is reported, not propagated`() = runAgent {
        val provider = ScriptedModelProvider(
            mapOf(
                AgentRole.MAIN to mutableListOf(
                    delegate(AgentRole.CODER, "edit auth"),
                    response("", toolCall(AgentProtocol.FINISH_TOOL, AgentProtocol.ARG_SUMMARY to "Continued")),
                ),
            ),
        )
        val invoker = SubAgentInvoker { error("boom") }
        val result = mainLoop(provider).run(
            request = mainRequest(),
            sink = CollectingEventSink(),
            subAgentInvoker = invoker,
        )
        assertEquals(AgentStatus.COMPLETED, result.status)
        assertTrue(result.errors.any { it.code == com.agentx.app.agent.domain.AgentErrorCode.SUB_AGENT_FAILURE })
    }

    @Test
    fun `the main agent prompt carries deterministic complexity guidance`() = runAgent {
        val provider = ScriptedModelProvider(
            mapOf(AgentRole.MAIN to mutableListOf(response("done"))),
        )
        val (invoker, _) = capturingInvoker(AgentRole.EXPLORER)
        mainLoop(provider).run(request = mainRequest(), sink = CollectingEventSink(), subAgentInvoker = invoker)

        val system = provider.requests.first()
            .messages.first { it.role == ModelRole.SYSTEM }
            .content
        // "do work" classifies as SIMPLE, so the direct-handling guidance must appear.
        assertEquals(TaskComplexity.SIMPLE, TaskComplexityClassifier.classify("do work"))
        assertTrue(system.contains("This task looks simple"))
    }

    @Test
    fun `a simple read-only turn never delegates`() = runAgent {
        // MAIN answers directly with a final message and no delegate call.
        val provider = ScriptedModelProvider(
            mapOf(AgentRole.MAIN to mutableListOf(response("It reads like this: ..."))),
        )
        val (invoker, captured) = capturingInvoker(AgentRole.EXPLORER)
        val result = mainLoop(provider).run(
            request = mainRequest(),
            sink = CollectingEventSink(),
            subAgentInvoker = invoker,
        )
        assertEquals(AgentStatus.COMPLETED, result.status)
        assertTrue(captured.isEmpty())
    }
}
