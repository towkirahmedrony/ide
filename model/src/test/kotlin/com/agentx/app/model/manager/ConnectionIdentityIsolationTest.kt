package com.agentx.app.model.manager

import com.agentx.app.core.errorOrNull
import com.agentx.app.core.valueOrNull
import com.agentx.app.model.DefaultModelGateway
import com.agentx.app.model.ModelConfig
import com.agentx.app.model.preset.DefaultModelPresetRepository
import com.agentx.app.model.preset.EndpointConfig
import com.agentx.app.model.preset.EndpointDiscoveryMode
import com.agentx.app.model.preset.InMemoryModelPresetStore
import com.agentx.app.model.preset.InMemoryModelSecretStore
import com.agentx.app.model.preset.ModelApiProtocol
import com.agentx.app.model.preset.ModelPreset
import com.agentx.app.model.preset.ModelProviderIds
import com.agentx.app.model.preset.ModelProviderType
import com.agentx.app.model.preset.StoreBackedModelCredentialResolver
import com.agentx.app.model.runtime.ModelLifecycleState
import com.agentx.app.model.runtime.RecordingLogSink
import com.agentx.app.model.runtime.customPreset
import com.agentx.app.model.runtime.geminiPreset
import com.agentx.app.model.runtime.healthy
import com.agentx.app.model.runtime.recordingLogger
import com.agentx.app.model.runtime.unhealthy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent model connections must coexist in the production runtime without
 * evicting, overwriting, disconnecting or degrading one another.
 *
 * The reported P0 was that several independent custom/local endpoints collapsed
 * into one shared identity — the provider family `openai-compatible` — so
 * connecting a second custom endpoint replaced the first, disconnecting one
 * removed the other, and a health failure of one was reported against the wrong
 * connection.
 *
 * Every test below drives the real [DefaultModelManager] and the real
 * [GatewayModelConnectionRegistry] over the real [DefaultModelGateway]; only the
 * runner is scripted. The identity under test is the persisted preset id
 * ([ModelPreset.connectionId]), never the provider family.
 */
class ConnectionIdentityIsolationTest {

    private val store = InMemoryModelPresetStore()
    private val secrets = InMemoryModelSecretStore()
    private val gateway = DefaultModelGateway()
    private val logs = RecordingLogSink()
    private val runner = FakeModelRunner()
    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    private val managers = mutableListOf<DefaultModelManager>()
    private var ids = 0

    @AfterTest
    fun tearDown() {
        managers.forEach(DefaultModelManager::close)
        managers.clear()
        scope.cancel()
    }

    // --- harness -----------------------------------------------------------

    private fun manager(): DefaultModelManager {
        val created = DefaultModelManager(
            repository = DefaultModelPresetRepository(
                store = store,
                clock = { NOW },
                idFactory = { "generated-${++ids}" },
            ),
            runners = listOf(runner),
            registry = GatewayModelConnectionRegistry(
                gateway = gateway,
                providerFactory = { preset -> RecordingModelProvider(id = preset.providerId) },
                logger = recordingLogger(logs),
            ),
            credentials = StoreBackedModelCredentialResolver(secrets),
            secretStore = secrets,
            logger = recordingLogger(logs),
            scope = scope,
            clock = { NOW },
            ioDispatcher = Dispatchers.Unconfined,
            monitorEnabled = false,
        )
        managers += created
        // Every connection is reached at its own endpoint, so two connections of
        // one family are distinguishable by address as well as identity.
        runner.onStart = { preset -> onlineResult(preset, url = "https://${preset.id}.example.dev") }
        return created
    }

    private suspend fun DefaultModelManager.save(preset: ModelPreset): ModelPreset =
        assertNotNull(createPreset(preset).valueOrNull())

    /** A saved custom/OpenAI-compatible endpoint. */
    private fun custom(id: String, name: String, model: String, endpoint: String) =
        customPreset(id = id, name = name, model = model, endpoint = endpoint)

    /** A saved Ollama endpoint (same provider family as [custom], different protocol). */
    private fun ollama(id: String, name: String, model: String, endpoint: String) =
        customPreset(id = id, name = name, model = model, endpoint = endpoint).copy(
            apiProtocol = ModelApiProtocol.OLLAMA,
            providerType = ModelProviderType.LOCAL_PHONE,
        )

    /** A hosted Cerebras connection: an independent family, same wire protocol as custom. */
    private fun cerebras(id: String, model: String) = ModelPreset(
        id = id,
        displayName = "Cerebras",
        providerType = ModelProviderType.REMOTE_OPENAI_COMPATIBLE,
        modelIdentifier = model,
        apiProtocol = ModelApiProtocol.OPENAI_COMPATIBLE,
        apiBasePath = "/v1",
        endpoint = EndpointConfig(EndpointDiscoveryMode.CONFIGURED_ENDPOINT, "https://api.cerebras.ai"),
        setupKind = ModelProviderIds.CEREBRAS,
    )

    /** A saved Groq connection. */
    private fun groq(id: String, model: String) = ModelPreset(
        id = id,
        displayName = "Groq",
        providerType = ModelProviderType.REMOTE_OPENAI_COMPATIBLE,
        modelIdentifier = model,
        apiProtocol = ModelApiProtocol.OPENAI_COMPATIBLE,
        apiBasePath = "/v1",
        endpoint = EndpointConfig(EndpointDiscoveryMode.CONFIGURED_ENDPOINT, "https://api.groq.com/openai"),
        setupKind = ModelProviderIds.GROQ,
    )

    // --- same-family coexistence (the critical regression) -----------------

    @Test
    fun `two custom openai-compatible endpoints and an ollama endpoint coexist`() = runBlocking {
        val manager = manager()
        manager.save(custom("custom-a", "My Devstral", "devstral-24b", "https://a.example.dev"))
        manager.save(custom("custom-b", "Qwen server", "qwen2.5-coder-14b", "https://b.example.dev"))
        manager.save(ollama("ollama-c", "Local Ollama", "qwen2.5:7b", "http://127.0.0.1:11434"))

        assertNotNull(manager.selectModel("custom-a").valueOrNull())
        assertNotNull(manager.selectModel("custom-b").valueOrNull())
        assertNotNull(manager.selectModel("ollama-c").valueOrNull())

        val connections = manager.connections()
        assertEquals(setOf("custom-a", "custom-b", "ollama-c"), connections.keys)
        // All three share the provider family; none replaced another.
        assertEquals(ModelProviderIds.OPENAI_COMPATIBLE, connections.getValue("custom-a").providerId)
        assertEquals(ModelProviderIds.OPENAI_COMPATIBLE, connections.getValue("custom-b").providerId)
        assertEquals(ModelProviderIds.OPENAI_COMPATIBLE, connections.getValue("ollama-c").providerId)
        assertNotEquals(
            connections.getValue("custom-a").baseUrl,
            connections.getValue("custom-b").baseUrl,
            "each connection keeps its own endpoint",
        )
        assertNotNull(gateway.provider("custom-a"))
        assertNotNull(gateway.provider("custom-b"))
        assertNotNull(gateway.provider("ollama-c"))
        Unit
    }

    @Test
    fun `connecting a second custom endpoint never overwrites the first`() = runBlocking {
        val manager = manager()
        manager.save(custom("custom-a", "Devstral", "devstral-24b", "https://a.example.dev"))
        assertNotNull(manager.selectModel("custom-a").valueOrNull())
        val first = assertNotNull(manager.connections()["custom-a"])

        manager.save(custom("custom-b", "Qwen", "qwen2.5-coder-14b", "https://b.example.dev"))
        assertNotNull(manager.selectModel("custom-b").valueOrNull())

        assertEquals(first, manager.connections()["custom-a"], "A is byte-for-byte unchanged")
        assertNotEquals(manager.connections()["custom-a"], manager.connections()["custom-b"])
    }

    // --- delete / disconnect isolation -------------------------------------

    @Test
    fun `disconnecting one connection removes only it`() = runBlocking {
        val manager = manager()
        manager.save(custom("custom-a", "A", "devstral-24b", "https://a.example.dev"))
        manager.save(custom("custom-b", "B", "qwen2.5-coder-14b", "https://b.example.dev"))
        assertNotNull(manager.selectModel("custom-a").valueOrNull())
        assertNotNull(manager.selectModel("custom-b").valueOrNull())

        assertNotNull(manager.stopModel("custom-a").valueOrNull())

        assertNull(manager.connections()["custom-a"])
        assertNotNull(manager.connections()["custom-b"], "an unrelated connection must survive")
        assertNotNull(gateway.provider("custom-b"))
        assertNull(gateway.provider("custom-a"))
    }

    @Test
    fun `deleting one connection leaves the other registered`() = runBlocking {
        val manager = manager()
        val a = manager.save(custom("custom-a", "A", "devstral-24b", "https://a.example.dev"))
        val b = manager.save(custom("custom-b", "B", "qwen2.5-coder-14b", "https://b.example.dev"))
        assertNotNull(manager.selectModel(a.id).valueOrNull())
        assertNotNull(manager.selectModel(b.id).valueOrNull())

        assertNotNull(manager.deletePreset(a.id).valueOrNull())

        assertNull(manager.connections()[a.id])
        assertNotNull(manager.connections()[b.id])
        assertNotNull(manager.preset(b.id), "B's saved preset is untouched")
        Unit
    }

    // --- health / reconnect isolation --------------------------------------

    @Test
    fun `a health failure of one connection does not degrade another`() = runBlocking {
        val manager = manager()
        manager.save(custom("custom-a", "Devstral", "devstral-24b", "https://a.example.dev"))
        manager.save(geminiPreset(id = "gemini-c", name = "Gemini", model = "gemini-3.5-flash"))

        assertNotNull(manager.selectModel("gemini-c").valueOrNull())
        assertNotNull(manager.selectModel("custom-a").valueOrNull())
        assertEquals(ModelLifecycleState.ONLINE, manager.state.value.status("gemini-c").state)

        runner.onHealth = { preset ->
            if (preset.id == "custom-a") unhealthy("A stopped answering") else healthy()
        }
        assertNotNull(manager.checkModelHealth("custom-a").valueOrNull())

        assertEquals(ModelLifecycleState.DISCONNECTED, manager.state.value.status("custom-a").state)
        // B (Gemini) is neither disconnected nor degraded.
        assertEquals(ModelLifecycleState.ONLINE, manager.state.value.status("gemini-c").state)
        assertNotNull(manager.connections()["gemini-c"], "A failure must not touch another connection")
        assertNotNull(gateway.provider("gemini-c"))
        Unit
    }

    @Test
    fun `a reconnect failure of one connection leaves the other intact`() = runBlocking {
        val manager = manager()
        manager.save(custom("custom-a", "A", "devstral-24b", "https://a.example.dev"))
        manager.save(custom("custom-b", "B", "qwen2.5-coder-14b", "https://b.example.dev"))
        assertNotNull(manager.selectModel("custom-b").valueOrNull())
        // A is the active connection, so its failed reconnect is applied to A only.
        assertNotNull(manager.selectModel("custom-a").valueOrNull())

        runner.onReconnect = { preset ->
            if (preset.id == "custom-a") {
                failedResult(preset, "A is gone")
            } else {
                onlineResult(preset, url = "https://${preset.id}.example.dev")
            }
        }
        val error = assertNotNull(manager.reconnectModel("custom-a").errorOrNull())
        assertEquals("custom-a", error.details["presetId"])

        assertNull(manager.connections()["custom-a"])
        assertNotNull(manager.connections()["custom-b"], "B must be untouched by A's reconnect failure")
        Unit
    }

    // --- update isolation --------------------------------------------------

    @Test
    fun `editing one connection does not mutate another`() = runBlocking {
        val manager = manager()
        val a = manager.save(custom("custom-a", "My Devstral", "devstral-24b", "https://a.example.dev"))
        val b = manager.save(custom("custom-b", "Qwen", "qwen2.5-coder-14b", "https://b.example.dev"))
        assertNotNull(manager.selectModel(a.id).valueOrNull())
        assertNotNull(manager.selectModel(b.id).valueOrNull())
        val bBefore = assertNotNull(manager.connections()[b.id])

        // Renaming A, and changing the model it runs, is an edit to A alone.
        assertNotNull(
            manager.updatePreset(a.copy(displayName = "Renamed", modelIdentifier = "devstral-next"))
                .valueOrNull(),
        )

        val reloadedA = assertNotNull(manager.preset(a.id))
        assertEquals("custom-a", reloadedA.id, "the display name never becomes the identity")
        assertEquals("custom-a", reloadedA.connectionId)
        assertEquals("Renamed", reloadedA.displayName)

        assertEquals(bBefore, assertNotNull(manager.connections()[b.id]), "B is unchanged by A's edit")
        assertEquals("Qwen", assertNotNull(manager.preset(b.id)).displayName)
    }

    // --- persistence / restart ---------------------------------------------

    @Test
    fun `two connections survive a reload with distinct identities`() = runBlocking {
        val first = manager()
        first.save(custom("custom-a", "Devstral", "devstral-24b", "https://a.example.dev"))
        first.save(custom("custom-b", "Qwen", "qwen2.5-coder-14b", "https://b.example.dev"))
        assertNotNull(first.selectModel("custom-a").valueOrNull())
        assertNotNull(first.selectModel("custom-b").valueOrNull())

        // A second manager over the same stores stands in for the process restarting.
        val restarted = manager()
        restarted.refresh()

        val reloadedA = assertNotNull(restarted.preset("custom-a"))
        val reloadedB = assertNotNull(restarted.preset("custom-b"))
        assertEquals("custom-a", reloadedA.connectionId)
        assertEquals("custom-b", reloadedB.connectionId)
        assertNotEquals(reloadedA.connectionId, reloadedB.connectionId)

        // Both can be brought online independently and both remain connected.
        assertNotNull(restarted.selectModel(reloadedA.id).valueOrNull())
        assertNotNull(restarted.selectModel(reloadedB.id).valueOrNull())
        assertEquals(setOf("custom-a", "custom-b"), restarted.connections().keys)
    }

    @Test
    fun `updating and disconnecting after a reload affect only their own connection`() = runBlocking {
        val first = manager()
        val a = first.save(custom("custom-a", "A", "devstral-24b", "https://a.example.dev"))
        val b = first.save(custom("custom-b", "B", "qwen2.5-coder-14b", "https://b.example.dev"))
        first.selectModel(a.id)
        first.selectModel(b.id)

        val restarted = manager()
        restarted.refresh()
        restarted.selectModel(a.id)
        restarted.selectModel(b.id)

        // Editing B's label leaves both connections registered. A display name is
        // not the live endpoint, so renaming B must not release B (or A).
        assertNotNull(restarted.updatePreset(b.copy(displayName = "B renamed")).valueOrNull())
        assertNotNull(restarted.connections()[a.id], "A survives B's edit")
        assertEquals(a.id, assertNotNull(restarted.connections()[a.id]).metadata["modelPresetId"])
        assertNotNull(restarted.connections()[b.id], "renaming B must not disconnect B")
        assertEquals(b.id, assertNotNull(restarted.connections()[b.id]).connectionId)

        // Disconnecting A leaves B registered.
        assertNotNull(restarted.stopModel(a.id).valueOrNull())
        assertNull(restarted.connections()[a.id])
        assertNotNull(restarted.connections()[b.id], "disconnecting A must not remove B")
        assertEquals(1, restarted.connections().values.count { it.connectionId == b.id })
        Unit
    }

    @Test
    fun `an API connection keeps identity through degraded reconnect and never disturbs another`() = runBlocking {
        val manager = manager()
        val api = manager.save(geminiPreset(id = "gemini-a", name = "Gemini", model = "gemini-3.5-flash"))
        val other = manager.save(custom("custom-b", "B", "qwen2.5-coder-14b", "https://b.example.dev"))
        assertNotNull(manager.selectModel(api.id).valueOrNull())
        assertNotNull(manager.selectModel(other.id).valueOrNull())
        val apiBefore = assertNotNull(manager.connections()[api.id])
        val otherBefore = assertNotNull(manager.connections()[other.id])
        assertNotEquals(apiBefore.connectionId, otherBefore.connectionId)
        assertEquals(api.id, apiBefore.connectionId)
        assertEquals(other.id, otherBefore.connectionId)

        runner.onHealth = { preset ->
            if (preset.id == api.id) unhealthy("temporarily unavailable") else healthy()
        }
        assertNotNull(manager.checkModelHealth(api.id).valueOrNull())

        assertEquals(ModelLifecycleState.DEGRADED, manager.state.value.status(api.id).state)
        val degraded = assertNotNull(manager.connections()[api.id], "degraded health is not a lost connection")
        assertEquals(apiBefore.connectionId, degraded.connectionId)
        assertEquals(apiBefore.providerId, degraded.providerId)
        assertEquals(apiBefore.model, degraded.model)
        assertEquals(apiBefore.baseUrl, degraded.baseUrl)
        assertEquals(otherBefore, manager.connections()[other.id], "B is untouched by A's outage")

        runner.onHealth = { healthy() }
        val reconnected = assertNotNull(manager.reconnectModel(api.id).valueOrNull())

        assertEquals(ModelLifecycleState.ONLINE, reconnected.state)
        val apiAfter = assertNotNull(manager.connections()[api.id])
        assertEquals(apiBefore.connectionId, apiAfter.connectionId)
        assertEquals(apiBefore.providerId, apiAfter.providerId)
        assertEquals(apiBefore.model, apiAfter.model)
        assertEquals(apiBefore.baseUrl, apiAfter.baseUrl)
        assertEquals(1, manager.connections().values.count { it.connectionId == api.id }, "reconnect must not add a duplicate")
        assertEquals(otherBefore, manager.connections()[other.id])
        assertEquals(setOf(api.id, other.id), manager.connections().keys)
        Unit
    }

    // --- sequential cross-mutation -----------------------------------------

    @Test
    fun `a sequence of operations only ever affects its own connection`() = runBlocking {
        val manager = manager()
        val a = manager.save(custom("custom-a", "A", "devstral-24b", "https://a.example.dev"))
        val b = manager.save(custom("custom-b", "B", "qwen2.5-coder-14b", "https://b.example.dev"))

        manager.selectModel(a.id)
        manager.selectModel(b.id)

        // A health update, then B's.
        manager.checkModelHealth(a.id)
        manager.checkModelHealth(b.id)
        assertNotNull(manager.connections()[a.id])
        assertNotNull(manager.connections()[b.id])

        // A disconnect, then B reconnect.
        manager.stopModel(a.id)
        assertNull(manager.connections()[a.id])
        assertNotNull(manager.reconnectModel(b.id).valueOrNull())
        assertNotNull(manager.connections()[b.id])

        // A preset edit after A is gone must not disturb B.
        manager.updatePreset(a.copy(displayName = "A again"))
        assertNotNull(manager.connections()[b.id])
        assertEquals("B", assertNotNull(manager.preset(b.id)).displayName)
    }

    @Test
    fun `identity is never derived from display name provider family model or endpoint`() = runBlocking {
        val registry = GatewayModelConnectionRegistry(
            gateway = gateway,
            providerFactory = { preset -> RecordingModelProvider(id = preset.providerId) },
            logger = recordingLogger(logs),
        )
        // Deliberately confusing values: a display name that looks like an endpoint,
        // and an opaque model id.
        val preset = customPreset(
            id = "preset-77",
            name = "https://not-an-endpoint.example",
            model = "hf.co/unsloth/Devstral-Small-2-24B-Instruct-2512-GGUF:Q3_K_M",
            endpoint = "https://real-endpoint.example",
        )
        val config: ModelConfig = registry.connect(preset, endpoint("https://real-endpoint.example"), null)

        assertEquals("preset-77", config.connectionId)
        assertEquals(ModelProviderIds.OPENAI_COMPATIBLE, config.providerId)
        assertEquals("hf.co/unsloth/Devstral-Small-2-24B-Instruct-2512-GGUF:Q3_K_M", config.model)
        assertEquals("https://real-endpoint.example/v1", config.baseUrl)
        assertFalse(config.connectionId.contains("not-an-endpoint"))
        assertNotEquals("custom", config.providerId)
    }

    // --- the multi-agent product requirement -------------------------------

    @Test
    fun `main coder reviewer explorer and debugger connections are independently registered`() = runBlocking {
        val registry = GatewayModelConnectionRegistry(
            gateway = gateway,
            providerFactory = { preset -> RecordingModelProvider(id = preset.providerId) },
            logger = recordingLogger(logs),
        )
        // MAIN → Devstral (custom), CODER → Cerebras, REVIEWER → Gemini,
        // EXPLORER → Groq, DEBUGGER → local Ollama.
        val main = customPreset(id = "main-devstral", name = "Devstral", model = "devstral-24b", endpoint = "https://main.example.dev")
        val coder = cerebras("coder-cerebras", "llama3.1-8b")
        val reviewer = geminiPreset(id = "reviewer-gemini", name = "Gemini", model = "gemini-3.5-flash")
        val explorer = groq("explorer-groq", "llama-3.3-70b-versatile")
        val debugger = ollama("debugger-ollama", "Qwen", "qwen2.5-coder-14b", "http://127.0.0.1:11434")

        listOf(
            main to "https://main.example.dev",
            coder to "https://api.cerebras.ai",
            reviewer to "https://generativelanguage.googleapis.com",
            explorer to "https://api.groq.com/openai",
            debugger to "http://127.0.0.1:11434",
        ).forEach { (preset, url) ->
            registry.connect(preset, endpoint(url), null)
        }

        assertEquals(
            setOf("main-devstral", "coder-cerebras", "reviewer-gemini", "explorer-groq", "debugger-ollama"),
            registry.connections().keys,
        )
        assertEquals(5, gateway.providers().size)
        // MAIN and DEBUGGER share the openai-compatible family and still coexist.
        assertEquals(
            ModelProviderIds.OPENAI_COMPATIBLE,
            registry.connections().getValue("main-devstral").providerId,
        )
        assertEquals(
            ModelProviderIds.OPENAI_COMPATIBLE,
            registry.connections().getValue("debugger-ollama").providerId,
        )

        // Disconnecting one leaves the other four registered and unchanged.
        val snapshot = registry.connections()
        assertTrue(registry.disconnect("coder-cerebras"))
        assertEquals(
            setOf("main-devstral", "reviewer-gemini", "explorer-groq", "debugger-ollama"),
            registry.connections().keys,
        )
        listOf("main-devstral", "reviewer-gemini", "explorer-groq", "debugger-ollama").forEach { id ->
            assertEquals(snapshot.getValue(id), registry.connections().getValue(id))
        }
        assertNull(gateway.provider("coder-cerebras"))
    }

    private companion object {
        const val NOW = 1_000L
    }
}
