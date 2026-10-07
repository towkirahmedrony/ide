package com.agentx.app.integrations.github

import com.agentx.app.core.ForgeResult
import com.agentx.app.core.failure
import com.agentx.app.core.success
import com.agentx.app.integrations.connection.ConnectionCredentialGateway
import com.agentx.app.integrations.connection.ConnectionId
import kotlinx.coroutines.CancellationException
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File
import java.io.IOException

/**
 * Clones a selected GitHub repository into the managed AgentX workspace.
 *
 * The clone is authenticated with the connection's credential, lent through
 * [ConnectionCredentialGateway] for the duration of the operation only. The
 * token is passed to the transport as a password, so it is never part of the
 * remote URL and never lands in `.git/config`.
 *
 * The destination is derived from repository metadata and validated against the
 * managed root before anything is written, so a repository cannot be used to
 * choose where AgentX clones to. On any failure the partial clone is removed and
 * the connection itself is left untouched.
 */
interface GitHubRepositoryCloneService {

    /**
     * Clones [repository] under [managedRoot] and returns the cloned directory.
     *
     * @param managedRoot the AgentX-managed directory clones live in; the target is
     *        derived from the repository and must stay inside it.
     * @param branch the branch to check out; defaults to the repository's default branch.
     * @param onProgress human-readable phase messages. Never carries a credential.
     */
    suspend fun clone(
        connectionId: ConnectionId,
        repository: GitHubRepository,
        managedRoot: File,
        branch: String? = null,
        onProgress: (String) -> Unit = {},
    ): ForgeResult<String, GitHubRepositoryError>
}

/** The outcome of resolving where a clone would go. */
sealed interface CloneDestinationValidation {
    data class Valid(val directory: File) : CloneDestinationValidation
    data class Invalid(val error: GitHubRepositoryError) : CloneDestinationValidation
}

/**
 * Decides where a clone may be written.
 *
 * Two independent guarantees, because a GitHub repository name is remote input:
 * the directory name is built from validated segments (no separator, no `..`, no
 * control character), and the resolved target is checked to be inside the
 * managed root. The containment check compares canonical paths, so a symlink
 * planted at the destination resolves outside the root and is refused rather
 * than written through.
 */
class CloneDestinationValidator {

    /**
     * The folder a clone of this repository gets, or null when its metadata is not usable.
     *
     * The folder is named after the repository itself: cloning `octocat/hello-world` produces
     * `hello-world`, which is the name the user recognizes and the name a project AgentX creates by
     * hand would have.
     *
     * The owner still has to be a single safe segment even though it no longer appears in the path.
     * It is remote input, and repository metadata AgentX cannot trust is refused rather than
     * silently ignored — a name is only ever built from values that were checked.
     */
    fun directoryName(owner: String, name: String): String? {
        if (!isSafeSegment(owner) || !isSafeSegment(name)) return null
        val repositoryName = name.trim()
        return if (repositoryName.length > MAX_DIRECTORY_NAME_LENGTH) {
            repositoryName.take(MAX_DIRECTORY_NAME_LENGTH)
        } else {
            repositoryName
        }
    }

    /**
     * Resolves the directory [repository] may be cloned into under [managedRoot].
     *
     * The root is created on first use; the target itself is not.
     */
    fun validate(managedRoot: File, repository: GitHubRepository): CloneDestinationValidation {
        if (!managedRoot.exists() && !managedRoot.mkdirs()) {
            // The usual cause is storage access AgentX has not been granted, so the message names
            // where that is fixed instead of only what failed.
            return invalid(
                "The AgentX project folder could not be created. Check the storage access AgentX " +
                    "needs in Settings → Permissions, then try again.",
            )
        }
        if (!managedRoot.isDirectory) {
            return invalid("The AgentX project folder is not a directory")
        }

        val name = directoryName(repository.owner, repository.name)
            ?: return invalid("\"${repository.owner}/${repository.name}\" cannot be used as a folder name")

        val root = managedRoot.canonicalFile
        val target = File(root, name).canonicalFile
        if (target == root || !target.path.startsWith(root.path + File.separator)) {
            return CloneDestinationValidation.Invalid(
                GitHubRepositoryError.PathTraversal("A clone destination must stay inside the AgentX project folder"),
            )
        }
        if (target.exists()) {
            return invalid("A folder named \"$name\" already exists in the AgentX projects folder")
        }

        return CloneDestinationValidation.Valid(target)
    }

    private fun invalid(detail: String): CloneDestinationValidation =
        CloneDestinationValidation.Invalid(GitHubRepositoryError.InvalidDestination(detail))

    private fun isSafeSegment(value: String): Boolean {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed == "." || trimmed == "..") return false
        return trimmed.none { it == '/' || it == '\\' || it.isISOControl() }
    }

    companion object {
        /** Long enough for real repository names, short enough for any filesystem. */
        const val MAX_DIRECTORY_NAME_LENGTH: Int = 100
    }
}

/**
 * The production clone service, built on JGit.
 *
 * JGit is the transport because it takes the credential as a
 * [UsernamePasswordCredentialsProvider] rather than in the URL, which is what
 * keeps the token out of `.git/config`. The remote URL is re-written from the
 * repository's own credential-free URL after the clone as a second guarantee.
 */
class JGitGitHubRepositoryCloneService(
    private val credentialGateway: ConnectionCredentialGateway,
    private val validator: CloneDestinationValidator = CloneDestinationValidator(),
    private val logger: GitHubRepositoryLogger = QuietGitHubRepositoryLogger,
) : GitHubRepositoryCloneService {

    override suspend fun clone(
        connectionId: ConnectionId,
        repository: GitHubRepository,
        managedRoot: File,
        branch: String?,
        onProgress: (String) -> Unit,
    ): ForgeResult<String, GitHubRepositoryError> {
        val validation = validator.validate(managedRoot, repository)
        if (validation is CloneDestinationValidation.Invalid) {
            logger.logCloneFailed(connectionId.value, repository, validation.error)
            return failure(validation.error)
        }
        val destination = (validation as CloneDestinationValidation.Valid).directory

        logger.logCloneStarted(connectionId.value, repository)
        onProgress("Cloning ${repository.fullName}")

        val handed = try {
            credentialGateway.withCredential(connectionId) { token ->
                cloneWithToken(repository, destination, branch ?: repository.defaultBranch, onProgress, token)
            }
        } catch (cancelled: CancellationException) {
            discard(destination)
            throw cancelled
        } catch (error: Exception) {
            discard(destination)
            logger.logCloneFailed(connectionId.value, repository, GitHubRepositoryError.Unknown(error.message.orEmpty()))
            return failure(GitHubRepositoryError.Unknown(error.message ?: "The clone failed"))
        }

        return when (handed) {
            is ForgeResult.Success -> when (val cloned = handed.value) {
                is ForgeResult.Success -> {
                    logger.logCloneFinished(connectionId.value, cloned.value)
                    cloned
                }
                is ForgeResult.Failure -> {
                    discard(destination)
                    logger.logCloneFailed(connectionId.value, repository, cloned.error)
                    cloned
                }
            }
            is ForgeResult.Failure -> {
                discard(destination)
                val error = gitHubGatewayError(handed.error)
                logger.logCloneFailed(connectionId.value, repository, error)
                failure(error)
            }
        }
    }

    private fun cloneWithToken(
        repository: GitHubRepository,
        destination: File,
        branch: String,
        onProgress: (String) -> Unit,
        token: String,
    ): ForgeResult<String, GitHubRepositoryError> {
        return try {
            val git = Git.cloneRepository()
                .setURI(repository.cloneUrl.url)
                .setDirectory(destination)
                .setBranch(branch)
                .setCredentialsProvider(UsernamePasswordCredentialsProvider(CLONE_USERNAME, token))
                .call()
            try {
                resetRemoteUrl(git, repository)
            } finally {
                git.close()
            }
            onProgress("Cloned ${repository.fullName} at $branch")
            success(destination.path)
        } catch (error: GitAPIException) {
            failure(mapGitException(error))
        } catch (error: IOException) {
            failure(GitHubRepositoryError.NetworkFailure)
        }
    }

    /**
     * Re-writes `remote.origin.url` from the repository's credential-free URL and
     * drops anything that could make Git store or replay a credential.
     *
     * JGit is handed the token as a password rather than in the URL, so this is a
     * guarantee rather than a repair: the token is absent from `.git/config`
     * whether or not this runs.
     */
    internal fun resetRemoteUrl(git: Git, repository: GitHubRepository) {
        val config = git.repository.config
        config.setString("remote", REMOTE_NAME, "url", repository.cloneUrl.url)
        config.unset("remote", REMOTE_NAME, "pushurl")
        config.unset("remote", REMOTE_NAME, "helper")
        config.unset("credential", null, "helper")
        config.unset("credential", null, "username")
        config.unset("credential", null, "store")
        config.save()
    }

    /**
     * Maps a JGit failure onto a typed repository error.
     *
     * The transport's message is inspected because JGit reports a refused
     * authentication as a transport exception rather than a status code; the
     * message itself never carries the credential, which is a separate string.
     */
    internal fun mapGitException(error: GitAPIException): GitHubRepositoryError {
        val message = error.message.orEmpty()
        return when {
            message.containsAny("not authorized", "authentication is required", "401", "authentication failed") ->
                GitHubRepositoryError.Unauthenticated
            message.containsAny("403", "forbidden") -> GitHubRepositoryError.Forbidden
            message.containsAny("not found", "404") -> GitHubRepositoryError.NotFound
            message.containsAny("rate limit", "429") -> GitHubRepositoryError.RateLimited
            message.containsAny("unknownhost", "connection refused", "network", "timed out", "timeout", "unable to resolve") ->
                GitHubRepositoryError.NetworkFailure
            else -> GitHubRepositoryError.Unknown("The clone failed: $message")
        }
    }

    private fun String.containsAny(vararg needles: String): Boolean =
        needles.any { contains(it, ignoreCase = true) }

    /** Removes a partial clone, and only that directory. */
    private fun discard(destination: File) {
        runCatching { destination.deleteRecursively() }
    }

    private companion object {
        const val CLONE_USERNAME: String = "x-access-token"
        const val REMOTE_NAME: String = "origin"
    }
}
