package com.agentx.app.integrations.providers

import com.agentx.app.core.config.OAuthConfig
import com.agentx.app.integrations.connection.ConnectionProvider
import com.agentx.app.integrations.connection.ConnectionProviderRegistry
import com.agentx.app.integrations.connection.ConnectionType
import com.agentx.app.integrations.oauth.GitHubOAuthProvider
import com.agentx.app.integrations.oauth.InMemoryOAuthSessionStore
import com.agentx.app.integrations.oauth.OAuthFlowRunner
import com.agentx.app.integrations.oauth.OAuthHttpClient
import com.agentx.app.integrations.oauth.OAuthProvider
import com.agentx.app.integrations.oauth.toClientConfig
import com.agentx.app.integrations.oauth.SupabaseOAuthProvider

/**
 * Builds the providers this build can connect to.
 *
 * One place assembles the built-in services, so the app only has to hand over the
 * OAuth configuration and an HTTP client. A provider whose client id and redirect
 * URI are missing is still registered: the Connections page then explains what the
 * app owner has to configure instead of silently hiding the service.
 */
object ConnectionProviders {

    /** The services that ship with the app, in the order the UI shows them. */
    val builtInTypes: List<ConnectionType> = listOf(
        ConnectionType.GITHUB,
        ConnectionType.SUPABASE,
        ConnectionType.MCP_SERVER,
    )

    fun fromConfig(
        config: OAuthConfig,
        http: OAuthHttpClient,
        clock: () -> Long = System::currentTimeMillis,
    ): ConnectionProviderRegistry {
        val github = GitHubOAuthProvider(client = config.github.toClientConfig(), http = http, clock = clock)
        val supabase = SupabaseOAuthProvider(client = config.supabase.toClientConfig(), http = http, clock = clock)
        // Each provider keeps its own pending authorizations, so a callback can only
        // ever complete the service that started it.
        val githubFlow = flowFor(github, clock)
        val supabaseFlow = flowFor(supabase, clock)
        return ConnectionProviderRegistry.builder()
            .register(GitHubConnectionProvider(github, githubFlow, clock))
            .register(SupabaseConnectionProvider(supabase, supabaseFlow, clock))
            .register(McpConnectionProvider(clock))
            .build()
    }

    /** Assembles a registry from already-built providers (used by tests). */
    fun registryOf(vararg providers: ConnectionProvider): ConnectionProviderRegistry =
        ConnectionProviderRegistry.builder().apply { providers.forEach(::register) }.build()

    /**
     * A flow runner that resolves its provider by connection type, so the callback
     * path never needs a provider reference held across requests.
     */
    private fun flowFor(provider: OAuthProvider, clock: () -> Long): OAuthFlowRunner = OAuthFlowRunner(
        sessions = InMemoryOAuthSessionStore(clock),
        clock = clock,
        providerFor = { type -> provider.takeIf { it.descriptor.type == type } },
    )
}
