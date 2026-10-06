package com.agentx.app.integrations

import com.agentx.app.core.foundation.ServiceKeys
import com.agentx.app.core.module.ForgeModule
import com.agentx.app.core.module.ModuleContext
import com.agentx.app.integrations.connection.ConnectionCredentialGateway
import com.agentx.app.integrations.connection.ConnectionManager
import com.agentx.app.integrations.connection.ConnectionProviderRegistry
import com.agentx.app.integrations.connection.ConnectionSecretStore
import com.agentx.app.integrations.connection.ConnectionStore
import com.agentx.app.integrations.connection.ConnectionTester
import com.agentx.app.integrations.connection.ConnectionType
import com.agentx.app.integrations.connection.DefaultConnectionManager
import com.agentx.app.integrations.connection.DispatchingConnectionTester
import com.agentx.app.integrations.connection.InMemoryConnectionSecretStore
import com.agentx.app.integrations.connection.InMemoryConnectionStore
import com.agentx.app.integrations.github.GitHubRepositoryServiceKeys
import com.agentx.app.integrations.github.GitHubRepositoryServices
import com.agentx.app.integrations.setup.IntegrationSetupManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Wires the Connection Manager and the integration registry into the container.
 *
 * Providers are optional: a build without the official client ids registers the
 * built-in providers anyway, and they explain what is missing instead of
 * reporting a connection that does not work. Service testers are optional in the
 * same way, and a missing tester never reports connected.
 */
class IntegrationsModule(
    private val connectionStore: ConnectionStore = InMemoryConnectionStore(),
    private val secretStore: ConnectionSecretStore = InMemoryConnectionSecretStore(),
    private val tester: ConnectionTester = DispatchingConnectionTester(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val providers: ConnectionProviderRegistry = ConnectionProviderRegistry.EMPTY,
    private val setup: IntegrationSetupManager? = null,
) : ForgeModule {

    override val id: String = "integrations"

    override fun initialize(context: ModuleContext) {
        val created = ConnectionManagers.create(
            store = connectionStore,
            secrets = secretStore,
            tester = tester,
            ioDispatcher = ioDispatcher,
            providers = providers,
        )
        val registry = DefaultIntegrationRegistry()
        context.services.register(ServiceKeys.CONNECTION_MANAGER, created)
        // The manager is also the credential gateway: service clients ask it for a
        // credential and it lends one over without publishing it.
        context.services.register(ServiceKeys.CONNECTION_CREDENTIAL_GATEWAY, created)
        context.services.register(ServiceKeys.CONNECTION_PROVIDER_REGISTRY, providers)
        context.services.register(ServiceKeys.INTEGRATION_REGISTRY, registry)
        setup?.let { context.services.register(ServiceKeys.INTEGRATION_SETUP, it) }

        // GitHub repository discovery and authenticated clone (Phase 2) hang off that
        // same credential gateway, so they are published only when the GitHub provider
        // is registered: a build without GitHub never advertises a service that cannot
        // work. They are read back from the container rather than captured, so a later
        // rebinding of the gateway is picked up instead of served from a stale handle.
        val credentialGateway =
            context.services.get<ConnectionCredentialGateway>(ServiceKeys.CONNECTION_CREDENTIAL_GATEWAY)
        if (credentialGateway != null && providers.provider(ConnectionType.GITHUB) != null) {
            val github = GitHubRepositoryServices.create(credentialGateway = credentialGateway)
            context.services.register(GitHubRepositoryServiceKeys.REPOSITORY_SERVICE, github.repositoryService)
            context.services.register(GitHubRepositoryServiceKeys.CLONE_SERVICE, github.cloneService)
            context.services.register(
                GitHubRepositoryServiceKeys.DESTINATION_VALIDATOR,
                github.destinationValidator,
            )
        }
    }
}

/** Assembles a [ConnectionManager] from its ports. Shared by the module and tests. */
object ConnectionManagers {

    fun create(
        store: ConnectionStore = InMemoryConnectionStore(),
        secrets: ConnectionSecretStore = InMemoryConnectionSecretStore(),
        tester: ConnectionTester = com.agentx.app.integrations.connection.UnsupportedConnectionTester(),
        clock: () -> Long = System::currentTimeMillis,
        idFactory: () -> String = { java.util.UUID.randomUUID().toString() },
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        providers: ConnectionProviderRegistry = ConnectionProviderRegistry.EMPTY,
    ): ConnectionManager = DefaultConnectionManager(
        store = store,
        secrets = secrets,
        tester = tester,
        providers = providers,
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
