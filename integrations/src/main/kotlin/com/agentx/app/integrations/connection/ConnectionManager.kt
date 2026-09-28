package com.agentx.app.integrations.connection

import com.agentx.app.core.ForgeError
import com.agentx.app.core.ForgeErrorCode
import com.agentx.app.core.ForgeResult
import com.agentx.app.integrations.oauth.OAuthAuthorizationStart
import kotlinx.coroutines.flow.StateFlow

/** Snapshot the Connections UI renders. Credentials never appear here. */
data class ConnectionManagerState(
    val loading: Boolean = true,
    val connections: List<Connection> = emptyList(),
) {
    val isEmpty: Boolean get() = !loading && connections.isEmpty()

    fun connection(id: ConnectionId): Connection? = connections.firstOrNull { it.id == id }
}

/**
 * Owns saved external-service connections and their credentials.
 *
 * Layering: `Settings → Connections UI → ConnectionManager → External Service
 * → Tool System → Agent`. The Agent never talks to this manager directly and
 * never receives raw credentials; tools request a type plus a capability and
 * receive an [AuthorizedConnection] or a structured refusal.
 *
 * Connections are authorized **OAuth-first**: [connect] / [beginAuthorization]
 * hand the user to the provider's own authorization page, [completeAuthorization]
 * handles the callback and stores the grant, and [disconnect] drops it again.
 * Manual API keys and access tokens remain available for services without OAuth.
 */
interface ConnectionManager {
    val state: StateFlow<ConnectionManagerState>

    /** False when the platform could not offer encrypted storage for credentials. */
    val credentialsPersistent: Boolean

    /** OAuth availability per service, so the UI can offer the right entry point. */
    fun oauthAvailability(): List<ConnectionOAuthAvailability>

    suspend fun refresh()

    suspend fun connection(id: ConnectionId): Connection?

    suspend fun list(): List<Connection>

    suspend fun addConnection(draft: ConnectionDraft): ForgeResult<Connection, ForgeError>

    suspend fun updateConnection(draft: ConnectionDraft): ForgeResult<Connection, ForgeError>

    suspend fun removeConnection(id: ConnectionId): ForgeResult<Unit, ForgeError>

    suspend fun setEnabled(id: ConnectionId, enabled: Boolean): ForgeResult<Connection, ForgeError>

    suspend fun testConnection(id: ConnectionId): ForgeResult<ConnectionTestResult, ForgeError>

    /**
     * Starts the OAuth flow for [type], creating the connection record on first
     * use. The returned URL is the **provider's** authorization page; the
     * connection stays [ConnectionStatus.AUTHORIZING] until the callback arrives.
     */
    suspend fun connect(
        type: ConnectionType,
        displayName: String? = null,
    ): ForgeResult<OAuthAuthorizationStart, ForgeError>

    /** Re-runs authorization for an existing connection (reconnect or re-authorize). */
    suspend fun beginAuthorization(id: ConnectionId): ForgeResult<OAuthAuthorizationStart, ForgeError>

    /**
     * Handles a provider callback: validates `state` (single use) and the PKCE
     * verifier, exchanges the code, stores the grant securely, validates it with
     * the provider and only then marks the connection [ConnectionStatus.CONNECTED].
     */
    suspend fun completeAuthorization(callbackUri: String): ForgeResult<Connection, ForgeError>

    /** Abandons an authorization that is in flight. */
    suspend fun cancelAuthorization(id: ConnectionId): ForgeResult<Connection, ForgeError>

    /**
     * Revokes (best effort) and forgets the stored credentials, leaving the
     * connection record in place so it can be authorized again.
     */
    suspend fun disconnect(id: ConnectionId): ForgeResult<Connection, ForgeError>

    /**
     * Forces a token refresh for a connection whose grant is expiring. When the
     * provider cannot refresh, the connection is marked [ConnectionStatus.EXPIRED]
     * and the user has to re-authorize.
     */
    suspend fun refreshAuthorization(id: ConnectionId): ForgeResult<Connection, ForgeError>

    fun status(id: ConnectionId): ConnectionStatus?

    /**
     * Grants a tool access to a connection of [type] that declares [capability].
     * Returns a handle with a credential *reference*, never the secret.
     */
    suspend fun authorize(
        type: ConnectionType,
        capability: ConnectionCapability,
        connectionId: ConnectionId? = null,
    ): ForgeResult<AuthorizedConnection, ConnectionAuthorizationError>
}

internal fun connectionFailure(
    code: ForgeErrorCode,
    message: String,
    details: Map<String, Any?> = emptyMap(),
): ForgeError = ForgeError(code = code, message = message, details = details)

internal fun connectionNotFound(id: ConnectionId): ForgeError = connectionFailure(
    code = ForgeErrorCode.CONNECTION_NOT_FOUND,
    message = "No connection with id '${id.value}'",
    details = mapOf("connectionId" to id.value),
)
