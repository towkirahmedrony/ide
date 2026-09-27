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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.forge.ide.ui.ide.components.IdeCard
import dev.forge.ide.ui.ide.components.IdeEmptyState
import dev.forge.ide.ui.ide.components.IdeLabelValue
import dev.forge.ide.ui.ide.components.IdeSpacer
import dev.forge.ide.ui.ide.components.IdeStatusPill
import dev.forge.ide.ui.ide.components.IdeTopBar
import dev.forge.ide.ui.ide.model.ProjectSummary
import dev.forge.ide.ui.ide.state.HomeViewModel
import dev.forge.ide.ui.theme.ForgeCanvas
import dev.forge.ide.ui.theme.ForgeInk
import dev.forge.ide.ui.theme.ForgeMint
import dev.forge.ide.ui.theme.ForgeMuted
import dev.forge.ide.ui.theme.ForgePeriwinkle

@Composable
fun HomeScreen(
    appName: String,
    viewModel: HomeViewModel,
    onOpenWorkspace: (String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = viewModel.uiState

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
                    message = "Open a folder on this device to start using the IDE.",
                    actionLabel = "Open Project",
                    onAction = { viewModel.pickWorkspace(onOpenWorkspace) },
                )

                else -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    state.projects.forEach { project ->
                        ProjectCard(
                            project = project,
                            onClick = { viewModel.openRecent(project.id, onOpenWorkspace) },
                            onForget = { viewModel.forget(project.id) },
                        )
                    }
                }
            }

            IdeSpacer(16)
        }
    }
}

@Composable
private fun HeroCard(
    appName: String,
    opening: Boolean,
    onOpenProject: () -> Unit,
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
            text = "Open a project folder to reach the file explorer, editor, AI agent, git and terminal.",
            style = MaterialTheme.typography.bodyMedium,
            color = ForgeMuted,
        )
        IdeSpacer(20)
        Button(
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

@Composable
private fun ProjectCard(
    project: ProjectSummary,
    onClick: () -> Unit,
    onForget: () -> Unit,
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
            IconButton(onClick = onForget) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Remove from recent",
                    tint = ForgeMuted,
                )
            }
        }
        IdeSpacer(8)
        IdeLabelValue(label = "Last opened", value = project.lastOpenedLabel)
    }
}
