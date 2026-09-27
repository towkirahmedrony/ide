package dev.forge.ide.workspace.android

import android.content.ContentResolver
import android.content.Context
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import dev.forge.ide.core.ForgeResult
import dev.forge.ide.core.failure
import dev.forge.ide.core.logging.ForgeLogger
import dev.forge.ide.core.logging.ForgeLoggers
import dev.forge.ide.core.logging.LogLevel
import dev.forge.ide.core.success
import dev.forge.ide.core.valueOrNull
import dev.forge.ide.workspace.WorkspaceDirectory
import dev.forge.ide.workspace.WorkspaceError
import dev.forge.ide.workspace.WorkspaceErrorCode
import dev.forge.ide.workspace.WorkspaceFile
import dev.forge.ide.workspace.WorkspaceFileSystem
import dev.forge.ide.workspace.WorkspaceNode
import dev.forge.ide.workspace.WorkspacePath
import dev.forge.ide.workspace.WorkspaceResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * A [WorkspaceFileSystem] backed by Android's Storage Access Framework.
 *
 * All access is rooted at the tree the user selected. Every path is resolved by
 * walking named segments from that root, so absolute paths and traversal can
 * never reach outside it.
 */
class SafWorkspaceFileSystem(
    context: Context,
    private val root: DocumentFile,
) : WorkspaceFileSystem {

    private val resolver: ContentResolver = context.applicationContext.contentResolver

    private val logger: ForgeLogger = ForgeLoggers.create(LogLevel.WARN, baseFields = mapOf("layer" to "saf"))

    /**
     * Documents already resolved during this session, keyed by workspace path.
     *
     * Listing a folder hands back its children, so remembering them turns
     * "expand a folder" into one provider query instead of re-walking (and
     * re-listing) every ancestor for each child. Cleared whenever the tree is
     * mutated, because paths can then point at different documents.
     */
    private val resolved = LinkedHashMap<String, DocumentFile>()

    override suspend fun list(path: String): WorkspaceResult<List<WorkspaceNode>> = io {
        val rel = when (val normalized = WorkspacePath.normalize(path)) {
            is ForgeResult.Success -> normalized.value
            is ForgeResult.Failure -> return@io normalized
        }
        val directory = find(rel) ?: return@io notFound(rel)
        val readable = accessOf(directory)
        if (readable == Access.DENIED) return@io permissionDenied(rel)
        if (!runCatching { directory.isDirectory }.getOrDefault(false)) return@io notADirectory(rel)

        val children = try {
            directory.listFiles()
        } catch (denied: SecurityException) {
            logger.warn("Reading a workspace folder was denied", mapOf("path" to rel))
            return@io permissionDenied(rel)
        } catch (missing: FileNotFoundException) {
            return@io notFound(rel)
        } catch (cause: Throwable) {
            // Never report "empty" for a folder we could not read: that hides
            // revoked permissions behind a permanently empty-looking tree.
            logger.error("Reading a workspace folder failed", cause, mapOf("path" to rel))
            return@io ioFailed(rel)
        }

        if (children.isEmpty() && accessOf(directory) == Access.DENIED) return@io permissionDenied(rel)

        children
            .take(MAX_ENTRIES_PER_DIRECTORY)
            .mapNotNull { child -> toListNode(rel, child) }
            .sortedWith(NODE_ORDER)
            .let { success(it) }
    }

    override suspend fun metadata(path: String): WorkspaceResult<WorkspaceNode> = io {
        val rel = when (val normalized = WorkspacePath.normalize(path)) {
            is ForgeResult.Success -> normalized.value
            is ForgeResult.Failure -> return@io normalized
        }
        val document = find(rel) ?: return@io notFound(rel)
        toDetailNode(WorkspacePath.parent(rel), document)?.let { success(it) } ?: notFound(rel)
    }

    override suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
        val rel = WorkspacePath.normalize(path).valueOrNull() ?: return@withContext false
        runCatching { find(rel)?.exists() == true }.getOrDefault(false)
    }

    override suspend fun readFile(path: String): WorkspaceResult<String> = io {
        val rel = when (val normalized = WorkspacePath.normalize(path)) {
            is ForgeResult.Success -> normalized.value
            is ForgeResult.Failure -> return@io normalized
        }
        val document = find(rel) ?: return@io notFound(rel)
        if (runCatching { document.isDirectory }.getOrDefault(false)) return@io notAFile(rel)
        if (!runCatching { document.canRead() }.getOrDefault(false)) return@io permissionDenied(rel)
        if (runCatching { document.length() }.getOrDefault(0L) > MAX_FILE_BYTES) return@io tooLarge(rel)

        val stream = try {
            resolver.openInputStream(document.uri)
        } catch (denied: SecurityException) {
            logger.warn("Reading a file was denied", mapOf("path" to rel))
            return@io permissionDenied(rel)
        } catch (missing: FileNotFoundException) {
            return@io notFound(rel)
        } catch (cause: Throwable) {
            logger.error("Opening a file failed", cause, mapOf("path" to rel))
            return@io ioFailed(rel)
        } ?: return@io if (accessOf(document) == Access.DENIED) permissionDenied(rel) else ioFailed(rel)
        val bytes = stream.use { input -> readBounded(input, MAX_FILE_BYTES) }
        if (bytes == null) return@io tooLarge(rel)
        success(bytes.toString(Charsets.UTF_8))
    }

    override suspend fun writeFile(path: String, content: String): WorkspaceResult<Unit> = io {
        val rel = when (val normalized = WorkspacePath.normalize(path)) {
            is ForgeResult.Success -> normalized.value
            is ForgeResult.Failure -> return@io normalized
        }
        val document = find(rel) ?: return@io notFound(rel)
        if (runCatching { document.isDirectory }.getOrDefault(false)) return@io notAFile(rel)
        if (!runCatching { document.canWrite() }.getOrDefault(false)) return@io permissionDenied(rel)

        val written = runCatching {
            val output = resolver.openOutputStream(document.uri, "wt")
            if (output == null) {
                false
            } else {
                output.use { stream ->
                    stream.write(content.toByteArray(Charsets.UTF_8))
                    stream.flush()
                }
                true
            }
        }.getOrDefault(false)
        if (!written) return@io ioFailed(rel)
        success(Unit)
    }

    override suspend fun createFile(path: String): WorkspaceResult<WorkspaceFile> = io {
        val rel = when (val normalized = WorkspacePath.normalize(path)) {
            is ForgeResult.Success -> normalized.value
            is ForgeResult.Failure -> return@io normalized
        }
        if (rel.isEmpty()) return@io invalid(rel)
        val parentPath = WorkspacePath.parent(rel)
        val name = WorkspacePath.name(rel)
        val parent = find(parentPath) ?: return@io notFound(parentPath)
        if (!runCatching { parent.isDirectory }.getOrDefault(false)) return@io notADirectory(parentPath)
        if (!runCatching { parent.canWrite() }.getOrDefault(false)) return@io permissionDenied(parentPath)
        if (runCatching { find(rel)?.exists() == true }.getOrDefault(false)) return@io alreadyExists(rel)

        val created = runCatching { parent.createFile(mimeTypeFor(name), name) }.getOrNull()
            ?: return@io ioFailed(rel)
        invalidateResolved()
        success(WorkspaceFile(path = rel, name = created.name ?: name, sizeBytes = 0L, writable = true))
    }

    override suspend fun createDirectory(path: String): WorkspaceResult<WorkspaceDirectory> = io {
        val rel = when (val normalized = WorkspacePath.normalize(path)) {
            is ForgeResult.Success -> normalized.value
            is ForgeResult.Failure -> return@io normalized
        }
        if (rel.isEmpty()) return@io invalid(rel)
        val parentPath = WorkspacePath.parent(rel)
        val name = WorkspacePath.name(rel)
        val parent = find(parentPath) ?: return@io notFound(parentPath)
        if (!runCatching { parent.isDirectory }.getOrDefault(false)) return@io notADirectory(parentPath)
        if (!runCatching { parent.canWrite() }.getOrDefault(false)) return@io permissionDenied(parentPath)
        if (runCatching { find(rel)?.exists() == true }.getOrDefault(false)) return@io alreadyExists(rel)

        val created = runCatching { parent.createDirectory(name) }.getOrNull()
            ?: return@io ioFailed(rel)
        invalidateResolved()
        success(WorkspaceDirectory(path = rel, name = created.name ?: name, writable = true))
    }

    override suspend fun rename(path: String, newName: String): WorkspaceResult<WorkspaceNode> = io {
        val rel = when (val normalized = WorkspacePath.normalize(path)) {
            is ForgeResult.Success -> normalized.value
            is ForgeResult.Failure -> return@io normalized
        }
        if (rel.isEmpty()) return@io invalid(rel)
        val document = find(rel) ?: return@io notFound(rel)
        val target = when (val child = WorkspacePath.child(WorkspacePath.parent(rel), newName)) {
            is ForgeResult.Success -> child.value
            is ForgeResult.Failure -> return@io child
        }
        if (target != rel && runCatching { find(target)?.exists() == true }.getOrDefault(false)) {
            return@io alreadyExists(target)
        }
        val ok = runCatching { document.renameTo(newName) }.getOrDefault(false)
        if (!ok) return@io ioFailed(rel)
        invalidateResolved()
        success(nodeFor(target, runCatching { document.isDirectory }.getOrDefault(false)))
    }

    override suspend fun move(sourcePath: String, destinationPath: String): WorkspaceResult<WorkspaceNode> = io {
        val source = when (val normalized = WorkspacePath.normalize(sourcePath)) {
            is ForgeResult.Success -> normalized.value
            is ForgeResult.Failure -> return@io normalized
        }
        val destination = when (val normalized = WorkspacePath.normalize(destinationPath)) {
            is ForgeResult.Success -> normalized.value
            is ForgeResult.Failure -> return@io normalized
        }
        if (source.isEmpty() || destination.isEmpty()) return@io invalid(destinationPath)
        if (destination == source || destination.startsWith("$source/")) return@io invalid(destinationPath)

        val document = find(source) ?: return@io notFound(source)
        if (runCatching { find(destination)?.exists() == true }.getOrDefault(false)) return@io alreadyExists(destination)

        val isDirectory = runCatching { document.isDirectory }.getOrDefault(false)
        val destinationParentPath = WorkspacePath.parent(destination)
        val destinationParent = find(destinationParentPath) ?: return@io notFound(destinationParentPath)
        if (!runCatching { destinationParent.isDirectory }.getOrDefault(false)) return@io notADirectory(destinationParentPath)

        if (WorkspacePath.parent(source) == destinationParentPath) {
            val ok = runCatching { document.renameTo(WorkspacePath.name(destination)) }.getOrDefault(false)
            if (!ok) return@io ioFailed(source)
            invalidateResolved()
            return@io success(nodeFor(destination, isDirectory))
        }

        val sourceParent = find(WorkspacePath.parent(source)) ?: return@io notFound(sourcePath)
        runCatching {
            DocumentsContract.moveDocument(resolver, document.uri, sourceParent.uri, destinationParent.uri)
        }.getOrNull() ?: return@io unsupported(destinationPath)
        invalidateResolved()
        success(nodeFor(destination, isDirectory))
    }

    override suspend fun delete(path: String): WorkspaceResult<Unit> = io {
        val rel = when (val normalized = WorkspacePath.normalize(path)) {
            is ForgeResult.Success -> normalized.value
            is ForgeResult.Failure -> return@io normalized
        }
        if (rel.isEmpty()) return@io invalid(rel)
        val document = find(rel) ?: return@io notFound(rel)
        val ok = runCatching { document.delete() }.getOrDefault(false)
        if (!ok) return@io ioFailed(rel)
        invalidateResolved()
        success(Unit)
    }

    // --- helpers -----------------------------------------------------------

    /**
     * Walks the named segments of [relative] from the workspace root.
     *
     * Documents the session has already seen are served from [resolved]; only a
     * path that was never listed (a cold lookup) falls back to the provider's
     * `findFile`, which scans the whole parent directory.
     */
    private fun find(relative: String): DocumentFile? {
        if (relative.isEmpty()) return root
        resolved[relative]?.let { return it }

        var current: DocumentFile = root
        var path = ""
        for (segment in relative.split('/')) {
            path = if (path.isEmpty()) segment else "$path/$segment"
            val known = resolved[path]
            if (known != null) {
                current = known
                continue
            }
            if (!runCatching { current.isDirectory }.getOrDefault(false)) return null
            current = runCatching { current.findFile(segment) }.getOrNull() ?: return null
            remember(path, current)
        }
        return current
    }

    /** Remembers a resolved document; bounded so a deep browse cannot grow forever. */
    private fun remember(path: String, document: DocumentFile) {
        if (resolved.size >= MAX_RESOLVED_DOCUMENTS) resolved.clear()
        resolved[path] = document
    }

    /**
     * Drops resolved documents after the tree changed, so a path can never be
     * served from a document that has been renamed, moved or deleted.
     */
    private fun invalidateResolved() {
        resolved.clear()
    }

    /** What the provider says about reading [document]; providers may not report it at all. */
    private fun accessOf(document: DocumentFile): Access = try {
        when {
            document.canRead() -> Access.GRANTED
            document.exists() -> Access.UNKNOWN
            else -> Access.DENIED
        }
    } catch (denied: SecurityException) {
        Access.DENIED
    } catch (cause: Throwable) {
        Access.UNKNOWN
    }

    private fun toListNode(parentPath: String, document: DocumentFile): WorkspaceNode? {
        val name = runCatching { document.name }.getOrNull() ?: return null
        val path = if (parentPath.isEmpty()) name else "$parentPath/$name"
        val isDirectory = runCatching { document.isDirectory }.getOrDefault(false)
        remember(path, document)
        return if (isDirectory) {
            WorkspaceDirectory(path = path, name = name)
        } else {
            WorkspaceFile(path = path, name = name)
        }
    }

    private fun toDetailNode(parentPath: String, document: DocumentFile): WorkspaceNode? {
        val name = runCatching { document.name }.getOrNull() ?: return null
        val path = if (parentPath.isEmpty()) name else "$parentPath/$name"
        val readable = runCatching { document.canRead() }.getOrDefault(false)
        val writable = runCatching { document.canWrite() }.getOrDefault(false)
        return if (runCatching { document.isDirectory }.getOrDefault(false)) {
            WorkspaceDirectory(path = path, name = name, readable = readable, writable = writable)
        } else {
            WorkspaceFile(
                path = path,
                name = name,
                sizeBytes = runCatching { document.length() }.getOrDefault(0L).takeIf { it > 0L },
                lastModifiedEpochMillis = runCatching { document.lastModified() }.getOrDefault(0L).takeIf { it > 0L },
                readable = readable,
                writable = writable,
            )
        }
    }

    private fun nodeFor(path: String, isDirectory: Boolean): WorkspaceNode =
        if (isDirectory) {
            WorkspaceDirectory(path = path, name = WorkspacePath.name(path))
        } else {
            WorkspaceFile(path = path, name = WorkspacePath.name(path))
        }

    private fun mimeTypeFor(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase()
        if (extension.isEmpty()) return "text/plain"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "text/plain"
    }

    private fun readBounded(input: InputStream, limit: Long): ByteArray? {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            total += read
            if (total > limit) return null
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }

    private suspend fun <T> io(block: () -> WorkspaceResult<T>): WorkspaceResult<T> =
        withContext(Dispatchers.IO) { block() }

    private fun invalid(path: String): WorkspaceResult<Nothing> =
        failure(WorkspaceError(WorkspaceErrorCode.INVALID_PATH, "The path is not valid.", path))

    private fun notFound(path: String): WorkspaceResult<Nothing> =
        failure(WorkspaceError(WorkspaceErrorCode.NOT_FOUND, "Not found.", path))

    private fun notADirectory(path: String): WorkspaceResult<Nothing> =
        failure(WorkspaceError(WorkspaceErrorCode.NOT_A_DIRECTORY, "Not a directory.", path))

    private fun notAFile(path: String): WorkspaceResult<Nothing> =
        failure(WorkspaceError(WorkspaceErrorCode.NOT_A_FILE, "Not a file.", path))

    private fun alreadyExists(path: String): WorkspaceResult<Nothing> =
        failure(WorkspaceError(WorkspaceErrorCode.ALREADY_EXISTS, "Already exists.", path))

    private fun permissionDenied(path: String): WorkspaceResult<Nothing> =
        failure(WorkspaceError(WorkspaceErrorCode.PERMISSION_DENIED, "Access denied.", path))

    private fun tooLarge(path: String): WorkspaceResult<Nothing> =
        failure(WorkspaceError(WorkspaceErrorCode.FILE_TOO_LARGE, "File is too large to open.", path))

    private fun ioFailed(path: String): WorkspaceResult<Nothing> =
        failure(WorkspaceError(WorkspaceErrorCode.IO_FAILED, "Storage operation failed.", path))

    private fun unsupported(path: String): WorkspaceResult<Nothing> =
        failure(WorkspaceError(WorkspaceErrorCode.UNSUPPORTED_OPERATION, "Move is not supported here.", path))

    /** What the provider reports about reading a document. */
    private enum class Access { GRANTED, DENIED, UNKNOWN }

    companion object {
        private const val MAX_ENTRIES_PER_DIRECTORY = 1000
        private const val MAX_FILE_BYTES = 2L * 1024L * 1024L

        /** Upper bound for the path → document cache; cleared rather than grown. */
        private const val MAX_RESOLVED_DOCUMENTS = 4096

        private val NODE_ORDER =
            compareByDescending<WorkspaceNode> { it is WorkspaceDirectory }.thenBy { it.name.lowercase() }
    }
}
