package com.agentx.app.model.manager

import com.agentx.app.core.errorOrNull
import com.agentx.app.core.valueOrNull
import com.agentx.app.model.DefaultModelGateway
import com.agentx.app.model.connect.ModelSetupKind
import com.agentx.app.model.preset.DefaultModelPresetRepository
import com.agentx.app.model.preset.HealthCheckConfig
import com.agentx.app.model.preset.InMemoryModelPresetStore
import com.agentx.app.model.preset.InMemoryModelSecretStore
import com.agentx.app.model.preset.ModelApiProtocol
import com.agentx.app.model.preset.ModelPreset
import com.agentx.app.model.preset.ModelProviderIds
import com.agentx.app.model.preset.ModelProviderType
import com.agentx.app.model.preset.StoreBackedModelCredentialResolver
import com.agentx.app.model.runtime.ModelLifecycleState
import com.agentx.app.model.runtime.ModelRuntimeFailure
import com.agentx.app.model.runtime.ModelRunner
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An API provider and a Local/Custom endpoint must not share a connection lifecycle.
 *
 * The reported failure was that they did: both were served by the same runtime
 * lifecycle, so a failed probe — of an API provider, or of a local endpoint that had
 * nothing to do with it — could release an API provider's connection, and local
 * reconnect behaviour ran against API providers.
 *
 * Every test below drives the real [DefaultModelManager] and the real
 * [GatewayModelConnectionRegistry]; only the runner is scripted, so what is asserted
 * is the manager's policy rather than a stub's.
 */
class ApiLocalConnectionSeparationTest {

    private val store = InMemoryModelPresetStore()
    private val secrets = InMemoryModelSecretStore()
    private val gateway = DefaultModelGateway()
    private val logs = RecordingLogSink()
    private val runner = FakeModelRunner()
    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    private val managers = mutableListOf<DefaultModelManager>()
    private var ids = 0

    private val apiKey = "test-key-not-a-secret"
    private val localKey = "local-key-not-a-secret"
    private val apiModel = "gemini-3.5-flash-lite"
    private val localModel = "hf.co/unsloth/Devstral-Small-2-24B-Instruct-2512-GGUF:Q3_K_M"

    @AfterTest
    fun tearDown() {
        managers.forEach(DefaultModelManager::close)
        managers.clear()
        scope.cancel()
    }

    // --- harness -----------------------------------------------------------

    private fun manager(runner: ModelRunner = this.runner, monitor: Boolean = false): DefaultModelManager {
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
            monitorEnabled = monitor,
        )
        managers += created
        return created
    }

    /** A saved Gemini API preset: provider identity, endpoint, key and model. */
    private suspend fun apiPreset(manager: DefaultModelManager, id: String = "gemini"): ModelPreset = assertNotNull(
        manager.createPreset(
            geminiPreset(id = id, name = "Gemini", model = apiModel),
            credential = apiKey,
        ).valueOrNull(),
    )

    /** A saved Custom/Local preset: the user's own endpoint. */
    private suspend fun localPreset(
        manager: DefaultModelManager,
        id: String = "devstral",
        health: HealthCheckConfig = HealthCheckConfig(),
    ): ModelPreset = assertNotNull(
        manager.createPreset(
            customPreset(id = id, name = "Devstral", model = localModel).copy(health = health),
            credential = localKey,
        ).valueOrNull(),
    )

    /** The API provider's connection as the gateway holds it. */
    private fun apiConnection(manager: DefaultModelManager) = manager.connections()[ModelProviderIds.GEMINI]

    // --- 0. the boundary itself -------------------------------------------

    @Test
    fun `hosted providers are API connections and everything else is local or custom`() {
        assertEquals(ModelConnectionKind.API, ModelSetupKind.GEMINI.connectionKind)
        assertEquals(ModelConnectionKind.API, ModelSetupKind.GROQ.connectionKind)
        assertEquals(ModelConnectionKind.LOCAL_CUSTOM, ModelSetupKind.CUSTOM.connectionKind)

        // The persisted setup kind decides, and an unknown one is never assumed to be
        // a hosted provider.
        assertEquals(ModelConnectionKind.API, "gemini".connectionKind)
        assertEquals(ModelConnectionKind.API, "groq".connectionKind)
        assertEquals(ModelConnectionKind.LOCAL_CUSTOM, "custom".connectionKind)
        assertEquals(ModelConnectionKind.LOCAL_CUSTOM, null.connectionKind)
        assertEquals(ModelConnectionKind.LOCAL_CUSTOM, "something-else".connectionKind)

        assertEquals(ModelConnectionKind.API, geminiPreset().connectionKind)
        assertEquals(ModelConnectionKind.LOCAL_CUSTOM, customPreset().connectionKind)

        val api = ApiModelConnectionManager()
        val local = LocalModelConnectionManager()
        assertTrue(api.handles(geminiPreset()))
        assertFalse(api.handles(customPreset()))
        assertTrue(local.handles(customPreset()))
        assertFalse(local.handles(geminiPreset()))

        // The rules that differ, stated once.
        assertTrue(api.connectsFromConfiguration)
        assertFalse(api.recoversLapsedRuntime)
        assertEquals(ModelLifecycleState.DEGRADED, api.unreachableState())

        assertFalse(local.connectsFromConfiguration)
        assertTrue(local.recoversLapsedRuntime)
        assertEquals(ModelLifecycleState.DISCONNECTED, local.unreachableState())
    }

    // --- Test 1 ------------------------------------------------------------

    @Test
    fun `disconnecting a local model leaves the API configuration intact`() = runBlocking {
        val manager = manager()
        val api = apiPreset(manager)
        val local = localPreset(manager)

        assertNotNull(manager.selectModel(api.id).valueOrNull())
        assertNotNull(manager.selectModel(local.id).valueOrNull())
        val before = assertNotNull(apiConnection(manager))

        val status = assertNotNull(manager.stopModel(local.id).valueOrNull())

        // The local model is gone.
        assertEquals(ModelLifecycleState.STOPPED, status.state)
        assertNull(manager.connections()["devstral"])

        // The API provider is untouched: same endpoint, model, provider identity and
        // credential, still registered with the gateway.
        val after = assertNotNull(apiConnection(manager), "the API connection must survive a local disconnect")
        assertEquals(before.baseUrl, after.baseUrl)
        assertEquals(before.model, after.model)
        assertEquals(before.providerId, after.providerId)
        assertEquals(apiKey, after.apiKey)
        assertNotNull(gateway.provider(ModelProviderIds.GEMINI))

        // ... and the saved configuration it came from is unchanged.
        val reloaded = assertNotNull(manager.preset(api.id))
        assertEquals(apiModel, reloaded.modelIdentifier)
        assertEquals(apiKey, secrets.get(assertNotNull(reloaded.credentialRef)))
        assertEquals(api.credentialRef, reloaded.credentialRef)
    }

    // --- Test 2 ------------------------------------------------------------

    @Test
    fun `a local endpoint going unreachable leaves the API configuration intact`() = runBlocking {
        val manager = manager()
        val api = apiPreset(manager)
        val local = localPreset(manager)

        assertNotNull(manager.selectModel(api.id).valueOrNull())
        assertNotNull(manager.selectModel(local.id).valueOrNull())
        val before = assertNotNull(apiConnection(manager))

        // The endpoint the user runs stops answering, and the app re-checks what is
        // selected. Nothing about that reaches the API provider.
        runner.onHealth = { unhealthy("the tunnel is gone") }
        manager.refresh()

        assertEquals(ModelLifecycleState.DISCONNECTED, manager.state.value.status(local.id).state)
        assertNull(manager.connections()["devstral"])

        val after = assertNotNull(apiConnection(manager), "a local endpoint failure must not drop an API provider")
        assertEquals(before.baseUrl, after.baseUrl)
        assertEquals(before.model, after.model)
        assertEquals(apiKey, after.apiKey)
        assertEquals(api.id, manager.state.value.presets.first { it.providerId == ModelProviderIds.GEMINI }.id)
        assertNotNull(gateway.provider(ModelProviderIds.GEMINI))
        Unit
    }

    // --- Test 3 ------------------------------------------------------------

    @Test
    fun `an API configuration survives a reload of app state and repository`() = runBlocking {
        val first = manager()
        val api = apiPreset(first)
        assertNotNull(first.selectModel(api.id).valueOrNull())
        val before = assertNotNull(apiConnection(first))

        // A second manager over the same stores stands in for the process restarting:
        // it only has what was persisted.
        val restarted = manager()
        restarted.refresh()

        val restored = assertNotNull(
            apiConnection(restarted),
            "a saved API provider must come back online from its configuration alone",
        )
        assertEquals(before.baseUrl, restored.baseUrl)
        assertEquals(before.model, restored.model)
        assertEquals(before.providerId, restored.providerId)
        assertEquals(apiKey, restored.apiKey)

        assertEquals(api.id, restarted.state.value.activePresetId)
        val active = assertNotNull(restarted.activeConfig())
        assertEquals(ModelProviderIds.GEMINI, active.providerId)
        assertEquals(apiModel, active.model)
        assertFalse(restarted.connections().containsKey("devstral"))

        val reloaded = assertNotNull(restarted.preset(api.id))
        assertEquals(apiModel, reloaded.modelIdentifier)
        assertEquals(apiKey, secrets.get(assertNotNull(reloaded.credentialRef)))
    }

    // --- Test 4 ------------------------------------------------------------

    @Test
    fun `a local configuration survives a reload with its endpoint protocol and model`() = runBlocking {
        val first = manager()
        val local = localPreset(first)
        assertNotNull(first.selectModel(local.id).valueOrNull())

        val restarted = manager()
        restarted.refresh()

        val reloaded = assertNotNull(restarted.preset(local.id))
        assertEquals(local.endpoint.explicitUrl, reloaded.endpoint.explicitUrl)
        assertEquals(local.apiProtocol, reloaded.apiProtocol)
        assertEquals(localModel, reloaded.modelIdentifier)
        assertEquals(local.credentialRef, reloaded.credentialRef)
        assertEquals("custom", reloaded.setupKind)
        assertEquals(ModelProviderType.REMOTE_OPENAI_COMPATIBLE, reloaded.providerType)
        assertEquals(localKey, secrets.get(assertNotNull(reloaded.credentialRef)))

        // A reachable endpoint is reconnected, exactly as before this change.
        assertEquals(local.id, restarted.state.value.activePresetId)
        assertNotNull(restarted.activeConfig())
        assertNotNull(restarted.connections()[local.id])
        Unit
    }

    // --- Test 5 ------------------------------------------------------------

    @Test
    fun `a failed local reconnect deletes no API credential and no API model selection`() = runBlocking {
        val manager = manager()
        val api = apiPreset(manager)
        val local = localPreset(manager)

        assertNotNull(manager.selectModel(api.id).valueOrNull())
        assertNotNull(manager.selectModel(local.id).valueOrNull())
        val apiRef = assertNotNull(api.credentialRef)
        val before = assertNotNull(apiConnection(manager))

        runner.onReconnect = { failedResult(it, "the local server is no longer running") }
        val error = assertNotNull(manager.reconnectModel(local.id).errorOrNull())

        assertEquals(local.id, error.details["presetId"])
        assertNull(manager.connections()["devstral"])

        // No API credential, model selection or connection was reset by it.
        val after = assertNotNull(apiConnection(manager))
        assertEquals(before.baseUrl, after.baseUrl)
        assertEquals(before.model, after.model)
        assertEquals(apiKey, after.apiKey)
        assertEquals(apiKey, secrets.get(apiRef), "the API credential must still be stored")

        val reloaded = assertNotNull(manager.preset(api.id))
        assertEquals(apiRef, reloaded.credentialRef)
        assertEquals(apiModel, reloaded.modelIdentifier)
        assertTrue(store.load().any { it.id == api.id }, "the API preset is neither deleted nor reset")
        assertEquals(apiKey, secrets.get(assertNotNull(reloaded.credentialRef)))
    }

    // --- Test 6 ------------------------------------------------------------

    @Test
    fun `an API request that fails temporarily leaves the saved configuration in place`() = runBlocking {
        val manager = manager()
        val api = apiPreset(manager)
        assertNotNull(manager.selectModel(api.id).valueOrNull())
        val before = assertNotNull(apiConnection(manager))
        val apiRef = assertNotNull(api.credentialRef)

        runner.onHealth = { unhealthy("the provider is temporarily unavailable") }
        val health = assertNotNull(manager.checkModelHealth(api.id).valueOrNull())

        assertFalse(health.isReachable)
        assertTrue(health.detail.contains("temporarily unavailable"), health.detail)

        // Health is reported as health. The connection is not released, and the
        // configuration is not deleted, invalidated or marked failed.
        val status = manager.state.value.status(api.id)
        assertEquals(ModelLifecycleState.DEGRADED, status.state)
        assertEquals(ModelRuntimeFailure.MODEL_API_UNREACHABLE, status.failure)
        assertFalse(status.awaitingRuntime)

        val after = assertNotNull(
            apiConnection(manager),
            "a failed API request is health, not a lost connection",
        )
        assertEquals(before.baseUrl, after.baseUrl)
        assertEquals(before.model, after.model)
        assertEquals(apiKey, after.apiKey)
        assertEquals(before, assertNotNull(manager.activeConfig()))
        assertNotNull(gateway.provider(ModelProviderIds.GEMINI))

        val reloaded = assertNotNull(manager.preset(api.id))
        assertEquals(apiRef, reloaded.credentialRef)
        assertEquals(apiModel, reloaded.modelIdentifier)
        assertEquals(apiKey, secrets.get(apiRef))

        // And it is still usable without being configured again.
        val reconnected = assertNotNull(manager.reconnectModel(api.id).valueOrNull())
        assertEquals(ModelLifecycleState.DEGRADED, reconnected.state)
        assertNotNull(apiConnection(manager))
        Unit
    }

    @Test
    fun `an API provider recovers and reconnects after a temporary failure without a new connection`() = runBlocking {
        val manager = manager()
        val api = apiPreset(manager)
        assertNotNull(manager.selectModel(api.id).valueOrNull())
        val before = assertNotNull(apiConnection(manager))

        // A temporary outage: the provider stops answering, but the saved configuration
        // is reported as degraded health and the connection is never released.
        runner.onHealth = { unhealthy("the provider is temporarily unavailable") }
        assertFalse(manager.checkModelHealth(api.id).valueOrNull()?.isReachable == true)
        assertEquals(ModelLifecycleState.DEGRADED, manager.state.value.status(api.id).state)
        assertNotNull(apiConnection(manager), "the saved configuration survives the outage")

        // The outage clears. Reconnect recovers the SAME connection in place: the user
        // never has to delete the connection and add it again.
        runner.onHealth = { healthy() }
        val reconnected = assertNotNull(manager.reconnectModel(api.id).valueOrNull())

        assertEquals(ModelLifecycleState.ONLINE, reconnected.state)
        val after = assertNotNull(apiConnection(manager))
        // The connection identity is unchanged: reconnect repaired the existing record
        // rather than producing a new one.
        assertEquals(before.connectionId, after.connectionId)
        assertEquals(before.baseUrl, after.baseUrl)
        assertEquals(before.model, after.model)
        assertEquals(apiKey, after.apiKey)
        assertEquals(api.id, manager.state.value.activePresetId)
        assertNotNull(gateway.provider(ModelProviderIds.GEMINI))
        Unit
    }

    @Test
    fun `the same health failure still disconnects a local model`() = runBlocking {
        val manager = manager()
        val local = localPreset(manager)
        assertNotNull(manager.selectModel(local.id).valueOrNull())
        assertNotNull(manager.connections()["devstral"])

        runner.onHealth = { unhealthy("the local server stopped") }
        manager.checkModelHealth(local.id)

        // The local/custom lifecycle is unchanged: an endpoint that stopped answering
        // is a lost connection, and a bounded reconnect is what recovers it.
        assertEquals(ModelLifecycleState.DISCONNECTED, manager.state.value.status(local.id).state)
        assertNull(manager.connections()["devstral"])
        assertNull(manager.activeConfig())
    }

    @Test
    fun `an API provider is never polled or reconnected the way a runtime is`() = runBlocking {
        val pollingRunner = FakeModelRunner()
        val manager = manager(runner = pollingRunner, monitor = true)
        val api = apiPreset(manager)

        assertNotNull(manager.selectModel(api.id).valueOrNull())
        val probesAfterConnect = pollingRunner.healthCalls
        assertTrue(probesAfterConnect >= 1, "the connection is still verified once")

        delay(POLL_WINDOW_MILLIS)

        assertEquals(
            probesAfterConnect,
            pollingRunner.healthCalls,
            "an API provider is not polled: its connection does not depend on a live runtime",
        )
        assertEquals(0, pollingRunner.reconnectCalls)
        assertNotNull(apiConnection(manager))

        // A runtime-backed connection over the same period is monitored.
        val runtimeRunner = FakeModelRunner()
        val runtimeManager = manager(runner = runtimeRunner, monitor = true)
        val local = localPreset(runtimeManager, health = HealthCheckConfig(intervalMillis = 1))
        assertNotNull(runtimeManager.selectModel(local.id).valueOrNull())
        val probesAfterLocalConnect = runtimeRunner.healthCalls

        delay(POLL_WINDOW_MILLIS)

        assertTrue(
            runtimeRunner.healthCalls > probesAfterLocalConnect,
            "a local/custom connection is still re-checked while the app is in front",
        )
    }

    @Test
    fun `the model gateway still receives a normal descriptor for both kinds`() = runBlocking {
        val manager = manager()
        val api = apiPreset(manager)
        val local = localPreset(manager)

        assertNotNull(manager.selectModel(api.id).valueOrNull())
        assertNotNull(manager.selectModel(local.id).valueOrNull())

        // Both connections are ordinary ModelConfigs the gateway routes on; only the
        // lifecycle that produced them differs.
        val gemini = assertNotNull(apiConnection(manager))
        val custom = assertNotNull(manager.connections()[local.id])
        // The API provider is addressed at its configured endpoint; the local/custom
        // connection is addressed at the endpoint its runtime published.
        assertEquals("$GEMINI_ROOT/v1beta", gemini.baseUrl)
        assertEquals("$FAKE_ENDPOINT_URL/v1", custom.baseUrl)
        assertEquals(ModelApiProtocol.GEMINI_NATIVE, assertNotNull(manager.preset(api.id)).apiProtocol)
        assertEquals(ModelApiProtocol.OPENAI_COMPATIBLE, assertNotNull(manager.preset(local.id)).apiProtocol)
        assertEquals(api.id, gemini.metadata["modelPresetId"])
        assertEquals(local.id, custom.metadata["modelPresetId"])
        assertTrue(gemini.validate().isEmpty(), gemini.validate().toString())
        assertTrue(custom.validate().isEmpty(), custom.validate().toString())
        assertTrue(assertNotNull(gateway.provider(api.id)) is RecordingModelProvider)
        assertTrue(assertNotNull(gateway.provider(local.id)) is RecordingModelProvider)
    }

    private companion object {
        const val NOW = 1_000L
        const val POLL_WINDOW_MILLIS = 80L
        const val GEMINI_ROOT = "https://generativelanguage.googleapis.com"
    }
}
