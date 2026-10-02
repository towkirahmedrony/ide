package com.agentx.app.model.provider.gemini

import com.agentx.app.core.logging.ForgeLogger
import com.agentx.app.core.timeout.AgentTimeouts
import com.agentx.app.model.ContentToolCallParser
import com.agentx.app.model.ModelCapabilities
import com.agentx.app.model.ModelConfig
import com.agentx.app.model.ModelFinishReason
import com.agentx.app.model.ModelMessage
import com.agentx.app.model.ModelProvider
import com.agentx.app.model.ModelProviderError
import com.agentx.app.model.ModelProviderErrorCode
import com.agentx.app.model.ModelRequest
import com.agentx.app.model.ModelResponse
import com.agentx.app.model.ModelRole
import com.agentx.app.model.ModelStreamEvent
import com.agentx.app.model.ModelToolCall
import com.agentx.app.model.ModelToolChoice
import com.agentx.app.model.ModelToolParameterType
import com.agentx.app.model.ModelToolSpec
import com.agentx.app.model.ModelUsage
import com.agentx.app.model.connect.diagnosticPath
import com.agentx.app.model.diagnostics.ApiOperation
import com.agentx.app.model.diagnostics.ApiTrace
import com.agentx.app.model.diagnostics.configuredFlag
import com.agentx.app.model.diagnostics.sanitizeForLog
import com.agentx.app.model.http.HttpRequestSpec
import com.agentx.app.model.http.HttpResponseSpec
import com.agentx.app.model.http.HttpTransport
import com.agentx.app.model.http.UrlConnectionHttpTransport
import com.agentx.app.model.ratelimit.RetryAfter
import com.agentx.app.model.json.Json
import com.agentx.app.model.json.JsonCodec
import com.agentx.app.model.json.JsonObject
import com.agentx.app.model.json.JsonValue
import com.agentx.app.model.json.arrayOrNull
import com.agentx.app.model.json.numberOrNull
import com.agentx.app.model.json.objectOrNull
import com.agentx.app.model.json.stringOrNull
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Gemini's own API, spoken natively.
 *
 * Gemini is not an OpenAI-compatible provider: the model travels in the path
 * (`POST /v1beta/models/<model>:generateContent`), the key travels in the
 * `x-goog-api-key` header, the prompt is a `contents` array, the system
 * instruction is a separate field, tools are `functionDeclarations`, and the
 * answer arrives as `candidates[].content.parts[]`. Sending an OpenAI
 * `/chat/completions` request to the compatible surface instead answers 404, so
 * this provider exists rather than pointing the OpenAI-compatible one at Gemini.
 *
 * The provider receives only [ModelConfig], so an OpenAI-compatible endpoint or
 * Groq is untouched: those keep their own provider and their own paths.
 */
class GeminiModelProvider(
    override val id: String = DEFAULT_ID,
    private val transport: HttpTransport = UrlConnectionHttpTransport(),
    private val defaultCapabilities: ModelCapabilities = DEFAULT_CAPABILITIES,
    /** Structured Developer Log sink; null disables tracing. */
    private val logger: ForgeLogger? = null,
) : ModelProvider {

    override fun capabilities(modelId: String): ModelCapabilities = defaultCapabilities

    override suspend fun complete(request: ModelRequest): ModelResponse {
        val trace = ApiTrace.create(logger, id, ApiOperation.COMPLETION)
        val url = endpoint(request)
        val body = buildRequestBody(request)
        traceRequest(trace, request, url, body, stream = false)
        val started = System.nanoTime()
        val response = try {
            send(request, url, body)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            trace.failure(
                "ERROR",
                "stage" to "transport",
                "provider" to id,
                "model" to request.model,
                "path" to diagnosticPath(url),
                "message" to sanitizeForLog(error.message ?: error.javaClass.simpleName),
            )
            throw error
        }
        traceResponse(trace, request, url, response, started)
        if (!response.isSuccess) {
            val failure = httpError(response)
            trace.failure(
                "ERROR",
                "stage" to "http",
                "provider" to id,
                "model" to request.model,
                "status" to response.statusCode,
                "code" to failure.code.name,
                "retryable" to failure.retryable,
                "message" to sanitizeForLog(failure.message ?: ""),
            )
            throw failure
        }
        if (response.body.isBlank()) throw invalidResponse("Gemini returned an empty response")
        val parsed = try {
            parseCompletion(parseJson(response.body), request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ModelProviderError) {
            trace.failure(
                "ERROR",
                "stage" to "parse",
                "provider" to id,
                "model" to request.model,
                "status" to response.statusCode,
                "code" to error.code.name,
                "message" to sanitizeForLog(error.message ?: ""),
            )
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

    /**
     * Streaming is not announced by [capabilities], so the gateway uses
     * [complete]; this keeps the contract by reporting the whole answer once.
     */
    override suspend fun stream(
        request: ModelRequest,
        onEvent: (ModelStreamEvent) -> Unit,
    ): ModelResponse {
        val trace = ApiTrace.create(logger, id, ApiOperation.STREAM)
        trace.stage(
            "START",
            "provider" to id,
            "model" to request.model,
            "stream" to true,
            "streamingSupported" to configuredFlag(defaultCapabilities.streaming),
        )
        onEvent(ModelStreamEvent.Started(request.model, id))
        val response = complete(request)
        if (response.content.isNotEmpty()) onEvent(ModelStreamEvent.TextDelta(response.content))
        response.usage?.let { onEvent(ModelStreamEvent.UsageReported(it)) }
        onEvent(ModelStreamEvent.Completed(response.finishReason, response.usage))
        trace.stage(
            "COMPLETE",
            "provider" to id,
            "model" to response.model,
            "contentChars" to response.content.length,
            "note" to "streaming-not-announced-single-response",
        )
        return response
    }

    // --- transport ---------------------------------------------------------

    private suspend fun send(request: ModelRequest, url: String, body: String): HttpResponseSpec =
        try {
            transport.execute(httpRequest(request, url, body))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            throw transportError(error)
        }

    private fun httpRequest(request: ModelRequest, url: String, body: String): HttpRequestSpec {
        val headers = LinkedHashMap<String, String>()
        headers["Content-Type"] = "application/json"
        headers["Accept"] = "application/json"
        // The documented API-key header. The key never enters the URL, so it cannot
        // leak through a logged or displayed address.
        request.config.apiKey?.takeIf { it.isNotBlank() }?.let { key -> headers[API_KEY_HEADER] = key }
        request.config.headers.forEach { (name, value) -> headers[name] = value }
        val timeout = request.config.timeoutMillis?.takeIf { it > 0 }?.toInt()
        return HttpRequestSpec(
            method = "POST",
            url = url,
            headers = headers,
            body = body,
            connectTimeoutMillis = timeout ?: CONNECT_TIMEOUT_MILLIS,
            readTimeoutMillis = timeout ?: READ_TIMEOUT_MILLIS,
        )
    }

    /** `POST <baseUrl>/models/<model>:generateContent` — the model is in the path. */
    private fun endpoint(request: ModelRequest): String {
        val model = request.model.trim().removePrefix("models/")
        return request.config.baseUrl.trimEnd('/') + CHAT_PATH_PREFIX + model + GENERATE_SUFFIX
    }

    // --- diagnostics -------------------------------------------------------

    /**
     * Request trace in the shared `[PROVIDER][OPERATION][id] STAGE key=value`
     * shape: the path shows the model, and the key is reported as a presence flag
     * rather than a value.
     */
    private fun traceRequest(
        trace: ApiTrace,
        request: ModelRequest,
        url: String,
        body: String,
        stream: Boolean,
    ) {
        val generation = request.effectiveGeneration
        trace.stage(
            "REQUEST",
            "method" to "POST",
            "path" to diagnosticPath(url),
            "endpoint" to diagnosticPath(request.config.baseUrl),
            "purpose" to "normal-completion",
            "provider" to id,
            "protocol" to PROTOCOL,
            "model" to request.model,
            "stream" to stream,
            "bodyBytes" to body.length,
            "messageCount" to request.messages.size,
            "toolCount" to request.tools.size,
            "toolCalling" to configuredFlag(request.tools.isNotEmpty()),
            "hasApiKey" to configuredFlag(!request.config.apiKey.isNullOrBlank()),
            "temperature" to (generation.temperature ?: "-"),
            "maxTokens" to (generation.maxOutputTokens ?: "-"),
        )
    }

    private fun traceResponse(
        trace: ApiTrace,
        request: ModelRequest,
        url: String,
        response: HttpResponseSpec,
        startedNanos: Long,
    ) {
        trace.stage(
            "RESPONSE",
            "status" to response.statusCode,
            "elapsedMs" to elapsedMillis(startedNanos),
            "bodyBytes" to response.body.length,
            "contentType" to (
                response.headers.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
                    ?.value?.firstOrNull()?.substringBefore(';')?.trim() ?: "-"
                ),
            "success" to response.isSuccess,
            "provider" to id,
            "protocol" to PROTOCOL,
            "model" to request.model,
            "path" to diagnosticPath(url),
        )
    }

    private fun elapsedMillis(startedNanos: Long): Long = (System.nanoTime() - startedNanos) / 1_000_000

    // --- error normalization ----------------------------------------------

    private fun transportError(error: Throwable): ModelProviderError {
        val root = unwrap(error)
        return when {
            root is ModelProviderError -> root
            root is SocketTimeoutException || root is java.io.InterruptedIOException -> ModelProviderError(
                code = ModelProviderErrorCode.TIMEOUT,
                message = "The Gemini request timed out.",
                providerId = id,
                retryable = true,
                cause = error,
            )
            root is UnknownHostException -> ModelProviderError(
                code = ModelProviderErrorCode.CONNECTION_FAILED,
                message = "Could not resolve the Gemini API host.",
                providerId = id,
                retryable = true,
                cause = error,
            )
            root is ConnectException || root is NoRouteToHostException || root is PortUnreachableException ->
                ModelProviderError(
                    code = ModelProviderErrorCode.CONNECTION_FAILED,
                    message = "Could not reach the Gemini API.",
                    providerId = id,
                    retryable = true,
                    cause = error,
                )
            root is SSLException -> ModelProviderError(
                code = ModelProviderErrorCode.CONNECTION_FAILED,
                message = "Secure connection to the Gemini API failed",
                providerId = id,
                retryable = true,
                cause = error,
            )
            root is IOException -> ModelProviderError(
                code = ModelProviderErrorCode.NETWORK_ERROR,
                message = "The Gemini request failed: ${root.message ?: "network error"}",
                providerId = id,
                retryable = true,
                cause = error,
            )
            else -> ModelProviderError(
                code = ModelProviderErrorCode.UNKNOWN,
                message = "The Gemini request failed unexpectedly",
                providerId = id,
                retryable = false,
                cause = error,
            )
        }
    }

    private fun unwrap(error: Throwable): Throwable {
        var current: Throwable = error
        while (current.cause != null && current.cause !== current && current.javaClass == RuntimeException::class.java) {
            current = current.cause ?: break
        }
        return current
    }

    private fun httpError(response: HttpResponseSpec): ModelProviderError {
        val status = response.statusCode
        val info = extractErrorInfo(response.body)
        val code = when (status) {
            400 -> ModelProviderErrorCode.INVALID_REQUEST
            401, 403 -> ModelProviderErrorCode.AUTHENTICATION_FAILED
            404 -> ModelProviderErrorCode.UNSUPPORTED
            429 -> ModelProviderErrorCode.RATE_LIMITED
            in 500..599 -> ModelProviderErrorCode.PROVIDER_ERROR
            else -> ModelProviderErrorCode.PROVIDER_ERROR
        }
        return ModelProviderError(
            code = code,
            message = info.message ?: "Gemini returned HTTP $status",
            providerId = id,
            httpStatus = status,
            providerErrorType = info.status,
            retryable = status == 429 || status in 500..599,
            retryAfterMillis = if (status == 429) RetryAfter.parseMillis(response.headers) else null,
        )
    }

    private class ProviderErrorInfo(val message: String?, val status: String?)

    private fun extractErrorInfo(body: String): ProviderErrorInfo {
        val root = runCatching { JsonCodec.parse(body).objectOrNull() }.getOrNull()
            ?: return ProviderErrorInfo(null, null)
        val error = root.objectOrNull("error")
        if (error != null) {
            return ProviderErrorInfo(
                message = error.stringOrNull("message"),
                status = error.stringOrNull("status") ?: error.stringOrNull("code"),
            )
        }
        return ProviderErrorInfo(root.stringOrNull("message"), root.stringOrNull("status"))
    }

    private fun invalidResponse(message: String): ModelProviderError =
        ModelProviderError(ModelProviderErrorCode.INVALID_RESPONSE, message, id)

    private fun parseJson(body: String): JsonObject {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) throw invalidResponse("Gemini returned an empty response")
        val parsed = runCatching { JsonCodec.parse(trimmed) }.getOrElse { error ->
            throw invalidResponse("Gemini returned invalid JSON: ${error.message ?: "parse error"}")
        }
        return parsed.objectOrNull()
            ?: throw invalidResponse("Gemini returned a non-object JSON response")
    }

    // --- request building --------------------------------------------------

    private fun buildRequestBody(request: ModelRequest): String {
        val fields = LinkedHashMap<String, JsonValue>()
        fields["contents"] = Json.array(request.messages.mapNotNull { serializeMessage(it, request) })

        // A system instruction is its own field in the native API, not a message.
        request.messages
            .firstOrNull { it.role == ModelRole.SYSTEM }
            ?.content
            ?.takeIf { it.isNotBlank() }
            ?.let { fields["systemInstruction"] = Json.obj("parts" to Json.array(listOf(Json.obj("text" to Json.of(it))))) }

        if (request.tools.isNotEmpty()) {
            fields["tools"] = Json.array(
                listOf(Json.obj("functionDeclarations" to Json.array(request.tools.map { serializeTool(it) }))),
            )
        }
        request.toolChoice?.let { fields["toolConfig"] = serializeToolChoice(it) }

        val generation = request.effectiveGeneration
        val config = LinkedHashMap<String, JsonValue>()
        generation.temperature?.let { config["temperature"] = Json.of(it) }
        generation.maxOutputTokens?.let { config["maxOutputTokens"] = Json.of(it) }
        generation.topP?.let { config["topP"] = Json.of(it) }
        if (generation.stop.isNotEmpty()) config["stopSequences"] = Json.array(generation.stop.map { Json.of(it) })
        if (config.isNotEmpty()) fields["generationConfig"] = JsonValue.Obj(config)

        return JsonCodec.encodeObject(fields)
    }

    /**
     * One message as a native `contents` entry.
     *
     * Gemini calls the assistant turn `model`, and a tool result is a
     * `functionResponse` part inside a user turn. A message that carries neither
     * text nor a function part is skipped rather than sent empty.
     *
     * A system prompt is not a turn at all: it travels in `systemInstruction`, so
     * emitting it here as well would send it twice.
     */
    private fun serializeMessage(message: ModelMessage, request: ModelRequest): JsonValue? {
        if (message.role == ModelRole.SYSTEM) return null
        val parts = mutableListOf<JsonValue>()
        if (message.content.isNotBlank() && message.toolCallId == null) {
            parts += Json.obj("text" to Json.of(message.content))
        }
        if (message.content.isNotBlank() && message.toolCallId != null) {
            parts += Json.obj(
                "functionResponse" to Json.obj(
                    "name" to Json.of(toolNameFor(message, request)),
                    "response" to Json.obj("result" to Json.of(message.content)),
                ),
            )
        }
        message.toolCalls.forEach { call ->
            parts += Json.obj(
                "functionCall" to Json.obj(
                    "name" to Json.of(call.name),
                    "args" to JsonValue.Obj(call.arguments),
                ),
            )
        }
        if (parts.isEmpty()) return null
        val role = if (message.role == ModelRole.ASSISTANT) ASSISTANT_ROLE else USER_ROLE
        return Json.obj(
            "role" to Json.of(role),
            "parts" to Json.array(parts),
        )
    }

    /**
     * The function name a tool result belongs to.
     *
     * Gemini matches a `functionResponse` by name, not by call id, so the name is
     * taken from the result message or looked up through the call it answers.
     */
    private fun toolNameFor(message: ModelMessage, request: ModelRequest): String {
        message.name?.takeIf { it.isNotBlank() }?.let { return it }
        val callId = message.toolCallId ?: return FUNCTION_UNKNOWN
        return request.messages
            .flatMap { it.toolCalls }
            .firstOrNull { it.id == callId }
            ?.name
            ?: FUNCTION_UNKNOWN
    }

    private fun serializeTool(spec: ModelToolSpec): JsonValue {
        val properties = LinkedHashMap<String, JsonValue>()
        spec.parameters.forEach { parameter ->
            properties[parameter.name] = Json.obj(
                "type" to Json.of(parameter.type.schemaName()),
                "description" to Json.of(parameter.description),
            )
        }
        val required = spec.parameters.filter { it.required }.map { Json.of(it.name) }
        return Json.obj(
            "name" to Json.of(spec.name),
            "description" to Json.of(spec.description),
            "parameters" to Json.obj(
                "type" to Json.of("OBJECT"),
                "properties" to JsonValue.Obj(properties),
                "required" to Json.array(required),
            ),
        )
    }

    private fun serializeToolChoice(choice: ModelToolChoice): JsonValue = when (choice) {
        ModelToolChoice.Auto -> Json.obj(
            "functionCallingConfig" to Json.obj("mode" to Json.of("AUTO")),
        )
        ModelToolChoice.None -> Json.obj(
            "functionCallingConfig" to Json.obj("mode" to Json.of("NONE")),
        )
        ModelToolChoice.Required -> Json.obj(
            "functionCallingConfig" to Json.obj("mode" to Json.of("ANY")),
        )
        is ModelToolChoice.Specific -> Json.obj(
            "functionCallingConfig" to Json.obj(
                "mode" to Json.of("ANY"),
                "allowedFunctionNames" to Json.array(listOf(Json.of(choice.name))),
            ),
        )
    }

    // --- response parsing --------------------------------------------------

    private fun parseCompletion(json: JsonObject, request: ModelRequest): ModelResponse {
        if (json.objectOrNull("error") != null) {
            val info = extractErrorInfo(JsonCodec.encode(JsonValue.Obj(json)))
            throw ModelProviderError(
                code = ModelProviderErrorCode.PROVIDER_ERROR,
                message = info.message ?: "Gemini reported an error",
                providerId = id,
                providerErrorType = info.status,
            )
        }
        val candidate = json.arrayOrNull("candidates")?.firstOrNull()?.objectOrNull()
        val parts = candidate?.objectOrNull("content")?.arrayOrNull("parts").orEmpty()
        val text = parts.mapNotNull { part -> part.objectOrNull()?.stringOrNull("text") }.joinToString("")
        val toolCalls = parts.mapNotNull { part -> part.objectOrNull()?.objectOrNull("functionCall")?.let(::parseFunctionCall) }
        val blocked = json.objectOrNull("promptFeedback")?.stringOrNull("blockReason")
        if (candidate == null && blocked != null) {
            throw invalidResponse("Gemini blocked the prompt ($blocked)")
        }
        val finishReason = when {
            toolCalls.isNotEmpty() -> ModelFinishReason.TOOL_CALLS
            candidate?.stringOrNull("finishReason")?.equals("MAX_TOKENS", ignoreCase = true) == true ->
                ModelFinishReason.LENGTH
            candidate?.stringOrNull("finishReason")?.equals("SAFETY", ignoreCase = true) == true ->
                ModelFinishReason.CONTENT_FILTER
            else -> ModelFinishReason.STOP
        }
        return ContentToolCallParser.normalize(
            ModelResponse(
                model = json.stringOrNull("modelVersion")?.removePrefix("models/") ?: request.model,
                providerId = id,
                content = text,
                toolCalls = toolCalls,
                finishReason = finishReason,
                usage = json.objectOrNull("usageMetadata")?.let { parseUsage(it) },
            ),
        )
    }

    /**
     * A `functionCall` part.
     *
     * Gemini does not return a call id, so one is derived from the function name
     * and its arguments: the runtime needs a stable id to attach the tool result
     * to, and a derived id stays stable for the same call.
     */
    private fun parseFunctionCall(part: JsonObject): ModelToolCall? {
        val name = part.stringOrNull("name")?.takeIf { it.isNotBlank() } ?: return null
        val arguments: JsonObject = part.objectOrNull("args") ?: emptyMap()
        return ModelToolCall(
            id = "gemini-$name",
            name = name,
            arguments = arguments,
        )
    }

    private fun parseUsage(usage: JsonObject): ModelUsage = ModelUsage(
        promptTokens = usage.numberOrNull("promptTokenCount")?.toInt(),
        completionTokens = usage.numberOrNull("candidatesTokenCount")?.toInt(),
        totalTokens = usage.numberOrNull("totalTokenCount")?.toInt(),
    )

    companion object {
        const val DEFAULT_ID: String = "gemini"

        /** Gemini's model list and chat paths both sit under this API version. */
        const val API_VERSION_PATH: String = "/v1beta"

        /** The documented header for a Gemini API key. */
        const val API_KEY_HEADER: String = "x-goog-api-key"

        const val CHAT_PATH_PREFIX: String = "/models/"
        const val GENERATE_SUFFIX: String = ":generateContent"
        const val PROTOCOL: String = "GEMINI_NATIVE"
        const val USER_ROLE: String = "user"
        const val ASSISTANT_ROLE: String = "model"
        const val FUNCTION_UNKNOWN: String = "tool"
        const val CONNECT_TIMEOUT_MILLIS: Int = 15_000

        val READ_TIMEOUT_MILLIS: Int = AgentTimeouts.MODEL_REQUEST_MILLIS.toInt()

        val DEFAULT_CAPABILITIES: ModelCapabilities = ModelCapabilities(
            streaming = false,
            toolCalling = true,
            vision = false,
            structuredOutput = true,
            systemMessages = true,
        )
    }
}

/** Gemini expects schema types in its own casing. */
private fun ModelToolParameterType.schemaName(): String = when (this) {
    ModelToolParameterType.STRING -> "STRING"
    ModelToolParameterType.NUMBER -> "NUMBER"
    ModelToolParameterType.BOOLEAN -> "BOOLEAN"
    ModelToolParameterType.OBJECT -> "OBJECT"
    ModelToolParameterType.ARRAY -> "ARRAY"
    ModelToolParameterType.ANY -> "STRING"
}
