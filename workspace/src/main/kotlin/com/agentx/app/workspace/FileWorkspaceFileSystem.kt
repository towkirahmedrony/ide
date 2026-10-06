package com.agentx.app.workspace

import com.agentx.app.core.ForgeResult
import com.agentx.app.core.failure
import com.agentx.app.core.success
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * A [WorkspaceFileSystem] over an ordinary directory.
 *
 * This is the filesystem a project with a real path uses — an AgentX-managed Git clone in
 * app-private storage, or a folder the app can already read. It edits the directory itself; no
 * copy is made and nothing is cached, so a file the editor writes is immediately the same file
 * `git status` and a shell in `/workspace` see.
 *
 * Path handling is the shared [WorkspacePath] rule, applied on every entry point, plus a
 * canonical-path containment check so a symlink cannot walk out of the selected root either.
 */
class FileWorkspaceFileSystem(root: File) : WorkspaceFileSystem {

    private val root: File = runCatching { root.canonicalFile }.getOrElse { root.absoluteFile }

    override suspend fun list(path: String): WorkspaceResult<List<WorkspaceNode>> {
        val directory = when (val resolved = resolve(path)) {
            is ForgeResult.Success -> resolved.value
            is ForgeResult.Failure -> return resolved
        }
        if (!directory.exists()) return notFound(path)
        if (!directory.isDirectory) return notADirectory(path)
        val children = directory.listFiles()
            ?: return failure(WorkspaceError(WorkspaceErrorCode.PERMISSION_DENIED, "This folder could not be read.", path))
        return success(
            children
                .map { child -> nodeFor(child) }
                .sortedWith(NODE_ORDER),
        )
    }

    override suspend fun metadata(path: String): WorkspaceResult<WorkspaceNode> {
        val target = when (val resolved = resolve(path)) {
            is ForgeResult.Success -> resolved.value
            is ForgeResult.Failure -> return resolved
        }
        if (!target.exists()) return notFound(path)
        return success(nodeFor(target))
    }

    override suspend fun exists(path: String): Boolean {
        val target = resolve(path)
        return target is ForgeResult.Success && target.value.exists()
    }

    override suspend fun readFile(path: String): WorkspaceResult<String> {
        val target = when (val resolved = resolve(path)) {
            is ForgeResult.Success -> resolved.value
            is ForgeResult.Failure -> return resolved
        }
        if (!target.exists()) return notFound(path)
        if (target.isDirectory) return notAFile(path)
        if (target.length() > MAX_FILE_BYTES) return tooLarge(path)
        return runCatching { success(target.readText(Charsets.UTF_8)) }
            .getOrElse { ioFailed(path) }
    }

    override suspend fun writeFile(path: String, content: String): WorkspaceResult<Unit> {
        val target = when (val resolved = resolve(path)) {
            is ForgeResult.Success -> resolved.value
            is ForgeResult.Failure -> return resolved
        }
        if (!target.exists()) return notFound(path)
        if (target.isDirectory) return notAFile(path)
        return runCatching {
            writeAtomically(target, content)
            success(Unit)
        }.getOrElse { ioFailed(path) }
    }

    /**
     * Writes [content] beside [target] and renames it into place, so a failure
     * part-way through a write leaves the previous file untouched rather than
     * truncated. The temporary file lives in the same directory as [target], which
     * keeps the rename on one filesystem and off any path the caller did not ask
     * for; it is always removed, whether the move succeeded or failed.
     */
    private fun writeAtomically(target: File, content: String) {
        val parent = target.parentFile ?: error("A workspace file must have a parent directory")
        val temp = File.createTempFile(TEMP_PREFIX, TEMP_SUFFIX, parent)
        try {
            temp.writeText(content, Charsets.UTF_8)
            try {
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (unsupported: AtomicMoveNotSupportedException) {
                // Not every filesystem can rename atomically; a plain replace is the
                // best available guarantee there, and the temp file is still cleaned up.
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temp.exists()) runCatching { temp.delete() }
        }
    }

    override suspend fun createFile(path: String): WorkspaceResult<WorkspaceFile> {
        val target = when (val resolved = resolve(path)) {
            is ForgeResult.Success -> resolved.value
            is ForgeResult.Failure -> return resolved
        }
        if (path.isEmpty()) return invalid(path)
        if (target.exists()) return alreadyExists(path)
        val parent = target.parentFile
        if (parent == null || !parent.isDirectory) return notFound(WorkspacePath.parent(path))
        val created = runCatching { target.createNewFile() }.getOrDefault(false)
        if (!created) return ioFailed(path)
        return success(
            WorkspaceFile(path = path, name = WorkspacePath.name(path), sizeBytes = 0L, writable = true),
        )
    }

    override suspend fun createDirectory(path: String): WorkspaceResult<WorkspaceDirectory> {
        val target = when (val resolved = resolve(path)) {
            is ForgeResult.Success -> resolved.value
            is ForgeResult.Failure -> return resolved
        }
        if (path.isEmpty()) return invalid(path)
        if (target.exists()) return alreadyExists(path)
        val parent = target.parentFile
        if (parent == null || !parent.isDirectory) return notFound(WorkspacePath.parent(path))
        val created = runCatching { target.mkdir() }.getOrDefault(false)
        if (!created) return ioFailed(path)
        return success(WorkspaceDirectory(path = path, name = WorkspacePath.name(path), writable = true))
    }

    override suspend fun rename(path: String, newName: String): WorkspaceResult<WorkspaceNode> {
        val source = when (val resolved = resolve(path)) {
            is ForgeResult.Success -> resolved.value
            is ForgeResult.Failure -> return resolved
        }
        if (path.isEmpty()) return invalid(path)
        if (!source.exists()) return notFound(path)
        val destination = when (val child = WorkspacePath.child(WorkspacePath.parent(path), newName)) {
            is ForgeResult.Success -> child.value
            is ForgeResult.Failure -> return child
        }
        return moveTo(source, path, destination)
    }

    override suspend fun move(sourcePath: String, destinationPath: String): WorkspaceResult<WorkspaceNode> {
        val source = when (val resolved = resolve(sourcePath)) {
            is ForgeResult.Success -> resolved.value
            is ForgeResult.Failure -> return resolved
        }
        if (sourcePath.isEmpty() || destinationPath.isEmpty()) return invalid(destinationPath)
        if (!source.exists()) return notFound(sourcePath)
        val destination = when (val resolved = resolve(destinationPath)) {
            is ForgeResult.Success -> resolved.value
            is ForgeResult.Failure -> return resolved
        }
        if (destination.path == source.path || destination.path.startsWith(source.path + File.separator)) {
            return invalid(destinationPath)
        }
        return moveTo(source, sourcePath, destinationPath)
    }

    private fun moveTo(source: File, sourcePath: String, destinationPath: String): WorkspaceResult<WorkspaceNode> {
        val normalized = when (val clean = WorkspacePath.normalize(destinationPath)) {
            is ForgeResult.Success -> clean.value
            is ForgeResult.Failure -> return clean
        }
        if (normalized.isEmpty()) return invalid(destinationPath)
        val destination = File(root, normalized)
        if (destination.exists()) return alreadyExists(destinationPath)
        val parent = destination.parentFile
        if (parent == null || !parent.isDirectory) return notFound(WorkspacePath.parent(destinationPath))
        val isDirectory = source.isDirectory
        val moved = runCatching { source.renameTo(destination) }.getOrDefault(false)
        if (!moved) return ioFailed(sourcePath)
        return success(nodeFor(destination, isDirectory))
    }

    override suspend fun delete(path: String): WorkspaceResult<Unit> {
        val target = when (val resolved = resolve(path)) {
            is ForgeResult.Success -> resolved.value
            is ForgeResult.Failure -> return resolved
        }
        if (path.isEmpty()) return invalid(path)
        if (!target.exists()) return notFound(path)
        val removed = if (target.isDirectory) target.deleteRecursively() else target.delete()
        return if (removed) success(Unit) else ioFailed(path)
    }

    // --- helpers -----------------------------------------------------------

    /**
     * Resolves [path] into a file under the root, or a structured failure.
     *
     * [WorkspacePath.normalize] rejects absolute paths and `..` segments first; the canonical
     * containment check is the second line of defence, so a symlink inside the workspace still
     * cannot point outside it.
     */
    private fun resolve(path: String): WorkspaceResult<File> {
        val relative = when (val normalized = WorkspacePath.normalize(path)) {
            is ForgeResult.Success -> normalized.value
            is ForgeResult.Failure -> return normalized
        }
        val target = if (relative.isEmpty()) root else File(root, relative)
        return if (contained(target)) {
            success(target)
        } else {
            failure(
                WorkspaceError(
                    code = WorkspaceErrorCode.PATH_TRAVERSAL,
                    message = "The path points outside the workspace.",
                    path = path,
                ),
            )
        }
    }

    private fun contained(target: File): Boolean {
        val canonical = runCatching { target.canonicalFile }.getOrNull() ?: return false
        return canonical.path == root.path || canonical.path.startsWith(root.path + File.separator)
    }

    private fun nodeFor(file: File, isDirectory: Boolean = file.isDirectory): WorkspaceNode {
        val path = relativePath(file)
        return if (isDirectory) {
            WorkspaceDirectory(
                path = path,
                name = file.name,
                readable = file.canRead(),
                writable = file.canWrite(),
            )
        } else {
            WorkspaceFile(
                path = path,
                name = file.name,
                sizeBytes = file.length(),
                lastModifiedEpochMillis = file.lastModified(),
                readable = file.canRead(),
                writable = file.canWrite(),
            )
        }
    }

    private fun relativePath(file: File): String {
        val absolute = runCatching { file.canonicalFile }.getOrElse { file.absoluteFile }
        if (absolute.path == root.path) return WorkspacePath.ROOT
        return absolute.path.removePrefix(root.path).removePrefix(File.separator)
    }

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

    private fun tooLarge(path: String): WorkspaceResult<Nothing> =
        failure(WorkspaceError(WorkspaceErrorCode.FILE_TOO_LARGE, "File is too large to open.", path))

    private fun ioFailed(path: String): WorkspaceResult<Nothing> =
        failure(WorkspaceError(WorkspaceErrorCode.IO_FAILED, "Storage operation failed.", path))

    private companion object {
        /** Same ceiling the SAF filesystem uses, so the editor's contract is identical. */
        const val MAX_FILE_BYTES: Long = 2L * 1024L * 1024L

        const val TEMP_PREFIX: String = ".agentx-write-"
        const val TEMP_SUFFIX: String = ".tmp"

        val NODE_ORDER =
            compareByDescending<WorkspaceNode> { it is WorkspaceDirectory }.thenBy { it.name.lowercase() }
    }
}
