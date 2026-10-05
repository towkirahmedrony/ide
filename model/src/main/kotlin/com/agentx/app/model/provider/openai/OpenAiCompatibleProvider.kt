package com.agentx.app.model.provider.openai

import com.agentx.app.core.logging.ForgeLogger
import com.agentx.app.core.timeout.AgentTimeouts
import com.agentx.app.model.*
import com.agentx.app.model.capability.isLoopbackEndpoint
import com.agentx.app.model.connect.DiscoveryFailureKind
import com.agentx.app.model.connect.diagnosticPath
import com.agentx.app.model.discovery.ModelDiscoveryOutcome
import com.agentx.app.model.discovery.ModelListParsing
import com.agentx.app.model.diagnostics.ApiOperation
import com.agentx.app.model.diagnostics.ApiTrace
import com.agentx.app.model.diagnostics.configuredFlag
import com.agentx.app.model.diagnostics.firstHeaderValue
import com.agentx.app.model.diagnostics.rateLimitHeaderNames
import com.agentx.app.model.diagnostics.safeResponsePreview
import com.agentx.app.model.diagnostics.sanitizeForLog
import com.agentx.app.model.error.ProviderErrorClassifier
import com.agentx.app.model.http.*
import com.agentx.app.model.json.*
import com.agentx.app.model.ratelimit.RetryAfter
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Generic provider for any endpoint that exposes an OpenAI-style
 * `chat/completions` API: hosted APIs, OpenRouter, vLLM, llama.cpp servers, and
 * other compatible runtimes.
 *
 * Nothing about a specific vendor is baked in: the provider id, endpoint, model,
 * and credential all come from [ModelConfig]. The API key is optional, the base
 * URL is fully configurable, and all provider-specific request/response handling
 * lives here rather than in the agent core.
 */
class OpenAiCompatibleProvider(
    override val id: String = DEFAULT_ID,
    private val transport: HttpTransport = UrlConnectionHttpTransport(),
    private val defaultCapabilities: ModelCapabilities = DEFAULT_CAPABILITIES,
    private val chatPath: String = DEFAULT_CHAT_PATH,
    /**
     * Optional structured Developer Log sink for API diagnostics.
     *
     * Null (the default) disables tracing, so existing callers and tests keep
     * exactly the previous behaviour. Prompt text, source code and tool arguments
     * are never written: only the request shape and the response metadata are.
     */
    private val logger: ForgeLogger? = null,
) : ModelProvider {

    override fun capabilities(modelId: String): ModelCapabilities = defaultCapabilities

    /**
     * Enumerates the models the endpoint exposes for [config].
     *
     * `GET <baseUrl>/models` is the OpenAI-compatible list route, which every
     * compatible runtime that offers discovery serves (Groq documents it as
     * `<host>/openai/v1/models`, which is exactly the configured base URL plus
     * `/models`). The credential, when there is one, is the same bearer token the
     * chat call uses; a local runtime that needs none is asked without one.
     *
     * A runtime that has no list route answers 404/405. That is reported as
     * [ModelDiscoveryOutcome.Unavailable] rather than as a failure or an empty
     * list, so a caller keeps the manually configured models and never invents a
     * catalog for an endpoint that published none.
     *
     * Capabilities are not inferred from a name or an id: the list is normalized
     * into identity and whatever metadata the endpoint actually reported.
     */
    override suspend fun discoverModels(config: ModelConfig): ModelDiscoveryOutcome {
        val trace = ApiTrace.create(logger, id, ApiOperation.DISCOVERY)
        val base = config.baseUrl.trim()
        if (base.isBlank()) {
            return ModelDiscoveryOutcome.Unavailable(
                reason = ModelDiscoveryOutcome.REASON_NOT_CONNECTED,
                message = "The endpoint has no base URL to list models from.",
            )
        }
        val url = modelsUrl(base)
        val headers = LinkedHashMap<String, String>()
        headers["Accept"] = "application/json"
        config.apiKey?.takeIf { it.isNotBlank() }?.let { key -> headers["Authorization"] = "Bearer $key" }
        config.headers.forEach { (name, value) -> headers[name] = value }

        trace.stage(
            "START",
            "operation" to "model-discovery",
            "provider" to id,
            "path" to diagnosticPath(url),
            "hasApiKey" to configuredFlag(!config.apiKey.isNullOrBlank()),
        )
        val started = System.nanoTime()
        val response = try {
            transport.execute(
                HttpRequestSpec(
                    method = "GET",
                    url = url,
                    headers = headers,
                    connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS,
                    readTimeoutMillis = READ_TIMEOUT_MILLIS,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val kind = discoveryKind(error)
            trace.failure(
                "ERROR",
                "stage" to "transport",
                "path" to diagnosticPath(url),
                "kind" to kind.name,
                "exception" to error.javaClass.name,
                "message" to sanitizeForLog(error.message ?: error.javaClass.name),
            )
            return ModelDiscoveryOutcome.Failed(kind = kind, message = discoveryMessage(error))
        }
        trace.stage(
            "RESPONSE",
            "status" to response.statusCode,
            "elapsedMs" to elapsedMillis(started),
            "bodyBytes" to response.body.length,
            "success" to response.isSuccess,
        )

        if (response.statusCode == 404 || response.statusCode == 405) {
            trace.warn("UNAVAILABLE", "reason" to "no-model-list-route", "status" to response.statusCode)
            return ModelDiscoveryOutcome.Unavailable(
                reason = ModelDiscoveryOutcome.REASON_NO_MODEL_LIST,
                message = "The endpoint does not expose a model list at ${diagnosticPath(url)}.",
            )
        }
        if (!response.isSuccess) {
            val kind = discoveryHttpKind(response.statusCode)
            trace.failure(
                "ERROR",
                "stage" to "http",
                "status" to response.statusCode,
                "kind" to kind.name,
                "path" to diagnosticPath(url),
            )
            return ModelDiscoveryOutcome.Failed(
                kind = kind,
                message = "The model list endpoint returned HTTP ${response.statusCode}.",
                httpStatus = response.statusCode,
            )
        }

        val parsed = ModelListParsing.parse(response.body, id)
        if (parsed == null) {
            trace.failure("PARSE_FAIL", "reason" to "not-a-model-list", "path" to diagnosticPath(url))
            return ModelDiscoveryOutcome.Failed(
                kind = DiscoveryFailureKind.MALFORMED,
                message = "The model list response could not be parsed.",
            )
        }
        trace.stage(
            "COMPLETE",
            "outcome" to "discovered",
            "received" to parsed.reportedCount,
            "accepted" to parsed.models.size,
            "rejected" to parsed.rejected.size,
        )
        // A loopback endpoint is this device or the local network, so every model it
        // lists runs locally: the flag travels with the descriptor instead of being
        // guessed from a model name later.
        val local = isLoopbackEndpoint(base)
        return ModelDiscoveryOutcome.Discovered(
            models = if (local) parsed.models.map { it.copy(local = true) } else parsed.models,
            reportedCount = parsed.reportedCount,
            rejected = parsed.rejected,
            httpStatus = response.statusCode,
        )
    }

    /** The OpenAI-compatible model-list route, relative to the configured base URL. */
    private fun modelsUrl(baseUrl: String): String = baseUrl.trimEnd('/') + MODELS_PATH

    private fun discoveryHttpKind(status: Int): DiscoveryFailureKind = when (status) {
        401, 403 -> DiscoveryFailureKind.AUTHENTICATION_REQUIRED
        408 -> DiscoveryFailureKind.TIMEOUT
        429 -> DiscoveryFailureKind.RATE_LIMITED
        in 500..599 -> DiscoveryFailureKind.SERVER_ERROR
        else -> DiscoveryFailureKind.UNSUPPORTED
    }

    private fun discoveryKind(error: Throwable): DiscoveryFailureKind = when (val root = unwrap(error)) {
        is SocketTimeoutException, is java.io.InterruptedIOException -> DiscoveryFailureKind.TIMEOUT
        is UnknownHostException, is ConnectException, is NoRouteToHostException,
        is PortUnreachableException, is SSLException,
        -> DiscoveryFailureKind.UNREACHABLE
        is IOException -> DiscoveryFailureKind.UNREACHABLE
        else -> DiscoveryFailureKind.UNKNOWN
    }

    private fun discoveryMessage(error: Throwable): String = when (unwrap(error)) {
        is SocketTimeoutException, is java.io.InterruptedIOException ->
            "The model list endpoint did not respond in time."
        is UnknownHostException -> "The model endpoint host could not be resolved."
        else -> "Could not reach the model list: ${sanitizeForLog(unwrap(error).message ?: "network error")}"
    }

    override suspend fun complete(request: ModelRequest): ModelResponse {
        val trace = ApiTrace.create(logger, id, ApiOperation.COMPLETION)
        val body = buildRequestBody(request, stream = false)
        traceRequest(trace, request, body, stream = false, purpose = "normal-completion")
        val started = System.nanoTime()
        val response = try {
            send(request, body)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            traceTransportFailure(trace, request, error, started)
            throw error
        }
        traceResponse(trace, request, response, started, bytes = response.body.length)
        if (!response.isSuccess) {
            val failure = httpError(response)
            traceHttpFailure(trace, request, failure, started)
            throw failure
        }
        if (response.body.isBlank()) {
            traceParseFailure(
                trace = trace,
                request = request,
                response = response,
                error = invalidResponse(EMPTY_BODY_MESSAGE),
            )
            throw invalidResponse(EMPTY_BODY_MESSAGE)
        }
        val parsed = try {
            parseCompletion(parseJson(response.body), request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ModelProviderError) {
            traceParseFailure(trace, request, response, error)
            throw error
        }
        trace.stage(
            "COMPLETE",
            "provider" to id,
            "model" to parsed.model,
            "stream" to false,
            "finishReason" to (parsed.finishReason?.name ?: "-"),
            "contentChars" to parsed.content.length,
            "toolCalls" to parsed.toolCalls.size,
            "elapsedMs" to elapsedMillis(started),
        )
        return parsed
    }

    override suspend fun stream(
        request: ModelRequest,
        onEvent: (ModelStreamEvent) -> Unit,
    ): ModelResponse {
        val accumulator = StreamAccumulator()
        val rawBody = StringBuilder()
        val trace = ApiTrace.create(logger, id, ApiOperation.STREAM)
        onEvent(ModelStreamEvent.Started(request.model, id))
        val body = buildRequestBody(request, stream = true)
        traceRequest(trace, request, body, stream = true, purpose = "streaming-completion")
        val started = System.nanoTime()
        val response = try {
            sendStreaming(request, body) { line ->
                if (rawBody.isNotEmpty()) rawBody.append('\n')
                rawBody.append(line)
                handleStreamLine(line, accumulator, onEvent)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            traceTransportFailure(trace, request, error, started)
            throw error
        }
        // A streamed success carries its payload on the wire instead of in the
        // response body, so the accumulated SSE size is what represents it.
        traceResponse(trace, request, response, started, bytes = rawBody.length)
        if (!response.isSuccess) {
            val failure = httpError(response)
            traceHttpFailure(trace, request, failure, started)
            throw failure
        }
        if (accumulator.content.isEmpty() && accumulator.toolCalls().isEmpty() && !accumulator.done) {
            applyNonStreamFallback(accumulator, rawBody.toString(), request, onEvent)
        }
        onEvent(ModelStreamEvent.Completed(accumulator.finishReason, accumulator.usage))
        val normalized = ContentToolCallParser.normalize(
            ModelResponse(
                model = accumulator.model ?: request.model,
                providerId = id,
                content = accumulator.content.toString(),
                toolCalls = accumulator.toolCalls(),
                finishReason = accumulator.finishReason,
                usage = accumulator.usage,
            ),
        )
        trace.stage(
            "COMPLETE",
            "provider" to id,
            "model" to normalized.model,
            "stream" to true,
            "finishReason" to (normalized.finishReason?.name ?: "-"),
            "contentChars" to normalized.content.length,
            "toolCalls" to normalized.toolCalls.size,
            "sseBytes" to rawBody.length,
            "elapsedMs" to elapsedMillis(started),
        )
        return normalized
    }

    // --- diagnostics --------------------------------------------------------

    /**
     * Logs the outbound shape of a request, never its content.
     *
     * Message text, source code and tool arguments stay out of the log, so the
     * Developer Log cannot become a leak of the user's project; the counts,
     * model, endpoint and generation parameters are enough to tell a malformed
     * request from a rejected one.
     */
    private fun traceRequest(
        trace: ApiTrace,
        request: ModelRequest,
        body: String,
        stream: Boolean,
        purpose: String,
    ) {
        val generation = request.effectiveGeneration
        trace.stage(
            "REQUEST",
            "method" to "POST",
            "path" to diagnosticPath(endpoint(request.config)),
            "purpose" to purpose,
            "provider" to id,
            "model" to request.model,
            "stream" to stream,
            "bodyBytes" to body.length,
            "messageCount" to request.messages.size,
            "toolCount" to request.tools.size,
            "toolCalling" to configuredFlag(request.tools.isNotEmpty()),
            "hasApiKey" to configuredFlag(!request.config.apiKey.isNullOrBlank()),
            "temperature" to (generation.temperature ?: "-"),
            "maxTokens" to (generation.maxOutputTokens ?: "-"),
            "topP" to (generation.topP ?: "-"),
            "customHeaderNames" to if (request.config.headers.isEmpty()) {
                "none"
            } else {
                request.config.headers.keys.joinToString(",")
            },
        )
    }

    private fun traceResponse(
        trace: ApiTrace,
        request: ModelRequest,
        response: HttpResponseSpec,
        startedNanos: Long,
        bytes: Int,
    ) {
        // Status first: it is the single most useful field when scanning the log.
        trace.stage(
            "RESPONSE",
            "status" to response.statusCode,
            "elapsedMs" to elapsedMillis(startedNanos),
            "bodyBytes" to bytes,
            "contentType" to (response.headers.firstHeaderValue("Content-Type")?.substringBefore(';')?.trim() ?: "-"),
            "requestId" to (
                response.headers.firstHeaderValue("x-request-id")
                    ?: response.headers.firstHeaderValue("request-id")
                    ?: "-"
                ),
            "retryAfter" to (response.headers.firstHeaderValue("Retry-After") ?: "-"),
            "rateLimitHeaders" to (
                response.headers.rateLimitHeaderNames().joinToString(",").ifBlank { "-" }
                ),
            "success" to response.isSuccess,
            "provider" to id,
            "model" to request.model,
        )
    }

    private fun traceHttpFailure(
        trace: ApiTrace,
        request: ModelRequest,
        error: ModelProviderError,
        startedNanos: Long,
    ) {
        trace.failure(
            "ERROR",
            "stage" to "http",
            "provider" to id,
            "model" to request.model,
            "status" to (error.httpStatus ?: "-"),
            "kind" to error.code.name,
            "retryable" to error.retryable,
            "retryAfterMs" to (error.retryAfterMillis ?: "-"),
            "providerErrorType" to (error.providerErrorType ?: "-"),
            "message" to sanitizeForLog(error.message ?: ""),
            "elapsedMs" to elapsedMillis(startedNanos),
        )
    }

    private fun traceTransportFailure(
        trace: ApiTrace,
        request: ModelRequest,
        error: Throwable,
        startedNanos: Long,
    ) {
        val mapped = error as? ModelProviderError
        trace.failure(
            "ERROR",
            "stage" to "transport",
            "provider" to id,
            "model" to request.model,
            "kind" to (mapped?.code?.name ?: ModelProviderErrorCode.UNKNOWN.name),
            "exception" to error.javaClass.name,
            "message" to sanitizeForLog(error.message ?: error.javaClass.name),
            "retryable" to (mapped?.retryable ?: false),
            "elapsedMs" to elapsedMillis(startedNanos),
        )
    }

    private fun traceParseFailure(
        trace: ApiTrace,
        request: ModelRequest,
        response: HttpResponseSpec,
        error: ModelProviderError,
    ) {
        trace.failure(
            "PARSE_FAIL",
            "provider" to id,
            "model" to request.model,
            "status" to response.statusCode,
            "bodyBytes" to response.body.length,
            "kind" to error.code.name,
            "message" to sanitizeForLog(error.message ?: ""),
            // Only a small structured body is previewed, redacted and flattened;
            // anything else is reported structurally instead.
            "preview" to (safeResponsePreview(response.body) ?: "omitted"),
        )
    }

    private fun elapsedMillis(startedNanos: Long): Long = (System.nanoTime() - startedNanos) / 1_000_000

    // --- transport ---------------------------------------------------------

    private suspend fun send(request: ModelRequest, body: String): HttpResponseSpec =
        try {
            transport.execute(httpRequest(request, body, stream = false))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            throw transportError(error)
        }

    private suspend fun sendStreaming(
        request: ModelRequest,
        body: String,
        onLine: (String) -> Unit,
    ): HttpResponseSpec = try {
        transport.executeStreaming(httpRequest(request, body, stream = true), onLine)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        throw transportError(error)
    }

    private fun httpRequest(request: ModelRequest, body: String, stream: Boolean): HttpRequestSpec {
        val headers = LinkedHashMap<String, String>()
        headers["Content-Type"] = "application/json"
        headers["Accept"] = if (stream) {
            "text/event-stream, application/json"
        } else {
            "application/json"
        }
        request.config.apiKey?.takeIf { it.isNotBlank() }?.let { key ->
            headers["Authorization"] = "Bearer $key"
        }
        request.config.headers.forEach { (name, value) -> headers[name] = value }
        val timeout = request.config.timeoutMillis?.takeIf { it > 0 }?.toInt()
        return HttpRequestSpec(
            method = "POST",
            url = endpoint(request.config),
            headers = headers,
            body = body,
            connectTimeoutMillis = timeout ?: CONNECT_TIMEOUT_MILLIS,
            readTimeoutMillis = timeout ?: READ_TIMEOUT_MILLIS,
        )
    }

    /** Builds the chat URL from the configured base URL; the path is overridable. */
    private fun endpoint(config: ModelConfig): String = config.baseUrl.trimEnd('/') + chatPath

    // --- error normalization ----------------------------------------------

    private fun transportError(error: Throwable): ModelProviderError {
        val root = unwrap(error)
        return when {
            root is ModelProviderError -> root
            root is SocketTimeoutException || root is java.io.InterruptedIOException -> timeoutError(root)
            root.javaClass.name.endsWith("NetworkOnMainThreadException") -> ModelProviderError(
                code = ModelProviderErrorCode.CONNECTION_FAILED,
                message = "Model request was blocked because network I/O ran on the UI thread",
                providerId = id,
                retryable = true,
                cause = error,
            )
            root is UnknownHostException -> ModelProviderError(
                code = ModelProviderErrorCode.CONNECTION_FAILED,
                message = "Could not resolve the model endpoint host. The Colab tunnel URL may have expired.",
                providerId = id,
                retryable = true,
                cause = error,
            )
            root is ConnectException || root is NoRouteToHostException || root is PortUnreachableException ->
                connectionFailed(error)
            root is SSLException -> ModelProviderError(
                code = ModelProviderErrorCode.CONNECTION_FAILED,
                message = "Secure connection to the model endpoint failed",
                providerId = id,
                retryable = true,
                cause = error,
            )
            root is IOException && isConnectionRefused(root) -> connectionFailed(error)
            root is IOException -> ModelProviderError(
                code = ModelProviderErrorCode.NETWORK_ERROR,
                message = "Network error while contacting the model endpoint",
                providerId = id,
                retryable = true,
                cause = error,
            )
            else -> ModelProviderError(
                code = ModelProviderErrorCode.UNKNOWN,
                message = root.message ?: "Unexpected model provider error",
                providerId = id,
                cause = error,
            )
        }
    }

    private fun unwrap(error: Throwable): Throwable {
        var current = error
        val seen = HashSet<Throwable>()
        while (current.cause != null && current.cause !== current && seen.add(current)) {
            val cause = current.cause ?: break
            if (cause is ConnectException ||
                cause is SocketTimeoutException ||
                cause is UnknownHostException ||
                cause is SSLException
            ) {
                return cause
            }
            current = cause
        }
        return error
    }

    private fun isConnectionRefused(error: Throwable): Boolean {
        val message = error.message.orEmpty().lowercase()
        return message.contains("connection refused") ||
            message.contains("failed to connect") ||
            message.contains("econnrefused")
    }

    private fun connectionFailed(error: Throwable): ModelProviderError = ModelProviderError(
        code = ModelProviderErrorCode.CONNECTION_FAILED,
        message = "Could not connect to the model endpoint. The Colab runtime may be stopped.",
        providerId = id,
        retryable = true,
        cause = error,
    )

    private fun httpError(response: HttpResponseSpec): ModelProviderError {
        val status = response.statusCode
        val info = extractErrorInfo(response.body)
        // One shared rule for every provider: the status and the endpoint's own
        // error type decide the category, so 401/403/404/408/429/5xx stop collapsing
        // into a single "provider error".
        val code = ProviderErrorClassifier.forHttpStatus(status, info.type, info.message)
        return ModelProviderError(
            code = code,
            message = info.message ?: "Model endpoint returned HTTP $status",
            providerId = id,
            httpStatus = status,
            providerErrorType = info.type,
            retryable = ProviderErrorClassifier.isTransient(code, status),
            retryAfterMillis = if (status == 429) RetryAfter.parseMillis(response.headers) else null,
        )
    }

    private fun extractErrorInfo(body: String): ProviderErrorInfo {
        val root = runCatching { JsonCodec.parse(body).objectOrNull() }.getOrNull()
            ?: return ProviderErrorInfo(null, null)
        val error = root.objectOrNull("error")
        if (error != null) {
            return ProviderErrorInfo(
                message = error.stringOrNull("message"),
                type = error.stringOrNull("type") ?: error.stringOrNull("code"),
            )
        }
        return ProviderErrorInfo(root.stringOrNull("message"), root.stringOrNull("type"))
    }

    private fun timeoutError(error: Throwable): ModelProviderError = ModelProviderError(
        code = ModelProviderErrorCode.TIMEOUT,
        message = "The model request timed out. Check that the Colab runtime and tunnel are still running.",
        providerId = id,
        retryable = true,
        cause = error,
    )

    private fun invalidResponse(message: String): ModelProviderError =
        ModelProviderError(ModelProviderErrorCode.INVALID_RESPONSE, message, id)

    private fun parseJson(body: String): JsonObject {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) throw invalidResponse("Model endpoint returned an empty response")
        val parsed = runCatching { JsonCodec.parse(trimmed) }.getOrElse { error ->
            throw invalidResponse("Model endpoint returned invalid JSON: ${error.message ?: "parse error"}")
        }
        return parsed.objectOrNull()
            ?: throw invalidResponse("Model endpoint returned a non-object JSON response")
    }

    // --- request building --------------------------------------------------

    private fun buildRequestBody(request: ModelRequest, stream: Boolean): String {
        val fields = LinkedHashMap<String, JsonValue>()
        fields["model"] = Json.of(request.model)
        fields["messages"] = Json.array(request.messages.map { serializeMessage(it) })
        fields["stream"] = Json.of(stream)

        val generation = request.effectiveGeneration
        generation.temperature?.let { fields["temperature"] = Json.of(it) }
        generation.maxOutputTokens?.let { fields["max_tokens"] = Json.of(it) }
        generation.topP?.let { fields["top_p"] = Json.of(it) }
        if (generation.stop.isNotEmpty()) fields["stop"] = Json.array(generation.stop.map { Json.of(it) })
        generation.extra.forEach { (key, value) -> fields[key] = value }

        if (request.tools.isNotEmpty()) fields["tools"] = Json.array(request.tools.map { serializeTool(it) })
        request.toolChoice?.let { fields["tool_choice"] = serializeToolChoice(it) }

        return JsonCodec.encodeObject(fields)
    }

    private fun serializeMessage(message: ModelMessage): JsonValue {
        val fields = LinkedHashMap<String, JsonValue>()
        fields["role"] = Json.of(message.role.wireName)
        fields["content"] = Json.of(message.content)
        message.name?.let { fields["name"] = Json.of(it) }
        message.toolCallId?.let { fields["tool_call_id"] = Json.of(it) }
        if (message.toolCalls.isNotEmpty()) {
            fields["tool_calls"] = Json.array(message.toolCalls.map { serializeToolCall(it) })
        }
        return JsonValue.Obj(fields)
    }

    private fun serializeToolCall(call: ModelToolCall): JsonValue = Json.obj(
        "id" to Json.of(call.id),
        "type" to Json.of("function"),
        "function" to Json.obj(
            "name" to Json.of(call.name),
            "arguments" to Json.of(JsonCodec.encode(JsonValue.Obj(call.arguments))),
        ),
    )

    private fun serializeTool(spec: ModelToolSpec): JsonValue {
        val properties = spec.parameters.associate { parameter ->
            parameter.name to Json.obj(
                "type" to Json.of(parameter.type.jsonName()),
                "description" to Json.of(parameter.description),
            )
        }
        val required = spec.parameters.filter { it.required }.map { Json.of(it.name) }
        return Json.obj(
            "type" to Json.of("function"),
            "function" to Json.obj(
                "name" to Json.of(spec.name),
                "description" to Json.of(spec.description),
                "parameters" to Json.obj(
                    "type" to Json.of("object"),
                    "properties" to JsonValue.Obj(properties),
                    "required" to Json.array(required),
                ),
            ),
        )
    }

    private fun serializeToolChoice(choice: ModelToolChoice): JsonValue = when (choice) {
        ModelToolChoice.Auto -> Json.of("auto")
        ModelToolChoice.None -> Json.of("none")
        ModelToolChoice.Required -> Json.of("required")
        is ModelToolChoice.Specific -> Json.obj(
            "type" to Json.of("function"),
            "function" to Json.obj("name" to Json.of(choice.name)),
        )
    }

    // --- response parsing --------------------------------------------------

    private fun parseCompletion(json: JsonObject, request: ModelRequest): ModelResponse {
        val choices = json.arrayOrNull("choices") ?: throw invalidResponse("Response did not contain 'choices'")
        val choice = choices.firstOrNull()?.objectOrNull() ?: throw invalidResponse("Response contained no choices")
        val message = choice.objectOrNull("message") ?: throw invalidResponse("Choice did not contain a 'message'")
        val content = extractMessageContent(message)
        val toolCalls = parseToolCalls(message.arrayOrNull("tool_calls"))
            .ifEmpty { parseLegacyFunctionCall(message) }
        val finishReason = choice.stringOrNull("finish_reason")?.let { toFinishReason(it) }
        val usage = json.objectOrNull("usage")?.let { parseUsage(it) }
        val model = json.stringOrNull("model") ?: request.model
        return ContentToolCallParser.normalize(
            ModelResponse(
                model = model,
                providerId = id,
                content = content,
                toolCalls = toolCalls,
                finishReason = finishReason,
                usage = usage,
            ),
        )
    }

    private fun parseToolCalls(items: List<JsonValue>?): List<ModelToolCall> {
        if (items == null) return emptyList()
        return items.mapNotNull { item ->
            val call = item.objectOrNull() ?: return@mapNotNull null
            val function = call.objectOrNull("function")
            val name = function?.stringOrNull("name") ?: call.stringOrNull("name") ?: return@mapNotNull null
            ModelToolCall(
                id = call.stringOrNull("id") ?: "",
                name = name,
                arguments = parseArguments(function?.get("arguments") ?: call["arguments"]),
            )
        }
    }

    /** Older OpenAI `function_call` payloads that predate `tool_calls`. */
    private fun parseLegacyFunctionCall(message: JsonObject): List<ModelToolCall> {
        val function = message.objectOrNull("function_call") ?: return emptyList()
        val name = function.stringOrNull("name") ?: return emptyList()
        return listOf(
            ModelToolCall(
                id = message.stringOrNull("id").orEmpty(),
                name = name,
                arguments = parseArguments(function["arguments"]),
            ),
        )
    }

    private fun parseArguments(raw: JsonValue?): JsonObject = when (raw) {
        null, is JsonValue.Null -> emptyMap()
        is JsonValue.Obj -> raw.fields
        is JsonValue.Str -> {
            if (raw.value.isBlank()) emptyMap()
            else runCatching { JsonCodec.parse(raw.value).objectOrNull() }.getOrNull() ?: emptyMap()
        }
        else -> emptyMap()
    }

    private fun parseUsage(json: JsonObject): ModelUsage = ModelUsage(
        promptTokens = json.numberOrNull("prompt_tokens")?.toInt(),
        completionTokens = json.numberOrNull("completion_tokens")?.toInt(),
        totalTokens = json.numberOrNull("total_tokens")?.toInt(),
    )

    private fun toFinishReason(raw: String): ModelFinishReason = when (raw) {
        "stop" -> ModelFinishReason.STOP
        "length" -> ModelFinishReason.LENGTH
        "tool_calls", "function_call" -> ModelFinishReason.TOOL_CALLS
        "content_filter" -> ModelFinishReason.CONTENT_FILTER
        "error" -> ModelFinishReason.ERROR
        else -> ModelFinishReason.UNKNOWN
    }

    private fun handleStreamLine(
        line: String,
        accumulator: StreamAccumulator,
        onEvent: (ModelStreamEvent) -> Unit,
    ) {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith(":")) return
        if (!trimmed.startsWith("data:")) return
        val payload = trimmed.removePrefix("data:").trim()
        if (payload == "[DONE]") {
            accumulator.done = true
            return
        }
        val json = runCatching { JsonCodec.parse(payload).objectOrNull() }.getOrNull() ?: return
        json.stringOrNull("model")?.let { accumulator.model = it }
        json.objectOrNull("usage")?.let { usage ->
            val parsed = parseUsage(usage)
            accumulator.usage = parsed
            onEvent(ModelStreamEvent.UsageReported(parsed))
        }
        val choices = json.arrayOrNull("choices") ?: return
        val choice = choices.firstOrNull()?.objectOrNull() ?: return
        choice.stringOrNull("finish_reason")?.let { accumulator.finishReason = toFinishReason(it) }
        val delta = choice.objectOrNull("delta") ?: return
        extractDeltaContent(delta)?.let { text ->
            if (text.isNotEmpty()) {
                accumulator.content.append(text)
                onEvent(ModelStreamEvent.TextDelta(text))
            }
        }
        delta.arrayOrNull("tool_calls")?.forEach { item ->
            val call = item.objectOrNull() ?: return@forEach
            val index = call.numberOrNull("index")?.toInt() ?: 0
            val callId = call.stringOrNull("id")
            val function = call.objectOrNull("function")
            val name = function?.stringOrNull("name")
            val argumentsDelta = function?.stringOrNull("arguments")
            accumulator.appendToolCall(index, callId, name, argumentsDelta)
            onEvent(ModelStreamEvent.ToolCallDelta(index, callId, name, argumentsDelta))
        }
    }

    /**
     * Some OpenAI-compatible Colab servers ignore `stream: true` and return a
     * normal chat-completions JSON body. Treat that as a completed response
     * instead of crashing or showing an empty bubble.
     */
    private fun applyNonStreamFallback(
        accumulator: StreamAccumulator,
        rawBody: String,
        request: ModelRequest,
        onEvent: (ModelStreamEvent) -> Unit,
    ) {
        val trimmed = rawBody.trim()
        if (trimmed.isEmpty() || !trimmed.startsWith("{")) return
        val parsed = runCatching { parseCompletion(parseJson(trimmed), request) }.getOrNull() ?: return
        accumulator.model = parsed.model
        accumulator.finishReason = parsed.finishReason
        accumulator.usage = parsed.usage
        if (parsed.content.isNotEmpty()) {
            accumulator.content.append(parsed.content)
            onEvent(ModelStreamEvent.TextDelta(parsed.content))
        }
        parsed.toolCalls.forEachIndexed { index, call ->
            val encoded = JsonCodec.encode(JsonValue.Obj(call.arguments))
            accumulator.appendToolCall(index, call.id, call.name, encoded)
            onEvent(ModelStreamEvent.ToolCallDelta(index, call.id, call.name, encoded))
        }
    }

    private class StreamAccumulator {
        val content = StringBuilder()
        var model: String? = null
        var finishReason: ModelFinishReason? = null
        var usage: ModelUsage? = null
        var done: Boolean = false

        private val toolCalls = LinkedHashMap<Int, ToolCallBuilder>()

        fun appendToolCall(index: Int, id: String?, name: String?, argumentsDelta: String?) {
            val builder = toolCalls.getOrPut(index) { ToolCallBuilder() }
            if (id != null) builder.id = id
            if (name != null) builder.name = name
            if (argumentsDelta != null) builder.arguments.append(argumentsDelta)
        }

        fun toolCalls(): List<ModelToolCall> = toolCalls.entries
            .sortedBy { it.key }
            .mapNotNull { (_, builder) -> builder.toToolCall() }
    }

    private class ToolCallBuilder {
        var id: String? = null
        var name: String? = null
        val arguments = StringBuilder()

        fun toToolCall(): ModelToolCall? {
            val callName = name ?: return null
            val parsed: JsonObject = if (arguments.isBlank()) {
                emptyMap()
            } else {
                runCatching { JsonCodec.parse(arguments.toString()).objectOrNull() }.getOrNull() ?: emptyMap()
            }
            return ModelToolCall(id = id ?: "", name = callName, arguments = parsed)
        }
    }

    /**
     * OpenAI-compatible servers (vLLM, llama.cpp, some Colab notebooks) may
     * return `content` as a string or as a list of text parts.
     */
    private fun extractMessageContent(message: JsonObject): String {
        message["content"]?.let { return jsonTextOrToolCall(it) }
        message["text"]?.let { return jsonTextOrToolCall(it) }
        return ""
    }

    /**
     * Some servers put a tool-call object in `content` instead of a string.
     * Encode it so [ContentToolCallParser] can recover the call.
     */
    private fun jsonTextOrToolCall(value: JsonValue): String {
        if (value is JsonValue.Obj) {
            val text = value.fields.stringOrNull("text") ?: value.fields.stringOrNull("content")
            if (!text.isNullOrEmpty()) return text
            val encoded = JsonCodec.encode(value)
            if (ContentToolCallParser.parse(encoded).isNotEmpty()) return encoded
            return ""
        }
        return jsonText(value)
    }

    private fun extractDeltaContent(delta: JsonObject): String? {
        val value = delta["content"] ?: delta["text"] ?: return null
        val text = jsonText(value)
        return text.takeIf { it.isNotEmpty() }
    }

    private fun jsonText(value: JsonValue): String = when (value) {
        is JsonValue.Str -> value.value
        is JsonValue.Num -> value.value.toString()
        is JsonValue.Bool -> value.value.toString()
        is JsonValue.Null -> ""
        is JsonValue.Arr -> value.items.joinToString("") { item ->
            val obj = item.objectOrNull()
            when {
                obj != null -> obj.stringOrNull("text") ?: obj.stringOrNull("content").orEmpty()
                else -> jsonText(item)
            }
        }
        is JsonValue.Obj -> value.fields.stringOrNull("text")
            ?: value.fields.stringOrNull("content").orEmpty()
    }

    private class ProviderErrorInfo(val message: String?, val type: String?)

    companion object {
        const val DEFAULT_ID: String = "openai-compatible"
        const val DEFAULT_CHAT_PATH: String = "/chat/completions"
        const val CONNECT_TIMEOUT_MILLIS: Int = 15_000

        /**
         * The OpenAI-compatible model-list route, relative to the configured base
         * URL. Groq's documented `GET /openai/v1/models` is exactly this path on a
         * base URL that already ends in `/openai/v1`.
         */
        const val MODELS_PATH: String = "/models"

        private const val EMPTY_BODY_MESSAGE: String = "Model endpoint returned an empty response"

        /**
         * One model response, streamed or not, follows the central model-request
         * budget instead of an arbitrary two minutes. `ModelConfig.timeoutMillis`
         * still overrides it per model.
         */
        val READ_TIMEOUT_MILLIS: Int = AgentTimeouts.MODEL_REQUEST_MILLIS.toInt()

        val DEFAULT_CAPABILITIES: ModelCapabilities = ModelCapabilities(
            streaming = true,
            toolCalling = true,
            vision = false,
            structuredOutput = true,
            systemMessages = true,
        )
    }
}

private fun ModelToolParameterType.jsonName(): String = when (this) {
    ModelToolParameterType.STRING -> "string"
    ModelToolParameterType.NUMBER -> "number"
    ModelToolParameterType.BOOLEAN -> "boolean"
    ModelToolParameterType.OBJECT -> "object"
    ModelToolParameterType.ARRAY -> "array"
    ModelToolParameterType.ANY -> "object"
}
