package com.agentx.app.integrations.setup

import com.agentx.app.core.ForgeError
import com.agentx.app.core.ForgeErrorCode
import com.agentx.app.core.ForgeResult
import com.agentx.app.core.config.OAuthConfig
import com.agentx.app.core.config.OAuthProviderConfig
import com.agentx.app.core.failure
import com.agentx.app.core.success
import com.agentx.app.integrations.connection.Connection
import com.agentx.app.integrations.connection.ConnectionType
import com.agentx.app.integrations.connection.connectionFailure
import com.agentx.app.integrations.oauth.GitHubOAuthProvider
import com.agentx.app.integrations.oauth.OAuthCallbackAuthority
import com.agentx.app.integrations.oauth.OAuthClientConfig
import com.agentx.app.integrations.oauth.OAuthProvider
import com.agentx.app.integrations.oauth.SupabaseOAuthProvider
import com.agentx.app.integrations.oauth.toClientConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Owns the personal, one-time provider setup for this IDE.
 *
 * Client IDs and callback URIs live here. Authorization, credentials and
 * connection state stay on [com.agentx.app.integrations.connection.ConnectionManager].
 * Saving a Client ID never marks a provider connected.
 */
class IntegrationSetupManager(
    private val store: IntegrationSetupStore,
    private val fallback: OAuthConfig = OAuthConfig(),
    private val callbacks: OAuthCallbackAuthority = OAuthCallbackAuthority.DEFAULT,
    private val github: GitHubOAuthProvider? = null,
    private val supabase: SupabaseOAuthProvider? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val mutableState = MutableStateFlow(IntegrationSetupState())

    val state: StateFlow<IntegrationSetupState> = mutableState.asStateFlow()

    val callbackAuthority: OAuthCallbackAuthority get() = callbacks

    fun callbackUri(type: ConnectionType): String = callbacks.uriFor(type)

    fun guide(type: ConnectionType): ProviderSetupGuide = IntegrationSetupGuides.forType(type)

    suspend fun refresh() {
        val loaded = store.loadAll()
        applyToProviders(loaded)
        mutableState.value = IntegrationSetupState(loaded = true, setups = loaded)
    }

    fun snapshot(
        type: ConnectionType,
        connection: Connection? = null,
    ): ProviderSetupSnapshot {
        val resolved = resolvedClient(type)
        val validation = validationFor(type, resolved.clientId, resolved.exchangeBrokerUrl)
        return ProviderSetupSnapshot(
            type = type,
            lifecycle = integrationLifecycleOf(configured = validation.complete, connection = connection),
            clientId = resolved.clientId,
            callbackUri = callbacks.uriFor(type),
            exchangeBrokerUrl = resolved.exchangeBrokerUrl,
            accountLabel = connection?.accountLabel,
            statusMessage = connection?.statusMessage,
            validation = validation,
        )
    }

    fun validation(type: ConnectionType): SetupValidation {
        val resolved = resolvedClient(type)
        return validationFor(type, resolved.clientId, resolved.exchangeBrokerUrl)
    }

    /**
     * Only the services with a personal OAuth app need a Client ID. A service the
     * user describes in the app (MCP, custom API) is configured as soon as it is
     * registered, so it is never held back by a form it does not have.
     */
    private fun validationFor(
        type: ConnectionType,
        clientId: String,
        brokerUrl: String?,
    ): SetupValidation = if (usesPersonalOAuthSetup(type)) {
        validatePersonalSetup(
            type = type,
            clientId = clientId,
            callbackUri = callbacks.uriFor(type),
            brokerUrl = brokerUrl,
        )
    } else {
        SetupValidation(type = type)
    }

    /**
     * Persists the public Client ID for [type] and hot-applies it to the live
     * OAuth provider. Never stores a client secret.
     */
    suspend fun saveSetup(
        type: ConnectionType,
        clientId: String,
        exchangeBrokerUrl: String? = null,
        scopes: Set<String> = emptySet(),
    ): ForgeResult<PersonalOAuthSetup, ForgeError> {
        if (type != ConnectionType.GITHUB && type != ConnectionType.SUPABASE) {
            return failure(
                connectionFailure(
                    code = ForgeErrorCode.CONNECTION_INVALID,
                    message = "${type.displayName} is not configured with an OAuth Client ID.",
                    details = mapOf("type" to type.name),
                ),
            )
        }
        val trimmedId = clientId.trim()
        val trimmedBroker = exchangeBrokerUrl?.trim()?.takeIf { it.isNotBlank() }
        val check = validatePersonalSetup(
            type = type,
            clientId = trimmedId,
            callbackUri = callbacks.uriFor(type),
            brokerUrl = trimmedBroker,
        )
        // A half-configured provider would show as configured and then fail at the
        // provider, so an incomplete setup is refused with the reason instead of
        // being saved.
        if (!check.complete) {
            return failure(
                connectionFailure(
                    code = ForgeErrorCode.CONNECTION_INVALID,
                    message = check.summary,
                    details = mapOf("type" to type.name, "errors" to check.problems),
                ),
            )
        }
        val setup = PersonalOAuthSetup(
            clientId = trimmedId,
            exchangeBrokerUrl = trimmedBroker,
            scopes = scopes,
            updatedAtMillis = clock(),
        )
        store.save(type, setup)
        applyToProvider(type, setup)
        mutableState.update { current ->
            current.copy(setups = current.setups + (type to setup))
        }
        return success(setup)
    }

    suspend fun clearSetup(type: ConnectionType): ForgeResult<Unit, ForgeError> {
        store.clear(type)
        applyToProvider(type, setup = null)
        mutableState.update { current ->
            current.copy(setups = current.setups - type)
        }
        return success(Unit)
    }

    fun oauthProvider(type: ConnectionType): OAuthProvider? = when (type) {
        ConnectionType.GITHUB -> github
        ConnectionType.SUPABASE -> supabase
        else -> null
    }

    /** Public client values currently in effect: personal setup, then build fallback. */
    fun resolvedClient(type: ConnectionType): OAuthClientConfig {
        val redirect = callbacks.uriFor(type)
        val personal = mutableState.value.setups[type]
        val fallbackConfig = fallback.provider(type)
        return if (personal != null && personal.clientId.isNotBlank()) {
            personal.toClientConfig(redirect, fallbackConfig)
        } else {
            fallbackConfig.toClientConfig().copy(redirectUri = redirect.ifBlank { fallbackConfig.redirectUri })
        }
    }

    private fun applyToProviders(loaded: Map<ConnectionType, PersonalOAuthSetup>) {
        applyToProvider(ConnectionType.GITHUB, loaded[ConnectionType.GITHUB])
        applyToProvider(ConnectionType.SUPABASE, loaded[ConnectionType.SUPABASE])
    }

    private fun applyToProvider(type: ConnectionType, setup: PersonalOAuthSetup?) {
        val client = when {
            setup != null && setup.clientId.isNotBlank() ->
                setup.toClientConfig(callbacks.uriFor(type), fallback.provider(type))
            else -> fallback.provider(type).toClientConfig().copy(
                redirectUri = callbacks.uriFor(type).ifBlank { fallback.provider(type).redirectUri },
            )
        }
        when (type) {
            ConnectionType.GITHUB -> github?.replaceClient(client)
            ConnectionType.SUPABASE -> supabase?.replaceClient(client)
            else -> Unit
        }
    }
}

data class IntegrationSetupState(
    val loaded: Boolean = false,
    val setups: Map<ConnectionType, PersonalOAuthSetup> = emptyMap(),
)

fun OAuthProviderConfig.withRedirect(redirectUri: String): OAuthClientConfig =
    toClientConfig().copy(redirectUri = redirectUri.ifBlank { this.redirectUri })
