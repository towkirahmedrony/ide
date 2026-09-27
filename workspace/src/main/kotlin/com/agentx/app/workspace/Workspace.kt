package com.agentx.app.workspace

/** Stable identity for an opened workspace. */
@JvmInline
value class WorkspaceId(val value: String)

/**
 * The minimum metadata required to show and reopen a workspace. It never
 * contains file contents; [displayLocation] is presentation-only and is not a
 * guarantee that the location can be opened directly.
 */
data class WorkspaceMetadata(
    val id: WorkspaceId,
    val name: String,
    val displayLocation: String,
    val lastOpenedAtEpochMillis: Long? = null,
    val persisted: Boolean = false,
)

/** An opened workspace and its scoped filesystem. */
interface Workspace {
    val id: WorkspaceId
    val metadata: WorkspaceMetadata
    val fileSystem: WorkspaceFileSystem
}

/** A live workspace session. Closing it releases runtime resources, not user data. */
interface WorkspaceSession {
    val workspace: Workspace
    val fileSystem: WorkspaceFileSystem

    fun close()
}

/** Default [Workspace] backed by any [WorkspaceFileSystem] implementation. */
class DefaultWorkspace(
    override val metadata: WorkspaceMetadata,
    override val fileSystem: WorkspaceFileSystem,
) : Workspace {
    override val id: WorkspaceId get() = metadata.id
}

/** Default [WorkspaceSession] that simply exposes a [Workspace]. */
class DefaultWorkspaceSession(override val workspace: Workspace) : WorkspaceSession {
    override val fileSystem: WorkspaceFileSystem get() = workspace.fileSystem

    override fun close() = Unit
}
