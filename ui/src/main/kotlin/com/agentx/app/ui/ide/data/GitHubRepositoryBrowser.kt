package com.agentx.app.ui.ide.data

/** One repository in the browser: just enough to choose it. */
data class GitHubRepoItem(
    val fullName: String,
    val isPrivate: Boolean,
    val defaultBranch: String,
)

sealed interface GitHubRepoLoad {
    data class Loaded(val items: List<GitHubRepoItem>, val truncated: Boolean) : GitHubRepoLoad

    /** [needsReconnect] is true when the account has to be connected or re-authorized. */
    data class Failed(val message: String, val needsReconnect: Boolean) : GitHubRepoLoad
}

sealed interface GitHubRepoOpen {
    data class Opened(
        val fullName: String,
        val workspaceId: String,
        val alreadyCloned: Boolean,
    ) : GitHubRepoOpen

    data class Failed(val message: String) : GitHubRepoOpen
}

/**
 * What the repository browser needs from the connected GitHub account. The app binds the
 * real implementation; previews leave it null and the screen says GitHub is unavailable.
 * No token or URL with a credential ever crosses this boundary.
 */
interface GitHubRepositoryBrowser {
    suspend fun load(): GitHubRepoLoad

    /** Clones the repository (or reuses an earlier clone) and makes it the active project. */
    suspend fun open(fullName: String): GitHubRepoOpen
}
