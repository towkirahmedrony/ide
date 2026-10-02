package com.agentx.app.model.connect

import com.agentx.app.core.logging.ForgeLogger
import com.agentx.app.model.diagnostics.ApiOperation
import com.agentx.app.model.diagnostics.ApiTrace
import com.agentx.app.model.diagnostics.configuredFlag
import com.agentx.app.model.diagnostics.firstHeaderValue
import com.agentx.app.model.diagnostics.rateLimitHeaderNames
import com.agentx.app.model.diagnostics.safeResponsePreview
import com.agentx.app.model.diagnostics.sanitizeForLog
import com.agentx.app.model.http.HttpRequestSpec
import com.agentx.app.model.http.HttpTransport
import com.agentx.app.model.http.UrlConnectionHttpTransport
import com.agentx.app.model.json.JsonCodec
import com.agentx.app.model.json.JsonObject
import com.agentx.app.model.json.arrayOrNull
import com.agentx.app.model.json.objectOrNull
import com.agentx.app.model.json.stringOrNull
import com.agentx.app.model.preset.ModelApiProtocol
import com.agentx.app.model.preset.ModelProviderIds
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
 *
 * Every stage writes a compact, credential-free line to [logger] so a failed
 * Gemini/Groq connect can be pin-pointed in the Developer Log: request path →
 * authentication → HTTP status → JSON parsing → model normalization → filtering
 * → final catalog. Logging is opt-in ([logger] defaults to null) and never
 * changes the result of a probe.
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
    /** Structured Developer Log sink for discovery diagnostics; null disables it. */
    private val logger: ForgeLogger? = null,
) {

    suspend fun discover(
        rawEndpoint: String,
        credential: String? = null,
        preferredModelId: String? = null,
        catalogPreferred: String? = null,
    ): DiscoveryResult {
        val trace = ApiTrace.create(logger, GENERIC_PROVIDER_LABEL, ApiOperation.DISCOVERY)
        val resolved = when (val outcome = EndpointResolver.resolve(rawEndpoint)) {
            is EndpointResolver.Outcome.Invalid -> {
                trace.failure(
                    "ERROR",
                    "stage" to "resolve-endpoint",
                    "kind" to DiscoveryFailureKind.UNREACHABLE.name,
                    "message" to sanitizeForLog(outcome.reason),
                )
                return DiscoveryResult.Failed(
                    kind = DiscoveryFailureKind.UNREACHABLE,
                    message = outcome.reason,
                )
            }

            is EndpointResolver.Outcome.Ok -> outcome.resolved
        }
        trace.stage(
            "START",
            "operation" to "model-discovery",
            "source" to "user-endpoint",
            "endpoint" to diagnosticPath(rawEndpoint),
            "candidates" to resolved.candidates.size,
            "hasApiKey" to configuredFlag(!credential.isNullOrBlank()),
            "preferredModel" to (preferredModelId ?: "-"),
        )
        val result = discoverCandidates(
            candidates = resolved.candidates,
            credential = credential,
            preferredModelId = preferredModelId,
            catalogPreferred = catalogPreferred,
            trace = trace,
        )
        traceOutcome(trace, result)
        return result
    }

    suspend fun discoverKnown(
        spec: KnownProviderSpec,
        credential: String?,
        preferredModelId: String? = null,
    ): DiscoveryResult {
        val providerId = ModelProviderIds.forPreset(spec.kind.id, spec.protocol)
        val trace = ApiTrace.create(logger, providerId, ApiOperation.DISCOVERY)
        val candidate = EndpointResolver.Candidate(
            rootUrl = spec.rootUrl.trimEnd('/'),
            apiBasePath = spec.apiBasePath,
            reason = spec.kind.displayName,
        )
        trace.stage(
            "START",
            "operation" to "model-discovery",
            "provider" to providerId,
            "endpointType" to spec.protocol.name.lowercase(),
            "rootUrl" to diagnosticPath(candidate.rootUrl),
            "basePath" to candidate.apiBasePath.ifBlank { "-" },
            "modelListPath" to modelListDiagnosticPath(spec, candidate.rootUrl),
            "authScheme" to authSchemeLabel(spec.modelListAuth),
            "hasApiKey" to configuredFlag(!credential.isNullOrBlank()),
            "preferredModel" to (preferredModelId ?: "-"),
            "catalogPreferred" to (spec.preferredModel ?: "-"),
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
            trace = trace,
        )
        if (result is DiscoveryResult.Failed && result.kind == DiscoveryFailureKind.NOT_FOUND) {
            val fallbackIds = spec.suggestedModels
            if (fallbackIds.isNotEmpty() && credential?.isNotBlank() == true) {
                val selected = selectDiscoveredModel(fallbackIds, preferredModelId, spec.preferredModel)
                    ?: fallbackIds.first()
                trace.warn(
                    "FALLBACK",
                    "reason" to "model-list-not-found",
                    "status" to (result.httpStatus ?: "-"),
                    "models" to fallbackIds.size,
                    "selected" to selected,
                )
                val fallback = DiscoveryResult.Found(
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
                traceOutcome(trace, fallback)
                return fallback
            }
            trace.warn(
                "FALLBACK",
                "used" to false,
                "reason" to if (fallbackIds.isEmpty()) "no-compatible-catalog" else "no-credential",
                "status" to (result.httpStatus ?: "-"),
            )
        }
        if (result is DiscoveryResult.Failed &&
            result.kind == DiscoveryFailureKind.AUTHENTICATION_REQUIRED &&
            spec.kind.requiresApiKey &&
            credential.isNullOrBlank()
        ) {
            val missing = DiscoveryResult.Failed(
                kind = DiscoveryFailureKind.AUTHENTICATION_REQUIRED,
                message = "An API key is required for ${spec.kind.displayName}.",
                httpStatus = result.httpStatus,
                reachable = true,
            )
            traceOutcome(trace, missing)
            return missing
        }
        traceOutcome(trace, result)
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
        trace: ApiTrace? = null,
    ): DiscoveryResult {
        val active = trace ?: ApiTrace.create(null, GENERIC_PROVIDER_LABEL, ApiOperation.DISCOVERY)
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
        var attempts = 0

        candidates@ for (candidate in candidates) {
            for (protocol in protocols) {
                // Always make at least one attempt; the deadline only stops the
                // fan-out once a real answer exists to report.
                if (last != null && clock() >= deadline) {
                    active.warn(
                        "DEADLINE",
                        "reason" to "overall-timeout",
                        "attempts" to attempts,
                        "overallTimeoutMs" to overallTimeoutMillis,
                    )
                    break@candidates
                }
                attempts++
                when (
                    val result = probeWithWarmUp(
                        candidate = candidate,
                        protocol = protocol,
                        credential = credential,
                        preferredModelId = preferredModelId,
                        catalogPreferred = catalogPreferred,
                        trace = active,
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
        trace: ApiTrace,
    ): DiscoveryResult {
        val url = modelList?.url ?: healthUrl(candidate, protocol)
        val headers = LinkedHashMap<String, String>()
        headers["Accept"] = "application/json"
        val auth = modelList?.auth ?: ModelListAuth.BEARER
        credential?.takeIf { it.isNotBlank() }?.let { headers[auth.headerName] = "${auth.scheme}$it" }

        // Wall-clock timing uses nanoTime, never the injected [clock]: the clock is
        // the deadline's input, and counting extra reads would change how many
        // candidates the fan-out gets through.
        val startedNanos = System.nanoTime()
        trace.stage(
            "REQUEST",
            "method" to "GET",
            "path" to diagnosticPath(url),
            "purpose" to "model-discovery",
            "protocol" to protocol.name,
            "accept" to "application/json",
            "hasApiKey" to configuredFlag(!credential.isNullOrBlank()),
            "authScheme" to if (credential.isNullOrBlank()) "none" else authSchemeLabel(auth),
        )

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
            val kind = transportKind(error)
            trace.failure(
                "ERROR",
                "stage" to "transport",
                "path" to diagnosticPath(url),
                "kind" to kind.name,
                "exception" to error.javaClass.name,
                "message" to sanitizeForLog(error.message ?: error.javaClass.name),
                "elapsedMs" to elapsedMillis(startedNanos),
                "retries" to timeoutRetries,
            )
            return DiscoveryResult.Failed(
                kind = kind,
                message = transportMessage(error),
            )
        }

        val elapsed = elapsedMillis(startedNanos)
        trace.stage(
            "RESPONSE",
            "status" to response.statusCode,
            "elapsedMs" to elapsed,
            "bodyBytes" to response.body.length,
            "contentType" to contentTypeOf(response.headers),
            "requestId" to requestIdOf(response.headers),
            "retryAfter" to (response.headers.firstHeaderValue("Retry-After") ?: "-"),
            "rateLimitHeaders" to (response.headers.rateLimitHeaderNames().joinToString(",").ifBlank { "-" }),
            "success" to (response.statusCode in 200..299),
        )

        return when (response.statusCode) {
            in 200..299 -> parseSuccess(
                candidate = candidate,
                protocol = protocol,
                body = response.body,
                preferredModelId = preferredModelId,
                catalogPreferred = catalogPreferred,
                requestedUrl = url,
                trace = trace,
            )

            401, 403 -> {
                trace.failure(
                    "ERROR",
                    "stage" to "http",
                    "status" to response.statusCode,
                    "kind" to DiscoveryFailureKind.AUTHENTICATION_REQUIRED.name,
                    "path" to diagnosticPath(url),
                    "authScheme" to if (credential.isNullOrBlank()) "none" else authSchemeLabel(auth),
                    "message" to "The server is reachable, but authentication is required.",
                )
                DiscoveryResult.Failed(
                    kind = DiscoveryFailureKind.AUTHENTICATION_REQUIRED,
                    message = "The server is reachable, but authentication is required.",
                    httpStatus = response.statusCode,
                    reachable = true,
                )
            }

            404 -> {
                trace.failure(
                    "ERROR",
                    "stage" to "http",
                    "status" to 404,
                    "kind" to DiscoveryFailureKind.NOT_FOUND.name,
                    "path" to diagnosticPath(url),
                    "message" to "No model list was found at ${diagnosticPath(url)}.",
                )
                DiscoveryResult.Failed(
                    kind = DiscoveryFailureKind.NOT_FOUND,
                    // The path (never the credential, never a query string) is part of
                    // the message: it is what makes a wrong model-list location obvious.
                    message = "No model list was found at ${diagnosticPath(url)}.",
                    httpStatus = 404,
                    reachable = true,
                )
            }

            408 -> {
                trace.failure(
                    "ERROR",
                    "stage" to "http",
                    "status" to 408,
                    "kind" to DiscoveryFailureKind.TIMEOUT.name,
                    "path" to diagnosticPath(url),
                    "message" to TIMEOUT_MESSAGE,
                )
                DiscoveryResult.Failed(
                    kind = DiscoveryFailureKind.TIMEOUT,
                    message = TIMEOUT_MESSAGE,
                    httpStatus = 408,
                    reachable = true,
                )
            }

            429 -> {
                trace.failure(
                    "ERROR",
                    "stage" to "http",
                    "status" to 429,
                    "kind" to DiscoveryFailureKind.RATE_LIMITED.name,
                    "path" to diagnosticPath(url),
                    "retryAfter" to (response.headers.firstHeaderValue("Retry-After") ?: "-"),
                    "rateLimitHeaders" to (response.headers.rateLimitHeaderNames().joinToString(",").ifBlank { "-" }),
                    "message" to "The model endpoint is rate-limiting requests. Try again shortly.",
                )
                DiscoveryResult.Failed(
                    kind = DiscoveryFailureKind.RATE_LIMITED,
                    message = "The model endpoint is rate-limiting requests. Try again shortly.",
                    httpStatus = 429,
                    reachable = true,
                )
            }

            in 500..599 -> {
                trace.failure(
                    "ERROR",
                    "stage" to "http",
                    "status" to response.statusCode,
                    "kind" to DiscoveryFailureKind.SERVER_ERROR.name,
                    "path" to diagnosticPath(url),
                    "preview" to (safeResponsePreview(response.body) ?: "omitted"),
                )
                DiscoveryResult.Failed(
                    kind = DiscoveryFailureKind.SERVER_ERROR,
                    message = "The model endpoint returned HTTP ${response.statusCode}.",
                    httpStatus = response.statusCode,
                    reachable = true,
                )
            }

            else -> {
                trace.failure(
                    "ERROR",
                    "stage" to "http",
                    "status" to response.statusCode,
                    "kind" to DiscoveryFailureKind.UNSUPPORTED.name,
                    "path" to diagnosticPath(url),
                    "preview" to (safeResponsePreview(response.body) ?: "omitted"),
                )
                DiscoveryResult.Failed(
                    kind = DiscoveryFailureKind.UNSUPPORTED,
                    message = "The endpoint returned HTTP ${response.statusCode}.",
                    httpStatus = response.statusCode,
                    reachable = true,
                )
            }
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
        trace: ApiTrace,
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
                trace = trace,
            )
            if (result !is DiscoveryResult.Failed || result.kind != DiscoveryFailureKind.TIMEOUT) return result
            if (attempt >= timeoutRetries) return result
            attempt++
            trace.warn("RETRY", "reason" to "timeout", "attempt" to attempt, "path" to diagnosticPath(modelList?.url ?: healthUrl(candidate, protocol)))
        }
    }

    private fun parseSuccess(
        candidate: EndpointResolver.Candidate,
        protocol: ModelApiProtocol,
        body: String,
        preferredModelId: String?,
        catalogPreferred: String?,
        requestedUrl: String,
        trace: ApiTrace,
    ): DiscoveryResult {
        val parsed = runCatching { JsonCodec.parse(body) }
        val parseError = parsed.exceptionOrNull()
        val root = parsed.getOrNull()?.objectOrNull()
        if (root == null) {
            traceParseFailure(
                trace = trace,
                requestedUrl = requestedUrl,
                body = body,
                fieldPath = "none",
                exception = parseError,
                reason = if (parseError == null) "non-object-json" else "invalid-json",
            )
            return DiscoveryResult.Failed(
                kind = DiscoveryFailureKind.UNSUPPORTED,
                message = "Server is reachable, but AgentX could not detect a supported API.",
                reachable = true,
            )
        }
        val extracted = extractModels(protocol, root)
        if (extracted == null) {
            traceParseFailure(
                trace = trace,
                requestedUrl = requestedUrl,
                body = body,
                fieldPath = "none",
                exception = null,
                reason = "expected-field-missing",
            )
            return DiscoveryResult.Failed(
                kind = DiscoveryFailureKind.UNSUPPORTED,
                message = "Server is reachable, but AgentX could not detect a supported API.",
                reachable = true,
            )
        }

        // The field the parser actually used, and every entry it saw: this is what
        // separates "the API answered in an unexpected shape" from "the list was
        // empty" or "the ids were normalized away".
        trace.stage(
            "PARSE",
            "fieldPath" to extracted.fieldPath,
            "rawModels" to extracted.rawCount,
            "usable" to extracted.accepted.size,
            "dropped" to extracted.dropped.size,
        )
        extracted.dropped.forEach { dropped ->
            trace.stage(
                "MODEL",
                "raw" to (dropped.raw ?: "none"),
                "normalized" to "-",
                "accepted" to false,
                "reason" to dropped.reason,
            )
        }
        extracted.accepted.forEach { entry ->
            trace.stage(
                "MODEL",
                "raw" to entry.raw,
                "normalized" to entry.normalized,
                "accepted" to true,
                "normalizedFromPrefix" to (entry.raw != entry.normalized),
            )
        }

        val models = extracted.accepted.map { it.normalized }
        if (models.isEmpty()) {
            trace.failure(
                "PARSE_FAIL",
                "reason" to "no-models",
                "fieldPath" to extracted.fieldPath,
                "rawModels" to extracted.rawCount,
                "path" to diagnosticPath(requestedUrl),
                "message" to "The model list at ${diagnosticPath(requestedUrl)} contained no models.",
            )
            // Answered, but with nothing usable. Reported as a parsing outcome so it
            // is never mistaken for "no compatible provider", and so a caller cannot
            // present its built-in fallback as if discovery had found these models.
            return DiscoveryResult.Failed(
                kind = DiscoveryFailureKind.MALFORMED,
                message = "The model list at ${diagnosticPath(requestedUrl)} contained no models.",
                reachable = true,
            )
        }

        val unique = models.distinct()
        models.groupingBy { it }.eachCount().filterValues { count -> count > 1 }.keys.forEach { duplicate ->
            trace.stage("FILTER", "id" to duplicate, "reason" to "duplicate")
        }
        // Only meaningful when the selector has to choose: with a single model, or
        // an explicit preference, the utility filter is never reached.
        if (unique.size > 1) {
            unique.filter(::isUtilityModel).forEach { id ->
                trace.stage("FILTER", "id" to id, "reason" to "utility-model", "excluded" to true)
            }
        }

        val selected = selectDiscoveredModel(models, preferredModelId, catalogPreferred)
        trace.stage(
            "SELECT",
            "preferred" to (preferredModelId ?: "-"),
            "catalogPreferred" to (catalogPreferred ?: "-"),
            "candidates" to unique.size,
            "selected" to (selected ?: "none"),
            "reason" to when {
                selected == null -> if (unique.size > 1) "ambiguous" else "no-usable-model"
                preferredModelId != null && selected == normalizeModelId(preferredModelId) -> "preferred"
                catalogPreferred != null && selected == normalizeModelId(catalogPreferred) -> "catalog-preferred"
                unique.size == 1 -> "single"
                else -> "auto"
            },
        )
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

    /**
     * Logs one terminal line for a discovery, so a single operation always ends
     * with either COMPLETE or ERROR regardless of how many candidates were tried.
     */
    private fun traceOutcome(trace: ApiTrace, result: DiscoveryResult) {
        when (result) {
            is DiscoveryResult.Found -> trace.stage(
                "COMPLETE",
                "outcome" to "found",
                "catalog" to result.api.modelIds.size,
                "selected" to (result.api.selectedModelId ?: "none"),
                "fallback" to result.api.catalogFallback,
                "rootUrl" to diagnosticPath(result.api.rootUrl),
                "basePath" to result.api.apiBasePath.ifBlank { "-" },
            )

            is DiscoveryResult.NeedsModelChoice -> trace.stage(
                "COMPLETE",
                "outcome" to "needs-choice",
                "catalog" to result.modelIds.size,
                "selected" to "none",
                "fallback" to false,
            )

            is DiscoveryResult.Failed -> trace.failure(
                "ERROR",
                "kind" to result.kind.name,
                "status" to (result.httpStatus ?: "-"),
                "reachable" to result.reachable,
                "message" to sanitizeForLog(result.message),
            )
        }
    }

    private fun traceParseFailure(
        trace: ApiTrace,
        requestedUrl: String,
        body: String,
        fieldPath: String,
        exception: Throwable?,
        reason: String,
    ) {
        trace.failure(
            "PARSE_FAIL",
            "reason" to reason,
            "path" to diagnosticPath(requestedUrl),
            "fieldPath" to fieldPath,
            "bodyBytes" to body.length,
            "exception" to (exception?.javaClass?.name ?: "-"),
            "message" to sanitizeForLog(exception?.message ?: reason),
            // A preview is only ever attached for a small structured body, redacted
            // and flattened; anything else is reported structurally instead.
            "preview" to (safeResponsePreview(body) ?: "omitted"),
        )
    }

    private fun healthUrl(candidate: EndpointResolver.Candidate, protocol: ModelApiProtocol): String {
        val base = candidate.rootUrl.trimEnd('/')
        return if (protocol.healthIsRelativeToApiBase) {
            base + candidate.apiBasePath + protocol.defaultHealthPath
        } else {
            base + protocol.defaultHealthPath
        }
    }

    /** The model-list path a provider will ask, without a credential or query. */
    private fun modelListDiagnosticPath(spec: KnownProviderSpec, rootUrl: String): String =
        diagnosticPath(spec.modelListUrlFor(rootUrl))

    /**
     * Model ids from a model-list response, kept structured so each entry can be
     * logged with both its raw and normalized form.
     *
     * The OpenAI-compatible `data` array is tried first, then a provider's own
     * `models` array (Gemini's shape, where each entry names the model as
     * `models/<id>`). Ids are normalized so the two forms resolve to one model.
     */
    private fun extractModels(protocol: ModelApiProtocol, root: JsonObject): ExtractedModels? {
        val (fieldPath, items) = when (protocol) {
            ModelApiProtocol.OPENAI_COMPATIBLE -> {
                val data = root.arrayOrNull("data")
                if (data != null) {
                    "data" to data
                } else {
                    val models = root.arrayOrNull("models")
                    if (models != null) "models" to models else return null
                }
            }

            // Gemini's own list, like Ollama, answers with a `models` array.
            ModelApiProtocol.OLLAMA,
            ModelApiProtocol.GEMINI_NATIVE,
            -> {
                val models = root.arrayOrNull("models")
                if (models != null) "models" to models else return null
            }
        }

        val accepted = mutableListOf<ModelEntry>()
        val dropped = mutableListOf<DroppedEntry>()
        items.forEach { item ->
            val obj = item.objectOrNull()
            if (obj == null) {
                dropped += DroppedEntry(null, "not-an-object")
                return@forEach
            }
            val raw = obj.stringOrNull("id") ?: obj.stringOrNull("name") ?: obj.stringOrNull("model")
            if (raw == null) {
                dropped += DroppedEntry(null, "missing-id")
                return@forEach
            }
            val normalized = normalizeModelId(raw)
            if (normalized.isBlank()) {
                dropped += DroppedEntry(raw, "blank-id")
                return@forEach
            }
            accepted += ModelEntry(raw, normalized)
        }
        return ExtractedModels(
            fieldPath = fieldPath,
            rawCount = items.size,
            accepted = accepted,
            dropped = dropped,
        )
    }

    private fun elapsedMillis(startedNanos: Long): Long = (System.nanoTime() - startedNanos) / 1_000_000

    private fun contentTypeOf(headers: Map<String, List<String>>): String =
        headers.firstHeaderValue("Content-Type")?.substringBefore(';')?.trim() ?: "-"

    private fun requestIdOf(headers: Map<String, List<String>>): String =
        headers.firstHeaderValue("x-request-id")
            ?: headers.firstHeaderValue("request-id")
            ?: headers.firstHeaderValue("x-groq-request-id")
            ?: "-"

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

    /** One model-list entry as it arrived, plus the id the app derives from it. */
    private data class ModelEntry(val raw: String, val normalized: String)

    /** An entry that could not become a model id, and why it was dropped. */
    private data class DroppedEntry(val raw: String?, val reason: String)

    /** A parsed model list with everything a diagnostic log needs. */
    private data class ExtractedModels(
        val fieldPath: String,
        val rawCount: Int,
        val accepted: List<ModelEntry>,
        val dropped: List<DroppedEntry>,
    )

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

        /** Label for an endpoint the user typed rather than a known provider. */
        private const val GENERIC_PROVIDER_LABEL: String = "ENDPOINT"
    }
}

/** The credential header a model list uses; a scheme name, never a value. */
private fun authSchemeLabel(auth: ModelListAuth): String = when (auth) {
    ModelListAuth.BEARER -> "bearer"
    ModelListAuth.API_KEY_HEADER -> "api-key-header"
}
