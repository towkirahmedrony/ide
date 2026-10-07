package com.agentx.app.integrations.github

import com.agentx.app.core.ForgeResult
import com.agentx.app.core.failure
import com.agentx.app.core.success
import com.agentx.app.integrations.connection.ConnectionCredentialGateway
import com.agentx.app.integrations.connection.ConnectionId
import com.agentx.app.integrations.oauth.OAuthJson
import com.agentx.app.integrations.oauth.OAuthJsonValue
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Authenticated GitHub repository discovery.
 *
 * The access token is obtained through the existing Phase-1
 * [ConnectionCredentialGateway] — it is never requested, stored, or duplicated
 * here, and it is lent only into the HTTP call, so it cannot reach the UI, the
 * model, a tool argument, or a log.
 */
interface GitHubRepositoryService {

    /**
     * Lists the repositories the authenticated account can see.
     *
     * An empty page means the account really has no matching repositories; it is
     * never a stand-in for an error.
     */
    suspend fun list(
        connectionId: ConnectionId,
        visibility: GitHubRepositoryVisibility = GitHubRepositoryVisibility.PUBLIC,
        page: Int = 1,
        perPage: Int = DEFAULT_PER_PAGE,
    ): ForgeResult<GitHubRepositoryPage, GitHubRepositoryError>

    /**
     * How many repositories the most recent successful listing implies. Zero
     * before anything has been listed successfully.
     */
    suspend fun totalKnown(connectionId: ConnectionId): Int

    companion object {
        /** GitHub's own default for `per_page`. */
        const val DEFAULT_PER_PAGE: Int = 30

        /** GitHub's documented maximum for `per_page`. */
        const val MAX_PER_PAGE: Int = 100
    }
}

/**
 * One page of repository results, with the pagination state the UI needs to load
 * more instead of pulling a whole account into memory at once.
 */
data class GitHubRepositoryPage(
    val repositories: List<GitHubRepository>,
    val hasNextPage: Boolean,
    val nextPageNumber: Int?,
    /**
     * A lower bound on the number of repositories the account can see. When the
     * page is the last one it is the exact total; earlier pages can only
     * under-count, because GitHub does not report a total for this endpoint.
     */
    val totalCount: Int,
) {
    companion object {
        fun empty(): GitHubRepositoryPage = GitHubRepositoryPage(
            repositories = emptyList(),
            hasNextPage = false,
            nextPageNumber = null,
            totalCount = 0,
        )
    }
}

/**
 * A GitHub REST response, reduced to the status, the body, and the headers this
 * client needs.
 *
 * The body is treated as opaque by the transport. [toString] deliberately prints
 * neither the body nor the headers, because a header map can hold an echoed
 * request.
 */
data class GitHubRestResponse(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, List<String>> = emptyMap(),
) {
    val isSuccess: Boolean get() = statusCode in 200..299

    /** Case-insensitive header lookup; the transport's own key casing is not a contract. */
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value
            ?.firstOrNull()

    override fun toString(): String = "GitHubRestResponse(statusCode=$statusCode)"
}

/**
 * Port for GitHub REST calls, so the service is testable without a network.
 *
 * Implementations must not log or echo the `Authorization` header value.
 */
interface GitHubRestClient {
    suspend fun get(
        url: String,
        headers: Map<String, String>,
    ): GitHubRestResponse

    /**
     * Performs an authenticated write. [body] is the request body (JSON).
     *
     * As with [get], implementations must never log or echo the `Authorization`
     * header value, and must never include it in an exception.
     */
    suspend fun post(
        url: String,
        headers: Map<String, String>,
        body: String,
    ): GitHubRestResponse
}

/** Raised when a GitHub REST call could not be performed at all. */
class GitHubRestNetworkException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * The production [GitHubRestClient], built on `HttpURLConnection`.
 *
 * This is the only place an `Authorization` header value exists as a string, and
 * it is never logged, never echoed into an exception, and never returned.
 */
class UrlConnectionGitHubRestClient(
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 20_000,
) : GitHubRestClient {

    override suspend fun get(url: String, headers: Map<String, String>): GitHubRestResponse {
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            // The message is built here rather than taken from the transport so it can
            // never carry a header value.
            throw GitHubRestNetworkException("Could not reach GitHub", error)
        }

        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.instanceFollowRedirects = false
            connection.doInput = true
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }

            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()

            // Copied rather than viewed, and the status line's keyless entry is dropped:
            // the header map outlives the connection, which is disconnected below.
            val responseHeaders = LinkedHashMap<String, List<String>>()
            connection.headerFields?.forEach { (name, values) ->
                if (name != null) responseHeaders[name] = values
            }

            GitHubRestResponse(statusCode, body, responseHeaders)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            throw GitHubRestNetworkException("The GitHub request failed", error)
        } catch (error: Exception) {
            throw GitHubRestNetworkException("The GitHub request failed", error)
        } finally {
            connection.disconnect()
        }
    }

    override suspend fun post(
        url: String,
        headers: Map<String, String>,
        body: String,
    ): GitHubRestResponse {
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            throw GitHubRestNetworkException("Could not reach GitHub", error)
        }

        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            // GitHub only reads the body when it is declared as JSON; the header
            // carries no credential, so it is safe to set unconditionally.
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }

            connection.outputStream.use { stream ->
                stream.write(body.toByteArray(Charsets.UTF_8))
                stream.flush()
            }

            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()

            val responseHeaders = LinkedHashMap<String, List<String>>()
            connection.headerFields?.forEach { (name, values) ->
                if (name != null) responseHeaders[name] = values
            }

            GitHubRestResponse(statusCode, responseBody, responseHeaders)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            throw GitHubRestNetworkException("The GitHub request failed", error)
        } catch (error: Exception) {
            throw GitHubRestNetworkException("The GitHub request failed", error)
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * Authenticated GitHub repository service.
 *
 * Authentication errors do **not** delete the connection: a 401 says the stored
 * credential is no longer usable, which the Connections UI turns into a
 * re-authorize prompt.
 */
class GitHubRepositoryServiceImpl(
    private val credentialGateway: ConnectionCredentialGateway,
    private val restClient: GitHubRestClient = UrlConnectionGitHubRestClient(),
    private val baseUrl: String = DEFAULT_BASE_URL,
) : GitHubRepositoryService {

    /** The implied total from the last successful listing, per connection. */
    private val totals = ConcurrentHashMap<String, Int>()

    override suspend fun list(
        connectionId: ConnectionId,
        visibility: GitHubRepositoryVisibility,
        page: Int,
        perPage: Int,
    ): ForgeResult<GitHubRepositoryPage, GitHubRepositoryError> {
        require(page >= 1) { "A page number starts at 1" }
        require(perPage in 1..GitHubRepositoryService.MAX_PER_PAGE) {
            "per_page must be between 1 and ${GitHubRepositoryService.MAX_PER_PAGE}"
        }

        val handed = credentialGateway.withCredential(connectionId) { token ->
            requestPage(token, visibility, page, perPage)
        }

        return when (handed) {
            is ForgeResult.Success -> when (val requested = handed.value) {
                is ForgeResult.Success -> {
                    val parsed = parsePage(requested.value, page, perPage)
                    when (parsed) {
                        is ForgeResult.Success -> totals[connectionId.value] = parsed.value.totalCount
                        is ForgeResult.Failure -> totals.remove(connectionId.value)
                    }
                    parsed
                }
                is ForgeResult.Failure -> {
                    totals.remove(connectionId.value)
                    requested
                }
            }
            is ForgeResult.Failure -> {
                totals.remove(connectionId.value)
                failure(gitHubGatewayError(handed.error))
            }
        }
    }

    override suspend fun totalKnown(connectionId: ConnectionId): Int =
        totals[connectionId.value] ?: 0

    private suspend fun requestPage(
        token: String,
        visibility: GitHubRepositoryVisibility,
        page: Int,
        perPage: Int,
    ): ForgeResult<GitHubRestResponse, GitHubRepositoryError> {
        val url = "$baseUrl/user/repos" +
            "?visibility=${visibility.apiParameter}" +
            "&sort=full_name&direction=asc" +
            "&per_page=$perPage&page=$page"

        val response = try {
            restClient.get(
                url = url,
                headers = mapOf(
                    "Authorization" to "Bearer $token",
                    "Accept" to "application/vnd.github+json",
                    "X-GitHub-Api-Version" to GITHUB_API_VERSION,
                    "User-Agent" to USER_AGENT,
                ),
            )
        } catch (network: GitHubRestNetworkException) {
            return failure(GitHubRepositoryError.NetworkFailure)
        } catch (offline: IOException) {
            return failure(GitHubRepositoryError.NetworkFailure)
        }

        return when {
            response.isSuccess -> success(response)
            response.statusCode == 401 -> failure(GitHubRepositoryError.Unauthenticated)
            response.statusCode == 403 -> failure(
                if (response.header("x-ratelimit-remaining") == "0") {
                    GitHubRepositoryError.RateLimited
                } else {
                    GitHubRepositoryError.Forbidden
                },
            )
            response.statusCode == 404 -> failure(GitHubRepositoryError.NotFound)
            response.statusCode == 429 -> failure(GitHubRepositoryError.RateLimited)
            response.statusCode >= 500 -> failure(GitHubRepositoryError.ServerError(response.statusCode))
            else -> failure(GitHubRepositoryError.Unknown("GitHub answered with status ${response.statusCode}"))
        }
    }

    private fun parsePage(
        response: GitHubRestResponse,
        requestedPage: Int,
        perPage: Int,
    ): ForgeResult<GitHubRepositoryPage, GitHubRepositoryError> {
        if (response.body.isBlank()) {
            return failure(GitHubRepositoryError.MalformedResponse("GitHub sent an empty body"))
        }
        val parsed = OAuthJson.parse(response.body)
            ?: return failure(GitHubRepositoryError.MalformedResponse("GitHub sent a body that is not JSON"))
        val items = (parsed as? OAuthJsonValue.Arr)?.items
            ?: return failure(GitHubRepositoryError.MalformedResponse("Expected a JSON array of repositories"))

        val repositories = items.mapNotNull { it.toRepository() }
        if (repositories.isEmpty() && items.isNotEmpty()) {
            return failure(
                GitHubRepositoryError.MalformedResponse("No repository in the response could be read"),
            )
        }

        val links = paginationLinks(response.header("link"))
        val totalCount = links.lastPage?.let { last -> (last - 1) * perPage + repositories.size }
            ?: repositories.size

        return success(
            GitHubRepositoryPage(
                repositories = repositories,
                hasNextPage = links.nextPage != null && links.nextPage > requestedPage,
                nextPageNumber = links.nextPage,
                totalCount = totalCount,
            ),
        )
    }

    private data class PaginationLinks(val nextPage: Int?, val lastPage: Int?)

    /**
     * Reads the `Link` header GitHub sends on collection endpoints, e.g.
     * `<https://api.github.com/user/repos?...&page=2>; rel="next", ...; rel="last"`.
     */
    private fun paginationLinks(header: String?): PaginationLinks {
        if (header.isNullOrBlank()) return PaginationLinks(null, null)

        var next: Int? = null
        var last: Int? = null
        for (part in header.split(',')) {
            val url = LINK_URL.find(part)?.groupValues?.getOrNull(1) ?: continue
            val relation = LINK_RELATION.find(part)?.groupValues?.getOrNull(1) ?: continue
            val number = PAGE_PARAMETER.find(url)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: continue
            when (relation) {
                "next" -> next = number
                "last" -> last = number
            }
        }
        return PaginationLinks(next, last)
    }

    private companion object {
        const val DEFAULT_BASE_URL: String = "https://api.github.com"
        const val GITHUB_API_VERSION: String = "2022-11-28"
        const val USER_AGENT: String = "AgentX-Android"

        val LINK_URL = Regex("""<([^>]*)>""")
        // The closing quote is left off the pattern on purpose: the negated class
        // cannot cross it, and a trailing quote would end the raw string early.
        val LINK_RELATION = Regex("""rel="([^"]*)""")
        val PAGE_PARAMETER = Regex("""[?&]page=(\d+)""")
    }
}

/**
 * Reads one repository out of a GitHub payload.
 *
 * Returns null for anything that is not a complete repository, so one unreadable
 * entry cannot fail the whole page.
 */
private fun OAuthJsonValue.toRepository(): GitHubRepository? {
    val fields = (this as? OAuthJsonValue.Obj)?.fields ?: return null

    val id = fields["id"].asIdString() ?: return null
    val owner = fields["owner"].blankSafeString("login") ?: return null
    val name = fields["name"].asString() ?: return null
    val fullName = fields["full_name"].asString() ?: return null
    val cloneUrl = fields["clone_url"].asString()?.let { raw -> GitHubRepositoryCloneUrl.parse(raw) }
        ?: return null
    val webUrl = fields["html_url"].asString() ?: return null

    return GitHubRepository(
        id = RepositoryId(id),
        owner = owner,
        name = name,
        fullName = fullName,
        visibility = GitHubRepositoryVisibility.fromPrivateFlag(fields["private"].asBoolean() ?: false),
        defaultBranch = fields["default_branch"].asString() ?: GitHubRepository.DEFAULT_BRANCH,
        cloneUrl = cloneUrl,
        webUrl = webUrl,
    )
}

private fun OAuthJsonValue?.asString(): String? = when (this) {
    is OAuthJsonValue.Str -> value.takeIf { it.isNotBlank() }
    is OAuthJsonValue.Num -> value.toLong().toString()
    else -> null
}

/** Repository ids are numbers in JSON; keep them as the digits GitHub means. */
private fun OAuthJsonValue?.asIdString(): String? = when (this) {
    is OAuthJsonValue.Num -> value.toLong().toString()
    is OAuthJsonValue.Str -> value.takeIf { it.isNotBlank() }
    else -> null
}

private fun OAuthJsonValue?.asBoolean(): Boolean? = when (this) {
    is OAuthJsonValue.Bool -> value
    is OAuthJsonValue.Str -> value.equals("true", ignoreCase = true)
    else -> null
}

private fun OAuthJsonValue?.blankSafeString(field: String): String? =
    (this as? OAuthJsonValue.Obj)?.fields?.get(field)?.asString()
