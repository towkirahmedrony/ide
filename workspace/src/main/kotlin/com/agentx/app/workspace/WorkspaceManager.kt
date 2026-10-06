package com.agentx.app.workspace

/**
 * Opens user-selected workspaces and remembers recent ones.
 *
 * The manager never sees Android APIs: it turns an opaque [handle] produced by
 * the platform's folder picker into an opened [Workspace] through a
 * [WorkspaceBackend], and persists only the metadata needed to reopen it.
 */
interface WorkspaceManager {

    /** The currently opened workspace, if any. */
    val current: WorkspaceSession?

    /**
     * The opaque handle the current workspace was opened with, when it is known.
     *
     * Still opaque to the domain: it is the platform's identifier (`content://` tree URI, or a
     * path), and callers must not parse it. It exists so the embedded Ubuntu runtime can
     * bind-mount the workspace at `/workspace` — a real path as-is, a SAF tree resolved to the
     * folder it names — by asking for the handle instead of guessing. `null` when nothing is
     * open.
     */
    val currentHandle: String? get() = null

    /** Recently opened workspaces, most recent first. */
    suspend fun recent(): WorkspaceResult<List<WorkspaceMetadata>>

    /** Opens [handle] and makes it the current workspace. */
    suspend fun open(handle: String): WorkspaceResult<WorkspaceSession>

    /** Reopens a remembered workspace by id. */
    suspend fun openRecent(id: WorkspaceId): WorkspaceResult<WorkspaceSession>

    /**
     * Creates a new empty project in AgentX-managed storage and makes it the current workspace.
     *
     * Nothing becomes active until the project directory has actually been created: a failure
     * leaves the previously active workspace untouched, and a project that could not be opened is
     * not registered or remembered. On success the project is exposed exactly like one that was
     * opened or cloned, so the file browser, editor, Git and the terminal's `/workspace` all use
     * it through the existing active-project mechanism. Only the project directory is created — no
     * template, source files, README or Git repository.
     */
    suspend fun createProject(name: String): WorkspaceResult<WorkspaceSession>

    /** Reopens the last workspace, or returns `null` when none is remembered. */
    suspend fun restoreLastOpened(): WorkspaceResult<WorkspaceSession>?

    /** Releases the current session without forgetting it. */
    suspend fun close()

    /** Removes a workspace from the recent list. */
    suspend fun forget(id: WorkspaceId): WorkspaceResult<Unit>
}

/** A persisted pointer back to a workspace. [handle] is opaque to the domain. */
data class WorkspaceRecord(
    val metadata: WorkspaceMetadata,
    val handle: String,
)

/**
 * Persistence port for workspace metadata. Implementations must never store
 * file contents; only the handle, display name, and last-opened time.
 */
interface WorkspaceMetadataStore {
    suspend fun records(): List<WorkspaceRecord>

    suspend fun find(id: WorkspaceId): WorkspaceRecord?

    suspend fun save(record: WorkspaceRecord)

    suspend fun delete(id: WorkspaceId)

    suspend fun lastOpenedId(): WorkspaceId?

    suspend fun setLastOpened(id: WorkspaceId?)
}

/** Turns an opaque handle into an opened [Workspace]. */
interface WorkspaceBackend {
    suspend fun open(handle: String): WorkspaceResult<Workspace>
}
