package dev.forge.ide.ui.ide.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.forge.ide.ui.ide.components.IdeEmptyState
import dev.forge.ide.ui.ide.model.FileNode
import dev.forge.ide.ui.ide.state.FilesUiState
import dev.forge.ide.ui.theme.ForgeCanvas
import dev.forge.ide.ui.theme.ForgeInk
import dev.forge.ide.ui.theme.ForgeMint
import dev.forge.ide.ui.theme.ForgeMuted
import dev.forge.ide.ui.theme.ForgePeriwinkle
import dev.forge.ide.ui.theme.ForgeSurfaceVariant

/** Visible tree rows as (node, depth) pairs, honouring the expanded set. */
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
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize().background(ForgeCanvas)) {
        when {
            state.loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = ForgeMint)
            }

            state.error != null -> IdeEmptyState(
                icon = Icons.Outlined.FolderOff,
                title = "Files unavailable",
                message = state.error,
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

            else -> {
                val rows = flattenVisible(state.root, state.expanded)
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(rows, key = { it.first.path }) { (node, depth) ->
                        FileRow(
                            node = node,
                            depth = depth,
                            expanded = node.path in state.expanded,
                            selected = state.selectedPath == node.path,
                            onToggle = { onToggle(node.path) },
                            onOpenFile = { onOpenFile(node.path) },
                        )
                    }
                }
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
