package dev.forge.ide.model.manager

import dev.forge.ide.core.ForgeErrorCode
import dev.forge.ide.core.errorOrNull
import dev.forge.ide.core.valueOrNull
import dev.forge.ide.model.DefaultModelGateway
import dev.forge.ide.model.ModelMessage
import dev.forge.ide.model.ModelRequest
import dev.forge.ide.model.preset.DefaultModelPresetRepository
import dev.forge.ide.model.preset.InMemoryModelPresetStore
import dev.forge.ide.model.preset.InMemoryModelSecretStore
import dev.forge.ide.model.preset.StoreBackedModelCredentialResolver
import dev.forge.ide.model.runtime.ModelLifecycleState
import dev.forge.ide.model.runtime.RecordingLogSink
import dev.forge.ide.model.runtime.colabPreset
import dev.forge.ide.model.runtime.healthy
import dev.forge.ide.model.runtime.recordingLogger
import dev.forge.ide.model.runtime.unhealthy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The manager's own responsibilities: preset lifecycle, model selection, gateway
 * binding, credentials and status bookkeeping. The runner is scripted, so any
 * failure here is a manager bug rather than a runtime behaviour.
 */
class ModelManagerTest {

    private val store = InMemoryModelPresetStore()
    private val secrets = InMemoryModelSecretStore()
    private val gateway = DefaultModelGateway()
    private val logs = RecordingLogSink()
    private val runner = FakeModelRunner()
    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    private var ids = 0

    private val manager: DefaultModelManager = DefaultModelManager(
        repository = DefaultModelPresetRepository(
            store = store,
            clock = { 1_000L },
            idFactory = { "generated-${++ids}" },
        ),
        runners = listOf(runner),
        registry = GatewayModelConnectionRegistry(
            gateway = gateway,
            providerFactory = { RecordingModelProvider() },
            logger = recordingLogger(logs),
        ),
        credentials = StoreBackedModelCredentialResolver(secrets),
        secretStore = secrets,
        logger = recordingLogger(logs),
        scope = scope,
        clock = { 1_000L },
        ioDispatcher = Dispatchers.Unconfined,
        monitorEnabled = false,
    )

    @AfterTest
    fun tearDown() {
        manager.close()
        scope.cancel()
    }

    private suspend fun preset(
        id: String,
        name: String = id.replaceFirstChar { it.uppercase() },
        model: String = "model-$id",
        credential: String? = null,
    ) = assertNotNull(
        manager.createPreset(colabPreset(id = id, name = name, model = model), credential).valueOrNull(),
    )

    // --- preset lifecycle --------------------------------------------------

    @Test
    fun `presets can be created, edited and removed through the manager`() = runBlocking {
        val created = preset("qwen")

        assertEquals(listOf("qwen"), manager.state.value.presets.map { it.id })

        val edited = assertNotNull(
            manager.updatePreset(created.copy(displayName = "Qwen 2.5", modelIdentifier = "qwen2.5")).valueOrNull(),
        )
        assertEquals("Qwen 2.5", manager.state.value.presets.single().displayName)
        assertEquals("qwen2.5", edited.modelIdentifier)

        assertNull(manager.deletePreset("qwen").errorOrNull())
        assertTrue(manager.state.value.presets.isEmpty())
    }

    @Test
    fun `a credential is stored securely and only its reference reaches the preset`() = runBlocking {
        val created = preset("qwen", credential = "sk-secret-value")

        val ref = assertNotNull(created.credentialRef)
        assertEquals("sk-secret-value", secrets.get(ref))
        assertFalse(created.toString().contains("sk-secret-value"), "the preset never carries the secret")

        assertNotNull(manager.updatePreset(created, clearCredential = true).valueOrNull())
        assertNull(manager.state.value.presets.single().credentialRef)
        assertNull(secrets.get(ref))
    }

    @Test
    fun `deleting a model also removes its credential and its runner state`() = runBlocking {
        val created = preset("qwen", credential = "sk-secret-value")
        val ref = assertNotNull(created.credentialRef)
        manager.selectModel("qwen")

        manager.deletePreset("qwen")

        assertNull(secrets.get(ref))
        assertNull(manager.activeConfig())
        assertNull(gateway.provider("openai-compatible"))
        assertEquals(ModelLifecycleState.NOT_CONFIGURED, manager.state.value.status("qwen").state)
    }

    // --- restoring state ---------------------------------------------------

    @Test
    fun `refresh restores the selected model, probes it once and never claims an unchecked model is online`() =
        runBlocking {
            preset("qwen")
            preset("llama")
            store.setActiveId("qwen")
            store.setLastStatus("qwen", "ONLINE")
            store.setLastStatus("llama", "ONLINE")

            manager.refresh()

            assertEquals("qwen", manager.state.value.activePresetId)
            assertEquals(1, runner.healthCalls, "one reachability probe, never a workload launch")
            assertEquals(ModelLifecycleState.ONLINE, manager.state.value.status("qwen").state)
            assertEquals(
                ModelLifecycleState.UNKNOWN,
                manager.state.value.status("llama").state,
                "a stored ONLINE flag is not proof of a live connection",
            )
            assertNotNull(manager.activeConfig())
        }

    @Test
    fun `refresh reports the selected model as disconnected when its endpoint is gone`() = runBlocking {
        preset("qwen")
        store.setActiveId("qwen")
        runner.onHealth = { unhealthy("no endpoint was detected") }

        manager.refresh()

        assertEquals(ModelLifecycleState.DISCONNECTED, manager.state.value.status("qwen").state)
        assertNull(manager.activeConfig())
        assertNull(gateway.provider("openai-compatible"))
    }

    // --- connecting --------------------------------------------------------

    @Test
    fun `selecting a model points the gateway at the detected endpoint`() = runBlocking {
        val created = preset("qwen", model = "qwen2.5-coder-7b-instruct")

        val status = assertNotNull(manager.selectModel("qwen").valueOrNull())

        assertTrue(status.isUsable)
        assertEquals("qwen", manager.state.value.activePresetId)
        val config = assertNotNull(manager.activeConfig())
        assertEquals("openai-compatible", config.providerId)
        assertEquals("https://unit-test-runner.trycloudflare.com/v1", config.baseUrl)
        assertEquals(created.modelIdentifier, config.model)
        assertEquals("qwen", config.metadata["modelPresetId"])
        assertNotNull(gateway.provider(config.providerId), "the gateway received the connection")
    }

    @Test
    fun `switching models replaces the active gateway connection`() = runBlocking {
        preset("qwen")
        preset("llama", model = "llama-3.1-8b")
        runner.onStart = { onlineResult(it, url = "https://${it.id}-runner.trycloudflare.com") }

        manager.selectModel("qwen")
        val first = assertNotNull(manager.activeConfig())
        assertEquals("https://qwen-runner.trycloudflare.com/v1", first.baseUrl)

        manager.selectModel("llama")
        val second = assertNotNull(manager.activeConfig())
        assertEquals("https://llama-runner.trycloudflare.com/v1", second.baseUrl)
        assertEquals("llama-3.1-8b", second.model)

        // The same provider-agnostic call path the agent uses now reaches llama.
        val provider = assertNotNull(gateway.provider(second.providerId)) as RecordingModelProvider
        gateway.complete(ModelRequest(second, listOf(ModelMessage.user("hi"))))
        assertEquals("llama-3.1-8b", provider.requests.last().model)
    }

    @Test
    fun `selecting an already online model does not restart it`() = runBlocking {
        preset("qwen")

        manager.selectModel("qwen")
        manager.selectModel("qwen")

        assertEquals(1, runner.startCalls, "a healthy runtime is re-used, not restarted")
        assertEquals(0, runner.healthCalls, "selecting a usable model does not even re-probe it")
        assertNotNull(manager.activeConfig())
    }

    @Test
    fun `a failed start is reported and leaves no connection behind`() = runBlocking {
        preset("qwen")
        runner.onStart = { failedResult(it) }

        val result = manager.selectModel("qwen")

        val error = assertNotNull(result.errorOrNull())
        assertEquals(ForgeErrorCode.MODEL_OPERATION_FAILED, error.code)
        assertEquals("qwen", error.details["presetId"])
        assertEquals(ModelLifecycleState.FAILED, manager.state.value.status("qwen").state)
        assertTrue(manager.state.value.status("qwen").awaitingRuntime)
        assertNull(manager.activeConfig())
        assertNull(gateway.provider("openai-compatible"))
    }

    @Test
    fun `stopping a model releases the gateway connection`() = runBlocking {
        preset("qwen")
        manager.selectModel("qwen")
        assertNotNull(manager.activeConfig())

        val status = assertNotNull(manager.stopModel("qwen").valueOrNull())

        assertEquals(ModelLifecycleState.STOPPED, status.state)
        assertNull(manager.activeConfig())
        assertNull(gateway.provider("openai-compatible"))
        assertEquals(1, runner.stopCalls)
    }

    // --- health and recovery ----------------------------------------------

    @Test
    fun `a lost connection is reported and can be reconnected`() = runBlocking {
        preset("qwen")
        manager.selectModel("qwen")

        runner.onHealth = { unhealthy("tunnel is gone") }
        manager.checkModelHealth("qwen")

        assertEquals(ModelLifecycleState.DISCONNECTED, manager.state.value.status("qwen").state)
        assertNull(manager.activeConfig())

        runner.onHealth = { healthy() }
        val reconnected = assertNotNull(manager.reconnectModel("qwen").valueOrNull())

        assertEquals(ModelLifecycleState.ONLINE, reconnected.state)
        assertNotNull(manager.activeConfig())
        assertNotNull(gateway.provider("openai-compatible"))
    }

    @Test
    fun `background entry marks the connection as unverified and returning re-checks it`() = runBlocking {
        preset("qwen")
        manager.selectModel("qwen")
        assertEquals(ModelLifecycleState.ONLINE, runner.status("qwen").state)

        manager.onAppBackground()

        assertEquals(ModelLifecycleState.CHECKING, runner.status("qwen").state)
        assertTrue(runner.status("qwen").message.contains("background"))

        runner.onHealth = { healthy() }
        manager.onAppForeground()

        assertEquals(ModelLifecycleState.ONLINE, runner.status("qwen").state)
        assertNotNull(manager.activeConfig())
    }

    // --- security and decoupling ------------------------------------------

    @Test
    fun `credentials and tunnel endpoints never reach the logs`() = runBlocking {
        preset("qwen", credential = "sk-super-secret-value")

        manager.selectModel("qwen")

        assertTrue(logs.records.isNotEmpty(), "the connection is observable")
        assertFalse(logs.contains("sk-super-secret-value"), "the credential must never be logged")
        assertFalse(logs.contains(FAKE_ENDPOINT_URL), "a tunnel URL is a capability, not log material")
        assertEquals(
            "sk-super-secret-value",
            assertNotNull(manager.activeConfig()).apiKey,
            "the credential still reaches the request path",
        )
    }

    @Test
    fun `the model runner browser session never controls the model connection`() = runBlocking {
        preset("qwen")
        manager.selectModel("qwen")
        val configBefore = manager.activeConfig()
        val statusesBefore = manager.state.value.statuses

        // The WebView was closed, then reopened: neither may touch the connection.
        manager.onRunnerSessionChanged("qwen", attached = false)
        manager.onRunnerSessionChanged("qwen", attached = true)

        assertEquals(configBefore, manager.activeConfig())
        assertEquals(statusesBefore, manager.state.value.statuses)
        assertEquals(true, manager.state.value.runnerSessions["qwen"])

        val provider = assertNotNull(gateway.provider("openai-compatible")) as RecordingModelProvider
        val config = assertNotNull(manager.activeConfig())
        gateway.complete(ModelRequest(config, listOf(ModelMessage.user("still works"))))
        assertEquals(1, provider.requests.size, "the agent path is independent of the browser")
    }

    @Test
    fun `a model preset without a runner for its provider is reported honestly`() = runBlocking {
        val created = preset("custom")
        val isolated = DefaultModelManager(
            repository = DefaultModelPresetRepository(store, clock = { 1_000L }),
            runners = emptyList(),
            registry = GatewayModelConnectionRegistry(gateway),
            credentials = StoreBackedModelCredentialResolver(secrets),
            secretStore = secrets,
            scope = scope,
            ioDispatcher = Dispatchers.Unconfined,
            monitorEnabled = false,
        )

        val error = assertNotNull(isolated.startModel(created.id).errorOrNull())

        assertEquals(ForgeErrorCode.MODEL_RUNNER_UNAVAILABLE, error.code)
    }

    @Test
    fun `the manager reports whether credentials can survive a restart`() {
        assertFalse(manager.credentialsPersistent, "the in-memory store is honest about not persisting secrets")
    }
}
