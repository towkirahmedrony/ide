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

    private fun failingViewModel(kind: AgentFailureKind, message: String): AgentViewModel {
        val session = ScriptedAgentSession { _, onEvent ->
            onEvent(AgentStreamEvent.Failed(message, kind))
        }
        return AgentViewModel(session)
    }

    private class ScriptedAgentSession(
        private val block: suspend (String, (AgentStreamEvent) -> Unit) -> Unit,
    ) : AgentSession {
        override suspend fun run(input: String, onEvent: (AgentStreamEvent) -> Unit) {
            block(input, onEvent)
        }
    }
}
