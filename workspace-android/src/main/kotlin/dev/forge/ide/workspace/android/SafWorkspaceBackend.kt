package dev.forge.ide.workspace.android

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import dev.forge.ide.core.failure
import dev.forge.ide.core.logging.ForgeLogger
import dev.forge.ide.core.logging.ForgeLoggers
import dev.forge.ide.core.logging.LogLevel
import dev.forge.ide.core.success
import dev.forge.ide.workspace.DefaultWorkspace
import dev.forge.ide.workspace.Workspace
import dev.forge.ide.workspace.WorkspaceBackend
import dev.forge.ide.workspace.WorkspaceError
import dev.forge.ide.workspace.WorkspaceErrorCode
import dev.forge.ide.workspace.WorkspaceId
import dev.forge.ide.workspace.WorkspaceMetadata
import dev.forge.ide.workspace.WorkspaceResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Opens workspaces through Android's Storage Access Framework.
 *
 * The opaque handle is the persisted tree URI string produced by the system
 * folder picker. Persistable access is requested so the workspace can be
 * reopened later; only a stable id and display name are derived from it.
 */
class SafWorkspaceBackend(private val context: Context) : WorkspaceBackend {

    private val logger: ForgeLogger = ForgeLoggers.create(LogLevel.WARN, baseFields = mapOf("layer" to "saf"))

    override suspend fun open(handle: String): WorkspaceResult<Workspace> = withContext(Dispatchers.IO) {
        val uri = runCatching { Uri.parse(handle) }.getOrNull() ?: return@withContext invalidHandle()
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return@withContext invalidHandle()
        // Only a tree URI (what ACTION_OPEN_DOCUMENT_TREE returns) can be listed
        // and walked; a single-document URI cannot become a workspace.
        if (!runCatching { DocumentsContract.isTreeUri(uri) }.getOrDefault(false)) {
            return@withContext invalidHandle()
        }

        if (!persistAccess(uri)) {
            logger.warn(
                "The folder could not be remembered for later; it stays usable for this session only.",
                mapOf("location" to displayLocation(uri, "workspace")),
            )
        }

        val root = runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull()
            ?: return@withContext notFound()
        if (!runCatching { root.exists() }.getOrDefault(false)) return@withContext notFound()
        if (!runCatching { root.isDirectory }.getOrDefault(false)) return@withContext notADirectory()
        if (!runCatching { root.canRead() }.getOrDefault(false)) return@withContext permissionDenied()

        val name = runCatching { root.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Workspace"
        val metadata = WorkspaceMetadata(
            id = WorkspaceId(stableId(uri)),
            name = name,
            displayLocation = displayLocation(uri, name),
            lastOpenedAtEpochMillis = null,
            persisted = true,
        )
        success(DefaultWorkspace(metadata = metadata, fileSystem = SafWorkspaceFileSystem(context, root)))
    }

    /**
     * Requests durable access and verifies it was actually granted.
     *
     * Write access is requested first because it implies read, but a folder
     * picked read-only must still open: the fallback asks for read only, and the
     * result is confirmed against [ContentResolver.getPersistedUriPermissions]
     * instead of being assumed.
     */
    private fun persistAccess(uri: Uri): Boolean {
        val readOnly = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val readWrite = readOnly or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

        runCatching { context.contentResolver.takePersistableUriPermission(uri, readWrite) }
            .recoverCatching { context.contentResolver.takePersistableUriPermission(uri, readOnly) }

        return runCatching {
            context.contentResolver.persistedUriPermissions.any { granted ->
                granted.uri == uri && granted.isReadPermission
            }
        }.getOrDefault(false)
    }

    private fun stableId(uri: Uri): String = "saf-" + uri.toString().hashCode().toUInt().toString(16)

    private fun displayLocation(uri: Uri, fallback: String): String {
        val segment = uri.lastPathSegment ?: return fallback
        val withoutVolume = segment.substringAfter(':', segment).trim()
        return withoutVolume.ifBlank { fallback }
    }

    private fun invalidHandle(): WorkspaceResult<Nothing> =
        failure(WorkspaceError(WorkspaceErrorCode.INVALID_HANDLE, "The selected workspace location is not valid."))

    private fun notFound(): WorkspaceResult<Nothing> =
        failure(WorkspaceError(WorkspaceErrorCode.WORKSPACE_NOT_FOUND, "The selected folder is not available."))

    private fun notADirectory(): WorkspaceResult<Nothing> =
        failure(WorkspaceError(WorkspaceErrorCode.NOT_A_DIRECTORY, "The selected item is not a folder."))

    private fun permissionDenied(): WorkspaceResult<Nothing> =
        failure(WorkspaceError(WorkspaceErrorCode.PERMISSION_DENIED, "Read access to this folder was not granted."))
}
