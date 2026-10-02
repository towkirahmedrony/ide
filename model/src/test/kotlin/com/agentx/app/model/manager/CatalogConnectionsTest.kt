package com.agentx.app.model.manager

import com.agentx.app.core.ForgeResult
import com.agentx.app.model.FakeHttpTransport
import com.agentx.app.model.catalog.DefaultModelCatalogRegistry
import com.agentx.app.model.catalog.RemoteModelCatalogFactory
import com.agentx.app.model.connect.ModelSetupKind
import com.agentx.app.model.http.HttpResponseSpec
import com.agentx.app.model.preset.EndpointConfig
import com.agentx.app.model.preset.EndpointDiscoveryMode
import com.agentx.app.model.preset.InMemoryModelPresetStore
import com.agentx.app.model.preset.InMemoryModelSecretStore
import com.agentx.app.model.preset.ModelApiProtocol
import com.agentx.app.model.preset.ModelPreset
import com.agentx.app.model.preset.ModelProviderIds
import com.agentx.app.model.preset.ModelProviderType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The catalog must be able to read a saved provider's own model list even when that
 * provider is not the connection currently in use. Before this, only live
 * connections were listed, so a saved Gemini preset that was not the active model
 * left Settings with no catalog and a built-in fallback list.
 */
class CatalogConnectionsTest {

    private val geminiRootUrl = "https://generativelanguage.googleapis.com"

    private fun geminiPreset(
        modelId: String = "gemini-3.5-flash",
        credentialRef: String? = "gemini-credential",
    ) = ModelPreset(
        id = "gemini-preset",
        displayName = "Gemini",
        providerType = ModelProviderType.REMOTE_OPENAI_COMPATIBLE,
        modelIdentifier = modelId,
        apiProtocol = ModelApiProtocol.GEMINI_NATIVE,
        apiBasePath = "/v1beta",
        credentialRef = credentialRef,
        endpoint = EndpointConfig(EndpointDiscoveryMode.CONFIGURED_ENDPOINT, geminiRootUrl),
        setupKind = ModelSetupKind.GEMINI.id,
    )

    /** Gemini's own model shape: `name` plus the generation methods. */
    private fun geminiModelsJson(vararg ids: String): String =
        "{\"models\":[" + ids.joinToString(",") { id ->
            "{\"name\":\"models/$id\",\"displayName\":\"$id\"," +
                "\"supportedGenerationMethods\":[\"generateContent\"]}"
        } + "]}"

    @Test
    fun `a saved gemini provider is listed for the catalog even when it is not connected`() = runBlocking {
        val presetStore = InMemoryModelPresetStore(listOf(geminiPreset()))
        val secretStore = InMemoryModelSecretStore()
        secretStore.put("gemini-credential", "AIza-test")

        val manager = ModelManagers.create(
            presetStore = presetStore,
            secretStore = secretStore,
            monitorEnabled = false,
            ioDispatcher = Dispatchers.Unconfined,
        )
        manager.refresh()

        // Nothing is online: the routing set the resolver uses stays empty.
        assertTrue(manager.connections().isEmpty())

        // The catalog view still carries the saved provider, with its credential.
        val catalogConnection = assertNotNull(manager.catalogConnections()[ModelProviderIds.GEMINI])
        assertEquals("$geminiRootUrl/v1beta", catalogConnection.baseUrl)
        assertEquals("AIza-test", catalogConnection.apiKey)
        assertTrue(catalogConnection.validate().isEmpty(), catalogConnection.validate().toString())

        // And the registry built on it can read the provider's own model list.
        val transport = FakeHttpTransport(
            response = HttpResponseSpec(
                statusCode = 200,
                body = geminiModelsJson("gemini-3.1-flash", "gemini-3.5-flash"),
            ),
        )
        val registry = DefaultModelCatalogRegistry(
            connections = { manager.catalogConnections() },
            factory = RemoteModelCatalogFactory(transport = transport, clock = { 0L }),
        )

        val snapshot = (registry.refresh(ModelProviderIds.GEMINI, force = true) as ForgeResult.Success).value

        assertEquals(
            listOf("gemini-3.1-flash", "gemini-3.5-flash"),
            snapshot.availableModels().map { it.id },
        )
        assertEquals("$geminiRootUrl/v1beta/models", transport.lastRequest?.url)
        assertEquals("AIza-test", transport.lastRequest?.headers?.get("x-goog-api-key"))
        // The credential reaches the request but never the cached snapshot.
        assertFalse(snapshot.toString().contains("AIza-test"))
    }

    @Test
    fun `a saved provider without an endpoint is not listed for the catalog`() = runBlocking {
        val presetStore = InMemoryModelPresetStore(
            listOf(
                geminiPreset().copy(
                    endpoint = EndpointConfig(EndpointDiscoveryMode.RUNTIME_OUTPUT, explicitUrl = null),
                ),
            ),
        )

        val manager = ModelManagers.create(
            presetStore = presetStore,
            secretStore = InMemoryModelSecretStore(),
            monitorEnabled = false,
            ioDispatcher = Dispatchers.Unconfined,
        )
        manager.refresh()

        // Only providers addressable without runtime discovery join the catalog view.
        assertTrue(manager.catalogConnections().isEmpty())
    }
}
