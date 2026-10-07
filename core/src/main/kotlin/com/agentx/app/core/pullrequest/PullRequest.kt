package com.agentx.app.core.pullrequest

import com.agentx.app.core.ForgeResult

/**
 * Owner/name coordinates of the repository a pull request targets.
 *
 * Mirrors [com.agentx.app.core.verification.CiRepositoryRef] on purpose: the active
 * project's credential-free GitHub remote yields the same pair, so PR creation
 * reuses the existing repository-resolution abstraction instead of inventing one.
 */
data class PullRequestRef(val owner: String, val name: String) {
    init {
        require(owner.isNotBlank()) { "Repository owner must not be blank" }
        require(name.isNotBlank()) { "Repository name must not be blank" }
    }

    val fullName: String get() = "$owner/$name"
}

/**
 * A request to open a pull request. It carries no credential: the access token is
 * lent into the HTTP call by the credential gateway and never travels in this
 * object, in a tool argument, or in a result.
 *
 * [head] and [base] are branch names only (never refspecs), so the service cannot
 * be talked into a force update, a ref deletion, or a push.
 */
data class NewPullRequest(
    val repository: PullRequestRef,
    val title: String,
    val body: String,
    val head: String,
    val base: String,
)

/**
 * The created pull request, reduced to the non-sensitive fields the UI and the
 * model may see. It never carries a token, an Authorization header, or raw
 * credential data; [htmlUrl] is the public page GitHub reports.
 */
data class CreatedPullRequest(
    val number: Int,
    val title: String,
    val state: String,
    val headBranch: String,
    val baseBranch: String,
    val repository: PullRequestRef,
    val htmlUrl: String,
    val draft: Boolean = false,
)

/**
 * A structured reason a pull request could not be created.
 *
 * The categories are deliberately separate so the agent can tell a validation
 * problem from a permission problem from a transport problem, and none of them
 * carries a credential, a header, or a raw provider payload.
 */
sealed interface PullRequestError {

    /** No connected GitHub account with the required access is available. */
    data object NoConnection : PullRequestError {
        override fun toString(): String = "NoConnection"
    }

    /** 401: the credential is invalid or expired and must be re-authorized. */
    data object Unauthenticated : PullRequestError {
        override fun toString(): String = "Unauthenticated"
    }

    /** 403: GitHub refused the request. */
    data object Forbidden : PullRequestError {
        override fun toString(): String = "Forbidden"
    }

    /** 404: the repository, the base branch or the head branch does not exist. */
    data class NotFound(val detail: String) : PullRequestError {
        override fun toString(): String = "NotFound($detail)"
    }

    /** 409: a conflicting pull request state (e.g. a duplicate open PR). */
    data class Conflict(val detail: String) : PullRequestError {
        override fun toString(): String = "Conflict($detail)"
    }

    /** 400 or 422: GitHub rejected the request as invalid. */
    data class ValidationFailed(val detail: String) : PullRequestError {
        override fun toString(): String = "ValidationFailed($detail)"
    }

    /** 429, or 403 with the remaining quota at zero. */
    data object RateLimited : PullRequestError {
        override fun toString(): String = "RateLimited"
    }

    /** DNS, TLS, timeout, or no route to GitHub. */
    data object NetworkFailure : PullRequestError {
        override fun toString(): String = "NetworkFailure"
    }

    /** A 5xx from GitHub. */
    data class ServerError(val code: Int) : PullRequestError {
        override fun toString(): String = "ServerError($code)"
    }

    /** GitHub answered, but not with JSON this client can read. */
    data class MalformedResponse(val detail: String) : PullRequestError {
        override fun toString(): String = "MalformedResponse($detail)"
    }

    data class Unknown(val message: String) : PullRequestError {
        override fun toString(): String = "Unknown($message)"
    }
}

/**
 * The one GitHub pull-request write this platform performs: creating a pull
 * request.
 *
 * It is intentionally narrow. There is no merge, no branch creation, no branch
 * deletion, no force update and no review surface here, so a PR tool cannot be
 * talked into any of those; the credential is lent only into the HTTP call
 * through the existing credential gateway, exactly like the repository and
 * Actions clients.
 */
interface PullRequestService {

    /** Opens a pull request, or returns a structured refusal. */
    suspend fun create(request: NewPullRequest): ForgeResult<CreatedPullRequest, PullRequestError>
}
