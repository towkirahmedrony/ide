package com.agentx.app.agent

import com.agentx.app.agent.catalog.AgentCatalog
import com.agentx.app.agent.domain.AgentErrorCode
import com.agentx.app.agent.domain.AgentEvent
import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.agent.domain.AgentStatus
import com.agentx.app.agent.domain.CollectingEventSink
import com.agentx.app.agent.domain.PermissionLevel
import com.agentx.app.agent.protocol.AgentProtocol
import com.agentx.app.agent.runtime.AgentLoop
import com.agentx.app.agent.runtime.AgentLoopRequest
import com.agentx.app.agent.runtime.ResumedPermission
import com.agentx.app.agent.tools.AgentToolBridge
import com.agentx.app.model.DefaultModelGateway
import com.agentx.app.model.ModelConfig
import com.agentx.app.model.ModelRole
import com.agentx.app.tools.DefaultToolRegistry
import com.agentx.app.tools.DefaultToolRouter
import com.agentx.app.tools.ToolCapability
import com.agentx.app.tools.ToolPermissionDecision
import com.agentx.app.tools.ToolPermissionLevel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A model response can ask for several tools at once, and the pause must survive that.
 *
 * The bug these tests pin: the loop used to park on the blocked call and abandon the
 * rest of the assistant message, so a later call was never executed and the resumed
 * transcript carried an assistant message with more tool calls than tool results —
 * both a lost side effect and an invalid request to send back to a provider.
 */
class PermissionPauseIntegrityTest {

    // `testConfig()` comes from TestSupport: it declares toolCalling, which the gateway
    // requires before it will send tool specs at all.

    private fun agentRequest(
        allowedTools: List<String>,
        permissionLevel: PermissionLevel = PermissionLevel.WORKSPACE_WRITE,
        maxSteps: Int = 4,
    ): AgentLoopRequest = AgentLoopRequest(
        sessionId = "s",
        parentSessionId = null,
        definition = AgentCatalog.MAIN,
        allowedTools = allowedTools,
        permissionLevel = permissionLevel,
        maxSteps = maxSteps,
        userPrompt = "go",
        objective = null,
        scopedContext = "",
        workspaceId = null,
        modelConfig = testConfig(),
    )

    private fun loopOver(registry: DefaultToolRegistry, provider: ScriptedModelProvider): AgentLoop {
        val gateway = DefaultModelGateway().also { it.register(provider) }
        return AgentLoop(gateway, DefaultToolRouter(registry), AgentToolBridge(registry))
    }

    private fun guardedTool() = RecordingTool(
        name = "guarded_write",
        capabilities = setOf(ToolCapability.MUTATING, ToolCapability.FILESYSTEM),
        permission = ToolPermissionDecision.ASK,
        required = setOf(ToolPermissionLevel.WORKSPACE_WRITE),
    )

    private val allowed = listOf("read_one", "guarded_write", "read_two", AgentProtocol.FINISH_TOOL)

    /**
     * The scenario from the report: read_one → guarded_write (ASK) → read_two, then a
     * finish on the next step.
     */
    private fun threeCallProvider() = ScriptedModelProvider(
        mapOf(
            AgentRole.MAIN to mutableListOf(
                response(
                    "",
                    toolCall("read_one", "path" to "a.kt", id = "c1"),
                    toolCall("guarded_write", "path" to "b.kt", id = "c2"),
                    toolCall("read_two", "path" to "c.kt", id = "c3"),
                ),
                response("", toolCall(AgentProtocol.FINISH_TOOL, AgentProtocol.ARG_SUMMARY to "done")),
            ),
        ),
    )

    private fun fixtures(): Pair<DefaultToolRegistry, Triple<RecordingTool, RecordingTool, RecordingTool>> {
        val one = RecordingTool("read_one", setOf(ToolCapability.READ_ONLY, ToolCapability.FILESYSTEM))
        val two = RecordingTool("read_two", setOf(ToolCapability.READ_ONLY, ToolCapability.FILESYSTEM))
        val guarded = guardedTool()
        val registry = DefaultToolRegistry().also {
            it.register(one)
            it.register(guarded)
            it.register(two)
        }
        return registry to Triple(one, guarded, two)
    }

    // --- A. the pause keeps the whole assistant message ---------------------

    @Test
    fun `a pause preserves every tool call of the assistant message`() = runBlocking {
        val (registry, tools) = fixtures()
        val (one, guarded, two) = tools
        val provider = threeCallProvider()
        val loop = loopOver(registry, provider)

        val paused = loop.run(
            request = agentRequest(allowed),
            sink = CollectingEventSink(),
            onCancelled = { false },
        )

        assertEquals(AgentStatus.WAITING_FOR_PERMISSION, paused.status)
        val pending = assertNotNull(paused.pendingPermission)
        assertEquals("guarded_write", pending.toolName)
        assertEquals("c2", pending.toolCallId)

        // The complete assistant message, in order, with ids intact.
        assertEquals(3, pending.batch.size, "all three calls must be preserved")
        assertEquals(listOf("c1", "c2", "c3"), pending.batch.map { it.toolCallId })
        assertEquals(listOf(0, 1, 2), pending.batch.map { it.index })
        assertEquals(1, pending.pendingIndex)

        // Exactly one call already ran; the blocked one and the one after it did not.
        assertEquals(listOf(true, false, false), pending.batch.map { it.completed })
        assertEquals(1, one.invocations.size, "the call before the pause already ran")
        assertTrue(guarded.invocations.isEmpty(), "the blocked tool must not run")
        assertTrue(two.invocations.isEmpty(), "the sibling after the pause has not run yet")

        // The pending view names what is left to run, in order.
        assertEquals(listOf("c2", "c3"), pending.remaining.map { it.toolCallId })
    }

    // --- B. approval resolves the siblings too ------------------------------

    @Test
    fun `approving the pending call runs it once and does not lose its siblings`() = runBlocking {
        val (registry, tools) = fixtures()
        val (one, guarded, two) = tools
        val provider = threeCallProvider()
        val loop = loopOver(registry, provider)

        val paused = loop.run(agentRequest(allowed), CollectingEventSink()) { false }
        val pending = assertNotNull(paused.pendingPermission)

        val resumed = loop.run(
            request = agentRequest(allowed).copy(
                resumeContext = paused.resumeContext,
                resumePermission = ResumedPermission(
                    toolName = pending.toolName,
                    arguments = pending.arguments,
                    reason = pending.reason,
                    toolCallId = pending.toolCallId,
                    approved = true,
                    batch = pending.batch,
                    pendingIndex = pending.pendingIndex,
                ),
            ),
            sink = CollectingEventSink(),
            onCancelled = { false },
        )

        assertEquals(AgentStatus.COMPLETED, resumed.status)
        assertEquals(1, guarded.invocations.size, "the approved tool runs exactly once")
        assertEquals("b.kt", guarded.invocations.single().string("path"))
        assertEquals(1, one.invocations.size, "the sibling before the pause is not re-run")
        assertEquals(1, two.invocations.size, "the sibling after the pause is not lost")

        // The assistant message now has a result for every call it requested.
        val followUp = provider.requests.last()
        assertEquals(
            3,
            followUp.messages.count { it.role == ModelRole.TOOL },
            "every tool call of the assistant message must have a matching result",
        )
    }

    // --- C. denial skips only the denied call -------------------------------

    @Test
    fun `denying the pending call skips it and still resolves the rest`() = runBlocking {
        val (registry, tools) = fixtures()
        val (one, guarded, two) = tools
        val provider = threeCallProvider()
        val loop = loopOver(registry, provider)

        val paused = loop.run(agentRequest(allowed), CollectingEventSink()) { false }
        val pending = assertNotNull(paused.pendingPermission)

        val resumed = loop.run(
            request = agentRequest(allowed).copy(
                resumeContext = paused.resumeContext,
                resumePermission = ResumedPermission(
                    toolName = pending.toolName,
                    arguments = pending.arguments,
                    reason = pending.reason,
                    toolCallId = pending.toolCallId,
                    approved = false,
                    batch = pending.batch,
                    pendingIndex = pending.pendingIndex,
                ),
            ),
            sink = CollectingEventSink(),
            onCancelled = { false },
        )

        assertTrue(guarded.invocations.isEmpty(), "a denied tool must never execute")
        assertEquals(1, one.invocations.size)
        assertEquals(1, two.invocations.size, "an authorised sibling still runs after a denial")
        assertTrue(resumed.errors.any { it.code == AgentErrorCode.PERMISSION_DENIED })
        assertEquals(
            3,
            provider.requests.last().messages.count { it.role == ModelRole.TOOL },
            "a denied call must still leave a result so the transcript stays well formed",
        )
    }

    // --- D. resume is deterministic -----------------------------------------

    @Test
    fun `resume does not duplicate the model request or any tool execution`() = runBlocking {
        val (registry, tools) = fixtures()
        val (one, guarded, two) = tools
        val provider = threeCallProvider()
        val loop = loopOver(registry, provider)

        val paused = loop.run(agentRequest(allowed), CollectingEventSink()) { false }
        val pending = assertNotNull(paused.pendingPermission)
        val callsBeforeResume = provider.completeCalls.size

        val resumed = loop.run(
            request = agentRequest(allowed).copy(
                resumeContext = paused.resumeContext,
                resumePermission = ResumedPermission(
                    toolName = pending.toolName,
                    arguments = pending.arguments,
                    reason = pending.reason,
                    toolCallId = pending.toolCallId,
                    approved = true,
                    batch = pending.batch,
                    pendingIndex = pending.pendingIndex,
                ),
            ),
            sink = CollectingEventSink(),
            onCancelled = { false },
        )

        assertEquals(AgentStatus.COMPLETED, resumed.status)
        assertEquals(1, callsBeforeResume, "the pause happened after the first model call")
        assertEquals(
            1,
            provider.completeCalls.size - callsBeforeResume,
            "resume must not re-ask the model while the assistant message still has unresolved calls",
        )
        // Each tool ran exactly once across the whole pause/resume cycle.
        assertEquals(1, one.invocations.size)
        assertEquals(1, guarded.invocations.size)
        assertEquals(1, two.invocations.size)
    }

    // --- N. statistics survive a pause --------------------------------------

    @Test
    fun `a pause and resume does not double-count steps or tools`() = runBlocking {
        val (registry, tools) = fixtures()
        val (one, guarded, _) = tools
        val provider = threeCallProvider()
        val loop = loopOver(registry, provider)

        val paused = loop.run(agentRequest(allowed), CollectingEventSink()) { false }
        // Only the call that actually produced a result is counted before the pause.
        assertEquals(1, paused.stepStats.toolCalls)
        assertEquals(1, paused.stepStats.modelCalls)

        val pending = assertNotNull(paused.pendingPermission)
        val resumed = loop.run(
            request = agentRequest(allowed).copy(
                resumeContext = paused.resumeContext,
                resumePermission = ResumedPermission(
                    toolName = pending.toolName,
                    arguments = pending.arguments,
                    reason = pending.reason,
                    toolCallId = pending.toolCallId,
                    approved = true,
                    batch = pending.batch,
                    pendingIndex = pending.pendingIndex,
                ),
            ),
            sink = CollectingEventSink(),
            onCancelled = { false },
        )

        // Each run reports its own accounting. The paused run counted the one call that
        // produced a result before it parked; the resumed run counts the two it ran —
        // the approved guarded_write and read_two. It must not count read_one again,
        // which is exactly what "not 3" asserts here. Across the whole cycle the three
        // calls each executed once.
        assertEquals(
            2,
            resumed.stepStats.toolCalls,
            "resume must count only the calls it actually ran, never read_one again",
        )
        // One model call, after the whole assistant message has results — not one per
        // remaining tool call.
        assertEquals(1, resumed.stepStats.modelCalls, "resume asks the model once")
        assertEquals(1, guarded.invocations.size, "the approved call still runs exactly once")
        assertEquals(1, one.invocations.size, "the call that ran before the pause is not repeated")
    }

    // --- O. states stay distinguishable -------------------------------------

    @Test
    fun `waiting for permission is a distinct state, not a failure`() = runBlocking {
        val (registry, _) = fixtures()
        val loop = loopOver(registry, threeCallProvider())

        val paused = loop.run(agentRequest(allowed), CollectingEventSink()) { false }

        assertEquals(AgentStatus.WAITING_FOR_PERMISSION, paused.status)
        assertTrue(paused.status != AgentStatus.FAILED, "a pause is not a failure")
        assertTrue(paused.status != AgentStatus.CANCELLED, "a pause is not a cancellation")
        assertNotNull(paused.pendingPermission)
        assertTrue(paused.errors.none { it.code == AgentErrorCode.PERMISSION_DENIED })
    }

    @Test
    fun `cancellation reports CANCELLED and never a generic failure`() = runBlocking {
        val (registry, _) = fixtures()
        val provider = threeCallProvider()
        val loop = loopOver(registry, provider)
        val sink = CollectingEventSink()

        val result = loop.run(agentRequest(allowed), sink) { true }

        assertEquals(AgentStatus.CANCELLED, result.status)
        assertTrue(result.status != AgentStatus.FAILED, "cancellation must not become a failure")
        assertTrue(result.errors.none { it.code == AgentErrorCode.MODEL_FAILURE })
        assertTrue(sink.events.any { it is AgentEvent.Cancelled })
        assertTrue(provider.completeCalls.isEmpty(), "no model request may start after cancellation")
    }
}
