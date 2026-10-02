package com.agentx.app.model.connect

import com.agentx.app.model.DefaultModelGateway
import com.agentx.app.model.FakeHttpTransport
import com.agentx.app.model.SUCCESS_RESPONSE
import com.agentx.app.model.http.HttpResponseSpec
import com.agentx.app.model.preset.ModelApiProtocol
import com.agentx.app.model.preset.ModelPreset
import com.agentx.app.model.preset.ModelProviderType
import com.agentx.app.model.manager.DefaultModelProviderFactory
import com.agentx.app.model.provider.openai.OpenAiCompatibleProvider
import com.agentx.app.model.preset.EndpointConfig
import com.agentx.app.model.preset.EndpointDiscoveryMode
import com.agentx.app.model.connect.ModelSetupKind
import com.agentx.app.model.runtime.colabPreset
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatCapabilityProbeTest {

    private fun preset(): ModelPreset = colabPreset(
        id = "quick",
        name = "Qwen Coder 14B",
        model = "Qwen/Qwen2.5-Coder-14B-Instruct",
    ).copy(providerType = ModelProviderType.REMOTE_OPENAI_COMPATIBLE)

    /** The real provider selection, with a fake transport: probe and runtime agree. */
    private fun probe(transport: FakeHttpTransport): ChatCapabilityProbe = ChatCapabilityProbe(
        gateway = DefaultModelGateway(),
        providerFactory = { preset -> DefaultModelProviderFactory(transport).create(preset) },
    )

    private fun geminiPreset() = ModelPreset(
        id = "gemini",
        displayName = "Gemini",
        providerType = ModelProviderType.REMOTE_OPENAI_COMPATIBLE,
        modelIdentifier = "gemini-3.5-flash",
        apiProtocol = ModelApiProtocol.GEMINI_NATIVE,
        apiBasePath = "/v1beta",
        endpoint = EndpointConfig(EndpointDiscoveryMode.CONFIGURED_ENDPOINT, "https://generativelanguage.googleapis.com"),
        setupKind = ModelSetupKind.GEMINI.id,
    )

    @Test
    fun `successful chat capability uses a tiny request`() = runBlocking {
        val transport = FakeHttpTransport(response = HttpResponseSpec(200, SUCCESS_RESPONSE))
        val result = probe(transport).verify(
            preset = preset(),
            rootUrl = "https://host.example",
            apiBasePath = "/v1",
            modelId = "Qwen/Qwen2.5-Coder-14B-Instruct",
            credential = null,
        )

        assertTrue(result.succeeded)
        val request = transport.lastRequest!!
        assertTrue(request.url.endsWith("/v1/chat/completions"))
        assertTrue(request.body.orEmpty().contains("\"max_tokens\":1"))
        assertFalse(request.body.orEmpty().contains("sk-"))
    }

    @Test
    fun `401 chat is authentication required`() = runBlocking {
        val transport = FakeHttpTransport(response = HttpResponseSpec(401, """{"error":{"message":"bad key"}}"""))
        val result = probe(transport).verify(
            preset = preset(),
            rootUrl = "https://host.example",
            apiBasePath = "/v1",
            modelId = "qwen",
            credential = "sk-secret",
        )
        assertFalse(result.succeeded)
        assertEquals(DiscoveryFailureKind.AUTHENTICATION_REQUIRED, result.kind)
        assertTrue(result.message.contains("authentication"))
        assertFalse(result.message.contains("sk-secret"))
    }

    @Test
    fun `the live gateway is not the one used for probing`() = runBlocking {
        val live = DefaultModelGateway()
        live.register(
            OpenAiCompatibleProvider(
                id = ModelApiProtocol.OPENAI_COMPATIBLE.providerId,
                transport = FakeHttpTransport(response = HttpResponseSpec(500, "nope")),
            ),
        )
        val transport = FakeHttpTransport(response = HttpResponseSpec(200, SUCCESS_RESPONSE))
        val result = ChatCapabilityProbe(
            gateway = DefaultModelGateway(),
            providerFactory = { preset -> DefaultModelProviderFactory(transport).create(preset) },
        ).verify(
            preset = preset(),
            rootUrl = "https://host.example",
            apiBasePath = "/v1",
            modelId = "qwen",
            credential = null,
        )
        assertTrue(result.succeeded)
        assertTrue(live.provider(ModelApiProtocol.OPENAI_COMPATIBLE.providerId) != null)
    }

    @Test
    fun `a gemini connection is verified with the native generateContent call`() = runBlocking {
        val transport = FakeHttpTransport(
            response = HttpResponseSpec(
                200,
                """{"candidates":[{"content":{"parts":[{"text":"pong"}]},"finishReason":"STOP"}]}""",
            ),
        )

        val result = probe(transport).verify(
            preset = geminiPreset(),
            rootUrl = "https://generativelanguage.googleapis.com",
            apiBasePath = "/v1beta",
            modelId = "gemini-3.5-flash",
            credential = "AIza-secret",
        )

        assertTrue(result.succeeded, result.message)
        val request = transport.lastRequest!!
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent",
            request.url,
        )
        assertEquals("AIza-secret", request.headers["x-goog-api-key"])
        assertFalse(request.headers.containsKey("Authorization"))
        // The endpoint that answered 404 on the compatible surface is never used.
        assertFalse(request.url.contains("/chat/completions"), request.url)
        assertFalse(request.url.contains("/v1beta/openai"), request.url)
        assertTrue(request.body.orEmpty().contains("\"contents\""), request.body.orEmpty())
    }

    @Test
    fun `a 404 on the native path is reported instead of accepted`() = runBlocking {
        val transport = FakeHttpTransport(response = HttpResponseSpec(404, """{"error":{"message":"not found"}}"""))

        val result = probe(transport).verify(
            preset = geminiPreset(),
            rootUrl = "https://generativelanguage.googleapis.com",
            apiBasePath = "/v1beta",
            modelId = "gemini-3.5-flash",
            credential = "AIza-secret",
        )

        assertFalse(result.succeeded)
        assertEquals(404, result.httpStatus)
        assertFalse(result.message.contains("AIza-secret"))
    }

    @Test
    fun `an openai compatible connection still verifies on chat completions`() = runBlocking {
        val transport = FakeHttpTransport(response = HttpResponseSpec(200, SUCCESS_RESPONSE))

        val result = probe(transport).verify(
            preset = preset(),
            rootUrl = "https://api.groq.com/openai",
            apiBasePath = "/v1",
            modelId = "llama-3.3-70b-versatile",
            credential = "gsk-secret",
        )

        assertTrue(result.succeeded)
        val request = transport.lastRequest!!
        assertEquals("https://api.groq.com/openai/v1/chat/completions", request.url)
        assertEquals("Bearer gsk-secret", request.headers["Authorization"])
    }
}
