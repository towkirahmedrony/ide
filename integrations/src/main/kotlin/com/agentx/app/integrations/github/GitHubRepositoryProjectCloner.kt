package com.agentx.app.integrations.github

import com.agentx.app.core.ForgeResult
import com.agentx.app.core.failure
import com.agentx.app.core.success
import com.agentx.app.integrations.connection.ConnectionId
import com.agentx.app.workspace.WorkspaceManager
import com.agentx.app.workspace.WorkspaceSession
import java.io.File

/**
 * Clones a GitHub repository into AgentX project storage and registers the clone as a project.
 *
 * A clone only becomes useful to AgentX once it is a project the user can open, edit and run, so
 * this is the second half of the clone flow rather than a separate feature: the repository is cloned
 * straight into the AgentX project root, and the directory that produced is then opened through the
 * existing [WorkspaceManager], which is the one mechanism that validates the location, persists the
 * workspace, makes it current and puts it in Recent Projects.
 *
 * There is deliberately no second registry and no second storage location. A cloned repository and a
 * project AgentX created by hand are siblings under the same root and are opened identically — the
 * only difference is which transport wrote the files, and `.git` is left exactly as the transport
 * wrote it.
 *
 * The root is injected rather than derived here so the composition root can resolve Android's
 * shared-storage location once and hand the same [File] to every creation path. Because this class
 * holds that root, a caller cannot clone anywhere else: the destination is always
 * `<AgentX>/<repository name>/`, and the existing [CloneDestinationValidator] inside the transport
 * keeps it a canonical direct child of the root.
 */
class GitHubRepositoryProjectCloner(
    private val cloner: GitHubRepositoryCloneService,
    private val workspaces: WorkspaceManager,
    /** The AgentX project root every clone lands directly inside. */
    private val projectRoot: File,
) {

    /**
     * Clones [repository] into the AgentX project root and opens the clone as the active project.
     *
     * On success the clone exists on disk, is the current workspace and is persisted, so it appears
     * in Recent Projects — the caller receives the session, exactly as
     * [WorkspaceManager.createProject] returns one.
     *
     * A clone that succeeded is never destroyed because opening it failed. The user's repository is
     * on disk, and the failure says where it is and what went wrong, so nothing that reached the
     * device is silently thrown away. A clone that failed leaves nothing behind, which is the
     * transport's own guarantee.
     */
    suspend fun cloneAndOpen(
        connectionId: ConnectionId,
        repository: GitHubRepository,
        branch: String? = null,
        onProgress: (String) -> Unit = {},
    ): ForgeResult<WorkspaceSession, GitHubRepositoryError> {
        val cloned = when (val result = cloner.clone(connectionId, repository, projectRoot, branch, onProgress)) {
            is ForgeResult.Success -> result.value
            is ForgeResult.Failure -> return result
        }

        return when (val opened = workspaces.open(cloned)) {
            is ForgeResult.Success -> success(opened.value)

            is ForgeResult.Failure -> failure(
                GitHubRepositoryError.Unknown(
                    "The repository was cloned to $cloned, but AgentX could not open it as a " +
                        "project: ${opened.error.userMessage} The clone was left on disk.",
                ),
            )
        }
    }
}
