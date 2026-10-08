package com.agentx.app.model

import com.agentx.app.model.http.HttpResponseSpec
import com.agentx.app.model.json.JsonCodec
import com.agentx.app.model.json.JsonObject
import com.agentx.app.model.json.JsonValue
import com.agentx.app.model.json.arrayOrNull
import com.agentx.app.model.json.booleanOrNull
import com.agentx.app.model.json.numberOrNull
import com.agentx.app.model.json.objectOrNull
import com.agentx.app.model.json.stringOrNull
import com.agentx.app.model.preset.ModelProviderIds
import com.agentx.app.model.preset.NGROK_SKIP_BROWSER_WARNING_HEADER
import com.agentx.app.model.provider.openai.OpenAiCompatibleProvider
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenAiCompatibleProviderTest {

    private fun provider(transport: FakeHttpTransport): OpenAiCompatibleProvider =
        OpenAiCompatibleProvider(transport = transport)

    private fun requestBody(transport: FakeHttpTransport): JsonObject {
        val raw = assertNotNull(transport.lastRequest?.body)
        return assertNotNull(JsonCodec.parse(raw).objectOrNull())
    }

    // --- successful non-streaming -----------------------------------------

    @Test
    fun `complete parses a normalized response`() {
        val transport = FakeHttpTransport(response = HttpResponseSpec(200, SUCCESS_RESPONSE))
        val provider = provider(transport)

        val response = runSuspend {
            provider.complete(request(openAiConfig(), ModelMessage.user("hi")))
        }

        assertEquals("Hello!", response.content)
        assertEquals("local-model", response.model)
        assertEquals("openai-compatible", response.providerId)
        assertEquals(ModelFinishReason.STOP, response.finishReason)
        assertEquals(3, response.usage?.promptTokens)
        assertEquals(2, response.usage?.completionTokens)
        assertEquals(5, response.usage?.totalTokens)
    }

    @Test
    fun `complete sends the configured model and generation settings`() {
        val transport = FakeHttpTransport(response = HttpResponseSpec(200, SUCCESS_RESPONSE))
        val provider = provider(transport)
        val config = openAiConfig(
            model = "my-model",
            generation = ModelGenerationSettings(temperature = 0.3, maxOutputTokens = 42, topP = 0.9),
        )

        runSuspend { provider.complete(request(config, ModelMessage.user("hi"))) }

        val json = requestBody(transport)
        assertEquals("my-model", json.stringOrNull("model"))
        assertFalse(json.booleanOrNull("stream") ?: true)
        assertEquals(0.3, json.numberOrNull("temperature"))
        assertEquals(42.0, json.numberOrNull("max_tokens"))
        assertEquals(0.9, json.numberOrNull("top_p"))
    }

    @Test
    fun `complete builds the request from the configured base url`() {
        val transport = FakeHttpTransport(response = HttpResponseSpec(200, SUCCESS_RESPONSE))
        val provider = provider(transport)

        runSuspend {
            provider.complete(request(openAiConfig(baseUrl = "http://localhost:8080/v1/"), ModelMessage.user("hi")))
        }

        assertEquals("http://localhost:8080/v1/chat/completions", transport.lastRequest?.url)
        assertEquals("POST", transport.lastRequest?.method)
        assertEquals("application/json", transport.lastRequest?.headers?.get("Content-Type"))
        assertEquals("application/json", transport.lastRequest?.headers?.get("Accept"))
        assertEquals("hi", requestBody(transport).arrayOrNull("messages")?.firstOrNull()?.objectOrNull()?.stringOrNull("content"))
        assertEquals(OpenAiCompatibleProvider.CONNECT_TIMEOUT_MILLIS, transport.lastRequest?.connectTimeoutMillis)
        assertEquals(OpenAiCompatibleProvider.READ_TIMEOUT_MILLIS, transport.lastRequest?.readTimeoutMillis)
    }

    @Test
    fun `complete honors a per-config timeout`() {
        val transport = FakeHttpTransport(response = HttpResponseSpec(200, SUCCESS_RESPONSE))
        val provider = provider(transport)

        runSuspend {
            provider.complete(
                request(
                    openAiConfig().copy(timeoutMillis = 4_000),
                    ModelMessage.user("hi"),
                ),
            )
        }

        assertEquals(4_000, transport.lastRequest?.connectTimeoutMillis)
        assertEquals(4_000, transport.lastRequest?.readTimeoutMillis)
    }

    @Test
    fun `complete parses content arrays used by some colab servers`() {
        val transport = FakeHttpTransport(
            response = HttpResponseSpec(
                200,
                """{"choices":[{"message":{"role":"assistant","content":[{"type":"text","text":"Hel"},{"type":"text","text":"lo"}]},"finish_reason":"stop"}]}""",
            ),
        )
        val provider = provider(transport)

        val response = runSuspend { provider.complete(request(openAiConfig(), ModelMessage.user("hi"))) }

        assertEquals("Hello", response.content)
    }

    @Test
    fun `complete rejects an empty response body`() {
        val transport = FakeHttpTransport(response = HttpResponseSpec(200, "   "))
        val provider = provider(transport)

        val error = assertFailsWith<ModelProviderError> {
            runSuspend { provider.complete(request(openAiConfig(), ModelMessage.user("hi"))) }
        }

        assertEquals(ModelProviderErrorCode.INVALID_RESPONSE, error.code)
    }

    // --- optional API key --------------------------------------------------

    @Test
    fun `no authorization header is sent when the api key is absent`() {
        val transport = FakeHttpTransport(response = HttpResponseSpec(200, SUCCESS_RESPONSE))
        val provider = provider(transport)

        runSuspend { provider.complete(request(openAiConfig(apiKey = null), ModelMessage.user("hi"))) }

        assertNull(transport.lastRequest?.headers?.get("Authorization"))
    }

    @Test
    fun `an authorization header is sent when the api key is present`() {
        val transport = FakeHttpTransport(response = HttpResponseSpec(200, SUCCESS_RESPONSE))
        val provider = provider(transport)

        runSuspend { provider.complete(request(openAiConfig(apiKey = "secret"), ModelMessage.user("hi"))) }

        assertEquals("Bearer secret", transport.lastRequest?.headers?.get("Authorization"))
    }

    // --- connection headers -------------------------------------------------

    /**
     * A custom endpoint's connection-level headers (a proxy flag, say) are part of
     * the saved connection, so they must reach the wire on a normal completion and
     * not only on discovery.
     */
    @Test
    fun `connection headers are sent with a normal completion`() {
        val transport = FakeHttpTransport(response = HttpResponseSpec(200, SUCCESS_RESPONSE))
        val provider = provider(transport)
        val config = openAiConfig(apiKey = "secret")
            .copy(headers = mapOf(NGROK_SKIP_BROWSER_WARNING_HEADER to "true"))

        runSuspend { provider.complete(request(config, ModelMessage.user("hi"))) }

        assertEquals("true", transport.lastRequest?.headers?.get(NGROK_SKIP_BROWSER_WARNING_HEADER))
        assertEquals("Bearer secret", transport.lastRequest?.headers?.get("Authorization"))
    }

    @Test
    fun `connection headers are sent with the provider's own model list request`() {
        val transport = FakeHttpTransport(
            response = HttpResponseSpec(200, """{"object":"list","data":[{"id":"only"}]}"""),
        )
        val provider = provider(transport)
        val config = openAiConfig().copy(headers = mapOf(NGROK_SKIP_BROWSER_WARNING_HEADER to "true"))

        runSuspend { provider.discoverModels(config) }

        assertEquals("true", transport.lastRequest?.headers?.get(NGROK_SKIP_BROWSER_WARNING_HEADER))
    }

    // --- streaming ---------------------------------------------------------

    @Test
    fun `stream emits deltas and returns the aggregated response`() {
        val transport = FakeHttpTransport(
            streamLines = listOf(
                """data: {"model":"local-model","choices":[{"index":0,"delta":{"role":"assistant","content":"Hel"}}]}""",
                """data: {"model":"local-model","choices":[{"index":0,"delta":{"content":"lo"}}]}""",
                """data: {"model":"local-model","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
                "data: [DONE]",
            ),
        )
        val provider = provider(transport)
        val events = mutableListOf<ModelStreamEvent>()

        val response = runSuspend {
            provider.stream(request(openAiConfig(stream = true), ModelMessage.user("hi"))) { events += it }
        }

        assertEquals("Hello", response.content)
        assertEquals(ModelFinishReason.STOP, response.finishReason)
        assertEquals(listOf("Hel", "lo"), events.filterIsInstance<ModelStreamEvent.TextDelta>().map { it.text })
        assertTrue(events.any { it is ModelStreamEvent.Started })
        assertTrue(events.any { it is ModelStreamEvent.Completed })
        assertTrue(requestBody(transport).booleanOrNull("stream") == true)
        assertEquals("text/event-stream, application/json", transport.lastRequest?.headers?.get("Accept"))
    }

    // --- FreeLLMAPI: the same provider, addressed at the API gateway --------

    /** The API-AI path: an OpenAI-compatible gateway in front of Gemini/Groq. */
    private fun freeLlmConfig(model: String = "gemini-2.5-flash") = openAiConfig(
        providerId = ModelProviderIds.FREELMAPI,
        baseUrl = "https://agentx-vgtx.onrender.com/v1",
        model = model,
        apiKey = "fla-test-key",
    )

    @Test
    fun `a freellmapi completion is addressed at the gateway with the selected model and bearer auth`() {
        val transport = FakeHttpTransport(response = HttpResponseSpec(200, SUCCESS_RESPONSE))
        val provider = provider(transport)

        val response = runSuspend {
            provider.complete(request(freeLlmConfig("openai/gpt-oss-20b"), ModelMessage.user("hi")))
        }

        val sent = assertNotNull(transport.lastRequest)
        // No FreeLLMAPI-specific client: the shared OpenAI-compatible one is aimed at
        // the gateway's base URL.
        assertEquals("https://agentx-vgtx.onrender.com/v1/chat/completions", sent.url)
        assertEquals("Bearer fla-test-key", sent.headers["Authorization"])
        // The model the user selected travels in the body, never a gateway default.
        assertEquals("openai/gpt-oss-20b", requestBody(transport).stringOrNull("model"))
        assertEquals("Hello!", response.content)
    }

    @Test
    fun `a freellmapi stream reuses the shared sse pipeline`() {
        val transport = FakeHttpTransport(
            streamLines = listOf(
                """data: {"model":"gemini-2.5-flash","choices":[{"index":0,"delta":{"role":"assistant","content":"Hel"}}]}""",
                """data: {"model":"gemini-2.5-flash","choices":[{"index":0,"delta":{"content":"lo"}}]}""",
                """data: {"model":"gemini-2.5-flash","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
                "data: [DONE]",
            ),
        )
        val events = mutableListOf<ModelStreamEvent>()

        val response = runSuspend {
            provider(transport).stream(request(freeLlmConfig().copy(stream = true), ModelMessage.user("hi"))) {
                events += it
            }
        }

        assertEquals("Hello", response.content)
        assertEquals(ModelFinishReason.STOP, response.finishReason)
        assertEquals(listOf("Hel", "lo"), events.filterIsInstance<ModelStreamEvent.TextDelta>().map { it.text })
        assertTrue(requestBody(transport).booleanOrNull("stream") == true)
        // The gateway's stream is read by the same SSE reader, ending on [DONE].
        assertEquals("text/event-stream, application/json", transport.lastRequest?.headers?.get("Accept"))
    }

    @Test
    fun `stream falls back to a non-sse openai json body`() {
        val body = """{"model":"local-model","choices":[{"message":{"role":"assistant","content":"Hello from Colab"},"finish_reason":"stop"}]}"""
        val transport = FakeHttpTransport(streamLines = listOf(body))
        val provider = provider(transport)
        val events = mutableListOf<ModelStreamEvent>()

        val response = runSuspend {
            provider.stream(request(openAiConfig(stream = true), ModelMessage.user("hi"))) { events += it }
        }

        assertEquals("Hello from Colab", response.content)
        assertTrue(events.filterIsInstance<ModelStreamEvent.TextDelta>().any { it.text == "Hello from Colab" })
    }

    // --- invalid responses -------------------------------------------------

    @Test
    fun `complete rejects a non-json response`() {
        val transport = FakeHttpTransport(response = HttpResponseSpec(200, "not json at all"))
        val provider = provider(transport)

        val error = assertFailsWith<ModelProviderError> {
            runSuspend { provider.complete(request(openAiConfig(), ModelMessage.user("hi"))) }
        }

        assertEquals(ModelProviderErrorCode.INVALID_RESPONSE, error.code)
    }

    @Test
    fun `complete rejects a response without choices`() {
        val transport = FakeHttpTransport(response = HttpResponseSpec(200, """{"id":"x","choices":[]}"""))
        val provider = provider(transport)

        val error = assertFailsWith<ModelProviderError> {
            runSuspend { provider.complete(request(openAiConfig(), ModelMessage.user("hi"))) }
        }

        assertEquals(ModelProviderErrorCode.INVALID_RESPONSE, error.code)
    }

    // --- HTTP error normalization -----------------------------------------

    @Test
    fun `http 401 normalizes to authentication failure without leaking the key`() {
        val transport = FakeHttpTransport(
            response = HttpResponseSpec(401, """{"error":{"message":"Invalid API key","type":"invalid_request_error"}}"""),
        )
        val provider = provider(transport)

        val error = assertFailsWith<ModelProviderError> {
            runSuspend { provider.complete(request(openAiConfig(apiKey = "super-secret-key"), ModelMessage.user("hi"))) }
        }

        assertEquals(ModelProviderErrorCode.AUTHENTICATION_FAILED, error.code)
        assertEquals(401, error.httpStatus)
        assertEquals("invalid_request_error", error.providerErrorType)
        assertEquals("Invalid API key", error.message)
        assertFalse(error.toString().contains("super-secret-key"))
        assertFalse(error.message.orEmpty().contains("super-secret-key"))
    }

    @Test
    fun `http 429 normalizes to a rate limit that the retry layer does not repeat`() {
        val transport = FakeHttpTransport(response = HttpResponseSpec(429, """{"error":{"message":"slow down"}}"""))
        val provider = provider(transport)

        val error = assertFailsWith<ModelProviderError> {
            runSuspend { provider.complete(request(openAiConfig(), ModelMessage.user("hi"))) }
        }

        assertEquals(ModelProviderErrorCode.RATE_LIMITED, error.code)
        // A rate limit is not repeated by the transient-retry layer: RateLimitManager
        // owns the cooldown the provider asked for, and recovery is the configured
        // fallback. "Retryable" therefore means "repeat this exact request", and for a
        // 429 that answer is no.
        assertFalse(error.retryable)
        assertNull(error.retryAfterMillis)
    }

    @Test
    fun `http 429 parses Retry-After seconds`() {
        val transport = FakeHttpTransport(
            response = HttpResponseSpec(
                statusCode = 429,
                body = """{"error":{"message":"slow down"}}""",
                headers = mapOf("Retry-After" to listOf("2")),
            ),
        )
        val provider = provider(transport)

        val error = assertFailsWith<ModelProviderError> {
            runSuspend { provider.complete(request(openAiConfig(), ModelMessage.user("hi"))) }
        }

        assertEquals(ModelProviderErrorCode.RATE_LIMITED, error.code)
        assertEquals(2_000L, error.retryAfterMillis)
    }

    @Test
    fun `http 500 normalizes to a retryable server error`() {
        val transport = FakeHttpTransport(response = HttpResponseSpec(500, "server exploded"))
        val provider = provider(transport)

        val error = assertFailsWith<ModelProviderError> {
            runSuspend { provider.complete(request(openAiConfig(), ModelMessage.user("hi"))) }
        }

        // A plain 5xx is the provider's own fault, not a nameless "provider error".
        assertEquals(ModelProviderErrorCode.SERVER_ERROR, error.code)
        assertTrue(error.retryable)
    }

    @Test
    fun `http 403 normalizes to an authorization failure and is not retryable`() {
        val transport = FakeHttpTransport(
            response = HttpResponseSpec(403, """{"error":{"message":"no access to this model"}}"""),
        )
        val provider = provider(transport)

        val error = assertFailsWith<ModelProviderError> {
            runSuspend { provider.complete(request(openAiConfig(), ModelMessage.user("hi"))) }
        }

        assertEquals(ModelProviderErrorCode.AUTHORIZATION_FAILED, error.code)
        assertFalse(error.retryable)
    }

    @Test
    fun `http 404 normalizes to a model-not-found failure and is not retryable`() {
        val transport = FakeHttpTransport(
            response = HttpResponseSpec(404, """{"error":{"message":"model does not exist","code":"model_not_found"}}"""),
        )
        val provider = provider(transport)

        val error = assertFailsWith<ModelProviderError> {
            runSuspend { provider.complete(request(openAiConfig(), ModelMessage.user("hi"))) }
        }

        assertEquals(ModelProviderErrorCode.MODEL_NOT_FOUND, error.code)
        assertFalse(error.retryable)
    }

    @Test
    fun `a 429 that reports a spent quota is classified apart from a rate limit`() {
        val transport = FakeHttpTransport(
            response = HttpResponseSpec(
                429,
                """{"error":{"message":"You exceeded your current quota","type":"insufficient_quota"}}""",
            ),
        )
        val provider = provider(transport)

        val error = assertFailsWith<ModelProviderError> {
            runSuspend { provider.complete(request(openAiConfig(), ModelMessage.user("hi"))) }
        }

        assertEquals(ModelProviderErrorCode.QUOTA_EXHAUSTED, error.code)
        assertFalse(error.retryable)
    }

    @Test
    fun `transport io errors normalize to network errors`() {
        val transport = FakeHttpTransport()
        transport.onExecute = { throw IOException("connection reset") }
        val provider = provider(transport)

        val error = assertFailsWith<ModelProviderError> {
            runSuspend { provider.complete(request(openAiConfig(), ModelMessage.user("hi"))) }
        }

        assertEquals(ModelProviderErrorCode.NETWORK_ERROR, error.code)
        assertTrue(error.retryable)
    }

    @Test
    fun `transport timeouts normalize to timeout errors`() {
        val transport = FakeHttpTransport()
        transport.onExecute = { throw SocketTimeoutException("timed out") }
        val provider = provider(transport)

        val error = assertFailsWith<ModelProviderError> {
            runSuspend { provider.complete(request(openAiConfig(), ModelMessage.user("hi"))) }
        }

        assertEquals(ModelProviderErrorCode.TIMEOUT, error.code)
    }

    @Test
    fun `connection refused normalizes to a connection failure`() {
        val transport = FakeHttpTransport()
        transport.onExecute = { throw ConnectException("Connection refused") }
        val provider = provider(transport)

        val error = assertFailsWith<ModelProviderError> {
            runSuspend { provider.complete(request(openAiConfig(), ModelMessage.user("hi"))) }
        }

        assertEquals(ModelProviderErrorCode.CONNECTION_FAILED, error.code)
        assertTrue(error.retryable)
    }

    @Test
    fun `nested connection failures are unwrapped`() {
        val transport = FakeHttpTransport()
        transport.onExecute = { throw IOException("failed to connect", ConnectException("Connection refused")) }
        val provider = provider(transport)

        val error = assertFailsWith<ModelProviderError> {
            runSuspend { provider.complete(request(openAiConfig(), ModelMessage.user("hi"))) }
        }

        assertEquals(ModelProviderErrorCode.CONNECTION_FAILED, error.code)
    }

    @Test
    fun `streaming http errors are normalized`() {
        val transport = FakeHttpTransport(
            streamResponse = HttpResponseSpec(503, """{"error":{"message":"unavailable"}}"""),
        )
        val provider = provider(transport)

        val error = assertFailsWith<ModelProviderError> {
            runSuspend { provider.stream(request(openAiConfig(stream = true), ModelMessage.user("hi"))) { } }
        }

        assertEquals(ModelProviderErrorCode.SERVICE_UNAVAILABLE, error.code)
        assertEquals("unavailable", error.message)
    }

    // --- tool-calling readiness -------------------------------------------

    @Test
    fun `tools are serialized when provided`() {
        val transport = FakeHttpTransport(response = HttpResponseSpec(200, SUCCESS_RESPONSE))
        val provider = provider(transport)
        val tools = listOf(
            ModelToolSpec(
                name = "echo",
                description = "Echo text",
                parameters = listOf(
                    ModelToolParameter(name = "message", type = ModelToolParameterType.STRING, required = true),
                ),
            ),
        )
        val modelRequest = ModelRequest(
            config = openAiConfig(),
            messages = listOf(ModelMessage.user("hi")),
            tools = tools,
        )

        runSuspend { provider.complete(modelRequest) }

        val serialized = assertNotNull(requestBody(transport)["tools"])
        val array = assertIs<JsonValue.Arr>(serialized)
        assertEquals(1, array.items.size)
    }

    @Test
    fun `capabilities report streaming and tool calling`() {
        val capabilities = OpenAiCompatibleProvider().capabilities("any-model")

        assertTrue(capabilities.streaming)
        assertTrue(capabilities.toolCalling)
    }

    @Test
    fun `content JSON tool call is recovered as a structured toolCalls payload`() {
        val transport = FakeHttpTransport(
            response = HttpResponseSpec(
                200,
                """
                {
                  "id": "chatcmpl-1",
                  "model": "local-model",
                  "choices": [{
                    "index": 0,
                    "message": {
                      "role": "assistant",
                      "content": "{\"name\":\"read_file\",\"arguments\":{\"path\":\"package.json\"}}"
                    },
                    "finish_reason": "stop"
                  }]
                }
                """.trimIndent(),
            ),
        )
        val response = runSuspend {
            provider(transport).complete(request(openAiConfig(), ModelMessage.user("inspect")))
        }
        assertEquals(1, response.toolCalls.size)
        assertEquals("read_file", response.toolCalls.single().name)
        assertEquals("package.json", response.toolCalls.single().arguments.stringOrNull("path"))
        assertTrue(response.content.isBlank())
        assertEquals(ModelFinishReason.TOOL_CALLS, response.finishReason)
    }

    @Test
    fun `legacy function_call payloads become ModelToolCall`() {
        val transport = FakeHttpTransport(
            response = HttpResponseSpec(
                200,
                """
                {
                  "id": "chatcmpl-1",
                  "model": "local-model",
                  "choices": [{
                    "index": 0,
                    "message": {
                      "role": "assistant",
                      "content": null,
                      "function_call": {
                        "name": "list_directory",
                        "arguments": "{\"path\":\".\"}"
                      }
                    },
                    "finish_reason": "function_call"
                  }]
                }
                """.trimIndent(),
            ),
        )
        val response = runSuspend {
            provider(transport).complete(request(openAiConfig(), ModelMessage.user("inspect")))
        }
        assertEquals("list_directory", response.toolCalls.single().name)
        assertEquals(".", response.toolCalls.single().arguments.stringOrNull("path"))
        assertEquals(ModelFinishReason.TOOL_CALLS, response.finishReason)
    }
}
