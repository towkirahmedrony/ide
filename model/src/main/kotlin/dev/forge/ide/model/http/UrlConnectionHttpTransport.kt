package dev.forge.ide.model.http

import java.net.HttpURLConnection
import java.net.URI

/**
 * Default [HttpTransport] built on [HttpURLConnection]. It is available on both
 * the JVM and Android and needs no external dependency. Blocking I/O happens on
 * the caller's thread; callers should dispatch appropriately.
 */
class UrlConnectionHttpTransport(
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 120_000,
) : HttpTransport {

    override suspend fun execute(request: HttpRequestSpec): HttpResponseSpec {
        val connection = open(request)
        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return HttpResponseSpec(status, body, readHeaders(connection))
        } finally {
            connection.disconnect()
        }
    }

    override suspend fun executeStreaming(request: HttpRequestSpec, onLine: (String) -> Unit): HttpResponseSpec {
        val connection = open(request)
        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                val body = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                return HttpResponseSpec(status, body, readHeaders(connection))
            }
            connection.inputStream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    onLine(line)
                    line = reader.readLine()
                }
            }
            return HttpResponseSpec(status, "", readHeaders(connection))
        } finally {
            connection.disconnect()
        }
    }

    private fun open(request: HttpRequestSpec): HttpURLConnection {
        val connection = URI(request.url).toURL().openConnection() as HttpURLConnection
        connection.requestMethod = request.method
        connection.connectTimeout = connectTimeoutMillis
        connection.readTimeout = readTimeoutMillis
        request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        request.body?.let { body ->
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        return connection
    }

    private fun readHeaders(connection: HttpURLConnection): Map<String, List<String>> =
        connection.headerFields.entries
            .mapNotNull { entry ->
                val name = entry.key ?: return@mapNotNull null
                name to (entry.value ?: emptyList())
            }
            .toMap()
}
