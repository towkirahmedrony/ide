package com.agentx.app.ui.ide.state

import com.agentx.app.ui.ide.data.AgentFailureKind
import com.agentx.app.ui.ide.data.AgentSession
import com.agentx.app.ui.ide.data.AgentStreamEvent
import com.agentx.app.ui.ide.model.AgentActivity
import com.agentx.app.ui.ide.model.AgentActivityStatus
import com.agentx.app.ui.ide.model.ChatRole
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AgentViewModelTest {

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `send streams a reply and marks the turn completed`() {
        val session = ScriptedAgentSession { prompt, onEvent ->
            onEvent(AgentStreamEvent.Activity(AgentActivity(AgentActivityStatus.THINKING, "AI responding")))
            onEvent(AgentStreamEvent.Chunk("Hello "))
            onEvent(AgentStreamEvent.Chunk(prompt))
            onEvent(AgentStreamEvent.Completed("Hello $prompt"))
        }
        val viewModel = AgentViewModel(session)

        viewModel.onInputChange("world")
        viewModel.send()

        val state = viewModel.uiState
        assertFalse(state.running)
        assertEquals("", state.input)
        assertEquals(AgentActivityStatus.COMPLETED, state.activity.status)
        assertEquals("Completed", state.activity.label)
        assertEquals("world", state.messages.single { it.role == ChatRole.USER }.text)
        assertEquals("Hello world", state.messages.single { it.role == ChatRole.AGENT }.text)
        assertFalse(state.messages.single { it.role == ChatRole.AGENT }.streaming)
    }

    @Test
    fun `connection failure keeps the user message and shows Connection error`() {
        val viewModel = failingViewModel(AgentFailureKind.CONNECTION, "Could not connect")

        viewModel.onInputChange("ping colab")
        viewModel.send()

        val state = viewModel.uiState
        assertFalse(state.running)
        assertEquals("ping colab", state.messages.single { it.role == ChatRole.USER }.text)
        assertEquals("Could not connect", state.messages.single { it.role == ChatRole.AGENT }.text)
        assertEquals(AgentActivityStatus.CONNECTION_ERROR, state.activity.status)
        assertEquals("Connection error", state.activity.label)
    }

    @Test
    fun `timeout keeps the user message and shows Timeout`() {
        val viewModel = failingViewModel(AgentFailureKind.TIMEOUT, "The model request timed out")

        viewModel.onInputChange("slow turn")
        viewModel.send()

        val state = viewModel.uiState
        assertEquals("slow turn", state.messages.single { it.role == ChatRole.USER }.text)
        assertEquals(AgentActivityStatus.TIMEOUT, state.activity.status)
        assertEquals("Timeout", state.activity.label)
    }

    @Test
    fun `invalid response keeps the user message and shows Invalid response`() {
        val viewModel = failingViewModel(AgentFailureKind.INVALID_RESPONSE, "Model endpoint returned invalid JSON")

        viewModel.onInputChange("say hi")
        viewModel.send()

        val state = viewModel.uiState
        assertEquals("say hi", state.messages.single { it.role == ChatRole.USER }.text)
        assertEquals(AgentActivityStatus.INVALID_RESPONSE, state.activity.status)
        assertEquals("Invalid response", state.activity.label)
    }

    @Test
    fun `session exceptions become visible errors without dropping the prompt`() {
        val session = ScriptedAgentSession { _, _ -> throw IllegalStateException("boom") }
        val viewModel = AgentViewModel(session)

        viewModel.onInputChange("keep me")
        viewModel.send()

        val state = viewModel.uiState
        assertFalse(state.running)
        assertEquals("keep me", state.messages.single { it.role == ChatRole.USER }.text)
        assertEquals("boom", state.messages.single { it.role == ChatRole.AGENT }.text)
        assertEquals(AgentActivityStatus.ERROR, state.activity.status)
        assertEquals("Error", state.activity.label)
    }

    @Test
    fun `tool running permission and failure update the activity bar`() {
        val session = ScriptedAgentSession { _, onEvent ->
            onEvent(AgentStreamEvent.ToolRunning("read_file"))
            onEvent(AgentStreamEvent.ToolFinished("read_file", true, "ok"))
            onEvent(AgentStreamEvent.PermissionRequired("write_file", "needs write"))
            onEvent(AgentStreamEvent.ToolFinished("write_file", false, "denied"))
            onEvent(AgentStreamEvent.Completed("done"))
        }
        val viewModel = AgentViewModel(session)
        viewModel.onInputChange("write")
        viewModel.send()

        val state = viewModel.uiState
        assertEquals(AgentActivityStatus.COMPLETED, state.activity.status)
        assertEquals("done", state.messages.single { it.role == ChatRole.AGENT }.text)
    }

    @Test
    fun `blank input is ignored`() {
        val session = ScriptedAgentSession { _, _ -> error("should not run") }
        val viewModel = AgentViewModel(session)
        val before = viewModel.uiState.messages

        viewModel.onInputChange("   ")
        viewModel.send()

        assertEquals(before, viewModel.uiState.messages)
        assertFalse(viewModel.uiState.running)
    }

    @Test
    fun `send is ignored while a turn is already running`() {
        val gate = CompletableDeferred<Unit>()
        var runs = 0
        val session = ScriptedAgentSession { _, _ ->
            runs += 1
            gate.await()
        }
        val viewModel = AgentViewModel(session)

        viewModel.onInputChange("first")
        viewModel.send()
        assertTrue(viewModel.uiState.running)
        viewModel.onInputChange("second")
        viewModel.send()

        assertEquals(1, runs)
        assertTrue(viewModel.uiState.messages.none { it.role == ChatRole.USER && it.text == "second" })
        gate.complete(Unit)
        assertFalse(viewModel.uiState.running)
    }

    @Test
    fun `tool activity is visible in the transcript`() {
        val session = ScriptedAgentSession { _, onEvent ->
            onEvent(AgentStreamEvent.ToolRequested("search_files", "query=auth"))
            onEvent(AgentStreamEvent.ToolFinished("search_files", true, "2 matches"))
            onEvent(AgentStreamEvent.Completed("Found it"))
        }
        val viewModel = AgentViewModel(session)

        viewModel.onInputChange("find auth")
        viewModel.send()

        val tool = viewModel.uiState.messages.single { it.role == ChatRole.TOOL }
        assertEquals("search_files", tool.toolName)
        assertTrue(tool.text.contains("query=auth"))
        assertTrue(tool.text.contains("2 matches"))
        assertFalse(tool.streaming)
    }

    @Test
    fun `permission prompt is shown and resolved from the chat`() {
        val session = PermissionAgentSession()
        val viewModel = AgentViewModel(session)

        viewModel.onInputChange("edit config")
        viewModel.send()

        val prompt = assertNotNull(viewModel.uiState.pendingPermission)
        assertEquals("write_file", prompt.toolName)
        assertEquals("session-1", prompt.sessionId)
        assertTrue(prompt.detail.contains("config.yaml"))
        assertEquals("WORKSPACE_WRITE", prompt.requiredPermission)
        assertFalse(viewModel.uiState.running)

        viewModel.respondToPermission(true)

        val after = viewModel.uiState
        assertNull(after.pendingPermission)
        assertFalse(after.running)
        assertEquals("Config updated", after.messages.last { it.role == ChatRole.AGENT }.text)
    }

    @Test
    fun `send forwards the live workspace id and selected file`() {
        val captured = mutableListOf<Pair<String?, String?>>()
        val session = object : AgentSession {
            override suspend fun run(
                input: String,
                onEvent: (AgentStreamEvent) -> Unit,
                workspaceId: String?,
                selectedFile: String?,
            ) {
                captured += workspaceId to selectedFile
                onEvent(AgentStreamEvent.Completed("ok"))
            }
        }
        var openFile: String? = "src/Main.kt"
        val viewModel = AgentViewModel(
            session = session,
            workspaceId = "saf-project",
            selectedFile = { openFile },
        )

        viewModel.onInputChange("What does this project do?")
        viewModel.send()

        assertEquals(listOf("saf-project" to "src/Main.kt"), captured)
        openFile = "README.md"
        viewModel.onInputChange("summarise the readme")
        viewModel.send()
        assertEquals("README.md", captured.last().second)
    }

    private fun failingViewModel(kind: AgentFailureKind, message: String): AgentViewModel {
        val session = ScriptedAgentSession { _, onEvent ->
            onEvent(AgentStreamEvent.Failed(message, kind))
        }
        return AgentViewModel(session)
    }

    private class ScriptedAgentSession(
        private val block: suspend (String, (AgentStreamEvent) -> Unit) -> Unit,
    ) : AgentSession {
        override suspend fun run(
            input: String,
            onEvent: (AgentStreamEvent) -> Unit,
            workspaceId: String?,
            selectedFile: String?,
        ) {
            block(input, onEvent)
        }
    }

    /** A session that parks on a permission request, then continues when resolved. */
    private class PermissionAgentSession : AgentSession {
        override suspend fun run(
            input: String,
            onEvent: (AgentStreamEvent) -> Unit,
            workspaceId: String?,
            selectedFile: String?,
        ) {
            onEvent(AgentStreamEvent.Activity(AgentActivity(AgentActivityStatus.THINKING, "AI responding")))
            onEvent(
                AgentStreamEvent.PermissionRequired(
                    toolName = "write_file",
                    reason = "Tool 'write_file' requires approval",
                    sessionId = "session-1",
                    toolCallId = "call-1",
                    detail = "path=config.yaml",
                    requiredPermission = "WORKSPACE_WRITE",
                ),
            )
        }

        override suspend fun resolvePermission(
            sessionId: String,
            approved: Boolean,
            onEvent: (AgentStreamEvent) -> Unit,
        ) {
            assertEquals("session-1", sessionId)
            assertTrue(approved)
            onEvent(AgentStreamEvent.ToolRequested("write_file", "path=config.yaml"))
            onEvent(AgentStreamEvent.ToolFinished("write_file", true, "Wrote config.yaml"))
            onEvent(AgentStreamEvent.Completed("Config updated"))
        }
    }
}
