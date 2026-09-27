package dev.forge.ide.ui.ide.model

import dev.forge.ide.workspace.WorkspaceMetadata

/** Presentation summary for a remembered workspace. */
fun WorkspaceMetadata.toSummary(nowMillis: Long = System.currentTimeMillis()): ProjectSummary = ProjectSummary(
    id = id.value,
    name = name,
    rootPath = displayLocation,
    branch = null,
    lastOpenedLabel = relativeTimeLabel(lastOpenedAtEpochMillis, nowMillis),
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
