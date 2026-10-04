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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agentx.app.ui.ide.components.IdeEmptyState
import com.agentx.app.ui.ide.model.DirectoryLoadState
import com.agentx.app.ui.ide.model.FileNode
import com.agentx.app.ui.ide.state.FilesUiState
import com.agentx.app.ui.theme.ForgeCanvas
import com.agentx.app.ui.theme.ForgeDanger
import com.agentx.app.ui.theme.ForgeInk
import com.agentx.app.ui.theme.ForgeMint
import com.agentx.app.ui.theme.ForgeMuted
import com.agentx.app.ui.theme.ForgePeriwinkle
import com.agentx.app.ui.theme.ForgeSurfaceVariant

/**
 * Visible tree rows as (node, depth) pairs, honouring the expanded set.
 *
 * Only folders that were expanded contribute child rows, so the rows reflect
 * exactly what the runtime has loaded — never a whole-project scan.
 */
private fun flattenVisible(
    nodes: List<FileNode>,
    expanded: Set<String>,
    depth: Int = 0,
): List<Pair<FileNode, Int>> = nodes.flatMap { node ->
    val self = listOf(node to depth)
    if (node.isDirectory && node.path in expanded) {
        self + flattenVisible(node.children, expanded, depth + 1)
    } else {
        self
    }
}

/** A pending create/rename dialog: its title, the name it starts from, and what it does. */
private data class NameDialogSpec(
    val title: String,
    val initial: String,
    val confirmLabel: String,
    val onConfirm: (String) -> Unit,
)

@Composable
fun FilesScreen(
    state: FilesUiState,
    onToggle: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    onRetry: () -> Unit,
    onRetryDirectory: (String) -> Unit,
    onNavigateUp: () -> Unit,
    onRefresh: () -> Unit,
    onCreateFile: (String, String) -> Unit,
    onCreateDirectory: (String, String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var nameDialog by remember { mutableStateOf<NameDialogSpec?>(null) }
    var deleteTarget by remember { mutableStateOf<FileNode?>(null) }

    Box(modifier = modifier.fillMaxSize().background(ForgeCanvas)) {
        when {
            // The workspace itself is still being opened.
            state.loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = ForgeMint)
            }

            // The workspace could not be opened: a real error, with a retry.
            state.error != null -> IdeEmptyState(
                icon = Icons.Outlined.FolderOff,
                title = "Files unavailable",
                message = state.error,
                actionLabel = "Retry",
                onAction = onRetry,
                modifier = Modifier.align(Alignment.Center),
            )

            // The root folder could not be read: same treatment, folder scoped.
            state.rootState == DirectoryLoadState.ERROR -> IdeEmptyState(
                icon = Icons.Outlined.ErrorOutline,
                title = "Could not load this folder",
                message = state.rootError ?: "The folder could not be read.",
                actionLabel = "Retry",
                onAction = onRetry,
                modifier = Modifier.align(Alignment.Center),
            )

            state.isEmpty -> IdeEmptyState(
                icon = Icons.Outlined.FolderOff,
                title = "This folder is empty",
                message = "There are no files in this workspace yet.",
                actionLabel = "New file",
                onAction = {
                    nameDialog = NameDialogSpec("New file", "", "Create") { name ->
                        onCreateFile(state.focusedPath, name)
                    }
                },
                modifier = Modifier.align(Alignment.Center),
            )

            else -> Column(modifier = Modifier.fillMaxSize()) {
                FolderPathBar(
                    label = state.breadcrumb(),
                    canNavigateUp = state.focusedPath.isNotEmpty(),
                    onNavigateUp = onNavigateUp,
                    onNewFile = {
                        nameDialog = NameDialogSpec("New file", "", "Create") { name ->
                            onCreateFile(state.focusedPath, name)
                        }
                    },
                    onNewFolder = {
                        nameDialog = NameDialogSpec("New folder", "", "Create") { name ->
                            onCreateDirectory(state.focusedPath, name)
                        }
                    },
                    onRefresh = onRefresh,
                )
                state.message?.let { message ->
                    OperationMessageStrip(
                        message = message,
                        isError = state.messageIsError,
                        onDismiss = onDismissMessage,
                    )
                }
                val rows = flattenVisible(state.root.children, state.expanded)
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(rows, key = { it.first.path }) { (node, depth) ->
                        Column {
                            FileRow(
                                node = node,
                                depth = depth,
                                expanded = node.path in state.expanded,
                                selected = state.selectedPath == node.path,
                                onToggle = { onToggle(node.path) },
                                onOpenFile = { onOpenFile(node.path) },
                                onRename = {
                                    nameDialog = NameDialogSpec("Rename", node.name, "Rename") { newName ->
                                        onRename(node.path, newName)
                                    }
                                },
                                onDelete = { deleteTarget = node },
                            )
                            // A folder reports its own state instead of spinning forever.
                            if (node.isDirectory && node.path in state.expanded) {
                                FolderStatusRow(
                                    node = node,
                                    depth = depth,
                                    onRetry = { onRetryDirectory(node.path) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    nameDialog?.let { spec ->
        NameEntryDialog(
            spec = spec,
            onDismiss = { nameDialog = null },
            onConfirm = { name ->
                nameDialog = null
                spec.onConfirm(name)
            },
        )
    }

    deleteTarget?.let { node ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete ${if (node.isDirectory) "folder" else "file"}?") },
            text = {
                Text(
                    if (node.isDirectory) {
                        "\"${node.name}\" and everything inside it will be deleted from the project."
                    } else {
                        "\"${node.name}\" will be deleted from the project."
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTarget = null
                        onDelete(node.path)
                    },
                ) { Text("Delete", color = ForgeDanger) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            },
        )
    }
}

/** The name prompt shared by create and rename. */
@Composable
private fun NameEntryDialog(
    spec: NameDialogSpec,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(spec) { mutableStateOf(spec.initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(spec.title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Name") },
                isError = name.isBlank(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = name.isNotBlank(),
            ) { Text(spec.confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Dismissible result of the last create/rename/delete. */
@Composable
private fun OperationMessageStrip(
    message: String,
    isError: Boolean,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isError) ForgeDanger.copy(alpha = 0.12f) else ForgeMint.copy(alpha = 0.10f))
            .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.labelSmall,
            color = if (isError) ForgeDanger else ForgeMint,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDismiss) { Text("Dismiss") }
    }
}

/** Breadcrumb for the folder the user is working in, with the folder actions. */
@Composable
private fun FolderPathBar(
    label: String,
    canNavigateUp: Boolean,
    onNavigateUp: () -> Unit,
    onNewFile: () -> Unit,
    onNewFolder: () -> Unit,
    onRefresh: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ForgeSurfaceVariant)
            .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = ForgeMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onNavigateUp, enabled = canNavigateUp) {
            Icon(
                imageVector = Icons.Filled.ArrowUpward,
                contentDescription = "Go to parent folder",
                tint = if (canNavigateUp) ForgeInk else ForgeMuted,
                modifier = Modifier.size(18.dp),
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = "Folder actions",
                    tint = ForgeInk,
                    modifier = Modifier.size(18.dp),
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("New file") },
                    leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onNewFile()
                    },
                )
                DropdownMenuItem(
                    text = { Text("New folder") },
                    leadingIcon = { Icon(Icons.Filled.CreateNewFolder, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onNewFolder()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Refresh") },
                    leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onRefresh()
                    },
                )
            }
        }
    }
}

/** Inline status for an expanded folder: loading, empty, or unreadable. */
@Composable
private fun FolderStatusRow(
    node: FileNode,
    depth: Int,
    onRetry: () -> Unit,
) {
    val indent = ((depth + 1) * 16 + 42).dp
    when (node.loadState) {
        // A folder that is being read already shows its spinner on the row itself.
        DirectoryLoadState.LOADING, DirectoryLoadState.UNLOADED -> Unit

        DirectoryLoadState.LOADED -> if (node.children.isEmpty()) {
            Text(
                text = "This folder is empty",
                style = MaterialTheme.typography.labelSmall,
                color = ForgeMuted,
                modifier = Modifier.padding(start = indent, top = 2.dp, bottom = 6.dp),
            )
        }

        DirectoryLoadState.ERROR -> Row(
            modifier = Modifier.fillMaxWidth().padding(start = indent, top = 2.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = ForgeDanger,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = node.errorMessage ?: "Unable to read this folder.",
                style = MaterialTheme.typography.labelSmall,
                color = ForgeDanger,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text("Retry")
            }
        }
    }
}

@Composable
private fun FileRow(
    node: FileNode,
    depth: Int,
    expanded: Boolean,
    selected: Boolean,
    onToggle: () -> Unit,
    onOpenFile: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .background(if (selected) ForgeSurfaceVariant else Color.Transparent)
            .clickable { if (node.isDirectory) onToggle() else onOpenFile() }
            .padding(start = (depth * 16).dp, top = 12.dp, bottom = 12.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val icon = when {
            node.isDirectory && expanded -> Icons.Filled.FolderOpen
            node.isDirectory -> Icons.Filled.Folder
            else -> Icons.Filled.Description
        }
        val tint = if (node.isDirectory) ForgePeriwinkle else ForgeMuted
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            text = node.name,
            style = MaterialTheme.typography.bodyMedium,
            color = if (node.isDirectory) ForgeInk else ForgeMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (node.isDirectory && node.loadState == DirectoryLoadState.LOADING) {
            CircularProgressIndicator(
                color = ForgeMint,
                strokeWidth = 2.dp,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(4.dp))
        }
        if (node.isDirectory) {
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = ForgeMuted,
                modifier = Modifier.size(18.dp),
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = "Actions for ${node.name}",
                    tint = ForgeMuted,
                    modifier = Modifier.size(18.dp),
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Rename") },
                    leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onRename()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Delete") },
                    leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                )
            }
        }
    }
}
