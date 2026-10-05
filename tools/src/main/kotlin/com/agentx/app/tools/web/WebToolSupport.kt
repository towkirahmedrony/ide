package com.agentx.app.tools.web

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI

/** A minimal HTTP GET response: status, decoded body, and the resolved URL. */
data class HttpGetResponse(
    val statusCode: Int,
    val body: String,
    val contentType: String? = null,
    val finalUrl: String,
)

/**
 * The HTTP port the web tools fetch through.
 *
 * It is a port, not a client, so a test can supply canned pages and the platform
 * can swap the transport (for example the embedded runtime's network stack)
 * without touching the tools. Blocking work must happen off the caller's thread;
 * [get] is suspending so callers can integrate it into the coroutine tree.
 */
fun interface HttpGetClient {
    suspend fun get(url: String, headers: Map<String, String>): HttpGetResponse
}

/** Bindable [HttpGetClient]; fails closed until the app binds a real transport. */
class DelegatingHttpGetClient(
    @Volatile private var delegate: HttpGetClient = UnavailableHttpGetClient,
) : HttpGetClient {
    fun bind(client: HttpGetClient) {
        delegate = client
    }

    override suspend fun get(url: String, headers: Map<String, String>): HttpGetResponse =
        delegate.get(url, headers)
}

/** Fails every request; used when no transport is bound. */
object UnavailableHttpGetClient : HttpGetClient {
    override suspend fun get(url: String, headers: Map<String, String>): HttpGetResponse =
        throw WebUnavailableException("Network access is not configured in this build")
}

class WebUnavailableException(message: String) : RuntimeException(message)

/**
 * Default [HttpGetClient] over [HttpURLConnection]. JVM and Android both provide
 * it, so the web tools work without adding a networking dependency. The body is
 * capped so a huge page cannot blow up the agent's context.
 */
class UrlConnectionHttpGetClient(
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 20_000,
    private val userAgent: String = "AgentX/1.0 (+https://agentx.app)",
) : HttpGetClient {

    override suspend fun get(url: String, headers: Map<String, String>): HttpGetResponse =
        withContext(Dispatchers.IO) {
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            try {
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                    val buffer = CharArray(MAX_BODY_CHARS)
                    val read = reader.read(buffer)
                    if (read <= 0) "" else String(buffer, 0, read)
                }.orEmpty()
                HttpGetResponse(
                    statusCode = status,
                    body = body,
                    contentType = connection.contentType,
                    finalUrl = connection.url?.toString() ?: url,
                )
            } finally {
                connection.disconnect()
            }
        }

    companion object {
        const val MAX_BODY_CHARS = 200_000
    }
}

/** One search hit: title, URL and a short snippet. */
data class WebSearchItem(val title: String, val url: String, val snippet: String)

/**
 * The search provider port.
 *
 * It is deliberately separate from the research *model*: a model may summarize
 * the hits, but the hits themselves come from this provider. Swapping providers
 * (a hosted search API, a self-hosted instance, a future MCP server) is a matter
 * of binding a different implementation; no model provider is ever the search
 * implementation.
 */
fun interface WebSearchProvider {
    suspend fun search(query: String, limit: Int): List<WebSearchItem>
}

/** Bindable [WebSearchProvider]; fails closed until the app binds a real provider. */
class DelegatingWebSearchProvider(
    @Volatile private var delegate: WebSearchProvider = UnavailableWebSearchProvider,
) : WebSearchProvider {
    fun bind(provider: WebSearchProvider) {
        delegate = provider
    }

    override suspend fun search(query: String, limit: Int): List<WebSearchItem> = delegate.search(query, limit)
}

object UnavailableWebSearchProvider : WebSearchProvider {
    override suspend fun search(query: String, limit: Int): List<WebSearchItem> =
        throw WebUnavailableException("No web search provider is configured in this build")
}

/**
 * A real, keyless search provider backed by the DuckDuckGo Instant Answer API.
 *
 * It is intentionally provider-agnostic at the seam: the tool depends on
 * [WebSearchProvider], and this class is just one implementation the app may
 * bind. It issues a plain HTTP GET through [client] and extracts the abstract
 * and related topics from the JSON response, so a truncated or unexpected page
 * yields fewer results rather than an exception.
 */
class DuckDuckGoWebSearchProvider(
    private val client: HttpGetClient,
    private val endpoint: String = "https://html.duckduckgo.com/html/",
) : WebSearchProvider {

    override suspend fun search(query: String, limit: Int): List<WebSearchItem> {
        if (query.isBlank()) return emptyList()
        val url = endpoint + "?q=" + java.net.URLEncoder.encode(query, "UTF-8")
        val response = client.get(url, headers = mapOf("Accept" to "text/html"))
        if (response.statusCode !in 200..299) {
            throw WebUnavailableException("Search provider returned HTTP ${response.statusCode}")
        }
        return parseHtmlResults(response.body, limit)
    }

    /**
     * Extracts result anchors and snippets from the DuckDuckGo HTML endpoint.
     * The parser is tolerant: missing snippets simply come back empty, and no
     * result is fabricated when the page has none.
     */
    private fun parseHtmlResults(html: String, limit: Int): List<WebSearchItem> {
        val anchor = Regex(
            """<a[^>]*class="[^"]*result__a[^"]*"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        val snippet = Regex(
            """<a[^>]*class="[^"]*result__snippet[^"]*"[^>]*>(.*?)</a>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        val snippets = snippet.findAll(html).map { stripTags(it.groupValues[1]) }.toList()
        return anchor.findAll(html).take(limit).mapIndexed { index, match ->
            WebSearchItem(
                title = stripTags(match.groupValues[2]).ifBlank { "Result ${index + 1}" },
                url = decodeRedirect(match.groupValues[1]),
                snippet = snippets.getOrElse(index) { "" },
            )
        }.toList()
    }

    private fun decodeRedirect(href: String): String {
        // DuckDuckGo wraps results as //duckduckgo.com/l/?uddg=<encoded>; unwrap it.
        val marker = "uddg="
        val index = href.indexOf(marker)
        if (index < 0) return href
        val encoded = href.substring(index + marker.length).substringBefore('&')
        return runCatching { java.net.URLDecoder.decode(encoded, "UTF-8") }.getOrDefault(href)
    }

    private fun stripTags(html: String): String = html
        .replace(Regex("<[^>]+>"), " ")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#x27;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace(Regex("\\s+"), " ")
        .trim()
}
