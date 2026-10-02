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

/**
 * Where a probe should read the model list, when that is not the endpoint the
 * provider chats on.
 *
 * [url] never carries a credential: a provider that needs a key header gets one,
 * which also keeps the key out of every logged or displayed URL.
 */
data class ModelListRequest(
    val url: String,
    val auth: ModelListAuth = ModelListAuth.BEARER,
)

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
    private val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MILLIS,
    /**
     * Extra attempts allowed for a probe that timed out.
     *
     * A Colab/ngrok/Cloudflare endpoint can accept the connection and still hold
     * the very first request while the tunnel finishes coming up: the request
     * reaches the server *after* the client has already given up, so the server
     * logs a 200 for a request the app reported as a timeout. One warm-up retry
     * turns that first-contact stall into a successful connect.
     */
    private val timeoutRetries: Int = DEFAULT_TIMEOUT_RETRIES,
    /** Overall cap for the candidate fan-out, so retries cannot grow unbounded. */
    private val overallTimeoutMillis: Long = DEFAULT_OVERALL_TIMEOUT_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
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
        val result = probeWithWarmUp(
            candidate = candidate,
            protocol = spec.protocol,
            credential = credential,
            preferredModelId = preferredModelId,
            catalogPreferred = spec.preferredModel,
            // A provider that lists models outside its chat surface (Gemini) is
            // asked at its own model-list path with its documented credential
            // header; the chat endpoint the preset keeps is unaffected.
            modelList = spec.modelListPath?.let { path ->
                ModelListRequest(
                    url = spec.modelListUrlFor(candidate.rootUrl),
                    auth = spec.modelListAuth,
                )
            },
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
        val deadline = clock() + overallTimeoutMillis

        candidates@ for (candidate in candidates) {
            for (protocol in protocols) {
                // Always make at least one attempt; the deadline only stops the
                // fan-out once a real answer exists to report.
                if (last != null && clock() >= deadline) break@candidates
                when (
                    val result = probeWithWarmUp(
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
        modelList: ModelListRequest? = null,
    ): DiscoveryResult {
        val url = modelList?.url ?: healthUrl(candidate, protocol)
        val headers = LinkedHashMap<String, String>()
        headers["Accept"] = "application/json"
        val auth = modelList?.auth ?: ModelListAuth.BEARER
        credential?.takeIf { it.isNotBlank() }?.let { headers[auth.headerName] = "${auth.scheme}$it" }

        val response = try {
            transport.execute(
                HttpRequestSpec(
                    method = "GET",
                    url = url,
                    headers = headers,
                    connectTimeoutMillis = connectTimeoutMillis,
                    readTimeoutMillis = readTimeoutMillis,
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
            in 200..299 -> parseSuccess(candidate, protocol, response.body, preferredModelId, catalogPreferred, url)
            401, 403 -> DiscoveryResult.Failed(
                kind = DiscoveryFailureKind.AUTHENTICATION_REQUIRED,
                message = "The server is reachable, but authentication is required.",
                httpStatus = response.statusCode,
                reachable = true,
            )
            404 -> DiscoveryResult.Failed(
                kind = DiscoveryFailureKind.NOT_FOUND,
                // The path (never the credential, never a query string) is part of
                // the message: it is what makes a wrong model-list location obvious.
                message = "No model list was found at ${diagnosticPath(url)}.",
                httpStatus = 404,
                reachable = true,
            )
            408 -> DiscoveryResult.Failed(
                kind = DiscoveryFailureKind.TIMEOUT,
                message = TIMEOUT_MESSAGE,
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

    /**
     * Retries a probe that timed out, within [timeoutRetries].
     *
     * Only timeouts are retried: 401/404/429 and the like are real answers and
     * repeating them would just be noise. The retry exists for the cold-tunnel
     * case, where the first request is what brings the tunnel up.
     */
    private suspend fun probeWithWarmUp(
        candidate: EndpointResolver.Candidate,
        protocol: ModelApiProtocol,
        credential: String?,
        preferredModelId: String?,
        catalogPreferred: String?,
        modelList: ModelListRequest? = null,
    ): DiscoveryResult {
        var attempt = 0
        while (true) {
            val result = probeCandidate(
                candidate = candidate,
                protocol = protocol,
                credential = credential,
                preferredModelId = preferredModelId,
                catalogPreferred = catalogPreferred,
                modelList = modelList,
            )
            if (result !is DiscoveryResult.Failed || result.kind != DiscoveryFailureKind.TIMEOUT) return result
            if (attempt >= timeoutRetries) return result
            attempt++
        }
    }

    private fun parseSuccess(
        candidate: EndpointResolver.Candidate,
        protocol: ModelApiProtocol,
        body: String,
        preferredModelId: String?,
        catalogPreferred: String?,
        requestedUrl: String,
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
        if (models.isEmpty()) {
            // Answered, but with nothing usable. Reported as a parsing outcome so it
            // is never mistaken for "no compatible provider", and so a caller cannot
            // present its built-in fallback as if discovery had found these models.
            return DiscoveryResult.Failed(
                kind = DiscoveryFailureKind.MALFORMED,
                message = "The model list at ${diagnosticPath(requestedUrl)} contained no models.",
                reachable = true,
            )
        }
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

    /**
     * Model ids from a model-list response.
     *
     * The OpenAI-compatible `data` array is tried first, then a provider's own
     * `models` array (Gemini's shape, where each entry names the model as
     * `models/<id>`). Ids are normalized so the two forms resolve to one model.
     */
    private fun extractModels(protocol: ModelApiProtocol, root: com.agentx.app.model.json.JsonObject): List<String>? {
        val items = when (protocol) {
            ModelApiProtocol.OPENAI_COMPATIBLE -> root.arrayOrNull("data") ?: root.arrayOrNull("models")
            ModelApiProtocol.OLLAMA -> root.arrayOrNull("models")
        } ?: return null
        return items.mapNotNull { item ->
            val obj = item.objectOrNull() ?: return@mapNotNull null
            (obj.stringOrNull("id") ?: obj.stringOrNull("name") ?: obj.stringOrNull("model"))
                ?.let(::normalizeModelId)
                ?.takeIf { it.isNotBlank() }
        }
    }


    private fun transportKind(error: Throwable): DiscoveryFailureKind = when (error) {
        is SocketTimeoutException -> DiscoveryFailureKind.TIMEOUT
        is UnknownHostException, is ConnectException -> DiscoveryFailureKind.UNREACHABLE
        is IOException -> DiscoveryFailureKind.UNREACHABLE
        else -> DiscoveryFailureKind.UNKNOWN
    }

    private fun transportMessage(error: Throwable): String = when (error) {
        is SocketTimeoutException -> TIMEOUT_MESSAGE
        is UnknownHostException -> "The model endpoint host could not be resolved."
        is ConnectException -> "The model endpoint refused the connection."
        is IOException -> "The model endpoint could not be reached."
        else -> "The model endpoint could not be reached."
    }

    companion object {
        /** Reaching the Colab/ngrok/Cloudflare edge. */
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS: Int = 15_000

        /**
         * Waiting for the model list. Deliberately larger than the connect
         * timeout: on first contact a free tunnel can hold the request while it
         * finishes coming up.
         */
        const val DEFAULT_READ_TIMEOUT_MILLIS: Int = 30_000

        const val DEFAULT_TIMEOUT_RETRIES: Int = 1

        /**
         * Hard cap for trying every candidate/protocol combination, so a
         * pathological endpoint cannot leave the app in "Discovering API…".
         */
        const val DEFAULT_OVERALL_TIMEOUT_MILLIS: Long = 90_000

        /**
         * Shown before any retry has been exhausted, so it tells the user what
         * to do rather than only what failed.
         */
        private const val TIMEOUT_MESSAGE: String =
            "The model endpoint did not respond in time. A Colab or ngrok tunnel " +
                "can be slow on its first request — try again."
    }
}
