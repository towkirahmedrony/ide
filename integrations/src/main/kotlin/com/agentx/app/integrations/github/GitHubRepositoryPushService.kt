package com.agentx.app.integrations.github

import com.agentx.app.core.ForgeError
import com.agentx.app.core.ForgeErrorCode
import com.agentx.app.core.ForgeResult
import com.agentx.app.core.failure
import com.agentx.app.core.success
import com.agentx.app.git.GitProjectProvider
import com.agentx.app.git.GitPushError
import com.agentx.app.git.GitPushFailure
import com.agentx.app.git.GitPushResult
import com.agentx.app.git.GitPushService
import com.agentx.app.git.GitPushSuccess
import com.agentx.app.integrations.connection.ConnectionCredentialGateway
import com.agentx.app.integrations.connection.ConnectionId
import kotlinx.coroutines.CancellationException
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File
import java.io.IOException

/**
 * Resolves the GitHub connection a repository write should authenticate with.
 *
 * A tiny seam so the push service never talks to the Connection Manager's whole
 * surface and can be unit-tested without one: the production resolver asks the
 * manager to authorize `GITHUB` + `repository_write`, which enforces enabled state,
 * connection status and capability before a credential is ever requested.
 */
fun interface GitHubRepositoryConnectionResolver {
    suspend fun resolveRepositoryWriteConnection(): ConnectionId?
}

/**
 * Pushes the active workspace's current branch to GitHub over JGit.
 *
 * Authentication comes only from the existing Phase-1 [ConnectionCredentialGateway]:
 * the token is lent into the JGit transport as a [UsernamePasswordCredentialsProvider]
 * password for the duration of the push, so it is never written into `.git/config`,
 * never embedded in a remote URL, and never returned to the caller. The remote URL is
 * read from the repository's own configuration and must already be a credential-free
 * HTTPS GitHub URL — a URL with an `@` (which would carry a credential) is refused
 * before the gateway is touched.
 *
 * Only a normal push can happen here: the refspec is always
 * `refs/heads/<branch>:refs/heads/<branch>`, without a leading `+`. Force pushes,
 * ref deletions and tag pushes are not expressible through this service, so there is
 * nothing for the model to enable.
 */
class JGitGitHubRepositoryPushService(
    private val credentialGateway: ConnectionCredentialGateway,
    private val connections: GitHubRepositoryConnectionResolver,
    private val projects: GitProjectProvider,
) : GitPushService {

    override suspend fun push(workspaceId: String, targetBranch: String): GitPushResult {
        val project = projects.active()
        if (project == null || !project.available) {
            return gitPushFailure(
                GitPushFailure.WORKSPACE_UNAVAILABLE,
                "No reachable project is open to push.",
            )
        }
        val hostPath = project.hostPath
        if (hostPath.isNullOrBlank()) {
            return gitPushFailure(
                GitPushFailure.WORKSPACE_UNAVAILABLE,
                "No reachable project is open to push.",
            )
        }
        // The repository is chosen by the active workspace, never by the caller: a
        // push for a workspace that is no longer the active one is refused.
        if (project.workspaceId != workspaceId) {
            return gitPushFailure(
                GitPushFailure.WORKSPACE_UNAVAILABLE,
                "The active project changed; refresh Git and try again.",
            )
        }

        val repositoryDir = File(hostPath)
        if (!repositoryDir.isDirectory) {
            return gitPushFailure(GitPushFailure.NOT_A_REPOSITORY, "The active project is not a Git repository.")
        }

        val git = try {
            Git.open(repositoryDir)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            return gitPushFailure(GitPushFailure.NOT_A_REPOSITORY, "The active project is not a Git repository.")
        }

        return try {
            pushRepository(git, targetBranch)
        } finally {
            git.close()
        }
    }

    private suspend fun pushRepository(git: Git, targetBranch: String): GitPushResult {
        val remoteUrl = git.repository.config.getString(REMOTE_SECTION, REMOTE_NAME, "url")
        if (remoteUrl == null || GitHubRepositoryCloneUrl.parse(remoteUrl) == null) {
            return gitPushFailure(
                GitPushFailure.REMOTE_NOT_GITHUB,
                "This project's origin remote is not a credential-free GitHub HTTPS remote.",
            )
        }

        val currentBranch = runCatching { git.repository.branch }.getOrNull()
            ?: return gitPushFailure(
                GitPushFailure.BRANCH_MISMATCH,
                "The repository is in a detached HEAD state; check out '$targetBranch' first.",
            )
        if (currentBranch != targetBranch) {
            return gitPushFailure(
                GitPushFailure.BRANCH_MISMATCH,
                "Refusing to push branch '$currentBranch' to '$targetBranch'; check out '$targetBranch' first.",
            )
        }

        val commitSha = runCatching { git.repository.resolve(Constants.HEAD) }.getOrNull()?.name

        val connectionId = connections.resolveRepositoryWriteConnection()
            ?: return gitPushFailure(
                GitPushFailure.NO_CONNECTION,
                "No connected GitHub account with repository write access is available.",
            )

        val handed = credentialGateway.withCredential(connectionId) { token ->
            pushWithCredentials(
                git = git,
                remote = REMOTE_NAME,
                branch = currentBranch,
                commitSha = commitSha,
                token = token,
            )
        }

        return when (handed) {
            is ForgeResult.Success -> handed.value
            is ForgeResult.Failure -> failure(gatewayPushFailure(handed.error))
        }
    }

    /**
     * Performs the authenticated push. Kept internal so the transport can be exercised
     * against a local bare repository in tests without going near GitHub.
     */
    internal fun pushWithCredentials(
        git: Git,
        remote: String,
        branch: String,
        commitSha: String?,
        token: String,
    ): GitPushResult {
        return try {
            val results = git.push()
                .setRemote(remote)
                .setRefSpecs(RefSpec(pushRefSpec(branch)))
                .setCredentialsProvider(UsernamePasswordCredentialsProvider(USERNAME, token))
                .call()

            val rejected = results
                .asSequence()
                .flatMap { it.remoteUpdates.asSequence() }
                .firstOrNull { it.status.isRejection() }

            if (rejected != null) {
                val status = rejected.status
                failure(
                    GitPushError(
                        failure = pushFailureFor(status),
                        message = "The remote rejected the push of '$branch' ($status).",
                    ),
                )
            } else {
                success(
                    GitPushSuccess(
                        remote = remote,
                        branch = branch,
                        commitSha = commitSha,
                        message = "Pushed '$branch' to '$remote'.",
                    ),
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: GitAPIException) {
            val message = error.message.orEmpty()
            failure(
                GitPushError(
                    failure = classifyPushFailure(message),
                    message = message.ifBlank { "The push failed." },
                ),
            )
        } catch (error: IOException) {
            failure(GitPushError(GitPushFailure.NETWORK, "The push could not reach GitHub."))
        } catch (error: RuntimeException) {
            // JGit also reports some transport problems as internal runtime exceptions.
            // They become the structured "unknown Git failure" category rather than a
            // raw crash; the message is the transport's own text, which never contains
            // the credential (the password is a separate string handed to the provider).
            val message = error.message.orEmpty()
            failure(
                GitPushError(
                    failure = classifyPushFailure(message),
                    message = message.ifBlank { "The push failed." },
                ),
            )
        }
    }

    private fun gatewayPushFailure(error: ForgeError): GitPushError = when (error.code) {
        ForgeErrorCode.CONNECTION_CREDENTIAL_EXPIRED -> GitPushError(
            GitPushFailure.AUTHENTICATION,
            "The GitHub credentials expired; reconnect the account and try again.",
        )
        ForgeErrorCode.CONNECTION_NOT_FOUND,
        ForgeErrorCode.CONNECTION_UNAUTHORIZED,
        -> GitPushError(
            GitPushFailure.CREDENTIAL_UNAVAILABLE,
            "The GitHub connection has no usable credential.",
        )
        else -> GitPushError(
            GitPushFailure.CREDENTIAL_UNAVAILABLE,
            "The GitHub credential could not be used.",
        )
    }

    private fun gitPushFailure(category: GitPushFailure, message: String): GitPushResult =
        failure(GitPushError(category, message))

    companion object {
        /** The remote the clone wrote; a credential-free URL the push reuses. */
        internal const val REMOTE_NAME: String = "origin"
        private const val REMOTE_SECTION: String = "remote"

        /** GitHub accepts any non-empty password with a personal/installation token over HTTPS. */
        internal const val USERNAME: String = "x-access-token"

        /**
         * The only refspec this service can build: current branch to the same-named
         * remote branch, never forced (`+`) and never a deletion (`:<ref>` or `:`).
         */
        internal fun pushRefSpec(branch: String): String =
            "${Constants.R_HEADS}$branch:${Constants.R_HEADS}$branch"

        /** Maps a JGit transport message onto a structured failure. Never carries a token. */
        internal fun classifyPushFailure(message: String): GitPushFailure {
            val lower = message.lowercase()
            return when {
                "non-fast-forward" in lower || "fetch first" in lower || "cannot lock ref" in lower ->
                    GitPushFailure.NON_FAST_FORWARD
                "not authorized" in lower || "authentication" in lower || "401" in lower ||
                    "could not read username" in lower || "could not read password" in lower ->
                    GitPushFailure.AUTHENTICATION
                "403" in lower || "forbidden" in lower || "permission denied" in lower ||
                    "permission to" in lower || "write access" in lower || "denied to" in lower ->
                    GitPushFailure.AUTHORIZATION
                "repository not found" in lower || "404" in lower -> GitPushFailure.REPOSITORY_NOT_FOUND
                "rate limit" in lower || "429" in lower -> GitPushFailure.RATE_LIMITED
                "unknownhost" in lower || "connection refused" in lower || "network" in lower ||
                    "timed out" in lower || "timeout" in lower || "unable to resolve" in lower ||
                    "unable to access" in lower ->
                    GitPushFailure.NETWORK
                "rejected" in lower || "failed to push some refs" in lower -> GitPushFailure.REMOTE_REJECTED
                else -> GitPushFailure.UNKNOWN
            }
        }

        /** Maps a JGit update status onto a structured failure. */
        internal fun pushFailureFor(status: RemoteRefUpdate.Status): GitPushFailure = when (status) {
            RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD -> GitPushFailure.NON_FAST_FORWARD
            RemoteRefUpdate.Status.REJECTED_NODELETE,
            RemoteRefUpdate.Status.REJECTED_REMOTE_CHANGED,
            RemoteRefUpdate.Status.REJECTED_OTHER_REASON,
            -> GitPushFailure.REMOTE_REJECTED
            else -> GitPushFailure.UNKNOWN
        }
    }
}

/** True when a remote update did not land (anything other than a successful or no-op update). */
internal fun RemoteRefUpdate.Status.isRejection(): Boolean = when (this) {
    RemoteRefUpdate.Status.OK,
    RemoteRefUpdate.Status.UP_TO_DATE,
    RemoteRefUpdate.Status.NOT_ATTEMPTED,
    -> false
    else -> true
}
