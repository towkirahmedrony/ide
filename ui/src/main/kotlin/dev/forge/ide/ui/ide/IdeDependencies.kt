package dev.forge.ide.ui.ide

import dev.forge.ide.ui.ide.data.AgentSession
import dev.forge.ide.ui.ide.data.GitRepository
import dev.forge.ide.ui.ide.data.ProjectCatalog
import dev.forge.ide.ui.ide.data.TerminalSession
import dev.forge.ide.ui.ide.data.WorkspaceFileSource
import dev.forge.ide.ui.ide.data.mock.InMemoryProjectCatalog
import dev.forge.ide.ui.ide.data.mock.InMemoryWorkspaceFileSource
import dev.forge.ide.ui.ide.data.mock.MockAgentSession
import dev.forge.ide.ui.ide.data.mock.MockGitRepository
import dev.forge.ide.ui.ide.data.mock.MockTerminalSession

/**
 * Everything the IDE shell needs from the outside world, expressed only through
 * the contracts in [dev.forge.ide.ui.ide.data]. The application wires real
 * implementations here as they become available; until then [mock] provides an
 * in-memory demonstration.
 */
data class IdeDependencies(
    val projects: ProjectCatalog,
    val files: WorkspaceFileSource,
    val agent: AgentSession,
    val terminal: TerminalSession,
    val git: GitRepository,
) {
    companion object {
        fun mock(): IdeDependencies = IdeDependencies(
            projects = InMemoryProjectCatalog(),
            files = InMemoryWorkspaceFileSource(),
            agent = MockAgentSession(),
            terminal = MockTerminalSession(),
            git = MockGitRepository(),
        )
    }
}
