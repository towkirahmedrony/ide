package com.agentx.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.agentx.app.agent.conversation.FilesystemConversationStore
import com.agentx.app.agent.orchestrator.AgentOrchestrator
import com.agentx.app.agent.ui.OrchestratorAgentSession
import com.agentx.app.app.AgentxProjectStorage
import com.agentx.app.codeintel.DelegatingSyntaxParserProvider
import com.agentx.app.codeintel.android.TreeSitterParserProvider
import com.agentx.app.app.AndroidModelRunnerBrowserHost
import com.agentx.app.app.AndroidExternalContentReader
import com.agentx.app.app.rememberAndroidAttachmentPicker
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
import com.agentx.app.settings.SharedPreferencesThemeModeStore
import com.agentx.app.settings.SharedPreferencesToolPreferenceStore
import com.agentx.app.skills.CompositeSkillStore
import com.agentx.app.tools.DefaultToolPreferences
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
import com.agentx.app.ui.theme.AppearanceController
import com.agentx.app.ui.theme.ForgeTheme
import com.agentx.app.ui.theme.resolveDarkTheme
import com.agentx.app.workspace.DefaultWorkspaceManager
import com.agentx.app.workspace.FileWorkspaceBackend
import com.agentx.app.workspace.ManagedProjectDirectory
import com.agentx.app.git.DelegatingGitProjectProvider
import com.agentx.app.git.DelegatingGitPushService
import com.agentx.app.git.DelegatingGitService
import com.agentx.app.git.GitPushService
import com.agentx.app.core.pullrequest.PullRequestRef
import com.agentx.app.core.pullrequest.PullRequestService
import com.agentx.app.core.valueOrNull
import com.agentx.app.context.AttachmentMaterializer
import com.agentx.app.core.verification.CiVerificationService
import com.agentx.app.integrations.github.GitHubRepositoryCloneService
import com.agentx.app.integrations.github.GitHubRepositoryProjectCloner
import com.agentx.app.integrations.github.GitHubRepositoryRefs
import com.agentx.app.integrations.github.GitHubRepositoryServiceKeys
import com.agentx.app.tools.pullrequest.DelegatingPullRequestRepositoryProvider
import com.agentx.app.tools.pullrequest.DelegatingPullRequestService
import com.agentx.app.tools.pullrequest.PullRequestRepositoryProvider
import com.agentx.app.tools.verification.CiRepositoryRefProvider
import com.agentx.app.tools.verification.DelegatingCiRepositoryRefProvider
import com.agentx.app.tools.verification.DelegatingCiVerificationService
import com.agentx.app.tools.DelegatingWorkspaceHostPathResolver
import com.agentx.app.tools.WorkspaceHostPathResolver
import com.agentx.app.workspace.AgentxProjectRoot
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
            // Settings → Tools enable/disable choices persist here, so they survive
            // a restart the same way the other settings do. Only disabled ids are
            // stored; an empty set means every tool is on.
            toolPreferenceStore = SharedPreferencesToolPreferenceStore(applicationContext),
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

        // The user's tool enablement is restored off the main thread; until this
        // completes every tool stays enabled, which is the safe default. The Tool
        // Router and the agent bridge read the same instance, so the restored set
        // is in force for the next agent run.
        backgroundScope.launch {
            runCatching { (foundation.toolPreferences as? DefaultToolPreferences)?.load() }
                .onFailure { error ->
                    Log.e(TAG, "Could not restore tool preferences; keeping all tools enabled", error)
                }
        }

        // The theme mode is one value with one owner for the whole process: the
        // controller feeds ForgeTheme at the root and Settings → Appearance
        // writes to the same instance. It is process-scoped (below), so a
        // configuration change cannot orphan the instance retained screen state
        // still writes to. The persisted value is restored off the main thread;
        // until this completes the app shows the default dark appearance, so a
        // slow or failed read never delays the first frame.
        val appearance = appearanceController(applicationContext)
        backgroundScope.launch {
            runCatching { appearance.restore() }
                .onFailure { error ->
                    Log.e(TAG, "Could not restore the theme mode; keeping the default", error)
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

        // Every project AgentX owns lives in one user-visible folder in shared storage:
        // <shared storage>/AgentX/<name>. The base is resolved here, from Android's own API, so the
        // absolute prefix is never written into code; the folder name comes from AgentxProjectRoot,
        // so there is one statement of where a project lives. Projects AgentX creates and
        // repositories it clones both land directly in this folder.
        val agentxProjectRoot = AgentxProjectRoot.under(Environment.getExternalStorageDirectory())

        // Projects created before the AgentX folder existed are still directories in app-private
        // storage: they were never moved or copied. That root therefore stays registered as a
        // managed root, because it is what lets the existing deletion abstraction keep cleaning
        // those projects up — losing it would leave them openable but undeletable.
        val legacyManagedProjectsRoot = File(applicationContext.filesDir, LEGACY_PROJECTS_DIRECTORY)

        // One live workspace for the process: Files, Context Engine and tools
        // must share this instance. Recreating it from Compose remember would
        // drop the open session and make the agent look at an empty project.
        val workspaceManager = DefaultWorkspaceManager(
            // One backend per project source behind one port: a SAF tree is opened through the
            // document provider, a real path (an AgentX project, or a repository AgentX cloned)
            // through the filesystem.
            backend = RoutingWorkspaceBackend.contentAndPath(
                saf = SafWorkspaceBackend(applicationContext),
                files = FileWorkspaceBackend(),
            ),
            store = SharedPreferencesWorkspaceMetadataStore(applicationContext),
            // New projects are empty directories directly inside the AgentX folder — beside the
            // repositories AgentX clones into the same place, and outside the Ubuntu rootfs — and
            // become the active project through the existing open/`/workspace` mechanism. Nothing
            // falls back to app-private storage: the root is the project-location contract.
            projects = ManagedProjectDirectory(agentxProjectRoot),
            // Deleting a project also removes the AgentX-owned data that belongs to it, and only
            // that: the copy roots hold copies AgentX made, the managed roots hold projects AgentX
            // created — in the AgentX folder, and in the app-private storage older projects stayed
            // in — and the rootfs, the cached archive and the user's own folders are out of scope by
            // construction. See AgentxProjectStorage.
            projectStorage = AgentxProjectStorage.create(
                copyRoots = AgentxProjectStorage.copyRoots(developerRuntime, termuxRuntime),
                managedRoots = AgentxProjectStorage.managedRoots(
                    listOf(agentxProjectRoot.path, legacyManagedProjectsRoot.path),
                ),
            ),
        )

        // Git runs against the active workspace through the embedded runtime, so it sees the
        // same files the IDE and the terminal do. The project is resolved live, never cached.
        val gitService = CliGitService(
            projects = ActiveGitProjectProvider(workspaceManager),
            runner = UbuntuGitCommandRunner(developerRuntime),
        )

        // A cloned repository becomes a project the same way a created one does: it is cloned into
        // the same AgentX folder and then opened through the workspace runtime, which persists it,
        // makes it current and puts it in Recent Projects. The clone transport is the one the
        // GitHub module already registered, so authentication and containment validation are
        // unchanged; only the destination and the registration step are added.
        val projectCloner = (foundation.services.get<Any>(GitHubRepositoryServiceKeys.CLONE_SERVICE)
            as? GitHubRepositoryCloneService)?.let { cloneService ->
            GitHubRepositoryProjectCloner(
                cloner = cloneService,
                workspaces = workspaceManager,
                projectRoot = agentxProjectRoot,
            )
        }

        com.agentx.app.foundation.installGitHubAgentTools(registry = registry, connectionManager = connectionManager, repositoryService = foundation.services.get<Any>(GitHubRepositoryServiceKeys.REPOSITORY_SERVICE), cloneService = foundation.services.get<Any>(GitHubRepositoryServiceKeys.CLONE_SERVICE), workspaceManager = workspaceManager, managedRoot = managedProjectsRoot)
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
        // The GitHub push service resolves the active project through this provider, and
        // the agent's `git_push` tool reaches the same authenticated service. Both stay
        // fail-closed until this binding runs.
        when (val provider = foundation.services.get<Any>(ServiceKeys.GIT_PROJECT_PROVIDER)) {
            is DelegatingGitProjectProvider -> provider.bind(gitProjectProvider)
        }
        when (val holder = foundation.services.get<Any>(ServiceKeys.GIT_PUSH_SERVICE)) {
            is DelegatingGitPushService -> {
                (foundation.services.get<Any>(GitHubRepositoryServiceKeys.PUSH_SERVICE) as? GitPushService)
                    ?.let(holder::bind)
            }
        }
        // Read-only CI verification: the agent's `ci_verification` tool observes the
        // same authenticated GitHub account, and the repository is resolved from the
        // active project's credential-free origin remote. Both stay fail-closed until
        // this binding runs.
        when (val holder = foundation.services.get<Any>(ServiceKeys.CI_VERIFICATION_SERVICE)) {
            is DelegatingCiVerificationService -> {
                (foundation.services.get<Any>(GitHubRepositoryServiceKeys.CI_VERIFICATION_SERVICE)
                    as? CiVerificationService)?.let(holder::bind)
            }
        }
        when (val holder = foundation.services.get<Any>(ServiceKeys.CI_REPOSITORY_REF_PROVIDER)) {
            is DelegatingCiRepositoryRefProvider -> holder.bind(
                CiRepositoryRefProvider {
                    val active = gitProjectProvider.active() ?: return@CiRepositoryRefProvider null
                    if (!active.available) return@CiRepositoryRefProvider null
                    val remotes = gitService.remotes(active.workspaceId).valueOrNull().orEmpty()
                    val url = remotes.firstOrNull { it.name == "origin" }?.url ?: remotes.firstOrNull()?.url
                    url?.let { GitHubRepositoryRefs.fromCloneUrl(it) }
                },
            )
        }
        // Optional, approval-gated pull-request creation: the agent's `create_pr` tool
        // reaches the same authenticated GitHub service, and the repository is resolved
        // from the active project's credential-free origin remote. Both stay fail-closed
        // until this binding runs, so nothing else changes for the normal push workflow.
        when (val holder = foundation.services.get<Any>(ServiceKeys.PULL_REQUEST_SERVICE)) {
            is DelegatingPullRequestService -> {
                (foundation.services.get<Any>(GitHubRepositoryServiceKeys.PULL_REQUEST_SERVICE)
                    as? PullRequestService)?.let(holder::bind)
            }
        }
        when (val holder = foundation.services.get<Any>(ServiceKeys.PULL_REQUEST_REPOSITORY_PROVIDER)) {
            is DelegatingPullRequestRepositoryProvider -> holder.bind(
                PullRequestRepositoryProvider {
                    val active = gitProjectProvider.active() ?: return@PullRequestRepositoryProvider null
                    if (!active.available) return@PullRequestRepositoryProvider null
                    val remotes = gitService.remotes(active.workspaceId).valueOrNull().orEmpty()
                    val url = remotes.firstOrNull { it.name == "origin" }?.url ?: remotes.firstOrNull()?.url
                    val ref = url?.let { GitHubRepositoryRefs.fromCloneUrl(it) }
                        ?: return@PullRequestRepositoryProvider null
                    PullRequestRef(ref.owner, ref.name)
                },
            )
        }

        setContent {
            // One flow, collected once: ForgeTheme and Settings → Appearance
            // observe the same selection, so a change applies immediately.
            val themeMode by appearance.mode.collectAsState()
            ForgeTheme(themeMode = themeMode) {
                // The system bars are part of the appearance: in light mode their
                // icons must flip dark or they would disappear over bright
                // content, and their color follows the resolved canvas so the
                // frame matches the theme before any content draws.
                val dark = resolveDarkTheme(themeMode)
                val barColor = MaterialTheme.colorScheme.background
                SideEffect {
                    window.statusBarColor = barColor.toArgb()
                    window.navigationBarColor = barColor.toArgb()
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                }
                // Workspace access is real: the Storage Access Framework opens the
                // folder the user picks and only the minimum metadata is persisted.
                val workspacePicker = rememberAndroidWorkspacePicker()
                // Attaching a file reads it through the platform resolver and copies it once into
                // the open workspace, so the chat holds a workspace reference and the agent's own
                // file tools can already read it.
                val attachmentPicker = rememberAndroidAttachmentPicker(
                    workspaces = workspaceManager,
                    materializer = AttachmentMaterializer(
                        reader = AndroidExternalContentReader(applicationContext),
                    ),
                )
                val dependencies = remember(workspacePicker, attachmentPicker, foundation) {
                    val orchestrator = foundation.services.get<AgentOrchestrator>(ServiceKeys.AGENT_ORCHESTRATOR)
                    IdeDependencies(
                        workspaceManager = workspaceManager,
                        workspaceSelection = workspaceSelection,
                        codeIntelligence = foundation.codeIntelligence,
                        workspacePicker = workspacePicker,
                        // The same roots project creation, cloning and deletion use, so
                        // Settings → Workspace classifies a project against the real layout
                        // rather than a path the UI guessed.
                        managedProjectRoots = listOf(
                            agentxProjectRoot.path,
                            legacyManagedProjectsRoot.path,
                        ),
                        attachmentPicker = attachmentPicker,
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
                        // Settings → Tools reads the live catalog and writes the
                        // same enablement the router and the agent core read.
                        tools = foundation.services.get<ToolRegistry>(ServiceKeys.TOOL_REGISTRY),
                        toolPreferences = foundation.toolPreferences,
                        oauthBrowser = IntentOAuthBrowserLauncher(applicationContext),
                        oauthCallbacks = oauthCallbacks,
                        modelRunnerBrowser = modelRunnerBrowser,
                        modelRuntimeOutput = runtimeOutput,
                        projectCloner = projectCloner,
                        appearance = appearance,
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

        /**
         * The one appearance controller for the process. A configuration change
         * recreates this Activity, but retained ViewModel state keeps writing
         * through the instance it was first given — the same reason the Termux
         * and Ubuntu runtimes are process-scoped — so the controller must
         * outlive the Activity instead of being rebuilt in [onCreate].
         */
        @Volatile
        private var appearanceInstance: AppearanceController? = null

        private val appearanceLock = Any()

        /**
         * The process-wide appearance controller, backed by the app-private
         * `SharedPreferences` the rest of the IDE's settings use.
         */
        private fun appearanceController(context: Context): AppearanceController =
            appearanceInstance ?: synchronized(appearanceLock) {
                appearanceInstance
                    ?: AppearanceController(SharedPreferencesThemeModeStore(context.applicationContext))
                        .also { created -> appearanceInstance = created }
            }

        /**
         * Where projects AgentX created before the AgentX folder existed still live, under
         * `filesDir`. Kept only so those projects stay deletable; nothing is created here any more.
         */
        const val LEGACY_PROJECTS_DIRECTORY = "projects"
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
