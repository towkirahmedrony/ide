package com.agentx.app.ui.ide

import com.agentx.app.agent.model.AgentRoleModelRegistry
import com.agentx.app.agent.model.DefaultAgentRoleModelRepository
import com.agentx.app.agent.model.InMemoryAgentRoleModelStore
import com.agentx.app.agent.prompt.PromptManager
import com.agentx.app.codeintel.CodeIntelligence
import com.agentx.app.context.WorkspaceSelectionState
import com.agentx.app.integrations.ConnectionManagers
import com.agentx.app.integrations.connection.ConnectionManager
import com.agentx.app.integrations.github.GitHubRepositoryProjectCloner
import com.agentx.app.integrations.setup.InMemoryIntegrationSetupStore
import com.agentx.app.integrations.setup.IntegrationSetupManager
import com.agentx.app.model.capability.ModelCapabilityRegistry
import com.agentx.app.model.catalog.ModelCatalogRegistry
import com.agentx.app.model.manager.ModelManager
import com.agentx.app.model.manager.ModelManagers
import com.agentx.app.model.ratelimit.RateLimitManager
import com.agentx.app.model.runtime.RuntimeOutputBuffer
import com.agentx.app.ui.ide.data.AgentSession
import com.agentx.app.ui.ide.data.ModelRunnerBrowserHost
import com.agentx.app.ui.ide.data.NoOpOAuthBrowserLauncher
import com.agentx.app.ui.ide.data.OAuthBrowserLauncher
import com.agentx.app.ui.ide.data.OAuthCallbackInbox
import com.agentx.app.ui.ide.data.AttachmentPicker
import com.agentx.app.ui.ide.data.WorkspacePicker
import com.agentx.app.ui.ide.data.mock.MockAgentSession
import com.agentx.app.ui.ide.data.mock.MockModelRunnerBrowser
import com.agentx.app.ui.ide.data.mock.mockWorkspaceManager
import com.agentx.app.ui.ide.data.mock.mockWorkspacePicker
import com.agentx.app.skills.DefaultSkillManager
import com.agentx.app.skills.SkillManager
import com.agentx.app.git.GitService
import com.agentx.app.git.UnavailableGitService
import com.agentx.app.termux.TermuxRuntime
import com.agentx.app.tools.ToolPreferences
import com.agentx.app.tools.ToolRegistry
import com.agentx.app.ubuntu.LocalUbuntuRuntime
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
    /**
     * Structural understanding of source files (symbols, outlines). Optional so
     * previews and tests keep working without one; when absent the editor simply
     * shows no structure instead of a wrong one.
     */
    val codeIntelligence: CodeIntelligence? = null,
    val workspacePicker: WorkspacePicker,
    /**
     * The AgentX-managed project roots on this device, in priority order, resolved by the composition
     * root from [com.agentx.app.workspace.AgentxProjectRoot] — the object project creation, GitHub
     * clone and project deletion all use. It is the single source of truth Settings → Workspace reads,
     * so that screen names where AgentX keeps its projects instead of hardcoding a path, and can tell
     * an AgentX-created project from a folder the user selected. Empty in previews, where nothing owns
     * a project folder.
     */
    val managedProjectRoots: List<String> = emptyList(),
    /**
     * Picks a file and materialises it into the open workspace. Optional: a host without pickers
     * (previews, tests) leaves it null and the composer says so instead of failing on tap.
     */
    val attachmentPicker: AttachmentPicker? = null,
    val agent: AgentSession,
    /**
     * The embedded Termux terminal runtime. Optional so previews render the Terminal tab
     * without a pty; when it is absent the screen says so instead of showing a fake shell.
     */
    val terminalRuntime: TermuxRuntime? = null,
    /**
     * The primary embedded developer runtime (Ubuntu ARM64 through PRoot). When present and
     * ready it supplies the terminal's process; when it is absent or not installed, the legacy
     * [terminalRuntime] remains the backend, so the terminal is never without a shell.
     */
    val developerRuntime: LocalUbuntuRuntime? = null,
    /** Project-aware Git for the active workspace; [UnavailableGitService] in previews. */
    val git: GitService,
    val modelManager: ModelManager,
    /** Central admission control + usage, surfaced in Settings → Agent Models. */
    val rateLimits: RateLimitManager? = null,
    /** Dynamic per-provider model catalogs (for example Groq's model list). */
    val modelCatalog: ModelCatalogRegistry? = null,
    /**
     * The authoritative capability registry, so Settings can say when an assigned
     * model is ineligible for its role instead of only that it is unavailable.
     */
    val modelCapabilities: ModelCapabilityRegistry? = null,
    val connectionManager: ConnectionManager,
    /** Personal Client ID / callback setup for this IDE. Optional in previews. */
    val integrationSetup: IntegrationSetupManager? = null,
    /**
     * Clones a GitHub repository into the AgentX project folder and opens the clone as the active
     * project. Optional: a preview has no GitHub connection, so a surface that offers the clone
     * action omits it rather than offering one that cannot work.
     */
    val projectCloner: GitHubRepositoryProjectCloner? = null,
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
    /** Agent system-prompt manager: built-in defaults plus user overrides. */
    val agentPrompts: PromptManager = PromptManager(),
    /**
     * The single authoritative per-role model configuration. Settings writes it
     * and the Agent Core's model resolver reads it, so the two never disagree.
     */
    val agentRoleModels: AgentRoleModelRegistry = AgentRoleModelRegistry(
        DefaultAgentRoleModelRepository(InMemoryAgentRoleModelStore()),
    ),
    /** Central skills registry and manager. */
    val skills: SkillManager = DefaultSkillManager(),
    /**
     * The live tool catalog Settings → Tools reads. Optional so previews and tests
     * render the screen without a wired Tool System, in which case it reports that
     * the tool system is unavailable instead of inventing tools.
     */
    val tools: ToolRegistry? = null,
    /**
     * The user-owned tool enablement Settings writes and the runtime reads. The same
     * instance the Tool Router and the agent tool bridge use, so a change on the
     * screen affects what the agent is offered and may run.
     */
    val toolPreferences: ToolPreferences? = null,
) {
    companion object {
        /** In-memory bindings for previews and tests. */
        fun mock(): IdeDependencies {
            val runtimeOutput = RuntimeOutputBuffer()
            val workspaceManager = mockWorkspaceManager()
            return IdeDependencies(
                workspaceManager = workspaceManager,
                workspaceSelection = WorkspaceSelectionState(),
                workspacePicker = mockWorkspacePicker(),
                agent = MockAgentSession(),
                // No pty in a preview: the terminal reports that instead of pretending.
                terminalRuntime = null,
                git = UnavailableGitService,
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
