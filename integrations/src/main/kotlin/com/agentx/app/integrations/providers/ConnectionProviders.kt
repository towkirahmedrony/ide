package com.agentx.app.integrations.providers

import com.agentx.app.core.config.OAuthConfig
import com.agentx.app.integrations.connection.ConnectionProvider
import com.agentx.app.integrations.connection.ConnectionProviderRegistry
import com.agentx.app.integrations.connection.ConnectionType
import com.agentx.app.integrations.oauth.GitHubOAuthProvider
import com.agentx.app.integrations.oauth.InMemoryOAuthSessionStore
import com.agentx.app.integrations.oauth.OAuthCallbackAuthority
import com.agentx.app.integrations.oauth.OAuthFlowRunner
import com.agentx.app.integrations.oauth.OAuthHttpClient
import com.agentx.app.integrations.oauth.OAuthProvider
import com.agentx.app.integrations.oauth.toClientConfig
import com.agentx.app.integrations.oauth.SupabaseOAuthProvider
import com.agentx.app.integrations.setup.InMemoryIntegrationSetupStore
import com.agentx.app.integrations.setup.IntegrationSetupManager
import com.agentx.app.integrations.setup.IntegrationSetupStore

/**
 * Builds the providers this personal IDE can connect to.
 *
 * One place assembles GitHub, Supabase and MCP. A provider whose Client ID is
 * missing is still registered: the Connections page then offers Set Up instead
 * of silently hiding the service.
 */
object ConnectionProviders {

    val builtInTypes: List<ConnectionType> = listOf(
        ConnectionType.GITHUB,
        ConnectionType.SUPABASE,
        ConnectionType.MCP_SERVER,
    )

    fun fromConfig(
        config: OAuthConfig,
        http: OAuthHttpClient,
        callbacks: OAuthCallbackAuthority = OAuthCallbackAuthority.DEFAULT,
        setupStore: IntegrationSetupStore = InMemoryIntegrationSetupStore(),
        clock: () -> Long = System::currentTimeMillis,
    ): BuiltIntegrations {
        val githubClient = config.github.toClientConfig().copy(
            redirectUri = callbacks.uriFor(ConnectionType.GITHUB).ifBlank { config.github.redirectUri },
        )
        val supabaseClient = config.supabase.toClientConfig().copy(
            redirectUri = callbacks.uriFor(ConnectionType.SUPABASE).ifBlank { config.supabase.redirectUri },
        )
        val github = GitHubOAuthProvider(client = githubClient, http = http, clock = clock)
        val supabase = SupabaseOAuthProvider(client = supabaseClient, http = http, clock = clock)
        val githubFlow = flowFor(github, clock)
        val supabaseFlow = flowFor(supabase, clock)
        val registry = ConnectionProviderRegistry.builder()
            .register(GitHubConnectionProvider(github, githubFlow, clock))
            .register(SupabaseConnectionProvider(supabase, supabaseFlow, clock))
            .register(McpConnectionProvider(clock))
            .build()
        val setup = IntegrationSetupManager(
            store = setupStore,
            fallback = config,
            callbacks = callbacks,
            github = github,
            supabase = supabase,
            clock = clock,
        )
        return BuiltIntegrations(registry = registry, setup = setup, github = github, supabase = supabase)
    }

    fun registryOf(vararg providers: ConnectionProvider): ConnectionProviderRegistry =
        ConnectionProviderRegistry.builder().apply { providers.forEach(::register) }.build()

    private fun flowFor(provider: OAuthProvider, clock: () -> Long): OAuthFlowRunner = OAuthFlowRunner(
        sessions = InMemoryOAuthSessionStore(clock),
        clock = clock,
        providerFor = { type -> provider.takeIf { it.descriptor.type == type } },
    )
}

/** Wired GitHub/Supabase/MCP plus the personal setup manager that owns Client IDs. */
data class BuiltIntegrations(
    val registry: ConnectionProviderRegistry,
    val setup: IntegrationSetupManager,
    val github: GitHubOAuthProvider,
    val supabase: SupabaseOAuthProvider,
)
