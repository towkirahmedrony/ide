package com.agentx.app.ui.ide.data

import com.agentx.app.context.AgentAttachment
import com.agentx.app.context.AgentAttachmentKind
import com.agentx.app.ui.ide.model.AgentActivity

/**
 * Presentation-facing ports for the IDE shell.
 *
 * Workspace access is real: the UI talks to the
 * `com.agentx.app.workspace.WorkspaceManager` domain contract, which is backed by
 * the Android Storage Access Framework and the filesystem. Git talks directly to
 * `com.agentx.app.git.GitService`. The agent port is still a stand-in for a layer that lands in a
 * later task. The terminal talks to the embedded Termux runtime
 * (`com.agentx.app.termux.TermuxRuntime`), which owns its own pty sessions.
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
    data class Completed(
        val text: String,
        /** Files the runtime reports as changed this turn; empty when it made no edits. */
        val filesChanged: List<String> = emptyList(),
        /** Files the runtime inspected this turn; shown for context, never fabricated. */
        val filesInspected: List<String> = emptyList(),
    ) : AgentStreamEvent
    data class Failed(
        val message: String,
        val kind: AgentFailureKind = AgentFailureKind.UNKNOWN,
    ) : AgentStreamEvent
    data class AgentChanged(val role: String, val label: String) : AgentStreamEvent

    /**
     * The Agent Runtime published its plan for this task.
     *
     * [steps] is the runtime's own step list with the runtime's own statuses; the
     * UI never invents a step and never advances one on its own. A task the
     * runtime planned without steps produces no plan block at all.
     */
    data class Plan(val steps: List<PlanStep>) : AgentStreamEvent

    /** One plan step, exactly as the Agent Runtime reported it. */
    data class PlanStep(val index: Int, val title: String, val status: String)

    /** A sub-agent run started; [detail] is its safe objective. */
    data class SubAgentStarted(val role: String, val label: String, val detail: String) : AgentStreamEvent

    /** A sub-agent run finished; [success] reflects its real end status. */
    data class SubAgentFinished(
        val role: String,
        val label: String,
        val success: Boolean,
        val summary: String,
    ) : AgentStreamEvent

    /** The model asked for a tool; emitted before it runs. */
    data class ToolRequested(val toolName: String, val detail: String) : AgentStreamEvent

    data class ToolRunning(val toolName: String) : AgentStreamEvent

    data class ToolFinished(
        val toolName: String,
        val success: Boolean,
        val summary: String,
        /** Raw tool output for the expandable terminal/tool rows; redacted by the presentation layer. */
        val output: String = "",
    ) : AgentStreamEvent

    data class OutputDelta(val text: String) : AgentStreamEvent

    data class ToolProgress(val toolName: String, val detail: String) : AgentStreamEvent

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
    /**
     * Stops the run that is actually executing in this session.
     *
     * Deliberately not the same thing as cancelling the caller's coroutine: this
     * must reach the runtime operation itself, so the model request, stream, tool
     * call or delegated sub-agent in flight is stopped instead of being left to run
     * on in the background. It is not suspending because the user's Stop has to take
     * effect immediately, from a plain UI callback.
     *
     * Sessions that keep no runtime (scripted and mock sessions) keep this default
     * no-op.
     */
    fun cancel(sessionId: String) = Unit

    suspend fun run(
        input: String,
        onEvent: (AgentStreamEvent) -> Unit,
        workspaceId: String? = null,
        selectedFile: String? = null,
        /** Files attached to this message, already materialised into the workspace. */
        attachments: List<AgentAttachment> = emptyList(),
        /** Skills the user picked for this message; null keeps the role's own set. */
        skillIds: Set<String>? = null,
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
        /** Files attached to this message, already materialised into the workspace. */
        attachments: List<AgentAttachment> = emptyList(),
        /** Skills the user picked for this message; null keeps the role's own set. */
        skillIds: Set<String>? = null,
    ) {
        run(
            input = input,
            onEvent = onEvent,
            workspaceId = workspaceId,
            selectedFile = selectedFile,
            attachments = attachments,
            skillIds = skillIds,
        )
    }

    /**
     * Persistent, newest-first session list owned by [projectId]. Defaults to
     * empty so a session without history simply shows no sidebar entries.
     *
     * Chat is project-scoped: this returns only the sessions that belong to
     * [projectId], never another project's.
     */
    suspend fun listSessions(projectId: String): List<AgentSessionInfo> = emptyList()

    /**
     * The session the next turn belongs to within [projectId], or null when none
     * is selected yet. A selection in one project is never visible in another.
     */
    suspend fun activeSessionId(projectId: String): String? = null

    /**
     * Creates and selects an empty session owned by [projectId], returning its
     * metadata. A session must always have an owning project.
     */
    suspend fun createSession(projectId: String): AgentSessionInfo? = null

    /**
     * Loads [sessionId]'s persisted transcript so it can be restored, but only
     * when that session belongs to [projectId]. A session owned by another
     * project is reported exactly like a missing one.
     */
    suspend fun restoreSession(projectId: String, sessionId: String): List<PersistedAgentMessage> = emptyList()

    /**
     * Renames a persisted session owned by [projectId]. Returns whether the
     * title changed; a session belonging to another project is not touched.
     */
    suspend fun renameSession(projectId: String, sessionId: String, title: String): Boolean = false

    /**
     * Deletes a persisted session owned by [projectId] and its transcript. A
     * session belonging to another project is not touched.
     */
    suspend fun deleteSession(projectId: String, sessionId: String): Boolean = false
}

/** What came back from an attachment pick. */
sealed interface AttachmentPickOutcome {
    /**
     * The file was attached. [attachment] is a reference into the open workspace, so the agent
     * reads it with the same tools it reads any other file with.
     */
    data class Attached(val attachment: AgentAttachment) : AttachmentPickOutcome

    /** The user backed out of the picker. Nothing to report and nothing to undo. */
    data object Cancelled : AttachmentPickOutcome

    /** The file could not be attached. [message] explains why, in the user's terms. */
    data class Failed(val message: String) : AttachmentPickOutcome
}

/**
 * Picks a file and turns it into an attachment the agent can read.
 *
 * The Android layer implements this: it opens the platform picker for [kind], reads the picked
 * `content://` URI and materialises it into the open workspace through the existing
 * [com.agentx.app.context.AttachmentMaterializer]. What comes back is a workspace reference, never a
 * copy the chat holds on to — so there is one place attachments live, and the agent's own file tools
 * can already read them.
 *
 * [onResult] is invoked exactly once per pick, on the main thread, including on cancellation.
 */
fun interface AttachmentPicker {
    fun pick(kind: AgentAttachmentKind, onResult: (AttachmentPickOutcome) -> Unit)
}
