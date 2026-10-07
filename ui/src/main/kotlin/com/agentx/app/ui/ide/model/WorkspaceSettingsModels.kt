package com.agentx.app.ui.ide.model

import com.agentx.app.workspace.WorkspaceMetadata
import java.io.File

/**
 * How a workspace's files are stored, in the user's terms.
 *
 * The distinction is not cosmetic: it decides what [com.agentx.app.workspace.WorkspaceManager.delete]
 * is allowed to remove. An [AGENTX_MANAGED] project *is* the directory AgentX created, so deleting it
 * removes that directory; the other two are folders the user selected, which AgentX never deletes.
 * Naming the kind here is what lets the Workspace screen say which delete is which instead of showing
 * a single, ambiguous "Delete".
 */
enum class WorkspaceStorageKind {
    /** A project AgentX created or cloned inside its own project folder. */
    AGENTX_MANAGED,

    /** A folder on this device that the user opened, addressed by a real filesystem path. */
    DEVICE_FOLDER,

    /** A folder the user chose with the system picker, addressed only through its stored permission. */
    SAF_FOLDER,

    /** Nothing is open, or the location is not known. */
    UNKNOWN,
}

/**
 * A workspace summarised for the Settings → Workspace screen.
 *
 * [location] is the runtime's own [com.agentx.app.workspace.WorkspaceMetadata.displayLocation] passed
 * through unchanged: a real path for a filesystem workspace, and a folder *name* for a SAF workspace —
 * never a fabricated path. The screen must render this as-is rather than reconstructing a location
 * from the handle.
 */
data class WorkspaceInfo(
    val id: String,
    val name: String,
    val location: String,
    val storageKind: WorkspaceStorageKind,
    val lastOpenedLabel: String = "not opened yet",
) {
    /** True when deleting the project removes the directory AgentX itself created. */
    val managed: Boolean get() = storageKind == WorkspaceStorageKind.AGENTX_MANAGED

    /** True when the location is reached only through a stored document permission. */
    val saf: Boolean get() = storageKind == WorkspaceStorageKind.SAF_FOLDER
}

/**
 * What the Git screen would report for the open workspace, reduced to the one line Settings needs.
 *
 * [note] carries the runtime's own explanation when Git is unavailable or the project is not a
 * repository, so the screen can be specific instead of claiming "clean".
 */
data class WorkspaceGitInfo(
    val repository: Boolean,
    val branch: String? = null,
    val clean: Boolean? = null,
    val changedCount: Int = 0,
    val note: String? = null,
)

/**
 * Classifies an opaque workspace [handle] against the AgentX-managed project roots.
 *
 * [managedRoots] is the composition root's view of where AgentX keeps its own projects, taken from
 * [com.agentx.app.workspace.AgentxProjectRoot] — the same value project creation, GitHub clone and
 * project deletion use. A path is managed only when it is a *direct child* of one of those roots, so a
 * folder that happens to sit beside them (or a project with the same name elsewhere) is never
 * misreported as AgentX-managed.
 *
 * A `content://` handle is a SAF tree and is never treated as a path: turning it into one would be a
 * fabricated location, which is exactly what this classifier exists to prevent.
 */
fun classifyWorkspaceStorage(
    handle: String?,
    managedRoots: List<String> = emptyList(),
): WorkspaceStorageKind {
    val raw = handle?.trim().orEmpty()
    if (raw.isEmpty()) return WorkspaceStorageKind.UNKNOWN
    if (raw.startsWith(SAF_SCHEME)) return WorkspaceStorageKind.SAF_FOLDER

    val normalized = normalizePath(raw) ?: return WorkspaceStorageKind.DEVICE_FOLDER
    val parent = normalized.substringBeforeLast(PATH_SEPARATOR, missingDelimiterValue = "")
    if (parent.isEmpty()) return WorkspaceStorageKind.DEVICE_FOLDER

    val managed = managedRoots
        .mapNotNull(::normalizePath)
        .any { it == parent }
    return if (managed) WorkspaceStorageKind.AGENTX_MANAGED else WorkspaceStorageKind.DEVICE_FOLDER
}

/**
 * Summarises the open workspace. [handle] is the manager's current handle, used only for
 * classification; the location shown comes from the metadata.
 */
fun WorkspaceMetadata.toWorkspaceInfo(
    handle: String?,
    managedRoots: List<String> = emptyList(),
    nowMillis: Long = System.currentTimeMillis(),
): WorkspaceInfo = WorkspaceInfo(
    id = id.value,
    name = name,
    location = displayLocation,
    storageKind = classifyWorkspaceStorage(handle, managedRoots),
    lastOpenedLabel = relativeTimeLabel(lastOpenedAtEpochMillis, nowMillis),
)

private const val SAF_SCHEME = "content://"
private const val PATH_SEPARATOR = '/'

/**
 * A comparable absolute path, canonical where the platform can resolve it (`/data/data/…` versus
 * `/data/user/0/…` name the same directory) and `null` when the value is not a path at all.
 */
private fun normalizePath(path: String): String? {
    val trimmed = path.trim().trimEnd(PATH_SEPARATOR).trim()
    if (trimmed.isEmpty() || !trimmed.startsWith(PATH_SEPARATOR)) return null
    val file = File(trimmed)
    return runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
        .trimEnd(PATH_SEPARATOR)
        .ifEmpty { PATH_SEPARATOR }
}
