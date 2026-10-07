package com.agentx.app.integrations.github

import com.agentx.app.core.ForgeError
import com.agentx.app.core.ForgeErrorCode
import com.agentx.app.core.ForgeResult
import com.agentx.app.core.failure
import com.agentx.app.core.pullrequest.CreatedPullRequest
import com.agentx.app.core.pullrequest.NewPullRequest
import com.agentx.app.core.pullrequest.PullRequestError
import com.agentx.app.core.pullrequest.PullRequestRef
import com.agentx.app.core.pullrequest.PullRequestService
import com.agentx.app.integrations.connection.ConnectionCredentialGateway
import com.agentx.app.integrations.connection.ConnectionId
import com.agentx.app.integrations.oauth.OAuthJson
import com.agentx.app.integrations.oauth.OAuthJsonValue
import com.agentx.app.integrations.oauth.long
import com.agentx.app.integrations.oauth.string
import kotlinx.coroutines.CancellationException
import java.io.IOException

/**
 * Resolves the GitHub connection a pull-request write should authenticate with.
 *
 * A tiny seam, mirroring the push and Actions resolvers, so the PR client never
 * depends on the Connection Manager's whole surface: the production resolver asks
 * the manager to authorize `GITHUB` + `pull_request`, which enforces enabled
 * state, connection status and capability before a credential is requested.
 */
fun interface GitHubPullRequestConnectionResolver {
    suspend fun resolvePullRequestConnection(): ConnectionId?
}

/**
 * Creates GitHub pull requests through the official Pull Request API.
 *
 * This is the *only* pull-request write in the platform. It uses the existing
 * [GitHubRestClient] and the Phase-1 [ConnectionCredentialGateway]: the token is
 * lent into the HTTP call for its duration, so it never reaches a result, a log,
 * a tool argument, a URL, or an exception. Nothing here can merge a pull request,
 * delete a branch, force-push, or read a secret — those operations are simply not
 * expressible through this client.
 *
 * Before the write, both branches are checked to exist so a missing head/base is
 * a clear `NotFound` rather than an opaque validation failure. Cancellation
 * propagates as the coroutine's own exception so a cancelled request never
 * continues and never creates a pull request.
 */
class GitHubPullRequestServiceImpl(
    private val credentialGateway: ConnectionCredentialGateway,
    private val connections: GitHubPullRequestConnectionResolver,
    private val restClient: GitHubRestClient = UrlConnectionGitHubRestClient(),
    private val baseUrl: String = DEFAULT_BASE_URL,
) : PullRequestService {

    override suspend fun create(
        request: NewPullRequest,
    ): ForgeResult<CreatedPullRequest, PullRequestError> {
        localValidation(request)?.let { return failure(it) }

        val connectionId = connections.resolvePullRequestConnection()
            ?: return failure(PullRequestError.NoConnection)

        val handed = credentialGateway.withCredential(connectionId) { token ->
            createWithToken(request, token)
        }

        return when (handed) {
            is ForgeResult.Success -> handed.value
            is ForgeResult.Failure -> failure(gatewayError(handed.error))
        }
    }

    /**
     * Refuses a request the API would reject anyway, so no HTTP call is spent on a
     * malformed branch pair. `head == base` is called out separately because the
     * fix ("use a different head branch") is not obvious from a 422 alone.
     */
    private fun localValidation(request: NewPullRequest): PullRequestError? = when {
        request.title.isBlank() -> PullRequestError.ValidationFailed("A pull request title is required.")
        request.head.isBlank() -> PullRequestError.ValidationFailed("A head branch is required.")
        request.base.isBlank() -> PullRequestError.ValidationFailed("A base branch is required.")
        request.head == request.base ->
            PullRequestError.ValidationFailed("The head and base branches must differ.")
        else -> null
    }

    private suspend fun createWithToken(
        request: NewPullRequest,
        token: String,
    ): ForgeResult<CreatedPullRequest, PullRequestError> {
        // Both branches must exist before the PR is attempted; a missing branch is
        // reported as NotFound (404), not as a generic validation failure.
        branchError(request.repository, request.base, token)?.let { return failure(it) }
        branchError(request.repository, request.head, token)?.let { return failure(it) }

        val url = "$baseUrl/repos/${request.repository.owner}/${request.repository.name}/pulls"
        val response = try {
            restClient.post(url = url, headers = headers(token), body = createBody(request))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (network: GitHubRestNetworkException) {
            return failure(PullRequestError.NetworkFailure)
        } catch (offline: IOException) {
            return failure(PullRequestError.NetworkFailure)
        }

        return mapCreateResponse(response, request)
    }

    /** Reads a branch, returning a structured error when it is missing or unreadable. */
    private suspend fun branchError(
        repository: PullRequestRef,
        branch: String,
        token: String,
    ): PullRequestError? {
        val url = "$baseUrl/repos/${repository.owner}/${repository.name}/branches/${encode(branch)}"
        val response = try {
            restClient.get(url = url, headers = headers(token))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (network: GitHubRestNetworkException) {
            return PullRequestError.NetworkFailure
        } catch (offline: IOException) {
            return PullRequestError.NetworkFailure
        }
        return when {
            response.isSuccess -> null
            response.statusCode == 401 -> PullRequestError.Unauthenticated
            response.statusCode == 403 -> rateLimitOr(response, PullRequestError.Forbidden)
            response.statusCode == 404 ->
                PullRequestError.NotFound("Branch '$branch' was not found in ${repository.fullName}.")
            response.statusCode == 429 -> PullRequestError.RateLimited
            response.statusCode >= 500 -> PullRequestError.ServerError(response.statusCode)
            else -> PullRequestError.Unknown("GitHub answered with status ${response.statusCode}")
        }
    }

    private fun mapCreateResponse(
        response: GitHubRestResponse,
        request: NewPullRequest,
    ): ForgeResult<CreatedPullRequest, PullRequestError> = when {
        response.statusCode == 201 -> parseCreated(response.body, request)
        // GitHub only answers 200 on this endpoint for an already-existing PR in
        // rare cases; a success response is still parsed rather than trusted blindly.
        response.isSuccess -> parseCreated(response.body, request)
        response.statusCode == 400 ->
            failure(PullRequestError.ValidationFailed("GitHub rejected the request as invalid."))
        response.statusCode == 401 -> failure(PullRequestError.Unauthenticated)
        response.statusCode == 403 -> failure(rateLimitOr(response, PullRequestError.Forbidden))
        response.statusCode == 404 ->
            failure(PullRequestError.NotFound("The repository or one of its branches was not found."))
        response.statusCode == 409 ->
            failure(PullRequestError.Conflict("A conflicting pull request already exists."))
        response.statusCode == 422 ->
            failure(PullRequestError.ValidationFailed(validationDetail(response.body)))
        response.statusCode == 429 -> failure(PullRequestError.RateLimited)
        response.statusCode >= 500 -> failure(PullRequestError.ServerError(response.statusCode))
        else -> failure(
            PullRequestError.Unknown("GitHub answered with status ${response.statusCode}"),
        )
    }

    private fun parseCreated(
        body: String,
        request: NewPullRequest,
    ): ForgeResult<CreatedPullRequest, PullRequestError> {
        val parsed = GitHubPullRequestParsing.parseCreated(body, request.repository)
            ?: return failure(GitHubPullRequestParsing.malformedDetail(body))
        return com.agentx.app.core.success(parsed)
    }

    private fun rateLimitOr(response: GitHubRestResponse, fallback: PullRequestError): PullRequestError =
        if (response.header("x-ratelimit-remaining") == "0") PullRequestError.RateLimited else fallback

    private fun headers(token: String): Map<String, String> = mapOf(
        "Authorization" to "Bearer $token",
        "Accept" to "application/vnd.github+json",
        "X-GitHub-Api-Version" to GITHUB_API_VERSION,
        "User-Agent" to USER_AGENT,
    )

    private fun gatewayError(error: ForgeError): PullRequestError = when (error.code) {
        ForgeErrorCode.CONNECTION_CREDENTIAL_EXPIRED -> PullRequestError.Unauthenticated
        ForgeErrorCode.CONNECTION_NOT_FOUND,
        ForgeErrorCode.CONNECTION_UNAUTHORIZED,
        -> PullRequestError.NoConnection
        else -> PullRequestError.Unknown(error.message ?: "The GitHub credential could not be used")
    }

    private fun createBody(request: NewPullRequest): String = buildString {
        append("{\"title\":").append(jsonString(request.title))
        append(",\"head\":").append(jsonString(request.head))
        append(",\"base\":").append(jsonString(request.base))
        // Omit an empty body rather than sending an empty string, so GitHub's own
        // default is preserved and nothing from the agent is injected as markup.
        if (request.body.isNotEmpty()) append(",\"body\":").append(jsonString(request.body))
        append('}')
    }

    /** Escapes a value for the JSON request body. Never logs or echoes its input. */
    private fun jsonString(value: String): String {
        val builder = StringBuilder(value.length + 2)
        builder.append('"')
        for (character in value) {
            when (character) {
                '"' -> builder.append("\\\"")
                '\\' -> builder.append("\\\\")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                else -> if (character < ' ') {
                    builder.append("\\u").append(character.code.toString(16).padStart(4, '0'))
                } else {
                    builder.append(character)
                }
            }
        }
        builder.append('"')
        return builder.toString()
    }

    /** Path-encodes a branch name for the branches endpoint. Branch names cannot contain '#'. */
    private fun encode(value: String): String =
        value.replace("%", "%25").replace(" ", "%20").replace("#", "%23")

    private companion object {
        const val DEFAULT_BASE_URL: String = "https://api.github.com"
        const val GITHUB_API_VERSION: String = "2022-11-28"
        const val USER_AGENT: String = "AgentX-Android"
    }
}

/**
 * Pure parsing of the GitHub pull-request JSON the client reads.
 *
 * Kept separate from the transport so success and every malformed shape is
 * directly testable without a network, exactly like the repository and Actions
 * parsing.
 */
object GitHubPullRequestParsing {

    /** The created pull request, or null when the body is not that shape. */
    fun parseCreated(body: String, repository: PullRequestRef): CreatedPullRequest? {
        val root = OAuthJson.parse(body) ?: return null
        val number = root.long("number")?.toInt() ?: return null
        val title = root.string("title") ?: return null
        val htmlUrl = root.string("html_url") ?: return null
        val head = root.refOf("head") ?: return null
        val base = root.refOf("base") ?: return null
        return CreatedPullRequest(
            number = number,
            title = title,
            state = root.string("state") ?: "open",
            headBranch = head,
            baseBranch = base,
            repository = repository,
            htmlUrl = htmlUrl,
            draft = root.boolean("draft") ?: false,
        )
    }

    /** A safe, non-sensitive explanation for a malformed body. */
    fun malformedDetail(body: String): PullRequestError =
        PullRequestError.MalformedResponse(
            if (body.isBlank()) {
                "GitHub sent an empty body"
            } else {
                "GitHub sent a body that is not a pull request"
            },
        )

    /** Reads the `ref` of a nested object field such as `head` or `base`. */
    private fun OAuthJsonValue?.refOf(field: String): String? {
        val nested = (this as? OAuthJsonValue.Obj)?.fields?.get(field) as? OAuthJsonValue.Obj ?: return null
        return (nested.fields["ref"] as? OAuthJsonValue.Str)?.value?.takeIf { it.isNotBlank() }
    }

    private fun OAuthJsonValue?.boolean(field: String): Boolean? {
        val value = (this as? OAuthJsonValue.Obj)?.fields?.get(field) ?: return null
        return (value as? OAuthJsonValue.Bool)?.value
    }
}

/** Reads the `message` field GitHub sends on a 422, bounded and never a secret. */
private fun validationDetail(body: String): String {
    val message = OAuthJson.parse(body)?.string("message")?.takeIf { it.isNotBlank() }
    return message ?: "GitHub rejected the pull request as invalid."
}
