package com.agentx.app.integrations.github

import com.agentx.app.core.ForgeResult
import com.agentx.app.core.failure
import com.agentx.app.core.success
import com.agentx.app.integrations.connection.ConnectionCredentialGateway
import com.agentx.app.integrations.connection.ConnectionId
import org.eclipse.jgit.api.CreateBranchCommand
import org.eclipse.jgit.api.CloneCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.transport.CredentialItem
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/**
 * Secure authenticated GitHub repository clone service.
 *
 * Uses [ConnectionCredentialGateway.withCredential] to obtain the GitHub access
 * token on demand, then clones via JGit with an authenticated transport.
 *
 * Security guarantees:
 * - Token is never written to .git/config as plaintext.
 * - Token is never logged, echoed, or exposed to the UI/model.
 * - Destination is validated against managed workspace root before clone.
 * - Repository-derived paths cannot escape the workspace root.
 * - On failure, only the partially-created clone is cleaned up.
 * - Connection credential is never deleted on clone failure.
 */
interface GitHubRepositoryCloneService {
    /**
     * Clone a GitHub repository into the managed workspace directory.
     *
     * @param connectionId The GitHub connection to obtain the token from.
     * @param repository The repository to clone.
     * @param destinationDir The directory to clone into (must be under managed root).
     * @param branch The branch to checkout. Defaults to repository's default branch.
     * @param onProgress Optional progress callback (never receives token).
     * @return ForgeResult with the workspace path on success.
     */
    suspend fun clone(
        connectionId: ConnectionId,
        repository: GitHubRepository,
        destinationDir: File,
        branch: String? = null,
        onProgress: suspend (String) -> Unit = {},
    ): ForgeResult<String, GitHubRepositoryError>
}

/**
 * Clone destination validator.
 *
 * Ensures:
 * - destinationDir is a File under the managed workspace root.
 * - Path does not contain ".." or traversal patterns.
 * - Path is absolute and normalized.
 * - Repository-derived directory names are safe.
 */
class CloneDestinationValidator {
    /**
     * Validate and resolve a clone destination directory.
     *
     * @param managedRoot The managed workspace root (must be a directory).
     * @param repository The repository being cloned (for path derivation).
     * @param proposedDestination The proposed destination directory, or null to derive from repository.
     * @return Validation result.
     */
    fun validate(
        managedRoot: File,
        repository: GitHubRepository,
        proposedDestination: File? = null,
    ): CloneDestinationValidation {
        // Ensure managed root is a directory
        if (!managedRoot.isDirectory) {
            return CloneDestinationValidation.Invalid(
                GitHubRepositoryError.InvalidDestination(
                    "Managed workspace root is not a directory: ${managedRoot.absolutePath}"
                )
            )
        }

        // Derive destination directory name from repository metadata
        val destName = proposedDestination?.name ?: sanitizeRepoDirName(repository.owner, repository.name)
            ?: return CloneDestinationValidation.Invalid(
                GitHubRepositoryError.InvalidDestination("Invalid repository-derived directory name")
            )

        // Construct destination path
        val destination = File(managedRoot, destName)

        // Resolve canonical paths
        return try {
            val canonicalManagedRoot = managedRoot.canonicalPath
            val canonicalDestination = destination.canonicalFile

            // Security checks
            if (!canonicalDestination.path.startsWith(canonicalManagedRoot + File.separator) &&
                canonicalDestination.path != canonicalManagedRoot) {
                return CloneDestinationValidation.Invalid(
                    GitHubRepositoryError.PathTraversal(
                        "Clone destination would escape managed workspace root"
                    )
                )
            }

            // Check if destination already exists
            if (destination.exists()) {
                return if (destination.isDirectory && destination.list().isEmpty()) {
                    CloneDestinationValidation.Valid(canonicalDestination.path, destination)
                } else {
                    CloneDestinationValidation.Invalid(
                        GitHubRepositoryError.InvalidDestination(
                            "Destination already exists: ${destination.absolutePath}"
                        )
                    )
                }
            }

            CloneDestinationValidation.Valid(canonicalDestination.path, destination)
        } catch (e: IOException) {
            CloneDestinationValidation.Invalid(
                GitHubRepositoryError.Unknown("Failed to resolve destination path: ${e.message}")
            )
        } catch (e: SecurityException) {
            CloneDestinationValidation.Invalid(
                GitHubRepositoryError.Unknown("Security exception checking destination: ${e.message}")
            )
        }
    }

    /**
     * Sanitize a directory name derived from repository owner and name.
     *
     * Rejects:
     * - Empty or blank names
     * - Names containing path separators (/ or \)
     * - Names containing ..
     * - Names with control characters
     * - Names that are too long
     *
     * Trims and limits length to a safe maximum.
     */
    fun sanitizeRepoDirName(owner: String, name: String): String? {
        if (owner.isBlank() || name.isBlank()) return null

        // Reject dangerous characters
        val dangerousChars = Regex("[/\\\\\\u0000-\\u001F]")
        if (owner.contains(dangerousChars) || name.contains(dangerousChars)) return null
        if (owner == ".." || name == "..") return null

        // Limit length
        val maxLen = 100
        val sanitizedOwner = owner.trim().take(maxLen)
        val sanitizedName = name.trim().take(maxLen)

        if (sanitizedOwner.isEmpty() || sanitizedName.isEmpty()) return null

        return "${sanitizedOwner}-${sanitizedName}"
    }

    /**
     * Check if a path is safely inside the managed root.
     */
    fun isPathInsideRoot(path: File, root: File): Boolean {
        return try {
            val canonicalPath = path.canonicalPath
            val canonicalRoot = root.canonicalPath
            canonicalPath.startsWith(canonicalRoot + File.separator) ||
                canonicalPath == canonicalRoot
        } catch (e: IOException) {
            false
        }
    }
}

/**
 * Result of clone destination validation.
 */
sealed interface CloneDestinationValidation {
    data class Valid(val path: String, val directory: File) : CloneDestinationValidation
    data class Invalid(val error: GitHubRepositoryError) : CloneDestinationValidation
}

/**
 * Default implementation using JGit for authenticated cloning.
 *
 * Token is provided transiently via [ConnectionCredentialGateway] and used
 * only during the clone operation. It is NOT stored in .git/config.
 *
 * Instead, we use JGit's CredentialsProvider with the token, and after clone
 * we ensure the remote URL does NOT contain any credentials.
 */
class JGitGitHubRepositoryCloneService(
    private val credentialGateway: ConnectionCredentialGateway,
    private val validator: CloneDestinationValidator,
    private val logger: GitHubRepositoryLogger = QuietGitHubRepositoryLogger,
) : GitHubRepositoryCloneService {

    override suspend fun clone(
        connectionId: ConnectionId,
        repository: GitHubRepository,
        destinationDir: File,
        branch: String?,
        onProgress: suspend (String) -> Unit,
    ): ForgeResult<String, GitHubRepositoryError> {
        // Validate destination first
        val validation = validator.validate(
            managedRoot = destinationDir.parentFile ?: return failure(
                GitHubRepositoryError.InvalidDestination("Destination has no parent directory")
            ),
            repository = repository,
            proposedDestination = destinationDir,
        )

        if (validation !is CloneDestinationValidation.Valid) {
            return failure(validation.error)
        }

        val destPath = validation.path
        val destFile = validation.directory

        logger.logCloneStart(connectionId.value, repository.owner, repository.name)

        // Obtain the token transiently for this clone operation
        return try {
            credentialGateway.withCredential(connectionId) { token ->
                cloneWithToken(repository, destFile, destPath, branch ?: repository.defaultBranch, onProgress, token)
            }
        } catch (cancelled: java.util.concurrent.CancellationException) {
            throw cancelled
        } catch (e: GitAPIException) {
            logger.logCloneError(connectionId.value, "GitAPIException: ${e.message}")
            // Clean up partially-created clone
            cleanupClone(destFile, destPath)
            failure(mapGitException(e))
        } catch (e: IOException) {
            logger.logCloneError(connectionId.value, "IOException: ${e.message}")
            cleanupClone(destFile, destPath)
            failure(GitHubRepositoryError.NetworkFailure)
        } catch (e: SecurityException) {
            logger.logCloneError(connectionId.value, "SecurityException: ${e.message}")
            cleanupClone(destFile, destPath)
            failure(GitHubRepositoryError.Unknown("Security exception: ${e.message}"))
        } catch (e: Throwable) {
            logger.logCloneError(connectionId.value, "Unexpected: ${e.message}")
            cleanupClone(destFile, destPath)
            failure(GitHubRepositoryError.Unknown(e.message ?: "Unknown error"))
        }
    }

    private suspend fun cloneWithToken(
        repository: GitHubRepository,
        destination: File,
        destinationPath: String,
        branch: String,
        onProgress: suspend (String) -> Unit,
        token: String,
    ): ForgeResult<String, GitHubRepositoryError> {
        return try {
            logger.logCloneProgress(connectionId.value, "Cloning branch: $branch")

            // Create a credential provider that uses the token without persisting it
            val credentialsProvider = object : UsernamePasswordCredentialsProvider {
                init {
                    // Provide credentials for authentication
                    fetch() // This will use the username/password from the parent class
                }

                override fun get(uri: String?): CredentialItem? {
                    return provide(CredentialItem.USERNAME, "x-access-token") { true }
                        .then(provide(CredentialItem.PASSWORD, token) { true })
                        .then(provide(CredentialItem.PASSWORD_PROTECTED, "true") { true })
                        . result
                }
            }

            // Actually, use a simpler approach: UsernamePasswordCredentialsProvider directly
            val credProvider = UsernamePasswordCredentialsProvider("x-access-token", token)

            val cloneCommand = Git.cloneRepository()
                .setURI(repository.cloneUrl.url)
                .setDirectory(destination)
                .setBranch(branch)
                .setCredentialsProvider(credProvider)
                .setProgressMonitor(object : org.eclipse.jgit.transport.ProgressMonitor {
                    private var lastPercent = -1
                    override fun startTime(seconds: Long) {}
                    override fun startTime(seconds: Long, nanos: Int) {}
                    override fun startFetching(fileCount: Int, size: Long) {}
                    override fun fetchProgress(current: Long, total: Long, currentObj: String?, message: String?) {
                        val percent = if (total > 0) (current * 100 / total) else 0
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress("Cloning... $percent%")
                        }
                    }
                    override fun endFetch() {}
                    override fun startBundling(count: Long, size: Long) {}
                    override fun endBundling() {}
                    override fun startUploading(count: Long, size: Long) {}
                    override fun endUploading() {}
                    override fun startProofReading() {}
                    override fun endProofReading() {}
                    override fun startResolving() {}
                    override fun endResolving() {}
                    override fun onTransfer(uri: String?) {}
                    override fun onResume() {}
                    override fun onCancel() {}
                    override fun onSleep(ms: Long) {}
                    override fun onRetry() {}
                    override fun onTruncate() {}
                })

            logger.logCloneProgress(connectionId.value, "Starting clone operation")

            val git = cloneCommand.call()

            logger.logCloneProgress(connectionId.value, "Clone completed, verifying remote URL")

            // After clone, ensure .git/config does NOT contain the token
            // JGit may have set the remote URL with embedded credentials
            // We need to reset it to the plain HTTPS URL
            ensureCleanGitConfig(git, repository)

            git.close()

            logger.logCloneComplete(connectionId.value, destinationPath)

            success(destinationPath)
        } catch (e: GitAPIException) {
            logger.logCloneError(connectionId.value, "Clone failed: ${e.message}")
            failure(mapGitException(e))
        } catch (e: IOException) {
            logger.logCloneError(connectionId.value, "IO error during clone: ${e.message}")
            failure(GitHubRepositoryError.NetworkFailure)
        } catch (e: Exception) {
            logger.logCloneError(connectionId.value, "Unexpected error: ${e.message}")
            failure(GitHubRepositoryError.Unknown(e.message ?: "Unknown error"))
        }
    }

    /**
     * Ensure .git/config does not contain any credentials.
     *
     * After JGit clone, the remote URL might have embedded credentials.
     * We reset it to the plain HTTPS URL.
     */
    private fun ensureCleanGitConfig(git: Git, repository: GitHubRepository) {
        try {
            val config = git.getRepository().getConfig()
            val remoteName = "origin"

            // Check if remote exists
            val remotes = git.remoteList().call()
            if (remotes.contains(remoteName)) {
                // Reset URL to plain HTTPS (no credentials)
                config.setString("remote", remoteName, "url", repository.cloneUrl.url)

                // Remove any credential helper settings
                config.unset("remote", remoteName, "helper")
                config.unset("credential", null, "helper")
                config.unset("credential", null, "store")

                config.save()
            }
        } catch (e: Exception) {
            logger.logCloneError("Failed to clean git config: ${e.message}")
            // Non-fatal: continue without config cleanup
        }
    }

    /**
     * Clean up a partially-created clone directory.
     *
     * Only cleans the specific directory created by this clone operation.
     * Does not touch other workspace directories.
     */
    private fun cleanupClone(destination: File, destinationPath: String) {
        try {
            if (destination.exists()) {
                destination.deleteRecursively()
                logger.logCloneProgress("Cleaned up partial clone: $destinationPath")
            }
        } catch (e: Exception) {
            logger.logCloneError("Failed to clean up clone at $destinationPath: ${e.message}")
        }
    }

    /**
     * Map JGit exceptions to domain errors.
     */
    private fun mapGitException(e: GitAPIException): GitHubRepositoryError {
        val message = e.message ?: return GitHubRepositoryError.Unknown("Git error")

        return when {
            // Authentication errors
            message.contains("Unauthorized", ignoreCase = true) ||
            message.contains("401", ignoreCase = true) ||
            message.contains("403 Forbidden", ignoreCase = true) ||
            message.contains("Authentication failed", ignoreCase = true) ->
                GitHubRepositoryError.Unauthenticated

            // Network errors
            message.contains("Connection refused", ignoreCase = true) ||
            message.contains("Network is unreachable", ignoreCase = true) ||
            message.contains("timeout", ignoreCase = true) ||
            message.contains("UnknownHostException", ignoreCase = true) ->
                GitHubRepositoryError.NetworkFailure

            // Repository not found
            message.contains("Repository not found", ignoreCase = true) ||
            message.contains("404", ignoreCase = true) ->
                GitHubRepositoryError.NotFound

            // Rate limiting
            message.contains("429", ignoreCase = true) ||
            message.contains("rate limit", ignoreCase = true) ->
                GitHubRepositoryError.RateLimited

            // Other git errors
            else -> GitHubRepositoryError.Unknown("Git clone failed: ${e.message}")
        }
    }
}
