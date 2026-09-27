package dev.forge.ide.model.http

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import kotlin.coroutines.coroutineContext

/**
 * Default [HttpTransport] built on [HttpURLConnection]. It is available on both
 * the JVM and Android and needs no external dependency.
 *
 * Blocking I/O always runs on [ioDispatcher] so Android chat turns cannot hit
 * `NetworkOnMainThreadException` or ANR the UI. Streaming line callbacks are
 * delivered on the caller's dispatcher so Compose/UI collectors stay on main.
 */
class UrlConnectionHttpTransport(
    private val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MILLIS,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : HttpTransport {

    override suspend fun execute(request: HttpRequestSpec): HttpResponseSpec =
        withContext(ioDispatcher) {
            val connection = open(request)
            try {
                val status = connection.responseCode
                val stream = if (status in 200..299) {
                    runCatching { connection.inputStream }.getOrNull()
                } else {
                    runCatching { connection.errorStream }.getOrNull()
                        ?: runCatching { connection.inputStream }.getOrNull()
                }
                val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                HttpResponseSpec(status, body, readHeaders(connection))
            } finally {
                connection.disconnect()
            }
        }

    override suspend fun executeStreaming(
        request: HttpRequestSpec,
        onLine: (String) -> Unit,
    ): HttpResponseSpec {
        val deliveryContext = coroutineContext.minusKey(Job)
        return withContext(ioDispatcher) {
            val connection = open(request)
            try {
                val status = connection.responseCode
                if (status !in 200..299) {
                    val body = runCatching { connection.errorStream }.getOrNull()
                        ?.bufferedReader(Charsets.UTF_8)
                        ?.use { it.readText() }
                        .orEmpty()
                    return@withContext HttpResponseSpec(status, body, readHeaders(connection))
                }
                val input = runCatching { connection.inputStream }.getOrNull()
                    ?: return@withContext HttpResponseSpec(status, "", readHeaders(connection))
                input.bufferedReader(Charsets.UTF_8).use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        coroutineContext.ensureActive()
                        val captured = line
                        withContext(deliveryContext) { onLine(captured) }
                        line = reader.readLine()
                    }
                }
                HttpResponseSpec(status, "", readHeaders(connection))
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun open(request: HttpRequestSpec): HttpURLConnection {
        val connection = URI(request.url).toURL().openConnection() as HttpURLConnection
        connection.requestMethod = request.method
        connection.connectTimeout = request.connectTimeoutMillis ?: connectTimeoutMillis
        connection.readTimeout = request.readTimeoutMillis ?: readTimeoutMillis
        connection.useCaches = false
        connection.instanceFollowRedirects = true
        request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        request.body?.let { body ->
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        return connection
    }

    private fun readHeaders(connection: HttpURLConnection): Map<String, List<String>> {
        val fields = connection.headerFields ?: return emptyMap()
        return fields.entries
            .mapNotNull { entry ->
                val name = entry.key ?: return@mapNotNull null
                name to (entry.value ?: emptyList())
            }
            .toMap()
    }

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS: Int = 15_000
        const val DEFAULT_READ_TIMEOUT_MILLIS: Int = 120_000
    }
}
