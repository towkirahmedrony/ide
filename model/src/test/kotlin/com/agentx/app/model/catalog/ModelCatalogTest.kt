package com.agentx.app.model.catalog

import com.agentx.app.core.ForgeResult
import com.agentx.app.model.FakeHttpTransport
import com.agentx.app.model.ModelConfig
import com.agentx.app.model.http.HttpResponseSpec
import com.agentx.app.model.runSuspend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
            connections = { mapOf("gemini" to ModelConfig("gemini", "https://example.test/v1", "gemini-2.0-flash")) },
            factory = RemoteModelCatalogFactory(),
        )

        val result = registry.refresh("gemini", force = true)

        assertTrue(result is ForgeResult.Failure)
    }

    @Test
    fun `the codec round trips a snapshot`() {
        val snapshot = ModelCatalogSnapshot(
            providerId = "groq",
            models = listOf(
                CatalogModel(id = "llama-3.3-70b-versatile", contextWindowTokens = 131072, available = true),
                CatalogModel(id = "old-model", available = false),
            ),
            fetchedAtMillis = 123L,
            source = CatalogSource.REMOTE,
        )

        val decoded = ModelCatalogCodec.decode(ModelCatalogCodec.encode(snapshot))

        assertNotNull(decoded)
        assertEquals("groq", decoded.providerId)
        assertEquals(2, decoded.models.size)
        assertEquals(131072, decoded.models.first().contextWindowTokens)
        assertFalse(decoded.models.last().available)
    }
}
