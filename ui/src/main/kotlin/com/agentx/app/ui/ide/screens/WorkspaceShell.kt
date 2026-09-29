package com.agentx.app.ui.ide.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.agentx.app.ui.ide.IdeDependencies
import com.agentx.app.ui.ide.components.IdeTopBar
import com.agentx.app.ui.ide.state.AgentViewModel
import com.agentx.app.ui.ide.state.GitViewModel
import com.agentx.app.ui.ide.state.IdeViewModelFactory
import com.agentx.app.ui.ide.state.TerminalViewModel
import com.agentx.app.ui.ide.state.WorkspaceViewModel
import com.agentx.app.ui.theme.ForgeCanvas
import com.agentx.app.ui.theme.ForgeMint
import com.agentx.app.ui.theme.ForgeMuted
import com.agentx.app.ui.theme.ForgeSurface

enum class WorkspaceTab(
    val route: String,
    val label: String,
    val subtitle: String,
    val icon: ImageVector,
) {
    FILES("workspace_tab_files", "Files", "File explorer", Icons.Filled.FolderOpen),
    EDITOR("workspace_tab_editor", "Editor", "Code editor", Icons.Filled.Code),
    AGENT("workspace_tab_agent", "AI", "Agent workspace", Icons.Filled.AutoAwesome),
    GIT("workspace_tab_git", "Git", "Source control", Icons.Filled.AccountTree),
    TERMINAL("workspace_tab_terminal", "Terminal", "Command terminal", Icons.Filled.Terminal),
}

private fun NavHostController.navigateToTab(tab: WorkspaceTab) {
    navigate(tab.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
fun WorkspaceShell(
    workspaceId: String,
    dependencies: IdeDependencies,
    onExit: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val workspaceViewModel: WorkspaceViewModel = viewModel(
        key = "workspace-$workspaceId",
        factory = IdeViewModelFactory {
            WorkspaceViewModel(
                workspaceId = workspaceId,
                manager = dependencies.workspaceManager,
                selection = dependencies.workspaceSelection,
                codeIntelligence = dependencies.codeIntelligence,
            )
        },
    )
    val agentViewModel: AgentViewModel = viewModel(
        key = "agent-$workspaceId",
        factory = IdeViewModelFactory {
            AgentViewModel(
                session = dependencies.agent,
                workspaceId = workspaceId,
                selectedFile = {
                    workspaceViewModel.editorState.file?.path
                        ?: dependencies.workspaceSelection.selectedFile()
                },
            )
        },
    )
    val terminalViewModel: TerminalViewModel = viewModel(
        key = "terminal-$workspaceId",
        factory = IdeViewModelFactory {
            TerminalViewModel(
                workspaceId = workspaceId,
                workspaceName = workspaceViewModel.project?.name ?: workspaceId,
                workspaceLocation = {
                    dependencies.workspaceManager.current
                        ?.takeIf { it.workspace.id.value == workspaceId }
                        ?.workspace?.metadata?.displayLocation
                },
                runtime = dependencies.terminalRuntime,
            )
        },
    )
    val gitViewModel: GitViewModel = viewModel(
        key = "git-$workspaceId",
        factory = IdeViewModelFactory { GitViewModel(workspaceId, dependencies.git) },
    )

    val innerNavController = rememberNavController()
    val backStackEntry by innerNavController.currentBackStackEntryAsState()
    val currentTab = WorkspaceTab.entries.firstOrNull { it.route == backStackEntry?.destination?.route }
        ?: WorkspaceTab.FILES

    BackHandler {
        if (currentTab != WorkspaceTab.FILES) {
            innerNavController.navigateToTab(WorkspaceTab.FILES)
        } else {
            onExit()
        }
    }

    workspaceViewModel.pendingOpenPath?.let { path ->
        AlertDialog(
            onDismissRequest = { workspaceViewModel.clearPendingOpen() },
            title = { Text("Discard unsaved changes?") },
            text = {
                Text(
                    "\"${workspaceViewModel.editorState.file?.name ?: "The open file"}\" has " +
                        "unsaved changes. Opening $path will discard them.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        workspaceViewModel.clearPendingOpen()
                        workspaceViewModel.openFile(path)
                        innerNavController.navigateToTab(WorkspaceTab.EDITOR)
                    },
                ) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { workspaceViewModel.clearPendingOpen() }) { Text("Keep editing") }
            },
        )
    }

    Scaffold(
        modifier = modifier,
        containerColor = ForgeCanvas,
        topBar = {
            IdeTopBar(
                title = workspaceViewModel.project?.name ?: "Workspace",
                subtitle = currentTab.subtitle,
                onBack = onExit,
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        bottomBar = {
            WorkspaceBottomBar(
                current = currentTab,
                onSelect = { tab -> innerNavController.navigateToTab(tab) },
            )
        },
    ) { padding ->
        NavHost(
            navController = innerNavController,
            startDestination = WorkspaceTab.FILES.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(WorkspaceTab.FILES.route) {
                FilesScreen(
                    state = workspaceViewModel.filesState,
                    onToggle = workspaceViewModel::toggleDirectory,
                    onOpenFile = { path ->
                        if (workspaceViewModel.needsDiscardConfirmation(path)) {
                            workspaceViewModel.stagePendingOpen(path)
                        } else {
                            workspaceViewModel.openFile(path)
                            innerNavController.navigateToTab(WorkspaceTab.EDITOR)
                        }
                    },
                    onRetry = workspaceViewModel::loadWorkspace,
                    onRetryDirectory = workspaceViewModel::retryDirectory,
                    onNavigateUp = workspaceViewModel::navigateUp,
                )
            }
            composable(WorkspaceTab.EDITOR.route) {
                EditorScreen(
                    state = workspaceViewModel.editorState,
                    onEdit = workspaceViewModel::editDraft,
                    onSave = workspaceViewModel::save,
                    onBrowseFiles = { innerNavController.navigateToTab(WorkspaceTab.FILES) },
                    dismissStatus = workspaceViewModel::dismissEditorStatus,
                    structure = workspaceViewModel.structureState,
                    onCursorMoved = workspaceViewModel::onCursorMoved,
                )
            }
            composable(WorkspaceTab.AGENT.route) {
                AgentScreen(viewModel = agentViewModel)
            }
            composable(WorkspaceTab.GIT.route) {
                GitScreen(viewModel = gitViewModel)
            }
            composable(WorkspaceTab.TERMINAL.route) {
                TerminalScreen(viewModel = terminalViewModel)
            }
        }
    }
}

@Composable
private fun WorkspaceBottomBar(
    current: WorkspaceTab,
    onSelect: (WorkspaceTab) -> Unit,
) {
    NavigationBar(containerColor = ForgeSurface) {
        WorkspaceTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = current == tab,
                onClick = { onSelect(tab) },
                icon = { Icon(tab.icon, contentDescription = tab.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = ForgeMint,
                    selectedTextColor = ForgeMint,
                    indicatorColor = ForgeMint.copy(alpha = 0.16f),
                    unselectedIconColor = ForgeMuted,
                    unselectedTextColor = ForgeMuted,
                ),
            )
        }
    }
}
