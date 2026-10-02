package com.agentx.app.model.catalog

import com.agentx.app.core.ForgeResult
import com.agentx.app.model.DefaultModelGateway
import com.agentx.app.model.FakeHttpTransport
import com.agentx.app.model.ModelConfig
import com.agentx.app.model.connect.ModelSetupKind
import com.agentx.app.model.http.HttpResponseSpec
import com.agentx.app.model.manager.GatewayModelConnectionRegistry
import com.agentx.app.model.preset.EndpointConfig
import com.agentx.app.model.preset.EndpointDiscoveryMode
import com.agentx.app.model.preset.ModelApiProtocol
import com.agentx.app.model.preset.ModelPreset
import com.agentx.app.model.preset.ModelProviderIds
import com.agentx.app.model.preset.ModelProviderType
import com.agentx.app.model.runSuspend
import com.agentx.app.model.runtime.EndpointSource
import com.agentx.app.model.runtime.ModelEndpoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelCatalogTest {

    private val modelsUrl = "https://api.groq.com/openai/v1/models"

    private fun catalog(
        transport: FakeHttpTransport,
        store: ModelCatalogStore = InMemoryModelCatalogStore(),
        now: () -> Long = { 0L },
        ttlMillis: Long = 60_000L,
    ) = RemoteModelCatalog(
        providerId = "groq",
        modelsUrl = { modelsUrl },
        credential = { "secret-key" },
        transport = transport,
        store = store,
        ttlMillis = ttlMillis,
        clock = now,
    )

    private fun modelsJson(vararg ids: String): String = buildString {
        append("{\"object\":\"list\",\"data\":[")
        ids.forEachIndexed { index, id ->
            if (index > 0) append(',')
            append("{\"id\":\"$id\",\"object\":\"model\",\"active\":true,\"context_window\":131072,\"owned_by\":\"meta\"}")
        }
        append("]}")
    }

    @Test
    fun `a refresh parses model ids and metadata`() = runSuspend {
        val transport = FakeHttpTransport(
            response = HttpResponseSpec(
                statusCode = 200,
                body = modelsJson("llama-3.3-70b-versatile", "llama-3.1-8b-instant"),
            ),
        )
        val catalog = catalog(transport)

        val snapshot = (catalog.refresh(force = true) as ForgeResult.Success).value

        assertEquals(listOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant"), snapshot.models.map { it.id })
        assertTrue(snapshot.availableModels().all { it.contextWindowTokens == 131072 })
        assertEquals(CatalogSource.REMOTE, snapshot.source)
        // The credential is sent, never stored in a snapshot.
        assertEquals("Bearer secret-key", transport.lastRequest?.headers?.get("Authorization"))
        assertFalse(snapshot.toString().contains("secret-key"))
    }

    @Test
    fun `speech and guard models are not exposed as choices`() = runSuspend {
        val transport = FakeHttpTransport(
            response = HttpResponseSpec(
                statusCode = 200,
                body = modelsJson(
                    "llama-3.3-70b-versatile",
                    "whisper-large-v3",
                    "playai-tts",
                    "meta-llama/llama-guard-4-12b",
                ),
            ),
        )

        val snapshot = (catalog(transport).refresh(force = true) as ForgeResult.Success).value

        assertEquals(listOf("llama-3.3-70b-versatile"), snapshot.models.map { it.id })
    }

    @Test
    fun `a fresh cache is reused without another request`() = runSuspend {
        val transport = FakeHttpTransport(
            response = HttpResponseSpec(statusCode = 200, body = modelsJson("llama-3.3-70b-versatile")),
        )
        var now = 0L
        val catalog = catalog(transport, now = { now })

        catalog.refresh(force = false)
        now = 1_000L
        val second = (catalog.refresh(force = false) as ForgeResult.Success).value

        assertEquals(1, transport.requests.size, "the catalog must not be fetched before every request")
        assertEquals(CatalogSource.CACHE, second.source)
    }

    @Test
    fun `a forced refresh bypasses the cache`() = runSuspend {
        val transport = FakeHttpTransport(
            response = HttpResponseSpec(statusCode = 200, body = modelsJson("llama-3.3-70b-versatile")),
        )
        val catalog = catalog(transport)

        catalog.refresh(force = true)
        catalog.refresh(force = true)

        assertEquals(2, transport.requests.size)
    }

    @Test
    fun `a removed model is kept and marked unavailable`() = runSuspend {
        val transport = FakeHttpTransport()
        val catalog = catalog(transport)

        transport.response = HttpResponseSpec(
            statusCode = 200,
            body = modelsJson("llama-3.3-70b-versatile", "llama-3.1-8b-instant"),
        )
        catalog.refresh(force = true)

        transport.response = HttpResponseSpec(statusCode = 200, body = modelsJson("llama-3.3-70b-versatile"))
        val snapshot = (catalog.refresh(force = true) as ForgeResult.Success).value

        assertTrue(snapshot.isAvailable("llama-3.3-70b-versatile"))
        assertFalse(snapshot.isAvailable("llama-3.1-8b-instant"))
        val removed = snapshot.find("llama-3.1-8b-instant")
        assertNotNull(removed)
        assertFalse(removed.available)
    }

    @Test
    fun `a malformed body fails but keeps the previous snapshot`() = runSuspend {
        val transport = FakeHttpTransport(
            response = HttpResponseSpec(statusCode = 200, body = modelsJson("llama-3.3-70b-versatile")),
        )
        val catalog = catalog(transport)
        catalog.refresh(force = true)

        transport.response = HttpResponseSpec(statusCode = 200, body = "not json")
        val result = catalog.refresh(force = true)

        assertTrue(result is ForgeResult.Failure)
        assertNotNull(catalog.cached())
    }

    @Test
    fun `an http error is reported`() = runSuspend {
        val transport = FakeHttpTransport(
            response = HttpResponseSpec(statusCode = 401, body = "{\"error\":{\"message\":\"bad key\"}}"),
        )
        val result = catalog(transport).refresh(force = true)

        assertTrue(result is ForgeResult.Failure)
    }

    @Test
    fun `the registry builds a catalog lazily from a connected provider`() = runSuspend {
        val transport = FakeHttpTransport(
            response = HttpResponseSpec(
                statusCode = 200,
                body = modelsJson("llama-3.3-70b-versatile", "llama-3.1-8b-instant"),
            ),
        )
        val connection = ModelConfig(
            providerId = "groq",
            baseUrl = "https://api.groq.com/openai/v1",
            model = "llama-3.3-70b-versatile",
            apiKey = "secret-key",
        )
        val registry = DefaultModelCatalogRegistry(
            connections = { mapOf("groq" to connection) },
            factory = ModelCatalogFactory { providerId, conn ->
                RemoteModelCatalog(
                    providerId = providerId,
                    modelsUrl = { GroqModelCatalog.modelsUrl(conn.baseUrl) },
                    credential = { conn.apiKey },
                    transport = transport,
                )
            },
        )

        val snapshot = (registry.refresh("groq", force = true) as ForgeResult.Success).value

        assertEquals("groq", snapshot.providerId)
        assertEquals(2, registry.availableModels("groq").size)
        assertEquals("https://api.groq.com/openai/v1/models", transport.lastRequest?.url)
    }

    @Test
    fun `the registry reports no catalog for an unsupported provider`() = runSuspend {
        val registry = DefaultModelCatalogRegistry(
            connections = {
                mapOf("openai-compatible" to ModelConfig("openai-compatible", "http://localhost:11434/v1", "local-model"))
            },
            factory = RemoteModelCatalogFactory(),
        )

        val result = registry.refresh("openai-compatible", force = true)

        assertTrue(result is ForgeResult.Failure)
    }

    // --- Gemini ---------------------------------------------------------------

    private val geminiBaseUrl = "https://generativelanguage.googleapis.com/v1beta/openai"

    /** The connection registry the manager uses, so a preset really becomes a ModelConfig. */
    private fun gatewayRegistry() = GatewayModelConnectionRegistry(gateway = DefaultModelGateway())

    private fun geminiPreset(modelId: String) = ModelPreset(
        id = "gemini-preset",
        displayName = "Gemini",
        providerType = ModelProviderType.REMOTE_OPENAI_COMPATIBLE,
        modelIdentifier = modelId,
        apiProtocol = ModelApiProtocol.OPENAI_COMPATIBLE,
        apiBasePath = "",
        endpoint = EndpointConfig(EndpointDiscoveryMode.CONFIGURED_ENDPOINT, geminiBaseUrl),
        setupKind = ModelSetupKind.GEMINI.id,
    )

    private fun geminiRegistry(transport: FakeHttpTransport, now: () -> Long = { 0L }) =
        DefaultModelCatalogRegistry(
            connections = {
                mapOf(
                    ModelProviderIds.GEMINI to ModelConfig(
                        providerId = ModelProviderIds.GEMINI,
                        baseUrl = geminiBaseUrl,
                        model = "gemini-2.0-flash",
                        apiKey = "secret-key",
                    ),
                )
            },
            factory = RemoteModelCatalogFactory(transport = transport, clock = now),
        )

    /** Gemini's own model shape: `name`, a display name, limits and methods. */
    private fun geminiModelsJson(vararg models: String): String =
        "{\"models\":[" + models.joinToString(",") + "]}"

    private fun geminiModel(
        id: String,
        displayName: String? = null,
        inputTokenLimit: Int? = null,
        outputTokenLimit: Int? = null,
        methods: List<String> = listOf("generateContent"),
        status: String? = null,
    ): String = buildString {
        append("{\"name\":\"models/").append(id).append('"')
        displayName?.let { append(",\"displayName\":\"").append(it).append('"') }
        inputTokenLimit?.let { append(",\"inputTokenLimit\":").append(it) }
        outputTokenLimit?.let { append(",\"outputTokenLimit\":").append(it) }
        append(",\"supportedGenerationMethods\":[")
        append(methods.joinToString(",") { "\"$it\"" })
        append("]")
        status?.let { append(",\"status\":\"").append(it).append('"') }
        append('}')
    }

    @Test
    fun `gemini models are discovered from the provider's own list`() = runSuspend {
        val transport = FakeHttpTransport(
            response = HttpResponseSpec(
                statusCode = 200,
                body = geminiModelsJson(
                    geminiModel("gemini-2.0-flash", displayName = "Gemini 2.0 Flash", inputTokenLimit = 1_048_576, outputTokenLimit = 8_192),
                    // A release the source code has never heard of, with a version
                    // shape no whitelist could predict.
                    geminiModel("gemini-9-ultra-preview", displayName = "Gemini 9 Ultra (preview)", inputTokenLimit = 2_000_000),
                ),
            ),
        )

        val snapshot = (geminiRegistry(transport).refresh(ModelProviderIds.GEMINI, force = true) as ForgeResult.Success).value

        assertEquals(listOf("gemini-2.0-flash", "gemini-9-ultra-preview"), snapshot.models.map { it.id })
        assertEquals("https://generativelanguage.googleapis.com/v1beta/openai/models", transport.lastRequest?.url)
        assertEquals("Bearer secret-key", transport.lastRequest?.headers?.get("Authorization"))
        val flash = assertNotNull(snapshot.find("gemini-2.0-flash"))
        assertEquals("Gemini 2.0 Flash", flash.displayName)
        assertEquals(1_048_576, flash.contextWindowTokens)
        assertEquals(8_192, flash.maxOutputTokens)
        assertTrue(flash.available)
        // The credential is never part of a snapshot.
        assertFalse(snapshot.toString().contains("secret-key"))
    }

    @Test
    fun `gemini models that cannot do text chat are filtered out`() = runSuspend {
        val transport = FakeHttpTransport(
            response = HttpResponseSpec(
                statusCode = 200,
                body = geminiModelsJson(
                    geminiModel("gemini-2.0-flash"),
                    geminiModel("gemini-embedding-001", methods = listOf("embedContent")),
                    geminiModel("imagen-3.0-generate-002", methods = listOf("predict")),
                    geminiModel("veo-3.0-generate-preview", methods = listOf("predictLongRunning")),
                    geminiModel("gemini-2.0-flash-tts", methods = listOf("generateContent")),
                ),
            ),
        )

        val snapshot = (geminiRegistry(transport).refresh(ModelProviderIds.GEMINI, force = true) as ForgeResult.Success).value

        assertEquals(listOf("gemini-2.0-flash"), snapshot.models.map { it.id })
    }

    @Test
    fun `gemini ids are filtered by family when no generation methods are reported`() = runSuspend {
        val transport = FakeHttpTransport(
            response = HttpResponseSpec(
                statusCode = 200,
                body = modelsJson(
                    "gemini-2.0-flash",
                    "gemini-4-experimental",
                    "gemini-embedding-001",
                    "imagen-3.0-generate-002",
                    "gemini-2.5-flash-image",
                    "veo-3.0-generate-preview",
                ),
            ),
        )

        val snapshot = (geminiRegistry(transport).refresh(ModelProviderIds.GEMINI, force = true) as ForgeResult.Success).value

        assertEquals(listOf("gemini-2.0-flash", "gemini-4-experimental"), snapshot.models.map { it.id })
    }

    @Test
    fun `a provider-reported deprecation is kept and hides the model from choices`() = runSuspend {
        val transport = FakeHttpTransport(
            response = HttpResponseSpec(
                statusCode = 200,
                body = geminiModelsJson(
                    geminiModel("gemini-2.0-flash"),
                    geminiModel("gemini-legacy-flash", status = "deprecated"),
                ),
            ),
        )

        val snapshot = (geminiRegistry(transport).refresh(ModelProviderIds.GEMINI, force = true) as ForgeResult.Success).value

        val legacy = assertNotNull(snapshot.find("gemini-legacy-flash"))
        assertEquals(true, legacy.deprecated)
        assertFalse(legacy.available)
        assertFalse(snapshot.isAvailable("gemini-legacy-flash"))
        assertEquals(listOf("gemini-2.0-flash"), snapshot.availableModels().map { it.id })
    }

    @Test
    fun `a gemini cache is reused and a forced refresh refetches`() = runSuspend {
        val transport = FakeHttpTransport(
            response = HttpResponseSpec(statusCode = 200, body = geminiModelsJson(geminiModel("gemini-2.0-flash"))),
        )
        var now = 0L
        val registry = geminiRegistry(transport, now = { now })

        registry.refresh(ModelProviderIds.GEMINI, force = false)
        now = 1_000L
        val cached = (registry.refresh(ModelProviderIds.GEMINI, force = false) as ForgeResult.Success).value
        assertEquals(1, transport.requests.size, "the list must not be fetched on every render")
        assertEquals(CatalogSource.CACHE, cached.source)

        now = 10_000_000L
        registry.refresh(ModelProviderIds.GEMINI, force = true)
        assertEquals(2, transport.requests.size, "a manual refresh bypasses the cache")
    }

    @Test
    fun `a gemini discovery failure keeps the previous list`() = runSuspend {
        val transport = FakeHttpTransport(
            response = HttpResponseSpec(statusCode = 200, body = geminiModelsJson(geminiModel("gemini-2.0-flash"))),
        )
        val registry = geminiRegistry(transport)
        registry.refresh(ModelProviderIds.GEMINI, force = true)

        transport.response = HttpResponseSpec(statusCode = 401, body = "{\"error\":{\"message\":\"bad key\"}}")
        val result = registry.refresh(ModelProviderIds.GEMINI, force = true)

        assertTrue(result is ForgeResult.Failure)
        assertEquals(listOf("gemini-2.0-flash"), registry.availableModels(ModelProviderIds.GEMINI).map { it.id })
    }

    @Test
    fun `a model discovered from gemini can be saved and reaches the connection`() = runSuspend {
        val transport = FakeHttpTransport(
            response = HttpResponseSpec(
                statusCode = 200,
                body = geminiModelsJson(geminiModel("gemini-9-ultra-preview")),
            ),
        )
        val discovered = (geminiRegistry(transport).refresh(ModelProviderIds.GEMINI, force = true) as ForgeResult.Success)
            .value.availableModels().single()

        val config = gatewayRegistry().connect(
            preset = geminiPreset(discovered.id),
            endpoint = ModelEndpoint(geminiBaseUrl, EndpointSource.CONFIGURED),
            credential = "secret-key",
        )

        assertEquals(ModelProviderIds.GEMINI, config.providerId)
        assertEquals("gemini-9-ultra-preview", config.model)
        assertEquals(geminiBaseUrl, config.baseUrl)
        assertTrue(config.validate().isEmpty(), config.validate().toString())
    }

    @Test
    fun `a saved gemini model is usable when discovery is unavailable`() = runSuspend {
        val transport = FakeHttpTransport(
            response = HttpResponseSpec(statusCode = 503, body = "{\"error\":{\"message\":\"unavailable\"}}"),
        )
        val registry = geminiRegistry(transport)

        assertTrue(registry.refresh(ModelProviderIds.GEMINI, force = true) is ForgeResult.Failure)
        assertTrue(registry.availableModels(ModelProviderIds.GEMINI).isEmpty())

        // Discovery failing must not stop the model that is already saved.
        val config = gatewayRegistry().connect(
            preset = geminiPreset("gemini-1.5-pro"),
            endpoint = ModelEndpoint(geminiBaseUrl, EndpointSource.CONFIGURED),
            credential = "secret-key",
        )

        assertEquals("gemini-1.5-pro", config.model)
        assertEquals(geminiBaseUrl, config.baseUrl)
    }

    @Test
    fun `adding gemini does not change groq or local catalogs`() {
        val factory = RemoteModelCatalogFactory()

        assertTrue(factory.supports(ModelProviderIds.GROQ))
        assertTrue(factory.supports(ModelProviderIds.GEMINI))
        assertFalse(factory.supports(ModelProviderIds.OPENAI_COMPATIBLE))
        assertEquals(
            "https://api.groq.com/openai/v1/models",
            factory.modelsUrlFor(ModelProviderIds.GROQ, "https://api.groq.com/openai/v1"),
        )
        assertEquals(
            "$geminiBaseUrl/models",
            factory.modelsUrlFor(ModelProviderIds.GEMINI, geminiBaseUrl),
        )
    }

    @Test
    fun `the codec round trips a snapshot`() {
        val snapshot = ModelCatalogSnapshot(
            providerId = "groq",
            models = listOf(
                CatalogModel(
                    id = "llama-3.3-70b-versatile",
                    displayName = "Llama 3.3 70B",
                    contextWindowTokens = 131072,
                    maxOutputTokens = 8192,
                    available = true,
                ),
                CatalogModel(id = "old-model", available = false, deprecated = true),
            ),
            fetchedAtMillis = 123L,
            source = CatalogSource.REMOTE,
        )

        val decoded = ModelCatalogCodec.decode(ModelCatalogCodec.encode(snapshot))

        assertNotNull(decoded)
        assertEquals("groq", decoded.providerId)
        assertEquals(2, decoded.models.size)
        assertEquals(131072, decoded.models.first().contextWindowTokens)
        assertEquals(8192, decoded.models.first().maxOutputTokens)
        assertEquals("Llama 3.3 70B", decoded.models.first().displayName)
        assertFalse(decoded.models.last().available)
        assertEquals(true, decoded.models.last().deprecated)
        // A model nobody reported as deprecated stays unknown rather than guessed.
        assertNull(decoded.models.first().deprecated)
    }
}
