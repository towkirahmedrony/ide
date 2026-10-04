package com.agentx.app.git

import com.agentx.app.core.failure
import com.agentx.app.core.success

/**
 * A [GitService] for bindings that have no real project behind them.
 *
 * Previews and the in-memory demo have no `/workspace`, so instead of inventing status they
 * report that nothing is available. The real service ([CliGitService]) is wired in the app.
 */
object UnavailableGitService : GitService {

    private val unavailable = GitError(
        GitErrorCode.NO_WORKSPACE,
        "No project is open, so Git is unavailable.",
    )

    override suspend fun detect(workspaceId: String): GitResult<GitDetection> =
        success(GitDetection(isRepository = false, reason = unavailable.message))

    override suspend fun status(workspaceId: String): GitResult<GitStatus> = failure(unavailable)

    override suspend fun diff(workspaceId: String, staged: Boolean, paths: List<String>): GitResult<String> =
        failure(unavailable)

    override suspend fun branches(workspaceId: String): GitResult<List<GitBranch>> = failure(unavailable)

    override suspend fun checkout(workspaceId: String, branch: String): GitResult<GitOperationResult> =
        failure(unavailable)

    override suspend fun log(workspaceId: String, limit: Int): GitResult<List<GitLogEntry>> = failure(unavailable)

    override suspend fun remotes(workspaceId: String): GitResult<List<GitRemote>> = failure(unavailable)

    override suspend fun add(workspaceId: String, paths: List<String>): GitResult<GitOperationResult> =
        failure(unavailable)

    override suspend fun commit(workspaceId: String, message: String): GitResult<GitOperationResult> =
        failure(unavailable)

    override suspend fun pull(workspaceId: String): GitResult<GitOperationResult> = failure(unavailable)

    override suspend fun push(workspaceId: String): GitResult<GitOperationResult> = failure(unavailable)
}
