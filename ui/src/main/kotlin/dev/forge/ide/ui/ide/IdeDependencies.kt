package dev.forge.ide.ui.ide

import dev.forge.ide.model.manager.ModelManager
import dev.forge.ide.model.manager.ModelManagers
import dev.forge.ide.model.runtime.RuntimeOutputBuffer
import dev.forge.ide.ui.ide.data.AgentSession
import dev.forge.ide.ui.ide.data.GitRepository
import dev.forge.ide.ui.ide.data.ModelRunnerBrowserHost
import dev.forge.ide.ui.ide.data.TerminalSession
import dev.forge.ide.ui.ide.data.WorkspacePicker
import dev.forge.ide.ui.ide.data.mock.MockAgentSession
import dev.forge.ide.ui.ide.data.mock.MockGitRepository
import dev.forge.ide.ui.ide.data.mock.MockModelRunnerBrowser
import dev.forge.ide.ui.ide.data.mock.MockTerminalSession
import dev.forge.ide.ui.ide.data.mock.mockWorkspaceManager
import dev.forge.ide.ui.ide.data.mock.mockWorkspacePicker
import dev.forge.ide.workspace.WorkspaceManager

/**
 * Everything the IDE shell needs from the outside world. The workspace runtime
 * is injected as the domain [WorkspaceManager]; the application binds it to the
 * Android implementation, while [mock] provides an in-memory demonstration.
 *
 * [modelManager] is the same idea for models: the UI never touches a runner, a
 * tunnel or the gateway directly, and previews work without any of them.
 */
data class IdeDependencies(
    val workspaceManager: WorkspaceManager,
    val workspacePicker: WorkspacePicker,
    val agent: AgentSession,
    val terminal: TerminalSession,
    val git: GitRepository,
    val modelManager: ModelManager,
    val modelRunnerBrowser: ModelRunnerBrowserHost,
    /** Captured model-runtime output; shared with the manager's endpoint discovery. */
    val modelRuntimeOutput: RuntimeOutputBuffer,
) {
    companion object {
        /** In-memory bindings for previews and tests. */
        fun mock(): IdeDependencies {
            val runtimeOutput = RuntimeOutputBuffer()
            return IdeDependencies(
                workspaceManager = mockWorkspaceManager(),
                workspacePicker = mockWorkspacePicker(),
                agent = MockAgentSession(),
                terminal = MockTerminalSession(),
                git = MockGitRepository(),
                modelManager = ModelManagers.create(
                    runtimeOutput = runtimeOutput,
                    monitorEnabled = false,
                ),
                modelRunnerBrowser = MockModelRunnerBrowser(),
                modelRuntimeOutput = runtimeOutput,
            )
        }
    }
}
