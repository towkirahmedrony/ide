package dev.forge.ide.workspace

import dev.forge.ide.core.architecture.LayerDescriptor
import dev.forge.ide.core.architecture.LayerStatus

enum class FileKind {
    FILE,
    DIRECTORY,
}

data class FileStat(
    val path: String,
    val kind: FileKind,
    val size: Long? = null,
)

/** Identifies a workspace the runtime can open. */
data class WorkspaceDescriptor(
    val id: String,
    val name: String,
    val root: String,
)

/**
 * Filesystem port. Implementations may target device storage, a sandbox, or a
 * remote runtime; none exist yet.
 */
interface FileSystemAdapter {
    suspend fun read(path: String): String

    suspend fun write(path: String, content: String)

    suspend fun exists(path: String): Boolean

    suspend fun list(path: String): List<FileStat>
}

/** An opened workspace and its filesystem access. */
interface Workspace {
    val descriptor: WorkspaceDescriptor

    val fileSystem: FileSystemAdapter
}

/** Opens and tracks workspaces. */
interface WorkspaceRuntime {
    suspend fun open(descriptor: WorkspaceDescriptor): Workspace
}

val WORKSPACE_LAYER = LayerDescriptor(
    id = "workspace",
    title = "Workspace Runtime",
    summary = "Opens workspaces and provides isolated filesystem and process access.",
    status = LayerStatus.CONTRACT_ONLY,
)
