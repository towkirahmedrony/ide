package com.agentx.app.integrations.github

import com.agentx.app.core.ForgeResult
import com.agentx.app.core.failure
import com.agentx.app.core.success
import com.agentx.app.integrations.connection.ConnectionCredentialGateway
import com.agentx.app.integrations.connection.ConnectionId
import com.agentx.app.integrations.connection.ConnectionType
import com.agentx.app.integrations.connection.ConnectionAuthorizationFailure
import kotlinx.coroutines.cancellation.CancellationException
import java.util.Locale

/**
 * Authenticated GitHub repository discovery.
 *
 * Uses the existing GitHub connection credential gateway -- never requests,
 * stores, or duplicates a token. The token is lent only into the GitHub REST
 * call and never reaches the UI, the model, logs, or tool arguments.
 */
interface GitHubRepositoryService {
    /**
     * Lists repositories accessible to the authenticated GitHub account.
     *
     * @return A page of repositories. [hasMore] is true when the GitHub API
     *         returned a paginated link and there are more repositories to load.
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

data class GitHubRepositoryPage(
    val repositories: List<GitHubRepository>,
    val hasNextPage: Boolean,
    val nextPageNumber: Int?,
    val totalCount: Int = repositories.size,
) {
    companion object {
        fun empty(): GitHubRepositoryPage = GitHubRepositoryPage(
            repositories = emptyList(),
            hasNextPage = false,
            nextPageNumber = null,
        )
    }
}

sealed interface GitHubRepositoryError {
    /** 401: token invalid/expired -> re-authentication may be required. Connection is NOT deleted. */
    data object Unauthenticated : GitHubRepositoryError {
        override fun toString(): String = "Unauthenticated"
    }
    /** 403: forbidden (or rate limited when rate limit exhausted but not yet 429). Connection NOT deleted. */
    data object Forbidden : GitHubRepositoryError {
        override fun toString(): String = "Forbidden"
    }
    /** 404: user/repo not found. Connection NOT deleted. */
    data object NotFound : GitHubRepositoryError {
        override fun toString(): String = "Not Found"
    }
    /** 429: rate limited. Connection NOT deleted. */
    data object RateLimited : GitHubRepositoryError {
        override fun toString(): String = "Rate Limited"
    }
    /** 5xx server error. Connection NOT deleted. */
    data class ServerError(val code: Int) : GitHubRepositoryError {
        override fun toString(): String = "ServerError($code)"
    }
    /** Network failure (DNS, TLS, timeout, no internet). Connection NOT deleted. */
    data object NetworkFailure : GitHubRepositoryError {
        override fun toString(): String = "NetworkFailure"
    }
    /** Malformed API response. Connection NOT deleted. */
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
    /** An unexpected failure that is not one of the above. */
    data class Unknown(val message: String) : GitHubRepositoryError {
        override fun toString(): String = "Unknown($message)"
    }
}

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

/**
 * Minimal typed HTTP contract for the GitHub REST API.
 *
 * This is deliberately shallow and separate from the OAuth HTTP layer: it
 * carries opaque responses only, with [body] treated as potentially containing
 * a token only inside the transport, never logged or echoed. The repository
 * service owns authorization semantics; this interface only forwards bytes.
 */
interface GitHubRestClient {
    suspend fun get(
        url: String,
        headers: Map<String, String>,
    ): GitHubRestResponse
}

data class GitHubRestResponse(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, List<String>> = emptyMap(),
) {
    val isSuccess: Boolean get() = statusCode in 200..299
    val isRedirect: Boolean get() = statusCode in 300..399
    val isClientError: Boolean get() = statusCode in 400..499
    val isServerError: Boolean get() = statusCode >= 500

    override fun toString(): String = "GitHubRestResponse(statusCode=$statusCode)"
}

/**
 * Default [GitHubRestClient] implemented over the platform HTTP transport.
 *
 * Tokens may appear in the Authorization header value *inside this transport
 * only*; they are never logged, echoed, or returned to callers.
 */
class UrlConnectionGitHubRestClient(
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 20_000,
) : GitHubRestClient {
    override suspend fun get(url: String, headers: Map<String, String>): GitHubRestResponse {
        val connection = try {
            java.net.URL(url).openConnection() as java.net.HttpURLConnection
        } catch (error: Exception) {
            // Wrap network-level failures without logging any Authorization value.
            return GitHubRestResponse(-1, "", emptyMap()).copy(isSuccess = false)
                .also { throw GitHubRestNetworkException(error) }
        }
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.instanceFollowRedirects = false
            connection.doInput = true
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            val status = connection.responseCode
            val inputStream = if (status in 200..299) connection.inputStream
                else connection.errorStream
            val body = inputStream?.use { kotlin.io.readBytes(it).toString(Charsets.UTF_8) } ?: ""
            val responseHeaders = connection.headerFields?.toMutableMap()
                ?: emptyMap()
            GitHubRestResponse(status, body, responseHeaders)
        } catch (error: Exception) {
            // Do not log or echo any Authorization header here.
            throw GitHubRestNetworkException(error)
        } finally {
            connection.disconnect()
        }
    }
}

class GitHubRestNetworkException(override val message: String?, override val cause: Throwable?) :
    RuntimeException(message, cause)

/**
 * Authenticated GitHub repository service.
 *
 * Uses [ConnectionCredentialGateway.withCredential] to obtain the GitHub access
 * token, then calls GitHub's documented REST endpoint
 * `GET /user/repos` with `accept: application/vnd.github+json` and a Bearer token.
 *
 * Pagination: the GitHub API uses a `Link` header for pagination, which we
 * follow via the `next` link. We also support naive per-page pagination for
 * efficiency and to match the existing UI.
 *
 * Authentication errors (401) do NOT delete the connection; they indicate
 * the credential may be expired and the user should re-authorize.
 */
class GitHubRepositoryServiceImpl(
    private val credentialGateway: ConnectionCredentialGateway,
    private val restClient: GitHubRestClient,
    private val baseUrl: String = "https://api.github.com",
    private val logger: GitHubRepositoryLogger = QuietGitHubRepositoryLogger,
) : GitHubRepositoryService {

    private data class LastListResult(
        val totalCount: Int,
        val hasNextPage: Boolean,
        val nextPageNumber: Int?,
    )

    private val lastResult = mutableMapOf<String, LastListResult>()
    private val lastResultLock = java.util.concurrent.locks.ReentrantLock()

    override suspend fun list(
        connectionId: ConnectionId,
        visibility: GitHubRepositoryVisibility,
        page: Int,
        perPage: Int,
    ): ForgeResult<GitHubRepositoryPage, GitHubRepositoryError> {
        val key = connectionId.value
        // If we already have a cached total count from a previous page, prefer that.
        val previousTotal = lastResultLock.read { lastResult[key]?.totalCount ?: 0 }

        return try {
            val tokenResult = credentialGateway.withCredential(connectionId) { token ->
                listRepositories(token, visibility, page, perPage)
            }
            tokenResult.fold(
                onSuccess = { page ->
                    val hasMore = page.hasNextPage
                    val nextPage = page.nextPageNumber
                    lastResultLock.write {
                        lastResult[key] = LastListResult(
                            totalCount = page.totalCount,
                            hasNextPage = hasMore,
                            nextPageNumber = nextPage,
                        )
                    }
                    success(page)
                },
                onFailure = { error ->
                    logger.logError(key, error, repositoriesListed = 0)
                    failure(error)
                },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: GitException) {
            logger.logError(key, GitHubRepositoryError.Unknown(error.message ?: "Git exception"), 0)
            failure(GitHubRepositoryError.Unknown(error.message ?: "Git exception"))
        } catch (error: Throwable) {
            logger.logError(key, GitHubRepositoryError.Unknown(error.message ?: "Unexpected error"), 0)
            failure(GitHubRepositoryError.Unknown(error.message ?: "Unexpected error"))
        }
    }

    override suspend fun totalKnown(connectionId: ConnectionId): Int {
        return lastResultLock.read { lastResult[connectionId.value]?.totalCount ?: 0 }
    }

    private suspend fun listRepositories(
        token: String,
        visibility: GitHubRepositoryVisibility,
        page: Int,
        perPage: Int,
    ): ForgeResult<GitHubRepositoryPage, GitHubRepositoryError> {
        // Build the request URL. visibility filter is applied server-side using GitHub's
        // `visibility` and `affiliation` parameters where appropriate. For "public" we
        // request only public; for "private" we request all repos the user can see and
        // filter after. For full flexibility, default to listing all repos the connection
        // can access and let callers filter by visibility.
        val visibilityParam = when (visibility) {
            GitHubRepositoryVisibility.PUBLIC -> "visibility=public"
            GitHubRepositoryVisibility.PRIVATE -> "visibility=private"
        }
        val url = "$baseUrl/user/repos?${visibilityParam}&per_page=$perPage&page=$page&sort=full_name&direction=asc" +
            "&accept=application/vnd.github+json"

        val response = try {
            restClient.get(
                url = url,
                headers = mapOf(
                    "Authorization" to "Bearer $token",
                    "Accept" to "application/vnd.github+json",
                    "X-GitHub-Api-Version" to "2022-11-28",
                    "User-Agent" to "AgentX-Android",
                ),
            )
        } catch (network: GitHubRestNetworkException) {
            logger.logNetworkFailure(connectionId.value)
            return failure(GitHubRepositoryError.NetworkFailure)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            return failure(GitHubRepositoryError.Unknown(error.message ?: "Request failed"))
        }

        return when {
            response.statusCode == 200 -> parseSuccess(response)
            response.statusCode == 401 -> failure(GitHubRepositoryError.Unauthenticated)
            response.statusCode == 403 -> {
                // GitHub returns 403 with rate limit details sometimes; if true, map to rate limited.
                val rateLimited = response.headers["x-ratelimit-remaining"]?.firstOrNull()?.toIntOrNull() == 0
                    || response.headers["x-ratelimit-resource"]?.firstOrNull()?.contains("core") == true
                if (rateLimited) failure(GitHubRepositoryError.RateLimited)
                else failure(GitHubRepositoryError.Forbidden)
            }
            response.statusCode == 404 -> failure(GitHubRepositoryError.NotFound)
            response.statusCode == 429 -> failure(GitHubRepositoryError.RateLimited)
            response.statusCode >= 500 -> failure(GitHubRepositoryError.ServerError(response.statusCode))
            else -> failure(GitHubRepositoryError.Unknown("Unexpected status ${response.statusCode}"))
        }
    }

    private fun parseSuccess(response: GitHubRestResponse): ForgeResult<GitHubRepositoryPage, GitHubRepositoryError> {
        val json = parseJsonBody(response.body)
        // GitHub returns either an array of repo objects directly for /user/repos.
        val repos: List<GitHubRepository> = when {
            json is JsonArray -> json.items.mapNotNull { item ->
                item.toRepository()
            }
            is JsonObject -> {
                // Could be a single object response; rare; treat as empty.
                emptyList()
            }
            else -> emptyList()
        }

        val totalCount = json.totalCount ?: repos.size
        val linkHeader = response.headers["link"]?.firstOrNull() ?: ""
        val (hasNext, nextPageNum) = parseLinkHeader(linkHeader, page = 1)

        // For large lists, we also support simple next-page detection when Link header absent.
        val fallbackHasMore = repos.size == 30
        val fallbackNextPage = if (fallbackHasMore) 2 else null

        val page = GitHubRepositoryPage(
            repositories = repos,
            hasNextPage = hasNext || fallbackHasMore,
            nextPageNumber = nextPageNum ?: fallbackNextPage,
            totalCount = totalCount,
        )
        return success(page)
    }

    private fun parseLinkHeader(linkHeader: String, page: Int): Pair<Boolean, Int?> {
        // GitHub Link header format: <url>; rel="next", <url>; rel="last"
        // Parse for `rel="next"`.
        val nextPattern = Regex("""<([^>]+)>;\s*rel="next"""")
        val nextMatch = nextPattern.find(linkHeader)
        if (nextMatch != null) {
            val nextUrl = nextMatch.groupValues[1]
            // Extract page param from URL.
            val pageParam = Regex("""page=(\d+)""").find(nextUrl)
            val nextPageNum = pageParam?.groupValues?.getOrNull(1)?.toIntOrNull()
            return nextMatch != null to nextPageNum
        }
        return false to null
    }

    private fun parseJsonBody(body: String): JsonValue {
        // Use a minimal lenient parser. Body may be empty or malformed.
        if (body.isBlank()) return JsonNull
        return try {
            parseJson(body)
        } catch (error: Exception) {
            JsonNull
        }
    }
}

private sealed interface JsonValue {
    val totalCount: Int?
    class JsonNull : JsonValue { override val totalCount: Int? = null }
    class JsonObject(val fields: Map<String, JsonValue>) : JsonValue {
        override val totalCount: Int? get() = fields["total_count"]?.asInt()
    }
    class JsonArray(val items: List<JsonValue>) : JsonValue {
        override val totalCount: Int? get() = null
    }
    class JsonString(val value: String) : JsonValue {
        override val totalCount: Int? get() = value.toIntOrNull()
    }
    class JsonNumber(val value: Double) : JsonValue {
        override val totalCount: Int? get() = value.toLongOrNull()?.toStringOrNull()?.toIntOrNull()
    }
}

private fun JsonValue.asInt(): Int? = when (this) {
    is JsonString -> value.toIntOrNull()
    is JsonNumber -> value.toLongOrNull()?.toIntOrNull()
    else -> null
}

private fun JsonValue.toRepository(): GitHubRepository? {
    if (this !is JsonObject) return null
    val id = fields["id"]?.asLongString()
    val ownerLogin = (fields["owner"] as? JsonObject)?.fields["login"]?.asString()
    val name = fields["name"]?.asString()
    val fullName = fields["full_name"]?.asString()
    val isPrivate = fields["private"]?.asBoolean() ?: false
    val defaultBranch = fields["default_branch"]?.asString() ?: "main"
    val cloneUrl = fields["clone_url"]?.asString()
    val htmlUrl = fields["html_url"]?.asString()
    val idValue = id ?: return null
    val ownerValue = ownerLogin ?: return null
    val nameValue = name ?: return null
    val fullNameValue = fullName ?: return null
    val cloneUrlValue = cloneUrl ?: return null
    val htmlUrlValue = htmlUrl ?: return null
    return GitHubRepository(
        id = RepositoryId(idValue),
        owner = ownerValue,
        name = nameValue,
        fullName = fullNameValue,
        visibility = if (isPrivate) GitHubRepositoryVisibility.PRIVATE else GitHubRepositoryVisibility.PUBLIC,
        defaultBranch = defaultBranch,
        cloneUrl = GitHubRepositoryCloneUrl.parse(cloneUrlValue),
        webUrl = htmlUrlValue,
    )
}

private fun JsonValue.asLongString(): String? = when (this) {
    is JsonNumber -> value.toString()
    is JsonString -> value.takeIf { it.isNotBlank() }
    else -> null
}

private fun JsonValue.asString(): String? = when (this) {
    is JsonString -> value.takeIf { it.isNotBlank() }
    is JsonNumber -> value.toString()
    else -> null
}

private fun JsonValue.asBoolean(): Boolean? = when (this) {
    is JsonObject -> fields["value"]?.asBoolean()
    else -> (this as? JsonString)?.value.equals("true", ignoreCase = true)
        ?: (this as? JsonNumber)?.value == 1.0
}

// --- Minimal JSON parser ---
private fun parseJson(input: String): JsonValue {
    val tokenizer = Tokenizer(input.trim())
    return tokenizer.parseValue()
}

private class Tokenizer(private val input: String) {
    private var pos = 0

    fun parseValue(): JsonValue {
        skipWhitespace()
        return when (current()) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> JsonString(parseString())
            't', 'f' -> parseBoolean()
            'n' -> parseNull()
            else -> parseNumber()
        }
    }

    private fun parseObject(): JsonObject {
        expect('{')
        skipWhitespace()
        val fields = mutableMapOf<String, JsonValue>()
        if (current() == '}') {
            pos++
            return JsonObject(fields)
        }
        while (true) {
            skipWhitespace()
            val key = parseString()
            expect(':')
            skipWhitespace()
            val value = parseValue()
            fields[key] = value
            skipWhitespace()
            if (current() == ',') {
                pos++
            } else if (current() == '}') {
                pos++
                return JsonObject(fields)
            } else {
                throw IllegalStateException("Unexpected character: ${current()}")
            }
        }
    }

    private fun parseArray(): JsonArray {
        expect('[')
        skipWhitespace()
        val items = mutableListOf<JsonValue>()
        if (current() == ']') {
            pos++
            return JsonArray(items)
        }
        while (true) {
            skipWhitespace()
            items.add(parseValue())
            skipWhitespace()
            if (current() == ',') {
                pos++
            } else if (current() == ']') {
                pos++
                return JsonArray(items)
            } else {
                throw IllegalStateException("Unexpected character: ${current()}")
            }
        }
    }

    private fun parseString(): String {
        expect('"')
        val sb = StringBuilder()
        while (current() != '"') {
            if (current() == '\\') {
                pos++
                when (current()) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        pos++
                        val hex = input.substring(pos, pos + 4)
                        sb.append(hex.toInt(16).toChar())
                        pos += 3
                    }
                    else -> throw IllegalStateException("Invalid escape: \\$")
                }
            } else {
                sb.append(current())
            }
            pos++
        }
        expect('"')
        return sb.toString()
    }

    private fun parseNumber(): JsonNumber {
        val start = pos
        if (current() == '-') pos++
        while (pos < input.length && current().isDigit()) pos++
        if (pos < input.length && current() == '.') {
            pos++
            while (pos < input.length && current().isDigit()) pos++
        }
        if (pos < input.length && (current() == 'e' || current() == 'E')) {
            pos++
            if (current() == '+' || current() == '-') pos++
            while (pos < input.length && current().isDigit()) pos++
        }
        return JsonNumber(input.substring(start, pos).toDouble())
    }

    private fun parseBoolean(): JsonObject {
        if (input.startsWith("true", pos)) { pos += 4; return JsonObject(emptyMap()) }
        if (input.startsWith("false", pos)) { pos += 5; return JsonObject(emptyMap()) }
        throw IllegalStateException("Invalid boolean")
    }

    private fun parseNull(): JsonNull {
        require(input.startsWith("null", pos)) { "Invalid null" }
        pos += 4
        return JsonNull
    }

    private fun current(): Char = if (pos < input.length) input[pos] else '\u0000'
    private fun skipWhitespace() {
        while (pos < input.length && current().isWhitespace()) pos++
    }
    private fun expect(c: Char) {
        if (current() != c) throw IllegalStateException("Expected '$c' but got '${current()}'")
        pos++
    }
}

// --- Logging (never logs tokens) ---
interface GitHubRepositoryLogger {
    fun logError(connectionId: String, error: GitHubRepositoryError, repositoriesListed: Int)
    fun logNetworkFailure(connectionId: String)
    fun logCloneStart(connectionId: String, owner: String, repo: String)
    fun logCloneProgress(connectionId: String, progress: String)
    fun logCloneComplete(connectionId: String, workspacePath: String)
    fun logCloneError(connectionId: String, error: GitHubRepositoryError)
}

private object QuietGitHubRepositoryLogger : GitHubRepositoryLogger {
    // In production, this could delegate to a proper logger. Tokens are never passed here.
    override fun logError(connectionId: String, error: GitHubRepositoryError, repositoriesListed: Int) {}
    override fun logNetworkFailure(connectionId: String) {}
    override fun logCloneStart(connectionId: String, owner: String, repo: String) {}
    override fun logCloneProgress(connectionId: String, progress: String) {}
    override fun logCloneComplete(connectionId: String, workspacePath: String) {}
    override fun logCloneError(connectionId: String, error: GitHubRepositoryError) {}
}
