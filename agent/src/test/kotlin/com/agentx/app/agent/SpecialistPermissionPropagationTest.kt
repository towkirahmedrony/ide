package com.agentx.app.agent

import com.agentx.app.agent.domain.AgentErrorCode
import com.agentx.app.agent.domain.AgentEvent
import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.agent.domain.AgentRunRequest
import com.agentx.app.agent.domain.AgentStatus
import com.agentx.app.agent.domain.CollectingEventSink
import com.agentx.app.agent.protocol.AgentProtocol
import com.agentx.app.core.timeout.AgentTimeouts
import com.agentx.app.model.DefaultModelGateway
import com.agentx.app.tools.DefaultToolRegistry
import com.agentx.app.tools.DefaultToolRouter
import com.agentx.app.tools.ToolCapability
import com.agentx.app.tools.ToolPermissionDecision
import com.agentx.app.tools.ToolPermissionLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A delegated specialist can reach an ASK-gated tool. That pause must cross the
 * sub-agent boundary instead of being flattened into SUB_AGENT_FAILURE, and the
 * user's decision must resume the CHILD — never the parent — without weakening any
 * permission check or re-running a call the user already decided.
 */
class SpecialistPermissionPropagationTest {

    private class Fixture {
        val read = RecordingTool("read_file", setOf(ToolCapability.READ_ONLY, ToolCapability.FILESYSTEM))
        val write = RecordingTool(
            "write_file",
            setOf(ToolCapability.MUTATING, ToolCapability.FILESYSTEM),
            permission = ToolPermissionDecision.ASK,
            required = setOf(ToolPermissionLevel.WORKSPACE_WRITE),
        )
        val run = RecordingTool(
            "run_command",
            setOf(ToolCapability.SHELL, ToolCapability.FILESYSTEM),
            permission = ToolPermissionDecision.ASK,
            required = setOf(ToolPermissionLevel.COMMAND_EXECUTION),
        )
        val commit = RecordingTool(
            "git_commit",
            setOf(ToolCapability.GIT, ToolCapability.MUTATING),
            permission = ToolPermissionDecision.ASK,
            required = setOf(ToolPermissionLevel.GIT_WRITE),
        )
        val registry = DefaultToolRegistry().also {
            it.register(read)
            it.register(write)
            it.register(run)
            it.register(commit)
        }
    }

    private fun runtime(fixture: Fixture, provider: ScriptedModelProvider): AgentRuntime {
        val gateway = DefaultModelGateway().also { it.register(provider) }
        return AgentModule.assemble(
            gateway = gateway,
            registry = fixture.registry,
            router = DefaultToolRouter(fixture.registry),
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

    private fun finish(summary: String = "Done") =
        response("", toolCall(AgentProtocol.FINISH_TOOL, AgentProtocol.ARG_SUMMARY to summary))

    private fun askTool(name: String, id: String = "call-$name") =
        response("", toolCall(name, "path" to "Auth.kt", id = id))

    private fun pause(
        fixture: Fixture,
        role: AgentRole,
        childScript: MutableList<com.agentx.app.model.ModelResponse>,
        sink: CollectingEventSink,
        task: String = "do work",
    ): Pair<AgentRuntime, com.agentx.app.agent.domain.AgentResult> {
        val provider = ScriptedModelProvider(
            mapOf(
                AgentRole.MAIN to mutableListOf(delegate(role, task), finish()),
                role to childScript,
            ),
        )
        val runtime = runtime(fixture, provider)
        val result = runtime.orchestrator.run(
            request = AgentRunRequest(prompt = task, workspaceId = "ws"),
            modelConfig = testConfig(),
            sink = sink,
        )
        return runtime to result
    }

    // --- 1. the pause crosses the boundary as a permission request -----------

    @Test
    fun `a specialist permission pause propagates to the parent as a permission request`() = runAgent {
        val fixture = Fixture()
        val sink = CollectingEventSink()
        val (runtime, result) = pause(
            fixture = fixture,
            role = AgentRole.CODER,
            childScript = mutableListOf(askTool("write_file", id = "w1"), finish("edited")),
            sink = sink,
        )

        assertEquals(AgentStatus.WAITING_FOR_PERMISSION, result.status)
        assertTrue(
            result.errors.none { it.code == AgentErrorCode.SUB_AGENT_FAILURE },
            "a genuine permission pause must not become a sub-agent failure",
        )
        val pending = assertNotNull(result.pendingPermission)
        assertEquals("write_file", pending.toolName)
        assertTrue(result.resumeContext.isNotEmpty(), "the parent keeps its own resume snapshot")

        val delegated = assertNotNull(result.delegatedPermissionPause)
        assertEquals(AgentRole.CODER, delegated.childRole)
        assertEquals("write_file", delegated.pendingPermission.toolName)
        assertEquals("w1", delegated.pendingPermission.toolCallId)
        assertTrue(delegated.childSessionId.isNotBlank())
        assertTrue(delegated.childResumeContext.isNotEmpty(), "the child's own snapshot is retained")
        assertEquals("call-delegate_to_agent", delegated.delegateToolCallId, "the parent finds its delegate call")

        // The paused child is retained and parked; the tool never ran.
        val childSession = assertNotNull(runtime.orchestrator.session(delegated.childSessionId))
        assertEquals(AgentStatus.WAITING_FOR_PERMISSION, childSession.status)
        assertTrue(fixture.write.invocations.isEmpty())

        // The parent-level UI receives a real permission request, not a failure.
        assertTrue(
            sink.events.any { it is AgentEvent.PermissionRequested && it.sessionId == result.sessionId },
        )
        assertTrue(sink.events.none { it is AgentEvent.SubAgentCompleted && it.status == AgentStatus.FAILED })
    }

    // --- 2. approval resumes the child, which completes and returns to MAIN ---

    @Test
    fun `approving the specialist resumes the child and returns its result to main`() = runAgent {
        val fixture = Fixture()
        val sink = CollectingEventSink()
        val (runtime, paused) = pause(
            fixture = fixture,
            role = AgentRole.CODER,
            childScript = mutableListOf(askTool("write_file", id = "w1"), finish("edited")),
            sink = sink,
        )
        assertEquals(AgentStatus.WAITING_FOR_PERMISSION, paused.status)

        val resumed = assertNotNull(runtime.orchestrator.resumePermission(paused.sessionId, true, sink))
        assertEquals(AgentStatus.COMPLETED, resumed.status)
        assertEquals("Done", resumed.summary, "MAIN continued normally after the child completed")
        assertEquals(1, fixture.write.invocations.size, "the approved tool runs exactly once")
        assertEquals("Auth.kt", fixture.write.invocations.single().string("path"))
        assertTrue(fixture.read.invocations.isEmpty())
    }

    // --- 3. denial skips the tool and the child still returns to MAIN --------

    @Test
    fun `denying the specialist skips the tool and the child result returns normally`() = runAgent {
        val fixture = Fixture()
        val sink = CollectingEventSink()
        val (runtime, paused) = pause(
            fixture = fixture,
            role = AgentRole.CODER,
            childScript = mutableListOf(askTool("write_file", id = "w1"), finish("skipped")),
            sink = sink,
        )

        val resumed = assertNotNull(runtime.orchestrator.resumePermission(paused.sessionId, false, sink))
        assertEquals(AgentStatus.COMPLETED, resumed.status)
        assertTrue(fixture.write.invocations.isEmpty(), "a denied tool must never run")
        assertTrue(
            resumed.errors.none { it.code == AgentErrorCode.SUB_AGENT_FAILURE },
            "a denial is not a sub-agent failure",
        )
        assertTrue(resumed.errors.any { it.code == AgentErrorCode.PERMISSION_DENIED })
    }

    // --- 4. a later ASK sibling stays gated after the first approval ----------

    @Test
    fun `a later ask-gated sibling of the specialist stays gated after the first approval`() = runAgent {
        val fixture = Fixture()
        val sink = CollectingEventSink()
        val (runtime, paused) = pause(
            fixture = fixture,
            role = AgentRole.DEBUGGER,
            childScript = mutableListOf(
                response(
                    "",
                    toolCall("read_file", "path" to "Auth.kt", id = "r1"),
                    toolCall("write_file", "path" to "Auth.kt", id = "w1"),
                    toolCall("run_command", "path" to "Auth.kt", id = "c1"),
                ),
                finish("checked"),
            ),
            sink = sink,
        )

        val first = assertNotNull(paused.delegatedPermissionPause)
        assertEquals("write_file", first.pendingPermission.toolName)
        assertEquals(1, first.pendingPermission.pendingIndex)
        // read_file completed before the pause; the other two have not run.
        assertEquals(listOf(true, false, false), first.pendingPermission.batch.map { it.completed })
        assertEquals(1, fixture.read.invocations.size)

        // Approve write_file: it runs once, and run_command stays gated.
        val second = assertNotNull(runtime.orchestrator.resumePermission(paused.sessionId, true, sink))
        assertEquals(AgentStatus.WAITING_FOR_PERMISSION, second.status)
        assertEquals(1, fixture.write.invocations.size, "the approved sibling runs exactly once")
        assertTrue(fixture.run.invocations.isEmpty(), "a later ASK call must not auto-run")
        val secondPause = assertNotNull(second.delegatedPermissionPause)
        assertEquals("run_command", secondPause.pendingPermission.toolName)
        assertEquals(2, secondPause.pendingPermission.pendingIndex)
        assertEquals(1, fixture.read.invocations.size, "a completed sibling is never re-run")

        // Approve run_command: it runs once and the child completes; MAIN finishes.
        val third = assertNotNull(runtime.orchestrator.resumePermission(paused.sessionId, true, sink))
        assertEquals(AgentStatus.COMPLETED, third.status)
        assertEquals(1, fixture.run.invocations.size)
        assertEquals(1, fixture.write.invocations.size)
        assertEquals(1, fixture.read.invocations.size)
    }

    // --- 5. an approved specialist command executes --------------------------

    @Test
    fun `an approved specialist command executes and returns control to main`() = runAgent {
        val fixture = Fixture()
        val sink = CollectingEventSink()
        val (runtime, paused) = pause(
            fixture = fixture,
            role = AgentRole.DEBUGGER,
            childScript = mutableListOf(askTool("run_command", id = "c1"), finish("ran")),
            sink = sink,
        )
        assertEquals("run_command", assertNotNull(paused.pendingPermission).toolName)

        val resumed = assertNotNull(runtime.orchestrator.resumePermission(paused.sessionId, true, sink))
        assertEquals(AgentStatus.COMPLETED, resumed.status)
        assertEquals(1, fixture.run.invocations.size)
    }

    // --- 6. COMMIT_PR permission reaches the user boundary -------------------

    @Test
    fun `a commit-pr git_commit pause reaches the user instead of becoming a failure`() = runAgent {
        val fixture = Fixture()
        val sink = CollectingEventSink()
        val (_, result) = pause(
            fixture = fixture,
            role = AgentRole.COMMIT_PR,
            childScript = mutableListOf(askTool("git_commit", id = "g1"), finish("committed")),
            sink = sink,
        )

        assertEquals(AgentStatus.WAITING_FOR_PERMISSION, result.status)
        assertTrue(result.errors.none { it.code == AgentErrorCode.SUB_AGENT_FAILURE })
        val delegated = assertNotNull(result.delegatedPermissionPause)
        assertEquals(AgentRole.COMMIT_PR, delegated.childRole)
        assertEquals("git_commit", delegated.pendingPermission.toolName)
        assertTrue(fixture.commit.invocations.isEmpty(), "the commit must not run before approval")
        assertTrue(sink.events.any { it is AgentEvent.PermissionRequested })
    }

    // --- 7. cancellation clears a waiting specialist -------------------------

    @Test
    fun `cancelling while a specialist waits clears both parent and child pause state`() = runAgent {
        val fixture = Fixture()
        val sink = CollectingEventSink()
        val (runtime, paused) = pause(
            fixture = fixture,
            role = AgentRole.CODER,
            childScript = mutableListOf(askTool("write_file", id = "w1"), finish("edited")),
            sink = sink,
        )
        val childId = assertNotNull(paused.delegatedPermissionPause).childSessionId

        runtime.orchestrator.cancel(paused.sessionId)

        assertEquals(AgentStatus.CANCELLED, assertNotNull(runtime.orchestrator.session(paused.sessionId)).status)
        assertEquals(AgentStatus.CANCELLED, assertNotNull(runtime.orchestrator.session(childId)).status)
        assertNull(
            runtime.orchestrator.resumePermission(paused.sessionId, true, CollectingEventSink()),
            "no WAITING_FOR_PERMISSION state may leak after cancellation",
        )
        assertTrue(fixture.write.invocations.isEmpty())
    }
}
