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
import com.agentx.app.integrations.oauth.InMemoryOAuthSessionStore
import com.agentx.app.integrations.oauth.OAuthAuthorizer
import com.agentx.app.integrations.oauth.OAuthProviderRegistry
import com.agentx.app.integrations.oauth.OAuthSessionStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Wires the Connection Manager and the integration registry into the container.
 *
 * OAuth providers are optional: a build without the official client ids registers
 * providers that explain what is missing instead of reporting a fake connection.
 * Service-specific testers are optional in the same way.
 */
class IntegrationsModule(
    private val connectionStore: ConnectionStore = InMemoryConnectionStore(),
    private val secretStore: ConnectionSecretStore = InMemoryConnectionSecretStore(),
    private val tester: ConnectionTester = DispatchingConnectionTester(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val oauthProviders: OAuthProviderRegistry = OAuthProviderRegistry.EMPTY,
    private val oauthSessions: OAuthSessionStore = InMemoryOAuthSessionStore(),
) : ForgeModule {

    override val id: String = "integrations"

    override fun initialize(context: ModuleContext) {
        val created = ConnectionManagers.create(
            store = connectionStore,
            secrets = secretStore,
            tester = tester,
            ioDispatcher = ioDispatcher,
            oauth = OAuthAuthorizer(
                providers = oauthProviders,
                sessions = oauthSessions,
                secrets = secretStore,
            ),
        )
        val registry = DefaultIntegrationRegistry()
        context.services.register(ServiceKeys.CONNECTION_MANAGER, created)
        // The manager is also the credential gateway: service clients ask it for a
        // credential, and it hands one over without publishing it.
        context.services.register(ServiceKeys.CONNECTION_CREDENTIAL_GATEWAY, created)
        context.services.register(ServiceKeys.INTEGRATION_REGISTRY, registry)
        context.services.register(ServiceKeys.OAUTH_PROVIDER_REGISTRY, oauthProviders)
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
        oauthProviders: OAuthProviderRegistry = OAuthProviderRegistry.EMPTY,
        oauthSessions: OAuthSessionStore = InMemoryOAuthSessionStore(clock),
        oauth: OAuthAuthorizer = OAuthAuthorizer(
            providers = oauthProviders,
            sessions = oauthSessions,
            secrets = secrets,
            clock = clock,
        ),
    ): ConnectionManager = DefaultConnectionManager(
        store = store,
        secrets = secrets,
        tester = tester,
        clock = clock,
        idFactory = idFactory,
        ioDispatcher = ioDispatcher,
        oauth = oauth,
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
