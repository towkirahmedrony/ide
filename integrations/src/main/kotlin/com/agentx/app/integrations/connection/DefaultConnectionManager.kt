package com.agentx.app.integrations.connection

import com.agentx.app.core.ForgeError
import com.agentx.app.core.ForgeErrorCode
import com.agentx.app.core.ForgeResult
import com.agentx.app.core.errorOrNull
import com.agentx.app.core.failure
import com.agentx.app.core.success
import com.agentx.app.core.valueOrNull
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
 * It owns connection identity, state, capability mapping, credential references,
 * verification timestamps, errors and the enabled-tool view. Everything that is
 * specific to a service — how authorization starts, what a callback means, which
 * account is behind a credential, what a scope grants — is delegated to the
 * [ConnectionProvider] registered for that type.
 *
 * Credentials live only in [ConnectionSecretStore]. A provider hands over an
 * opaque payload; the manager stores it, passes it back for verification, refresh
 * and revoke, and never parses it, prints it, or returns it to a caller. The one
 * way out is [withCredential], which lends the value to a service client inside a
 * lambda so it cannot be captured in a tool result or the agent's context.
 *
 * Status discipline: a connection is only [ConnectionStatus.CONNECTED] after a
 * provider really returned credentials **and** verified the account. Unsupported
 * verification never becomes connected.
 */
class DefaultConnectionManager(
    private val store: ConnectionStore,
    private val secrets: ConnectionSecretStore,
    private val tester: ConnectionTester = UnsupportedConnectionTester(),
    private val providers: ConnectionProviderRegistry = ConnectionProviderRegistry.EMPTY,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ConnectionManager, ConnectionCredentialGateway {

    private val mutableState = MutableStateFlow(ConnectionManagerState())

    override val state: StateFlow<ConnectionManagerState> = mutableState.asStateFlow()

    override val credentialsPersistent: Boolean get() = secrets.persistent

    // --- Provider surface ----------------------------------------------------

    override fun providerAvailability(): List<ProviderAvailability> = providers.availability()

    override fun providerDescriptors(): List<ProviderDescriptor> = providers.descriptors()

    /**
     * Tool catalog with live enablement. A tool is enabled only when its provider is
     * connected, the connection really declares the required capability, and the
     * tool exists; permission is still decided by the Tool System afterwards.
     */
    override fun tools(): List<InstalledTool> {
        val connections = mutableState.value.connections
        return providers.toolCatalogs().flatMap { catalog ->
            val connection = connections
                .filter { it.type == catalog.provider && it.enabled }
                .firstOrNull { it.status == ConnectionStatus.CONNECTED }
                ?: connections.firstOrNull { it.type == catalog.provider }
            catalog.tools.map { spec ->
                val connected = connection?.status == ConnectionStatus.CONNECTED
                val capability = connection?.capabilities?.contains(spec.requiredCapability) == true
                val enabled = connected && capability && spec.implemented
                InstalledTool(
                    provider = catalog.provider,
                    toolName = spec.toolName,
                    title = spec.title,
                    description = spec.description,
                    requiredCapability = spec.requiredCapability,
                    mutating = spec.mutating,
                    implemented = spec.implemented,
                    enabled = enabled,
                    reason = when {
                        connection == null -> "Connect ${catalog.provider.displayName} to enable this tool."
                        !connected -> "${catalog.provider.displayName} is not connected yet."
                        !capability -> "The connected account does not grant '${spec.requiredCapability.id}'."
                        !spec.implemented -> "This tool is declared but not enabled in this build."
                        else -> null
                    },
                    connectionId = connection?.id,
                )
            }
        }
    }

    override fun enabledToolNames(): List<String> = tools().filter { it.enabled }.map { it.toolName }

    // --- Persistence --------------------------------------------------------

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

        // A manual credential is verified with the provider before anything is
        // stored, so "saved" never silently means "usable".
        val manual = draft.credential?.takeIf { it.isNotBlank() }
        val credentialRef = storeNewCredential(id, manual)
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

        val withStatus = if (manual != null && !connection.usesOAuth) {
            verifyManual(connection, manual)
        } else {
            // Nothing has been authorized or verified yet, so the connection is
            // explicitly not connected — never optimistically connected.
            connection.copy(
                status = ConnectionStatus.NOT_CONNECTED,
                statusMessage = if (connection.usesOAuth) "Not authorized yet" else connection.statusMessage,
            )
        }
        io { store.save(withStatus) }
        reload()
        return success(withStatus)
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

        val newManual = draft.credential?.takeIf { it.isNotBlank() }
        val credentialRef = when {
            draft.clearCredential -> {
                clearPending(id)
                existing.credentialRef?.let { io { secrets.remove(it) } }
                null
            }

            newManual != null -> {
                val ref = existing.credentialRef ?: credentialRefFor(id)
                io { secrets.put(ref, newManual) }
                ref
            }

            else -> existing.credentialRef
        }

        val updated = draft.toConnection(
            id = id,
            existing = existing,
            credentialRef = credentialRef,
            now = clock(),
        )
        invalid(updated)?.let { return failure(it) }

        val withStatus = when {
            draft.clearCredential -> updated.copy(
                status = ConnectionStatus.DISCONNECTED,
                statusMessage = "Credential removed",
                grantedScopes = emptySet(),
                accountLabel = null,
                credentialsExpireAtMillis = null,
                refreshable = false,
            )

            newManual != null -> verifyManual(updated, newManual)

            // Editing a connection returns it to not-connected until it is verified
            // again; an OAuth grant is kept only when the method is still OAuth.
            updated.usesOAuth && existing.usesOAuth -> updated
            else -> updated.copy(status = ConnectionStatus.NOT_CONNECTED, statusMessage = "Not verified yet")
        }

        io { store.save(withStatus) }
        reload()
        return success(withStatus)
    }

    override suspend fun removeConnection(id: ConnectionId): ForgeResult<Unit, ForgeError> {
        val existing = io { store.load() }.firstOrNull { it.id == id }
            ?: return failure(connectionNotFound(id))
        clearPending(id)
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

        val provider = providers.provider(existing.type)
        val credentialRef = existing.credentialRef
        if (provider != null && credentialRef != null) {
            return verifyStored(existing, provider, credentialRef)
        }

        // No provider (or no credential yet): fall back to the probe tester, which
        // must never report connected.
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
        val safeStatus = if (result.message == ConnectionTestResult.NOT_IMPLEMENTED) {
            ConnectionStatus.DISCONNECTED
        } else {
            result.status
        }
        io {
            store.save(
                existing.copy(
                    status = safeStatus,
                    statusMessage = result.message,
                    lastTestedAtMillis = result.testedAtMillis,
                    updatedAtMillis = clock(),
                ),
            )
        }
        reload()

        return if (result.message == ConnectionTestResult.NOT_IMPLEMENTED) {
            failure(
                connectionFailure(
                    code = ForgeErrorCode.CONNECTION_TEST_UNSUPPORTED,
                    message = ConnectionTestResult.NOT_IMPLEMENTED,
                    details = mapOf("connectionId" to id.value, "type" to existing.type.name),
                ),
            )
        } else {
            success(result.copy(status = safeStatus))
        }
    }

    // --- Authorization ------------------------------------------------------

    override suspend fun connect(
        type: ConnectionType,
        displayName: String?,
    ): ForgeResult<AuthorizationStart, ForgeError> {
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

    override suspend fun beginAuthorization(id: ConnectionId): ForgeResult<AuthorizationStart, ForgeError> {
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
        val provider = providers.provider(existing.type)
            ?: return failure(unavailable(existing, "No provider handles ${existing.type.displayName}."))

        val target = if (existing.usesOAuth) existing else existing.copy(
            config = existing.config.copy(authMethod = ConnectionAuthMethod.OAUTH),
            updatedAtMillis = clock(),
        )

        val started = provider.beginAuthorization(target)
        val start = started.valueOrNull()
            ?: return failure(started.errorOrNull() ?: unavailable(existing, "The authorization could not start"))

        val next = when (start) {
            is AuthorizationStart.OpenUrl -> target.copy(
                status = ConnectionStatus.AUTHORIZING,
                statusMessage = "Waiting for you to approve access on ${existing.type.displayName}",
                updatedAtMillis = clock(),
            )

            // No hosted page: the form the user is already looking at collects the
            // credential, so the connection stays not-connected until it is verified.
            is AuthorizationStart.ManualFormRequired -> target.copy(
                status = ConnectionStatus.NOT_CONNECTED,
                statusMessage = start.reason,
                updatedAtMillis = clock(),
            )
        }
        io { store.save(next) }
        reload()
        return success(start)
    }

    override suspend fun completeAuthorization(callbackUri: String): ForgeResult<Connection, ForgeError> {
        // The callback belongs to the connection whose provider is holding a pending
        // authorization; the provider still validates state and PKCE itself.
        val waiting = current().filter { it.status == ConnectionStatus.AUTHORIZING }
        val target = waiting.firstOrNull { connection ->
            providers.provider(connection.type)?.hasPendingAuthorization(connection.id) == true
        } ?: waiting.firstOrNull()

        if (target == null) {
            return failure(
                connectionFailure(
                    code = ForgeErrorCode.CONNECTION_OAUTH_STATE_INVALID,
                    message = "No connection is waiting for an authorization response.",
                ),
            )
        }
        val activeProvider = providers.provider(target.type)
            ?: return failure(unavailable(target, "No provider handles ${target.type.displayName}."))

        val completed = activeProvider.completeAuthorization(target, callbackUri)
        val grant = completed.valueOrNull()
        if (grant == null) {
            val error = completed.errorOrNull()
            error?.let { io { markAuthorizationFailure(target, it) } }
            return failure(error ?: unavailable(target, "The authorization response could not be processed"))
        }

        return saveGrant(target, activeProvider, grant)
    }

    override suspend fun cancelAuthorization(id: ConnectionId): ForgeResult<Connection, ForgeError> {
        val existing = io { store.load() }.firstOrNull { it.id == id }
            ?: return failure(connectionNotFound(id))
        clearPending(id)
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

        clearPending(id)
        val credentialRef = existing.credentialRef
        var message = "Disconnected"
        if (credentialRef != null) {
            val provider = providers.provider(existing.type)
            val payload = io { secrets.get(credentialRef) }
            if (provider != null && payload != null) {
                val revocation = runCatching { provider.revoke(existing, payload) }.getOrNull()
                if (revocation != null && !revocation.revoked && revocation.message.isNotBlank()) {
                    message = "Disconnected. ${revocation.message}"
                }
            }
            // The credential is deleted whatever the provider answered: a stale
            // secret must never survive a disconnect.
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
                    message = "There is no stored credential for this connection",
                    details = mapOf("connectionId" to id.value),
                ),
            )
        val provider = providers.provider(existing.type)
            ?: return failure(unavailable(existing, "No provider handles ${existing.type.displayName}."))

        return verifyStored(existing, provider, ref)
    }

    override fun status(id: ConnectionId): ConnectionStatus? = mutableState.value.connection(id)?.status

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

        // An OAuth connection is only usable once authorization succeeded.
        if (existing.usesOAuth &&
            existing.status != ConnectionStatus.CONNECTED &&
            existing.status != ConnectionStatus.EXPIRED
        ) {
            return failure(
                connectionFailure(
                    code = ForgeErrorCode.CONNECTION_UNAUTHORIZED,
                    message = "The connection is not authorized yet",
                    details = mapOf("connectionId" to connectionId.value, "status" to existing.status.name),
                ),
            )
        }

        val provider = providers.provider(existing.type)
        var payload = io { secrets.get(ref) }
            ?: return failure(
                connectionFailure(
                    code = ForgeErrorCode.CONNECTION_UNAUTHORIZED,
                    message = "The connection has no stored credential",
                    details = mapOf("connectionId" to connectionId.value),
                ),
            )

        if (provider != null && existing.usesOAuth && provider.needsRefresh(existing, payload)) {
            val refreshed = provider.refresh(existing, payload)
            val fresh = refreshed.valueOrNull()
            if (fresh == null) {
                val error = refreshed.errorOrNull()
                io { store.save(expired(existing, error?.message ?: "The credential could not be refreshed")) }
                reload()
                return failure(refreshed.errorOrNull() ?: unavailable(existing, "The credential could not be refreshed"))
            }
            payload = fresh
            io { secrets.put(ref, fresh) }
            if (existing.status != ConnectionStatus.CONNECTED) {
                io { store.save(existing.copy(status = ConnectionStatus.CONNECTED, updatedAtMillis = clock())) }
                reload()
            }
        }

        // The value is passed to the caller's lambda and never returned.
        return success(block(payload))
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

        if (match == null || match.type != type) {
            return failure(
                ConnectionAuthorizationError(
                    failure = ConnectionAuthorizationFailure.NOT_FOUND,
                    type = type,
                    capability = capability,
                    connectionId = match?.id ?: connectionId,
                    detail = if (match != null) "Connection is not of type ${type.displayName}" else null,
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

        if (match.usesOAuth) {
            when (match.status) {
                ConnectionStatus.AUTHORIZING, ConnectionStatus.VERIFYING -> return failure(
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
                        detail = "The ${type.displayName} grant expired; reconnect the service",
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

    // --- Internals ----------------------------------------------------------

    /** Creates the record for a first-time connection, then starts authorization. */
    private suspend fun createForAuthorization(
        type: ConnectionType,
        displayName: String?,
    ): ForgeResult<AuthorizationStart, ForgeError> {
        val provider = providers.provider(type)
            ?: return failure(
                connectionFailure(
                    code = ForgeErrorCode.CONNECTION_OAUTH_UNAVAILABLE,
                    message = "No provider is registered for ${type.displayName} in this build.",
                    details = mapOf("type" to type.name),
                ),
            )
        val oauthFirst = provider.authMethods().contains(ConnectionAuthMethod.OAUTH) && provider.configured
        val name = displayName?.trim()?.takeIf { it.isNotBlank() }
            ?: uniqueName(type.displayName, io { store.load() }, type)

        val created = addConnection(
            ConnectionDraft(
                displayName = name,
                type = type,
                config = ConnectionConfig(
                    authMethod = if (oauthFirst) ConnectionAuthMethod.OAUTH else ConnectionAuthMethod.ACCESS_TOKEN,
                ),
                capabilities = ConnectionCapabilities.defaultsFor(type),
            ),
        )
        val connection = created.valueOrNull()
            ?: return failure(created.errorOrNull() ?: unavailable(null, "The connection could not be created", type))
        return beginAuthorization(connection.id)
    }

    /** Keeps recorded names unambiguous without failing the connect action. */
    private fun uniqueName(name: String, existing: List<Connection>, type: ConnectionType): String {
        val taken = existing.filter { it.type == type }.map { it.displayName.trim().lowercase() }
        if (name.lowercase() !in taken) return name
        var index = 2
        while ("${name.lowercase()} $index" in taken) index++
        return "$name $index"
    }

    /** Verifies a manual credential, storing the identity it resolved to. */
    private suspend fun verifyManual(connection: Connection, credential: String): Connection {
        val provider = providers.provider(connection.type)
            ?: return connection.copy(
                status = ConnectionStatus.ERROR,
                statusMessage = "No provider handles ${connection.type.displayName}",
                updatedAtMillis = clock(),
            )
        val verified = provider.verifyManualCredential(connection, credential)
        val identity = verified.valueOrNull()
        return if (identity == null) {
            connection.copy(
                status = ConnectionStatus.ERROR,
                statusMessage = verified.errorOrNull()?.message ?: "The credential could not be verified",
                updatedAtMillis = clock(),
            )
        } else {
            connection.copy(
                status = ConnectionStatus.CONNECTED,
                statusMessage = identity.message.ifBlank { "Credential verified" },
                accountLabel = identity.accountLabel,
                // A manual credential has no provider-reported scope set; the
                // connection keeps the capabilities the user declared.
                lastTestedAtMillis = clock(),
                updatedAtMillis = clock(),
            )
        }
    }

    /** Re-checks stored credentials with the provider, refreshing when needed. */
    private suspend fun verifyStored(
        existing: Connection,
        provider: ConnectionProvider,
        credentialRef: String,
    ): ForgeResult<ConnectionTestResult, ForgeError> {
        io {
            store.save(
                existing.copy(
                    status = ConnectionStatus.VERIFYING,
                    statusMessage = "Verifying with ${existing.type.displayName}",
                    updatedAtMillis = clock(),
                ),
            )
        }
        reload()

        var payload = io { secrets.get(credentialRef) }
        if (payload == null) {
            val failed = existing.copy(
                status = ConnectionStatus.ERROR,
                statusMessage = "The stored credential is missing",
                updatedAtMillis = clock(),
            )
            io { store.save(failed) }
            reload()
            return failure(
                connectionFailure(
                    code = ForgeErrorCode.CONNECTION_UNAUTHORIZED,
                    message = "The stored credential is missing",
                    details = mapOf("connectionId" to existing.id.value),
                ),
            )
        }

        if (existing.usesOAuth && provider.needsRefresh(existing, payload)) {
            val refreshed = provider.refresh(existing, payload)
            val fresh = refreshed.valueOrNull()
            if (fresh == null) {
                val error = refreshed.errorOrNull()
                io { store.save(expired(existing, error?.message ?: "The credential could not be refreshed")) }
                reload()
                return failure(refreshed.errorOrNull() ?: unavailable(existing, "The credential could not be refreshed"))
            }
            payload = fresh
            io { secrets.put(credentialRef, fresh) }
        }

        val verified = provider.verify(existing, payload)
        val identity = verified.valueOrNull()
        if (identity == null) {
            val error = verified.errorOrNull()
            val code = error?.code
            val failed = existing.copy(
                status = if (code == ForgeErrorCode.CONNECTION_CREDENTIAL_EXPIRED) {
                    ConnectionStatus.EXPIRED
                } else {
                    ConnectionStatus.ERROR
                },
                statusMessage = error?.message ?: "The connection could not be verified",
                lastTestedAtMillis = clock(),
                updatedAtMillis = clock(),
            )
            io { store.save(failed) }
            reload()
            return failure(error ?: unavailable(existing, "The connection could not be verified"))
        }

        val result = ConnectionTestResult(
            status = ConnectionStatus.CONNECTED,
            message = identity.message.ifBlank { "Connected as ${identity.accountLabel}" },
            testedAtMillis = clock(),
        )
        io {
            store.save(
                existing.copy(
                    status = ConnectionStatus.CONNECTED,
                    statusMessage = result.message,
                    accountLabel = identity.accountLabel,
                    credentialsExpireAtMillis = identity.expiresAtMillis ?: existing.credentialsExpireAtMillis,
                    lastTestedAtMillis = result.testedAtMillis,
                    updatedAtMillis = clock(),
                ),
            )
        }
        reload()
        return success(result)
    }

    /** Persists a completed authorization, then marks the connection connected. */
    private suspend fun saveGrant(
        target: Connection,
        provider: ConnectionProvider,
        grant: ProviderGrant,
    ): ForgeResult<Connection, ForgeError> {
        val existing = io { store.load() }.firstOrNull { it.id == grant.connectionId }
            ?: return failure(connectionNotFound(grant.connectionId))

        val ref = existing.credentialRef ?: credentialRefFor(existing.id)
        io { secrets.put(ref, grant.payload) }

        // Capabilities come from the grant: the provider only reports what the
        // granted scopes allow, already intersected with what was requested.
        val capabilities = grant.capabilities.ifEmpty {
            provider.capabilitiesFor(existing, grant.scopes).intersect(existing.capabilities)
        }

        val connected = existing.copy(
            config = existing.config.copy(authMethod = ConnectionAuthMethod.OAUTH),
            credentialRef = ref,
            capabilities = capabilities,
            status = ConnectionStatus.CONNECTED,
            statusMessage = grant.identity.message.ifBlank {
                "Connected as ${grant.identity.accountLabel}"
            },
            grantedScopes = grant.scopes,
            accountLabel = grant.identity.accountLabel,
            credentialsExpireAtMillis = grant.expiresAtMillis,
            refreshable = grant.refreshable,
            lastTestedAtMillis = clock(),
            updatedAtMillis = clock(),
        )
        io { store.save(connected) }
        reload()
        return success(connected)
    }

    /**
     * Reflects a failed authorization on the connection that asked for it. A
     * callback that matches nothing never touches a record, so a forged redirect
     * cannot change any status.
     */
    private suspend fun markAuthorizationFailure(connection: Connection, error: ForgeError) {
        io { store.save(toFailed(connection, error)) }
        reload()
    }

    private fun toFailed(connection: Connection, error: ForgeError): Connection = connection.copy(
        status = when (error.code) {
            ForgeErrorCode.CONNECTION_OAUTH_DENIED,
            ForgeErrorCode.CONNECTION_OAUTH_STATE_INVALID,
            -> ConnectionStatus.NOT_CONNECTED

            ForgeErrorCode.CONNECTION_CREDENTIAL_EXPIRED -> ConnectionStatus.EXPIRED
            else -> ConnectionStatus.ERROR
        },
        statusMessage = error.message ?: "Authorization failed",
        updatedAtMillis = clock(),
    )

    private fun expired(connection: Connection, message: String): Connection = connection.copy(
        status = ConnectionStatus.EXPIRED,
        statusMessage = message,
        updatedAtMillis = clock(),
    )

    /** Drops a pending authorization held by the provider for [id]. */
    private suspend fun clearPending(id: ConnectionId) {
        val existing = current().firstOrNull { it.id == id } ?: io { store.load() }.firstOrNull { it.id == id }
        existing?.let { providers.provider(it.type)?.cancelAuthorization(id) }
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

    private fun unavailable(
        connection: Connection?,
        message: String,
        type: ConnectionType? = null,
    ): ForgeError = connectionFailure(
        code = ForgeErrorCode.CONNECTION_OAUTH_UNAVAILABLE,
        message = message,
        details = mapOf(
            "connectionId" to connection?.id?.value,
            "type" to (connection?.type ?: type)?.name,
        ),
    )

    private companion object {
        fun List<Connection>.sorted(): List<Connection> =
            sortedWith(compareBy({ it.createdAtMillis }, { it.displayName.lowercase() }))
    }
}
