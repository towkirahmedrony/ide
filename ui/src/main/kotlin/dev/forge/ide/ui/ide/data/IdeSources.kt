package dev.forge.ide.ui.ide.data

import dev.forge.ide.ui.ide.model.AgentActivity
import dev.forge.ide.ui.ide.model.GitSnapshot
import dev.forge.ide.ui.ide.model.TerminalResult

/**
 * Presentation-facing ports for the IDE shell.
 *
 * Workspace access is no longer mocked: the UI talks to the
 * `dev.forge.ide.workspace.WorkspaceManager` domain contract, which is backed by
 * the Android Storage Access Framework. The remaining ports (agent, terminal,
 * git) are still stand-ins for layers that land in later tasks.
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

    data class Failed(val message: String) : AgentStreamEvent

    data class AgentChanged(val role: String, val label: String) : AgentStreamEvent
}

/**
 * Runs a single agent turn. The mock implementation returns canned output;
 * the real Agent Core will implement this without any UI changes.
 */
interface AgentSession {
    suspend fun run(input: String, onEvent: (AgentStreamEvent) -> Unit)
}

/** Executes shell commands for a workspace. Mocked until the runtime exists. */
interface TerminalSession {
    suspend fun run(workspaceId: String, command: String): TerminalResult
}

/** Reads repository state for a workspace. No real Git access yet. */
interface GitRepository {
    suspend fun snapshot(workspaceId: String): GitSnapshot
}
