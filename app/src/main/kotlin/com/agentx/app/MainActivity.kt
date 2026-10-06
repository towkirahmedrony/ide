package com.agentx.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.agentx.app.agent.conversation.FilesystemConversationStore
import com.agentx.app.agent.orchestrator.AgentOrchestrator
import com.agentx.app.agent.ui.OrchestratorAgentSession
import com.agentx.app.app.AgentxProjectStorage
import com.agentx.app.codeintel.DelegatingSyntaxParserProvider
import com.agentx.app.codeintel.android.TreeSitterParserProvider
import com.agentx.app.app.AndroidModelRunnerBrowserHost
import com.agentx.app.app.rememberAndroidWorkspacePicker
import com.agentx.app.context.DelegatingWorkspaceContextProvider
import com.agentx.app.context.WorkspaceRuntimeContextProvider
import com.agentx.app.context.WorkspaceSelectionState
import com.agentx.app.context.WorkspaceSkillSource
import com.agentx.app.core.config.ForgeConfig
import com.agentx.app.core.config.ForgeConfigLoader
import com.agentx.app.core.config.OAuthConfig
import com.agentx.app.core.config.OAuthProviderConfig
import com.agentx.app.core.foundation.ServiceKeys
import com.agentx.app.foundation.ConnectionManagerToolAuthorizer
import com.agentx.app.app.git.ActiveGitProjectProvider
import com.agentx.app.app.git.UbuntuGitCommandRunner
import com.agentx.app.foundation.Foundation
import com.agentx.app.git.CliGitService
import com.agentx.app.foundation.IntegrationToolSynchronizer
import com.agentx.app.logging.DeveloperLogSink
import com.agentx.app.integrations.android.SharedPreferencesIntegrationSetupStore
import com.agentx.app.integrations.oauth.OAuthCallbackAuthority
import com.agentx.app.integrations.providers.ConnectionProviders
import com.agentx.app.integrations.oauth.UrlConnectionOAuthHttpClient
import com.agentx.app.integrations.setup.IntegrationSetupManager
import com.agentx.app.oauth.IntentOAuthBrowserLauncher
import com.agentx.app.settings.FilesystemSkillFileStore
import com.agentx.app.settings.SharedPreferencesAgentPromptStore
import com.agentx.app.settings.SharedPreferencesAgentFallbackStore
import com.agentx.app.settings.SharedPreferencesAgentRoleModelStore
import com.agentx.app.settings.SharedPreferencesSkillConfigStore
import com.agentx.app.skills.CompositeSkillStore
import com.agentx.app.tools.ToolRegistry
import com.agentx.app.ui.ide.data.OAuthCallbackInbox
import com.agentx.app.model.ModelConfig
import com.agentx.app.model.manager.ModelManager
import com.agentx.app.integrations.android.KeystoreConnectionSecretStore
import com.agentx.app.integrations.android.SharedPreferencesConnectionStore
import com.agentx.app.integrations.connection.ConnectionManager
import com.agentx.app.model.android.KeystoreModelSecretStore
import com.agentx.app.model.android.SharedPreferencesModelCatalogStore
import com.agentx.app.model.android.SharedPreferencesModelPresetStore
import com.agentx.app.model.android.SharedPreferencesRateLimitProfileStore
import com.agentx.app.model.runtime.RuntimeOutputBuffer
import com.agentx.app.tools.DelegatingToolConnectionAuthorizer
import com.agentx.app.tools.DelegatingWorkspaceFileSystemResolver
import com.agentx.app.tools.WorkspaceManagerFileSystemResolver
import com.agentx.app.model.catalog.ModelCatalogRegistry
import com.agentx.app.model.ratelimit.RateLimitManager
import com.agentx.app.ui.ide.ForgeIdeApp
import com.agentx.app.ui.ide.IdeDependencies
import com.agentx.app.termux.TermuxRuntime
import com.agentx.app.termux.TermuxRuntimeHolder
import com.agentx.app.ubuntu.LocalUbuntuRuntime
import com.agentx.app.ui.theme.ForgeTheme
import com.agentx.app.workspace.DefaultWorkspaceManager
import com.agentx.app.workspace.FileWorkspaceBackend
import com.agentx.app.workspace.ManagedProjectDirectory
import com.agentx.app.git.DelegatingGitService
import com.agentx.app.tools.DelegatingWorkspaceHostPathResolver
import com.agentx.app.tools.WorkspaceHostPathResolver
import com.agentx.app.workspace.RoutingWorkspaceBackend
import com.agentx.app.workspace.android.SafWorkspaceBackend
import com.agentx.app.workspace.android.SharedPreferencesWorkspaceMetadataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var modelRunnerBrowser: AndroidModelRunnerBrowserHost

    /**
     * OAuth redirects are published here by [onCreate]/[onNewIntent] and consumed
     * by the UI, which completes the authorization through the Connection Manager.
     * The URI holds an authorization code, so it stays in memory only.
     */
    private val oauthCallbacks = OAuthCallbackInbox()

    private var toolSynchronizer: IntegrationToolSynchronizer? = null
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Captured model-runtime output is shared by the Model Runner browser and
        // the manager's endpoint discovery.
        val runtimeOutput = RuntimeOutputBuffer()
        modelRunnerBrowser = AndroidModelRunnerBrowserHost(this, runtimeOutput)
        modelRunnerBrowser.restoreState(savedInstanceState?.getBundle(KEY_RUNNER_STATE))

        // The core foundation boots once: health + layers, model presets, runners
        // and the active connection. Its status is surfaced from Settings → About →
        // Developer information.
        val oauthConfig = buildOAuthConfig()
        val callbacks = OAuthCallbackAuthority.from(
            scheme = BuildConfig.OAUTH_REDIRECT_SCHEME,
            host = BuildConfig.OAUTH_REDIRECT_HOST,
        )
        val built = ConnectionProviders.fromConfig(
            config = oauthConfig,
            http = UrlConnectionOAuthHttpClient(),
            callbacks = callbacks,
            setupStore = SharedPreferencesIntegrationSetupStore(applicationContext),
        )
        // The Context Engine's workspace port is created here rather than inside boot,
        // so the same live port backs both context building and workspace skill
        // discovery. It is pointed at the Workspace Runtime further down.
        val contextWorkspace = DelegatingWorkspaceContextProvider()
        val foundation = Foundation.boot(
            config = ForgeConfig(oauth = oauthConfig),
            contextWorkspace = contextWorkspace,
            presetStore = SharedPreferencesModelPresetStore(applicationContext),
            secretStore = KeystoreModelSecretStore(applicationContext),
            connectionStore = SharedPreferencesConnectionStore(applicationContext),
            connectionSecretStore = KeystoreConnectionSecretStore(applicationContext),
            runtimeOutput = runtimeOutput,
            connectionProviders = built.registry,
            integrationSetup = built.setup,
            agentPromptStore = SharedPreferencesAgentPromptStore(applicationContext),
            agentRoleModelStore = SharedPreferencesAgentRoleModelStore(applicationContext),
            // Controlled fallback is opt-in and user-owned: a chain configured here
            // outlives the process, and an app that never configures one keeps
            // failing clearly instead of quietly using another model. A chain entry
            // names a connection, never a credential.
            agentFallbackStore = SharedPreferencesAgentFallbackStore(applicationContext),
            // Provider model catalogs persist beside the presets, so a restart keeps
            // the models a previous run discovered instead of falling back to a
            // built-in list. No credential is written here; a snapshot holds none.
            modelCatalogStore = SharedPreferencesModelCatalogStore(applicationContext),
            // Configured rate-limit quotas persist beside the presets, so a provider's
            // limits are in force for the first request after a restart instead of
            // only being learnt from a 429. A profile holds no credential.
            rateLimitProfileStore = SharedPreferencesRateLimitProfileStore(applicationContext),
            // Agent sessions persist as one JSON file each, so a session can be
            // reopened (or deleted) without touching another session's history.
            conversationStore = FilesystemConversationStore(
                File(applicationContext.filesDir, AGENT_SESSIONS_DIRECTORY),
            ),
            skillStore = CompositeSkillStore(
                config = SharedPreferencesSkillConfigStore(applicationContext),
                files = FilesystemSkillFileStore(File(applicationContext.filesDir, SKILLS_DIRECTORY)),
            ),
            // Workspace skills come from the open workspace's `skills/<id>/SKILL.md`
            // folders, read through the Context Engine's workspace port. Built-in and
            // imported skills are unaffected; with no workspace open nothing is read.
            skillSources = listOf(WorkspaceSkillSource.discovery(contextWorkspace)),
            // Platform-layer structured records are also mirrored into the
            // Developer Log, so model/API diagnostics are inspectable beside the
            // terminal and runtime diagnostics rather than only on stdout.
            logSink = DeveloperLogSink(),
        )

        // The saved role → model assignments are restored off the main thread;
        // until this completes the registry serves the built-in default mapping.
        backgroundScope.launch {
            runCatching { foundation.agentRoleModels.load() }
        }

        // The persisted model catalog is restored off the main thread, so the
        // models a previous run discovered are back in the capability registry and
        // the picker before any provider is asked. A failed read never blocks
        // startup: the next refresh simply fetches a fresh list.
        backgroundScope.launch {
            runCatching {
                foundation.services.get<ModelCatalogRegistry>(ServiceKeys.MODEL_CATALOG)?.restore()
            }
        }

        // Skills are discovered from the filesystem off the main thread; until
        // this completes, the manager serves the built-in catalog only.
        backgroundScope.launch {
            runCatching { foundation.skillManager.refresh() }
                .onFailure { error ->
                    // A damaged or unreadable user skill must never prevent the IDE
                    // from opening. Built-in skills remain available from the last
                    // in-memory snapshot, while the cause is available in logcat.
                    Log.e(TAG, "Could not refresh user skills; continuing with current catalog", error)
                }
        }

        // The code intelligence module is registered at boot with a bindable
        // parser backend; the tree-sitter grammars are attached here. Binding is
        // lazy per language, so nothing is loaded until a file needs it, and a
        // grammar that fails to load is reported as unparseable instead of
        // crashing.
        (foundation.codeIntelligenceParsers as? DelegatingSyntaxParserProvider)?.bind(TreeSitterParserProvider())

        // The embedded Termux runtime is process-scoped, so an Activity recreation (rotation,
        // return from the background) finds the same shells instead of orphaning them.
        val termuxRuntime = TermuxRuntime.get(applicationContext)
        TermuxRuntimeHolder.install(termuxRuntime)

        // The primary developer runtime is process-scoped for the same reason: its rootfs
        // install must survive an Activity recreation, and its status drives the Terminal tab.
        // It is intentionally separate from the legacy Termux runtime, which stays installed
        // as the fallback backend while the new runtime is validated on devices.
        val developerRuntime = LocalUbuntuRuntime.get(applicationContext)

        val modelManager = foundation.services.get<ModelManager>(ServiceKeys.MODEL_MANAGER)
        val connectionManager = foundation.services.get<ConnectionManager>(ServiceKeys.CONNECTION_MANAGER)
        val integrationSetup = foundation.services.get<IntegrationSetupManager>(ServiceKeys.INTEGRATION_SETUP)
        integrationSetup?.let { manager ->
            backgroundScope.launch { manager.refresh() }
        }

        // Provider tools are installed while a connection provides what they need and
        // removed when it is disconnected.
        val registry = foundation.services.get<ToolRegistry>(ServiceKeys.TOOL_REGISTRY)
        if (connectionManager != null && registry != null) {
            toolSynchronizer = IntegrationToolSynchronizer(
                manager = connectionManager,
                registry = registry,
                scope = backgroundScope,
            ).also { it.start() }
        }

        // A redirect that started the app has to be handled as soon as the UI is up.
        intent?.data?.toString()?.let(oauthCallbacks::publish)

        // Android cannot run foreground network monitoring forever, so the manager
        // is told when the app is actually visible and re-checks on return.
        lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    modelManager?.onAppForeground()
                }

                override fun onStop(owner: LifecycleOwner) {
                    modelManager?.onAppBackground()
                }
            },
        )

        // Manually created projects live in AgentX-managed storage. The same directory is what a
        // project delete has to clean up, so both read it from one place rather than naming it
        // twice and drifting.
        val managedProjectsRoot = File(
            applicationContext.filesDir,
            ManagedProjectDirectory.DIRECTORY_NAME,
        )

        // One live workspace for the process: Files, Context Engine and tools
        // must share this instance. Recreating it from Compose remember would
        // drop the open session and make the agent look at an empty project.
        val workspaceManager = DefaultWorkspaceManager(
            // One backend per project source behind one port: a SAF tree is opened through the
            // document provider, a real path (an AgentX-managed Git clone) through the filesystem.
            backend = RoutingWorkspaceBackend.contentAndPath(
                saf = SafWorkspaceBackend(applicationContext),
                files = FileWorkspaceBackend(),
            ),
            store = SharedPreferencesWorkspaceMetadataStore(applicationContext),
            // Manually created projects are empty directories in AgentX-managed storage — the same
            // app-private location used for cloned repositories, outside the Ubuntu rootfs — and
            // become the active project through the existing open/`/workspace` mechanism.
            projects = ManagedProjectDirectory(managedProjectsRoot),
            // Deleting a project also removes the AgentX-owned data that belongs to it, and only
            // that: the copy roots hold copies AgentX made, the managed root holds projects AgentX
            // created, and the rootfs, the cached archive and the user's own folders are out of
            // scope by construction. See AgentxProjectStorage.
            projectStorage = AgentxProjectStorage.create(
                copyRoots = AgentxProjectStorage.copyRoots(developerRuntime, termuxRuntime),
                managedRoots = listOf(managedProjectsRoot.path),
            ),
        )

        // Git runs against the active workspace through the embedded runtime, so it sees the
        // same files the IDE and the terminal do. The project is resolved live, never cached.
        val gitService = CliGitService(
            projects = ActiveGitProjectProvider(workspaceManager),
            runner = UbuntuGitCommandRunner(developerRuntime),
        )

        val workspaceSelection = WorkspaceSelectionState()
        when (val resolver = foundation.services.get<Any>(ServiceKeys.TOOL_WORKSPACE_RESOLVER)) {
            is DelegatingWorkspaceFileSystemResolver ->
                resolver.bind(WorkspaceManagerFileSystemResolver(workspaceManager))
        }
        (foundation.contextWorkspace as? DelegatingWorkspaceContextProvider)?.bind(
            WorkspaceRuntimeContextProvider(manager = workspaceManager, selection = workspaceSelection),
        )
        when (val authorizer = foundation.services.get<Any>(ServiceKeys.TOOL_CONNECTION_AUTHORIZER)) {
            is DelegatingToolConnectionAuthorizer ->
                authorizer.bind(
                    ConnectionManagerToolAuthorizer(
                        checkNotNull(connectionManager) { "Connection manager is not registered" },
                    ),
                )
        }
        // Agent-issued commands need a real host directory; it is the same project the
        // terminal and Git see, resolved live so a stale path is never used.
        val gitProjectProvider = ActiveGitProjectProvider(workspaceManager)
        when (val hostPaths = foundation.services.get<Any>(ServiceKeys.TOOL_WORKSPACE_HOST_PATHS)) {
            is DelegatingWorkspaceHostPathResolver ->
                hostPaths.bind(
                    WorkspaceHostPathResolver { context ->
                        val active = gitProjectProvider.active()
                        val matches = context.workspaceId == null || context.workspaceId == active?.workspaceId
                        active?.takeIf { it.available && matches }?.hostPath
                    },
                )
        }
        // The git tools operate through this same service the IDE uses.
        when (val holder = foundation.services.get<Any>(ServiceKeys.GIT_SERVICE)) {
            is DelegatingGitService -> holder.bind(gitService)
        }

        setContent {
            ForgeTheme {
                // Workspace access is real: the Storage Access Framework opens the
                // folder the user picks and only the minimum metadata is persisted.
                val workspacePicker = rememberAndroidWorkspacePicker()
                val dependencies = remember(workspacePicker, foundation) {
                    val orchestrator = foundation.services.get<AgentOrchestrator>(ServiceKeys.AGENT_ORCHESTRATOR)
                    IdeDependencies(
                        workspaceManager = workspaceManager,
                        workspaceSelection = workspaceSelection,
                        codeIntelligence = foundation.codeIntelligence,
                        workspacePicker = workspacePicker,
                        agent = OrchestratorAgentSession(
                            orchestrator = checkNotNull(orchestrator) { "Agent orchestrator is not registered" },
                            // The agent always uses whatever model the Model Manager
                            // has online; it never learns where that model runs.
                            modelConfig = { modelManagerOrDefault(modelManager) },
                        ),
                        terminalRuntime = termuxRuntime,
                        developerRuntime = developerRuntime,
                        git = gitService,
                        modelManager = checkNotNull(modelManager) { "Model manager is not registered" },
                        rateLimits = foundation.services.get<RateLimitManager>(ServiceKeys.RATE_LIMIT_MANAGER),
                        modelCatalog = foundation.services.get<ModelCatalogRegistry>(ServiceKeys.MODEL_CATALOG),
                        // The same capability registry the gateway and the role eligibility
                        // checker read, so Settings reports a capability-ineligible model
                        // with the answer the runtime would give.
                        modelCapabilities = foundation.services.get(ServiceKeys.MODEL_CAPABILITY_REGISTRY),
                        connectionManager = checkNotNull(connectionManager) { "Connection manager is not registered" },
                        integrationSetup = integrationSetup,
                        agentPrompts = foundation.promptManager,
                        agentRoleModels = foundation.agentRoleModels,
                        skills = foundation.skillManager,
                        oauthBrowser = IntentOAuthBrowserLauncher(applicationContext),
                        oauthCallbacks = oauthCallbacks,
                        modelRunnerBrowser = modelRunnerBrowser,
                        modelRuntimeOutput = runtimeOutput,
                    )
                }

                ForgeIdeApp(
                    dependencies = dependencies,
                    appName = foundation.config.appName,
                    version = "0.1.0",
                    layers = foundation.layers,
                    health = foundation.health,
                )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val browserState = Bundle()
        modelRunnerBrowser.saveState(browserState)
        outState.putBundle(KEY_RUNNER_STATE, browserState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Duplicate deliveries are handled downstream: the Connection Manager's
        // pending authorization is single-use, so a replay cannot exchange twice.
        intent.data?.toString()?.let(oauthCallbacks::publish)
    }

    override fun onDestroy() {
        toolSynchronizer?.stop()
        backgroundScope.cancel()
        // The WebView holds this activity, so it is always released here; its page
        // state was saved above and cookies survive in the WebView store.
        modelRunnerBrowser.release()
        super.onDestroy()
    }

    /**
     * OAuth configuration for this build.
     *
     * Client ids and redirect URIs are public values that the app owner registers
     * with each provider; the exchange broker URL points at the server that holds
     * the client secret, which is never part of the APK.
     */
    private fun buildOAuthConfig(): OAuthConfig {
        val redirectUri = "${BuildConfig.OAUTH_REDIRECT_SCHEME}://${BuildConfig.OAUTH_REDIRECT_HOST}" +
            BuildConfig.OAUTH_REDIRECT_PATH
        return OAuthConfig(
            github = OAuthProviderConfig(
                clientId = BuildConfig.OAUTH_GITHUB_CLIENT_ID,
                redirectUri = redirectUri,
                exchangeBrokerUrl = BuildConfig.OAUTH_GITHUB_BROKER_URL.takeIf { it.isNotBlank() },
                scopes = ForgeConfigLoader.parseScopes(BuildConfig.OAUTH_GITHUB_SCOPES),
            ),
            supabase = OAuthProviderConfig(
                clientId = BuildConfig.OAUTH_SUPABASE_CLIENT_ID,
                redirectUri = redirectUri,
                exchangeBrokerUrl = BuildConfig.OAUTH_SUPABASE_BROKER_URL.takeIf { it.isNotBlank() },
                scopes = ForgeConfigLoader.parseScopes(BuildConfig.OAUTH_SUPABASE_SCOPES),
            ),
        )
    }

    private companion object {
        const val TAG = "AgentX.MainActivity"
        const val KEY_RUNNER_STATE = "forge.modelRunner.state"
        const val SKILLS_DIRECTORY = "skills"
        const val AGENT_SESSIONS_DIRECTORY = "agent-sessions"
    }
}

/**
 * The configuration the agent should use.
 *
 * When no model is online the placeholder is intentionally invalid: the agent then
 * reports that no model is configured, instead of quietly calling an endpoint the
 * user never selected. Connecting a model is always an explicit user action.
 */
private fun modelManagerOrDefault(manager: ModelManager?): ModelConfig =
    manager?.activeConfig() ?: ModelConfig(providerId = "", baseUrl = "", model = "")
