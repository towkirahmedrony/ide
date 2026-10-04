package com.agentx.app.git

import com.agentx.app.core.ForgeResult
import com.agentx.app.core.architecture.LayerDescriptor
import com.agentx.app.core.architecture.LayerStatus

/** How a path differs from the index and/or HEAD, as `git status` reports it. */
enum class GitChangeType {
    ADDED,
    MODIFIED,
    DELETED,
    RENAMED,
    COPIED,
    UNTRACKED,
    TYPECHANGED,
    UNMERGED,
}

/**
 * One changed path.
 *
 * [staged] and [unstaged] are the two halves of `git status`'s `XY` code, kept separate so the
 * UI (and a commit) can respect the difference the brief asks for: a change that is only in the
 * working tree is not committed by a plain `git commit`.
 */
data class GitFileChange(
    val path: String,
    val type: GitChangeType,
    val staged: Boolean = false,
    val unstaged: Boolean = false,
    /** Where a rename/copy came from, when Git reported one. */
    val originalPath: String? = null,
)

/** The repository's own state, independent of whether the working tree is dirty. */
enum class GitRepositoryState {
    /** A normal repository on a branch. */
    NORMAL,

    /** A repository that has no commits yet; `HEAD` does not resolve. */
    NO_COMMITS,

    /** `HEAD` points straight at a commit, not a branch. */
    DETACHED,

    /** A bare repository has no working tree; file-level operations do not apply. */
    BARE,
}

/** What `git status --porcelain=v1 -b` reports for the active project. */
data class GitStatus(
    val branch: String? = null,
    val state: GitRepositoryState = GitRepositoryState.NORMAL,
    val upstream: String? = null,
    val ahead: Int = 0,
    val behind: Int = 0,
    val changes: List<GitFileChange> = emptyList(),
) {
    val isClean: Boolean get() = changes.isEmpty()

    val staged: List<GitFileChange> get() = changes.filter { it.staged }

    val unstaged: List<GitFileChange> get() = changes.filter { it.unstaged }
}

/** A local or remote branch. */
data class GitBranch(
    val name: String,
    val current: Boolean = false,
    val remote: Boolean = false,
    val upstream: String? = null,
)

/** A configured remote and its fetch URL. */
data class GitRemote(val name: String, val url: String)

/** One commit, as `git log` reports it. */
data class GitLogEntry(
    val hash: String,
    val shortHash: String,
    val author: String,
    val date: String,
    val subject: String,
)

/** Whether a directory is a Git repository, and where its working tree root is. */
data class GitDetection(
    val isRepository: Boolean,
    val root: String? = null,
    val reason: String? = null,
)

/** The outcome of a command that changes the repository (or tries to). */
data class GitOperationResult(
    val success: Boolean,
    val output: String,
    val error: GitError? = null,
)

/** Stable categories a caller can react to without parsing messages. */
enum class GitErrorCode {
    /** No project is open, or the requested one is no longer the active project. */
    NO_WORKSPACE,

    /** The active project is not reachable by the embedded Git (see [GitProject.available]). */
    PROJECT_UNAVAILABLE,

    /** The active project is not a Git repository. */
    NOT_A_REPOSITORY,

    /** Git ran and failed; [GitError.message] carries its own output. */
    COMMAND_FAILED,

    /** A commit message was empty or otherwise unusable. */
    INVALID_MESSAGE,

    /** The remote rejected the operation for lack of credentials. Nothing was modified. */
    AUTH_REQUIRED,
}

/** A structured Git failure, with a short, user-facing [userMessage]. */
data class GitError(
    val code: GitErrorCode,
    val message: String,
) {
    val userMessage: String
        get() = when (code) {
            GitErrorCode.NO_WORKSPACE -> "No project is open."
            GitErrorCode.PROJECT_UNAVAILABLE -> message.ifBlank {
                "This project's files are not reachable by Git."
            }
            GitErrorCode.NOT_A_REPOSITORY -> "This project is not a Git repository."
            GitErrorCode.COMMAND_FAILED -> message.ifBlank { "The Git command failed." }
            GitErrorCode.INVALID_MESSAGE -> message.ifBlank { "Enter a commit message." }
            GitErrorCode.AUTH_REQUIRED -> message.ifBlank {
                "Git needs credentials for this remote. Configure them in the terminal (for " +
                    "example with `gh auth login`) and try again."
            }
        }
}

/** Result type for every Git operation. */
typealias GitResult<T> = ForgeResult<T, GitError>

/** Convenience: a failed Git operation. */
fun gitFailure(code: GitErrorCode, message: String): GitResult<Nothing> =
    ForgeResult.Failure(GitError(code = code, message = message))

/**
 * The project Git should operate on.
 *
 * [hostPath] is the real directory the embedded runtime resolves the workspace to. It is `null`
 * when the location cannot be reached as a filesystem path (an unresolvable SAF tree, say);
 * [available] then carries [unavailableReason] so the UI can say why instead of pretending.
 */
data class GitProject(
    val workspaceId: String,
    val displayLocation: String,
    val hostPath: String? = null,
    val available: Boolean = true,
    val unavailableReason: String? = null,
)

/** Supplies the currently active project. Reads live state; never caches a project. */
fun interface GitProjectProvider {
    fun active(): GitProject?
}

/** What running a Git command produced, or why it could not be run. */
sealed interface GitCommandOutput {

    data class Completed(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean = false,
    ) : GitCommandOutput {
        val success: Boolean get() = exitCode == 0 && !timedOut
        val output: String
            get() = when {
                stderr.isEmpty() -> stdout
                stdout.isEmpty() -> stderr
                else -> stdout.trimEnd('\n') + "\n" + stderr
            }
    }

    /** Git could not be run at all (no runtime, no reachable path). */
    data class Unavailable(val reason: String) : GitCommandOutput
}

/**
 * Runs one Git command line against [project] inside the embedded runtime, with the guest
 * working directory fixed at `/workspace`. Injected so the service is unit-testable without a
 * device, and so a different backend (SSH, cloud) can supply the same seam later.
 */
fun interface GitCommandRunner {
    suspend fun run(project: GitProject, command: String, timeoutSeconds: Long): GitCommandOutput
}

/**
 * Git operations for the active project.
 *
 * Every method takes the `workspaceId` the caller is showing. The implementation resolves the
 * active project for that id on each call, so a stale result from a previous project can never
 * be returned for the new one — Git itself, not an Android-side cache, is the source of truth.
 */
interface GitService {

    /** Whether the active project is a Git repository, and where its root is. */
    suspend fun detect(workspaceId: String): GitResult<GitDetection>

    /** The equivalent of `git status` in the project's `/workspace`. */
    suspend fun status(workspaceId: String): GitResult<GitStatus>

    /**
     * `git diff` (working tree against the index) or `git diff --cached` (index against HEAD).
     * [paths] limits the diff to specific repository-relative paths.
     */
    suspend fun diff(
        workspaceId: String,
        staged: Boolean = false,
        paths: List<String> = emptyList(),
    ): GitResult<String>

    /** Local and remote branches. */
    suspend fun branches(workspaceId: String): GitResult<List<GitBranch>>

    /** Switches to an existing branch. */
    suspend fun checkout(workspaceId: String, branch: String): GitResult<GitOperationResult>

    /** Recent commits, newest first. */
    suspend fun log(workspaceId: String, limit: Int = DEFAULT_LOG_LIMIT): GitResult<List<GitLogEntry>>

    /** The configured remotes. */
    suspend fun remotes(workspaceId: String): GitResult<List<GitRemote>>

    /** Stages exactly the given paths; never stages the whole tree implicitly. */
    suspend fun add(workspaceId: String, paths: List<String>): GitResult<GitOperationResult>

    /** Commits what is staged. An empty or blank [message] is rejected before Git runs. */
    suspend fun commit(workspaceId: String, message: String): GitResult<GitOperationResult>

    /** Pulls the current branch from its upstream using the repository's existing auth. */
    suspend fun pull(workspaceId: String): GitResult<GitOperationResult>

    /** Pushes the current branch using the repository's existing auth. */
    suspend fun push(workspaceId: String): GitResult<GitOperationResult>

    companion object {
        const val DEFAULT_LOG_LIMIT: Int = 20
    }
}

val GIT_LAYER = LayerDescriptor(
    id = "git",
    title = "Git",
    summary = "Runs Git against the active project's /workspace so status, diffs and commits " +
        "reflect the same files the IDE and terminal use.",
    status = LayerStatus.ACTIVE,
)
