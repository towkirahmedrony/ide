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
import androidx.compose.runtime.LaunchedEffect
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
                // Chat is project-scoped: this workspace is the chat's owning project.
                projectId = workspaceId,
                selectedFile = {
                    workspaceViewModel.editorState.file?.path
                        ?: dependencies.workspaceSelection.selectedFile()
                },
                // The Agent header shows which model backs the agent.
                modelId = { dependencies.modelManager.activeConfig()?.model?.takeIf { it.isNotBlank() } },
                // Files the user attaches are materialised into this workspace, and the skills the
                // composer offers are the ones this chat's role could actually use.
                attachmentPicker = dependencies.attachmentPicker,
                skills = dependencies.skills,
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
                // The opaque handle of this workspace, so the embedded Ubuntu runtime can bind
                // the project at /workspace: a real path as-is, or a SAF tree resolved to its
                // original phone-storage path (never a copy). A content:// URI is never passed
                // to PRoot.
                workspaceHandle = {
                    dependencies.workspaceManager.current
                        ?.takeIf { it.workspace.id.value == workspaceId }
                        ?.let { dependencies.workspaceManager.currentHandle }
                },
                runtime = dependencies.terminalRuntime,
                developerRuntime = dependencies.developerRuntime,
            )
        },
    )
    val gitViewModel: GitViewModel = viewModel(
        key = "git-$workspaceId",
        factory = IdeViewModelFactory { GitViewModel(workspaceId, dependencies.git) },
    )

    // A workspace can carry its own `skills/<id>/SKILL.md` folders, and they are only
    // readable once this workspace is the open one. Discovery re-runs when the shell
    // is entered for a workspace; enablement and role assignment are unchanged by it.
    LaunchedEffect(workspaceId) {
        runCatching { dependencies.skills.refresh() }
    }

    val innerNavController = rememberNavController()
    val backStackEntry by innerNavController.currentBackStackEntryAsState()
    val currentTab = WorkspaceTab.entries.firstOrNull { it.route == backStackEntry?.destination?.route }
        ?: WorkspaceTab.FILES

    // Entering the browser or the editor re-reads the open folders, so a change made while the
    // user was in the terminal — `touch`, `rm`, `git checkout` — is on screen when they return.
    // Entering Git re-queries the repository, so it always reflects the active project.
    LaunchedEffect(currentTab) {
        when (currentTab) {
            WorkspaceTab.FILES, WorkspaceTab.EDITOR -> workspaceViewModel.reload()
            WorkspaceTab.GIT -> gitViewModel.refresh()
            else -> Unit
        }
    }

    BackHandler {
        if (currentTab != WorkspaceTab.FILES) {
            innerNavController.navigateToTab(WorkspaceTab.FILES)
        } else {
            onExit()
        }
    }

    // A save that would overwrite a file changed on disk is stopped and asked about here
    // instead, so the newer content is never silently clobbered.
    if (workspaceViewModel.editorState.saveConflict) {
        AlertDialog(
            onDismissRequest = workspaceViewModel::dismissSaveConflict,
            title = { Text("File changed on disk") },
            text = {
                Text(
                    "\"${workspaceViewModel.editorState.file?.name ?: "This file"}\" was modified " +
                        "outside the editor since you opened it. Saving now will overwrite those changes.",
                )
            },
            confirmButton = {
                TextButton(onClick = { workspaceViewModel.save(overwrite = true) }) { Text("Overwrite") }
            },
            dismissButton = {
                TextButton(onClick = workspaceViewModel::dismissSaveConflict) { Text("Keep editing") }
            },
        )
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

    // The Agent tab is a dedicated full-screen workspace: it renders its own
    // compact header (back, sessions, workspace/model context, settings), so the
    // global top bar would only duplicate controls and waste vertical space.
    // Every other tab keeps the global header exactly as it was.
    val agentTabActive = currentTab == WorkspaceTab.AGENT

    Scaffold(
        modifier = modifier,
        containerColor = ForgeCanvas,
        topBar = {
            if (!agentTabActive) {
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
            }
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
                    onRefresh = workspaceViewModel::reload,
                    onCreateFile = workspaceViewModel::createFile,
                    onCreateDirectory = workspaceViewModel::createDirectory,
                    onRename = workspaceViewModel::rename,
                    onDelete = workspaceViewModel::delete,
                    onDismissMessage = workspaceViewModel::dismissFilesMessage,
                )
            }
            composable(WorkspaceTab.EDITOR.route) {
                EditorScreen(
                    state = workspaceViewModel.editorState,
                    onEdit = workspaceViewModel::editDraft,
                    onSave = { workspaceViewModel.save() },
                    onBrowseFiles = { innerNavController.navigateToTab(WorkspaceTab.FILES) },
                    dismissStatus = workspaceViewModel::dismissEditorStatus,
                    structure = workspaceViewModel.structureState,
                    onCursorMoved = workspaceViewModel::onCursorMoved,
                    onReloadFromDisk = workspaceViewModel::reloadOpenFile,
                )
            }
            composable(WorkspaceTab.AGENT.route) {
                AgentScreen(
                    viewModel = agentViewModel,
                    workspaceName = workspaceViewModel.project?.name,
                    onBack = onExit,
                    onOpenSettings = onOpenSettings,
                )
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
