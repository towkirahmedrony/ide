package com.agentx.app.ui.ide

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.agentx.app.core.architecture.LayerDescriptor
import com.agentx.app.core.health.HealthReport
import com.agentx.app.integrations.connection.ConnectionType
import com.agentx.app.ui.ide.components.IdeEmptyState
import com.agentx.app.ui.ide.components.IdeTopBar
import com.agentx.app.ui.ide.nav.IdeDestinations
import com.agentx.app.ui.ide.screens.AboutScreen
import com.agentx.app.ui.ide.screens.ConnectionEditorScreen
import com.agentx.app.ui.ide.screens.ConnectionsScreen
import com.agentx.app.ui.ide.screens.DeveloperScreen
import com.agentx.app.ui.ide.screens.HomeScreen
import com.agentx.app.ui.ide.screens.ModelEditorScreen
import com.agentx.app.ui.ide.screens.ModelRunnerScreen
import com.agentx.app.ui.ide.screens.ModelsScreen
import com.agentx.app.ui.ide.screens.SettingsDetailScreen
import com.agentx.app.ui.ide.screens.ServiceDetailsScreen
import com.agentx.app.ui.ide.screens.SettingsScreen
import com.agentx.app.ui.ide.screens.SettingsSection
import com.agentx.app.ui.ide.screens.WorkspaceShell
import com.agentx.app.ui.ide.state.ConnectionEditorViewModel
import com.agentx.app.ui.ide.state.ConnectionsViewModel
import com.agentx.app.ui.ide.state.HomeViewModel
import com.agentx.app.ui.ide.state.IdeViewModelFactory
import com.agentx.app.ui.ide.state.ModelEditorViewModel
import com.agentx.app.ui.ide.state.ModelRunnerViewModel
import com.agentx.app.ui.ide.state.ModelsViewModel
import com.agentx.app.ui.ide.state.OAuthCallbackViewModel
import com.agentx.app.ui.theme.ForgeCanvas

/**
 * Root of the IDE shell. Owns the navigation graph and hands the swappable
 * data sources in [IdeDependencies] to each screen's state holder.
 */
@Composable
fun ForgeIdeApp(
    dependencies: IdeDependencies,
    modifier: Modifier = Modifier,
    appName: String = "AgentX",
    version: String = "0.1.0",
    layers: List<LayerDescriptor> = emptyList(),
    health: HealthReport? = null,
) {
    val navController = rememberNavController()

    // A provider redirect is completed above the navigation graph, so it is handled
    // wherever the user happens to be, and then returns them to the service they
    // were connecting. The redirect itself carries only a code; state and PKCE are
    // validated by the Connection Manager, and a replayed code cannot reach here.
    val oauthCallbackViewModel: OAuthCallbackViewModel = viewModel(
        key = "oauth-callback",
        factory = IdeViewModelFactory {
            OAuthCallbackViewModel(dependencies.connectionManager, dependencies.oauthCallbacks)
        },
    )
    LaunchedEffect(oauthCallbackViewModel.returnToType) {
        val type = oauthCallbackViewModel.returnToType ?: return@LaunchedEffect
        navController.navigate(IdeDestinations.serviceDetails(type.name)) { launchSingleTop = true }
        oauthCallbackViewModel.acknowledgeReturn()
    }
    oauthCallbackViewModel.message?.let { text ->
        AlertDialog(
            onDismissRequest = oauthCallbackViewModel::dismissMessage,
            title = { Text("Authorization") },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = oauthCallbackViewModel::dismissMessage) { Text("OK") }
            },
        )
    }

    NavHost(
        navController = navController,
        startDestination = IdeDestinations.HOME,
        modifier = modifier,
    ) {
        composable(IdeDestinations.HOME) {
            val homeViewModel: HomeViewModel = viewModel(
                factory = IdeViewModelFactory {
                    HomeViewModel(dependencies.workspaceManager, dependencies.workspacePicker)
                },
            )
            // Reopen where the user left off once per session.
            LaunchedEffect(Unit) {
                homeViewModel.restoreLastWorkspace { workspaceId ->
                    navController.navigate(IdeDestinations.workspace(workspaceId))
                }
            }
            HomeScreen(
                appName = appName,
                viewModel = homeViewModel,
                onOpenWorkspace = { workspaceId ->
                    navController.navigate(IdeDestinations.workspace(workspaceId))
                },
                onOpenSettings = { navController.navigate(IdeDestinations.SETTINGS) },
            )
        }

        composable(
            route = IdeDestinations.WORKSPACE,
            arguments = listOf(navArgument(IdeDestinations.ARG_WORKSPACE_ID) { type = NavType.StringType }),
        ) { entry ->
            val workspaceId = entry.arguments?.getString(IdeDestinations.ARG_WORKSPACE_ID).orEmpty()
            WorkspaceShell(
                workspaceId = workspaceId,
                dependencies = dependencies,
                onExit = { navController.popBackStack() },
                onOpenSettings = { navController.navigate(IdeDestinations.SETTINGS) },
            )
        }

        composable(IdeDestinations.SETTINGS) {
            SettingsScreen(
                appName = appName,
                onBack = { navController.popBackStack() },
                onSelect = { section ->
                    val route = when (section) {
                        // The Model section is the Model Manager, not a placeholder.
                        SettingsSection.MODEL -> IdeDestinations.MODELS
                        SettingsSection.CONNECTIONS -> IdeDestinations.CONNECTIONS
                        SettingsSection.ABOUT -> IdeDestinations.ABOUT
                        else -> IdeDestinations.settingsDetail(section.id)
                    }
                    navController.navigate(route)
                },
            )
        }

        composable(
            route = IdeDestinations.SETTINGS_DETAIL,
            arguments = listOf(navArgument(IdeDestinations.ARG_SECTION_ID) { type = NavType.StringType }),
        ) { entry ->
            val section = SettingsSection.fromId(
                entry.arguments?.getString(IdeDestinations.ARG_SECTION_ID),
            ) ?: SettingsSection.MODEL
            SettingsDetailScreen(section = section, onBack = { navController.popBackStack() })
        }

        composable(IdeDestinations.MODELS) {
            val modelsViewModel: ModelsViewModel = viewModel(
                factory = IdeViewModelFactory { ModelsViewModel(dependencies.modelManager) },
            )
            val state by modelsViewModel.state.collectAsState()
            ModelsScreen(
                state = state,
                busyPresetId = modelsViewModel.busyPresetId,
                message = modelsViewModel.message,
                credentialsPersistent = modelsViewModel.credentialsPersistent,
                onBack = { navController.popBackStack() },
                onAddModel = { navController.navigate(IdeDestinations.modelEditor()) },
                onEdit = { id -> navController.navigate(IdeDestinations.modelEditor(id)) },
                onUse = modelsViewModel::use,
                onStart = modelsViewModel::start,
                onStop = modelsViewModel::stop,
                onReconnect = modelsViewModel::reconnect,
                onCheckHealth = modelsViewModel::checkHealth,
                onOpenRunner = { id -> navController.navigate(IdeDestinations.modelRunner(id)) },
                onDelete = modelsViewModel::delete,
                onRefresh = modelsViewModel::refresh,
                onDismissMessage = modelsViewModel::dismissMessage,
            )
        }

        composable(
            route = IdeDestinations.MODEL_EDITOR,
            arguments = listOf(navArgument(IdeDestinations.ARG_PRESET_ID) { type = NavType.StringType }),
        ) { entry ->
            val raw = entry.arguments?.getString(IdeDestinations.ARG_PRESET_ID)
            val presetId = raw?.takeIf { it.isNotBlank() && it != IdeDestinations.NEW_MODEL }
            val editorViewModel: ModelEditorViewModel = viewModel(
                key = "model-editor-${raw.orEmpty()}",
                factory = IdeViewModelFactory {
                    ModelEditorViewModel(dependencies.modelManager, presetId)
                },
            )
            val editorState = editorViewModel.state
            LaunchedEffect(editorState.saved) {
                if (editorState.saved) navController.popBackStack()
            }
            ModelEditorScreen(
                state = editorState,
                onBack = { navController.popBackStack() },
                onEdit = editorViewModel::edit,
                onSave = editorViewModel::save,
                onRemoveCredential = editorViewModel::removeStoredCredential,
            )
        }

        composable(
            route = IdeDestinations.MODEL_RUNNER,
            arguments = listOf(navArgument(IdeDestinations.ARG_PRESET_ID) { type = NavType.StringType }),
        ) { entry ->
            val presetId = entry.arguments?.getString(IdeDestinations.ARG_PRESET_ID).orEmpty()
            val runnerViewModel: ModelRunnerViewModel = viewModel(
                key = "model-runner-$presetId",
                factory = IdeViewModelFactory {
                    ModelRunnerViewModel(
                        manager = dependencies.modelManager,
                        browser = dependencies.modelRunnerBrowser,
                        runtimeOutput = dependencies.modelRuntimeOutput,
                        presetId = presetId,
                    )
                },
            )
            val modelState by runnerViewModel.state.collectAsState()
            val blockedHost by runnerViewModel.blockedNavigation.collectAsState()
            val capturedLines by runnerViewModel.capturedLines.collectAsState()
            val preset = modelState.presets.firstOrNull { it.id == presetId }

            if (preset == null) {
                // The preset was deleted (or never existed) while this route was open.
                Scaffold(
                    containerColor = ForgeCanvas,
                    topBar = { IdeTopBar(title = "Model Runner", onBack = { navController.popBackStack() }) },
                ) { padding ->
                    IdeEmptyState(
                        icon = Icons.Filled.Memory,
                        title = "Model not found",
                        message = "This model is not saved any more.",
                        modifier = Modifier.padding(padding).padding(top = 24.dp),
                    )
                }
                return@composable
            }

            val notebookUrl = preset.colab?.notebookUrl.orEmpty()
            val hasNotebook = notebookUrl.isNotBlank()

            LaunchedEffect(presetId) { runnerViewModel.onSessionAttached(true) }

            ModelRunnerScreen(
                presetName = preset.displayName,
                notebookUrl = notebookUrl,
                status = modelState.status(presetId),
                browserAvailable = hasNotebook && runnerViewModel.browserAvailable,
                browserFallbackTitle = if (hasNotebook) {
                    "No embedded browser here"
                } else {
                    "Nothing to manage in a browser"
                },
                browserFallbackMessage = if (hasNotebook) {
                    "The platform did not provide a WebView, so the runtime cannot be managed from " +
                        "inside the app. Open the notebook in your own browser instead."
                } else {
                    "This model does not run in a notebook, so there is no runtime session to manage. " +
                        "Start it wherever it lives, then reconnect."
                },
                externalActionLabel = if (hasNotebook) "Open in browser" else null,
                onOpenExternally = if (hasNotebook) {
                    { runnerViewModel.openExternally(notebookUrl) }
                } else {
                    null
                },
                blockedHost = blockedHost,
                capturedLines = capturedLines.size,
                busy = runnerViewModel.busy,
                message = runnerViewModel.message,
                onBack = { navController.popBackStack() },
                onReconnect = runnerViewModel::reconnect,
                onCheck = runnerViewModel::check,
                onStop = runnerViewModel::stop,
                onCaptureOutput = runnerViewModel::captureOutput,
                onClearOutput = runnerViewModel::clearOutput,
                onDismissBlocked = runnerViewModel::clearBlocked,
                onDismissMessage = runnerViewModel::dismissMessage,
                onCreateView = { runnerViewModel.createView(notebookUrl, preset.tunnel.marker) },
                onSessionDetached = { runnerViewModel.onSessionAttached(false) },
            )
        }

        composable(IdeDestinations.CONNECTIONS) {
            val connectionsViewModel: ConnectionsViewModel = viewModel(
                key = "connections",
                factory = IdeViewModelFactory {
                    ConnectionsViewModel(
                        manager = dependencies.connectionManager,
                        browser = dependencies.oauthBrowser,
                        setup = dependencies.integrationSetup,
                    )
                },
            )
            val connectionsState by connectionsViewModel.state.collectAsState()
            ConnectionsScreen(
                state = connectionsState,
                providers = connectionsViewModel.providers,
                descriptors = connectionsViewModel.descriptors,
                tools = connectionsViewModel.tools,
                busyKey = connectionsViewModel.busyKey,
                message = connectionsViewModel.message,
                credentialsPersistent = connectionsViewModel.credentialsPersistent,
                setupOf = { type, connection -> connectionsViewModel.setupOf(type, connection) },
                onBack = { navController.popBackStack() },
                onOpenService = { type -> navController.navigate(IdeDestinations.serviceDetails(type.name)) },
                onConnect = connectionsViewModel::connect,
                onReconnect = connectionsViewModel::reconnect,
                onCancelAuthorization = connectionsViewModel::cancelAuthorization,
                onDisconnect = connectionsViewModel::disconnect,
                onDismissMessage = connectionsViewModel::dismissMessage,
                onRefresh = connectionsViewModel::refresh,
            )
        }

        composable(
            route = IdeDestinations.SERVICE_DETAILS,
            arguments = listOf(navArgument(IdeDestinations.ARG_SERVICE_TYPE) { type = NavType.StringType }),
        ) { entry ->
            val rawType = entry.arguments?.getString(IdeDestinations.ARG_SERVICE_TYPE)
            val type = ConnectionType.entries.firstOrNull { it.name == rawType } ?: ConnectionType.GITHUB
            // A redirect can land here before the list is refreshed; the manager is the
            // source of truth, so the details screen always reads live state.
            val detailsViewModel: ConnectionsViewModel = viewModel(
                key = "connections",
                factory = IdeViewModelFactory {
                    ConnectionsViewModel(
                        manager = dependencies.connectionManager,
                        browser = dependencies.oauthBrowser,
                        setup = dependencies.integrationSetup,
                    )
                },
            )
            val detailsState by detailsViewModel.state.collectAsState()
            val connection = detailsViewModel.connectionOf(type, detailsState)
            ServiceDetailsScreen(
                type = type,
                availability = detailsViewModel.availabilityOf(type),
                descriptor = detailsViewModel.descriptorOf(type),
                connection = connection,
                tools = detailsViewModel.toolsOf(type),
                setup = detailsViewModel.setupOf(type, connection),
                guide = detailsViewModel.guideOf(type),
                busy = detailsViewModel.isBusy(connection?.id?.value ?: type.name),
                authorizing = detailsViewModel.isAuthorizing(connection),
                setupBusy = detailsViewModel.setupBusy,
                onBack = { navController.popBackStack() },
                onConnect = { detailsViewModel.connect(type) },
                onReconnect = { connection?.let { detailsViewModel.reconnect(it.id.value) } },
                onCancelAuthorization = { connection?.let { detailsViewModel.cancelAuthorization(it.id.value) } },
                onDisconnect = { connection?.let { detailsViewModel.disconnect(it.id.value) } },
                onVerify = { connection?.let { detailsViewModel.verify(it.id.value) } },
                onSaveSetup = { clientId, broker ->
                    detailsViewModel.saveSetup(type, clientId, broker)
                },
                onClearSetup = { detailsViewModel.clearSetup(type) },
                onManage = {
                    navController.navigate(
                        IdeDestinations.connectionEditor(connection?.id?.value, type.name),
                    )
                },
            )
        }

        composable(
            route = IdeDestinations.CONNECTION_EDITOR,
            arguments = listOf(navArgument(IdeDestinations.ARG_CONNECTION_ID) { type = NavType.StringType }),
        ) { entry ->
            val raw = entry.arguments?.getString(IdeDestinations.ARG_CONNECTION_ID)
            val connectionId = raw?.takeIf { it.isNotBlank() && it != IdeDestinations.NEW_CONNECTION }
            val editorViewModel: ConnectionEditorViewModel = viewModel(
                key = "connection-editor-${raw.orEmpty()}",
                factory = IdeViewModelFactory {
                    ConnectionEditorViewModel(
                        manager = dependencies.connectionManager,
                        connectionId = connectionId,
                        browser = dependencies.oauthBrowser,
                    )
                },
            )
            val editorState = editorViewModel.state
            LaunchedEffect(editorState.saved) {
                if (editorState.saved) navController.popBackStack()
            }
            ConnectionEditorScreen(
                state = editorState,
                onBack = { navController.popBackStack() },
                onEdit = editorViewModel::edit,
                onSave = editorViewModel::save,
                onRemoveCredential = editorViewModel::removeStoredCredential,
            )
        }

        composable(IdeDestinations.ABOUT) {
            AboutScreen(
                appName = appName,
                version = version,
                onBack = { navController.popBackStack() },
                onOpenDeveloper = { navController.navigate(IdeDestinations.DEVELOPER) },
            )
        }

        composable(IdeDestinations.DEVELOPER) {
            DeveloperScreen(
                layers = layers,
                health = health,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
