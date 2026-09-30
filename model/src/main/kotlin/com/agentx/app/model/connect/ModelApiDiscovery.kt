package com.agentx.app.model.connect

import com.agentx.app.model.http.HttpRequestSpec
import com.agentx.app.model.http.HttpTransport
import com.agentx.app.model.http.UrlConnectionHttpTransport
import com.agentx.app.model.json.JsonCodec
import com.agentx.app.model.json.arrayOrNull
import com.agentx.app.model.json.objectOrNull
import com.agentx.app.model.json.stringOrNull
import com.agentx.app.model.preset.ModelApiProtocol
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.coroutines.cancellation.CancellationException

enum class DiscoveryFailureKind {
    UNREACHABLE,
    TIMEOUT,
    AUTHENTICATION_REQUIRED,
    NOT_FOUND,
    RATE_LIMITED,
    SERVER_ERROR,
    MALFORMED,
    UNSUPPORTED,
    CANCELLED,
    UNKNOWN,
}

data class DiscoveredApi(
    val protocol: ModelApiProtocol,
    val rootUrl: String,
    val apiBasePath: String,
    val modelIds: List<String>,
    val selectedModelId: String?,
    val candidate: EndpointResolver.Candidate,
    /** True when ids came from a known-provider catalog, not GET /models. */
    val catalogFallback: Boolean = false,
)

sealed interface DiscoveryResult {
    data class Found(val api: DiscoveredApi) : DiscoveryResult

    data class NeedsModelChoice(
        val api: DiscoveredApi,
        val modelIds: List<String>,
    ) : DiscoveryResult

    data class Failed(
        val kind: DiscoveryFailureKind,
        val message: String,
        val httpStatus: Int? = null,
        val reachable: Boolean = false,
    ) : DiscoveryResult
}

/**
 * Finds an OpenAI-compatible (or Ollama) API behind a user-supplied URL.
 *
 * Network traffic stays on [HttpTransport]. Callers never invent a second HTTP
 * client; this is the discovery half of connect, while chat verification goes
 * through the Model Gateway.
 */
class ModelApiDiscovery(
    private val transport: HttpTransport = UrlConnectionHttpTransport(),
    private val timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
) {

    suspend fun discover(
        rawEndpoint: String,
        credential: String? = null,
        preferredModelId: String? = null,
        catalogPreferred: String? = null,
    ): DiscoveryResult {
        val resolved = when (val outcome = EndpointResolver.resolve(rawEndpoint)) {
            is EndpointResolver.Outcome.Invalid -> return DiscoveryResult.Failed(
                kind = DiscoveryFailureKind.UNREACHABLE,
                message = outcome.reason,
            )
            is EndpointResolver.Outcome.Ok -> outcome.resolved
        }
        return discoverCandidates(
            candidates = resolved.candidates,
            credential = credential,
            preferredModelId = preferredModelId,
            catalogPreferred = catalogPreferred,
        )
    }

    suspend fun discoverKnown(
        spec: KnownProviderSpec,
        credential: String?,
        preferredModelId: String? = null,
    ): DiscoveryResult {
        val candidate = EndpointResolver.Candidate(
            rootUrl = spec.rootUrl.trimEnd('/'),
            apiBasePath = spec.apiBasePath,
            reason = spec.kind.displayName,
        )
        val result = probeCandidate(
            candidate = candidate,
            protocol = spec.protocol,
            credential = credential,
            preferredModelId = preferredModelId,
            catalogPreferred = spec.preferredModel,
        )
        if (result is DiscoveryResult.Failed && result.kind == DiscoveryFailureKind.NOT_FOUND) {
            val fallbackIds = spec.suggestedModels
            if (fallbackIds.isNotEmpty() && credential?.isNotBlank() == true) {
                val selected = selectDiscoveredModel(fallbackIds, preferredModelId, spec.preferredModel)
                    ?: fallbackIds.first()
                return DiscoveryResult.Found(
                    DiscoveredApi(
                        protocol = spec.protocol,
                        rootUrl = candidate.rootUrl,
                        apiBasePath = candidate.apiBasePath,
                        modelIds = fallbackIds,
                        selectedModelId = selected,
                        candidate = candidate,
                        catalogFallback = true,
                    ),
                )
            }
        }
        if (result is DiscoveryResult.Failed &&
            result.kind == DiscoveryFailureKind.AUTHENTICATION_REQUIRED &&
            spec.kind.requiresApiKey &&
            credential.isNullOrBlank()
        ) {
            return DiscoveryResult.Failed(
                kind = DiscoveryFailureKind.AUTHENTICATION_REQUIRED,
                message = "An API key is required for ${spec.kind.displayName}.",
                httpStatus = result.httpStatus,
                reachable = true,
            )
        }
        return result
    }

    internal suspend fun discoverCandidates(
        candidates: List<EndpointResolver.Candidate>,
        credential: String?,
        preferredModelId: String?,
        catalogPreferred: String?,
        protocols: List<ModelApiProtocol> = listOf(
            ModelApiProtocol.OPENAI_COMPATIBLE,
            ModelApiProtocol.OLLAMA,
        ),
    ): DiscoveryResult {
        if (candidates.isEmpty()) {
            return DiscoveryResult.Failed(
                kind = DiscoveryFailureKind.UNREACHABLE,
                message = "No endpoint candidates could be built from that URL.",
            )
        }

        var last: DiscoveryResult.Failed? = null
        var sawReachableUnsupported = false
        var sawAuth = false

        for (candidate in candidates) {
            for (protocol in protocols) {
                when (
                    val result = probeCandidate(
                        candidate = candidate,
                        protocol = protocol,
                        credential = credential,
                        preferredModelId = preferredModelId,
                        catalogPreferred = catalogPreferred,
                    )
                ) {
                    is DiscoveryResult.Found,
                    is DiscoveryResult.NeedsModelChoice,
                    -> return result

                    is DiscoveryResult.Failed -> {
                        last = result
                        when (result.kind) {
                            DiscoveryFailureKind.AUTHENTICATION_REQUIRED -> sawAuth = true
                            DiscoveryFailureKind.UNSUPPORTED,
                            DiscoveryFailureKind.MALFORMED,
                            -> if (result.reachable) sawReachableUnsupported = true
                            DiscoveryFailureKind.CANCELLED -> return result
                            else -> Unit
                        }
                    }
                }
            }
        }

        if (sawAuth) {
            return DiscoveryResult.Failed(
                kind = DiscoveryFailureKind.AUTHENTICATION_REQUIRED,
                message = "The server is reachable, but authentication is required.",
                httpStatus = last?.httpStatus,
                reachable = true,
            )
        }
        if (sawReachableUnsupported) {
            return DiscoveryResult.Failed(
                kind = DiscoveryFailureKind.UNSUPPORTED,
                message = "Server is reachable, but AgentX could not detect a supported API.",
                reachable = true,
            )
        }
        return last ?: DiscoveryResult.Failed(
            kind = DiscoveryFailureKind.UNREACHABLE,
            message = "The model endpoint could not be reached.",
        )
    }

    private suspend fun probeCandidate(
        candidate: EndpointResolver.Candidate,
        protocol: ModelApiProtocol,
        credential: String?,
        preferredModelId: String?,
        catalogPreferred: String?,
    ): DiscoveryResult {
        val url = healthUrl(candidate, protocol)
        val headers = LinkedHashMap<String, String>()
        headers["Accept"] = "application/json"
        credential?.takeIf { it.isNotBlank() }?.let { headers["Authorization"] = "Bearer $it" }

        val response = try {
            transport.execute(
                HttpRequestSpec(
                    method = "GET",
                    url = url,
                    headers = headers,
                    connectTimeoutMillis = timeoutMillis,
                    readTimeoutMillis = timeoutMillis,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            return DiscoveryResult.Failed(
                kind = transportKind(error),
                message = transportMessage(error),
            )
        }

        return when (response.statusCode) {
            in 200..299 -> parseSuccess(candidate, protocol, response.body, preferredModelId, catalogPreferred)
            401, 403 -> DiscoveryResult.Failed(
                kind = DiscoveryFailureKind.AUTHENTICATION_REQUIRED,
                message = "The server is reachable, but authentication is required.",
                httpStatus = response.statusCode,
                reachable = true,
            )
            404 -> DiscoveryResult.Failed(
                kind = DiscoveryFailureKind.NOT_FOUND,
                message = "No model list was found at this path.",
                httpStatus = 404,
                reachable = true,
            )
            408 -> DiscoveryResult.Failed(
                kind = DiscoveryFailureKind.TIMEOUT,
                message = "The model endpoint did not respond in time.",
                httpStatus = 408,
                reachable = true,
            )
            429 -> DiscoveryResult.Failed(
                kind = DiscoveryFailureKind.RATE_LIMITED,
                message = "The model endpoint is rate-limiting requests. Try again shortly.",
                httpStatus = 429,
                reachable = true,
            )
            in 500..599 -> DiscoveryResult.Failed(
                kind = DiscoveryFailureKind.SERVER_ERROR,
                message = "The model endpoint returned HTTP ${response.statusCode}.",
                httpStatus = response.statusCode,
                reachable = true,
            )
            else -> DiscoveryResult.Failed(
                kind = DiscoveryFailureKind.UNSUPPORTED,
                message = "The endpoint returned HTTP ${response.statusCode}.",
                httpStatus = response.statusCode,
                reachable = true,
            )
        }
    }

    private fun parseSuccess(
        candidate: EndpointResolver.Candidate,
        protocol: ModelApiProtocol,
        body: String,
        preferredModelId: String?,
        catalogPreferred: String?,
    ): DiscoveryResult {
        val root = runCatching { JsonCodec.parse(body).objectOrNull() }.getOrNull()
            ?: return DiscoveryResult.Failed(
                kind = DiscoveryFailureKind.UNSUPPORTED,
                message = "Server is reachable, but AgentX could not detect a supported API.",
                reachable = true,
            )
        val models = extractModels(protocol, root)
            ?: return DiscoveryResult.Failed(
                kind = DiscoveryFailureKind.UNSUPPORTED,
                message = "Server is reachable, but AgentX could not detect a supported API.",
                reachable = true,
            )
        val selected = selectDiscoveredModel(models, preferredModelId, catalogPreferred)
        val api = DiscoveredApi(
            protocol = protocol,
            rootUrl = candidate.rootUrl,
            apiBasePath = candidate.apiBasePath,
            modelIds = models,
            selectedModelId = selected,
            candidate = candidate,
        )
        return if (selected == null && models.size > 1) {
            DiscoveryResult.NeedsModelChoice(api, models)
        } else {
            DiscoveryResult.Found(api)
        }
    }

    private fun healthUrl(candidate: EndpointResolver.Candidate, protocol: ModelApiProtocol): String {
        val base = candidate.rootUrl.trimEnd('/')
        return if (protocol.healthIsRelativeToApiBase) {
            base + candidate.apiBasePath + protocol.defaultHealthPath
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

    private fun transportKind(error: Throwable): DiscoveryFailureKind = when (error) {
        is SocketTimeoutException -> DiscoveryFailureKind.TIMEOUT
        is UnknownHostException, is ConnectException -> DiscoveryFailureKind.UNREACHABLE
        is IOException -> DiscoveryFailureKind.UNREACHABLE
        else -> DiscoveryFailureKind.UNKNOWN
    }

    private fun transportMessage(error: Throwable): String = when (error) {
        is SocketTimeoutException -> "The model endpoint did not respond in time."
        is UnknownHostException -> "The model endpoint host could not be resolved."
        is ConnectException -> "The model endpoint refused the connection."
        is IOException -> "The model endpoint could not be reached."
        else -> "The model endpoint could not be reached."
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS: Int = 8_000
    }
}
