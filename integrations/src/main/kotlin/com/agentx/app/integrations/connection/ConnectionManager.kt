package com.agentx.app.integrations.connection

import com.agentx.app.core.ForgeError
import com.agentx.app.core.ForgeErrorCode
import com.agentx.app.core.ForgeResult
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
 */
interface ConnectionManager {
    val state: StateFlow<ConnectionManagerState>

    /** False when the platform could not offer encrypted storage for credentials. */
    val credentialsPersistent: Boolean

    suspend fun refresh()

    suspend fun connection(id: ConnectionId): Connection?

    suspend fun list(): List<Connection>

    suspend fun addConnection(draft: ConnectionDraft): ForgeResult<Connection, ForgeError>

    suspend fun updateConnection(draft: ConnectionDraft): ForgeResult<Connection, ForgeError>

    suspend fun removeConnection(id: ConnectionId): ForgeResult<Unit, ForgeError>

    suspend fun setEnabled(id: ConnectionId, enabled: Boolean): ForgeResult<Connection, ForgeError>

    suspend fun testConnection(id: ConnectionId): ForgeResult<ConnectionTestResult, ForgeError>

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
