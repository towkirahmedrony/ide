package com.agentx.app.workspace

import com.agentx.app.core.ForgeResult
import com.agentx.app.core.failure
import com.agentx.app.core.success
import java.time.Clock

/**
 * Platform-independent [WorkspaceManager]. It coordinates a [WorkspaceBackend]
 * (which knows how to actually open a location) and a [WorkspaceMetadataStore]
 * (which remembers it), so the Android integration stays isolated behind ports.
 */
class DefaultWorkspaceManager(
    private val backend: WorkspaceBackend,
    private val store: WorkspaceMetadataStore,
    /**
     * Where newly created projects are made. When absent, [createProject] reports that creating
     * projects is unsupported instead of guessing a location — previews and tests that only need
     * to open existing workspaces keep working unchanged.
     */
    private val projects: ManagedProjectDirectory? = null,
    private val clock: Clock = Clock.systemUTC(),
    /**
     * The AgentX-owned data a project can leave behind, so [delete] can remove it. Defaults to
     * "none": a build with no runtime that copies a project owns nothing extra, and its delete is
     * then exactly a forget plus the record's removal.
     */
    private val projectStorage: WorkspaceProjectStorage = NoWorkspaceProjectStorage,
) : WorkspaceManager {

    private var session: WorkspaceSession? = null
    private var currentHandleValue: String? = null

    override val current: WorkspaceSession? get() = session

    override val currentHandle: String? get() = currentHandleValue

    override suspend fun recent(): WorkspaceResult<List<WorkspaceMetadata>> = runCatching {
        store.records()
            .map { it.metadata }
            .sortedByDescending { it.lastOpenedAtEpochMillis ?: 0L }
    }.fold(
        onSuccess = { success(it) },
        onFailure = { failure(WorkspaceError(WorkspaceErrorCode.UNKNOWN, "Could not load recent workspaces.", cause = it)) },
    )

    override suspend fun open(handle: String): WorkspaceResult<WorkspaceSession> {
        val normalizedHandle = handle.trim()
        if (normalizedHandle.isEmpty()) {
            return failure(WorkspaceError(WorkspaceErrorCode.INVALID_HANDLE, "No workspace was selected."))
        }

        val opened = when (val result = backend.open(normalizedHandle)) {
            is ForgeResult.Success -> result.value
            is ForgeResult.Failure -> return result
        }

        val metadata = opened.metadata.copy(
            lastOpenedAtEpochMillis = clock.millis(),
            persisted = true,
        )
        val refreshed = DefaultWorkspace(metadata = metadata, fileSystem = opened.fileSystem)
        val newSession = DefaultWorkspaceSession(refreshed)
        session?.close()
        session = newSession
        currentHandleValue = normalizedHandle

        runCatching {
            store.save(WorkspaceRecord(metadata = metadata, handle = normalizedHandle))
            store.setLastOpened(metadata.id)
        }

        return success(newSession)
    }

    override suspend fun openRecent(id: WorkspaceId): WorkspaceResult<WorkspaceSession> {
        val record = store.find(id)
            ?: return failure(WorkspaceError(WorkspaceErrorCode.WORKSPACE_NOT_FOUND, "This workspace is not in your recent list."))
        return open(record.handle)
    }

    override suspend fun createProject(name: String): WorkspaceResult<WorkspaceSession> {
        val projects = projects
            ?: return failure(
                WorkspaceError(
                    WorkspaceErrorCode.UNSUPPORTED_OPERATION,
                    "Creating projects is not supported in this environment.",
                ),
            )

        val path = when (val created = projects.create(name)) {
            is ForgeResult.Success -> created.value
            is ForgeResult.Failure -> return created
        }

        // The project becomes active through the one existing mechanism: opening its path. If the
        // directory cannot be opened, nothing is made active and the empty leftover is discarded
        // so retrying the same name is not blocked.
        return when (val opened = open(path)) {
            is ForgeResult.Success -> opened
            is ForgeResult.Failure -> {
                projects.discard(path)
                opened
            }
        }
    }

    override suspend fun restoreLastOpened(): WorkspaceResult<WorkspaceSession>? {
        val id = store.lastOpenedId() ?: return null
        return openRecent(id)
    }

    override suspend fun close() {
        session?.close()
        session = null
        currentHandleValue = null
    }

    override suspend fun forget(id: WorkspaceId): WorkspaceResult<Unit> {
        runCatching { store.delete(id) }
            .onFailure { return failure(WorkspaceError(WorkspaceErrorCode.UNKNOWN, "Could not remove the workspace.", cause = it)) }
        if (session?.workspace?.id == id) {
            session?.close()
            session = null
            currentHandleValue = null
        }
        return success(Unit)
    }

    /**
     * Removes the project: its AgentX-owned data first, its record second.
     *
     * That order is the point. The record is what makes a project reachable, so dropping it first
     * would orphan whatever the cleanup then failed on — unreachable data, and no way for the user
     * to ask for its removal again. Cleanup first means a failure leaves the project in the list,
     * visible and retryable, and nothing was half-deleted: the storage layer reports what it could
     * not remove instead of silently succeeding.
     *
     * Nothing here reaches outside AgentX's own storage. The folder the user picked is not touched
     * — see [WorkspaceManager.delete] and [WorkspaceProjectStorage] for what is in scope.
     */
    override suspend fun delete(id: WorkspaceId): WorkspaceResult<Unit> {
        val record = runCatching { store.find(id) }
            .getOrElse {
                return failure(
                    WorkspaceError(WorkspaceErrorCode.UNKNOWN, "Could not read the project.", cause = it),
                )
            }
            // Already removed (or never known): a delete is idempotent, and this is not an error.
            ?: return success(Unit)

        val report = runCatching { projectStorage.remove(record) }
            .getOrElse {
                return failure(
                    WorkspaceError(
                        WorkspaceErrorCode.UNKNOWN,
                        "Could not remove the data AgentX created for this project.",
                        cause = it,
                    ),
                )
            }
        if (!report.ok) {
            // The project stays in the list on purpose: the user asked for it gone, it is not
            // gone, and hiding it would be the one outcome they cannot act on.
            return failure(
                WorkspaceError(
                    WorkspaceErrorCode.UNKNOWN,
                    "Could not remove everything AgentX created for this project, so it has been " +
                        "left in your list. Nothing else was deleted.",
                    path = report.failed.first(),
                ),
            )
        }

        runCatching { store.delete(id) }
            .onFailure { return failure(WorkspaceError(WorkspaceErrorCode.UNKNOWN, "Could not remove the workspace.", cause = it)) }

        // The project is gone, so a session pointing at it is stale. Closing it releases the
        // runtime resources and unmounts the project; it never deletes the project itself, which
        // is what leaving the project normally does too.
        if (session?.workspace?.id == id) {
            session?.close()
            session = null
            currentHandleValue = null
        }
        return success(Unit)
    }
}
