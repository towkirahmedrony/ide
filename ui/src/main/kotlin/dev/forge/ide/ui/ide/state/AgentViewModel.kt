package dev.forge.ide.ui.ide.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.forge.ide.ui.ide.data.AgentSession
import dev.forge.ide.ui.ide.data.AgentStreamEvent
import dev.forge.ide.ui.ide.model.AgentActivity
import dev.forge.ide.ui.ide.model.AgentActivityStatus
import dev.forge.ide.ui.ide.model.ChatMessage
import dev.forge.ide.ui.ide.model.ChatRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID

data class AgentUiState(
    val messages: List<ChatMessage> = emptyList(),
    val input: String = "",
    val activity: AgentActivity = AgentActivity(AgentActivityStatus.IDLE, "Idle"),
    val running: Boolean = false,
    val currentAgent: String = "Main",
)

/**
 * Drives the agent panel. It talks to [AgentSession] only, so swapping the mock
 * for the real Agent Core requires no UI change.
 */
class AgentViewModel(private val session: AgentSession) : ViewModel() {

    var uiState by mutableStateOf(
        AgentUiState(
            messages = listOf(
                ChatMessage(
                    id = UUID.randomUUID().toString(),
                    role = ChatRole.SYSTEM,
                    text = "Agent panel talks to the Agent Core. Configure a model provider to run a real turn.",
                ),
            ),
        ),
    )
        private set

    private var job: Job? = null

    fun onInputChange(text: String) {
        uiState = uiState.copy(input = text)
    }

    fun send() {
        val prompt = uiState.input.trim()
        if (prompt.isEmpty() || uiState.running) return

        val agentMessageId = UUID.randomUUID().toString()
        uiState = uiState.copy(
            input = "",
            running = true,
            currentAgent = "Main",
            activity = AgentActivity(AgentActivityStatus.THINKING, "Thinking"),
            messages = uiState.messages +
                ChatMessage(UUID.randomUUID().toString(), ChatRole.USER, prompt) +
                ChatMessage(agentMessageId, ChatRole.AGENT, "", streaming = true),
        )

        job = viewModelScope.launch {
            try {
                session.run(prompt) { event -> handleEvent(agentMessageId, event) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                appendSystem("Agent error: ${error.message ?: "unknown error"}")
                setActivity(AgentActivityStatus.ERROR, "Error")
            } finally {
                finalizeMessage(agentMessageId)
                uiState = uiState.copy(running = false)
            }
        }
    }

    fun stop() {
        if (!uiState.running) return
        job?.cancel()
        job = null
        appendSystem("Generation stopped by user.")
        setActivity(AgentActivityStatus.IDLE, "Stopped")
    }

    private fun handleEvent(agentMessageId: String, event: AgentStreamEvent) {
        when (event) {
            is AgentStreamEvent.Activity -> setActivity(event.activity.status, event.activity.label)
            is AgentStreamEvent.Chunk -> appendToMessage(agentMessageId, event.text)
            is AgentStreamEvent.Completed -> {
                setMessageText(agentMessageId, event.text)
                setActivity(AgentActivityStatus.COMPLETED, "Completed")
            }

            is AgentStreamEvent.Failed -> {
                appendToMessage(agentMessageId, event.message)
                setActivity(AgentActivityStatus.ERROR, "Error")
            }

            is AgentStreamEvent.AgentChanged -> {
                uiState = uiState.copy(currentAgent = event.label)
                setActivity(AgentActivityStatus.WAITING, event.label)
            }
        }
    }

    private fun setActivity(status: AgentActivityStatus, label: String) {
        uiState = uiState.copy(activity = AgentActivity(status, label))
    }

    private fun appendToMessage(id: String, text: String) {
        uiState = uiState.copy(
            messages = uiState.messages.map { message ->
                if (message.id == id) message.copy(text = message.text + text) else message
            },
        )
    }

    private fun setMessageText(id: String, text: String) {
        uiState = uiState.copy(
            messages = uiState.messages.map { message ->
                if (message.id == id) message.copy(text = text) else message
            },
        )
    }

    private fun finalizeMessage(id: String) {
        uiState = uiState.copy(
            messages = uiState.messages.map { message ->
                if (message.id == id) message.copy(streaming = false) else message
            },
        )
    }

    private fun appendSystem(text: String) {
        uiState = uiState.copy(
            messages = uiState.messages + ChatMessage(UUID.randomUUID().toString(), ChatRole.SYSTEM, text),
        )
    }
}
