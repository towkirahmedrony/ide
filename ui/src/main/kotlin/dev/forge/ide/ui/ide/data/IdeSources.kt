package dev.forge.ide.ui.ide.data

import dev.forge.ide.ui.ide.model.AgentActivity
import dev.forge.ide.ui.ide.model.FileNode
import dev.forge.ide.ui.ide.model.GitSnapshot
import dev.forge.ide.ui.ide.model.ProjectSummary
import dev.forge.ide.ui.ide.model.TerminalResult

/**
 * Presentation-facing ports for the IDE shell. The UI and its state holders
 * depend only on these contracts; later tasks provide real implementations
 * backed by the Workspace Runtime, Git layer, Agent Core, and Model Gateway.
 * The in-memory mock implementations let the shell be demonstrated today.
 */

/** Lists and creates workspaces shown on Home. */
interface ProjectCatalog {
    suspend fun recentProjects(): List<ProjectSummary>

    suspend fun createWorkspace(name: String): ProjectSummary

    suspend fun find(workspaceId: String): ProjectSummary?
}

/** Provides the file tree and file contents for a workspace. */
interface WorkspaceFileSource {
    suspend fun fileTree(workspaceId: String): List<FileNode>

    suspend fun readFile(path: String): String

    suspend fun writeFile(path: String, content: String)
}

/** Incremental events emitted while an agent produces a response. */
sealed interface AgentStreamEvent {
    data class Activity(val activity: AgentActivity) : AgentStreamEvent

    data class Chunk(val text: String) : AgentStreamEvent

    data class Completed(val text: String) : AgentStreamEvent

    data class Failed(val message: String) : AgentStreamEvent
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
