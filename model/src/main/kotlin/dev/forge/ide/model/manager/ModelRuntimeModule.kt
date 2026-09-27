package dev.forge.ide.model.manager

import dev.forge.ide.core.foundation.ServiceKeys
import dev.forge.ide.core.logging.ForgeLogger
import dev.forge.ide.core.logging.ForgeLoggers
import dev.forge.ide.core.logging.LogLevel
import dev.forge.ide.core.module.ForgeModule
import dev.forge.ide.core.module.ModuleContext
import dev.forge.ide.model.DefaultModelGateway
import dev.forge.ide.model.ModelGateway
import dev.forge.ide.model.http.HttpTransport
import dev.forge.ide.model.http.UrlConnectionHttpTransport
import dev.forge.ide.model.preset.DefaultModelPresetRepository
import dev.forge.ide.model.preset.InMemoryModelPresetStore
import dev.forge.ide.model.preset.InMemoryModelSecretStore
import dev.forge.ide.model.preset.ModelPresetStore
import dev.forge.ide.model.preset.ModelSecretStore
import dev.forge.ide.model.preset.StoreBackedModelCredentialResolver
import dev.forge.ide.model.runtime.ColabRunner
import dev.forge.ide.model.runtime.DefaultModelEndpointDiscovery
import dev.forge.ide.model.runtime.HostedEndpointRunner
import dev.forge.ide.model.runtime.HttpModelHealthChecker
import dev.forge.ide.model.runtime.ModelConnectionPolicy
import dev.forge.ide.model.runtime.ModelRunner
import dev.forge.ide.model.runtime.RuntimeOutputBuffer
import dev.forge.ide.model.runtime.TunnelProviders
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Wires the Model Manager, its persistence ports and its runners into the
 * container. The gateway is taken from the container (registered by
 * [dev.forge.ide.model.ModelModule]) so the agent side never changes.
 */
class ModelRuntimeModule(
    private val presetStore: ModelPresetStore = InMemoryModelPresetStore(),
    private val secretStore: ModelSecretStore = InMemoryModelSecretStore(),
    private val runtimeOutput: RuntimeOutputBuffer = RuntimeOutputBuffer(),
    private val policy: ModelConnectionPolicy = ModelConnectionPolicy(),
    /** Overrides the default runner set; used by tests. */
    private val runners: List<ModelRunner>? = null,
    private val monitorEnabled: Boolean = true,
) : ForgeModule {

    override val id: String = "model-runtime"

    private var manager: ModelManager? = null

    override fun initialize(context: ModuleContext) {
        val gateway = context.services.get<ModelGateway>(ServiceKeys.MODEL_GATEWAY) ?: DefaultModelGateway()
        val created = ModelManagers.create(
            gateway = gateway,
            presetStore = presetStore,
            secretStore = secretStore,
            runtimeOutput = runtimeOutput,
            policy = policy,
            runners = runners,
            monitorEnabled = monitorEnabled,
            logger = context.logger,
        )
        manager = created
        context.services.register(ServiceKeys.MODEL_MANAGER, created)
    }

    override fun dispose() {
        manager?.close()
        manager = null
    }
}

/** Assembles a [ModelManager] from its ports. Shared by the module and by previews/tests. */
object ModelManagers {

    fun create(
        gateway: ModelGateway = DefaultModelGateway(),
        presetStore: ModelPresetStore = InMemoryModelPresetStore(),
        secretStore: ModelSecretStore = InMemoryModelSecretStore(),
        runtimeOutput: RuntimeOutputBuffer = RuntimeOutputBuffer(),
        policy: ModelConnectionPolicy = ModelConnectionPolicy(),
        runners: List<ModelRunner>? = null,
        monitorEnabled: Boolean = true,
        providerFactory: ModelProviderFactory = OpenAiCompatibleProviderFactory(),
        transport: HttpTransport = UrlConnectionHttpTransport(),
        clock: () -> Long = System::currentTimeMillis,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        logger: ForgeLogger = ForgeLoggers.create(
            level = LogLevel.WARN,
            baseFields = mapOf("component" to "model-manager"),
        ),
    ): ModelManager {
        val repository = DefaultModelPresetRepository(presetStore, clock)
        val credentials = StoreBackedModelCredentialResolver(secretStore)
        val discovery = DefaultModelEndpointDiscovery(TunnelProviders(), runtimeOutput)

        // Both runners share the same discovery and lifecycle implementation; the
        // difference is only whether a runtime the app cannot control is involved.
        val defaultRunners = listOf(
            ColabRunner(
                discovery = discovery,
                credentials = credentials,
                policy = policy,
                healthChecker = HttpModelHealthChecker(transport, clock),
                clock = clock,
                logger = logger,
            ),
            HostedEndpointRunner(
                discovery = discovery,
                credentials = credentials,
                policy = policy,
                healthChecker = HttpModelHealthChecker(transport, clock),
                clock = clock,
                logger = logger,
            ),
        )

        return DefaultModelManager(
            repository = repository,
            runners = runners ?: defaultRunners,
            registry = GatewayModelConnectionRegistry(gateway, providerFactory, logger),
            credentials = credentials,
            secretStore = secretStore,
            logger = logger,
            scope = scope,
            clock = clock,
            ioDispatcher = ioDispatcher,
            monitorEnabled = monitorEnabled,
        )
    }
}
