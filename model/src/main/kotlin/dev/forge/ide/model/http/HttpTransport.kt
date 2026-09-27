package dev.forge.ide.model.http

/** A provider-agnostic HTTP request. Headers may contain credentials. */
data class HttpRequestSpec(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
)

/** A provider-agnostic HTTP response. */
data class HttpResponseSpec(
    val statusCode: Int,
    val body: String = "",
    val headers: Map<String, List<String>> = emptyMap(),
) {
    val isSuccess: Boolean get() = statusCode in 200..299
}

/**
 * Minimal HTTP port. Providers depend on this rather than a concrete client so
 * they can be tested with canned responses and the platform can supply its own
 * transport later. Implementations block; callers are responsible for running
 * them off the main thread.
 */
interface HttpTransport {
    suspend fun execute(request: HttpRequestSpec): HttpResponseSpec

    /**
     * Executes a request whose response body is read line by line (for example
     * server-sent events). [onLine] is invoked for each line only on success;
     * on failure the (error) body is returned so the provider can normalize it.
     */
    suspend fun executeStreaming(request: HttpRequestSpec, onLine: (String) -> Unit): HttpResponseSpec
}
