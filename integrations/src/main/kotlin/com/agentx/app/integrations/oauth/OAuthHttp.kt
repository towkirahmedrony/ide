package com.agentx.app.integrations.oauth

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** One HTTP call made by the OAuth layer. Bodies may contain secrets. */
data class OAuthHttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val contentType: String? = null,
    val body: String? = null,
) {
    /** Never prints the body: it can carry a code, a verifier or a token. */
    override fun toString(): String = "OAuthHttpRequest(method=$method, url=${redactUrl(url)})"
}

/** Response of an OAuth/API call. [body] is never logged. */
data class OAuthHttpResponse(
    val statusCode: Int,
    val body: String,
    val contentType: String? = null,
) {
    val isSuccess: Boolean get() = statusCode in 200..299

    override fun toString(): String = "OAuthHttpResponse(statusCode=$statusCode)"

    companion object {
        /** Form-encoded token response, e.g. GitHub's default representation. */
        const val CONTENT_TYPE_FORM: String = "application/x-www-form-urlencoded"
    }
}

/**
 * HTTP port used by providers. Injectable so tests can run the full OAuth flow
 * with fake providers and no network.
 */
fun interface OAuthHttpClient {
    suspend fun execute(request: OAuthHttpRequest): OAuthHttpResponse
}

/** Raised when a provider call cannot be performed at all (DNS, TLS, timeout). */
class OAuthHttpException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Default [OAuthHttpClient] built on [HttpURLConnection].
 *
 * Requests are short-lived, time-boxed, and never redirected automatically:
 * a redirect to another host would hand the code or token to a third party.
 */
class UrlConnectionOAuthHttpClient(
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 20_000,
) : OAuthHttpClient {

    override suspend fun execute(request: OAuthHttpRequest): OAuthHttpResponse {
        val connection = try {
            URL(request.url).openConnection() as HttpURLConnection
        } catch (error: Exception) {
            throw OAuthHttpException("The provider URL could not be opened", error)
        }
        return try {
            connection.requestMethod = request.method
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.instanceFollowRedirects = false
            connection.doInput = true
            request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            request.contentType?.let { connection.setRequestProperty("Content-Type", it) }
            val body = request.body
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { stream -> stream.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            OAuthHttpResponse(
                statusCode = status,
                body = readBody(stream),
                contentType = connection.contentType?.takeIf { it.isNotBlank() },
            )
        } catch (error: Exception) {
            throw OAuthHttpException("The provider could not be reached", error)
        } finally {
            connection.disconnect()
        }
    }

    private fun readBody(stream: InputStream?): String =
        stream?.use { input -> input.readBytes().toString(Charsets.UTF_8) }.orEmpty()
}

/** Strips query parameters so an authorization URL can appear in a test/log safely. */
internal fun redactUrl(url: String): String = url.substringBefore('?')
