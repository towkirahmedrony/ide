package com.agentx.app.ui.ide.state

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentx.app.ui.ide.data.AgentFailureKind
import com.agentx.app.ui.ide.data.AgentSession
import com.agentx.app.ui.ide.data.AgentStreamEvent
import com.agentx.app.ui.ide.model.AgentActivity
import com.agentx.app.ui.ide.model.AgentActivityStatus
import com.agentx.app.ui.ide.model.ChatMessage
import com.agentx.app.ui.ide.model.ChatRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * A tool call parked until the user approves or denies it. Everything here is
 * plain presentation data: the UI never sees model or tool types directly.
 */
data class PermissionPrompt(
    val sessionId: String,
    val toolCallId: String,
    val toolName: String,
    val detail: String,
    val requiredPermission: String,
    val reason: String,
)

data class AgentUiState(
    val messages: List<ChatMessage> = emptyList(),
    val input: String = "",
    val activity: AgentActivity = AgentActivity(AgentActivityStatus.IDLE, "Idle"),
    val running: Boolean = false,
    val currentAgent: String = "Main",
    val pendingPermission: PermissionPrompt? = null,
)

/**
 * Drives the agent panel. It talks to [AgentSession] only, so swapping the mock
 * for the real Agent Core requires no UI change.
 *
 * The ViewModel stays on the lifecycle scope (main). Network I/O is the HTTP
 * transport's job. Failures never drop the user's message; they become a
 * visible error state instead of crashing.
 */
class AgentViewModel(
    private val session: AgentSession,
    private val workspaceId: String? = null,
    /** Live editor selection; read at send-time so the agent sees the open file. */
    private val selectedFile: () -> String? = { null },
) : ViewModel() {

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
            pendingPermission = null,
            activity = AgentActivity(AgentActivityStatus.SENDING, "Sending"),
            messages = uiState.messages +
                ChatMessage(UUID.randomUUID().toString(), ChatRole.USER, prompt) +
                ChatMessage(agentMessageId, ChatRole.AGENT, "", streaming = true),
        )

        job = viewModelScope.launch {
            try {
                session.run(
                    input = prompt,
                    onEvent = { event -> handleEvent(agentMessageId, event) },
                    workspaceId = workspaceId,
                    selectedFile = selectedFile(),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.e(TAG, "Agent turn failed", error)
                handleEvent(
                    agentMessageId,
                    AgentStreamEvent.Failed(userFacingError(error), AgentFailureKind.UNKNOWN),
                )
            } finally {
                finalizeMessage(agentMessageId)
                dropEmptyAgentPlaceholder(agentMessageId)
                uiState = uiState.copy(running = false)
            }
        }
    }

    /** Answers a parked tool call and continues the paused turn. */
    fun respondToPermission(approved: Boolean) {
        val prompt = uiState.pendingPermission ?: return
        if (uiState.running) return

        val agentMessageId = UUID.randomUUID().toString()
        uiState = uiState.copy(
            running = true,
            pendingPermission = null,
            activity = AgentActivity(
                AgentActivityStatus.SENDING,
                if (approved) "Permission granted" else "Permission denied",
            ),
            messages = uiState.messages +
                ChatMessage(
                    UUID.randomUUID().toString(),
                    ChatRole.SYSTEM,
                    if (approved) "Approved ${prompt.toolName}" else "Denied ${prompt.toolName}",
                ) +
                ChatMessage(agentMessageId, ChatRole.AGENT, "", streaming = true),
        )

        job = viewModelScope.launch {
            try {
                session.resolvePermission(
                    sessionId = prompt.sessionId,
                    approved = approved,
                    onEvent = { event -> handleEvent(agentMessageId, event) },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.e(TAG, "Agent permission resume failed", error)
                handleEvent(
                    agentMessageId,
                    AgentStreamEvent.Failed(userFacingError(error), AgentFailureKind.UNKNOWN),
                )
            } finally {
                finalizeMessage(agentMessageId)
                dropEmptyAgentPlaceholder(agentMessageId)
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
        uiState = uiState.copy(running = false, pendingPermission = null)
    }

    private fun handleEvent(agentMessageId: String, event: AgentStreamEvent) {
        when (event) {
            is AgentStreamEvent.Activity -> setActivity(event.activity.status, event.activity.label)
            is AgentStreamEvent.Chunk -> {
                appendToMessage(agentMessageId, event.text)
                if (uiState.activity.status == AgentActivityStatus.SENDING) {
                    setActivity(AgentActivityStatus.THINKING, "AI responding")
                }
            }
            is AgentStreamEvent.Completed -> {
                setMessageText(agentMessageId, event.text)
                setActivity(AgentActivityStatus.COMPLETED, "Completed")
                uiState = uiState.copy(pendingPermission = null)
            }

            is AgentStreamEvent.Failed -> {
                if (event.message.isNotBlank()) {
                    setMessageText(agentMessageId, event.message)
                }
                setActivity(statusFor(event.kind), labelFor(event.kind))
                uiState = uiState.copy(pendingPermission = null)
            }

            is AgentStreamEvent.AgentChanged -> {
                uiState = uiState.copy(currentAgent = event.label)
                setActivity(AgentActivityStatus.WAITING, event.label)
            }

            is AgentStreamEvent.ToolRequested -> {
                appendToolMessage(event.toolName, event.detail)
                setActivity(AgentActivityStatus.USING_TOOL, "Using tool · ${event.toolName}")
            }

            is AgentStreamEvent.ToolRunning ->
                setActivity(AgentActivityStatus.USING_TOOL, "Using tool · ${event.toolName}")

            is AgentStreamEvent.ToolFinished -> {
                finishToolMessage(event.toolName, event.success, event.summary)
                val status = if (event.success) AgentActivityStatus.TOOL_SUCCESS else AgentActivityStatus.TOOL_FAILURE
                val prefix = if (event.success) "Tool ok" else "Tool failed"
                setActivity(status, "$prefix · ${event.toolName}")
            }

            is AgentStreamEvent.PermissionRequired -> {
                uiState = uiState.copy(
                    pendingPermission = PermissionPrompt(
                        sessionId = event.sessionId,
                        toolCallId = event.toolCallId,
                        toolName = event.toolName,
                        detail = event.detail,
                        requiredPermission = event.requiredPermission,
                        reason = event.reason,
                    ),
                )
                setActivity(AgentActivityStatus.PERMISSION_REQUIRED, "Permission required · ${event.toolName}")
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

    /** Adds a tool entry to the transcript as soon as the model requests it. */
    private fun appendToolMessage(toolName: String, detail: String) {
        val header = buildString {
            append("▶ ").append(toolName)
            if (detail.isNotBlank() && detail != "(no arguments)") append(" · ").append(detail)
        }
        uiState = uiState.copy(
            messages = uiState.messages + ChatMessage(
                id = UUID.randomUUID().toString(),
                role = ChatRole.TOOL,
                text = header,
                streaming = true,
                toolName = toolName,
            ),
        )
    }

    /** Attaches the outcome to the matching in-flight tool entry. */
    private fun finishToolMessage(toolName: String, success: Boolean, summary: String) {
        val index = uiState.messages.indexOfLast { message ->
            message.role == ChatRole.TOOL && message.toolName == toolName && message.streaming
        }
        if (index < 0) return
        val marker = if (success) "✔" else "✖"
        val updated = uiState.messages.toMutableList()
        val message = updated[index]
        updated[index] = message.copy(
            text = message.text + "\n" + "$marker " + summary.ifBlank { toolName },
            streaming = false,
        )
        uiState = uiState.copy(messages = updated)
    }

    private fun finalizeMessage(id: String) {
        uiState = uiState.copy(
            messages = uiState.messages.map { message ->
                if (message.id == id) message.copy(streaming = false) else message
            },
        )
    }

    private fun dropEmptyAgentPlaceholder(id: String) {
        uiState = uiState.copy(
            messages = uiState.messages.filterNot { message ->
                message.id == id && message.role == ChatRole.AGENT && message.text.isBlank()
            },
        )
    }

    private fun appendSystem(text: String) {
        if (text.isBlank()) return
        uiState = uiState.copy(
            messages = uiState.messages + ChatMessage(UUID.randomUUID().toString(), ChatRole.SYSTEM, text),
        )
    }

    private companion object {
        const val TAG = "ForgeAgent"

        fun statusFor(kind: AgentFailureKind): AgentActivityStatus = when (kind) {
            AgentFailureKind.CONNECTION -> AgentActivityStatus.CONNECTION_ERROR
            AgentFailureKind.TIMEOUT -> AgentActivityStatus.TIMEOUT
            AgentFailureKind.INVALID_RESPONSE -> AgentActivityStatus.INVALID_RESPONSE
            AgentFailureKind.NONE -> AgentActivityStatus.IDLE
            else -> AgentActivityStatus.ERROR
        }

        fun labelFor(kind: AgentFailureKind): String = when (kind) {
            AgentFailureKind.CONNECTION -> "Connection error"
            AgentFailureKind.TIMEOUT -> "Timeout"
            AgentFailureKind.INVALID_RESPONSE -> "Invalid response"
            AgentFailureKind.CANCELLED -> "Cancelled"
            AgentFailureKind.NOT_CONFIGURED -> "No model"
            AgentFailureKind.NONE -> "Idle"
            AgentFailureKind.UNKNOWN -> "Error"
        }

        fun userFacingError(error: Throwable): String =
            error.message?.takeIf { it.isNotBlank() } ?: error::class.simpleName ?: "unknown error"
    }
}
