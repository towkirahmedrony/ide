package com.agentx.app.model.provider.openai

import com.agentx.app.core.timeout.AgentTimeouts
import com.agentx.app.model.*
import com.agentx.app.model.http.*
import com.agentx.app.model.json.*
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
) : ModelProvider {

    override fun capabilities(modelId: String): ModelCapabilities = defaultCapabilities

    override suspend fun complete(request: ModelRequest): ModelResponse {
        val response = send(request, buildRequestBody(request, stream = false))
        if (!response.isSuccess) throw httpError(response)
        if (response.body.isBlank()) throw invalidResponse("Model endpoint returned an empty response")
        return parseCompletion(parseJson(response.body), request)
    }

    override suspend fun stream(
        request: ModelRequest,
        onEvent: (ModelStreamEvent) -> Unit,
    ): ModelResponse {
        val accumulator = StreamAccumulator()
        val rawBody = StringBuilder()
        onEvent(ModelStreamEvent.Started(request.model, id))
        val response = sendStreaming(request, buildRequestBody(request, stream = true)) { line ->
            if (rawBody.isNotEmpty()) rawBody.append('\n')
            rawBody.append(line)
            handleStreamLine(line, accumulator, onEvent)
        }
        if (!response.isSuccess) throw httpError(response)
        if (accumulator.content.isEmpty() && accumulator.toolCalls().isEmpty() && !accumulator.done) {
            applyNonStreamFallback(accumulator, rawBody.toString(), request, onEvent)
        }
        onEvent(ModelStreamEvent.Completed(accumulator.finishReason, accumulator.usage))
        return ContentToolCallParser.normalize(
            ModelResponse(
                model = accumulator.model ?: request.model,
                providerId = id,
                content = accumulator.content.toString(),
                toolCalls = accumulator.toolCalls(),
                finishReason = accumulator.finishReason,
                usage = accumulator.usage,
            ),
        )
    }

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
        val code = when (status) {
            400 -> ModelProviderErrorCode.INVALID_REQUEST
            401, 403 -> ModelProviderErrorCode.AUTHENTICATION_FAILED
            408 -> ModelProviderErrorCode.TIMEOUT
            429 -> ModelProviderErrorCode.RATE_LIMITED
            in 500..599 -> ModelProviderErrorCode.PROVIDER_ERROR
            else -> ModelProviderErrorCode.PROVIDER_ERROR
        }
        return ModelProviderError(
            code = code,
            message = info.message ?: "Model endpoint returned HTTP $status",
            providerId = id,
            httpStatus = status,
            providerErrorType = info.type,
            retryable = status == 429 || status in 500..599,
            // A provider-supplied Retry-After is a hint for the central rate-limit
            // retry path; the provider itself never retries.
            retryAfterMillis = if (status == 429) parseRetryAfter(response.headers) else null,
        )
    }

    /** Parses a numeric `Retry-After` (seconds). The HTTP-date form falls back to backoff. */
    private fun parseRetryAfter(headers: Map<String, List<String>>): Long? {
        val raw = headers.entries
            .firstOrNull { (name, _) -> name.equals("Retry-After", ignoreCase = true) }
            ?.value
            ?.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        val seconds = raw.toDoubleOrNull() ?: return null
        if (seconds < 0.0) return null
        return (seconds * 1000.0).toLong()
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
