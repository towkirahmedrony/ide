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
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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

@Composable
fun FilesScreen(
    state: FilesUiState,
    onToggle: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    onRetry: () -> Unit,
    onRetryDirectory: (String) -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
                modifier = Modifier.align(Alignment.Center),
            )

            else -> Column(modifier = Modifier.fillMaxSize()) {
                FolderPathBar(
                    label = state.breadcrumb(),
                    canNavigateUp = state.focusedPath.isNotEmpty(),
                    onNavigateUp = onNavigateUp,
                )
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
}

/** Breadcrumb for the folder the user is working in, with an "up" affordance. */
@Composable
private fun FolderPathBar(
    label: String,
    canNavigateUp: Boolean,
    onNavigateUp: () -> Unit,
) {
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
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .background(if (selected) ForgeSurfaceVariant else Color.Transparent)
            .clickable { if (node.isDirectory) onToggle() else onOpenFile() }
            .padding(start = (depth * 16).dp, top = 12.dp, bottom = 12.dp, end = 10.dp),
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
    }
}
