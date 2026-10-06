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
import com.agentx.app.integrations.connection.DefaultConnectionManager
import com.agentx.app.integrations.connection.DispatchingConnectionTester
import com.agentx.app.integrations.connection.InMemoryConnectionSecretStore
import com.agentx.app.integrations.connection.InMemoryConnectionStore
import com.agentx.app.integrations.github.*
import com.agentx.app.integrations.setup.IntegrationSetupManager
import com.agentx.app.integrations.connection.ConnectionType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.io.File

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
        // credential and it lends one without publishing it.
        context.services.register(ServiceKeys.CONNECTION_CREDENTIAL_GATEWAY, created)
        context.services.register(ServiceKeys.CONNECTION_PROVIDER_REGISTRY, providers)
        context.services.register(ServiceKeys.INTEGRATION_REGISTRY, registry)
        setup?.let { context.services.register(ServiceKeys.INTEGRATION_SETUP, it) }

        // GitHub repository service (Phase 2). Only wired when the GitHub provider
        // is registered, because it needs the credential gateway.
        if (providers.provider(ConnectionType.GITHUB) != null) {
            val managedProjectsRoot = context.config.getString(
                "managedProjectsRoot",
                "${context.services.get<File>(com.agentx.app.workspace.ServiceKeys.WORKSPACE_MANAGER_FILES_DIR)}/projects",
            )
            val validator = CloneDestinationValidator(File(managedProjectsRoot))
            val restClient = UrlConnectionGitHubRestClient()
            val repositoryService = GitHubRepositoryServiceImpl(
                credentialGateway = created,
                restClient = restClient,
            )
            val cloneService = JGitGitHubRepositoryCloneService(
                credentialGateway = created,
                validator = validator,
            )
            context.services.register(ServiceKeys.GITHUB_REPOSITORY_SERVICE, repositoryService)
            context.services.register(ServiceKeys.GITHUB_REPOSITORY_CLONE_SERVICE, cloneService)
            context.services.register(ServiceKeys.GITHUB_REPOSITORY_VALIDATOR, validator)
        }
    }
}
