package com.agentx.app.integrations.github

import com.agentx.app.integrations.github.GitHubRepository
import com.agentx.app.integrations.github.GitHubRepositoryError

/**
 * Current state of GitHub repository listing — separate from OAuth auth state.
 * Maps cleanly to the UI states required by the spec.
 */
data class GitHubRepositoryUiState(
    val loading: Boolean = false,
    val repositories: List<GitHubRepository> = emptyList(),
    val empty: Boolean = false,            // authenticated, no repos
    val hasMore: Boolean = false,
    val nextPage: Int? = null,
    val error: GitHubRepositoryError? = null,
    /** Count known (approximate). */
    val totalCount: Int = 0,
    /** When true, the underlying GitHub credential appears expired (401). */
    val authExpired: Boolean = false,
    /** When true, the provider returned 429. */
    val rateLimited: Boolean = false,
    /** Last refresh timestamp. */
    val refreshedAtMillis: Long = 0L,
) {
    /** True when there is a recoverable error the user can retry. */
    val recoverableError: Boolean get() = error is GitHubRepositoryError.Forbidden
        || error is GitHubRepositoryError.ServerError
        || error is GitHubRepositoryError.NetworkFailure
        || error is GitHubRepositoryError.MalformedResponse

    val displayError: String get() = when (error) {
        is GitHubRepositoryError.Unauthenticated -> "GitHub access needs re-authorization"
        is GitHubRepositoryError.Forbidden -> "GitHub access is forbidden"
        is GitHubRepositoryError.NotFound -> "GitHub resource not found"
        is GitHubRepositoryError.RateLimited -> "GitHub is rate limiting this request"
        is GitHubRepositoryError.ServerError -> "GitHub returned an error"
        is GitHubRepositoryError.NetworkFailure -> "Could not reach GitHub"
        is GitHubRepositoryError.MalformedResponse -> "GitHub response could not be read"
        is GitHubRepositoryError.NoCredential -> "No GitHub access available"
        is GitHubRepositoryError.Cancelled -> "Cancelled"
        is GitHubRepositoryError.Unknown -> "An unexpected error occurred"
        null -> ""
    }
}
