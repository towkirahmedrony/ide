package com.agentx.app.workspace

import com.agentx.app.core.logging.ForgeLogger
import com.agentx.app.core.logging.ForgeLoggers
import com.agentx.app.core.logging.LogLevel
import com.agentx.app.core.failure
import com.agentx.app.core.success
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Opens a project that already lives at a filesystem path.
 *
 * This is how an AgentX-managed Git clone becomes a workspace: the clone directory is opened
 * in place, and its filesystem is the repository itself — `.git` included — so editor changes and
 * terminal `git status` look at the same files. Nothing is copied into a second tree.
 *
 * The handle is the ordinary path; callers must not treat it as anything other than an opaque
 * location, which is what [WorkspaceManager.currentHandle] already documents.
 */
class FileWorkspaceBackend : WorkspaceBackend {

    private val logger: ForgeLogger = ForgeLoggers.create(LogLevel.WARN, baseFields = mapOf("layer" to "file"))

    override suspend fun open(handle: String): WorkspaceResult<Workspace> = withContext(Dispatchers.IO) {
        val raw = handle.trim()
        if (raw.isEmpty()) {
            return@withContext failure(
                WorkspaceError(WorkspaceErrorCode.INVALID_HANDLE, "No workspace was selected."),
            )
        }

        val directory = runCatching { File(raw).canonicalFile }.getOrElse { File(raw).absoluteFile }
        if (!directory.exists()) {
            return@withContext failure(
                WorkspaceError(WorkspaceErrorCode.WORKSPACE_NOT_FOUND, "The selected folder is not available."),
            )
        }
        if (!directory.isDirectory) {
            return@withContext failure(
                WorkspaceError(WorkspaceErrorCode.NOT_A_DIRECTORY, "The selected item is not a folder."),
            )
        }
        if (!directory.canRead()) {
            return@withContext failure(
                WorkspaceError(WorkspaceErrorCode.PERMISSION_DENIED, "Read access to this folder was not granted."),
            )
        }

        val name = directory.name.takeIf { it.isNotBlank() } ?: "Workspace"
        logger.debug("Opening filesystem workspace", mapOf("path" to directory.path))
        val metadata = WorkspaceMetadata(
            id = WorkspaceId(stableId(directory.path)),
            name = name,
            displayLocation = directory.path,
            persisted = true,
        )
        success(DefaultWorkspace(metadata = metadata, fileSystem = FileWorkspaceFileSystem(directory)))
    }

    private fun stableId(path: String): String = "file-" + path.hashCode().toUInt().toString(16)
}
