package dev.forge.ide.model

import dev.forge.ide.model.http.HttpResponseSpec
import dev.forge.ide.model.json.JsonCodec
import dev.forge.ide.model.json.JsonObject
import dev.forge.ide.model.json.JsonValue
import dev.forge.ide.model.json.booleanOrNull
import dev.forge.ide.model.json.numberOrNull
import dev.forge.ide.model.json.objectOrNull
import dev.forge.ide.model.json.stringOrNull
import dev.forge.ide.model.provider.openai.OpenAiCompatibleProvider
import java.io.IOException
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
    fun `http 429 normalizes to a retryable rate limit`() {
        val transport = FakeHttpTransport(response = HttpResponseSpec(429, """{"error":{"message":"slow down"}}"""))
        val provider = provider(transport)

        val error = assertFailsWith<ModelProviderError> {
            runSuspend { provider.complete(request(openAiConfig(), ModelMessage.user("hi"))) }
        }

        assertEquals(ModelProviderErrorCode.RATE_LIMITED, error.code)
        assertTrue(error.retryable)
    }

    @Test
    fun `http 500 normalizes to a retryable provider error`() {
        val transport = FakeHttpTransport(response = HttpResponseSpec(500, "server exploded"))
        val provider = provider(transport)

        val error = assertFailsWith<ModelProviderError> {
            runSuspend { provider.complete(request(openAiConfig(), ModelMessage.user("hi"))) }
        }

        assertEquals(ModelProviderErrorCode.PROVIDER_ERROR, error.code)
        assertTrue(error.retryable)
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
    fun `streaming http errors are normalized`() {
        val transport = FakeHttpTransport(
            streamResponse = HttpResponseSpec(503, """{"error":{"message":"unavailable"}}"""),
        )
        val provider = provider(transport)

        val error = assertFailsWith<ModelProviderError> {
            runSuspend { provider.stream(request(openAiConfig(stream = true), ModelMessage.user("hi"))) { } }
        }

        assertEquals(ModelProviderErrorCode.PROVIDER_ERROR, error.code)
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
}
