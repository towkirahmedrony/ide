package com.agentx.app.model.provider.gemini

import com.agentx.app.model.FakeHttpTransport
import com.agentx.app.model.ModelConfig
import com.agentx.app.model.ModelFinishReason
import com.agentx.app.model.ModelGenerationSettings
import com.agentx.app.model.ModelMessage
import com.agentx.app.model.ModelProviderError
import com.agentx.app.model.ModelProviderErrorCode
import com.agentx.app.model.ModelRequest
import com.agentx.app.model.ModelToolCall
import com.agentx.app.model.ModelToolParameter
import com.agentx.app.model.ModelToolParameterType
import com.agentx.app.model.ModelToolSpec
import com.agentx.app.model.http.HttpResponseSpec
import com.agentx.app.model.json.Json
import com.agentx.app.model.json.JsonCodec
import com.agentx.app.model.json.arrayOrNull
import com.agentx.app.model.json.objectOrNull
import com.agentx.app.model.json.stringOrNull
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Gemini's own API is not the OpenAI-compatible protocol: the model is addressed
 * in the path and the prompt travels in a native payload. These tests pin the
 * request that leaves the app, because sending an OpenAI `/chat/completions` call
 * to Gemini is exactly the 404 this provider exists to prevent.
 */
class GeminiModelProviderTest {

    private val baseUrl = "https://generativelanguage.googleapis.com/v1beta"

    private fun config(model: String = "gemini-3.5-flash", stream: Boolean = false) = ModelConfig(
        providerId = GeminiModelProvider.DEFAULT_ID,
        baseUrl = baseUrl,
        model = model,
        apiKey = "AIza-secret",
        stream = stream,
        generation = ModelGenerationSettings(temperature = 0.2, maxOutputTokens = 64),
    )

    private fun nativeResponse(text: String = "hello", includeFunctionCall: Boolean = false): String {
        val part = if (includeFunctionCall) {
            """{"functionCall":{"name":"read_file","args":{"path":"A.kt"}}}"""
        } else {
            """{"text":"$text"}"""
        }
        return """{"candidates":[{"content":{"role":"model","parts":[$part]},""" +
            """"finishReason":"STOP","modelVersion":"models/gemini-3.5-flash"}],""" +
            """"usageMetadata":{"promptTokenCount":7,"candidatesTokenCount":3,"totalTokenCount":10}}"""
    }

    @Test
    fun `a completion addresses the model in the path, never the compatible surface`() = runBlocking {
        val transport = FakeHttpTransport(response = HttpResponseSpec(200, nativeResponse("pong")))
        val provider = GeminiModelProvider(transport = transport)

        val response = provider.complete(
            ModelRequest(config = config(), messages = listOf(ModelMessage.user("ping"))),
        )

        val request = transport.lastRequest!!
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent",
            request.url,
        )
        assertEquals("POST", request.method)
        // The documented key header, and nothing that would carry the key in a URL.
        assertEquals("AIza-secret", request.headers[GeminiModelProvider.API_KEY_HEADER])
        assertFalse(request.headers.containsKey("Authorization"))
        assertFalse(request.url.contains("AIza-secret"))
        assertFalse(request.url.contains("/chat/completions"), request.url)
        assertFalse(request.url.contains("/v1beta/openai"), request.url)

        val body = JsonCodec.parse(request.body!!).objectOrNull()!!
        assertTrue(body.arrayOrNull("contents") != null, request.body)
        assertTrue(body.objectOrNull("generationConfig") != null, request.body)
        assertTrue(body.arrayOrNull("messages") == null, "a native request carries no messages array")

        assertEquals("pong", response.content)
        assertEquals("gemini-3.5-flash", response.model)
        assertEquals(GeminiModelProvider.DEFAULT_ID, response.providerId)
        assertEquals(ModelFinishReason.STOP, response.finishReason)
        assertEquals(7, response.usage?.promptTokens)
        assertEquals(10, response.usage?.totalTokens)
    }

    @Test
    fun `a models prefixed id is normalized into the path`() = runBlocking {
        val transport = FakeHttpTransport(response = HttpResponseSpec(200, nativeResponse()))
        GeminiModelProvider(transport = transport).complete(
            ModelRequest(config = config(model = "models/gemini-3.5-flash"), messages = listOf(ModelMessage.user("hi"))),
        )

        val url = transport.lastRequest!!.url
        assertTrue(url.endsWith("/models/gemini-3.5-flash:generateContent"), url)
        assertFalse(url.contains("/models/models/"), url)
    }

    @Test
    fun `the system instruction and history are sent as native fields`() = runBlocking {
        val transport = FakeHttpTransport(response = HttpResponseSpec(200, nativeResponse()))
        GeminiModelProvider(transport = transport).complete(
            ModelRequest(
                config = config(),
                messages = listOf(
                    ModelMessage.system("You are AgentX."),
                    ModelMessage.user("first"),
                    ModelMessage.assistant("second"),
                    ModelMessage.user("third"),
                ),
            ),
        )

        val body = JsonCodec.parse(transport.lastRequest!!.body!!).objectOrNull()!!
        // A system prompt is its own field, not a message with role "system".
        assertEquals(
            "You are AgentX.",
            body.objectOrNull("systemInstruction")
                ?.arrayOrNull("parts")
                ?.firstOrNull()
                ?.objectOrNull()
                ?.stringOrNull("text"),
        )
        val roles = body.arrayOrNull("contents")!!.mapNotNull { it.objectOrNull()?.stringOrNull("role") }
        assertEquals(listOf("user", "model", "user"), roles)
    }

    @Test
    fun `tools are declared natively and a function call comes back as a tool call`() = runBlocking {
        val transport = FakeHttpTransport(response = HttpResponseSpec(200, nativeResponse(includeFunctionCall = true)))
        val response = GeminiModelProvider(transport = transport).complete(
            ModelRequest(
                config = config(),
                messages = listOf(ModelMessage.user("read the file")),
                tools = listOf(
                    ModelToolSpec(
                        name = "read_file",
                        description = "Reads a file",
                        parameters = listOf(
                            ModelToolParameter("path", ModelToolParameterType.STRING, "File path", required = true),
                        ),
                    ),
                ),
            ),
        )

        val body = JsonCodec.parse(transport.lastRequest!!.body!!).objectOrNull()!!
        val declaration = body.arrayOrNull("tools")
            ?.firstOrNull()
            ?.objectOrNull()
            ?.arrayOrNull("functionDeclarations")
            ?.firstOrNull()
            ?.objectOrNull()
        assertEquals("read_file", declaration?.stringOrNull("name"))

        val call = response.toolCalls.single()
        assertEquals("read_file", call.name)
        assertEquals("A.kt", call.arguments["path"]?.stringOrNull())
        assertTrue(call.id.isNotBlank(), "a tool call needs an id for its result to answer")
        assertEquals(ModelFinishReason.TOOL_CALLS, response.finishReason)
    }

    @Test
    fun `a tool result answers the function by name`() = runBlocking {
        val transport = FakeHttpTransport(response = HttpResponseSpec(200, nativeResponse()))
        val call = ModelToolCall(id = "call-1", name = "read_file", arguments = mapOf("path" to Json.of("A.kt")))
        GeminiModelProvider(transport = transport).complete(
            ModelRequest(
                config = config(),
                messages = listOf(
                    ModelMessage.user("read it"),
                    ModelMessage.assistant("", toolCalls = listOf(call)),
                    ModelMessage.tool(toolCallId = "call-1", content = "file body"),
                ),
            ),
        )

        val contents = JsonCodec.parse(transport.lastRequest!!.body!!).objectOrNull()!!.arrayOrNull("contents")!!
        val functionResponse = contents
            .mapNotNull { it.objectOrNull() }
            .flatMap { content -> content.arrayOrNull("parts").orEmpty().mapNotNull { it.objectOrNull() } }
            .mapNotNull { part -> part.objectOrNull("functionResponse") }
            .single()
        // Gemini matches a result by name, so the name is recovered from the call.
        assertEquals("read_file", functionResponse.stringOrNull("name"))
        assertEquals(
            "file body",
            functionResponse.objectOrNull("response")?.stringOrNull("result"),
        )
    }

    @Test
    fun `streaming is not announced, and still returns the whole answer`() = runBlocking {
        val provider = GeminiModelProvider(
            transport = FakeHttpTransport(response = HttpResponseSpec(200, nativeResponse("streamed"))),
        )

        assertFalse(provider.capabilities("gemini-3.5-flash").streaming)
        assertTrue(provider.capabilities("gemini-3.5-flash").toolCalling)
        assertTrue(provider.capabilities("gemini-3.5-flash").systemMessages)

        val response = provider.stream(
            ModelRequest(config = config(), messages = listOf(ModelMessage.user("ping"))),
        ) {}
        assertEquals("streamed", response.content)
    }

    @Test
    fun `a 404 is a failure, not an accepted connection`() = runBlocking {
        val transport = FakeHttpTransport(
            response = HttpResponseSpec(404, """{"error":{"code":404,"status":"NOT_FOUND","message":"not found"}}"""),
        )

        val error = assertNotNull(
            runCatching {
                GeminiModelProvider(transport = transport).complete(
                    ModelRequest(config = config(), messages = listOf(ModelMessage.user("ping"))),
                )
            }.exceptionOrNull(),
        )

        val providerError = error as ModelProviderError
        assertEquals(404, providerError.httpStatus)
        assertEquals(ModelProviderErrorCode.UNSUPPORTED, providerError.code)
        assertTrue(providerError.message.orEmpty().contains("not found"), providerError.message)
        assertFalse(providerError.message.orEmpty().contains("AIza-secret"))
    }

    @Test
    fun `an error body is surfaced without the key`() = runBlocking {
        val transport = FakeHttpTransport(
            response = HttpResponseSpec(400, """{"error":{"status":"INVALID_ARGUMENT","message":"bad request"}}"""),
        )

        val error = runCatching {
            GeminiModelProvider(transport = transport).complete(
                ModelRequest(config = config(), messages = listOf(ModelMessage.user("ping"))),
            )
        }.exceptionOrNull() as ModelProviderError

        assertEquals(ModelProviderErrorCode.INVALID_REQUEST, error.code)
        assertEquals("INVALID_ARGUMENT", error.providerErrorType)
        assertFalse(error.toString().contains("AIza-secret"))
    }

    @Test
    fun `a blocked prompt is reported as an invalid response`() = runBlocking {
        val transport = FakeHttpTransport(
            response = HttpResponseSpec(200, """{"promptFeedback":{"blockReason":"SAFETY"}}"""),
        )

        val error = runCatching {
            GeminiModelProvider(transport = transport).complete(
                ModelRequest(config = config(), messages = listOf(ModelMessage.user("ping"))),
            )
        }.exceptionOrNull() as ModelProviderError

        assertEquals(ModelProviderErrorCode.INVALID_RESPONSE, error.code)
        assertTrue(error.message.orEmpty().contains("SAFETY"), error.message)
    }

    @Test
    fun `the model is addressed by the path and not repeated in the body`() = runBlocking {
        val transport = FakeHttpTransport(response = HttpResponseSpec(200, nativeResponse()))
        GeminiModelProvider(transport = transport).complete(
            ModelRequest(config = config(), messages = listOf(ModelMessage.user("ping"))),
        )

        val body = JsonCodec.parse(transport.lastRequest!!.body!!).objectOrNull()!!
        assertTrue(body.stringOrNull("model") == null, "the model belongs in the path, not the body")
        assertTrue(body.arrayOrNull("contents") != null, transport.lastRequest!!.body!!)
    }
}
