package com.agentx.app.model.manager

import com.agentx.app.core.valueOrNull
import com.agentx.app.model.DefaultModelGateway
import com.agentx.app.model.capability.CapabilitySupport
import com.agentx.app.model.capability.InMemoryModelCapabilityRegistry
import com.agentx.app.model.capability.ModelCapability
import com.agentx.app.model.capability.ModelCapabilityDeclaration
import com.agentx.app.model.preset.DefaultModelPresetRepository
import com.agentx.app.model.preset.InMemoryModelPresetStore
import com.agentx.app.model.preset.InMemoryModelSecretStore
import com.agentx.app.model.preset.StoreBackedModelCredentialResolver
import com.agentx.app.model.runtime.RecordingLogSink
import com.agentx.app.model.runtime.customPreset
import com.agentx.app.model.runtime.recordingLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The manager is what turns a saved declaration into capability resolution: the
 * store it loads presets from and the registry the role resolver reads are
 * separate objects, so the declaration has to be published on every load.
 */
class DeclaredModelCapabilityTest {

    private val providerId = "openai-compatible"

    /** The opaque id the connect flow persists for the user's own endpoint. */
    private val devstral = "hf.co/unsloth/Devstral-Small-2-24B-Instruct-2512-GGUF:Q3_K_M"

    private val store = InMemoryModelPresetStore()
    private val secrets = InMemoryModelSecretStore()
    private val logs = RecordingLogSink()
    private val runner = FakeModelRunner()
    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    private var ids = 0

    /** One shared registry, exactly as the app wires the manager and the resolver. */
    private val capabilities = InMemoryModelCapabilityRegistry()

    private fun manager(
        registry: InMemoryModelCapabilityRegistry = capabilities,
    ): DefaultModelManager = DefaultModelManager(
        repository = DefaultModelPresetRepository(
            store = store,
            clock = { 1_000L },
            idFactory = { "generated-${++ids}" },
        ),
        runners = listOf(runner),
        registry = GatewayModelConnectionRegistry(
            gateway = DefaultModelGateway(registry),
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
        capabilityRegistry = registry,
    )

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `saving a declared custom model publishes it for that model alone`() = runBlocking {
        val manager = manager()

        assertNotNull(
            manager.createPreset(
                customPreset(model = devstral).copy(
                    declaredCapabilities = ModelCapabilityDeclaration.toolEnabledEndpoint(),
                ),
                credential = null,
            ).valueOrNull(),
        )

        assertEquals(
            CapabilitySupport.SUPPORTED,
            capabilities.support(providerId, devstral, ModelCapability.TOOL_CALLING),
        )
        assertEquals(
            CapabilitySupport.SUPPORTED,
            capabilities.support(providerId, devstral, ModelCapability.STREAMING),
        )
        // The same provider's other models were never declared.
        assertEquals(
            CapabilitySupport.UNKNOWN,
            capabilities.support(providerId, "some-other-local-model", ModelCapability.TOOL_CALLING),
        )
        manager.close()
    }

    @Test
    fun `a preset that declares nothing leaves every model unknown`() = runBlocking {
        val manager = manager()

        assertNotNull(manager.createPreset(customPreset(model = devstral), credential = null).valueOrNull())

        assertEquals(
            CapabilitySupport.UNKNOWN,
            capabilities.support(providerId, devstral, ModelCapability.TOOL_CALLING),
        )
        assertNull(capabilities.get(providerId, devstral))
        manager.close()
    }

    @Test
    fun `the declaration is re-published after a restart`() = runBlocking {
        val first = manager()
        first.createPreset(
            customPreset(model = devstral).copy(
                declaredCapabilities = ModelCapabilityDeclaration.toolEnabledEndpoint(),
            ),
            credential = null,
        )
        first.close()

        // A registry that has never seen this model, and a manager over the same
        // saved store: only loading the preset can put the declaration back.
        val restartedRegistry = InMemoryModelCapabilityRegistry()
        assertEquals(
            CapabilitySupport.UNKNOWN,
            restartedRegistry.support(providerId, devstral, ModelCapability.TOOL_CALLING),
        )

        val restarted = manager(restartedRegistry)
        restarted.refresh()

        assertEquals(
            CapabilitySupport.SUPPORTED,
            restartedRegistry.support(providerId, devstral, ModelCapability.TOOL_CALLING),
        )
        assertEquals(
            CapabilitySupport.SUPPORTED,
            restartedRegistry.support(providerId, devstral, ModelCapability.STREAMING),
        )
        restarted.close()
    }
}
