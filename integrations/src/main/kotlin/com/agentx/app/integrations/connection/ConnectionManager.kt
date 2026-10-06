package com.agentx.app.integrations.connection

import com.agentx.app.core.ForgeError
import com.agentx.app.core.ForgeErrorCode
import com.agentx.app.core.ForgeResult
import com.agentx.app.integrations.oauth.DeviceFlowState
import kotlinx.coroutines.flow.StateFlow

/** Current snapshot of the Connection Manager. */
data class ConnectionManagerState(
    val loading: Boolean = true,
    val connections: List<Connection> = emptyList(),
) {
    fun connection(id: ConnectionId): Connection? = connections.firstOrNull { it.id == id }

    fun ofType(type: ConnectionType): List<Connection> = connections.filter { it.type == type }

    /** The connection a tool would use for [type]: the first enabled one. */
    fun preferred(type: ConnectionType): Connection? =
        ofType(type).firstOrNull { it.enabled } ?: ofType(type).firstOrNull()

    val connected: List<Connection> get() = connections.filter { it.status == ConnectionStatus.CONNECTED }
}

/**
 * Owns saved external-service connections and their credentials.
 *
 * Layering: `Connections UI → ConnectionManager → ConnectionProvider → External
 * Service`, and `Agent → Tool → ConnectionManager → provider → service`.
 *
 * The manager is **provider-agnostic**: it moves connections between states, keeps
 * credential payloads in the platform secret store, and delegates every service
 * specific step (authorization start, callback, identity, verification, refresh,
 * revoke, capability mapping) to the [ConnectionProvider] registered for the type.
 *
 * Credentials never leave this layer as values: [ConnectionCredentialGateway]
 * hands one to a service client without exposing it to the agent, the model, the
 * context engine, a tool result or a log.
 */
interface ConnectionManager {
    val state: StateFlow<ConnectionManagerState>

    /** False when the platform could not offer encrypted storage for credentials. */
    val credentialsPersistent: Boolean

    /** Providers this build can connect to, with the reason when one is unavailable. */
    fun providerAvailability(): List<ProviderAvailability>

    /** Public descriptors the Connections UI renders. */
    fun providerDescriptors(): List<ProviderDescriptor>

    /** Tool catalog with the current enablement state for each provider tool. */
    fun tools(): List<InstalledTool>

    /** Tool names the Tool System should have installed right now. */
    fun enabledToolNames(): List<String>

    suspend fun refresh()

    suspend fun connection(id: ConnectionId): Connection?

    suspend fun list(): List<Connection>

    suspend fun addConnection(draft: ConnectionDraft): ForgeResult<Connection, ForgeError>

    suspend fun updateConnection(draft: ConnectionDraft): ForgeResult<Connection, ForgeError>

    suspend fun removeConnection(id: ConnectionId): ForgeResult<Unit, ForgeError>

    suspend fun setEnabled(id: ConnectionId, enabled: Boolean): ForgeResult<Connection, ForgeError>

    suspend fun testConnection(id: ConnectionId): ForgeResult<ConnectionTestResult, ForgeError>

    /**
     * Starts the connection flow for [type], creating the record on first use.
     *
     * Returns either a URL for the provider's own authorization page or, for a
     * service with no hosted authorization, the instruction to use the app's form.
     * Nothing is marked connected here.
     */
    suspend fun connect(
        type: ConnectionType,
        displayName: String? = null,
    ): ForgeResult<AuthorizationStart, ForgeError>

    /** Re-runs authorization for an existing connection (reconnect or re-authorize). */
    suspend fun beginAuthorization(id: ConnectionId): ForgeResult<AuthorizationStart, ForgeError>

    /**
     * True when [type] can be authorized with a device code the user approves on
     * the provider's page, without a browser redirect back to the app.
     */
    fun supportsDeviceAuthorization(type: ConnectionType): Boolean

    /**
     * Starts a device authorization for [type], creating the connection record on
     * first use. Returns only the user code and verification URI; the device code
     * and any token stay inside the provider.
     */
    suspend fun beginDeviceAuthorization(
        type: ConnectionType,
        displayName: String? = null,
    ): ForgeResult<DeviceAuthorization, ForgeError>

    /**
     * Polls the device authorization until the user approves or the attempt fails,
     * reporting each [DeviceFlowState] transition, then stores the grant through the
     * existing credential infrastructure and marks the connection connected.
     *
     * Cancelling the calling coroutine stops the polling; a failure never deletes
     * the connection.
     */
    suspend fun completeDeviceAuthorization(
        id: ConnectionId,
        onState: suspend (DeviceFlowState) -> Unit = {},
    ): ForgeResult<Connection, ForgeError>

    /**
     * Handles the provider redirect: the provider validates `state` (single use) and
     * the PKCE verifier, exchanges the code, and the manager stores the grant,
     * verifies the identity and only then marks the connection connected.
     */
    suspend fun completeAuthorization(callbackUri: String): ForgeResult<Connection, ForgeError>

    /** Abandons an authorization that is in flight. */
    suspend fun cancelAuthorization(id: ConnectionId): ForgeResult<Connection, ForgeError>

    /**
     * Revokes (best effort) and forgets the stored credentials, clears the grant and
     * returns the connection to not-connected. Tools that needed this connection
     * stop being installed as soon as the state changes.
     */
    suspend fun disconnect(id: ConnectionId): ForgeResult<Connection, ForgeError>

    /**
     * Re-verifies stored credentials, refreshing them when the provider supports it.
     * Failing verification marks the connection expired rather than connected.
     */
    suspend fun refreshAuthorization(id: ConnectionId): ForgeResult<Connection, ForgeError>

    /** Status of one connection, or null when unknown. */
    fun status(id: ConnectionId): ConnectionStatus?

    /**
     * Resolves a connection a tool may use, or a structured refusal. Enforced in
     * order: enabled → connection state → capability → credential presence.
     */
    suspend fun authorize(
        type: ConnectionType,
        capability: ConnectionCapability,
        connectionId: ConnectionId? = null,
    ): ForgeResult<AuthorizedConnection, ConnectionAuthorizationError>
}

/** Resolves connections for tools. Bound to the manager by the app. */
fun interface ToolConnectionAuthorizer {
    suspend fun authorize(type: ConnectionType, capability: ConnectionCapability): ForgeResult<AuthorizedConnection, ConnectionAuthorizationError>
}

/** Why a tool was refused a connection. Safe to surface to the agent. */
enum class ConnectionAuthorizationFailure(val message: String) {
    NOT_FOUND("No connection matches the requested type"),
    DISABLED("The matching connection is disabled"),
    MISSING_CAPABILITY("The connection does not declare the required capability"),
    MISSING_CREDENTIAL("The connection has no stored credential"),
    NOT_AUTHORIZED("The connection is not authorized for this tool"),
    /** The stored grant is no longer usable; the user must re-authorize. */
    EXPIRED("The connection credentials expired and must be re-authorized"),
    /** OAuth authorization has not finished yet. */
    AUTHORIZING("The connection is waiting for the user to approve access"),
}

/** Structured refusal returned by [ConnectionManager.authorize]. */
data class ConnectionAuthorizationError(
    val failure: ConnectionAuthorizationFailure,
    val type: ConnectionType,
    val capability: ConnectionCapability,
    val connectionId: ConnectionId? = null,
    val detail: String? = null,
    override val message: String = detail ?: failure.message,
) : Exception(message) {

    val code: ForgeErrorCode = when (failure) {
        ConnectionAuthorizationFailure.NOT_FOUND -> ForgeErrorCode.CONNECTION_NOT_FOUND
        ConnectionAuthorizationFailure.DISABLED -> ForgeErrorCode.CONNECTION_UNAUTHORIZED
        ConnectionAuthorizationFailure.MISSING_CAPABILITY -> ForgeErrorCode.CONNECTION_UNAUTHORIZED
        ConnectionAuthorizationFailure.MISSING_CREDENTIAL -> ForgeErrorCode.CONNECTION_UNAUTHORIZED
        ConnectionAuthorizationFailure.NOT_AUTHORIZED -> ForgeErrorCode.CONNECTION_UNAUTHORIZED
        ConnectionAuthorizationFailure.EXPIRED -> ForgeErrorCode.CONNECTION_CREDENTIAL_EXPIRED
        ConnectionAuthorizationFailure.AUTHORIZING -> ForgeErrorCode.CONNECTION_UNAUTHORIZED
    }

    override fun toString(): String =
        "ConnectionAuthorizationError(failure=$failure, type=${type.name}, capability=${capability.id})"
}

/**
 * What the Connections page needs to know about a service: whether hosted
 * authorization can start, and why not when it cannot.
 */
data class ConnectionOAuthAvailability(
    val type: ConnectionType,
    val displayName: String,
    val oauthSupported: Boolean,
    /** True when this build has the provider's client id and redirect URI. */
    val configured: Boolean,
    /** True when an authorization is currently in flight for this service. */
    val authorizing: Boolean = false,
    val unavailableReason: String? = null,
) {
    /** True when the page may offer a "Connect" button. */
    val canAuthorize: Boolean get() = oauthSupported && configured && !authorizing
}

/** Builds a typed connection failure with non-secret details. */
fun connectionFailure(
    code: ForgeErrorCode,
    message: String,
    details: Map<String, Any?> = emptyMap(),
): ForgeError = ForgeError(
    code = code,
    message = message,
    details = details.filterValues { it != null },
)

/** Convenience: the failure a missing connection produces. */
fun connectionNotFound(id: ConnectionId): ForgeError = connectionFailure(
    code = ForgeErrorCode.CONNECTION_NOT_FOUND,
    message = "No connection with this id exists",
    details = mapOf("connectionId" to id.value),
)
