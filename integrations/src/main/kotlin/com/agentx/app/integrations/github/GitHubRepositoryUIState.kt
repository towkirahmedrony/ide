package com.agentx.app.integrations.github

/**
 * What a GitHub repository picker renders: the repositories discovered so far,
 * whether more can be loaded, and the one error worth showing.
 *
 * It is deliberately separate from OAuth authorization state — discovering a
 * repository never changes whether the connection is authorized.
 */
data class GitHubRepositoryUiState(
    val loading: Boolean = false,
    val repositories: List<GitHubRepository> = emptyList(),
    /** True only after a successful listing that returned nothing. */
    val empty: Boolean = false,
    val hasMore: Boolean = false,
    val nextPage: Int? = null,
    val error: GitHubRepositoryError? = null,
    /** The most recent implied total; a lower bound, and 0 before any listing. */
    val totalCount: Int = 0,
    /** The credential is no longer usable, so the connection must be re-authorized. */
    val authExpired: Boolean = false,
    /** GitHub is throttling this account. */
    val rateLimited: Boolean = false,
    val refreshedAtMillis: Long = 0L,
) {
    /** True when retrying the same request could succeed. */
    val recoverableError: Boolean
        get() = error is GitHubRepositoryError.Forbidden ||
            error is GitHubRepositoryError.ServerError ||
            error is GitHubRepositoryError.NetworkFailure ||
            error is GitHubRepositoryError.MalformedResponse

    /** The line to show for the current error, or empty when there is none. */
    val displayError: String
        get() = when (error) {
            is GitHubRepositoryError.Unauthenticated -> "GitHub access needs re-authorization"
            is GitHubRepositoryError.Forbidden -> "GitHub refused this request"
            is GitHubRepositoryError.NotFound -> "GitHub could not find that repository"
            is GitHubRepositoryError.RateLimited -> "GitHub is rate limiting this account"
            is GitHubRepositoryError.ServerError -> "GitHub returned an error"
            is GitHubRepositoryError.NetworkFailure -> "GitHub could not be reached"
            is GitHubRepositoryError.MalformedResponse -> "GitHub's response could not be read"
            is GitHubRepositoryError.NoCredential -> "No GitHub access is available"
            is GitHubRepositoryError.InvalidDestination -> "That repository cannot be cloned here"
            is GitHubRepositoryError.PathTraversal -> "That repository cannot be cloned here"
            is GitHubRepositoryError.Unknown -> "An unexpected error occurred"
            null -> ""
        }
}
