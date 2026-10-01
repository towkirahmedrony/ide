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
    private val clock: Clock = Clock.systemUTC(),
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
}
