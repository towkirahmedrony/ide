package com.agentx.app.workspace

import com.agentx.app.core.failure

/**
 * Routes a workspace handle to the [WorkspaceBackend] that knows how to open it.
 *
 * Both project sources keep their own backend — a SAF `content://` tree through the Android
 * document provider, a real path through `java.io.File` — while callers keep depending on the one
 * [WorkspaceBackend] port. The routing decision is the only thing that is aware of the handle's
 * shape, so nothing else has to be.
 */
class RoutingWorkspaceBackend(
    private val delegateFor: (String) -> WorkspaceBackend?,
) : WorkspaceBackend {

    override suspend fun open(handle: String): WorkspaceResult<Workspace> {
        val backend = delegateFor(handle) ?: return failure(
            WorkspaceError(
                code = WorkspaceErrorCode.INVALID_HANDLE,
                message = "This workspace location is not supported.",
            ),
        )
        return backend.open(handle)
    }

    companion object {
        /**
         * Routes `content://` handles (Storage Access Framework trees) to [saf] and every other
         * handle to [files]. A Git clone is opened with its path, so it reaches [files].
         */
        fun contentAndPath(saf: WorkspaceBackend, files: WorkspaceBackend): RoutingWorkspaceBackend =
            RoutingWorkspaceBackend { handle ->
                if (handle.trim().startsWith(CONTENT_SCHEME)) saf else files
            }

        private const val CONTENT_SCHEME = "content://"
    }
}
