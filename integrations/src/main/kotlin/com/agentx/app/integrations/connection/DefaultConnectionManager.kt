package com.agentx.app.integrations.connection

import com.agentx.app.core.ForgeError
import com.agentx.app.core.ForgeErrorCode
import com.agentx.app.core.ForgeResult
import com.agentx.app.core.failure
import com.agentx.app.core.success
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Default [ConnectionManager].
 *
 * Credentials are written only to [ConnectionSecretStore]. Public APIs, logs
 * and the in-memory state carry a reference (or a presence flag), never the
 * secret. Unsupported testers cannot report [ConnectionStatus.CONNECTED].
 */
class DefaultConnectionManager(
    private val store: ConnectionStore,
    private val secrets: ConnectionSecretStore,
    private val tester: ConnectionTester = UnsupportedConnectionTester(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ConnectionManager {

    private val mutableState = MutableStateFlow(ConnectionManagerState())

    override val state: StateFlow<ConnectionManagerState> = mutableState.asStateFlow()

    override val credentialsPersistent: Boolean get() = secrets.persistent

    override suspend fun refresh() {
        mutableState.update { it.copy(loading = true) }
        val loaded = io { store.load().sorted() }
        mutableState.update { ConnectionManagerState(loading = false, connections = loaded) }
    }

    override suspend fun connection(id: ConnectionId): Connection? =
        current().firstOrNull { it.id == id } ?: io { store.load().firstOrNull { it.id == id } }

    override suspend fun list(): List<Connection> {
        if (mutableState.value.loading) refresh()
        return current()
    }

    override suspend fun addConnection(draft: ConnectionDraft): ForgeResult<Connection, ForgeError> {
        val existing = io { store.load() }
        duplicate(draft, existing, excluding = null)?.let { return failure(it) }

        val id = draft.id ?: ConnectionId(idFactory())
        if (existing.any { it.id == id }) {
            return failure(
                connectionFailure(
                    code = ForgeErrorCode.CONNECTION_DUPLICATE,
                    message = "A connection with this id already exists",
                    details = mapOf("connectionId" to id.value),
                ),
            )
        }

        val credentialRef = storeNewCredential(id, draft.credential)
        val connection = draft.toConnection(
            id = id,
            existing = null,
            credentialRef = credentialRef,
            now = clock(),
        )
        invalid(connection)?.let { error ->
            credentialRef?.let { io { secrets.remove(it) } }
            return failure(error)
        }
        io { store.save(connection) }
        reload()
        return success(connection)
    }

    override suspend fun updateConnection(draft: ConnectionDraft): ForgeResult<Connection, ForgeError> {
        val id = draft.id ?: return failure(
            connectionFailure(
                code = ForgeErrorCode.CONNECTION_INVALID,
                message = "A connection id is required to update",
            ),
        )
        val existingList = io { store.load() }
        val existing = existingList.firstOrNull { it.id == id }
            ?: return failure(connectionNotFound(id))

        duplicate(draft, existingList, excluding = id)?.let { return failure(it) }

        val credentialRef = when {
            draft.clearCredential -> {
                existing.credentialRef?.let { io { secrets.remove(it) } }
                null
            }
            !draft.credential.isNullOrBlank() -> {
                val ref = existing.credentialRef ?: credentialRefFor(id)
                io { secrets.put(ref, draft.credential) }
                ref
            }
            else -> existing.credentialRef
        }

        val connection = draft.toConnection(
            id = id,
            existing = existing,
            credentialRef = credentialRef,
            now = clock(),
        )
        invalid(connection)?.let { return failure(it) }
        io { store.save(connection) }
        reload()
        return success(connection)
    }

    override suspend fun removeConnection(id: ConnectionId): ForgeResult<Unit, ForgeError> {
        val existing = io { store.load() }.firstOrNull { it.id == id }
            ?: return failure(connectionNotFound(id))
        existing.credentialRef?.let { io { secrets.remove(it) } }
        io { store.delete(id) }
        reload()
        return success(Unit)
    }

    override suspend fun setEnabled(id: ConnectionId, enabled: Boolean): ForgeResult<Connection, ForgeError> {
        val existing = io { store.load() }.firstOrNull { it.id == id }
            ?: return failure(connectionNotFound(id))
        val updated = existing.copy(enabled = enabled, updatedAtMillis = clock())
        io { store.save(updated) }
        reload()
        return success(updated)
    }

    override suspend fun testConnection(id: ConnectionId): ForgeResult<ConnectionTestResult, ForgeError> {
        val existing = io { store.load() }.firstOrNull { it.id == id }
            ?: return failure(connectionNotFound(id))
        if (!existing.enabled) {
            return failure(
                connectionFailure(
                    code = ForgeErrorCode.CONNECTION_OPERATION_FAILED,
                    message = "Disabled connections cannot be tested",
                    details = mapOf("connectionId" to id.value),
                ),
            )
        }

        val connecting = existing.copy(
            status = ConnectionStatus.CONNECTING,
            statusMessage = "Testing connection",
            updatedAtMillis = clock(),
        )
        io { store.save(connecting) }
        reload()

        val result = runCatching { tester.test(existing) }.getOrElse { error ->
            ConnectionTestResult(
                status = ConnectionStatus.ERROR,
                message = error.message?.takeIf { it.isNotBlank() } ?: "Connection test failed",
                testedAtMillis = clock(),
            )
        }
        val safeStatus = if (result.status == ConnectionStatus.CONNECTED && result.message == ConnectionTestResult.NOT_IMPLEMENTED) {
            ConnectionStatus.DISCONNECTED
        } else {
            result.status
        }
        val tested = existing.copy(
            status = safeStatus,
            statusMessage = result.message,
            lastTestedAtMillis = result.testedAtMillis,
            updatedAtMillis = clock(),
        )
        io { store.save(tested) }
        reload()

        return if (result.message == ConnectionTestResult.NOT_IMPLEMENTED) {
            failure(
                connectionFailure(
                    code = ForgeErrorCode.CONNECTION_TEST_UNSUPPORTED,
                    message = ConnectionTestResult.NOT_IMPLEMENTED,
                    details = mapOf(
                        "connectionId" to id.value,
                        "type" to existing.type.name,
                    ),
                ),
            )
        } else {
            success(result.copy(status = safeStatus))
        }
    }

    override fun status(id: ConnectionId): ConnectionStatus? =
        mutableState.value.connection(id)?.status

    override suspend fun authorize(
        type: ConnectionType,
        capability: ConnectionCapability,
        connectionId: ConnectionId?,
    ): ForgeResult<AuthorizedConnection, ConnectionAuthorizationError> {
        val loaded = io { store.load() }
        val ofType = loaded.filter { it.type == type }
        val match = if (connectionId != null) {
            loaded.firstOrNull { it.id == connectionId }
        } else {
            ofType.firstOrNull { it.enabled } ?: ofType.firstOrNull()
        }

        if (match == null) {
            return failure(
                ConnectionAuthorizationError(
                    failure = ConnectionAuthorizationFailure.NOT_FOUND,
                    type = type,
                    capability = capability,
                    connectionId = connectionId,
                ),
            )
        }

        if (match.type != type) {
            return failure(
                ConnectionAuthorizationError(
                    failure = ConnectionAuthorizationFailure.NOT_FOUND,
                    type = type,
                    capability = capability,
                    connectionId = match.id,
                    detail = "Connection is not of type ${type.displayName}",
                ),
            )
        }

        if (!match.enabled) {
            return failure(
                ConnectionAuthorizationError(
                    failure = ConnectionAuthorizationFailure.DISABLED,
                    type = type,
                    capability = capability,
                    connectionId = match.id,
                ),
            )
        }

        if (capability !in match.capabilities) {
            return failure(
                ConnectionAuthorizationError(
                    failure = ConnectionAuthorizationFailure.MISSING_CAPABILITY,
                    type = type,
                    capability = capability,
                    connectionId = match.id,
                    detail = "Connection '${match.displayName}' does not declare '${capability.id}'",
                ),
            )
        }

        if (match.config.expectsCredential) {
            val ref = match.credentialRef
            val present = ref != null && io { secrets.contains(ref) }
            if (!present) {
                return failure(
                    ConnectionAuthorizationError(
                        failure = ConnectionAuthorizationFailure.MISSING_CREDENTIAL,
                        type = type,
                        capability = capability,
                        connectionId = match.id,
                    ),
                )
            }
        }

        return success(match.authorizedHandle())
    }

    private fun current(): List<Connection> = mutableState.value.connections

    private suspend fun reload() {
        val loaded = io { store.load().sorted() }
        mutableState.value = ConnectionManagerState(loading = false, connections = loaded)
    }

    private fun invalid(connection: Connection): ForgeError? {
        val errors = connection.validate()
        if (errors.isEmpty()) return null
        return connectionFailure(
            code = ForgeErrorCode.CONNECTION_INVALID,
            message = errors.first(),
            details = mapOf("errors" to errors),
        )
    }

    private fun duplicate(
        draft: ConnectionDraft,
        existing: List<Connection>,
        excluding: ConnectionId?,
    ): ForgeError? {
        val name = draft.displayName.trim()
        val clash = existing.firstOrNull {
            it.id != excluding &&
                it.type == draft.type &&
                it.displayName.trim().equals(name, ignoreCase = true)
        } ?: return null
        return connectionFailure(
            code = ForgeErrorCode.CONNECTION_DUPLICATE,
            message = "A ${draft.type.displayName} connection named '${clash.displayName}' already exists",
            details = mapOf("connectionId" to clash.id.value, "type" to draft.type.name),
        )
    }

    private suspend fun storeNewCredential(id: ConnectionId, credential: String?): String? {
        val secret = credential?.takeIf { it.isNotBlank() } ?: return null
        val ref = credentialRefFor(id)
        io { secrets.put(ref, secret) }
        return ref
    }

    private fun credentialRefFor(id: ConnectionId): String = "connections.secret.${id.value}"

    private suspend fun <T> io(block: suspend () -> T): T = withContext(ioDispatcher) { block() }

    private companion object {
        fun List<Connection>.sorted(): List<Connection> =
            sortedWith(compareBy({ it.createdAtMillis }, { it.displayName.lowercase() }))
    }
}
