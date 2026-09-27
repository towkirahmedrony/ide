package dev.forge.ide.ui.ide

import dev.forge.ide.ui.ide.data.AgentSession
import dev.forge.ide.ui.ide.data.GitRepository
import dev.forge.ide.ui.ide.data.TerminalSession
import dev.forge.ide.ui.ide.data.WorkspacePicker
import dev.forge.ide.ui.ide.data.mock.MockAgentSession
import dev.forge.ide.ui.ide.data.mock.MockGitRepository
import dev.forge.ide.ui.ide.data.mock.MockTerminalSession
import dev.forge.ide.ui.ide.data.mock.mockWorkspaceManager
import dev.forge.ide.ui.ide.data.mock.mockWorkspacePicker
import dev.forge.ide.workspace.WorkspaceManager

/**
 * Everything the IDE shell needs from the outside world. The workspace runtime
 * is injected as the domain [WorkspaceManager]; the application binds it to the
 * Android implementation, while [mock] provides an in-memory demonstration.
 */
data class IdeDependencies(
    val workspaceManager: WorkspaceManager,
    val workspacePicker: WorkspacePicker,
    val agent: AgentSession,
    val terminal: TerminalSession,
    val git: GitRepository,
) {
    companion object {
        /** In-memory bindings for previews and tests. */
        fun mock(): IdeDependencies = IdeDependencies(
            workspaceManager = mockWorkspaceManager(),
            workspacePicker = mockWorkspacePicker(),
            agent = MockAgentSession(),
            terminal = MockTerminalSession(),
            git = MockGitRepository(),
        )
    }
}
