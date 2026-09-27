package dev.forge.ide.workspace

/** A node inside an opened workspace. */
sealed interface WorkspaceNode {
    /** Workspace-relative path. */
    val path: String

    /** Display name (final path segment). */
    val name: String
}

/** A file inside a workspace. */
data class WorkspaceFile(
    override val path: String,
    override val name: String,
    val sizeBytes: Long? = null,
    val lastModifiedEpochMillis: Long? = null,
    val readable: Boolean = true,
    val writable: Boolean = false,
) : WorkspaceNode

/** A directory inside a workspace. */
data class WorkspaceDirectory(
    override val path: String,
    override val name: String,
    val readable: Boolean = true,
    val writable: Boolean = false,
) : WorkspaceNode

/**
 * Workspace-scoped filesystem.
 *
 * Every path is relative to the opened workspace root ([WorkspacePath.ROOT]).
 * Implementations must reject absolute paths and path traversal, and must never
 * expose locations outside the workspace the user selected.
 */
interface WorkspaceFileSystem {

    /** Lists the direct children of [path], directories first. */
    suspend fun list(path: String = WorkspacePath.ROOT): WorkspaceResult<List<WorkspaceNode>>

    /** Returns metadata for a single entry. */
    suspend fun metadata(path: String): WorkspaceResult<WorkspaceNode>

    /** Whether an entry exists at [path]. Never throws for invalid paths. */
    suspend fun exists(path: String): Boolean

    /** Reads a file as UTF-8 text. */
    suspend fun readFile(path: String): WorkspaceResult<String>

    /** Overwrites an existing file with UTF-8 [content]. */
    suspend fun writeFile(path: String, content: String): WorkspaceResult<Unit>

    /** Creates an empty file at [path]. Parent directories must exist. */
    suspend fun createFile(path: String): WorkspaceResult<WorkspaceFile>

    /** Creates a directory at [path]. */
    suspend fun createDirectory(path: String): WorkspaceResult<WorkspaceDirectory>

    /** Renames an entry within its current directory. */
    suspend fun rename(path: String, newName: String): WorkspaceResult<WorkspaceNode>

    /** Moves an entry to [destinationPath]. */
    suspend fun move(sourcePath: String, destinationPath: String): WorkspaceResult<WorkspaceNode>

    /** Deletes a file or directory (and its contents) at [path]. */
    suspend fun delete(path: String): WorkspaceResult<Unit>
}
