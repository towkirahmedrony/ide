package com.agentx.app.model.runtime

import com.agentx.app.model.http.HttpRequestSpec
import com.agentx.app.model.http.HttpTransport
import com.agentx.app.model.http.UrlConnectionHttpTransport
import com.agentx.app.model.json.JsonCodec
import com.agentx.app.model.json.arrayOrNull
import com.agentx.app.model.json.objectOrNull
import com.agentx.app.model.json.stringOrNull
import com.agentx.app.model.preset.ModelApiProtocol
import com.agentx.app.model.preset.ModelPreset
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

enum class ModelHealthStatus { HEALTHY, DEGRADED, UNHEALTHY }

/** Outcome of verifying that the model API behind an endpoint really answers. */
data class ModelHealth(
    val status: ModelHealthStatus,
    val detail: String,
    val models: List<String> = emptyList(),
    val latencyMillis: Long? = null,
) {
    val isReachable: Boolean get() = status != ModelHealthStatus.UNHEALTHY

    companion object {
        fun unhealthy(detail: String) = ModelHealth(ModelHealthStatus.UNHEALTHY, detail)
    }
}

/**
 * Verifies that [endpoint] serves the *model API*, not merely that some web
 * server answers on that URL.
 *
 * For protocols with a defined model-list endpoint the response body must parse
 * as that list — a 200 HTML page (a proxy error page, a captive portal) is
 * therefore reported as unhealthy instead of being mistaken for a live model.
 * When the user configured a custom health path, the custom path defines the
 * contract and any 2xx response counts.
 */
interface ModelHealthChecker {
    suspend fun check(preset: ModelPreset, endpoint: ModelEndpoint, credential: String?): ModelHealth
}

class HttpModelHealthChecker(
    private val transport: HttpTransport = UrlConnectionHttpTransport(),
    private val clock: () -> Long = System::currentTimeMillis,
) : ModelHealthChecker {

    override suspend fun check(
        preset: ModelPreset,
        endpoint: ModelEndpoint,
        credential: String?,
    ): ModelHealth {
        val url = healthUrl(preset, endpoint)
        val headers = LinkedHashMap<String, String>()
        headers["Accept"] = "application/json"
        // The credential is only ever placed in the header; it is never logged.
        credential?.takeIf { it.isNotBlank() }?.let { headers["Authorization"] = "Bearer $it" }

        val started = clock()
        val response = try {
            transport.execute(HttpRequestSpec(method = "GET", url = url, headers = headers))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            return ModelHealth.unhealthy(transportMessage(error))
        }
        val latency = clock() - started

        if (response.statusCode !in 200..299) {
            return ModelHealth(
                status = ModelHealthStatus.UNHEALTHY,
                detail = httpMessage(response.statusCode),
                latencyMillis = latency,
            )
        }

        // A user-defined health path defines its own contract.
        if (!preset.health.path.isNullOrBlank()) {
            return ModelHealth(
                status = ModelHealthStatus.HEALTHY,
                detail = "Health endpoint responded with HTTP ${response.statusCode}",
                latencyMillis = latency,
            )
        }

        val root = runCatching { JsonCodec.parse(response.body).objectOrNull() }.getOrNull()
            ?: return ModelHealth(
                status = ModelHealthStatus.UNHEALTHY,
                detail = "The endpoint answered but did not return a JSON model list; " +
                    "it may not be the model API",
                latencyMillis = latency,
            )

        val models = extractModels(preset.apiProtocol, root)
            ?: return ModelHealth(
                status = ModelHealthStatus.UNHEALTHY,
                detail = "The endpoint answered with JSON but no model list, so the model API " +
                    "could not be confirmed",
                latencyMillis = latency,
            )

        if (models.isEmpty()) {
            return ModelHealth(
                status = ModelHealthStatus.DEGRADED,
                detail = "The model API responded but reported no models",
                latencyMillis = latency,
            )
        }

        if (preset.health.requireModelInList && models.none { it == preset.modelIdentifier }) {
            return ModelHealth(
                status = ModelHealthStatus.DEGRADED,
                detail = "The endpoint is reachable but does not offer '${preset.modelIdentifier}' " +
                    "(offers: ${models.take(MAX_NAMED_MODELS).joinToString(", ")})",
                models = models,
                latencyMillis = latency,
            )
        }

        return ModelHealth(
            status = ModelHealthStatus.HEALTHY,
            detail = "Model API reachable (${models.size} model${if (models.size == 1) "" else "s"} reported)",
            models = models,
            latencyMillis = latency,
        )
    }

    /** Health URL: protocol default, or the user's override path. */
    private fun healthUrl(preset: ModelPreset, endpoint: ModelEndpoint): String {
        val base = endpoint.url.trimEnd('/')
        preset.health.path?.takeIf { it.isNotBlank() }?.let { return base + it }
        val protocol = preset.apiProtocol
        return if (protocol.healthIsRelativeToApiBase) {
            base + preset.normalizedApiBasePath + protocol.defaultHealthPath
        } else {
            base + protocol.defaultHealthPath
        }
    }

    private fun extractModels(protocol: ModelApiProtocol, root: com.agentx.app.model.json.JsonObject): List<String>? {
        val items = when (protocol) {
            ModelApiProtocol.OPENAI_COMPATIBLE -> root.arrayOrNull("data")
            ModelApiProtocol.OLLAMA -> root.arrayOrNull("models")
        } ?: return null

        return items.mapNotNull { item ->
            val obj = item.objectOrNull() ?: return@mapNotNull null
            obj.stringOrNull("id") ?: obj.stringOrNull("name") ?: obj.stringOrNull("model")
        }
    }

    private fun transportMessage(error: Throwable): String = when (error) {
        is java.net.SocketTimeoutException -> "The model endpoint did not respond in time"
        is java.net.UnknownHostException -> "The model endpoint host could not be resolved"
        is java.net.ConnectException -> "The model endpoint refused the connection"
        is IOException -> "Network error while contacting the model endpoint"
        else -> "The model endpoint could not be reached: ${error::class.simpleName}"
    }

    private fun httpMessage(status: Int): String = when (status) {
        401, 403 -> "The model endpoint rejected the credential (HTTP $status)"
        404 -> "The model list endpoint was not found (HTTP 404); check the API base path"
        else -> "The model endpoint returned HTTP $status"
    }

    private companion object {
        const val MAX_NAMED_MODELS = 5
    }
}
