package dev.forge.ide.ui.ide.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Folder
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
import dev.forge.ide.ui.ide.components.IdeCard
import dev.forge.ide.ui.ide.components.IdeEmptyState
import dev.forge.ide.ui.ide.components.IdeLabelValue
import dev.forge.ide.ui.ide.components.IdeSectionLabel
import dev.forge.ide.ui.ide.components.IdeSpacer
import dev.forge.ide.ui.ide.components.IdeStatusPill
import dev.forge.ide.ui.ide.components.IdeTopBar
import dev.forge.ide.ui.ide.model.ProjectSummary
import dev.forge.ide.ui.ide.state.HomeUiState
import dev.forge.ide.ui.ide.state.HomeViewModel
import dev.forge.ide.ui.theme.ForgeCanvas
import dev.forge.ide.ui.theme.ForgeInk
import dev.forge.ide.ui.theme.ForgeMint
import dev.forge.ide.ui.theme.ForgeMuted
import dev.forge.ide.ui.theme.ForgePeriwinkle
import androidx.compose.material3.AlertDialog

@Composable
fun HomeScreen(
    appName: String,
    viewModel: HomeViewModel,
    onOpenWorkspace: (String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = viewModel.uiState
    var showNewWorkspace by remember { mutableStateOf(false) }

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
                hasProjects = state.projects.isNotEmpty(),
                onOpenProject = { state.projects.firstOrNull()?.let { onOpenWorkspace(it.id) } },
                onNewWorkspace = { showNewWorkspace = true },
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
                    title = "Could not load projects",
                    message = state.error,
                    actionLabel = "Retry",
                    onAction = viewModel::refresh,
                )

                state.isEmpty -> IdeEmptyState(
                    icon = Icons.Outlined.Folder,
                    title = "No projects yet",
                    message = "Create a workspace or open an existing one to start using the IDE.",
                    actionLabel = "New Workspace",
                    onAction = { showNewWorkspace = true },
                )

                else -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    state.projects.forEach { project ->
                        ProjectCard(project = project, onClick = { onOpenWorkspace(project.id) })
                    }
                }
            }

            IdeSpacer(16)
        }
    }

    if (showNewWorkspace) {
        NewWorkspaceDialog(
            onDismiss = { showNewWorkspace = false },
            onCreate = { name ->
                showNewWorkspace = false
                viewModel.createWorkspace(name) { project -> onOpenWorkspace(project.id) }
            },
        )
    }
}

@Composable
private fun HeroCard(
    appName: String,
    hasProjects: Boolean,
    onOpenProject: () -> Unit,
    onNewWorkspace: () -> Unit,
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
            text = "Open a project to reach the file explorer, editor, AI agent, git and terminal.",
            style = MaterialTheme.typography.bodyMedium,
            color = ForgeMuted,
        )
        IdeSpacer(20)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onOpenProject,
                enabled = hasProjects,
                modifier = Modifier.weight(1f),
            ) {
                Text("Open Project")
            }
            OutlinedButton(
                onClick = onNewWorkspace,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("New Workspace")
            }
        }
    }
}

@Composable
private fun ProjectCard(project: ProjectSummary, onClick: () -> Unit) {
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
        }
        IdeSpacer(12)
        IdeLabelValue(label = "Files", value = project.fileCount.toString())
        IdeLabelValue(label = "Last opened", value = project.lastOpenedLabel)
    }
}

@Composable
private fun NewWorkspaceDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New workspace") },
        text = {
            Column {
                Text(
                    text = "Give the workspace a name. A real filesystem-backed workspace arrives with the Workspace Runtime.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ForgeMuted,
                )
                IdeSpacer(12)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Name") },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name.ifBlank { "untitled" }) },
            ) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
