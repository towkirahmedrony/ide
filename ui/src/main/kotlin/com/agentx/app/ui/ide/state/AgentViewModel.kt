package com.agentx.app.ui.ide.state

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentx.app.ui.ide.data.AgentFailureKind
import com.agentx.app.ui.ide.data.AgentSession
import com.agentx.app.ui.ide.data.AgentSessionInfo
import com.agentx.app.ui.ide.data.AgentStreamEvent
import com.agentx.app.ui.ide.model.ActivityItemStatus
import com.agentx.app.ui.ide.model.AgentActivity
import com.agentx.app.ui.ide.model.AgentActivityStatus
import com.agentx.app.ui.ide.model.AgentActivityUiModel
import com.agentx.app.ui.ide.model.AgentChatUiState
import com.agentx.app.ui.ide.model.AgentSessionUiModel
import com.agentx.app.ui.ide.model.ChatMessageKind
import com.agentx.app.ui.ide.model.ChatMessageUiModel
import com.agentx.app.ui.ide.model.GenerationPhase
import com.agentx.app.ui.ide.model.GenerationState
import com.agentx.app.ui.ide.model.MessageState
import com.agentx.app.ui.ide.model.PermissionPromptUi
import com.agentx.app.ui.ide.model.ToolActivityUiModel
import com.agentx.app.ui.ide.model.ToolRunStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Drives the Agent chat. It talks to [AgentSession] only, so the session
 * history, the Agent Event stream and the Tool System all stay behind the port.
 *
 * Responsibilities kept out of the Composables: markdown parsing, the persistent
 * session list, the live generation timer, and the streaming/stop/retry state
 * machine. The elapsed timer is fed by a single ticker coroutine bound to
 * [viewModelScope], so it can never leak or double up.
 */
class AgentViewModel(
    private val session: AgentSession,
    private val workspaceId: String? = null,
    /** Live editor selection; read at send-time so the agent sees the open file. */
    private val selectedFile: () -> String? = { null },
    /** Clock used for timestamps and the elapsed timer; injectable for tests. */
    private val now: () -> Long = { System.currentTimeMillis() },
    /** Dispatcher for the file-backed session store; injectable for tests. */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val tickMillis: Long = 1_000L,
) : ViewModel() {

    var uiState by mutableStateOf(AgentChatUiState())
        private set

    private var job: Job? = null
    private var ticker: Job? = null
    private var stoppedByUser = false

    init {
        viewModelScope.launch {
            val sessions = withContext(ioDispatcher) { session.listSessions() }
            val activeId = withContext(ioDispatcher) { session.activeSessionId() }
                ?: sessions.firstOrNull()?.id
            val restored = activeId
                ?.let { id -> withContext(ioDispatcher) { session.restoreSession(id) } }
                .orEmpty()
            uiState = uiState.copy(
                sessions = sessions.map { info -> info.toUiModel(activeId) },
                activeSessionId = activeId,
                messages = AgentChatPresentation.persistedTranscriptToUi(restored),
            )
        }
    }

    // ───────────────────────────── Composer ─────────────────────────────

    fun onInputChange(text: String) {
        uiState = uiState.copy(input = text)
    }

    fun send() {
        val prompt = uiState.input.trim()
        if (prompt.isEmpty() || uiState.running) return
        uiState = uiState.copy(input = "")
        startTurn(prompt, base = uiState.messages, addUserMessage = true)
    }

    /**
     * Re-runs the turn that produced [assistantMessageId], replacing the old
     * assistant entry. The preceding user message is reused, so no duplicate
     * user message is created and the conversation is not corrupted.
     */
    fun regenerate(assistantMessageId: String) {
        if (uiState.running) return
        val index = uiState.messages.indexOfFirst { it.id == assistantMessageId }
        if (index < 0) return
        val prompt = uiState.messages.take(index).lastOrNull { it.kind == ChatMessageKind.USER }?.rawText
            ?: return
        startTurn(prompt, base = uiState.messages.take(index), addUserMessage = false)
    }

    /** A failed response is retried exactly like a regenerate. */
    fun retry(messageId: String) = regenerate(messageId)

    /** Edits a user message and re-runs from that point, dropping later entries. */
    fun editAndResend(userMessageId: String, newText: String) {
        if (uiState.running) return
        val text = newText.trim()
        if (text.isEmpty()) return
        val index = uiState.messages.indexOfFirst { it.id == userMessageId && it.kind == ChatMessageKind.USER }
        if (index < 0) return
        startTurn(text, base = uiState.messages.take(index), addUserMessage = true)
    }

    fun stop() {
        if (!uiState.running) return
        stoppedByUser = true
        job?.cancel()
        job = null
        val elapsed = elapsedSinceStart()
        uiState = uiState.copy(
            generation = GenerationState(GenerationPhase.STOPPED, uiState.generation.startedAtMillis, elapsed),
            activity = AgentActivity(AgentActivityStatus.IDLE, "Stopped"),
            pendingPermission = null,
        )
        stopTicker()
    }

    /** Answers a parked tool call and continues the paused turn. */
    fun respondToPermission(approved: Boolean) {
        val prompt = uiState.pendingPermission ?: return
        // A parked turn keeps `running` true for its live timer; answering it is
        // exactly the case where a new continuation is allowed.
        if (uiState.running && uiState.generation.phase != GenerationPhase.WAITING_PERMISSION) return
        job = null

        val startedAt = now()
        stoppedByUser = false
        val assistantId = UUID.randomUUID().toString()
        val notice = ChatMessageUiModel(
            id = UUID.randomUUID().toString(),
            kind = ChatMessageKind.SYSTEM,
            rawText = if (approved) "Approved ${prompt.toolName}" else "Denied ${prompt.toolName}",
            timestampMillis = startedAt,
        )
        uiState = uiState.copy(
            messages = uiState.messages + notice + streamingPlaceholder(assistantId, startedAt),
            pendingPermission = null,
            generation = GenerationState(GenerationPhase.THINKING, startedAt, 0L),
            activity = AgentActivity(
                AgentActivityStatus.SENDING,
                if (approved) "Permission granted" else "Permission denied",
            ),
        )
        startTicker()

        job = viewModelScope.launch {
            try {
                session.resolvePermission(prompt.sessionId, approved) { event ->
                    handleEvent(assistantId, event)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.e(TAG, "Agent permission resume failed", error)
                handleEvent(assistantId, AgentStreamEvent.Failed(userFacingError(error), AgentFailureKind.UNKNOWN))
            } finally {
                completeGeneration(assistantId)
                refreshSessions()
            }
        }
    }

    // ───────────────────────────── Sessions ─────────────────────────────

    fun newSession() {
        if (uiState.running) return
        viewModelScope.launch {
            val created = withContext(ioDispatcher) { session.createSession() }
            uiState = uiState.copy(
                activeSessionId = created?.id,
                messages = emptyList(),
                pendingPermission = null,
                generation = GenerationState(),
                activity = AgentActivity(AgentActivityStatus.IDLE, "Idle"),
            )
            refreshSessions()
        }
    }

    fun openSession(sessionId: String) {
        if (uiState.running || sessionId == uiState.activeSessionId) return
        viewModelScope.launch {
            val restored = withContext(ioDispatcher) { session.restoreSession(sessionId) }
            uiState = uiState.copy(
                activeSessionId = sessionId,
                messages = AgentChatPresentation.persistedTranscriptToUi(restored),
                pendingPermission = null,
                generation = GenerationState(),
                activity = AgentActivity(AgentActivityStatus.IDLE, "Idle"),
            )
            refreshSessions()
        }
    }

    fun renameSession(sessionId: String, title: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            withContext(ioDispatcher) { session.renameSession(sessionId, trimmed) }
            refreshSessions()
        }
    }

    fun deleteSession(sessionId: String) {
        if (uiState.running) return
        viewModelScope.launch {
            val removed = withContext(ioDispatcher) { session.deleteSession(sessionId) }
            if (removed && sessionId == uiState.activeSessionId) {
                val created = withContext(ioDispatcher) { session.createSession() }
                uiState = uiState.copy(
                    activeSessionId = created?.id,
                    messages = emptyList(),
                    pendingPermission = null,
                    generation = GenerationState(),
                    activity = AgentActivity(AgentActivityStatus.IDLE, "Idle"),
                )
            }
            refreshSessions()
        }
    }

    // ───────────────────────────── Generation timer ─────────────────────────────

    /** Recomputes the elapsed time from the real generation start timestamp. */
    fun refreshElapsed() {
        val started = uiState.generation.startedAtMillis ?: return
        uiState = uiState.copy(
            generation = uiState.generation.copy(
                elapsedMillis = (now() - started).coerceAtLeast(0L),
            ),
        )
    }

    private fun startTicker() {
        stopTicker()
        ticker = viewModelScope.launch {
            while (true) {
                delay(tickMillis)
                refreshElapsed()
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    private fun elapsedSinceStart(): Long {
        val started = uiState.generation.startedAtMillis ?: return uiState.generation.elapsedMillis
        return (now() - started).coerceAtLeast(0L)
    }

    // ───────────────────────────── Turn lifecycle ─────────────────────────────

    private fun startTurn(prompt: String, base: List<ChatMessageUiModel>, addUserMessage: Boolean) {
        if (uiState.running) return
        val startedAt = now()
        stoppedByUser = false
        val assistantId = UUID.randomUUID().toString()

        val user = ChatMessageUiModel(
            id = UUID.randomUUID().toString(),
            kind = ChatMessageKind.USER,
            rawText = prompt,
            blocks = AgentChatPresentation.userBlocks(prompt),
            timestampMillis = startedAt,
        )
        val messages = if (addUserMessage) {
            base + user + streamingPlaceholder(assistantId, startedAt)
        } else {
            base + streamingPlaceholder(assistantId, startedAt)
        }
        uiState = uiState.copy(
            messages = messages,
            generation = GenerationState(GenerationPhase.THINKING, startedAt, 0L),
            activity = AgentActivity(AgentActivityStatus.THINKING, "Thinking"),
            pendingPermission = null,
        )
        startTicker()

        job = viewModelScope.launch {
            val sessionId = ensureActiveSession()
            try {
                if (sessionId != null) {
                    session.runInSession(
                        sessionId = sessionId,
                        input = prompt,
                        onEvent = { event -> handleEvent(assistantId, event) },
                        workspaceId = workspaceId,
                        selectedFile = selectedFile(),
                    )
                } else {
                    session.run(
                        input = prompt,
                        onEvent = { event -> handleEvent(assistantId, event) },
                        workspaceId = workspaceId,
                        selectedFile = selectedFile(),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.e(TAG, "Agent turn failed", error)
                handleEvent(assistantId, AgentStreamEvent.Failed(userFacingError(error), AgentFailureKind.UNKNOWN))
            } finally {
                completeGeneration(assistantId)
                refreshSessions()
            }
        }
    }

    private fun streamingPlaceholder(id: String, startedAt: Long) = ChatMessageUiModel(
        id = id,
        kind = ChatMessageKind.ASSISTANT,
        rawText = "",
        timestampMillis = startedAt,
        state = MessageState.STREAMING,
    )

    private suspend fun ensureActiveSession(): String? {
        uiState.activeSessionId?.let { return it }
        val created = withContext(ioDispatcher) { session.createSession() } ?: return null
        uiState = uiState.copy(
            activeSessionId = created.id,
            sessions = listOf(created.toUiModel(created.id)) +
                uiState.sessions.filterNot { it.id == created.id },
        )
        return created.id
    }

    private fun handleEvent(assistantId: String, event: AgentStreamEvent) {
        when (event) {
            is AgentStreamEvent.Activity -> {
                setActivity(event.activity.status, event.activity.label)
                appendGenericActivity(assistantId, event.activity.label)
            }

            is AgentStreamEvent.Chunk -> {
                appendText(assistantId, event.text)
                uiState = uiState.copy(generation = uiState.generation.copy(phase = GenerationPhase.THINKING))
                setActivity(AgentActivityStatus.THINKING, "AI responding")
            }

            is AgentStreamEvent.Completed -> {
                // Only replace the streamed text when the final summary carries
                // content; a blank summary must not wipe what was already shown.
                if (event.text.isNotBlank()) setAssistantText(assistantId, event.text)
                finishGeneration(GenerationPhase.COMPLETED, AgentActivityStatus.COMPLETED, "Completed")
            }

            is AgentStreamEvent.Failed -> {
                uiState = uiState.copy(pendingPermission = null)
                if (event.kind == AgentFailureKind.CANCELLED && stoppedByUser) {
                    finishGeneration(GenerationPhase.STOPPED, AgentActivityStatus.IDLE, "Stopped")
                } else {
                    if (event.message.isNotBlank()) {
                        setAssistantError(assistantId, event.message, event.kind)
                    }
                    val phase = if (event.kind == AgentFailureKind.CANCELLED) {
                        GenerationPhase.STOPPED
                    } else {
                        GenerationPhase.FAILED
                    }
                    finishGeneration(phase, statusFor(event.kind), labelFor(event.kind))
                }
            }

            is AgentStreamEvent.AgentChanged -> {
                uiState = uiState.copy(currentAgent = event.label)
                setActivity(AgentActivityStatus.WAITING, event.label)
            }

            is AgentStreamEvent.ToolRequested -> {
                val toolId = UUID.randomUUID().toString()
                appendToolMessage(toolId, event.toolName, event.detail)
                appendActivity(
                    assistantId = assistantId,
                    id = toolId,
                    label = AgentChatPresentation.activityLabel(event.toolName),
                    status = ActivityItemStatus.ACTIVE,
                    toolName = event.toolName,
                    at = now(),
                )
                uiState = uiState.copy(generation = uiState.generation.copy(phase = GenerationPhase.TOOL))
                setActivity(AgentActivityStatus.USING_TOOL, "Using tool · ${event.toolName}")
            }

            is AgentStreamEvent.ToolRunning -> {
                markToolRunning(event.toolName)
                uiState = uiState.copy(generation = uiState.generation.copy(phase = GenerationPhase.TOOL))
                setActivity(AgentActivityStatus.USING_TOOL, "Using tool · ${event.toolName}")
            }

            is AgentStreamEvent.ToolFinished -> {
                finishToolMessage(event.toolName, event.success, event.summary)
                finishActivityItem(assistantId, event.toolName, event.success)
                if (event.success) {
                    setActivity(AgentActivityStatus.TOOL_SUCCESS, "Tool ok · ${event.toolName}")
                } else {
                    setActivity(AgentActivityStatus.TOOL_FAILURE, "Tool failed · ${event.toolName}")
                }
            }

            is AgentStreamEvent.PermissionRequired -> {
                uiState = uiState.copy(
                    pendingPermission = PermissionPromptUi(
                        sessionId = event.sessionId,
                        toolCallId = event.toolCallId,
                        toolName = event.toolName,
                        detail = event.detail,
                        requiredPermission = event.requiredPermission,
                        reason = event.reason,
                    ),
                    generation = uiState.generation.copy(phase = GenerationPhase.WAITING_PERMISSION),
                )
                setActivity(
                    AgentActivityStatus.PERMISSION_REQUIRED,
                    "Permission required · ${event.toolName}",
                )
            }
        }
    }

    private fun setActivity(status: AgentActivityStatus, label: String) {
        uiState = uiState.copy(activity = AgentActivity(status, label))
    }

    private fun appendText(id: String, text: String) {
        if (text.isEmpty()) return
        uiState = uiState.copy(
            messages = uiState.messages.map { message ->
                if (message.id != id) {
                    message
                } else {
                    val updated = message.rawText + text
                    message.copy(rawText = updated, blocks = AgentChatPresentation.parseBlocks(updated))
                }
            },
        )
        refreshElapsed()
    }

    private fun setAssistantText(id: String, text: String) {
        uiState = uiState.copy(
            messages = uiState.messages.map { message ->
                if (message.id != id) message
                else message.copy(rawText = text, blocks = AgentChatPresentation.parseBlocks(text))
            },
        )
    }

    private fun setAssistantError(id: String, message: String, kind: AgentFailureKind) {
        uiState = uiState.copy(
            messages = uiState.messages.map { entry ->
                if (entry.id != id) {
                    entry
                } else {
                    entry.copy(
                        kind = ChatMessageKind.ERROR,
                        rawText = message,
                        blocks = AgentChatPresentation.parseBlocks(message),
                        error = AgentChatPresentation.errorFrom(kind, message),
                        state = MessageState.FAILED,
                    )
                }
            },
        )
    }

    /** Adds a tool card as soon as the model requests the tool. */
    private fun appendToolMessage(toolId: String, toolName: String, detail: String) {
        val tool = ToolActivityUiModel(
            id = toolId,
            toolName = toolName,
            displayName = AgentChatPresentation.toolDisplayName(toolName),
            detail = AgentChatPresentation.sanitizeToolDetail(detail),
            status = ToolRunStatus.RUNNING,
            startedAtMillis = now(),
        )
        uiState = uiState.copy(
            messages = uiState.messages + ChatMessageUiModel(
                id = toolId,
                kind = ChatMessageKind.TOOL,
                tool = tool,
                timestampMillis = tool.startedAtMillis,
                state = MessageState.STREAMING,
            ),
        )
    }

    private fun markToolRunning(toolName: String) {
        val index = runningToolIndex(toolName)
        if (index < 0) return
        val updated = uiState.messages.toMutableList()
        val message = updated[index]
        val tool = message.tool ?: return
        updated[index] = message.copy(
            tool = tool.copy(status = ToolRunStatus.RUNNING, startedAtMillis = now()),
        )
        uiState = uiState.copy(messages = updated)
    }

    private fun finishToolMessage(toolName: String, success: Boolean, summary: String) {
        val index = runningToolIndex(toolName)
        if (index < 0) return
        val updated = uiState.messages.toMutableList()
        val message = updated[index]
        val tool = message.tool ?: return
        val startedAt = tool.startedAtMillis.takeIf { it > 0L } ?: now()
        updated[index] = message.copy(
            tool = tool.copy(
                status = if (success) ToolRunStatus.COMPLETED else ToolRunStatus.FAILED,
                summary = AgentChatPresentation.summarizeToolResult(summary.ifBlank { null }),
                elapsedMillis = (now() - startedAt).coerceAtLeast(0L),
            ),
            state = if (success) MessageState.COMPLETE else MessageState.FAILED,
        )
        uiState = uiState.copy(messages = updated)
    }

    private fun runningToolIndex(toolName: String): Int = uiState.messages.indexOfLast { message ->
        message.kind == ChatMessageKind.TOOL &&
            message.tool?.toolName == toolName &&
            message.state == MessageState.STREAMING
    }

    private fun appendActivity(
        assistantId: String,
        id: String,
        label: String,
        status: ActivityItemStatus,
        toolName: String?,
        at: Long,
    ) {
        uiState = uiState.copy(
            messages = uiState.messages.map { message ->
                if (message.id != assistantId) {
                    message
                } else {
                    message.copy(
                        activities = message.activities + AgentActivityUiModel(
                            id = id,
                            label = label,
                            status = status,
                            toolName = toolName,
                            timestampMillis = at,
                        ),
                    )
                }
            },
        )
    }

    private fun appendGenericActivity(assistantId: String, label: String) {
        val clean = label.trim()
        if (clean.isEmpty() || clean.lowercase() in GENERIC_ACTIVITY_LABELS) return
        uiState = uiState.copy(
            messages = uiState.messages.map { message ->
                if (message.id != assistantId) {
                    message
                } else if (message.activities.lastOrNull()?.label == clean) {
                    message
                } else {
                    // Settle only previous generic steps; in-flight tool rows keep their own state.
                    val settled = message.activities.map { activity ->
                        if (activity.toolName == null && activity.status == ActivityItemStatus.ACTIVE) {
                            activity.copy(status = ActivityItemStatus.DONE)
                        } else {
                            activity
                        }
                    }
                    message.copy(
                        activities = settled + AgentActivityUiModel(
                            id = UUID.randomUUID().toString(),
                            label = clean,
                            status = ActivityItemStatus.ACTIVE,
                            timestampMillis = now(),
                        ),
                    )
                }
            },
        )
    }

    private fun finishActivityItem(assistantId: String, toolName: String, success: Boolean) {
        uiState = uiState.copy(
            messages = uiState.messages.map { message ->
                if (message.id != assistantId) {
                    message
                } else {
                    val activities = message.activities.toMutableList()
                    val index = activities.indexOfLast {
                        it.toolName == toolName && it.status == ActivityItemStatus.ACTIVE
                    }
                    if (index < 0) {
                        message
                    } else {
                        val elapsed = (now() - activities[index].timestampMillis).coerceAtLeast(0L)
                        activities[index] = activities[index].copy(
                            status = if (success) ActivityItemStatus.DONE else ActivityItemStatus.FAILED,
                            elapsedMillis = elapsed,
                        )
                        message.copy(activities = activities)
                    }
                }
            },
        )
    }

    private fun finishGeneration(phase: GenerationPhase, status: AgentActivityStatus, label: String) {
        uiState = uiState.copy(
            generation = GenerationState(phase, uiState.generation.startedAtMillis, elapsedSinceStart()),
            activity = AgentActivity(status, label),
        )
        stopTicker()
    }

    private fun completeGeneration(assistantId: String) {
        if (uiState.generation.phase == GenerationPhase.WAITING_PERMISSION && uiState.pendingPermission != null) {
            // The turn is parked, not finished: keep the generation live so the
            // elapsed timer keeps counting and Stop stays available. Any blank
            // placeholder is retired so it cannot keep showing a typing indicator.
            uiState = uiState.copy(
                messages = uiState.messages.mapNotNull { message ->
                    when {
                        message.id != assistantId -> message
                        message.rawText.isBlank() && message.error == null && message.activities.isEmpty() -> null
                        else -> message.copy(state = MessageState.COMPLETE)
                    }
                },
            )
            return
        }
        val current = uiState.messages.lastOrNull { it.id == assistantId }
        val failed = current?.kind == ChatMessageKind.ERROR || current?.state == MessageState.FAILED
        when {
            stoppedByUser -> {
                finalizeAssistant(assistantId, MessageState.STOPPED)
                if (uiState.generation.running) {
                    finishGeneration(GenerationPhase.STOPPED, AgentActivityStatus.IDLE, "Stopped")
                }
            }

            failed -> {
                finalizeAssistant(assistantId, MessageState.FAILED)
                if (uiState.generation.running) {
                    finishGeneration(GenerationPhase.FAILED, AgentActivityStatus.ERROR, "Error")
                }
            }

            else -> {
                finalizeAssistant(assistantId, MessageState.COMPLETE)
                if (uiState.generation.running) {
                    finishGeneration(GenerationPhase.COMPLETED, AgentActivityStatus.COMPLETED, "Completed")
                }
            }
        }
        stopTicker()
    }

    /**
     * Marks a finished assistant entry and stamps it with the final elapsed time.
     * An assistant placeholder that produced nothing is dropped, exactly as
     * before, so a failed or empty turn never leaves a blank bubble.
     */
    private fun finalizeAssistant(id: String, state: MessageState) {
        val elapsed = uiState.generation.elapsedMillis
        uiState = uiState.copy(
            messages = uiState.messages.mapNotNull { message ->
                when {
                    message.id != id -> message
                    message.rawText.isBlank() && message.error == null -> null
                    else -> message.copy(state = state, elapsedMillis = elapsed)
                }
            },
        )
    }

    private suspend fun refreshSessions() {
        val list = withContext(ioDispatcher) { session.listSessions() }
        val activeId = uiState.activeSessionId
        uiState = uiState.copy(sessions = list.map { info -> info.toUiModel(activeId) })
    }

    private companion object {
        const val TAG = "ForgeAgent"

        val GENERIC_ACTIVITY_LABELS = setOf(
            "idle", "sending", "thinking", "ai responding", "waiting", "completed", "stopped", "error",
        )

        fun statusFor(kind: AgentFailureKind): AgentActivityStatus = when (kind) {
            AgentFailureKind.CONNECTION -> AgentActivityStatus.CONNECTION_ERROR
            AgentFailureKind.TIMEOUT -> AgentActivityStatus.TIMEOUT
            AgentFailureKind.INVALID_RESPONSE -> AgentActivityStatus.INVALID_RESPONSE
            AgentFailureKind.NONE -> AgentActivityStatus.IDLE
            AgentFailureKind.CANCELLED -> AgentActivityStatus.IDLE
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

private fun AgentSessionInfo.toUiModel(activeId: String?): AgentSessionUiModel = AgentSessionUiModel(
    id = id,
    title = title,
    updatedAtMillis = updatedAtMillis,
    messageCount = messageCount,
    active = id == activeId,
)
