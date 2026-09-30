package com.agentx.app.ui.ide.data

import com.agentx.app.ui.ide.model.AgentActivity
import com.agentx.app.ui.ide.model.GitSnapshot

/**
 * Presentation-facing ports for the IDE shell.
 *
 * Workspace access is no longer mocked: the UI talks to the
 * `com.agentx.app.workspace.WorkspaceManager` domain contract, which is backed by
 * the Android Storage Access Framework. The remaining ports (agent, git)
 * are still stand-ins for layers that land in later tasks. The terminal talks to the embedded
 * Termux runtime (`com.agentx.app.termux.TermuxRuntime`), which owns its own pty sessions.
 */

/**
 * Launches the platform's folder picker.
 *
 * The UI only ever sees an opaque handle (or `null` when the user cancels); it
 * never touches Android storage APIs directly.
 */
fun interface WorkspacePicker {
    fun pick(onPicked: (String?) -> Unit)
}

/** Incremental events emitted while an agent produces a response. */
sealed interface AgentStreamEvent {
    data class Activity(val activity: AgentActivity) : AgentStreamEvent

    data class Chunk(val text: String) : AgentStreamEvent

    data class Completed(val text: String) : AgentStreamEvent

    data class Failed(
        val message: String,
        val kind: AgentFailureKind = AgentFailureKind.UNKNOWN,
    ) : AgentStreamEvent

    data class AgentChanged(val role: String, val label: String) : AgentStreamEvent

    /** The model asked for a tool; emitted before it runs. */
    data class ToolRequested(val toolName: String, val detail: String) : AgentStreamEvent

    data class ToolRunning(val toolName: String) : AgentStreamEvent

    data class ToolFinished(val toolName: String, val success: Boolean, val summary: String) : AgentStreamEvent

    /**
     * A tool call is parked until the user decides. Carries everything the
     * approval prompt needs: which tool, what it would do, and the permission
     * it requires.
     */
    data class PermissionRequired(
        val toolName: String,
        val reason: String,
        val sessionId: String = "",
        val toolCallId: String = "",
        val detail: String = "",
        val requiredPermission: String = "",
    ) : AgentStreamEvent
}

/**
 * Runs a single agent turn. The mock implementation returns canned output;
 * the real Agent Core will implement this without any UI changes.
 */
/** Coarse failure category the chat UI can render without crashing. */
enum class AgentFailureKind {
    NONE,
    CONNECTION,
    TIMEOUT,
    INVALID_RESPONSE,
    CANCELLED,
    NOT_CONFIGURED,
    UNKNOWN,
}

/**
 * Session metadata for the Agent session sidebar. Free of agent-core types so
 * the UI never depends on the persistence model.
 */
data class AgentSessionInfo(
    val id: String,
    val title: String,
    val updatedAtMillis: Long,
    val messageCount: Int = 0,
    val active: Boolean = false,
)

/** Kind of a persisted transcript entry, mapped for presentation. */
enum class PersistedMessageKind { USER, ASSISTANT, TOOL, ERROR, SYSTEM, SUB_AGENT }

/**
 * One persisted conversation entry. This is the only shape in which the Agent
 * Core's stored history crosses into the UI; the persistence model itself never
 * leaks into a Composable.
 */
data class PersistedAgentMessage(
    val id: String,
    val kind: PersistedMessageKind,
    val text: String,
    val toolName: String? = null,
    val toolArguments: String? = null,
    val toolResult: String? = null,
    val toolSuccess: Boolean? = null,
    val timestampMillis: Long = 0L,
    val errorCode: String? = null,
    val subAgentRole: String? = null,
    val modelId: String? = null,
)

interface AgentSession {
    suspend fun run(
        input: String,
        onEvent: (AgentStreamEvent) -> Unit,
        workspaceId: String? = null,
        selectedFile: String? = null,
    )

    /**
     * Answers a pending permission request and lets the parked run continue.
     * Sessions that never pause for permission keep the default no-op.
     */
    suspend fun resolvePermission(
        sessionId: String,
        approved: Boolean,
        onEvent: (AgentStreamEvent) -> Unit,
    ) = Unit

    /**
     * Runs one turn inside a persistent session. Sessions that keep no history
     * fall back to [run]; the default keeps scripted and mock sessions working.
     */
    suspend fun runInSession(
        sessionId: String,
        input: String,
        onEvent: (AgentStreamEvent) -> Unit,
        workspaceId: String? = null,
        selectedFile: String? = null,
    ) {
        run(input, onEvent, workspaceId, selectedFile)
    }

    /**
     * Persistent, newest-first session list for the current workspace. Defaults
     * to empty so a session without history simply shows no sidebar entries.
     */
    suspend fun listSessions(): List<AgentSessionInfo> = emptyList()

    /** The session the next turn belongs to, or null when none is selected yet. */
    suspend fun activeSessionId(): String? = null

    /** Creates and selects an empty session, returning its metadata. */
    suspend fun createSession(): AgentSessionInfo? = null

    /** Loads a session's persisted transcript so it can be restored. */
    suspend fun restoreSession(sessionId: String): List<PersistedAgentMessage> = emptyList()

    /** Renames a persisted session. Returns whether the title changed. */
    suspend fun renameSession(sessionId: String, title: String): Boolean = false

    /** Deletes a persisted session and its transcript. */
    suspend fun deleteSession(sessionId: String): Boolean = false
}

/** Reads repository state for a workspace. No real Git access yet. */
interface GitRepository {
    suspend fun snapshot(workspaceId: String): GitSnapshot
}
