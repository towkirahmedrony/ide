package com.agentx.app.integrations.oauth

import com.agentx.app.integrations.connection.ConnectionCapability
import com.agentx.app.integrations.connection.ConnectionType

/**
 * One authorization standard implementation (GitHub, Supabase, ...).
 *
 * The Connection Manager only ever talks to this port, never to a provider's
 * HTTP surface directly, so adding a provider means implementing this interface
 * and registering it — no change to the manager, the UI or the tool system.
 *
 * Implementations must not log codes, verifiers or tokens.
 */
interface OAuthProvider {

    val descriptor: OAuthProviderDescriptor

    /** Public client values. Never a client secret. */
    val client: OAuthClientConfig

    /** Provider scopes needed to satisfy [capabilities]. */
    fun scopesFor(capabilities: Set<ConnectionCapability>): Set<String>

    /**
     * The scopes that were really granted. Providers that echo `scope` in the
     * token response report it there; providers whose scopes are fixed when the
     * OAuth app is registered (Supabase) fall back to [OAuthClientConfig.configuredScopes].
     */
    fun grantedScopes(tokens: OAuthTokenSet): Set<String> =
        tokens.scopes.ifEmpty { client.configuredScopes }

    /**
     * Capabilities the authorization actually granted. Only scopes in [scopes]
     * may produce a capability, so a connection never claims more access than the
     * user approved.
     */
    fun capabilitiesFor(scopes: Set<String>): Set<ConnectionCapability>

    /** The URL the user is sent to, with state and PKCE parameters applied. */
    fun authorizationUrl(request: OAuthAuthorizationRequest): String

    /** Exchanges an authorization code for tokens. */
    suspend fun exchange(code: String, codeVerifier: String?, redirectUri: String): OAuthTokenResult

    /** Exchanges a refresh token for new tokens. */
    suspend fun refresh(refreshToken: String): OAuthTokenResult

    /** Confirms the grant works and reports which account it belongs to. */
    suspend fun validate(tokens: OAuthTokenSet): OAuthValidation

    /** Asks the provider to revoke the grant (best effort at disconnect). */
    suspend fun revoke(tokens: OAuthTokenSet): OAuthRevocationResult
}
