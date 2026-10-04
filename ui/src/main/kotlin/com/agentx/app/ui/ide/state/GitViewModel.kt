package com.agentx.app.ui.ide.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentx.app.core.ForgeResult
import com.agentx.app.git.GitBranch
import com.agentx.app.git.GitChangeType
import com.agentx.app.git.GitFileChange
import com.agentx.app.git.GitLogEntry
import com.agentx.app.git.GitOperationResult
import com.agentx.app.git.GitRemote
import com.agentx.app.git.GitRepositoryState
import com.agentx.app.git.GitResult
import com.agentx.app.git.GitService
import com.agentx.app.ui.ide.model.GitBranchInfo
import com.agentx.app.ui.ide.model.GitChange
import com.agentx.app.ui.ide.model.GitLogInfo
import com.agentx.app.ui.ide.model.GitRemoteInfo
import kotlinx.coroutines.launch

data class GitUiState(
    val loading: Boolean = true,
    /** Git is running an operation (commit, stage, pull, push, branch switch). */
    val busy: Boolean = false,
    /** The active project is a Git repository. */
    val available: Boolean = false,
    /** Why the project is not a repository, when [available] is false but nothing failed. */
    val repositoryMessage: String? = null,
    /** The query itself failed (no runtime, no project, Git error). */
    val error: String? = null,
    val branch: String? = null,
    val repositoryState: GitRepositoryState? = null,
    val upstream: String? = null,
    val ahead: Int = 0,
    val behind: Int = 0,
    val changes: List<GitChange> = emptyList(),
    val workingDiff: String = "",
    val stagedDiff: String = "",
    val branches: List<GitBranchInfo> = emptyList(),
    val remotes: List<GitRemoteInfo> = emptyList(),
    val log: List<GitLogInfo> = emptyList(),
    /** Result of the last operation, shown as a banner. */
    val message: String? = null,
    val commitMessage: String = "",
    val commitError: String? = null,
) {
    val hasChanges: Boolean get() = changes.isNotEmpty()
    val stagedCount: Int get() = changes.count { it.staged }
    val unstagedCount: Int get() = changes.count { it.unstaged }
    val hasRemote: Boolean get() = remotes.isNotEmpty()
    val hasUpstream: Boolean get() = !upstream.isNullOrBlank()
    val isClean: Boolean get() = available && changes.isEmpty()
}

/**
 * Drives the Git screen against the real [GitService].
 *
 * Every call passes this workspace's id, so the service can refuse a result that belongs to a
 * project the user has since switched away from — the Git context of one project never surfaces
 * for another.
 */
class GitViewModel(
    private val workspaceId: String,
    private val service: GitService,
) : ViewModel() {

    var uiState by mutableStateOf(GitUiState())
        private set

    init {
        refresh()
    }

    /** Re-queries the repository: status, both diffs, branches, remotes and the log. */
    fun refresh() {
        viewModelScope.launch {
            uiState = uiState.copy(loading = uiState.branch == null && !uiState.available, error = null)
            load()
        }
    }

    fun editCommitMessage(text: String) {
        uiState = uiState.copy(commitMessage = text, commitError = null)
    }

    fun clearCommitError() {
        uiState = uiState.copy(commitError = null)
    }

    fun dismissMessage() {
        uiState = uiState.copy(message = null)
    }

    /** Stages exactly one path; the whole tree is never staged implicitly. */
    fun stage(path: String) {
        runOperation("Staged $path") { service.add(workspaceId, listOf(path)) }
    }

    fun commit() {
        val message = uiState.commitMessage.trim()
        if (message.isEmpty()) {
            uiState = uiState.copy(commitError = "Enter a commit message.")
            return
        }
        viewModelScope.launch {
            uiState = uiState.copy(busy = true, message = null, commitError = null)
            when (val result = service.commit(workspaceId, message)) {
                is ForgeResult.Success -> {
                    uiState = uiState.copy(commitMessage = "")
                    load()
                    uiState = uiState.copy(busy = false, message = "Committed")
                }

                is ForgeResult.Failure ->
                    uiState = uiState.copy(busy = false, message = result.error.userMessage)
            }
        }
    }

    fun checkout(branch: String) {
        runOperation("Switched to $branch") { service.checkout(workspaceId, branch) }
    }

    fun pull() {
        runOperation("Pulled from the remote") { service.pull(workspaceId) }
    }

    fun push() {
        runOperation("Pushed to the remote") { service.push(workspaceId) }
    }

    // --- internals ---------------------------------------------------------

    private suspend fun load() {
        when (val detection = service.detect(workspaceId)) {
            is ForgeResult.Failure -> {
                uiState = uiState.copy(
                    loading = false,
                    available = false,
                    error = null,
                    repositoryMessage = detection.error.userMessage,
                )
                return
            }

            is ForgeResult.Success -> if (!detection.value.isRepository) {
                uiState = uiState.copy(
                    loading = false,
                    available = false,
                    error = null,
                    repositoryMessage = detection.value.reason ?: "This project is not a Git repository.",
                )
                return
            }
        }

        val reported = when (val status = service.status(workspaceId)) {
            is ForgeResult.Failure -> {
                uiState = uiState.copy(
                    loading = false,
                    available = false,
                    error = null,
                    repositoryMessage = status.error.userMessage,
                )
                return
            }

            is ForgeResult.Success -> status.value
        }

        uiState = uiState.copy(
            loading = false,
            available = true,
            repositoryMessage = null,
            error = null,
            branch = reported.branch,
            repositoryState = reported.state,
            upstream = reported.upstream,
            ahead = reported.ahead,
            behind = reported.behind,
            changes = reported.changes.map { it.toUi() },
            workingDiff = service.diff(workspaceId, staged = false).valueOrNull().orEmpty(),
            stagedDiff = service.diff(workspaceId, staged = true).valueOrNull().orEmpty(),
            branches = service.branches(workspaceId).valueOrNull().orEmpty().map { it.toUi() },
            remotes = service.remotes(workspaceId).valueOrNull().orEmpty().map { it.toUi() },
            log = service.log(workspaceId).valueOrNull().orEmpty().map { it.toUi() },
        )
    }

    private fun runOperation(successMessage: String, block: suspend () -> GitResult<GitOperationResult>) {
        viewModelScope.launch {
            uiState = uiState.copy(busy = true, message = null)
            when (val result = block()) {
                is ForgeResult.Success -> {
                    load()
                    uiState = uiState.copy(busy = false, message = successMessage)
                }

                is ForgeResult.Failure ->
                    uiState = uiState.copy(busy = false, message = result.error.userMessage)
            }
        }
    }

    private fun GitFileChange.toUi(): GitChange = GitChange(
        path = path,
        status = type.statusLabel(),
        staged = staged,
        unstaged = unstaged,
        originalPath = originalPath,
    )

    private fun GitBranch.toUi(): GitBranchInfo =
        GitBranchInfo(name = name, current = current, remote = remote, upstream = upstream)

    private fun GitRemote.toUi(): GitRemoteInfo = GitRemoteInfo(name = name, url = url)

    private fun GitLogEntry.toUi(): GitLogInfo =
        GitLogInfo(shortHash = shortHash, author = author, date = date, subject = subject)

    private fun GitChangeType.statusLabel(): String = when (this) {
        GitChangeType.ADDED -> "added"
        GitChangeType.MODIFIED -> "modified"
        GitChangeType.DELETED -> "deleted"
        GitChangeType.RENAMED -> "renamed"
        GitChangeType.COPIED -> "copied"
        GitChangeType.UNTRACKED -> "untracked"
        GitChangeType.TYPECHANGED -> "typechange"
        GitChangeType.UNMERGED -> "unmerged"
    }
}
