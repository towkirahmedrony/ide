package com.agentx.app.agent

import com.agentx.app.agent.conversation.MessageRole
import com.agentx.app.agent.conversation.MessageStatus
import com.agentx.app.agent.domain.AgentErrorCode
import com.agentx.app.agent.domain.AgentEvent
import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.agent.domain.AgentResult
import com.agentx.app.agent.domain.AgentRunRequest
import com.agentx.app.agent.domain.AgentStatus
import com.agentx.app.agent.domain.CollectingEventSink
import com.agentx.app.agent.protocol.AgentProtocol
import com.agentx.app.core.timeout.AgentTimeouts
import com.agentx.app.model.DefaultModelGateway
import com.agentx.app.model.ModelCapabilities
import com.agentx.app.model.ModelProvider
import com.agentx.app.model.ModelProviderErrorCode
import com.agentx.app.model.ModelRequest
import com.agentx.app.model.ModelResponse
import com.agentx.app.model.ModelRole
import com.agentx.app.model.ModelStreamEvent
import com.agentx.app.tools.DefaultToolRegistry
import com.agentx.app.tools.DefaultToolRouter
import com.agentx.app.tools.Json
import com.agentx.app.tools.Tool
import com.agentx.app.tools.ToolCapability
import com.agentx.app.tools.ToolDefinition
import com.agentx.app.tools.ToolExecutionContext
import com.agentx.app.tools.ToolInput
import com.agentx.app.tools.ToolInputSchema
import com.agentx.app.tools.ToolOutput
import com.agentx.app.tools.ToolParameter
import com.agentx.app.tools.ToolParameterType
import com.agentx.app.tools.ToolPermissionDecision
import com.agentx.app.tools.ToolPermissionLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Main → specialist → specialist model → result → Main, end to end through the real
 * [com.agentx.app.agent.orchestrator.DefaultAgentOrchestrator], [AgentLoop] and
 * conversation history.
 *
 * The assertions here are about *carrying*: the specialist is given the delegated task
 * and the parent's project, its result reaches Main as a structured tool result in the
 * right position exactly once, its failure is reported as a failure, its partial stream
 * is never presented as an answer, and a cancelled run leaves nothing behind that a
 * later turn could mistake for a finished result.
 */
class SpecializedExecutionAuditTest {

    private fun tools(read: Tool = ContextRecordingTool()) = DefaultToolRegistry().also {
        it.register(read)
    }

    private fun runtime(provider: ModelProvider, registry: DefaultToolRegistry = tools()) = AgentModule.assemble(
        gateway = DefaultModelGateway().also { gateway -> gateway.register(provider) },
        registry = registry,
        router = DefaultToolRouter(registry),
        timeouts = AgentTimeouts(mainTaskMillis = 30_000L, subAgentTaskMillis = 30_000L),
    )

    private fun delegate(role: AgentRole, task: String, objective: String = task, context: String? = null) =
        if (context == null) {
            response(
                "",
                toolCall(
                    AgentProtocol.DELEGATE_TOOL,
                    AgentProtocol.ARG_ROLE to role.name,
                    AgentProtocol.ARG_TASK to task,
                    AgentProtocol.ARG_OBJECTIVE to objective,
                ),
            )
        } else {
            response(
                "",
                toolCall(
                    AgentProtocol.DELEGATE_TOOL,
                    AgentProtocol.ARG_ROLE to role.name,
                    AgentProtocol.ARG_TASK to task,
                    AgentProtocol.ARG_OBJECTIVE to objective,
                    AgentProtocol.ARG_CONTEXT to context,
                ),
            )
        }

    private fun finish(summary: String, findings: String? = null) =
        if (findings == null) {
            response("", toolCall(AgentProtocol.FINISH_TOOL, AgentProtocol.ARG_SUMMARY to summary))
        } else {
            response(
                "",
                toolCall(
                    AgentProtocol.FINISH_TOOL,
                    AgentProtocol.ARG_SUMMARY to summary,
                    AgentProtocol.ARG_FINDINGS to findings,
                ),
            )
        }

    private fun mainRequests(requests: List<ModelRequest>) = requests.filter { roleOf(it) == AgentRole.MAIN }

    private fun specialistRequests(requests: List<ModelRequest>, role: AgentRole) =
        requests.filter { roleOf(it) == role }

    private fun toolResultOf(request: ModelRequest) =
        assertNotNull(request.messages.lastOrNull { it.role == ModelRole.TOOL }, "the delegate result must be sent back")

    // ───────────────────────── A, B, E, I: the happy path ─────────────────────────

    @Test
    fun `a specialist runs the delegated task in the parent project and its result reaches main`() = runAgent {
        val read = ContextRecordingTool()
        val provider = ScriptedModelProvider(
            mapOf(
                AgentRole.MAIN to mutableListOf(
                    delegate(
                        role = AgentRole.EXPLORER,
                        task = "map the auth flow",
                        objective = "find the login entry point",
                        context = "Auth.kt holds the login form",
                    ),
                    finish("Auth flow mapped"),
                ),
                AgentRole.EXPLORER to mutableListOf(
                    response("", toolCall("read_file", "path" to "Auth.kt", id = "r1")),
                    finish("Login lives in Auth.kt", findings = "session is held in memory"),
                ),
            ),
        )
        val sink = CollectingEventSink()
        val runtime = runtime(provider, tools(read))

        val result = runtime.orchestrator.run(
            request = AgentRunRequest(prompt = "Understand auth", sessionId = "main-a", workspaceId = "project-a"),
            modelConfig = testConfig(),
            sink = sink,
        )

        assertEquals(AgentStatus.COMPLETED, result.status)
        assertEquals("Auth flow mapped", result.summary)

        // The specialist received the delegated task and the context it was scoped with.
        val explorerRequest = specialistRequests(provider.requests, AgentRole.EXPLORER).first()
        val explorerPrompt = explorerRequest.messages.joinToString("\n") { it.content }
        assertTrue(explorerPrompt.contains("map the auth flow"), explorerPrompt)
        assertTrue(explorerPrompt.contains("Auth.kt holds the login form"), explorerPrompt)

        // The specialist's own session is owned by the parent's project and parent session.
        val started = assertNotNull(sink.events.filterIsInstance<AgentEvent.SubAgentStarted>().singleOrNull())
        assertEquals(AgentRole.EXPLORER, started.role)
        val childSession = assertNotNull(runtime.orchestrator.session(started.sessionId))
        assertEquals("project-a", childSession.workspaceId)
        assertEquals("main-a", childSession.parentSessionId)
        assertEquals(AgentStatus.COMPLETED, childSession.status)
        // A specialist's session is not a project chat of its own.
        assertTrue(runtime.history.conversations("project-a").none { it.id == started.sessionId })

        // ... and its tools ran in that project and in the specialist's own session, not
        // in a global one and not in another project's.
        assertEquals(listOf<String?>("project-a"), read.workspaceIds)
        assertEquals(listOf<String?>(started.sessionId), read.sessionIds)
        assertEquals(listOf<String?>(AgentRole.EXPLORER.name), read.agentSessionIds)

        // MAIN's second request carried the specialist's result as the delegate result.
        val second = mainRequests(provider.requests)[1]
        val toolResult = toolResultOf(second)
        assertEquals("call-delegate_to_agent", toolResult.toolCallId)
        assertTrue(toolResult.content.contains("status=COMPLETED"), toolResult.content)
        assertTrue(toolResult.content.contains("Login lives in Auth.kt"), toolResult.content)
        assertTrue(toolResult.content.contains("session is held in memory"), toolResult.content)

        // The result follows the assistant message that asked for it: never a result
        // before its call, and never one without the other.
        val assistantIndex = second.messages.indexOfLast { it.role == ModelRole.ASSISTANT && it.toolCalls.isNotEmpty() }
        val toolIndex = second.messages.indexOfLast { it.role == ModelRole.TOOL }
        assertTrue(assistantIndex in 0 until toolIndex, "the delegate call must precede its result")
        assertTrue(second.messages.drop(toolIndex + 1).none { it.role == ModelRole.TOOL }, "no result may repeat")
    }

    // ───────────────────────── J: recorded once, attributed correctly ─────────────────────────

    @Test
    fun `the specialist result and main reply are recorded once each against their own sessions`() = runAgent {
        val provider = ScriptedModelProvider(
            mapOf(
                AgentRole.MAIN to mutableListOf(
                    delegate(AgentRole.CODER, "edit Auth.kt"),
                    finish("Edited Auth.kt"),
                ),
                AgentRole.CODER to mutableListOf(finish("Added the guard")),
            ),
        )
        val sink = CollectingEventSink()
        val runtime = runtime(provider)

        val result = runtime.orchestrator.run(
            request = AgentRunRequest(prompt = "Harden auth", sessionId = "main-b", workspaceId = "project-b"),
            modelConfig = testConfig(),
            sink = sink,
        )
        assertEquals(AgentStatus.COMPLETED, result.status)

        val main = assertNotNull(runtime.history.conversation("main-b"))
        // The user turn appears once, the specialist once, and MAIN's reply once.
        assertEquals(1, main.messages.count { it.role == MessageRole.USER })
        val specialists = main.messages.filter { it.role == MessageRole.SUB_AGENT }
        assertEquals(1, specialists.size, "one delegation is recorded once")
        assertEquals("CODER", specialists.single().content.subAgentRole)
        assertEquals(MessageStatus.COMPLETED, specialists.single().metadata.status)
        val assistant = main.messages.filter { it.role == MessageRole.ASSISTANT }
        assertEquals(1, assistant.size)
        assertEquals("Edited Auth.kt", assistant.single().content.text)
        assertNull(assistant.single().content.errorCode)

        // The specialist's own transcript is owned by the same project and holds its task.
        val childId = assertNotNull(sink.events.filterIsInstance<AgentEvent.SubAgentStarted>().singleOrNull()).sessionId
        val child = assertNotNull(runtime.history.conversation(childId))
        assertEquals("project-b", child.workspaceId)
        assertEquals(1, child.messages.count { it.role == MessageRole.USER })
    }

    // ───────────────────────── C, G, H: failure is not an answer ─────────────────────────

    @Test
    fun `a specialist whose model fails reaches main as a failure, never as its output`() = runAgent {
        val failure = providerError(ModelProviderErrorCode.RATE_LIMITED, "Rate limit exceeded")
        val provider = FailingRoleProvider(
            failing = AgentRole.CODER,
            error = failure,
            streamedBeforeFailing = "partially streamed edit",
            inner = ScriptedModelProvider(
                mapOf(
                    AgentRole.MAIN to mutableListOf(
                        delegate(AgentRole.CODER, "edit the auth guard"),
                        finish("Could not edit; the specialist reported a failure"),
                    ),
                ),
            ),
        )
        val sink = CollectingEventSink()
        val runtime = runtime(provider)

        val result = runtime.orchestrator.run(
            request = AgentRunRequest(prompt = "Review my change", sessionId = "main-c", workspaceId = "project-c"),
            // Streaming: the endpoint emits part of the answer and then breaks, which is
            // exactly the case where partial output must not be mistaken for an answer.
            modelConfig = testConfig().copy(stream = true),
            sink = sink,
        )

        // MAIN completes — it was told, and chose to report it.
        assertEquals(AgentStatus.COMPLETED, result.status)
        // The specialist's failure is structured runtime state on the parent, carrying
        // the provider reason it failed for. It is not a substitute model's answer.
        assertTrue(
            result.errors.any { it.details["providerError"] == ModelProviderErrorCode.RATE_LIMITED.name },
            "the specialist's provider failure must reach main: ${result.errors}",
        )
        assertTrue(
            result.toolActions.any { it.toolName == AgentProtocol.DELEGATE_TOOL && !it.success },
            "the delegation must be recorded as a failed action: ${result.toolActions}",
        )

        // The result MAIN received says FAILED, carries the reason, and never claims success.
        val toolResult = toolResultOf(mainRequests(provider.requests)[1])
        assertTrue(toolResult.content.contains("status=FAILED"), toolResult.content)
        assertTrue(toolResult.content.contains("Rate limit exceeded"), toolResult.content)
        assertFalse(toolResult.content.contains("status=COMPLETED"), toolResult.content)
        // The partial stream is not offered as the specialist's answer.
        assertFalse(toolResult.content.contains("partially streamed edit"), toolResult.content)

        // No success is reported for the specialist, and its session ended failed.
        assertTrue(
            sink.events.none { it is AgentEvent.SubAgentCompleted && it.status == AgentStatus.COMPLETED },
            "a failed specialist must not produce a completed event",
        )
        val childId = assertNotNull(sink.events.filterIsInstance<AgentEvent.SubAgentStarted>().singleOrNull()).sessionId
        assertEquals(AgentStatus.FAILED, assertNotNull(runtime.orchestrator.session(childId)).status)
        assertEquals(AgentStatus.FAILED, assertNotNull(runtime.history.conversation(childId)).status)

        // MAIN's stored reply is MAIN's summary: a specialist's error never becomes the
        // assistant's answer, in memory or after a reload.
        val assistant = assertNotNull(runtime.history.conversation("main-c"))
            .messages
            .last { it.role == MessageRole.ASSISTANT }
        assertEquals("Could not edit; the specialist reported a failure", assistant.content.text)
        assertNull(assistant.content.errorCode)
        assertFalse(assistant.content.text.contains("Rate limit exceeded"))
    }

    // ───────────────────────── D: cancellation leaves no late result ─────────────────────────

    @Test
    fun `cancelling while a specialist works stops the run and main is never given a result`() = runAgent {
        var running: AgentRuntime? = null
        val provider = CancelOnRoleProvider(
            role = AgentRole.CODER,
            onCalled = { running?.orchestrator?.cancel("main-d") },
            inner = ScriptedModelProvider(
                mapOf(
                    AgentRole.MAIN to mutableListOf(
                        delegate(AgentRole.CODER, "edit Auth.kt"),
                        finish("Edited Auth.kt"),
                    ),
                    // The specialist is mid-work when the run is cancelled: its next call
                    // never runs, and the run must stop rather than continue to MAIN.
                    AgentRole.CODER to mutableListOf(response("", toolCall("read_file", "path" to "Auth.kt", id = "r1"))),
                ),
            ),
        )
        val sink = CollectingEventSink()
        val assembled = runtime(provider)
        running = assembled

        // The run executes in its own coroutine, the way the IDE runs a turn, so Stop
        // cancels *that* job — exactly what the orchestrator is asked to do.
        var outcome: AgentResult? = null
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        try {
            val run = scope.async {
                assembled.orchestrator.run(
                    request = AgentRunRequest(prompt = "Harden auth", sessionId = "main-d", workspaceId = "project-d"),
                    modelConfig = testConfig(),
                    sink = sink,
                ).also { result -> outcome = result }
            }
            run.join()
        } finally {
            scope.cancel()
        }
        val result = assertNotNull(outcome, "a cancelled run must still end and report itself")

        assertEquals(AgentStatus.CANCELLED, result.status, "a cancelled run must not report success")
        // MAIN was never asked again, so no result of the cancelled specialist was used.
        assertEquals(1, mainRequests(provider.requests).size, "no model call may follow the cancellation")
        assertTrue(
            sink.events.none { it is AgentEvent.SubAgentCompleted && it.status == AgentStatus.COMPLETED },
            "a cancelled specialist must not be reported as completed",
        )
        assertTrue(sink.events.none { it is AgentEvent.Completed }, "no success event may be emitted")

        val childId = assertNotNull(sink.events.filterIsInstance<AgentEvent.SubAgentStarted>().singleOrNull()).sessionId
        assertEquals(AgentStatus.CANCELLED, assertNotNull(assembled.orchestrator.session(childId)).status)
        assertEquals(
            AgentStatus.CANCELLED,
            assertNotNull(assembled.orchestrator.session("main-d")).status,
            "the run must end cancelled rather than stay registered as waiting for its specialist",
        )
    }

    // ───────────────────────── F: a denied specialist tool is never a success ─────────────────────────

    @Test
    fun `denying a specialist tool is reported to main and never runs`() = runAgent {
        val write = RecordingTool(
            "write_file",
            setOf(ToolCapability.MUTATING, ToolCapability.FILESYSTEM),
            permission = ToolPermissionDecision.ASK,
            required = setOf(ToolPermissionLevel.WORKSPACE_WRITE),
        )
        val registry = DefaultToolRegistry().also {
            it.register(ContextRecordingTool())
            it.register(write)
        }
        val provider = ScriptedModelProvider(
            mapOf(
                AgentRole.MAIN to mutableListOf(
                    delegate(AgentRole.CODER, "edit Auth.kt"),
                    finish("Handled the specialist's decision"),
                ),
                AgentRole.CODER to mutableListOf(
                    response("", toolCall("write_file", "path" to "Auth.kt", id = "w1")),
                    finish("Cannot write without approval"),
                ),
            ),
        )
        val sink = CollectingEventSink()
        val runtime = runtime(provider, registry)

        val paused = runtime.orchestrator.run(
            request = AgentRunRequest(prompt = "Harden auth", sessionId = "main-f", workspaceId = "project-f"),
            modelConfig = testConfig(),
            sink = sink,
        )
        assertEquals(AgentStatus.WAITING_FOR_PERMISSION, paused.status)
        // A pause is not an outcome: nothing may be recorded as a finished specialist yet.
        assertTrue(
            runtime.history.conversation("main-f")?.messages.orEmpty().none { it.role == MessageRole.SUB_AGENT },
            "a paused specialist must not be recorded as a completed or failed delegation",
        )

        val resumed = assertNotNull(runtime.orchestrator.resumePermission("main-f", false, sink))
        assertEquals(AgentStatus.COMPLETED, resumed.status)
        assertTrue(write.invocations.isEmpty(), "a denied tool must never run")
        assertTrue(resumed.errors.any { it.code == AgentErrorCode.PERMISSION_DENIED })

        // MAIN is told the specialist was blocked, and its own reply is what is stored.
        val toolResult = toolResultOf(mainRequests(provider.requests).last())
        assertTrue(toolResult.content.contains("denied"), toolResult.content)
        val main = assertNotNull(runtime.history.conversation("main-f"))
        val assistant = main.messages.last { it.role == MessageRole.ASSISTANT }
        assertEquals("Handled the specialist's decision", assistant.content.text)
        assertNull(assistant.content.errorCode)
        // The specialist did finish (it adapted) and is recorded as such, once.
        val specialists = main.messages.filter { it.role == MessageRole.SUB_AGENT }
        assertEquals(1, specialists.size)
        assertEquals(MessageStatus.COMPLETED, specialists.single().metadata.status)
    }

    // ───────────────────────── helpers ─────────────────────────

    /**
     * A provider that behaves normally except for one role, whose request fails the way a
     * real endpoint fails: after an optional first delta the connection is lost.
     */
    private class FailingRoleProvider(
        private val failing: AgentRole,
        private val error: Throwable,
        private val inner: ScriptedModelProvider,
        private val streamedBeforeFailing: String = "",
    ) : ModelProvider {

        override val id: String = "test"

        val requests: List<ModelRequest> get() = inner.requests

        override fun capabilities(modelId: String): ModelCapabilities =
            ModelCapabilities(toolCalling = true, streaming = true)

        override suspend fun complete(request: ModelRequest): ModelResponse {
            if (roleOf(request) == failing) throw error
            return inner.complete(request)
        }

        override suspend fun stream(request: ModelRequest, onEvent: (ModelStreamEvent) -> Unit): ModelResponse {
            if (roleOf(request) == failing) {
                if (streamedBeforeFailing.isNotEmpty()) onEvent(ModelStreamEvent.TextDelta(streamedBeforeFailing))
                throw error
            }
            return inner.stream(request, onEvent)
        }
    }

    /** Cancels the run as the given role is asked a question — the user pressing Stop. */
    private class CancelOnRoleProvider(
        private val role: AgentRole,
        private val onCalled: () -> Unit,
        private val inner: ScriptedModelProvider,
    ) : ModelProvider {

        override val id: String = "test"

        val requests: List<ModelRequest> get() = inner.requests

        override fun capabilities(modelId: String): ModelCapabilities =
            ModelCapabilities(toolCalling = true, streaming = true)

        override suspend fun complete(request: ModelRequest): ModelResponse {
            if (roleOf(request) == role) onCalled()
            return inner.complete(request)
        }

        override suspend fun stream(request: ModelRequest, onEvent: (ModelStreamEvent) -> Unit): ModelResponse {
            if (roleOf(request) == role) onCalled()
            return inner.stream(request, onEvent)
        }
    }

    /** A read tool that records the workspace it was executed against. */
    private class ContextRecordingTool(name: String = "read_file") : Tool {
        val workspaceIds = mutableListOf<String?>()
        val sessionIds = mutableListOf<String?>()
        val agentSessionIds = mutableListOf<String?>()

        override val definition = ToolDefinition(
            name = name,
            description = "Records the execution context it was given",
            inputSchema = ToolInputSchema(
                parameters = listOf(ToolParameter("path", ToolParameterType.STRING, required = false)),
            ),
            permission = ToolPermissionDecision.ALLOW,
            capabilities = setOf(ToolCapability.READ_ONLY, ToolCapability.FILESYSTEM),
        )

        override suspend fun execute(input: ToolInput, context: ToolExecutionContext): ToolOutput {
            workspaceIds += context.workspaceId
            sessionIds += context.sessionId
            agentSessionIds += context.agentId
            return ToolOutput(content = mapOf("ok" to Json.of(true)), displayText = "ok")
        }
    }
}

/**
 * The role a request belongs to, read from the system instruction the loop built — the
 * same signal the scripted provider uses to pick a script.
 */
private fun roleOf(request: ModelRequest): AgentRole {
    val system = request.messages.firstOrNull { it.role == ModelRole.SYSTEM }?.content.orEmpty()
    return AgentRole.entries.firstOrNull { role -> system.contains("Role: ${role.name}") } ?: AgentRole.MAIN
}
