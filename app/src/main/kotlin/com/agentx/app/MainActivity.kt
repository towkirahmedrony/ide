package com.agentx.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.agentx.app.agent.orchestrator.AgentOrchestrator
import com.agentx.app.agent.ui.OrchestratorAgentSession
import com.agentx.app.codeintel.DelegatingSyntaxParserProvider
import com.agentx.app.codeintel.android.TreeSitterParserProvider
import com.agentx.app.app.AndroidModelRunnerBrowserHost
import com.agentx.app.app.rememberAndroidWorkspacePicker
import com.agentx.app.context.DelegatingWorkspaceContextProvider
import com.agentx.app.context.WorkspaceRuntimeContextProvider
import com.agentx.app.context.WorkspaceSelectionState
import com.agentx.app.core.config.ForgeConfig
import com.agentx.app.core.config.ForgeConfigLoader
import com.agentx.app.core.config.OAuthConfig
import com.agentx.app.core.config.OAuthProviderConfig
import com.agentx.app.core.foundation.ServiceKeys
import com.agentx.app.foundation.ConnectionManagerToolAuthorizer
import com.agentx.app.foundation.Foundation
import com.agentx.app.foundation.IntegrationToolSynchronizer
import com.agentx.app.integrations.android.SharedPreferencesIntegrationSetupStore
import com.agentx.app.integrations.oauth.OAuthCallbackAuthority
import com.agentx.app.integrations.providers.ConnectionProviders
import com.agentx.app.integrations.oauth.UrlConnectionOAuthHttpClient
import com.agentx.app.integrations.setup.IntegrationSetupManager
import com.agentx.app.oauth.IntentOAuthBrowserLauncher
import com.agentx.app.settings.FilesystemSkillFileStore
import com.agentx.app.settings.SharedPreferencesAgentPromptStore
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
import com.agentx.app.model.android.SharedPreferencesModelPresetStore
import com.agentx.app.model.runtime.RuntimeOutputBuffer
import com.agentx.app.tools.DelegatingToolConnectionAuthorizer
import com.agentx.app.tools.DelegatingWorkspaceFileSystemResolver
import com.agentx.app.tools.WorkspaceManagerFileSystemResolver
import com.agentx.app.ui.ide.ForgeIdeApp
import com.agentx.app.ui.ide.IdeDependencies
import com.agentx.app.ui.ide.data.mock.MockGitRepository
import com.agentx.app.termux.TermuxRuntime
import com.agentx.app.termux.TermuxRuntimeHolder
import com.agentx.app.ui.theme.ForgeTheme
import com.agentx.app.workspace.DefaultWorkspaceManager
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
        val foundation = Foundation.boot(
            config = ForgeConfig(oauth = oauthConfig),
            presetStore = SharedPreferencesModelPresetStore(applicationContext),
            secretStore = KeystoreModelSecretStore(applicationContext),
            connectionStore = SharedPreferencesConnectionStore(applicationContext),
            connectionSecretStore = KeystoreConnectionSecretStore(applicationContext),
            runtimeOutput = runtimeOutput,
            connectionProviders = built.registry,
            integrationSetup = built.setup,
            agentPromptStore = SharedPreferencesAgentPromptStore(applicationContext),
            skillStore = CompositeSkillStore(
                config = SharedPreferencesSkillConfigStore(applicationContext),
                files = FilesystemSkillFileStore(File(applicationContext.filesDir, SKILLS_DIRECTORY)),
            ),
        )

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

        // One live workspace for the process: Files, Context Engine and tools
        // must share this instance. Recreating it from Compose remember would
        // drop the open session and make the agent look at an empty project.
        val workspaceManager = DefaultWorkspaceManager(
            backend = SafWorkspaceBackend(applicationContext),
            store = SharedPreferencesWorkspaceMetadataStore(applicationContext),
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
                        git = MockGitRepository(),
                        modelManager = checkNotNull(modelManager) { "Model manager is not registered" },
                        connectionManager = checkNotNull(connectionManager) { "Connection manager is not registered" },
                        integrationSetup = integrationSetup,
                        agentPrompts = foundation.promptManager,
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
