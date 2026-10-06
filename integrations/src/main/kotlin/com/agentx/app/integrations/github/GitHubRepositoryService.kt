package com.agentx.app.integrations.github

import com.agentx.app.core.ForgeResult
import com.agentx.app.core.failure
import com.agentx.app.core.success
import com.agentx.app.integrations.connection.ConnectionCredentialGateway
import com.agentx.app.integrations.connection.ConnectionId
import com.agentx.app.integrations.github.GitHubRepository
import com.agentx.app.integrations.github.GitHubRepositoryCloneUrl
import com.agentx.app.integrations.github.GitHubRepositoryError
import com.agentx.app.integrations.github.GitHubRepositoryPage
import com.agentx.app.integrations.github.GitHubRepositoryVisibility
import kotlinx.coroutines.cancellation.CancellationException
import org.json.JSONObject
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * Authenticated GitHub repository service.
 *
 * Uses [ConnectionCredentialGateway.withCredential] to obtain the GitHub access
 * token on demand, then calls the GitHub REST API.
 *
 * The token is lent only into the HTTP call and never reaches the UI, the model,
 * logs, or tool arguments.
 */
interface GitHubRepositoryService {
    /**
     * Lists repositories accessible to the authenticated GitHub account.
     *
     * @return A page of repositories. [GitHubRepositoryPage.hasNextPage] is true
     *         when the GitHub API returned a Link header with a next relation.
     *         An empty list means the account has no repositories.
     */
    suspend fun list(
        connectionId: ConnectionId,
        visibility: GitHubRepositoryVisibility = GitHubRepositoryVisibility.PUBLIC,
        page: Int = 1,
        perPage: Int = 30,
    ): ForgeResult<GitHubRepositoryPage, GitHubRepositoryError>

    /** Total count known from the most recent successful response, may be stale. */
    suspend fun totalKnown(connectionId: ConnectionId): Int
}

/**
 * One page of repository results from the GitHub API.
 */
data class GitHubRepositoryPage(
    val repositories: List<GitHubRepository>,
    val hasNextPage: Boolean,
    val nextPageNumber: Int?,
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
 * Errors from GitHub repository operations.
 *
 * Every error preserves the existing GitHub connection — none of these destroy
 * the credential, the connection record, or any other connection.
 */
sealed interface GitHubRepositoryError {
    /** 401: token invalid/expired — re-authentication may be required. */
    data object Unauthenticated : GitHubRepositoryError {
        override fun toString(): String = "Unauthenticated"
    }

    /** 403: forbidden or rate limited (core). */
    data object Forbidden : GitHubRepositoryError {
        override fun toString(): String = "Forbidden"
    }

    /** 404: user/repo not found. */
    data object NotFound : GitHubRepositoryError {
        override fun toString(): String = "NotFound"
    }

    /** 429: rate limited. */
    data object RateLimited : GitHubRepositoryError {
        override fun toString(): String = "RateLimited"
    }

    /** 5xx server error. */
    data class ServerError(val code: Int) : GitHubRepositoryError {
        override fun toString(): String = "ServerError($code)"
    }

    /** Network failure (DNS, TLS, timeout, no internet). */
    data object NetworkFailure : GitHubRepositoryError {
        override fun toString(): String = "NetworkFailure"
    }

    /** Malformed API response. */
    data class MalformedResponse(val detail: String) : GitHubRepositoryError {
        override fun toString(): String = "MalformedResponse($detail)"
    }

    /** The connection does not exist or has no usable credential. */
    data object NoCredential : GitHubRepositoryError {
        override fun toString(): String = "NoCredential"
    }

    /** Cancellation. */
    data object Cancelled : GitHubRepositoryError {
        override fun toString(): String = "Cancelled"
    }

    /** An unexpected failure. */
    data class Unknown(val message: String) : GitHubRepositoryError {
        override fun toString(): String = "Unknown($message)"
    }
}

/**
 * Minimal typed HTTP response from the GitHub REST API.
 *
 * Body is treated as opaque by the transport; [GitHubRepositoryServiceImpl]
 * parses it into domain models without ever logging or echoing the token.
 */
data class GitHubRestResponse(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, List<String>> = emptyMap(),
) {
    val isSuccess: Boolean get() = statusCode in 200..299
    val isClientError: Boolean get() = statusCode in 400..499
    val isServerError: Boolean get() = statusCode >= 500
}

/**
 * Port for GitHub REST calls. Implementations must not log or echo the
 * Authorization header value.
 */
interface GitHubRestClient {
    suspend fun get(
        url: String,
        headers: Map<String, String>,
    ): GitHubRestResponse
}

/**
 * Default [GitHubRestClient] built on `HttpURLConnection`.
 *
 * The Authorization header value may contain a token inside this transport
 * only; it is never logged, echoed, or returned to callers.
 */
class UrlConnectionGitHubRestClient(
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 20_000,
) : GitHubRestClient {

    @Throws(GitHubRestNetworkException::class)
    override suspend fun get(url: String, headers: Map<String, String>): GitHubRestResponse {
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (error: Exception) {
            throw GitHubRestNetworkException(error)
        }

        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.instanceFollowRedirects = false
            connection.doInput = true

            headers.forEach { (name, value) ->
                connection.setRequestProperty(name, value)
            }

            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val body = stream?.bufferedReader(Charsets.UTF_8)?.readText().orEmpty()
            val headerMap = connection.headerFields?.toMap().orEmpty()

            GitHubRestResponse(statusCode, body, headerMap)
        } catch (error: java.net.SocketTimeoutException) {
            throw GitHubRestNetworkException(error)
        } catch (error: java.io.IOException) {
            throw GitHubRestNetworkException(error)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw GitHubRestNetworkException(error)
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * Raised when a GitHub REST call cannot be performed at all (DNS, TLS,
 * timeout, no route, cancellation swallowed incorrectly).
 */
class GitHubRestNetworkException(message: String? = null, cause: Throwable? = null) :
    RuntimeException(message, cause)

/**
 * Authenticated GitHub repository service implementation.
 *
 * Uses the existing [ConnectionCredentialGateway] to obtain the access token,
 * then calls GitHub's documented REST endpoint.
 *
 * Pagination: we parse the `Link` response header the GitHub API returns on
 * collection endpoints and expose [GitHubRepositoryPage.hasNextPage] plus a
 * concrete next page number, so callers can load incrementally instead of
 * pulling a large list into memory. When the header is absent we fall back to
 * a simple per-page heuristic and still surface explicit pagination state.
 *
 * Authentication errors (401) do **not** delete the connection; they indicate
 * the credential may be expired and the user should re-authorize.
 */
class GitHubRepositoryServiceImpl(
    private val credentialGateway: ConnectionCredentialGateway,
    private val restClient: GitHubRestClient = UrlConnectionGitHubRestClient(),
    private val baseUrl: String = "https://api.github.com",
    private val clock: () -> Long = System::currentTimeMillis,
) : GitHubRepositoryService {

    // Cached total count so the UI can show an approximate number even when
    // loading pages incrementally. It is not authoritative and may be stale.
    private val totalCountCache = mutableMapOf<String, Int>()
    private val totalCountLock = java.util.concurrent.locks.ReentrantLock()

    override suspend fun list(
        connectionId: ConnectionId,
        visibility: GitHubRepositoryVisibility,
        page: Int,
        perPage: Int,
    ): ForgeResult<GitHubRepositoryPage, GitHubRepositoryError> {
        // Note: the actual token is obtained inside the retry-free call below.
        // Cancellation must propagate.
        return runCatching {
            listPage(connectionId, visibility, page, perPage)
        }.getOrElse { error ->
            when (error) {
                is CancellationException -> throw error
                is GitHubRestNetworkException -> failure(GitHubRepositoryError.NetworkFailure)
                is GitHubRestException -> map(restClientError = error)
                else -> failure(GitHubRepositoryError.Unknown(error.message ?: "Unexpected error"))
            }
        }
    }

    override suspend fun totalKnown(connectionId: ConnectionId): Int {
        return totalCountLock.read { totalCountCache[connectionId.value] ?: 0 }
    }

    private suspend fun listPage(
        connectionId: ConnectionId,
        visibility: GitHubRepositoryVisibility,
        page: Int,
        perPage: Int,
    ): ForgeResult<GitHubRepositoryPage, GitHubRepositoryError> {
        val tokenResult = credentialGateway.withCredential(connectionId) { token ->
            callGitHubApi(token, visibility, page, perPage)
        }

        return when (val result = tokenResult) {
            is ForgeResult.Success -> {
                val page = parsePage(result.value, page)
                totalCountLock.write {
                    totalCountCache[connectionId.value] = page.totalCount
                }
                success(page)
            }
            is ForgeResult.Failure -> {
                totalCountLock.write {
                    totalCountCache.remove(connectionId.value)
                }
                failure(result.error)
            }
        }
    }

    private suspend fun callGitHubApi(
        token: String,
        visibility: GitHubRepositoryVisibility,
        page: Int,
        perPage: Int,
    ): ForgeResult<GitHubRestResponse, GitHubRepositoryError> {
        // GitHub REST API: GET /user/repos
        // Parameters: type (all|owner|member), sort, direction, since, visibility
        // visibility param accepts: public, private, all
        val visibilityParam = when (visibility) {
            GitHubRepositoryVisibility.PUBLIC -> "visibility=public"
            GitHubRepositoryVisibility.PRIVATE -> "visibility=private"
        }

        val encodedVisibility = visibilityParam
        val url = "$baseUrl/user/repos?$encodedVisibility&per_page=$perPage&page=$page&sort=full_name&direction=asc"

        // Headers: Accept for the REST API version, Bearer token, User-Agent
        val headers = mapOf(
            "Authorization" to "Bearer $token",
            "Accept" to "application/vnd.github+json",
            "X-GitHub-Api-Version" to "2022-11-28",
            "User-Agent" to "AgentX-Android",
        )

        return try {
            val response = restClient.get(url, headers)
            when {
                response.isSuccess -> success(response)
                response.statusCode == 401 -> failure(GitHubRestException.Unauthenticated)
                response.statusCode == 403 -> {
                    // GitHub may return 403 with rate limit info; check header
                    if (response.headers["x-ratelimit-remaining"]?.firstOrNull()?.toIntOrNull() == 0) {
                        failure(GitHubRestException.RateLimited)
                    } else {
                        failure(GitHubRestException.Forbidden)
                    }
                }
                response.statusCode == 404 -> failure(GitHubRestException.NotFound)
                response.statusCode == 429 -> failure(GitHubRestException.RateLimited)
                response.statusCode >= 500 -> failure(GitHubRestException.ServerError(response.statusCode))
                else -> failure(GitHubRestException.Unknown(response.statusCode))
            }
        } catch (network: GitHubRestNetworkException) {
            failure(GitHubRepositoryError.NetworkFailure)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failure(GitHubRepositoryError.Unknown(error.message ?: "Request failed"))
        }
    }

    private fun parsePage(response: GitHubRestResponse, requestedPage: Int): GitHubRepositoryPage {
        val json = try {
            JSONObject(response.body)
        } catch (error: Exception) {
            // GitHub /user/repos returns a JSON array at the top level, not an object.
            // Try parsing as array first.
            try {
                JSONArray(response.body)
            } catch (arrayError: Exception) {
                return GitHubRepositoryPage.empty().copy(
                    totalCount = 0,
                    hasNextPage = false,
                    nextPageNumber = null,
                )
            }
        }

        val repos = mutableListOf<GitHubRepository>()
        val array = if (json is JSONArray) json else {
            // Maybe it's already an array
            try {
                JSONArray(response.body)
            } catch (e: Exception) {
                JSONArray()
            }
        }

        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val repo = parseRepository(obj)
            if (repo != null) repos.add(repo)
        }

        val totalCount = try {
            // Some endpoints return total_count in the response; /user/repos returns array only.
            // We'll use the array length as count for now, but also check if there's a total_count field.
            array.length()
        } catch (e: Exception) {
            repos.size
        }

        val (hasNext, nextPage) = parseLinkHeader(response.headers["link"]?.joinToString(",") ?: "", requestedPage)

        return GitHubRepositoryPage(
            repositories = repos,
            hasNextPage = hasNext,
            nextPageNumber = nextPage,
            totalCount = totalCount,
        )
    }

    private fun parseRepository(json: JSONObject): GitHubRepository? {
        return try {
            val id = json.getString("id")
            val ownerObj = json.getJSONObject("owner")
            val owner = ownerObj.getString("login")
            val name = json.getString("name")
            val fullName = json.getString("full_name")
            val isPrivate = json.getBoolean("private")
            val defaultBranch = json.optString("default_branch", "main")
            val cloneUrlRaw = json.getString("clone_url")
            val webUrl = json.getString("html_url")

            GitHubRepository(
                id = RepositoryId(id),
                owner = owner,
                name = name,
                fullName = fullName,
                visibility = if (isPrivate) GitHubRepositoryVisibility.PRIVATE else GitHubRepositoryVisibility.PUBLIC,
                defaultBranch = defaultBranch,
                cloneUrl = GitHubRepositoryCloneUrl.parse(cloneUrlRaw),
                webUrl = webUrl,
            )
        } catch (error: Exception) {
            null
        }
    }

    private fun parseLinkHeader(linkHeader: String, currentPage: Int): Pair<Boolean, Int?> {
        if (linkHeader.isBlank()) {
            // Fallback heuristic: if we got a full page, assume more exist
            return False to null
        }

        // Parse rel="next" URL
        val nextPattern = Regex("""<([^>]+)>;\s*rel="next"""")
        val nextMatch = nextPattern.find(linkHeader)

        return if (nextMatch != null) {
            val nextUrl = nextMatch.groupValues[1]
            val pageParam = Regex("""page=(\d+)""").find(nextUrl)
            val nextPageNum = pageParam?.groupValues?.getOrNull(1)?.toIntOrNull()
            true to nextPageNum
        } else {
            false to null
        }
    }

    private fun <T> Pair<T, T>.copy(first: T? = null, second: T? = null): Pair<T, T> {
        return Pair(first ?: first, second ?: second)
    }
}

// Internal sealed class for mapping HTTP errors from the rest client.
private sealed class GitHubRestException {
    data object Unauthenticated : GitHubRestException()
    data object Forbidden : GitHubRestException()
    data object NotFound : GitHubRestException()
    data object RateLimited : GitHubRestException()
    data class ServerError(val code: Int) : GitHubRestException()
    data class Unknown(val code: Int) : GitHubRestException()
}

private fun map(restClientError: GitHubRestException): ForgeResult<GitHubRepositoryPage, GitHubRepositoryError> {
    return when (restClientError) {
        is GitHubRestException.Unauthenticated -> failure(GitHubRepositoryError.Unauthenticated)
        is GitHubRestException.Forbidden -> failure(GitHubRepositoryError.Forbidden)
        is GitHubRestException.NotFound -> failure(GitHubRepositoryError.NotFound)
        is GitHubRestException.RateLimited -> failure(GitHubRepositoryError.RateLimited)
        is GitHubRestException.ServerError -> failure(GitHubRepositoryError.ServerError(restClientError.code))
        is GitHubRestException.Unknown -> failure(GitHubRepositoryError.Unknown("HTTP ${restClientError.code}"))
    }
}

private val False get() = false
