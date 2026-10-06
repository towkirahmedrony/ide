package com.agentx.app.ui.ide.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agentx.app.ui.ide.components.IdeCard
import com.agentx.app.ui.ide.components.IdeEmptyState
import com.agentx.app.ui.ide.components.IdeLabelValue
import com.agentx.app.ui.ide.components.IdeSpacer
import com.agentx.app.ui.ide.components.IdeStatusPill
import com.agentx.app.ui.ide.components.IdeTopBar
import com.agentx.app.ui.ide.model.ProjectSummary
import com.agentx.app.ui.ide.state.HomeViewModel
import com.agentx.app.ui.theme.ForgeCanvas
import com.agentx.app.ui.theme.ForgeInk
import com.agentx.app.ui.theme.ForgeMint
import com.agentx.app.ui.theme.ForgeMuted
import com.agentx.app.ui.theme.ForgePeriwinkle

@Composable
fun HomeScreen(
    appName: String,
    viewModel: HomeViewModel,
    onOpenWorkspace: (String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = viewModel.uiState
    var showCreateDialog by remember { mutableStateOf(false) }

    if (showCreateDialog) {
        CreateProjectDialog(
            creating = state.creating,
            error = state.createError,
            onDismiss = {
                if (!state.creating) {
                    showCreateDialog = false
                    viewModel.clearCreateError()
                }
            },
            onConfirm = { name ->
                viewModel.createProject(name) { workspaceId ->
                    showCreateDialog = false
                    onOpenWorkspace(workspaceId)
                }
            },
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = ForgeCanvas,
        topBar = {
            IdeTopBar(
                title = appName,
                subtitle = "Android-first agentic IDE",
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            HeroCard(
                appName = appName,
                opening = state.opening,
                onOpenProject = { viewModel.pickWorkspace(onOpenWorkspace) },
                onCreateProject = {
                    viewModel.clearCreateError()
                    showCreateDialog = true
                },
            )

            IdeSpacer(24)

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Recent projects",
                    style = MaterialTheme.typography.titleMedium,
                    color = ForgeInk,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = viewModel::refresh) { Text("Refresh") }
            }
            IdeSpacer(8)

            when {
                state.loading -> Box(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = ForgeMint)
                }

                state.error != null -> IdeEmptyState(
                    icon = Icons.Outlined.Folder,
                    title = "Could not open workspace",
                    message = state.error,
                    actionLabel = "Retry",
                    onAction = viewModel::refresh,
                )

                state.isEmpty -> IdeEmptyState(
                    icon = Icons.Outlined.Folder,
                    title = "No projects yet",
                    message = "Open a folder on this device or create a new project to start using the IDE.",
                    actionLabel = "Open Project",
                    onAction = { viewModel.pickWorkspace(onOpenWorkspace) },
                )

                else -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    state.projects.forEach { project ->
                        ProjectCard(
                            project = project,
                            onClick = { viewModel.openRecent(project.id, onOpenWorkspace) },
                            // Deleting is destructive and never happens on the tap itself: this only
                            // raises the confirmation below.
                            onDelete = { viewModel.requestDelete(project) },
                        )
                    }
                }
            }

            IdeSpacer(16)
        }
    }

    // The confirmation for a project delete. It shows the project's name so the user can see
    // exactly what would go, and it stays up (with the reason) if the delete could not be
    // completed, instead of the project silently staying in the list.
    state.pendingDelete?.let { project ->
        AlertDialog(
            onDismissRequest = viewModel::cancelDelete,
            title = { Text("Delete project?") },
            text = {
                Column {
                    Text(
                        text = "\"${project.name}\" will be removed from AgentX, along with the " +
                            "data AgentX created for it.",
                    )
                    IdeSpacer(8)
                    Text(
                        text = "The folder you opened stays on your device, and other projects " +
                            "are not affected.",
                        style = MaterialTheme.typography.bodySmall,
                        color = ForgeMuted,
                    )
                    state.deleteError?.let { message ->
                        IdeSpacer(8)
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = viewModel::confirmDelete,
                    enabled = !state.deleting,
                ) { Text(if (state.deleting) "Deleting…" else "Delete") }
            },
            dismissButton = {
                TextButton(
                    onClick = viewModel::cancelDelete,
                    enabled = !state.deleting,
                ) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun HeroCard(
    appName: String,
    opening: Boolean,
    onOpenProject: () -> Unit,
    onCreateProject: () -> Unit,
) {
    IdeCard {
        Text(
            text = appName.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = ForgeMint,
        )
        IdeSpacer(8)
        Text(
            text = "Build with an agent on Android",
            style = MaterialTheme.typography.headlineLarge,
            color = ForgeInk,
        )
        IdeSpacer(8)
        Text(
            text = "Open a project folder or create a new one to reach the file explorer, editor, AI agent, git and terminal.",
            style = MaterialTheme.typography.bodyMedium,
            color = ForgeMuted,
        )
        IdeSpacer(20)
        Button(
            onClick = onCreateProject,
            enabled = !opening,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Create New Project")
        }
        IdeSpacer(10)
        OutlinedButton(
            onClick = onOpenProject,
            enabled = !opening,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (opening) "Opening…" else "Open Project")
        }
    }
}

/**
 * Asks only for a project name, then hands it to the workspace runtime. The directory is created
 * in AgentX-managed storage and opened as the active project — no template, language or Git setup.
 */
@Composable
private fun CreateProjectDialog(
    creating: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val errorText: (@Composable () -> Unit)? = error?.let { message -> { Text(message) } }

    AlertDialog(
        onDismissRequest = { if (!creating) onDismiss() },
        title = { Text("Create New Project") },
        text = {
            Column {
                Text(
                    text = "Name your project. AgentX creates an empty folder in its managed " +
                        "project storage and opens it as /workspace.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ForgeMuted,
                )
                IdeSpacer(12)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    enabled = !creating,
                    isError = error != null,
                    label = { Text("Project name") },
                    supportingText = errorText,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name) },
                enabled = !creating && name.isNotBlank(),
            ) {
                Text(if (creating) "Creating…" else "Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !creating) { Text("Cancel") }
        },
    )
}

@Composable
private fun ProjectCard(
    project: ProjectSummary,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    IdeCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(ForgePeriwinkle.copy(alpha = 0.14f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.FolderOpen, contentDescription = null, tint = ForgePeriwinkle)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = project.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = ForgeInk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = project.rootPath,
                    style = MaterialTheme.typography.labelSmall,
                    color = ForgeMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            project.branch?.let { IdeStatusPill(it, ForgeMint) }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Delete project",
                    tint = ForgeMuted,
                )
            }
        }
        IdeSpacer(8)
        IdeLabelValue(label = "Last opened", value = project.lastOpenedLabel)
    }
}
