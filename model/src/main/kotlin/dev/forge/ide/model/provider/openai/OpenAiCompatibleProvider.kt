package dev.forge.ide.model.provider.openai

import dev.forge.ide.model.*
import dev.forge.ide.model.http.*
import dev.forge.ide.model.json.*
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
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
        return parseCompletion(parseJson(response.body), request)
    }

    override suspend fun stream(
        request: ModelRequest,
        onEvent: (ModelStreamEvent) -> Unit,
    ): ModelResponse {
        val accumulator = StreamAccumulator()
        onEvent(ModelStreamEvent.Started(request.model, id))
        val response = sendStreaming(request, buildRequestBody(request, stream = true)) { line ->
            handleStreamLine(line, accumulator, onEvent)
        }
        if (!response.isSuccess) throw httpError(response)
        onEvent(ModelStreamEvent.Completed(accumulator.finishReason, accumulator.usage))
        return ModelResponse(
            model = accumulator.model ?: request.model,
            providerId = id,
            content = accumulator.content.toString(),
            toolCalls = accumulator.toolCalls(),
            finishReason = accumulator.finishReason,
            usage = accumulator.usage,
        )
    }

    // --- transport ---------------------------------------------------------

    private suspend fun send(request: ModelRequest, body: String): HttpResponseSpec =
        try {
            transport.execute(httpRequest(request, body))
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
        transport.executeStreaming(httpRequest(request, body), onLine)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        throw transportError(error)
    }

    private fun httpRequest(request: ModelRequest, body: String): HttpRequestSpec {
        val headers = LinkedHashMap<String, String>()
        headers["Content-Type"] = "application/json"
        headers["Accept"] = "application/json"
        request.config.apiKey?.takeIf { it.isNotBlank() }?.let { key ->
            headers["Authorization"] = "Bearer $key"
        }
        request.config.headers.forEach { (name, value) -> headers[name] = value }
        return HttpRequestSpec(
            method = "POST",
            url = endpoint(request.config),
            headers = headers,
            body = body,
        )
    }

    /** Builds the chat URL from the configured base URL; the path is overridable. */
    private fun endpoint(config: ModelConfig): String = config.baseUrl.trimEnd('/') + chatPath

    // --- error normalization ----------------------------------------------

    private fun transportError(error: Throwable): ModelProviderError = when (error) {
        is ModelProviderError -> error
        is SocketTimeoutException -> ModelProviderError(
            code = ModelProviderErrorCode.TIMEOUT,
            message = "Model request timed out",
            providerId = id,
            retryable = true,
            cause = error,
        )
        is UnknownHostException, is ConnectException -> ModelProviderError(
            code = ModelProviderErrorCode.CONNECTION_FAILED,
            message = "Could not connect to the model endpoint",
            providerId = id,
            retryable = true,
            cause = error,
        )
        is IOException -> ModelProviderError(
            code = ModelProviderErrorCode.NETWORK_ERROR,
            message = "Network error while contacting the model endpoint",
            providerId = id,
            retryable = true,
            cause = error,
        )
        else -> ModelProviderError(
            code = ModelProviderErrorCode.UNKNOWN,
            message = error.message ?: "Unexpected model provider error",
            providerId = id,
            cause = error,
        )
    }

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

    private fun invalidResponse(message: String): ModelProviderError =
        ModelProviderError(ModelProviderErrorCode.INVALID_RESPONSE, message, id)

    private fun parseJson(body: String): JsonObject =
        runCatching { JsonCodec.parse(body).objectOrNull() }.getOrNull()
            ?: throw invalidResponse("Model endpoint returned a non-JSON response body")

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
        val content = message.stringOrNull("content") ?: ""
        val toolCalls = parseToolCalls(message.arrayOrNull("tool_calls"))
        val finishReason = choice.stringOrNull("finish_reason")?.let { toFinishReason(it) }
        val usage = json.objectOrNull("usage")?.let { parseUsage(it) }
        val model = json.stringOrNull("model") ?: request.model
        return ModelResponse(
            model = model,
            providerId = id,
            content = content,
            toolCalls = toolCalls,
            finishReason = finishReason,
            usage = usage,
        )
    }

    private fun parseToolCalls(items: List<JsonValue>?): List<ModelToolCall> {
        if (items == null) return emptyList()
        return items.mapNotNull { item ->
            val call = item.objectOrNull() ?: return@mapNotNull null
            val function = call.objectOrNull("function") ?: return@mapNotNull null
            val name = function.stringOrNull("name") ?: return@mapNotNull null
            ModelToolCall(
                id = call.stringOrNull("id") ?: "",
                name = name,
                arguments = parseArguments(function.stringOrNull("arguments")),
            )
        }
    }

    private fun parseArguments(raw: String?): JsonObject {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching { JsonCodec.parse(raw).objectOrNull() }.getOrNull() ?: emptyMap()
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
        delta.stringOrNull("content")?.let { text ->
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

    private class ProviderErrorInfo(val message: String?, val type: String?)

    companion object {
        const val DEFAULT_ID: String = "openai-compatible"
        const val DEFAULT_CHAT_PATH: String = "/chat/completions"

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
