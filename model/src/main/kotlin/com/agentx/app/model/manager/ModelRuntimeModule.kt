package com.agentx.app.model.manager

import com.agentx.app.core.foundation.ServiceKeys
import com.agentx.app.core.logging.ForgeLogger
import com.agentx.app.core.logging.ForgeLoggers
import com.agentx.app.core.logging.LogLevel
import com.agentx.app.core.module.ForgeModule
import com.agentx.app.core.module.ModuleContext
import com.agentx.app.model.DefaultModelGateway
import com.agentx.app.model.ModelGateway
import com.agentx.app.model.http.HttpTransport
import com.agentx.app.model.http.UrlConnectionHttpTransport
import com.agentx.app.model.preset.DefaultModelPresetRepository
import com.agentx.app.model.preset.InMemoryModelPresetStore
import com.agentx.app.model.preset.InMemoryModelSecretStore
import com.agentx.app.model.preset.ModelPresetStore
import com.agentx.app.model.preset.ModelSecretStore
import com.agentx.app.model.preset.StoreBackedModelCredentialResolver
import com.agentx.app.model.runtime.ColabRunner
import com.agentx.app.model.runtime.DefaultModelEndpointDiscovery
import com.agentx.app.model.runtime.HostedEndpointRunner
import com.agentx.app.model.runtime.HttpModelHealthChecker
import com.agentx.app.model.runtime.ModelConnectionPolicy
import com.agentx.app.model.runtime.ModelRunner
import com.agentx.app.model.runtime.RuntimeOutputBuffer
import com.agentx.app.model.runtime.TunnelProviders
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Wires the Model Manager, its persistence ports and its runners into the
 * container. The gateway is taken from the container (registered by
 * [com.agentx.app.model.ModelModule]) so the agent side never changes.
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
