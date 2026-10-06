package com.agentx.app.git

import com.agentx.app.core.ForgeResult
import com.agentx.app.core.failure

/**
 * A structured reason an agent-initiated push did not succeed.
 *
 * These are the categories the agent (and the user) can react to without parsing a
 * Git message: an expired credential is a re-authorize prompt, a non-fast-forward
 * is "the remote moved", a branch mismatch is "you are not on main". A driver for
 * this enum is the `git_push` tool's `pushCode`.
 */
enum class GitPushFailure {
    /** The remote refused the credential (401 / authentication failed). */
    AUTHENTICATION,

    /** The credential is valid but lacks permission (403 / forbidden). */
    AUTHORIZATION,

    /** DNS, TLS, timeout, or no route to the remote. */
    NETWORK,

    /** The remote repository does not exist or is not visible to the account. */
    REPOSITORY_NOT_FOUND,

    /** The remote rejected the update for a reason that is not a fast-forward conflict. */
    REMOTE_REJECTED,

    /** The remote branch has commits the local branch does not; a force-push would be required. */
    NON_FAST_FORWARD,

    /** The local branch is not the target branch (this phase only pushes the current branch to `main`). */
    BRANCH_MISMATCH,

    /** The remote rate-limited the request (429 / remaining quota at zero). */
    RATE_LIMITED,

    /** A force push was requested; the agent is never allowed to rewrite remote history. */
    FORCE_PUSH_FORBIDDEN,

    /** The operation was cancelled. */
    CANCELLED,

    /** No connected GitHub account with repository write access is available. */
    NO_CONNECTION,

    /** The active workspace is not reachable, or is no longer the active one. */
    WORKSPACE_UNAVAILABLE,

    /** The active workspace is not a Git repository. */
    NOT_A_REPOSITORY,

    /** The repository's origin remote is not a credential-free GitHub HTTPS remote. */
    REMOTE_NOT_GITHUB,

    /** The credential gateway refused to lend a credential (missing, disabled or expired). */
    CREDENTIAL_UNAVAILABLE,

    /** An unexpected failure that is none of the above. */
    UNKNOWN,
}

/**
 * A completed push. Carries only information that is safe to show the model and the
 * user: the remote name, the branch, the pushed commit and a short message. It never
 * carries a credential, a remote URL with embedded credentials, or an auth header.
 */
data class GitPushSuccess(
    val remote: String,
    val branch: String,
    val commitSha: String?,
    val message: String,
)

/**
 * A structured push failure. [message] is derived from the transport's own output,
 * which JGit builds without the credential (the credential is a separate string), and
 * it is redacted again by the tool executor before it reaches a result.
 */
data class GitPushError(
    val failure: GitPushFailure,
    val message: String,
) {
    val userMessage: String
        get() = message.ifBlank { "The push failed (${failure.name})." }
}

/** Result type for every agent-initiated push. */
typealias GitPushResult = ForgeResult<GitPushSuccess, GitPushError>

/**
 * Pushes the active workspace's current branch to the configured GitHub remote.
 *
 * The caller supplies only the workspace id: the repository is resolved from the
 * active workspace, the remote from the repository's own configuration, the target
 * branch defaults to [MAIN_BRANCH], and the credential is obtained through the
 * existing credential gateway. A push is never forced, never deletes a ref and never
 * pushes tags, so a normal push is the only remote mutation this port can perform.
 */
interface GitPushService {

    suspend fun push(workspaceId: String, targetBranch: String = MAIN_BRANCH): GitPushResult

    companion object {
        /** The only remote branch this phase pushes to. */
        const val MAIN_BRANCH: String = "main"
    }
}

/**
 * A [GitPushService] for builds with no GitHub connection behind them.
 *
 * Previews and the in-memory demo have no repository, so instead of inventing a push
 * they report that no connection is available. The real service is wired in the app.
 */
object UnavailableGitPushService : GitPushService {

    override suspend fun push(workspaceId: String, targetBranch: String): GitPushResult =
        failure(
            GitPushError(
                failure = GitPushFailure.NO_CONNECTION,
                message = "No GitHub connection is available in this build.",
            ),
        )
}

/**
 * Bindable [GitPushService], the same idea as [DelegatingGitService]: the tool system
 * registers `git_push` before the app has created the real GitHub-backed service, then
 * the app binds it once the connection infrastructure exists.
 *
 * Until [bind] is called the delegate is [UnavailableGitPushService], so the tool fails
 * closed with a structured error instead of pretending to push.
 */
class DelegatingGitPushService(
    @Volatile private var delegate: GitPushService = UnavailableGitPushService,
) : GitPushService {

    fun bind(service: GitPushService) {
        delegate = service
    }

    override suspend fun push(workspaceId: String, targetBranch: String): GitPushResult =
        delegate.push(workspaceId, targetBranch)
}

/**
 * Bindable [GitProjectProvider] for the connection layer.
 *
 * The GitHub push service is assembled by the integrations module, which runs before
 * the app has created the workspace runtime. This lets the app attach the real
 * project provider afterwards, exactly like [DelegatingGitService] does for the CLI
 * service. Until then no project is active, so a push fails closed.
 */
class DelegatingGitProjectProvider(
    @Volatile private var delegate: GitProjectProvider = GitProjectProvider { null },
) : GitProjectProvider {

    fun bind(provider: GitProjectProvider) {
        delegate = provider
    }

    override fun active(): GitProject? = delegate.active()
}
