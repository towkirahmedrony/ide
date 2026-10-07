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
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agentx.app.ui.ide.components.IdeCard
import com.agentx.app.ui.ide.components.IdeDivider
import com.agentx.app.ui.ide.components.IdeEmptyState
import com.agentx.app.ui.ide.components.IdeLabelValue
import com.agentx.app.ui.ide.components.IdeSectionLabel
import com.agentx.app.ui.ide.components.IdeSpacer
import com.agentx.app.ui.ide.components.IdeStatusPill
import com.agentx.app.ui.ide.components.IdeTopBar
import com.agentx.app.ui.ide.model.ProjectSummary
import com.agentx.app.ui.ide.model.WorkspaceGitInfo
import com.agentx.app.ui.ide.model.WorkspaceInfo
import com.agentx.app.ui.ide.model.WorkspaceStorageKind
import com.agentx.app.ui.ide.state.WorkspaceSettingsViewModel
import com.agentx.app.ui.theme.ForgeCanvas
import com.agentx.app.ui.theme.ForgeDanger
import com.agentx.app.ui.theme.ForgeInk
import com.agentx.app.ui.theme.ForgeMint
import com.agentx.app.ui.theme.ForgeMuted
import com.agentx.app.ui.theme.ForgePeriwinkle

/**
 * Settings → Workspace.
 *
 * The page manages the workspace AgentX currently has open: it names it, explains where its files live
 * and how the agent reaches them, and exposes the workspace operations the runtime already supports
 * (switch, open, create, refresh, remove, delete). Everything read-only is shown as information; no
 * control here is decorative, and the destructive ones are gated behind an explicit confirmation that
 * says whether files are affected.
 */
@Composable
fun WorkspaceSettingsScreen(
    viewModel: WorkspaceSettingsViewModel,
    onBack: () -> Unit,
    onOpenWorkspace: (String) -> Unit,
    onOpenSkills: () -> Unit,
    onWorkspaceRemoved: () -> Unit,
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

    state.pendingForget?.let { info ->
        AlertDialog(
            onDismissRequest = viewModel::cancelForget,
            title = { Text("Remove from AgentX?") },
            text = {
                Column {
                    Text("\"${info.name}\" will be removed from AgentX's project list.")
                    IdeSpacer(8)
                    Text(
                        text = "This only stops AgentX tracking it. No files are deleted — the " +
                            "folder stays exactly where it is on your device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = ForgeMuted,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmForget(onWorkspaceRemoved) }, enabled = !state.busy) {
                    Text("Remove", color = ForgeDanger)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelForget, enabled = !state.busy) { Text("Cancel") }
            },
        )
    }

    state.pendingDelete?.let { info ->
        AlertDialog(
            onDismissRequest = viewModel::cancelDelete,
            title = { Text("Delete project?") },
            text = {
                Column {
                    if (info.managed) {
                        Text(
                            "\"${info.name}\" will be removed from AgentX, and the project folder " +
                                "AgentX created at ${info.location} will be deleted.",
                        )
                    } else {
                        Text(
                            "\"${info.name}\" will be removed from AgentX, along with any copy " +
                                "AgentX created for it.",
                        )
                    }
                    IdeSpacer(8)
                    Text(
                        text = if (info.managed) {
                            "Only this project's folder is deleted — other projects and the shared " +
                                "runtime are untouched."
                        } else {
                            "The folder you opened stays on your device, and other projects are " +
                                "not affected."
                        },
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
                TextButton(onClick = { viewModel.confirmDelete(onWorkspaceRemoved) }, enabled = !state.busy) {
                    Text(if (state.busy) "Deleting…" else "Delete", color = ForgeDanger)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelDelete, enabled = !state.busy) { Text("Cancel") }
            },
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = ForgeCanvas,
        topBar = {
            IdeTopBar(
                title = "Workspace",
                subtitle = state.current?.name ?: "No project open",
                onBack = onBack,
                actions = {
                    TextButton(onClick = viewModel::refresh) { Text("Refresh", color = ForgeMint) }
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
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            (state.error ?: state.message)?.let { text ->
                MessageCard(text = text, isError = state.error != null, onDismiss = viewModel::dismissMessage)
            }

            if (state.loading && state.current == null) {
                Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = ForgeMint)
                }
            } else {
                CurrentWorkspaceCard(
                    info = state.current,
                    lastOpenedLabel = state.current?.lastOpenedLabel,
                    onOpen = { state.current?.let { onOpenWorkspace(it.id) } },
                )
                state.current?.let { info ->
                    StorageCard(info = info, managedProjectRoot = state.managedProjectRoot)
                }
                ActionsCard(
                    busy = state.busy,
                    onSwitch = { viewModel.switchWorkspace(onOpenWorkspace) },
                    onCreate = {
                        viewModel.clearCreateError()
                        showCreateDialog = true
                    },
                    onRefresh = viewModel::refresh,
                )
                if (state.otherProjects.isNotEmpty()) {
                    SwitchProjectCard(
                        projects = state.otherProjects,
                        busy = state.busy,
                        onOpen = { id -> viewModel.openWorkspace(id, onOpenWorkspace) },
                    )
                }
                state.current?.let { info ->
                    ProjectInformationCard(
                        git = state.git,
                        gitLoading = state.gitLoading,
                        trackedProjects = state.recents.size,
                    )
                    AgentAccessCard()
                    WorkspaceSkillsCard(
                        count = state.workspaceSkillCount,
                        onOpenSkills = onOpenSkills,
                    )
                    DangerZoneCard(info = info, busy = state.busy, onForget = viewModel::requestForget, onDelete = viewModel::requestDelete)
                }
            }
            IdeSpacer(8)
        }
    }
}

@Composable
private fun MessageCard(text: String, isError: Boolean, onDismiss: () -> Unit) {
    IdeCard(modifier = Modifier.clickable(onClick = onDismiss)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(if (isError) ForgeDanger else ForgeMint, RoundedCornerShape(50)),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isError) ForgeDanger else ForgeInk,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun CurrentWorkspaceCard(
    info: WorkspaceInfo?,
    lastOpenedLabel: String?,
    onOpen: () -> Unit,
) {
    if (info == null) {
        IdeCard {
            IdeSectionLabel("Current workspace")
            IdeSpacer(8)
            IdeEmptyState(
                icon = Icons.Outlined.Folder,
                title = "No project open",
                message = "Open a project below to reach the file explorer, editor, agent, git and terminal.",
            )
        }
        return
    }

    IdeCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(ForgePeriwinkle.copy(alpha = 0.14f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.FolderOpen, contentDescription = null, tint = ForgePeriwinkle)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = info.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = ForgeInk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IdeSpacer(4)
                IdeStatusPill(text = "Open", color = ForgeMint)
            }
        }
        IdeSpacer(10)
        IdeLabelValue(label = "Storage", value = storageLabel(info.storageKind))
        if (lastOpenedLabel != null) {
            IdeLabelValue(label = "Opened", value = lastOpenedLabel)
        }
        IdeSpacer(6)
        OutlinedButton(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
            Text("Open this workspace")
        }
    }
}

@Composable
private fun StorageCard(info: WorkspaceInfo, managedProjectRoot: String?) {
    IdeCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Folder, contentDescription = null, tint = ForgePeriwinkle, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            IdeSectionLabel("Storage")
        }
        IdeSpacer(8)
        IdeLabelValue(label = "Type", value = storageLabel(info.storageKind))
        IdeSpacer(6)
        LabelBlock(label = "Location", value = info.location)
        IdeSpacer(8)
        Text(
            text = when (info.storageKind) {
                WorkspaceStorageKind.AGENTX_MANAGED ->
                    "AgentX created this project inside its own project folder, so it owns the " +
                        "files here. Deleting the project removes this folder."

                WorkspaceStorageKind.DEVICE_FOLDER ->
                    "This is a folder on your device that you opened. AgentX reads and writes it in " +
                        "place and never deletes it."

                WorkspaceStorageKind.SAF_FOLDER ->
                    "This folder was chosen with the system picker. AgentX reaches it through the " +
                        "folder permission you granted — it has no filesystem path, so the name above " +
                        "is the folder, not a location AgentX can hardcode."

                WorkspaceStorageKind.UNKNOWN ->
                    "The storage location for this workspace is not known."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = ForgeMuted,
        )
        if (info.managed && managedProjectRoot != null) {
            IdeSpacer(8)
            IdeDivider()
            IdeSpacer(8)
            LabelBlock(label = "AgentX projects folder", value = managedProjectRoot)
            IdeSpacer(6)
            Text(
                text = "The folder AgentX creates and clones projects into. It is the same folder " +
                    "project creation, GitHub clone and project deletion use.",
                style = MaterialTheme.typography.bodySmall,
                color = ForgeMuted,
            )
        }
    }
}

@Composable
private fun ActionsCard(
    busy: Boolean,
    onSwitch: () -> Unit,
    onCreate: () -> Unit,
    onRefresh: () -> Unit,
) {
    IdeCard {
        IdeSectionLabel("Workspace actions")
        IdeSpacer(6)
        WorkspaceActionRow(
            icon = Icons.Filled.SwapHoriz,
            title = "Switch workspace",
            subtitle = "Open a different folder as the active project",
            enabled = !busy,
            onClick = onSwitch,
        )
        IdeDivider()
        WorkspaceActionRow(
            icon = Icons.Filled.CreateNewFolder,
            title = "Create new project",
            subtitle = "An empty project in AgentX's project folder",
            enabled = !busy,
            onClick = onCreate,
        )
        IdeDivider()
        WorkspaceActionRow(
            icon = Icons.Filled.Refresh,
            title = "Refresh",
            subtitle = "Re-read the workspace, its skills and Git",
            enabled = !busy,
            onClick = onRefresh,
        )
    }
}

@Composable
private fun SwitchProjectCard(
    projects: List<ProjectSummary>,
    busy: Boolean,
    onOpen: (String) -> Unit,
) {
    IdeCard {
        IdeSectionLabel("Recent projects")
        IdeSpacer(6)
        Text(
            text = "Switching opens the project in place. No files are moved between projects.",
            style = MaterialTheme.typography.bodySmall,
            color = ForgeMuted,
        )
        IdeSpacer(6)
        projects.forEachIndexed { index, project ->
            if (index > 0) IdeDivider()
            WorkspaceActionRow(
                icon = Icons.Filled.FolderOpen,
                title = project.name,
                subtitle = project.lastOpenedLabel,
                enabled = !busy,
                onClick = { onOpen(project.id) },
            )
        }
    }
}

@Composable
private fun ProjectInformationCard(
    git: WorkspaceGitInfo?,
    gitLoading: Boolean,
    trackedProjects: Int,
) {
    val report = git
    IdeCard {
        IdeSectionLabel("Project information")
        IdeSpacer(8)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.AccountTree, contentDescription = null, tint = ForgeMuted, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Git",
                style = MaterialTheme.typography.bodyMedium,
                color = ForgeInk,
                modifier = Modifier.weight(1f),
            )
            when {
                gitLoading -> Text("Checking…", style = MaterialTheme.typography.labelSmall, color = ForgeMuted)
                report != null && report.repository -> {
                    report.branch?.let { IdeStatusPill(text = it, color = ForgePeriwinkle) }
                    Spacer(Modifier.width(6.dp))
                    when {
                        report.clean == true -> IdeStatusPill(text = "Clean", color = ForgeMint)
                        report.clean == false -> IdeStatusPill(
                            text = "${report.changedCount} changed",
                            color = ForgeDanger,
                        )

                        else -> Unit
                    }
                }

                else -> IdeStatusPill(text = "Not detected", color = ForgeMuted)
            }
        }
        report?.note?.takeIf { !gitLoading }?.let { note ->
            IdeSpacer(6)
            Text(text = note, style = MaterialTheme.typography.bodySmall, color = ForgeMuted)
        }
        IdeSpacer(8)
        IdeDivider()
        IdeLabelValue(label = "Projects tracked by AgentX", value = trackedProjects.toString())
        IdeSpacer(4)
        Text(
            text = "File counts and sizes are not shown: AgentX reads project folders on demand and " +
                "does not walk a whole project just to display a number.",
            style = MaterialTheme.typography.bodySmall,
            color = ForgeMuted,
        )
    }
}

@Composable
private fun AgentAccessCard() {
    IdeCard {
        IdeSectionLabel("Agent access")
        IdeSpacer(8)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Security, contentDescription = null, tint = ForgeMint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Scoped to the current workspace",
                style = MaterialTheme.typography.bodyMedium,
                color = ForgeInk,
            )
        }
        IdeSpacer(8)
        Text(
            text = "Agents can access files inside the current workspace through the workspace tools. " +
                "The open workspace is the scope of the agent's file operations — a tool cannot reach " +
                "outside it.",
            style = MaterialTheme.typography.bodyMedium,
            color = ForgeMuted,
        )
        IdeSpacer(8)
        Text(
            text = "Repository metadata (`.git`) is protected from agent writes, and secret-looking " +
                "files such as `.env` are never read into a model prompt.",
            style = MaterialTheme.typography.bodySmall,
            color = ForgeMuted,
        )
        IdeSpacer(8)
        Text(
            text = "Switching workspace changes this scope: the agent then works on the newly opened " +
                "project, and never on both at once.",
            style = MaterialTheme.typography.bodySmall,
            color = ForgeMuted,
        )
    }
}

@Composable
private fun WorkspaceSkillsCard(count: Int, onOpenSkills: () -> Unit) {
    IdeCard {
        IdeSectionLabel("Workspace skills")
        IdeSpacer(8)
        Text(
            text = if (count == 0) {
                "No workspace skills found in this project."
            } else {
                "$count workspace skill${if (count == 1) "" else "s"} found in this project."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = ForgeInk,
        )
        IdeSpacer(6)
        Text(
            text = "Workspace skills are read from the open project's skills/<id>/SKILL.md folders. " +
                "They are managed with the rest of your skills.",
            style = MaterialTheme.typography.bodySmall,
            color = ForgeMuted,
        )
        IdeSpacer(4)
        IdeDivider()
        WorkspaceActionRow(
            icon = Icons.Filled.Extension,
            title = "Manage skills",
            subtitle = "Open Settings → Skills",
            onClick = onOpenSkills,
        )
    }
}

@Composable
private fun DangerZoneCard(
    info: WorkspaceInfo,
    busy: Boolean,
    onForget: () -> Unit,
    onDelete: () -> Unit,
) {
    IdeCard {
        IdeSectionLabel("Danger zone")
        IdeSpacer(6)
        Text(
            text = "Remove only stops AgentX tracking the project. Delete also removes the data " +
                "AgentX created for it.",
            style = MaterialTheme.typography.bodySmall,
            color = ForgeMuted,
        )
        IdeSpacer(4)
        IdeDivider()
        WorkspaceActionRow(
            icon = Icons.Filled.RemoveCircleOutline,
            title = "Remove from AgentX",
            subtitle = "Keeps every file on your device",
            enabled = !busy,
            danger = true,
            onClick = onForget,
        )
        IdeDivider()
        WorkspaceActionRow(
            icon = Icons.Filled.Delete,
            title = "Delete project",
            subtitle = if (info.managed) {
                "Deletes the project folder AgentX created"
            } else {
                "Removes AgentX's record and any copy it made — never your folder"
            },
            enabled = !busy,
            danger = true,
            onClick = onDelete,
        )
    }
}

@Composable
private fun WorkspaceActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val tint = when {
        !enabled -> ForgeMuted
        danger -> ForgeDanger
        else -> ForgePeriwinkle
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(tint.copy(alpha = 0.14f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = when {
                    !enabled -> ForgeMuted
                    danger -> ForgeDanger
                    else -> ForgeInk
                },
            )
            Text(text = subtitle, style = MaterialTheme.typography.labelSmall, color = ForgeMuted)
        }
    }
}

@Composable
private fun LabelBlock(label: String, value: String) {
    IdeSectionLabel(label)
    IdeSpacer(4)
    Text(text = value, style = MaterialTheme.typography.bodyMedium, color = ForgeInk)
}

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
                    text = "Name your project. AgentX creates an empty folder in its project " +
                        "storage and opens it as the active workspace.",
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
            Button(onClick = { onConfirm(name) }, enabled = !creating && name.isNotBlank()) {
                Text(if (creating) "Creating…" else "Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !creating) { Text("Cancel") }
        },
    )
}

private fun storageLabel(kind: WorkspaceStorageKind): String = when (kind) {
    WorkspaceStorageKind.AGENTX_MANAGED -> "AgentX project storage"
    WorkspaceStorageKind.DEVICE_FOLDER -> "Device folder"
    WorkspaceStorageKind.SAF_FOLDER -> "System folder (SAF)"
    WorkspaceStorageKind.UNKNOWN -> "Unknown"
}
