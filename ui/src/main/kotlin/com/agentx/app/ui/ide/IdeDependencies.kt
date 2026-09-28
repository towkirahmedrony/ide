package com.agentx.app.ui.ide

import com.agentx.app.context.WorkspaceSelectionState
import com.agentx.app.integrations.ConnectionManagers
import com.agentx.app.integrations.connection.ConnectionManager
import com.agentx.app.integrations.setup.InMemoryIntegrationSetupStore
import com.agentx.app.integrations.setup.IntegrationSetupManager
import com.agentx.app.model.manager.ModelManager
import com.agentx.app.model.manager.ModelManagers
import com.agentx.app.model.runtime.RuntimeOutputBuffer
import com.agentx.app.ui.ide.data.AgentSession
import com.agentx.app.ui.ide.data.GitRepository
import com.agentx.app.ui.ide.data.ModelRunnerBrowserHost
import com.agentx.app.ui.ide.data.NoOpOAuthBrowserLauncher
import com.agentx.app.ui.ide.data.OAuthBrowserLauncher
import com.agentx.app.ui.ide.data.OAuthCallbackInbox
import com.agentx.app.ui.ide.data.TerminalSession
import com.agentx.app.ui.ide.data.WorkspacePicker
import com.agentx.app.ui.ide.data.mock.MockAgentSession
import com.agentx.app.ui.ide.data.mock.MockGitRepository
import com.agentx.app.ui.ide.data.mock.MockModelRunnerBrowser
import com.agentx.app.ui.ide.data.mock.MockTerminalSession
import com.agentx.app.ui.ide.data.mock.mockWorkspaceManager
import com.agentx.app.ui.ide.data.mock.mockWorkspacePicker
import com.agentx.app.workspace.WorkspaceManager

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
    /**
     * Which file the editor has open and what was used recently. The Context
     * Engine reads the same instance, so the agent sees the user's context.
     */
    val workspaceSelection: WorkspaceSelectionState = WorkspaceSelectionState(),
    val workspacePicker: WorkspacePicker,
    val agent: AgentSession,
    val terminal: TerminalSession,
    val git: GitRepository,
    val modelManager: ModelManager,
    val connectionManager: ConnectionManager,
    /** Personal Client ID / callback setup for this IDE. Optional in previews. */
    val integrationSetup: IntegrationSetupManager? = null,
    /** Opens the provider's authorization page for OAuth-first connections. */
    val oauthBrowser: OAuthBrowserLauncher = NoOpOAuthBrowserLauncher,
    /**
     * Where the Activity publishes OAuth redirects. The same instance is read by
     * the callback state holder, so a redirect is handled exactly once.
     */
    val oauthCallbacks: OAuthCallbackInbox = OAuthCallbackInbox(),
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
                workspaceSelection = WorkspaceSelectionState(),
                workspacePicker = mockWorkspacePicker(),
                agent = MockAgentSession(),
                terminal = MockTerminalSession(),
                git = MockGitRepository(),
                modelManager = ModelManagers.create(
                    runtimeOutput = runtimeOutput,
                    monitorEnabled = false,
                ),
                connectionManager = ConnectionManagers.create(),
                integrationSetup = IntegrationSetupManager(store = InMemoryIntegrationSetupStore()),
                oauthBrowser = NoOpOAuthBrowserLauncher,
                oauthCallbacks = OAuthCallbackInbox(),
                modelRunnerBrowser = MockModelRunnerBrowser(),
                modelRuntimeOutput = runtimeOutput,
            )
        }
    }
}
