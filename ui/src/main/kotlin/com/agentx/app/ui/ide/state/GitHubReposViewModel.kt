package com.agentx.app.ui.ide.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentx.app.ui.ide.data.GitHubRepoItem
import com.agentx.app.ui.ide.data.GitHubRepoLoad
import com.agentx.app.ui.ide.data.GitHubRepoOpen
import com.agentx.app.integrations.github.GitHubDiagnostics
import com.agentx.app.ui.ide.data.GitHubRepositoryBrowser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

enum class RepoVisibilityFilter(val label: String) {
    ALL("All"),
    PUBLIC("Public"),
    PRIVATE("Private"),
}

/** State of the "all my repositories" page. */
class GitHubReposViewModel(
    private val browser: GitHubRepositoryBrowser?,
) : ViewModel() {

    // Every property is declared before `init`, which starts the first load.

    var loading by mutableStateOf(false)
        private set

    var repos by mutableStateOf<List<GitHubRepoItem>>(emptyList())
        private set

    /** True when the account has more repositories than the listing cap. */
    var truncated by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    var needsReconnect by mutableStateOf(false)
        private set

    /** The repository being cloned or opened right now. */
    var openingName by mutableStateOf<String?>(null)
        private set

    /** Set once a repository is open; the screen navigates and then consumes it. */
    var openedWorkspaceId by mutableStateOf<String?>(null)
        private set

    var query by mutableStateOf("")

    var filter by mutableStateOf(RepoVisibilityFilter.ALL)

    val available: Boolean get() = browser != null

    val visible: List<GitHubRepoItem>
        get() {
            val needle = query.trim()
            return repos
                .filter { repo ->
                    when (filter) {
                        RepoVisibilityFilter.ALL -> true
                        RepoVisibilityFilter.PUBLIC -> !repo.isPrivate
                        RepoVisibilityFilter.PRIVATE -> repo.isPrivate
                    }
                }
                .filter { needle.isEmpty() || it.fullName.contains(needle, ignoreCase = true) }
        }

    init {
        refresh()
    }

    fun refresh() {
        val source = browser
        if (source == null) {
            GitHubDiagnostics.warn(
                GitHubDiagnostics.STAGE_REPOSITORIES,
                "repository fetch skipped: GitHub is not available in this build",
            )
            error = "GitHub is not available in this build."
            return
        }
        if (loading) {
            GitHubDiagnostics.warn(
                GitHubDiagnostics.STAGE_REPOSITORIES,
                "repository fetch skipped: a load is already in progress",
            )
            return
        }
        loading = true
        error = null
        needsReconnect = false
        GitHubDiagnostics.repositories("repository fetch requested")
        viewModelScope.launch {
            try {
                when (val result = source.load()) {
                    is GitHubRepoLoad.Loaded -> {
                        repos = result.items.sortedBy { it.fullName.lowercase() }
                        truncated = result.truncated
                        GitHubDiagnostics.repositories(
                            "repository fetch succeeded",
                            mapOf("count" to repos.size, "truncated" to truncated),
                        )
                    }

                    is GitHubRepoLoad.Failed -> {
                        GitHubDiagnostics.failure(
                            GitHubDiagnostics.STAGE_REPOSITORIES,
                            "repository fetch failed",
                            fields = mapOf(
                                "needsReconnect" to result.needsReconnect,
                                "message" to result.message,
                            ),
                        )
                        error = result.message
                        needsReconnect = result.needsReconnect
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                GitHubDiagnostics.failure(
                    GitHubDiagnostics.STAGE_REPOSITORIES,
                    "repository fetch threw",
                    failure,
                )
                error = failure.message ?: "The repositories could not be loaded."
            } finally {
                loading = false
            }
        }
    }

    fun open(fullName: String) {
        val source = browser ?: return
        if (openingName != null) return
        GitHubDiagnostics.repositories("repository open requested", mapOf("fullName" to fullName))
        openingName = fullName
        error = null
        viewModelScope.launch {
            try {
                when (val result = source.open(fullName)) {
                    is GitHubRepoOpen.Opened -> {
                        GitHubDiagnostics.repositories(
                            "repository open succeeded",
                            mapOf("fullName" to fullName, "alreadyCloned" to result.alreadyCloned),
                        )
                        openedWorkspaceId = result.workspaceId
                    }
                    is GitHubRepoOpen.Failed -> {
                        GitHubDiagnostics.failure(
                            GitHubDiagnostics.STAGE_REPOSITORIES,
                            "repository open failed",
                            fields = mapOf("fullName" to fullName, "message" to result.message),
                        )
                        error = result.message
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                GitHubDiagnostics.failure(
                    GitHubDiagnostics.STAGE_REPOSITORIES,
                    "repository open threw",
                    failure,
                    mapOf("fullName" to fullName),
                )
                error = failure.message ?: "The repository could not be opened."
            } finally {
                openingName = null
            }
        }
    }

    fun consumeOpened() {
        openedWorkspaceId = null
    }
}
