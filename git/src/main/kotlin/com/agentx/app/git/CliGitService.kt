package com.agentx.app.git

import com.agentx.app.core.ForgeResult
import com.agentx.app.core.failure
import com.agentx.app.core.success

/**
 * A [GitService] that runs the guest's own `git` for the active project.
 *
 * It resolves the active project on every call and refuses a call whose `workspaceId` is no
 * longer active, so switching projects cannot return the previous project's state. Nothing is
 * cached here: `git` is the source of truth, this class only shapes its output.
 *
 * @param projects supplies the active project (live).
 * @param runner runs one command inside the embedded runtime, working directory `/workspace`.
 */
class CliGitService(
    private val projects: GitProjectProvider,
    private val runner: GitCommandRunner,
    private val defaultTimeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
    private val networkTimeoutSeconds: Long = NETWORK_TIMEOUT_SECONDS,
) : GitService {

    override suspend fun detect(workspaceId: String): GitResult<GitDetection> {
        val project = resolve(workspaceId) ?: return noProject()
        return when (val output = run(project, GitCommands.isRepository(), defaultTimeoutSeconds)) {
            is GitCommandOutput.Unavailable -> unavailable(output.reason)
            is GitCommandOutput.Completed -> {
                if (output.success && output.stdout.trim() == "true") {
                    val root = run(project, GitCommands.repositoryRoot(), defaultTimeoutSeconds)
                    success(
                        GitDetection(
                            isRepository = true,
                            root = (root as? GitCommandOutput.Completed)?.stdout?.trim()?.ifBlank { null },
                        ),
                    )
                } else {
                    success(
                        GitDetection(
                            isRepository = false,
                            reason = output.output.trim().ifBlank { "Not a Git repository." },
                        ),
                    )
                }
            }
        }
    }

    override suspend fun status(workspaceId: String): GitResult<GitStatus> {
        val project = resolve(workspaceId) ?: return noProject()
        return when (val output = run(project, GitCommands.status(), defaultTimeoutSeconds)) {
            is GitCommandOutput.Unavailable -> unavailable(output.reason)
            is GitCommandOutput.Completed ->
                if (output.success) success(GitParsing.parseStatus(output.stdout))
                else commandFailure(output)
        }
    }

    override suspend fun diff(
        workspaceId: String,
        staged: Boolean,
        paths: List<String>,
    ): GitResult<String> {
        val project = resolve(workspaceId) ?: return noProject()
        return when (val output = run(project, GitCommands.diff(staged, paths), defaultTimeoutSeconds)) {
            is GitCommandOutput.Unavailable -> unavailable(output.reason)
            is GitCommandOutput.Completed ->
                if (output.success) success(output.stdout) else commandFailure(output)
        }
    }

    override suspend fun branches(workspaceId: String): GitResult<List<GitBranch>> {
        val project = resolve(workspaceId) ?: return noProject()
        return when (val output = run(project, GitCommands.branches(), defaultTimeoutSeconds)) {
            is GitCommandOutput.Unavailable -> unavailable(output.reason)
            is GitCommandOutput.Completed ->
                if (output.success) success(GitParsing.parseBranches(output.stdout))
                else commandFailure(output)
        }
    }

    override suspend fun checkout(workspaceId: String, branch: String): GitResult<GitOperationResult> {
        val project = resolve(workspaceId) ?: return noProject()
        // Only an existing branch name reaches Git; a leading dash is refused so a value can
        // never be read as an option.
        if (branch.isBlank()) return gitFailure(GitErrorCode.COMMAND_FAILED, "Choose a branch to switch to.")
        if (branch.startsWith("-")) return gitFailure(GitErrorCode.COMMAND_FAILED, "That is not a valid branch name.")
        return operation(project, GitCommands.checkout(branch), defaultTimeoutSeconds)
    }

    override suspend fun log(workspaceId: String, limit: Int): GitResult<List<GitLogEntry>> {
        val project = resolve(workspaceId) ?: return noProject()
        val bounded = limit.coerceIn(1, MAX_LOG_LIMIT)
        return when (val output = run(project, GitCommands.log(bounded), defaultTimeoutSeconds)) {
            is GitCommandOutput.Unavailable -> unavailable(output.reason)
            is GitCommandOutput.Completed -> {
                if (output.success) return success(GitParsing.parseLog(output.stdout))
                // A repository with no commits yet is empty, not broken.
                val text = output.output.lowercase()
                if ("does not have any commits yet" in text || "unknown revision" in text) {
                    success(emptyList())
                } else {
                    commandFailure(output)
                }
            }
        }
    }

    override suspend fun remotes(workspaceId: String): GitResult<List<GitRemote>> {
        val project = resolve(workspaceId) ?: return noProject()
        return when (val output = run(project, GitCommands.remotes(), defaultTimeoutSeconds)) {
            is GitCommandOutput.Unavailable -> unavailable(output.reason)
            is GitCommandOutput.Completed ->
                if (output.success) success(GitParsing.parseRemotes(output.stdout))
                else commandFailure(output)
        }
    }

    override suspend fun add(workspaceId: String, paths: List<String>): GitResult<GitOperationResult> {
        val project = resolve(workspaceId) ?: return noProject()
        val cleaned = paths.filter { it.isNotBlank() }
        if (cleaned.isEmpty()) return gitFailure(GitErrorCode.COMMAND_FAILED, "No paths to stage.")
        return operation(project, GitCommands.add(cleaned), defaultTimeoutSeconds)
    }

    override suspend fun commit(workspaceId: String, message: String): GitResult<GitOperationResult> {
        val project = resolve(workspaceId) ?: return noProject()
        val trimmed = message.trim()
        if (trimmed.isEmpty()) return gitFailure(GitErrorCode.INVALID_MESSAGE, "Enter a commit message.")
        if (trimmed.length > MAX_MESSAGE_LENGTH) {
            return gitFailure(GitErrorCode.INVALID_MESSAGE, "The commit message is too long.")
        }
        // Deliberately no `git add -A`: only what the user staged is committed.
        return operation(project, GitCommands.commit(trimmed), defaultTimeoutSeconds)
    }

    override suspend fun pull(workspaceId: String): GitResult<GitOperationResult> {
        val project = resolve(workspaceId) ?: return noProject()
        return operation(project, GitCommands.pull(), networkTimeoutSeconds)
    }

    override suspend fun push(workspaceId: String): GitResult<GitOperationResult> {
        val project = resolve(workspaceId) ?: return noProject()
        return operation(project, GitCommands.push(), networkTimeoutSeconds)
    }

    // --- internals ---------------------------------------------------------

    private suspend fun operation(
        project: GitProject,
        command: String,
        timeoutSeconds: Long,
    ): GitResult<GitOperationResult> =
        when (val output = run(project, command, timeoutSeconds)) {
            is GitCommandOutput.Unavailable -> unavailable(output.reason)
            is GitCommandOutput.Completed ->
                if (output.success) {
                    success(GitOperationResult(success = true, output = output.output.trim()))
                } else {
                    commandFailure(output)
                }
        }

    private suspend fun run(
        project: GitProject,
        command: String,
        timeoutSeconds: Long,
    ): GitCommandOutput = runner.run(project, command, timeoutSeconds)

    /** The active project for [workspaceId], or null (reported as [noProject]). */
    private fun resolve(workspaceId: String): GitProject? {
        val project = projects.active() ?: return null
        if (project.workspaceId != workspaceId) return null
        if (!project.available) return null
        return project
    }

    private fun noProject(): GitResult<Nothing> {
        val active = projects.active()
        return when {
            active == null -> gitFailure(GitErrorCode.NO_WORKSPACE, "No project is open.")
            !active.available -> unavailable(active.unavailableReason ?: "This project's files are not reachable by Git.")
            else -> gitFailure(
                GitErrorCode.NO_WORKSPACE,
                "The active project changed; refresh Git.",
            )
        }
    }

    private fun <T> unavailable(reason: String): GitResult<T> =
        failure(GitError(GitErrorCode.PROJECT_UNAVAILABLE, reason))

    private fun <T> commandFailure(output: GitCommandOutput.Completed): GitResult<T> {
        val text = output.output.trim()
        val lower = text.lowercase()
        return when {
            "not a git repository" in lower -> failure(GitError(GitErrorCode.NOT_A_REPOSITORY, text))
            looksLikeAuthFailure(lower) -> failure(
                GitError(
                    GitErrorCode.AUTH_REQUIRED,
                    "Git could not authenticate to the remote. Configure credentials in the " +
                        "terminal (for example `gh auth login`) and try again. Nothing was changed.",
                ),
            )
            else -> failure(
                GitError(
                    GitErrorCode.COMMAND_FAILED,
                    text.ifBlank { "git exited with ${output.exitCode}." },
                ),
            )
        }
    }

    private fun looksLikeAuthFailure(lowercaseOutput: String): Boolean =
        AUTH_MARKERS.any { it in lowercaseOutput }

    companion object {
        const val DEFAULT_TIMEOUT_SECONDS: Long = 120L

        /** Network operations can exceed a local command's budget. */
        const val NETWORK_TIMEOUT_SECONDS: Long = 300L

        const val MAX_LOG_LIMIT: Int = 200
        const val MAX_MESSAGE_LENGTH: Int = 5_000

        private val AUTH_MARKERS = listOf(
            "authentication failed",
            "could not read username",
            "could not read password",
            "permission denied (publickey)",
            "terminal prompts disabled",
            "invalid username or password",
            "support for password authentication was removed",
        )
    }
}
