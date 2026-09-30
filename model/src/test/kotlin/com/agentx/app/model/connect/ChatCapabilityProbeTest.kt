package com.agentx.app.model.connect

import com.agentx.app.model.DefaultModelGateway
import com.agentx.app.model.FakeHttpTransport
import com.agentx.app.model.SUCCESS_RESPONSE
import com.agentx.app.model.http.HttpResponseSpec
import com.agentx.app.model.preset.ModelApiProtocol
import com.agentx.app.model.preset.ModelPreset
import com.agentx.app.model.preset.ModelProviderType
import com.agentx.app.model.provider.openai.OpenAiCompatibleProvider
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

    private fun probe(transport: FakeHttpTransport): ChatCapabilityProbe = ChatCapabilityProbe(
        gateway = DefaultModelGateway(),
        providerFactory = { protocol ->
            OpenAiCompatibleProvider(
                id = protocol.providerId,
                transport = transport,
                chatPath = protocol.chatPath,
            )
        },
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
            providerFactory = {
                OpenAiCompatibleProvider(id = it.providerId, transport = transport, chatPath = it.chatPath)
            },
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
}
