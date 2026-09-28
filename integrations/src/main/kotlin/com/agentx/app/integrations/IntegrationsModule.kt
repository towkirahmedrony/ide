package com.agentx.app.integrations

import com.agentx.app.core.foundation.ServiceKeys
import com.agentx.app.core.module.ForgeModule
import com.agentx.app.core.module.ModuleContext
import com.agentx.app.integrations.connection.ConnectionManager
import com.agentx.app.integrations.connection.ConnectionSecretStore
import com.agentx.app.integrations.connection.ConnectionStore
import com.agentx.app.integrations.connection.ConnectionTester
import com.agentx.app.integrations.connection.DefaultConnectionManager
import com.agentx.app.integrations.connection.DispatchingConnectionTester
import com.agentx.app.integrations.connection.InMemoryConnectionSecretStore
import com.agentx.app.integrations.connection.InMemoryConnectionStore
import com.agentx.app.integrations.connection.UnsupportedConnectionTester
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Wires the Connection Manager and the integration registry into the container.
 * Service-specific testers are optional; missing ones never report connected.
 */
class IntegrationsModule(
    private val connectionStore: ConnectionStore = InMemoryConnectionStore(),
    private val secretStore: ConnectionSecretStore = InMemoryConnectionSecretStore(),
    private val tester: ConnectionTester = DispatchingConnectionTester(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ForgeModule {

    override val id: String = "integrations"

    private var manager: ConnectionManager? = null

    override fun initialize(context: ModuleContext) {
        val created = ConnectionManagers.create(
            store = connectionStore,
            secrets = secretStore,
            tester = tester,
            ioDispatcher = ioDispatcher,
        )
        manager = created
        val registry = DefaultIntegrationRegistry()
        context.services.register(ServiceKeys.CONNECTION_MANAGER, created)
        context.services.register(ServiceKeys.INTEGRATION_REGISTRY, registry)
    }
}

/** Assembles a [ConnectionManager] from its ports. Shared by the module and by tests. */
object ConnectionManagers {

    fun create(
        store: ConnectionStore = InMemoryConnectionStore(),
        secrets: ConnectionSecretStore = InMemoryConnectionSecretStore(),
        tester: ConnectionTester = UnsupportedConnectionTester(),
        clock: () -> Long = System::currentTimeMillis,
        idFactory: () -> String = { java.util.UUID.randomUUID().toString() },
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): ConnectionManager = DefaultConnectionManager(
        store = store,
        secrets = secrets,
        tester = tester,
        clock = clock,
        idFactory = idFactory,
        ioDispatcher = ioDispatcher,
    )
}

/** In-memory [IntegrationRegistry] used until later tasks register live integrations. */
class DefaultIntegrationRegistry : IntegrationRegistry {

    private val items = LinkedHashMap<String, Integration>()

    override fun register(integration: Integration) {
        items[integration.descriptor.id] = integration
    }

    override fun descriptors(): List<IntegrationDescriptor> = items.values.map { it.descriptor }
}
