package com.agentx.app.model.discovery

import com.agentx.app.model.FakeHttpTransport
import com.agentx.app.model.ModelConfig
import com.agentx.app.model.capability.CapabilitySupport
import com.agentx.app.model.capability.InMemoryModelCapabilityRegistry
import com.agentx.app.model.capability.ModelCapability
import com.agentx.app.model.catalog.DefaultModelCatalogRegistry
import com.agentx.app.model.catalog.InMemoryModelCatalogStore
import com.agentx.app.model.catalog.ModelCatalogState
import com.agentx.app.model.catalog.RemoteModelCatalogFactory
import com.agentx.app.model.connect.DiscoveryFailureKind
import com.agentx.app.model.http.HttpResponseSpec
import com.agentx.app.model.preset.ModelProviderIds
import com.agentx.app.model.provider.gemini.GeminiModelProvider
import com.agentx.app.model.provider.openai.OpenAiCompatibleProvider
import com.agentx.app.model.runSuspend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Provider-owned model discovery.
 *
 * The provider reports and nothing else: it is asked for what a connection can
 * list, it normalizes what came back, and it never assigns a role or touches a
 * saved configuration. Every case here is about what discovery does with a real
 * provider answer — pagination, duplicates, non-chat families, and the difference
 * between "no list exists" and "the list could not be read".
 */
class ProviderModelDiscoveryTest {

    private val geminiBaseUrl = "https://generativelanguage.googleapis.com/v1beta"

    private fun gemini(transport: FakeHttpTransport) =
        GeminiModelProvider(id = ModelProviderIds.GEMINI, transport = transport)

    private fun compatible(id: String, transport: FakeHttpTransport) =
        OpenAiCompatibleProvider(id = id, transport = transport)

    private fun geminiConfig(model: String = "gemini-x", apiKey: String? = "secret-key") = ModelConfig(
        providerId = ModelProviderIds.GEMINI,
        baseUrl = geminiBaseUrl,
        model = model,
        apiKey = apiKey,
    )

    private fun compatibleConfig(
        providerId: String = ModelProviderIds.GROQ,
        baseUrl: String = "https://api.groq.com/openai/v1",
        model: String = "shared-model",
        apiKey: String? = "secret-key",
    ) = ModelConfig(providerId = providerId, baseUrl = baseUrl, model = model, apiKey = apiKey)

    // --- Gemini -------------------------------------------------------------

    @Test
    fun `gemini discovery pages the list and normalizes prefixed identifiers`() = runSuspend {
        val transport = FakeHttpTransport(
            executeHandler = { request ->
                if (request.url.contains("pageToken=")) {
                    HttpResponseSpec(
                        statusCode = 200,
                        body = """
                            {
                              "models": [
                                { "name": "models/gemini-3.5-flash", "displayName": "Gemini 3.5 Flash" },
                                { "name": "models/gemini-embedding-001",
                                  "supportedGenerationMethods": ["embedContent"] }
                              ]
                            }
                        """.trimIndent(),
                    )
                } else {
                    HttpResponseSpec(
                        statusCode = 200,
                        body = """
                            {
                              "models": [
                                { "name": "models/gemini-3.5-flash",
                                  "displayName": "Gemini 3.5 Flash",
                                  "inputTokenLimit": 1048576,
                                  "outputTokenLimit": 8192,
                                  "supportedGenerationMethods": ["generateContent", "streamGenerateContent"] },
                                { "name": "models/imagen-4.0-generate-001",
                                  "supportedGenerationMethods": ["predict"] }
                              ],
                              "nextPageToken": "page-2"
                            }
                        """.trimIndent(),
                    )
                }
            },
        )

        val outcome = gemini(transport).discoverModels(geminiConfig())
        val discovered = assertNotNull(outcome as? ModelDiscoveryOutcome.Discovered, "expected a discovery, got $outcome")

        // The first page, then the page token: the whole list is read.
        assertEquals(2, transport.requests.size)
        assertEquals("$geminiBaseUrl/models", transport.requests.first().url)
        assertTrue(transport.requests.last().url.contains("pageToken=page-2"), transport.requests.last().url)

        // `models/<id>` becomes the bare id a chat request sends, the repeated id
        // across pages collapses to one entry, and non-chat families are dropped.
        assertEquals(listOf("gemini-3.5-flash"), discovered.models.map { it.modelId })
        assertEquals(4, discovered.reportedCount)
        assertEquals(2, discovered.rejected.size)
        assertTrue(discovered.rejected.all { it.contains("does not report generateContent") })
        assertFalse(discovered.truncated)

        val model = discovered.models.single()
        assertEquals("Gemini 3.5 Flash", model.displayName)
        assertEquals(1048576, model.contextWindowTokens)
        assertEquals(8192, model.maxOutputTokens)
        // The provider's own generation methods are kept as metadata, never as a
        // capability claim.
        assertEquals(
            "generateContent,streamGenerateContent",
            model.providerMetadata[ModelListParsing.SUPPORTED_METHODS_KEY],
        )
    }

    @Test
    fun `gemini discovery keeps the documented endpoint free of a query string`() = runSuspend {
        val transport = FakeHttpTransport(
            response = HttpResponseSpec(statusCode = 200, body = """{"models":[{"name":"models/gemini-x"}]}"""),
        )

        gemini(transport).discoverModels(geminiConfig())

        assertEquals("$geminiBaseUrl/models", transport.lastRequest?.url)
    }

    @Test
    fun `gemini discovery authenticates with the api-key header and never the url`() = runSuspend {
        val transport = FakeHttpTransport(
            response = HttpResponseSpec(statusCode = 200, body = """{"models":[{"name":"models/gemini-x"}]}"""),
        )

        gemini(transport).discoverModels(geminiConfig())

        val request = assertNotNull(transport.lastRequest)
        assertEquals("secret-key", request.headers["x-goog-api-key"])
        assertFalse(request.headers.containsKey("Authorization"))
        assertFalse(request.url.contains("secret-key"))
    }

    // --- Groq / OpenAI-compatible -------------------------------------------

    @Test
    fun `compatible discovery normalizes the list and drops models the text runtime cannot drive`() = runSuspend {
        val transport = FakeHttpTransport(
            response = HttpResponseSpec(
                statusCode = 200,
                body = """
                    {
                      "object": "list",
                      "data": [
                        { "id": "llama-3.3-70b-versatile", "active": true,
                          "context_window": 131072, "owned_by": "meta" },
                        { "id": "whisper-large-v3", "active": true },
                        { "id": "playai-tts" }
                      ]
                    }
                """.trimIndent(),
            ),
        )
        val connection = compatibleConfig()

        val outcome = compatible(ModelProviderIds.GROQ, transport).discoverModels(connection)
        val discovered = assertNotNull(outcome as? ModelDiscoveryOutcome.Discovered)

        assertEquals(listOf("llama-3.3-70b-versatile"), discovered.models.map { it.modelId })
        assertEquals(3, discovered.reportedCount)
        assertEquals(2, discovered.rejected.size)

        val model = discovered.models.single()
        assertEquals(131072, model.contextWindowTokens)
        assertEquals("meta", model.providerOwnedBy)
        assertFalse(model.local)
    }

    @Test
    fun `a local endpoint lists its models and reports them as local`() = runSuspend {
        val transport = FakeHttpTransport(
            response = HttpResponseSpec(statusCode = 200, body = """{"data":[{"id":"qwen2.5-coder:14b"}]}"""),
        )
        val connection = compatibleConfig(
            providerId = ModelProviderIds.OPENAI_COMPATIBLE,
            baseUrl = "http://localhost:11434/v1",
        )

        val outcome = compatible(ModelProviderIds.OPENAI_COMPATIBLE, transport).discoverModels(connection)
        val discovered = assertNotNull(outcome as? ModelDiscoveryOutcome.Discovered)

        assertEquals("http://localhost:11434/v1/models", transport.lastRequest?.url)
        assertTrue(discovered.models.single().local, "a loopback model must be reported as local")
    }

    @Test
    fun `a repeated id collapses to one entry`() = runSuspend {
        val transport = FakeHttpTransport(
            response = HttpResponseSpec(
                statusCode = 200,
                body = """{"data":[{"id":"dup"},{"id":"dup"},{"id":"other"}]}""",
            ),
        )

        val outcome = compatible(ModelProviderIds.GROQ, transport).discoverModels(compatibleConfig())
        val discovered = assertNotNull(outcome as? ModelDiscoveryOutcome.Discovered)

        assertEquals(listOf("dup", "other"), discovered.models.map { it.modelId })
        assertEquals(3, discovered.reportedCount)
    }

    // --- outcomes -----------------------------------------------------------

    @Test
    fun `a missing model list is unavailable, not an empty catalog`() = runSuspend {
        val connection = compatibleConfig(
            providerId = ModelProviderIds.OPENAI_COMPATIBLE,
            baseUrl = "http://localhost:11434/v1",
        )

        listOf(404, 405).forEach { status ->
            val transport = FakeHttpTransport(response = HttpResponseSpec(statusCode = status, body = "no list here"))
            val outcome = compatible(ModelProviderIds.OPENAI_COMPATIBLE, transport).discoverModels(connection)

            val unavailable = assertNotNull(
                outcome as? ModelDiscoveryOutcome.Unavailable,
                "HTTP $status must be an unavailable discovery, got $outcome",
            )
            assertEquals(ModelDiscoveryOutcome.REASON_NO_MODEL_LIST, unavailable.reason)
        }
    }

    @Test
    fun `provider discovery surfaces each failure kind instead of an empty list`() = runSuspend {
        val connection = compatibleConfig()

        val cases = listOf(
            401 to DiscoveryFailureKind.AUTHENTICATION_REQUIRED,
            403 to DiscoveryFailureKind.AUTHENTICATION_REQUIRED,
            429 to DiscoveryFailureKind.RATE_LIMITED,
            500 to DiscoveryFailureKind.SERVER_ERROR,
            503 to DiscoveryFailureKind.SERVER_ERROR,
        )
        cases.forEach { (status, kind) ->
            val transport = FakeHttpTransport(response = HttpResponseSpec(statusCode = status, body = "nope"))
            val outcome = compatible(ModelProviderIds.GROQ, transport).discoverModels(connection)
            val failed = assertNotNull(
                outcome as? ModelDiscoveryOutcome.Failed,
                "HTTP $status must fail discovery, got $outcome",
            )
            assertEquals(kind, failed.kind, "HTTP $status")
            assertEquals(status, failed.httpStatus)
        }

        val malformed = FakeHttpTransport(response = HttpResponseSpec(statusCode = 200, body = "<html>not json</html>"))
        val outcome = compatible(ModelProviderIds.GROQ, malformed).discoverModels(connection)
        assertEquals(DiscoveryFailureKind.MALFORMED, assertNotNull(outcome as? ModelDiscoveryOutcome.Failed).kind)
    }

    @Test
    fun `a transport failure is reported as unreachable and never as an empty list`() = runSuspend {
        val transport = FakeHttpTransport(onExecute = { throw java.net.ConnectException("connection refused") })

        val outcome = compatible(ModelProviderIds.GROQ, transport).discoverModels(compatibleConfig())

        assertEquals(DiscoveryFailureKind.UNREACHABLE, assertNotNull(outcome as? ModelDiscoveryOutcome.Failed).kind)
    }

    @Test
    fun `an entry without an id is dropped with a reason rather than guessed at`() = runSuspend {
        val transport = FakeHttpTransport(
            response = HttpResponseSpec(
                statusCode = 200,
                body = """{"data":[{"id":"good-model"},{"object":"model"},{"id":""}]}""",
            ),
        )

        val outcome = compatible(ModelProviderIds.GROQ, transport).discoverModels(compatibleConfig())
        val discovered = assertNotNull(outcome as? ModelDiscoveryOutcome.Discovered)

        assertEquals(listOf("good-model"), discovered.models.map { it.modelId })
        assertEquals(3, discovered.reportedCount)
        assertEquals(2, discovered.rejected.size)
        assertTrue(discovered.rejected.all { it.contains("no id or name") })
    }

    // --- listModels convenience --------------------------------------------

    @Test
    fun `listModels returns normalized descriptors`() = runSuspend {
        val transport = FakeHttpTransport(
            response = HttpResponseSpec(
                statusCode = 200,
                body = """{"data":[{"id":"llama-3.1-8b-instant","context_window":131072}]}""",
            ),
        )
        val connection = compatibleConfig()

        val descriptor = compatible(ModelProviderIds.GROQ, transport).listModels(connection).single()

        assertEquals("llama-3.1-8b-instant", descriptor.id)
        assertEquals("llama-3.1-8b-instant", descriptor.label)
        assertEquals(ModelProviderIds.GROQ, descriptor.providerId)
        assertEquals(131072, descriptor.contextWindow)
    }

    @Test
    fun `listModels stays empty when a provider publishes no list`() = runSuspend {
        val transport = FakeHttpTransport(response = HttpResponseSpec(statusCode = 404, body = "no list"))
        val connection = compatibleConfig(
            providerId = ModelProviderIds.OPENAI_COMPATIBLE,
            baseUrl = "http://localhost:11434/v1",
        )
        val provider = compatible(ModelProviderIds.OPENAI_COMPATIBLE, transport)

        // Empty, never invented — and the structured outcome still carries the reason.
        assertEquals(emptyList(), provider.listModels(connection))
        assertTrue(provider.discoverModels(connection) is ModelDiscoveryOutcome.Unavailable)
    }

    // --- normalization into the catalog and the capability registry ---------

    /** A catalog registry whose discovery is owned by the providers, as in the app. */
    private fun registryWith(
        connections: Map<String, ModelConfig>,
        transport: FakeHttpTransport,
        capabilities: InMemoryModelCapabilityRegistry,
    ) = DefaultModelCatalogRegistry(
        connections = { connections },
        factory = RemoteModelCatalogFactory(
            transport = transport,
            clock = { 0L },
            capabilityRegistry = capabilities,
            discoverySource = { providerId, connection ->
                when (providerId) {
                    ModelProviderIds.GEMINI -> ModelDiscovery {
                        GeminiModelProvider(id = providerId, transport = transport).discoverModels(connection)
                    }
                    ModelProviderIds.GROQ,
                    ModelProviderIds.OPENAI_COMPATIBLE,
                    -> ModelDiscovery {
                        OpenAiCompatibleProvider(id = providerId, transport = transport).discoverModels(connection)
                    }
                    else -> null
                }
            },
        ),
        store = InMemoryModelCatalogStore(),
        capabilityRegistry = capabilities,
    )

    @Test
    fun `discovered models reach the capability registry with unknown capabilities`() = runSuspend {
        val transport = FakeHttpTransport(
            response = HttpResponseSpec(
                statusCode = 200,
                body = """{"models":[{"name":"models/gemini-9-experimental"}]}""",
            ),
        )
        val capabilities = InMemoryModelCapabilityRegistry()

        val registry = registryWith(
            connections = mapOf(ModelProviderIds.GEMINI to geminiConfig("gemini-9-experimental")),
            transport = transport,
            capabilities = capabilities,
        )
        registry.refresh(ModelProviderIds.GEMINI, force = true)

        val profile = assertNotNull(capabilities.get(ModelProviderIds.GEMINI, "gemini-9-experimental"))
        // Nothing about tool calling or streaming is claimed from a list entry, so a
        // tool-enabled role must not take this model.
        assertEquals(CapabilitySupport.UNKNOWN, profile.toolCalling)
        assertEquals(CapabilitySupport.UNKNOWN, profile.streaming)
        assertFalse(profile.known)
        assertFalse(
            capabilities.supports(ModelProviderIds.GEMINI, "gemini-9-experimental", ModelCapability.TOOL_CALLING),
        )
    }

    @Test
    fun `two providers may list the same model id without collision`() = runSuspend {
        val transport = FakeHttpTransport(
            response = HttpResponseSpec(statusCode = 200, body = """{"data":[{"id":"shared-model"}]}"""),
        )
        val capabilities = InMemoryModelCapabilityRegistry()
        val connections = mapOf(
            ModelProviderIds.GROQ to compatibleConfig(),
            ModelProviderIds.OPENAI_COMPATIBLE to compatibleConfig(
                providerId = ModelProviderIds.OPENAI_COMPATIBLE,
                baseUrl = "http://localhost:11434/v1",
            ),
        )

        val registry = registryWith(connections, transport, capabilities)
        registry.refresh(ModelProviderIds.GROQ, force = true)
        registry.refresh(ModelProviderIds.OPENAI_COMPATIBLE, force = true)

        // Identity is providerId + modelId: the same id from two providers is two
        // entries, and each refresh only touched its own provider's catalog.
        assertNotNull(capabilities.get(ModelProviderIds.GROQ, "shared-model"))
        assertNotNull(capabilities.get(ModelProviderIds.OPENAI_COMPATIBLE, "shared-model"))
        assertEquals(listOf("shared-model"), registry.availableModels(ModelProviderIds.GROQ).map { it.id })
        assertEquals(
            listOf("shared-model"),
            registry.availableModels(ModelProviderIds.OPENAI_COMPATIBLE).map { it.id },
        )
        assertTrue(assertNotNull(capabilities.get(ModelProviderIds.OPENAI_COMPATIBLE, "shared-model")).local)
        assertFalse(assertNotNull(capabilities.get(ModelProviderIds.GROQ, "shared-model")).local)
    }

    @Test
    fun `a discovery failure preserves the last known catalog`() = runSuspend {
        val capabilities = InMemoryModelCapabilityRegistry()
        val connection = geminiConfig("gemini-9-experimental")
        val store = InMemoryModelCatalogStore()

        fun registry(transport: FakeHttpTransport) = DefaultModelCatalogRegistry(
            connections = { mapOf(ModelProviderIds.GEMINI to connection) },
            factory = RemoteModelCatalogFactory(
                transport = transport,
                clock = { 0L },
                capabilityRegistry = capabilities,
                discoverySource = { providerId, config ->
                    ModelDiscovery {
                        GeminiModelProvider(id = providerId, transport = transport).discoverModels(config)
                    }
                },
            ),
            store = store,
            capabilityRegistry = capabilities,
        )

        val ok = FakeHttpTransport(
            response = HttpResponseSpec(
                statusCode = 200,
                body = """
                    {"models":[
                      {"name":"models/gemini-9-experimental"},
                      {"name":"models/gemini-9-pro"}
                    ]}
                """.trimIndent(),
            ),
        )
        val healthy = registry(ok)
        healthy.refresh(ModelProviderIds.GEMINI, force = true)
        assertEquals(2, healthy.availableModels(ModelProviderIds.GEMINI).size)

        // The provider now fails. The catalog it reported last is kept, and the
        // failure is exposed as its own state rather than as an empty catalog.
        val broken = FakeHttpTransport(response = HttpResponseSpec(statusCode = 503, body = "unavailable"))
        val failing = registry(broken)
        failing.refresh(ModelProviderIds.GEMINI, force = true)

        assertEquals(2, failing.availableModels(ModelProviderIds.GEMINI).size)
        assertTrue(failing.lastDiscovery(ModelProviderIds.GEMINI) is ModelCatalogState.Failed)
        assertNotNull(store.load(ModelProviderIds.GEMINI))
    }

    @Test
    fun `a persisted catalog survives a restart`() = runSuspend {
        val capabilities = InMemoryModelCapabilityRegistry()
        val store = InMemoryModelCatalogStore()
        val connection = geminiConfig("gemini-9-experimental")
        val transport = FakeHttpTransport(
            response = HttpResponseSpec(
                statusCode = 200,
                body = """{"models":[{"name":"models/gemini-9-experimental"}]}""",
            ),
        )

        fun factory() = RemoteModelCatalogFactory(
            transport = transport,
            clock = { 0L },
            capabilityRegistry = capabilities,
            discoverySource = { providerId, config ->
                ModelDiscovery {
                    GeminiModelProvider(id = providerId, transport = transport).discoverModels(config)
                }
            },
        )

        DefaultModelCatalogRegistry(
            connections = { mapOf(ModelProviderIds.GEMINI to connection) },
            factory = factory(),
            store = store,
            capabilityRegistry = capabilities,
        ).refresh(ModelProviderIds.GEMINI, force = true)

        // A restart: a new capability registry, a new catalog registry, the same
        // store. Restoring never asks the provider, so only persistence can explain
        // what is known afterwards.
        val restartedCapabilities = InMemoryModelCapabilityRegistry()
        val restarted = DefaultModelCatalogRegistry(
            connections = { mapOf(ModelProviderIds.GEMINI to connection) },
            factory = factory(),
            store = store,
            capabilityRegistry = restartedCapabilities,
        )
        restarted.restore()

        assertEquals(listOf("gemini-9-experimental"), restarted.availableModels(ModelProviderIds.GEMINI).map { it.id })
        val profile = assertNotNull(restartedCapabilities.get(ModelProviderIds.GEMINI, "gemini-9-experimental"))
        assertEquals(CapabilitySupport.UNKNOWN, profile.toolCalling)
        assertEquals(CapabilitySupport.UNKNOWN, profile.streaming)
    }

    @Test
    fun `an unsupported provider identity has no catalog and no invented discovery`() = runSuspend {
        val capabilities = InMemoryModelCapabilityRegistry()
        val registry = DefaultModelCatalogRegistry(
            connections = {
                mapOf(
                    ModelProviderIds.MISTRAL to ModelConfig(
                        ModelProviderIds.MISTRAL,
                        "https://api.mistral.ai/v1",
                        "mistral-large",
                    ),
                )
            },
            factory = RemoteModelCatalogFactory(clock = { 0L }, capabilityRegistry = capabilities),
            store = InMemoryModelCatalogStore(),
            capabilityRegistry = capabilities,
        )

        assertNull(registry.catalog(ModelProviderIds.MISTRAL))
        assertNull(registry.snapshot(ModelProviderIds.MISTRAL))
        assertTrue(registry.lastDiscovery(ModelProviderIds.MISTRAL) is ModelCatalogState.NotConnected)
    }
}
