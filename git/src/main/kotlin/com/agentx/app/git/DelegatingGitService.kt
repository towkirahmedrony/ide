package com.agentx.app.git

/**
 * Bindable [GitService], the same idea as the Tool System's delegating
 * resolver: the tool system registers its git tools before the app has created
 * the real service, then the app binds [CliGitService] once the workspace and
 * embedded runtime exist.
 *
 * Until [bind] is called the delegate is [UnavailableGitService], so git tools
 * fail closed with a structured "no project is open" error instead of inventing
 * a repository.
 */
class DelegatingGitService(
    @Volatile private var delegate: GitService = UnavailableGitService,
) : GitService {

    fun bind(service: GitService) {
        delegate = service
    }

    override suspend fun detect(workspaceId: String): GitResult<GitDetection> = delegate.detect(workspaceId)

    override suspend fun status(workspaceId: String): GitResult<GitStatus> = delegate.status(workspaceId)

    override suspend fun diff(workspaceId: String, staged: Boolean, paths: List<String>): GitResult<String> =
        delegate.diff(workspaceId, staged, paths)

    override suspend fun branches(workspaceId: String): GitResult<List<GitBranch>> = delegate.branches(workspaceId)

    override suspend fun checkout(workspaceId: String, branch: String): GitResult<GitOperationResult> =
        delegate.checkout(workspaceId, branch)

    override suspend fun log(workspaceId: String, limit: Int): GitResult<List<GitLogEntry>> =
        delegate.log(workspaceId, limit)

    override suspend fun remotes(workspaceId: String): GitResult<List<GitRemote>> = delegate.remotes(workspaceId)

    override suspend fun add(workspaceId: String, paths: List<String>): GitResult<GitOperationResult> =
        delegate.add(workspaceId, paths)

    override suspend fun commit(workspaceId: String, message: String): GitResult<GitOperationResult> =
        delegate.commit(workspaceId, message)

    override suspend fun pull(workspaceId: String): GitResult<GitOperationResult> = delegate.pull(workspaceId)

    override suspend fun push(workspaceId: String): GitResult<GitOperationResult> = delegate.push(workspaceId)
}
