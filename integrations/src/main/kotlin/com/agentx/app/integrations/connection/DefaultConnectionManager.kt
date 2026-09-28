package com.agentx.app.integrations.connection

import com.agentx.app.core.ForgeError
import com.agentx.app.core.ForgeErrorCode
import com.agentx.app.core.ForgeResult
import com.agentx.app.core.failure
import com.agentx.app.core.success
import com.agentx.app.core.valueOrNull
import com.agentx.app.integrations.oauth.OAuthAuthorizer
import com.agentx.app.integrations.oauth.OAuthAuthorizationStart
import com.agentx.app.integrations.oauth.OAuthCompletion
import com.agentx.app.integrations.oauth.OAuthValidation
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
 * Credentials are written only to [ConnectionSecretStore]. Public APIs, logs and
 * the in-memory state carry a reference (or a presence flag), never the secret.
 * Unsupported testers cannot report [ConnectionStatus.CONNECTED], and an OAuth
 * connection only reaches [ConnectionStatus.CONNECTED] after the provider
 * returned tokens that were then validated.
 */
class DefaultConnectionManager(
    private val store: ConnectionStore,
    private val secrets: ConnectionSecretStore,
    private val tester: ConnectionTester = UnsupportedConnectionTester(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val oauth: OAuthAuthorizer = OAuthAuthorizer(secrets = secrets),
) : ConnectionManager, ConnectionCredentialGateway {

    private val mutableState = MutableStateFlow(ConnectionManagerState())

    override val state: StateFlow<ConnectionManagerState> = mutableState.asStateFlow()

    override val credentialsPersistent: Boolean get() = secrets.persistent

    override fun oauthAvailability(): List<ConnectionOAuthAvailability> {
        val connections = mutableState.value.connections
        return ConnectionType.entries.map { type ->
            val authorizing = connections.any { it.type == type && it.status.isAuthorizing }
            oauth.availability(type, authorizing)
        }
    }

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
                oauth.cancel(id)
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
        oauth.cancel(id)
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

        val credentialRef = existing.credentialRef
        if (existing.usesOAuth && credentialRef != null) {
            return testOAuthConnection(existing, credentialRef)
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

    override suspend fun connect(
        type: ConnectionType,
        displayName: String?,
    ): ForgeResult<OAuthAuthorizationStart, ForgeError> {
        val existing = io { store.load() }
        val match = existing
            .filter { it.type == type }
            .sortedBy { if (it.enabled) 0 else 1 }
            .firstOrNull()
            ?: return createForAuthorization(type, displayName)

        if (!match.enabled) {
            return failure(
                connectionFailure(
                    code = ForgeErrorCode.CONNECTION_OPERATION_FAILED,
                    message = "The ${type.displayName} connection is disabled; enable it to connect.",
                    details = mapOf("connectionId" to match.id.value),
                ),
            )
        }
        return beginAuthorization(match.id)
    }

    override suspend fun beginAuthorization(id: ConnectionId): ForgeResult<OAuthAuthorizationStart, ForgeError> {
        val existing = io { store.load() }.firstOrNull { it.id == id }
            ?: return failure(connectionNotFound(id))
        if (!existing.enabled) {
            return failure(
                connectionFailure(
                    code = ForgeErrorCode.CONNECTION_OPERATION_FAILED,
                    message = "Disabled connections cannot be authorized",
                    details = mapOf("connectionId" to id.value),
                ),
            )
        }

        // Authorization is an explicit user action, so the connection switches to
        // OAuth. Its declared capabilities are what the scope request is built from.
        val target = if (existing.usesOAuth) existing else existing.copy(
            config = existing.config.copy(authMethod = ConnectionAuthMethod.OAUTH),
            updatedAtMillis = clock(),
        )

        val started = oauth.begin(target)
        val start = started.valueOrNull()
            ?: return failure(started.errorOrNull() ?: connectionFailure(
                code = ForgeErrorCode.CONNECTION_OAUTH_FAILED,
                message = "The authorization request could not be started",
            ))

        val authorizing = target.copy(
            status = ConnectionStatus.AUTHORIZING,
            statusMessage = "Waiting for you to approve access on ${target.type.displayName}",
            updatedAtMillis = clock(),
        )
        io { store.save(authorizing) }
        reload()
        return success(start)
    }

    override suspend fun completeAuthorization(callbackUri: String): ForgeResult<Connection, ForgeError> {
        val completed = oauth.complete(callbackUri)
        val completion = completed.valueOrNull()
        if (completion == null) {
            val error = completed.errorOrNull()
            error?.let { io { markAuthorizationFailure(it) } }
            return failure(error ?: connectionFailure(
                code = ForgeErrorCode.CONNECTION_OAUTH_FAILED,
                message = "The authorization response could not be processed",
            ))
        }
        return saveCompletion(completion)
    }

    override suspend fun cancelAuthorization(id: ConnectionId): ForgeResult<Connection, ForgeError> {
        val existing = io { store.load() }.firstOrNull { it.id == id }
            ?: return failure(connectionNotFound(id))
        oauth.cancel(id)
        if (!existing.status.isAuthorizing) return success(existing)
        val cancelled = existing.copy(
            status = ConnectionStatus.NOT_CONNECTED,
            statusMessage = "Authorization cancelled",
            updatedAtMillis = clock(),
        )
        io { store.save(cancelled) }
        reload()
        return success(cancelled)
    }

    override suspend fun disconnect(id: ConnectionId): ForgeResult<Connection, ForgeError> {
        val existing = io { store.load() }.firstOrNull { it.id == id }
            ?: return failure(connectionNotFound(id))

        oauth.cancel(id)
        val credentialRef = existing.credentialRef
        var message = "Disconnected"
        if (credentialRef != null) {
            if (existing.usesOAuth) {
                val revocation = oauth.revoke(existing, credentialRef)
                if (!revocation.revoked && revocation.message.isNotBlank()) {
                    message = "Disconnected. ${revocation.message}"
                }
            }
            io { secrets.remove(credentialRef) }
        }

        val disconnected = existing.copy(
            credentialRef = null,
            grantedScopes = emptySet(),
            accountLabel = null,
            credentialsExpireAtMillis = null,
            refreshable = false,
            status = ConnectionStatus.DISCONNECTED,
            statusMessage = message,
            updatedAtMillis = clock(),
        )
        io { store.save(disconnected) }
        reload()
        return success(disconnected)
    }

    override suspend fun refreshAuthorization(id: ConnectionId): ForgeResult<Connection, ForgeError> {
        val existing = io { store.load() }.firstOrNull { it.id == id }
            ?: return failure(connectionNotFound(id))
        val ref = existing.credentialRef
            ?: return failure(
                connectionFailure(
                    code = ForgeErrorCode.CONNECTION_CREDENTIAL_EXPIRED,
                    message = "There is no stored grant for this connection",
                    details = mapOf("connectionId" to id.value),
                ),
            )

        val fresh = oauth.ensureFresh(existing, ref)
        val tokens = fresh.valueOrNull()
        if (tokens == null) {
            val failed = markExpired(existing, fresh.errorOrNull()?.message ?: "The grant could not be refreshed")
            return failure(fresh.errorOrNull() ?: connectionFailure(
                code = ForgeErrorCode.CONNECTION_CREDENTIAL_EXPIRED,
                message = failed.statusMessage ?: "The grant could not be refreshed",
                details = mapOf("connectionId" to id.value),
            ))
        }

        val renewed = existing.copy(
            status = ConnectionStatus.CONNECTED,
            statusMessage = "Credentials refreshed",
            grantedScopes = tokens.scopes.ifEmpty { existing.grantedScopes },
            accountLabel = tokens.accountLabel ?: existing.accountLabel,
            credentialsExpireAtMillis = tokens.expiresAtMillis,
            refreshable = tokens.hasRefreshToken,
            updatedAtMillis = clock(),
        )
        io { store.save(renewed) }
        reload()
        return success(renewed)
    }

    override fun status(id: ConnectionId): ConnectionStatus? =
        mutableState.value.connection(id)?.status

    /**
     * Hands a credential to the service client. The value is passed to [block] and
     * never returned, so it cannot end up in a tool result or the agent's context.
     */
    override suspend fun <T> withCredential(
        connectionId: ConnectionId,
        block: suspend (String) -> T,
    ): ForgeResult<T, ForgeError> {
        val existing = io { store.load() }.firstOrNull { it.id == connectionId }
            ?: return failure(connectionNotFound(connectionId))
        if (!existing.enabled) {
            return failure(
                connectionFailure(
                    code = ForgeErrorCode.CONNECTION_UNAUTHORIZED,
                    message = "The matching connection is disabled",
                    details = mapOf("connectionId" to connectionId.value),
                ),
            )
        }
        val ref = existing.credentialRef
            ?: return failure(
                connectionFailure(
                    code = ForgeErrorCode.CONNECTION_UNAUTHORIZED,
                    message = "The connection has no stored credential",
                    details = mapOf("connectionId" to connectionId.value),
                ),
            )

        val credential = if (existing.usesOAuth) {
            if (existing.status != ConnectionStatus.CONNECTED && existing.status != ConnectionStatus.EXPIRED) {
                return failure(
                    connectionFailure(
                        code = ForgeErrorCode.CONNECTION_UNAUTHORIZED,
                        message = "The connection is not authorized yet",
                        details = mapOf("connectionId" to connectionId.value, "status" to existing.status.name),
                    ),
                )
            }
            val fresh = oauth.ensureFresh(existing, ref)
            val tokens = fresh.valueOrNull()
            if (tokens == null) {
                markExpired(existing, fresh.errorOrNull()?.message ?: "The grant can no longer be used")
                return failure(fresh.errorOrNull() ?: connectionFailure(
                    code = ForgeErrorCode.CONNECTION_CREDENTIAL_EXPIRED,
                    message = "The grant can no longer be used",
                    details = mapOf("connectionId" to connectionId.value),
                ))
            }
            if (existing.status != ConnectionStatus.CONNECTED) {
                io { store.save(existing.copy(status = ConnectionStatus.CONNECTED, updatedAtMillis = clock())) }
                reload()
            }
            tokens.accessToken
        } else {
            io { secrets.get(ref) }
                ?: return failure(
                    connectionFailure(
                        code = ForgeErrorCode.CONNECTION_UNAUTHORIZED,
                        message = "The connection has no stored credential",
                        details = mapOf("connectionId" to connectionId.value),
                    ),
                )
        }

        return success(block(credential))
    }

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

        // An OAuth connection is only usable once authorization really succeeded.
        if (match.usesOAuth) {
            when (match.status) {
                ConnectionStatus.AUTHORIZING -> return failure(
                    ConnectionAuthorizationError(
                        failure = ConnectionAuthorizationFailure.AUTHORIZING,
                        type = type,
                        capability = capability,
                        connectionId = match.id,
                        detail = "The ${type.displayName} authorization has not been approved yet",
                    ),
                )

                ConnectionStatus.EXPIRED -> return failure(
                    ConnectionAuthorizationError(
                        failure = ConnectionAuthorizationFailure.EXPIRED,
                        type = type,
                        capability = capability,
                        connectionId = match.id,
                        detail = "The ${type.displayName} grant expired; re-authorize the connection",
                    ),
                )

                ConnectionStatus.CONNECTED -> Unit

                else -> return failure(
                    ConnectionAuthorizationError(
                        failure = ConnectionAuthorizationFailure.NOT_AUTHORIZED,
                        type = type,
                        capability = capability,
                        connectionId = match.id,
                        detail = "The ${type.displayName} connection is not authorized",
                    ),
                )
            }
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

        if (match.config.expectsCredential || (match.usesOAuth && match.hasCredential)) {
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

    // --- OAuth helpers -------------------------------------------------------

    /** Creates the record for a first-time OAuth connection, then authorizes it. */
    private suspend fun createForAuthorization(
        type: ConnectionType,
        displayName: String?,
    ): ForgeResult<OAuthAuthorizationStart, ForgeError> {
        val name = displayName?.trim()?.takeIf { it.isNotBlank() } ?: defaultDisplayName(type)
        val created = addConnection(
            ConnectionDraft(
                displayName = uniqueName(name, io { store.load() }, type),
                type = type,
                config = ConnectionConfig(authMethod = ConnectionAuthMethod.OAUTH),
                capabilities = ConnectionCapabilities.defaultsFor(type),
            ),
        )
        val connection = created.valueOrNull()
            ?: return failure(created.errorOrNull() ?: connectionFailure(
                code = ForgeErrorCode.CONNECTION_INVALID,
                message = "The connection could not be created",
            ))
        return beginAuthorization(connection.id)
    }

    private fun defaultDisplayName(type: ConnectionType): String = type.displayName

    /** Keeps demo/recorded names unambiguous without failing the connect action. */
    private fun uniqueName(name: String, existing: List<Connection>, type: ConnectionType): String {
        val taken = existing.filter { it.type == type }.map { it.displayName.trim().lowercase() }
        if (name.lowercase() !in taken) return name
        var index = 2
        while ("${name.lowercase()} $index" in taken) index++
        return "$name $index"
    }

    /** Persists a completed authorization, then marks the connection connected. */
    private suspend fun saveCompletion(completion: OAuthCompletion): ForgeResult<Connection, ForgeError> {
        val existing = io { store.load() }.firstOrNull { it.id == completion.connectionId }
            ?: return failure(connectionNotFound(completion.connectionId))

        val ref = existing.credentialRef ?: credentialRefFor(existing.id)
        io { oauth.writeTokens(ref, completion.tokens) }

        val connected = existing.copy(
            config = existing.config.copy(authMethod = ConnectionAuthMethod.OAUTH),
            credentialRef = ref,
            capabilities = completion.grantedCapabilities,
            status = ConnectionStatus.CONNECTED,
            statusMessage = completion.validation.message.ifBlank { "Connected" },
            grantedScopes = oauth.grantedScopes(existing.type, completion.tokens),
            accountLabel = completion.validation.accountLabel,
            credentialsExpireAtMillis = completion.tokens.expiresAtMillis,
            refreshable = completion.tokens.hasRefreshToken,
            lastTestedAtMillis = clock(),
            updatedAtMillis = clock(),
        )
        io { store.save(connected) }
        reload()
        return success(connected)
    }

    /**
     * Reflects a failed authorization on the connection that asked for it. A
     * callback that matches nothing (state mismatch) never touches a record.
     */
    private suspend fun markAuthorizationFailure(error: ForgeError) {
        val connectionId = error.details["connectionId"]?.toString()?.takeIf { it.isNotBlank() } ?: return
        val existing = io { store.load() }.firstOrNull { it.id == ConnectionId(connectionId) } ?: return
        val denied = error.code == ForgeErrorCode.CONNECTION_OAUTH_DENIED
        val updated = existing.copy(
            status = when (error.code) {
                ForgeErrorCode.CONNECTION_OAUTH_DENIED,
                ForgeErrorCode.CONNECTION_OAUTH_STATE_INVALID,
                -> ConnectionStatus.NOT_CONNECTED

                ForgeErrorCode.CONNECTION_CREDENTIAL_EXPIRED -> ConnectionStatus.EXPIRED
                else -> ConnectionStatus.ERROR
            },
            statusMessage = error.message ?: if (denied) "Authorization was denied" else "Authorization failed",
            updatedAtMillis = clock(),
        )
        io { store.save(updated) }
        reload()
    }

    private suspend fun markExpired(connection: Connection, message: String): Connection {
        val expired = connection.copy(
            status = ConnectionStatus.EXPIRED,
            statusMessage = message,
            updatedAtMillis = clock(),
        )
        io { store.save(expired) }
        reload()
        return expired
    }

    /** "Test" for an OAuth connection: refresh if needed, then ask the provider. */
    private suspend fun testOAuthConnection(
        existing: Connection,
        credentialRef: String,
    ): ForgeResult<ConnectionTestResult, ForgeError> {
        val connecting = existing.copy(
            status = ConnectionStatus.CONNECTING,
            statusMessage = "Checking authorization with ${existing.type.displayName}",
            updatedAtMillis = clock(),
        )
        io { store.save(connecting) }
        reload()

        val validated: ForgeResult<OAuthValidation, ForgeError> = oauth.validate(existing, credentialRef)
        val validation = validated.valueOrNull()
        if (validation == null) {
            val error = validated.errorOrNull()
            val message = error?.message ?: "The authorization could not be verified"
            val failed = existing.copy(
                status = if (error?.code == ForgeErrorCode.CONNECTION_CREDENTIAL_EXPIRED) {
                    ConnectionStatus.EXPIRED
                } else {
                    ConnectionStatus.ERROR
                },
                statusMessage = message,
                lastTestedAtMillis = clock(),
                updatedAtMillis = clock(),
            )
            io { store.save(failed) }
            reload()
            return failure(error ?: connectionFailure(
                code = ForgeErrorCode.CONNECTION_OAUTH_FAILED,
                message = message,
                details = mapOf("connectionId" to existing.id.value),
            ))
        }

        val result = ConnectionTestResult(
            status = ConnectionStatus.CONNECTED,
            message = validation.message.ifBlank { "Connected" },
            testedAtMillis = clock(),
        )
        val tested = existing.copy(
            status = ConnectionStatus.CONNECTED,
            statusMessage = result.message,
            accountLabel = validation.accountLabel ?: existing.accountLabel,
            lastTestedAtMillis = result.testedAtMillis,
            updatedAtMillis = clock(),
        )
        io { store.save(tested) }
        reload()
        return success(result)
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
