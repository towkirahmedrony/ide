package com.agentx.app.ui.ide.model

import com.agentx.app.workspace.DirectoryState
import com.agentx.app.workspace.WorkspaceEntryKind
import com.agentx.app.workspace.WorkspaceMetadata
import com.agentx.app.workspace.WorkspaceTreeEntry

/** Presentation summary for a remembered workspace. */
fun WorkspaceMetadata.toSummary(nowMillis: Long = System.currentTimeMillis()): ProjectSummary = ProjectSummary(
    id = id.value,
    name = name,
    rootPath = displayLocation,
    branch = null,
    lastOpenedLabel = relativeTimeLabel(lastOpenedAtEpochMillis, nowMillis),
)

/**
 * Turns one entry of the runtime's lazily loaded tree into the presentation
 * node the Files screen renders. Loaded folders keep their children; folders
 * that were never opened stay [DirectoryLoadState.UNLOADED] and cost nothing.
 */
fun WorkspaceTreeEntry.toFileNode(): FileNode = FileNode(
    path = path,
    name = name,
    kind = if (kind == WorkspaceEntryKind.DIRECTORY) FileNodeKind.DIRECTORY else FileNodeKind.FILE,
    children = children.map { it.toFileNode() },
    loadState = when (directory) {
        null, DirectoryState.Unloaded -> DirectoryLoadState.UNLOADED
        DirectoryState.Loading -> DirectoryLoadState.LOADING
        is DirectoryState.Loaded -> DirectoryLoadState.LOADED
        is DirectoryState.Failed -> DirectoryLoadState.ERROR
    },
    errorMessage = (directory as? DirectoryState.Failed)?.error?.userMessage,
)

/** Human-friendly "last opened" label without pulling in a date library. */
fun relativeTimeLabel(epochMillis: Long?, nowMillis: Long): String {
    if (epochMillis == null || epochMillis <= 0L) return "not opened yet"
    val delta = (nowMillis - epochMillis).coerceAtLeast(0L)
    val minutes = delta / 60_000L
    val hours = delta / 3_600_000L
    val days = delta / 86_400_000L
    return when {
        minutes < 1L -> "just now"
        minutes < 60L -> "$minutes min ago"
        hours < 24L -> "$hours h ago"
        days == 1L -> "yesterday"
        days < 7L -> "$days d ago"
        else -> "${days / 7} w ago"
    }
}
