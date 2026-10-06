package com.agentx.app.integrations.github

import com.agentx.app.core.ForgeError
import com.agentx.app.core.ForgeErrorCode

/**
 * Errors from GitHub repository operations.
 *
 * Every one of these preserves the existing GitHub connection: none of them
 * deletes the credential, the connection record, or any other connection. A 401
 * means the stored credential is no longer usable, so the user re-authorizes;
 * it is never a reason to silently forget the connection.
 */
sealed interface GitHubRepositoryError {

    /** 401: the credential is invalid or expired and must be re-authorized. */
    data object Unauthenticated : GitHubRepositoryError {
        override fun toString(): String = "Unauthenticated"
    }

    /** 403: GitHub refused the request. */
    data object Forbidden : GitHubRepositoryError {
        override fun toString(): String = "Forbidden"
    }

    /** 404: the account or repository does not exist. */
    data object NotFound : GitHubRepositoryError {
        override fun toString(): String = "NotFound"
    }

    /** 429, or 403 with the remaining quota at zero. */
    data object RateLimited : GitHubRepositoryError {
        override fun toString(): String = "RateLimited"
    }

    /** 5xx: GitHub itself failed. */
    data class ServerError(val code: Int) : GitHubRepositoryError {
        override fun toString(): String = "ServerError($code)"
    }

    /** DNS, TLS, timeout, or no route to GitHub. */
    data object NetworkFailure : GitHubRepositoryError {
        override fun toString(): String = "NetworkFailure"
    }

    /** GitHub answered, but not with the JSON this client can read. */
    data class MalformedResponse(val detail: String) : GitHubRepositoryError {
        override fun toString(): String = "MalformedResponse($detail)"
    }

    /** The connection does not exist, is disabled, or holds no usable credential. */
    data object NoCredential : GitHubRepositoryError {
        override fun toString(): String = "NoCredential"
    }

    /** The clone destination is unusable (missing root, or something already there). */
    data class InvalidDestination(val detail: String) : GitHubRepositoryError {
        override fun toString(): String = "InvalidDestination($detail)"
    }

    /** A repository-derived path would leave the managed workspace root. */
    data class PathTraversal(val detail: String) : GitHubRepositoryError {
        override fun toString(): String = "PathTraversal($detail)"
    }

    /**
     * An unexpected failure that is none of the above.
     *
     * Cancellation is not modelled here: it propagates as the coroutine's own
     * exception so a cancelled listing or clone stays cancellable.
     */
    data class Unknown(val message: String) : GitHubRepositoryError {
        override fun toString(): String = "Unknown($message)"
    }
}

/**
 * Maps a credential-gateway refusal onto a repository error.
 *
 * The gateway's own message is kept for the cases the UI shows verbatim; nothing
 * it carries is a secret, because the manager reports connection state only.
 */
internal fun gitHubGatewayError(error: ForgeError): GitHubRepositoryError = when (error.code) {
    ForgeErrorCode.CONNECTION_CREDENTIAL_EXPIRED -> GitHubRepositoryError.Unauthenticated
    ForgeErrorCode.CONNECTION_NOT_FOUND -> GitHubRepositoryError.NoCredential
    ForgeErrorCode.CONNECTION_UNAUTHORIZED -> GitHubRepositoryError.NoCredential
    else -> GitHubRepositoryError.Unknown(error.message ?: "The GitHub credential could not be used")
}
