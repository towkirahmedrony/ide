package com.agentx.app.agent

import com.agentx.app.agent.conversation.InMemoryConversationStore
import com.agentx.app.agent.conversation.MessageRole
import com.agentx.app.agent.conversation.MessageStatus
import com.agentx.app.agent.domain.AgentResult
import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.agent.domain.AgentRunRequest
import com.agentx.app.agent.domain.AgentStatus
import com.agentx.app.agent.domain.CollectingEventSink
import com.agentx.app.agent.orchestrator.AgentSessionStore
import com.agentx.app.agent.orchestrator.InMemoryAgentSessionStore
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
import com.agentx.app.model.json.stringOrNull
import com.agentx.app.tools.DefaultToolRegistry
import com.agentx.app.tools.DefaultToolRouter
import com.agentx.app.tools.ToolCapability
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
import kotlin.test.assertTrue

/**
 * What the memory pipeline keeps across turns, through the real orchestrator, loop,
 * conversation history and Context Engine.
 *
 * The store-level writers and the Context Engine's own budgeting are covered by their
 * own suites; these tests cover the *turn* boundary — what one run leaves behind for
 * the next one, and what the next model request is therefore built from.
 */
class ContextMemoryIntegrityTest {

    private fun tools() = DefaultToolRegistry().also { it.register(RecordingTool("read_file", READ_ONLY)) }

    private fun runtime(
        provider: ModelProvider,
        conversations: InMemoryConversationStore = InMemoryConversationStore(),
        sessions: AgentSessionStore = InMemoryAgentSessionStore(),
        registry: DefaultToolRegistry = tools(),
    ) = AgentModule.assemble(
        gateway = DefaultModelGateway().also { gateway -> gateway.register(provider) },
        registry = registry,
        router = DefaultToolRouter(registry),
        sessions = sessions,
        conversations = conversations,
        timeouts = AgentTimeouts(mainTaskMillis = 30_000L, subAgentTaskMillis = 30_000L),
    )

    private fun request(prompt: String, sessionId: String = SESSION, workspaceId: String = PROJECT) =
        AgentRunRequest(prompt = prompt, sessionId = sessionId, workspaceId = workspaceId)

    private fun texts(runtime: AgentRuntime) = assertNotNull(runtime.history.conversation(SESSION))
        .messages
        .map { it.content.text }

    private fun roles(runtime: AgentRuntime) = assertNotNull(runtime.history.conversation(SESSION))
        .messages
        .map { it.role }

    // ───────────────────── A, B, I: multi-turn persistence ─────────────────────

    @Test
    fun `every turn's answer is persisted in order`() = runAgent {
        val provider = ScriptedModelProvider(
            mapOf(AgentRole.MAIN to mutableListOf(response("First answer"), response("Second answer"))),
        )
        val runtime = runtime(provider)

        runtime.orchestrator.run(request("first"), testConfig(), CollectingEventSink())
        runtime.orchestrator.run(request("second"), testConfig(), CollectingEventSink())

        assertEquals(
            listOf("first", "First answer", "second", "Second answer"),
            texts(runtime),
        )
        assertEquals(
            listOf(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.USER, MessageRole.ASSISTANT),
            roles(runtime),
        )
    }

    /**
     * The same words in a later turn are a new turn, not a repeat of the recorded one.
     * Dropping it loses what the user actually asked.
     */
    @Test
    fun `a prompt repeated in a later turn is recorded as its own turn`() = runAgent {
        val provider = ScriptedModelProvider(
            mapOf(AgentRole.MAIN to mutableListOf(response("First answer"), response("Second answer"))),
        )
        val runtime = runtime(provider)

        runtime.orchestrator.run(request("continue"), testConfig(), CollectingEventSink())
        runtime.orchestrator.run(request("continue"), testConfig(), CollectingEventSink())

        assertEquals(
            listOf("continue", "First answer", "continue", "Second answer"),
            texts(runtime),
        )
    }

    // ───────────────────── C: a failure is recorded as a failure ─────────────────────

    @Test
    fun `a failed turn is recorded as a failure and keeps the previous answer`() = runAgent {
        val provider = FailingCallProvider(
            first = response("First answer"),
            error = providerError(ModelProviderErrorCode.RATE_LIMITED, "Rate limit exceeded"),
        )
        val runtime = runtime(provider)

        assertEquals(AgentStatus.COMPLETED, runtime.orchestrator.run(request("first"), testConfig(), CollectingEventSink()).status)
        val failed = runtime.orchestrator.run(request("second"), testConfig(), CollectingEventSink())
        assertEquals(AgentStatus.FAILED, failed.status)

        assertEquals(
            listOf(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.USER, MessageRole.ERROR),
            roles(runtime),
        )
        val messages = assertNotNull(runtime.history.conversation(SESSION)).messages
        // The answer that was actually given is untouched ...
        assertEquals("First answer", messages[1].content.text)
        assertEquals(MessageStatus.COMPLETED, messages[1].metadata.status)
        // ... and the failed turn is a failure the next run can see as one.
        assertEquals(failed.errors.first().message, messages[3].content.text)
        assertEquals(MessageStatus.ERROR, messages[3].metadata.status)
        assertEquals("MODEL_FAILURE", messages[3].content.errorCode)
        assertNotNull(runtime.history.conversation(SESSION)).let { conversation ->
            assertEquals(AgentStatus.FAILED, conversation.status)
        }
    }

    // ───────────────────── D: a cancelled turn is not an answer ─────────────────────

    @Test
    fun `a cancelled turn is not stored as a completed answer`() = runAgent {
        var running: AgentRuntime? = null
        val provider = CancelOnCallProvider(
            first = response("First answer"),
            second = response("", toolCall("read_file", "path" to "Auth.kt", id = "r1")),
            onSecondCall = { running?.orchestrator?.cancel(SESSION) },
        )
        val runtime = runtime(provider)
        running = runtime

        assertEquals(AgentStatus.COMPLETED, runtime.orchestrator.run(request("first"), testConfig(), CollectingEventSink()).status)
        val outcome = runCancelledTurn(runtime) { runtime.orchestrator.run(request("second"), testConfig(), CollectingEventSink()) }
        assertEquals(AgentStatus.CANCELLED, outcome.status)

        // The cancelled turn leaves no answer behind, and does not disturb the previous one.
        assertEquals(
            listOf(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.USER, MessageRole.ERROR),
            roles(runtime),
        )
        val messages = assertNotNull(runtime.history.conversation(SESSION)).messages
        assertEquals("First answer", messages[1].content.text)
        assertTrue(messages[3].metadata.status == MessageStatus.ERROR, "a cancelled turn must not be completed")
        assertFalse(messages.any { it.content.text == "second" && it.role == MessageRole.ASSISTANT })
    }

    // ──────────────── L: a turn that pauses is still recorded exactly once ────────────────

    @Test
    fun `a paused turn is recorded once, when it finally ends`() = runAgent {
        val write = RecordingTool(
            "write_file",
            setOf(ToolCapability.MUTATING, ToolCapability.FILESYSTEM),
            permission = ToolPermissionDecision.ASK,
            required = setOf(ToolPermissionLevel.WORKSPACE_WRITE),
        )
        val registry = DefaultToolRegistry().also { it.register(write) }
        val provider = ScriptedModelProvider(
            mapOf(
                AgentRole.MAIN to mutableListOf(
                    response("", toolCall("write_file", "path" to "Auth.kt", id = "w1")),
                    response("Added the guard"),
                ),
            ),
        )
        val runtime = runtime(provider, registry = registry)

        val paused = runtime.orchestrator.run(request("harden auth"), testConfig(), CollectingEventSink())
        assertEquals(AgentStatus.WAITING_FOR_PERMISSION, paused.status)
        // A pause is not an answer, and it is not a second copy of the question either.
        assertEquals(listOf(MessageRole.USER), roles(runtime))

        val resumed = assertNotNull(runtime.orchestrator.resumePermission(SESSION, true, CollectingEventSink()))
        assertEquals(AgentStatus.COMPLETED, resumed.status)
        // One question, one answer, in that order — the ask was already recorded.
        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT), roles(runtime))
        assertEquals(listOf("harden auth", "Added the guard"), texts(runtime))
        assertEquals(1, write.invocations.size)
    }

    // ───────────────────── E: tool call and result ordering ─────────────────────

    @Test
    fun `a tool call and its result reach the next request in order with their call intact`() = runAgent {
        val read = RecordingTool("read_file", READ_ONLY)
        val provider = ScriptedModelProvider(
            mapOf(
                AgentRole.MAIN to mutableListOf(
                    response("", toolCall("read_file", "path" to "Auth.kt", id = "r1")),
                    response("Auth.kt holds the login form"),
                ),
            ),
        )
        val runtime = runtime(provider, registry = DefaultToolRegistry().also { it.register(read) })

        runtime.orchestrator.run(request("find the login"), testConfig(), CollectingEventSink())

        val second = provider.requests[1]
        // system, this turn's user message, then the assistant's call and its result.
        assertEquals(
            listOf(ModelRole.SYSTEM, ModelRole.USER, ModelRole.ASSISTANT, ModelRole.TOOL),
            second.messages.map { it.role },
        )
        val call = second.messages[2]
        assertEquals(listOf("r1"), call.toolCalls.map { it.id })
        assertEquals(listOf("read_file"), call.toolCalls.map { it.name })
        assertEquals("Auth.kt", call.toolCalls.single().arguments["path"]?.stringOrNull())
        val result = second.messages[3]
        assertEquals("r1", result.toolCallId)
        assertEquals("read_file", result.name)
        assertTrue(result.content.isNotBlank())
        // The tool ran once for one call.
        assertEquals(1, read.invocations.size)
    }

    // ───────────────────── F: project isolation ─────────────────────

    @Test
    fun `a run never sees another project's turns`() = runAgent {
        val provider = ScriptedModelProvider(
            mapOf(
                AgentRole.MAIN to mutableListOf(
                    response("Alpha answer"),
                    response("Beta answer"),
                ),
            ),
        )
        val runtime = runtime(provider)

        runtime.orchestrator.run(
            request("alpha-secret-marker", sessionId = "session-a", workspaceId = "project-alpha"),
            testConfig(),
            CollectingEventSink(),
        )
        runtime.orchestrator.run(
            request("beta task", sessionId = "session-b", workspaceId = "project-beta"),
            testConfig(),
            CollectingEventSink(),
        )

        val betaRequest = provider.requests.last()
        val sent = betaRequest.messages.joinToString("\n") { it.content }
        assertFalse(sent.contains("alpha-secret-marker"), sent)
        assertFalse(sent.contains("Alpha answer"), sent)
        assertTrue(sent.contains("beta task"), sent)

        // The stored turns stay with their own session and project too.
        val alpha = assertNotNull(runtime.history.conversation("session-a"))
        assertEquals("project-alpha", alpha.workspaceId)
        assertEquals(listOf("alpha-secret-marker", "Alpha answer"), alpha.messages.map { it.content.text })
        val beta = assertNotNull(runtime.history.conversation("session-b"))
        assertEquals("project-beta", beta.workspaceId)
        assertEquals(listOf("beta task", "Beta answer"), beta.messages.map { it.content.text })
        assertTrue(runtime.history.conversations("project-beta").none { it.id == "session-a" })
    }

    // ───────────────────── I: reload rebuilds the same context ─────────────────────

    @Test
    fun `a fresh runtime over the same stores rebuilds the same prior turns in order`() = runAgent {
        val conversations = InMemoryConversationStore()
        val sessions = InMemoryAgentSessionStore()

        val writer = runtime(
            ScriptedModelProvider(mapOf(AgentRole.MAIN to mutableListOf(response("First answer"), response("Second answer")))),
            conversations = conversations,
            sessions = sessions,
        )
        writer.orchestrator.run(request("first"), testConfig(), CollectingEventSink())
        writer.orchestrator.run(request("second"), testConfig(), CollectingEventSink())

        // An application restart: same stores, new agent core.
        val provider = ScriptedModelProvider(mapOf(AgentRole.MAIN to mutableListOf(response("Third answer"))))
        val reloaded = runtime(provider, conversations = conversations, sessions = sessions)
        reloaded.orchestrator.run(request("third"), testConfig(), CollectingEventSink())

        val sent = provider.requests.single().messages.joinToString("\n") { it.content }
        assertTrue(sent.contains("First answer"), sent)
        assertTrue(sent.contains("Second answer"), sent)
        assertTrue(sent.contains("first"), sent)
        assertTrue(sent.contains("second"), sent)
        // The rebuilt transcript reads as it happened: oldest first, and each line
        // attributed to the side that said it.
        assertEquals(
            listOf("user: first", "assistant: First answer", "user: second", "assistant: Second answer"),
            transcriptOf(provider.requests.single()),
        )

        // And the rebuilt transcript is the same conversation, extended by one turn.
        assertEquals(
            listOf("first", "First answer", "second", "Second answer", "third", "Third answer"),
            assertNotNull(reloaded.history.conversation(SESSION)).messages.map { it.content.text },
        )
    }

    // ───────────────────── helpers ─────────────────────

    /** The rendered conversation block of a request, one entry per prior message. */
    private fun transcriptOf(request: ModelRequest): List<String> = request.messages
        .joinToString("\n\n") { it.content }
        .split("\n\n")
        .filter { it.startsWith("[CONVERSATION]") }
        .map { block -> block.substringAfter('\n').trim() }

    /** Runs a turn that cancels itself, in its own job, so the cancel hits the run. */
    private suspend fun runCancelledTurn(runtime: AgentRuntime, block: suspend () -> AgentResult): AgentResult {
        var outcome: AgentResult? = null
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        try {
            scope.async { block().also { result -> outcome = result } }.join()
        } finally {
            scope.cancel()
        }
        return assertNotNull(outcome, "a cancelled turn must still end and report itself")
    }

    /** Answers the first call, fails every one after it. */
    private class FailingCallProvider(
        private val first: ModelResponse,
        private val error: Throwable,
    ) : ModelProvider {
        override val id: String = "test"
        private var calls = 0

        override fun capabilities(modelId: String): ModelCapabilities =
            ModelCapabilities(toolCalling = true, streaming = true)

        override suspend fun complete(request: ModelRequest): ModelResponse {
            calls++
            if (calls > 1) throw error
            return first
        }

        override suspend fun stream(request: ModelRequest, onEvent: (ModelStreamEvent) -> Unit): ModelResponse =
            complete(request).also { onEvent(ModelStreamEvent.TextDelta(it.content)) }
    }

    /** Answers the first call, then cancels the run as it is asked the second one. */
    private class CancelOnCallProvider(
        private val first: ModelResponse,
        private val second: ModelResponse,
        private val onSecondCall: () -> Unit,
    ) : ModelProvider {
        override val id: String = "test"
        private var calls = 0

        override fun capabilities(modelId: String): ModelCapabilities =
            ModelCapabilities(toolCalling = true, streaming = true)

        override suspend fun complete(request: ModelRequest): ModelResponse {
            calls++
            if (calls == 1) return first
            if (calls == 2) onSecondCall()
            return second
        }

        override suspend fun stream(request: ModelRequest, onEvent: (ModelStreamEvent) -> Unit): ModelResponse =
            complete(request).also { onEvent(ModelStreamEvent.TextDelta(it.content)) }
    }

    private companion object {
        const val SESSION = "session-1"
        const val PROJECT = "project-1"
        val READ_ONLY = setOf(ToolCapability.READ_ONLY, ToolCapability.FILESYSTEM)
    }
}
