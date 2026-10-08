package com.agentx.app.model.catalog

import com.agentx.app.core.ForgeResult
import com.agentx.app.model.FakeHttpTransport
import com.agentx.app.model.ModelConfig
import com.agentx.app.model.ModelFinishReason
import com.agentx.app.model.ModelMessage
import com.agentx.app.model.ModelRequest
import com.agentx.app.model.ModelStreamEvent
import com.agentx.app.model.SUCCESS_RESPONSE
import com.agentx.app.model.capability.InMemoryModelCapabilityRegistry
import com.agentx.app.model.http.HttpResponseSpec
import com.agentx.app.model.json.JsonCodec
import com.agentx.app.model.json.booleanOrNull
import com.agentx.app.model.json.objectOrNull
import com.agentx.app.model.json.stringOrNull
import com.agentx.app.model.manager.DefaultModelDiscoverySource
import com.agentx.app.model.manager.ModelConnectionKind
import com.agentx.app.model.manager.connectionKind
import com.agentx.app.model.preset.InMemoryModelSecretStore
import com.agentx.app.model.preset.ModelProviderIds
import com.agentx.app.model.preset.StoreBackedModelCredentialResolver
import com.agentx.app.model.provider.openai.OpenAiCompatibleProvider
import com.agentx.app.model.runSuspend
import com.agentx.app.model.runtime.customPreset
import com.agentx.app.model.runtime.freeLlmApiPreset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FreeLLMAPI as the first remote provider on the API AI path.
 *
 * It reuses the existing OpenAI-compatible provider for chat and the existing
 * `/models` support for discovery — there is no FreeLLMAPI-specific client, parser
 * or credential store, and these tests exist to prove that: the gateway is an
 * ordinary [ModelConfig] with provider identity [ModelProviderIds.FREELMAPI],
 * reached through the same provider the runtime chats through.
 *
 * The gateway address is configuration, not a constant in the networking layer, so
 * every test below supplies it on the connection.
 */
class FreeLlmApiProviderIntegrationTest {

    private fun connection(
        baseUrl: String = GATEWAY_BASE_URL,
        model: String = "gemini-2.5-flash",
        apiKey: String? = API_KEY,
    ) = ModelConfig(
        providerId = ModelProviderIds.FREELMAPI,
        baseUrl = baseUrl,
        model = model,
        apiKey = apiKey,
    )

    // --- connection classification / discovery ownership -------------------

    @Test
    fun `freeLLMAPI is an api connection listed through the openai compatible provider`() {
        val source = DefaultModelDiscoverySource(transport = FakeHttpTransport())

        // FreeLLMAPI speaks the compatible `/models` route, so its models are listed
        // through the very provider that serves its chat ...
        assertNotNull(source.create(ModelProviderIds.FREELMAPI, connection()))
        // ... like Groq, and unlike a hosted identity this build does not drive.
        assertNotNull(source.create(ModelProviderIds.GROQ, connection()))
        assertNull(source.create(ModelProviderIds.MISTRAL, connection()))

        // Its own provider identity is what keeps it apart from a user-run endpoint
        // that happens to speak the same protocol.
        assertTrue(RemoteModelCatalogFactory().supports(ModelProviderIds.FREELMAPI))
        assertFalse(RemoteModelCatalogFactory().supports(ModelProviderIds.MISTRAL))

        assertEquals(ModelProviderIds.FREELMAPI, freeLlmApiPreset().providerId)
        assertEquals(ModelConnectionKind.API, freeLlmApiPreset().connectionKind)
        assertEquals(ModelProviderIds.OPENAI_COMPATIBLE, customPreset().providerId)
        assertEquals(ModelConnectionKind.LOCAL_CUSTOM, customPreset().connectionKind)
    }

    // --- model discovery ----------------------------------------------------

    @Test
    fun `freeLLMAPI models are discovered from the gateway and reach the picker`() {
        val transport = FakeHttpTransport(response = HttpResponseSpec(200, MODELS_RESPONSE))
        val capabilities = InMemoryModelCapabilityRegistry()
        val store = InMemoryModelCatalogStore()
        val registry = DefaultModelCatalogRegistry(
            connections = { mapOf(ModelProviderIds.FREELMAPI to connection()) },
            factory = RemoteModelCatalogFactory(
                transport = transport,
                clock = { 0L },
                store = store,
                capabilityRegistry = capabilities,
                discoverySource = DefaultModelDiscoverySource(transport)::create,
            ),
            store = store,
            capabilityRegistry = capabilities,
        )

        val refreshed = runSuspend { registry.refresh(ModelProviderIds.FREELMAPI, force = true) }

        assertTrue(refreshed is ForgeResult.Success, refreshed.toString())
        // The existing OpenAI-compatible reader normalizes the response: `data[].id`
        // becomes the id that is sent to chat, and a family the text runtime cannot
        // drive is dropped rather than offered as a choice.
        assertEquals(
            listOf("gemini-2.5-flash", "openai/gpt-oss-20b"),
            registry.availableModels(ModelProviderIds.FREELMAPI).map { it.id },
        )
        // The gateway address comes from the connection, and the credential is the
        // bearer token the same provider uses for chat — never in the URL.
        val request = assertNotNull(transport.lastRequest)
        assertEquals("$GATEWAY_BASE_URL/models", request.url)
        assertEquals("Bearer $API_KEY", request.headers["Authorization"])
        assertFalse(request.url.contains(API_KEY))
    }

    // --- per-request model selection and routing ----------------------------

    @Test
    fun `a freeLLMAPI request uses the gateway base url the selected model and bearer auth`() {
        listOf("gemini-2.5-flash", "openai/gpt-oss-20b").forEach { selected ->
            val transport = FakeHttpTransport(response = HttpResponseSpec(200, SUCCESS_RESPONSE))
            val provider = OpenAiCompatibleProvider(id = ModelProviderIds.FREELMAPI, transport = transport)

            val response = runSuspend {
                provider.complete(
                    ModelRequest(
                        config = connection(model = selected),
                        messages = listOf(ModelMessage.user("hello")),
                    ),
                )
            }

            val request = assertNotNull(transport.lastRequest)
            assertEquals("$GATEWAY_BASE_URL/chat/completions", request.url)
            assertEquals("Bearer $API_KEY", request.headers["Authorization"])
            assertFalse(request.url.contains(API_KEY))
            // The selected model id is what is sent — never an implicit gateway default.
            val body = assertNotNull(JsonCodec.parse(assertNotNull(request.body)).objectOrNull())
            assertEquals(selected, body.stringOrNull("model"))
            assertEquals(ModelProviderIds.FREELMAPI, response.providerId)
        }
    }

    @Test
    fun `the gateway address is configuration, so another endpoint is routed to instead`() {
        val transport = FakeHttpTransport(response = HttpResponseSpec(200, SUCCESS_RESPONSE))
        val provider = OpenAiCompatibleProvider(id = ModelProviderIds.FREELMAPI, transport = transport)

        runSuspend {
            provider.complete(
                ModelRequest(
                    config = connection(baseUrl = "https://another-gateway.internal/v1"),
                    messages = listOf(ModelMessage.user("hello")),
                ),
            )
        }

        assertEquals("https://another-gateway.internal/v1/chat/completions", transport.lastRequest?.url)
    }

    // --- streaming ----------------------------------------------------------

    @Test
    fun `a freeLLMAPI stream reuses the existing sse pipeline`() {
        val transport = FakeHttpTransport(
            streamLines = listOf(
                """data: {"model":"gemini-2.5-flash","choices":[{"index":0,"delta":{"role":"assistant","content":"Hel"}}]}""",
                """data: {"model":"gemini-2.5-flash","choices":[{"index":0,"delta":{"content":"lo"}}]}""",
                """data: {"model":"gemini-2.5-flash","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":3,"completion_tokens":2,"total_tokens":5}}""",
                "data: [DONE]",
            ),
        )
        val provider = OpenAiCompatibleProvider(id = ModelProviderIds.FREELMAPI, transport = transport)
        val events = mutableListOf<ModelStreamEvent>()

        val response = runSuspend {
            provider.stream(
                ModelRequest(
                    config = connection().copy(stream = true),
                    messages = listOf(ModelMessage.user("hello")),
                ),
            ) { events += it }
        }

        assertEquals("Hello", response.content)
        assertEquals(ModelFinishReason.STOP, response.finishReason)
        assertEquals(listOf("Hel", "lo"), events.filterIsInstance<ModelStreamEvent.TextDelta>().map { it.text })
        assertTrue(events.any { it is ModelStreamEvent.Started })
        assertTrue(events.any { it is ModelStreamEvent.Completed })
        val request = assertNotNull(transport.lastRequest)
        assertEquals("$GATEWAY_BASE_URL/chat/completions", request.url)
        assertEquals("Bearer $API_KEY", request.headers["Authorization"])
        assertEquals("text/event-stream, application/json", request.headers["Accept"])
        val body = assertNotNull(JsonCodec.parse(assertNotNull(request.body)).objectOrNull())
        assertTrue(body.booleanOrNull("stream") == true)
    }

    // --- credential handling ------------------------------------------------

    @Test
    fun `the freeLLMAPI api key is resolved from the existing credential store`() {
        val secrets = InMemoryModelSecretStore()
        runSuspend { secrets.put("freellmapi-credential", API_KEY) }
        val resolver = StoreBackedModelCredentialResolver(secrets)

        // The key lives in the shared secret store; a FreeLLMAPI preset carries only
        // a reference to it.
        val preset = freeLlmApiPreset(credentialRef = "freellmapi-credential")
        assertEquals(API_KEY, runSuspend { resolver.resolve(preset) })

        // A preset with no stored reference resolves nothing: the key is never
        // embedded in the saved connection.
        assertNull(runSuspend { resolver.resolve(freeLlmApiPreset(credentialRef = null)) })
    }

    private companion object {
        /**
         * The deployed FreeLLMAPI gateway. It is *configuration*: the tests pass it
         * on a connection, and the second gateway in
         * `the gateway address is configuration` proves nothing in the networking
         * layer depends on this particular value.
         */
        const val GATEWAY_BASE_URL = "https://agentx-vgtx.onrender.com/v1"

        /** A test key: never a real credential, and asserted to stay out of URLs. */
        const val API_KEY = "llm-test-key-not-a-real-secret"

        val MODELS_RESPONSE = """
            {
              "object": "list",
              "data": [
                { "id": "gemini-2.5-flash", "owned_by": "google" },
                { "id": "openai/gpt-oss-20b", "owned_by": "groq" },
                { "id": "whisper-large-v3", "owned_by": "groq" }
              ]
            }
        """.trimIndent()
    }
}
