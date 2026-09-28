package com.agentx.app.integrations.oauth

import com.agentx.app.core.config.OAuthConfig
import com.agentx.app.core.config.OAuthProviderConfig
import com.agentx.app.integrations.connection.ConnectionOAuthAvailability
import com.agentx.app.integrations.connection.ConnectionType

/**
 * The providers this build can authorize against, keyed by connection type.
 *
 * The Connection Manager depends on this registry only, so it stays free of any
 * provider-specific knowledge and new providers are added by registration.
 */
class OAuthProviderRegistry(
    private val providers: Map<ConnectionType, OAuthProvider> = emptyMap(),
) {

    fun provider(type: ConnectionType): OAuthProvider? = providers[type]

    fun descriptor(type: ConnectionType): OAuthProviderDescriptor? = providers[type]?.descriptor

    /** Types that have a provider registered in this build. */
    fun types(): List<ConnectionType> = providers.keys.toList()

    /** Availability of OAuth for [type], including the reason when it is unavailable. */
    fun availability(type: ConnectionType, authorizing: Boolean = false): ConnectionOAuthAvailability {
        val provider = providers[type]
        val supported = type.oauthSupported || provider != null
        val problem = when {
            !supported -> "This service does not offer OAuth."
            provider == null -> "No OAuth provider is registered for ${type.displayName}."
            else -> provider.client.configurationProblem
        }
        return ConnectionOAuthAvailability(
            type = type,
            displayName = type.displayName,
            oauthSupported = supported,
            configured = supported && provider != null && provider.client.isConfigured,
            authorizing = authorizing,
            unavailableReason = problem,
        )
    }

    companion object {
        /** A registry with no providers: every OAuth request is refused, never faked. */
        val EMPTY: OAuthProviderRegistry = OAuthProviderRegistry()

        /**
         * Builds the official GitHub and Supabase providers from configuration.
         *
         * A provider without a client id and redirect URI is still registered, so
         * the UI can explain exactly what the operator has to configure instead of
         * silently hiding the service.
         */
        fun fromConfig(
            config: OAuthConfig,
            http: OAuthHttpClient,
            clock: () -> Long = System::currentTimeMillis,
        ): OAuthProviderRegistry = OAuthProviderRegistry(
            mapOf(
                ConnectionType.GITHUB to GitHubOAuthProvider(
                    client = config.github.toClientConfig(),
                    http = http,
                    clock = clock,
                ),
                ConnectionType.SUPABASE to SupabaseOAuthProvider(
                    client = config.supabase.toClientConfig(),
                    http = http,
                    clock = clock,
                ),
            ),
        )
    }
}

/** Maps configured public client values onto a provider's client config. */
fun OAuthProviderConfig.toClientConfig(): OAuthClientConfig = OAuthClientConfig(
    clientId = clientId,
    redirectUri = redirectUri,
    exchangeBrokerUrl = exchangeBrokerUrl,
    configuredScopes = scopes,
)
