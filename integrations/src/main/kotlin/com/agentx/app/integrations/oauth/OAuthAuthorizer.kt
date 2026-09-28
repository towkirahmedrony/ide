package com.agentx.app.integrations.oauth

import com.agentx.app.core.ForgeError
import com.agentx.app.core.ForgeErrorCode
import com.agentx.app.core.ForgeResult
import com.agentx.app.core.failure
import com.agentx.app.core.success
import com.agentx.app.integrations.connection.Connection
import com.agentx.app.integrations.connection.ConnectionCapability
import com.agentx.app.integrations.connection.ConnectionId
import com.agentx.app.integrations.connection.ConnectionOAuthAvailability
import com.agentx.app.integrations.connection.ConnectionSecretStore
import com.agentx.app.integrations.connection.ConnectionType
import com.agentx.app.integrations.connection.connectionFailure
import java.security.SecureRandom

/**
 * Runs the authorization-code + PKCE exchange for a connection.
 *
 * The Connection Manager owns persistence and the tool-facing surface; this class
 * owns the protocol. It never marks anything "connected" by itself: [begin]
 * returns a URL, and [complete] only produces a [OAuthCompletion] after the
 * provider really returned and validated tokens.
 *
 * Codes, verifiers and tokens live only inside this class's call frames and the
 * platform secret store. Nothing is logged, echoed, or attached to a connection
 * record.
 */
class OAuthAuthorizer(
    val providers: OAuthProviderRegistry = OAuthProviderRegistry.EMPTY,
    private val sessions: OAuthSessionStore = InMemoryOAuthSessionStore(),
    private val secrets: ConnectionSecretStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
    private val authorizationTtlMillis: Long = DEFAULT_AUTHORIZATION_TTL_MILLIS,
) {

    /** OAuth availability for a service, used by the Connections page. */
    fun availability(type: ConnectionType, authorizing: Boolean = false): ConnectionOAuthAvailability =
        providers.availability(type, authorizing)

    fun descriptor(type: ConnectionType): OAuthProviderDescriptor? = providers.descriptor(type)

    /** The authorization in flight for [connectionId], if any. */
    fun pendingFor(connectionId: ConnectionId): OAuthPendingAuthorization? = sessions.pendingFor(connectionId)

    fun cancel(connectionId: ConnectionId) = sessions.cancel(connectionId)

    /**
     * Starts an authorization: builds the provider URL with a fresh `state` and a
     * fresh PKCE verifier, and remembers the pending attempt in memory only.
     */
    fun begin(connection: Connection): ForgeResult<OAuthAuthorizationStart, ForgeError> {
        val provider = providers.provider(connection.type)
            ?: return failure(
                oauthFailure(
                    ForgeErrorCode.CONNECTION_OAUTH_UNAVAILABLE,
                    OAuthFailureReason.PROVIDER_UNSUPPORTED,
                    "No OAuth provider handles ${connection.type.displayName}.",
                    connection,
                ),
            )

        if (!provider.client.isConfigured) {
            return failure(
                oauthFailure(
                    ForgeErrorCode.CONNECTION_OAUTH_UNAVAILABLE,
                    OAuthFailureReason.PROVIDER_NOT_CONFIGURED,
                    "${provider.descriptor.displayName} OAuth is not configured: " +
                        (provider.client.configurationProblem ?: "missing client configuration"),
                    connection,
                ),
            )
        }

        val scopes = provider.scopesFor(connection.capabilities)
        val state = OAuthPkce.createState(random)
        val verifier = if (provider.descriptor.supportsPkce) OAuthPkce.createVerifier(random) else null
        val challenge = verifier?.let(OAuthPkce::challenge)
        val now = clock()

        val url = provider.authorizationUrl(
            OAuthAuthorizationRequest(
                clientId = provider.client.clientId,
                redirectUri = provider.client.redirectUri,
                state = state,
                scopes = scopes,
                codeChallenge = challenge,
                codeChallengeMethod = challenge?.let { OAuthPkce.METHOD_S256 },
            ),
        )

        sessions.save(
            OAuthPendingAuthorization(
                connectionId = connection.id,
                type = connection.type,
                state = state,
                codeVerifier = verifier,
                codeChallenge = challenge,
                redirectUri = provider.client.redirectUri,
                scopes = scopes,
                requestedCapabilities = connection.capabilities,
                createdAtMillis = now,
                expiresAtMillis = now + authorizationTtlMillis,
            ),
        )

        return success(
            OAuthAuthorizationStart(
                connectionId = connection.id,
                type = connection.type,
                displayName = connection.displayName,
                authorizationUrl = url,
                scopes = scopes,
                expiresAtMillis = now + authorizationTtlMillis,
            ),
        )
    }

    /**
     * Handles the provider's callback.
     *
     * Order matters: the callback is matched against a pending attempt by `state`
     * (single use), the PKCE verifier is only used for the provider that stored
     * one, and tokens are validated before [OAuthCompletion] is returned.
     */
    suspend fun complete(callbackUri: String): ForgeResult<OAuthCompletion, ForgeError> {
        val callback = OAuthCallback.parse(callbackUri)

        if (callback.isDenied) {
            sessions.consume(callback.state.orEmpty())
            return failure(
                oauthFailure(
                    ForgeErrorCode.CONNECTION_OAUTH_DENIED,
                    OAuthFailureReason.AUTHORIZATION_DENIED,
                    callback.errorDescription ?: "Access was denied on the authorization page.",
                ),
            )
        }

        if (callback.isError) {
            sessions.consume(callback.state.orEmpty())
            return failure(
                oauthFailure(
                    ForgeErrorCode.CONNECTION_OAUTH_FAILED,
                    OAuthFailureReason.AUTHORIZATION_ERROR,
                    callback.errorDescription ?: "The provider reported '${callback.error}'.",
                ),
            )
        }

        val state = callback.state
        if (state.isNullOrBlank()) {
            return failure(
                oauthFailure(
                    ForgeErrorCode.CONNECTION_OAUTH_STATE_INVALID,
                    OAuthFailureReason.STATE_MISMATCH,
                    "The authorization response carried no state, so it was not accepted.",
                ),
            )
        }

        val pending = when (val lookup = sessions.consume(state)) {
            is OAuthSessionLookup.Consumed -> lookup.pending
            is OAuthSessionLookup.AlreadyHandled -> return failure(
                oauthFailure(
                    ForgeErrorCode.CONNECTION_OAUTH_STATE_INVALID,
                    OAuthFailureReason.STATE_REPLAY,
                    "This authorization response was already handled.",
                    connectionId = lookup.pending?.connectionId,
                ),
            )

            is OAuthSessionLookup.Expired -> return failure(
                oauthFailure(
                    ForgeErrorCode.CONNECTION_OAUTH_STATE_INVALID,
                    OAuthFailureReason.STATE_EXPIRED,
                    "The authorization request expired before it was answered.",
                    connectionId = lookup.pending.connectionId,
                ),
            )

            OAuthSessionLookup.Unknown -> return failure(
                oauthFailure(
                    ForgeErrorCode.CONNECTION_OAUTH_STATE_INVALID,
                    OAuthFailureReason.STATE_MISMATCH,
                    "The authorization response did not match any request started by this app.",
                ),
            )
        }

        val provider = providers.provider(pending.type)
            ?: return failure(
                oauthFailure(
                    ForgeErrorCode.CONNECTION_OAUTH_UNAVAILABLE,
                    OAuthFailureReason.PROVIDER_UNSUPPORTED,
                    "No OAuth provider handles ${pending.type.displayName}.",
                    connectionId = pending.connectionId,
                ),
            )

        val code = callback.code
        if (code.isNullOrBlank()) {
            return failure(
                oauthFailure(
                    ForgeErrorCode.CONNECTION_OAUTH_FAILED,
                    OAuthFailureReason.AUTHORIZATION_ERROR,
                    "The authorization response carried no code.",
                    connectionId = pending.connectionId,
                ),
            )
        }

        val tokenResult = provider.exchange(
            code = code,
            codeVerifier = pending.codeVerifier,
            redirectUri = pending.redirectUri,
        )
        val tokens = when (tokenResult) {
            is OAuthTokenResult.Success -> tokenResult.tokens
            is OAuthTokenResult.Failure -> return failure(
                oauthFailure(
                    ForgeErrorCode.CONNECTION_OAUTH_FAILED,
                    tokenResult.failure.reason,
                    tokenResult.failure.message,
                    connectionId = pending.connectionId,
                ),
            )
        }

        val validation = provider.validate(tokens)
        if (!validation.valid) {
            return failure(
                oauthFailure(
                    ForgeErrorCode.CONNECTION_OAUTH_FAILED,
                    OAuthFailureReason.VALIDATION_FAILED,
                    validation.message.ifBlank { OAuthFailureReason.VALIDATION_FAILED.userMessage },
                    connectionId = pending.connectionId,
                ),
            )
        }

        val grantedScopes = provider.grantedScopes(tokens)
        // Both filters apply: the provider only grants what the scopes allow, and
        // the connection never gains a capability the user did not ask for.
        val grantedCapabilities = provider.capabilitiesFor(grantedScopes)
            .intersect(pending.requestedCapabilities)

        return success(
            OAuthCompletion(
                connectionId = pending.connectionId,
                tokens = tokens.copy(accountLabel = validation.accountLabel ?: tokens.accountLabel),
                validation = validation,
                grantedCapabilities = grantedCapabilities,
            ),
        )
    }

    /** Reads the stored grant for [connection], or null for a manual credential. */
    suspend fun readTokens(credentialRef: String?): OAuthTokenSet? =
        OAuthTokenCodec.decode(secrets.get(credentialRef ?: return null))

    /** Writes the grant to the platform secret store. Never to a record. */
    suspend fun writeTokens(credentialRef: String, tokens: OAuthTokenSet) {
        secrets.put(credentialRef, OAuthTokenCodec.encode(tokens))
    }

    suspend fun clearTokens(credentialRef: String) {
        secrets.remove(credentialRef)
    }

    /**
     * Returns usable tokens for [connection], refreshing them when they are about
     * to expire. A provider without refresh support, or a failed refresh, is
     * reported so the connection can be marked expired instead of being used.
     */
    suspend fun ensureFresh(connection: Connection, credentialRef: String): ForgeResult<OAuthTokenSet, ForgeError> {
        val stored = OAuthTokenCodec.decode(secrets.get(credentialRef))
            ?: return failure(
                oauthFailure(
                    ForgeErrorCode.CONNECTION_CREDENTIAL_EXPIRED,
                    OAuthFailureReason.NOT_CONNECTED,
                    "No stored grant exists for '${connection.displayName}'.",
                    connection,
                ),
            )

        if (!stored.isExpired(clock(), refreshLeewayMillis)) return success(stored)

        val provider = providers.provider(connection.type)
            ?: return failure(
                oauthFailure(
                    ForgeErrorCode.CONNECTION_CREDENTIAL_EXPIRED,
                    OAuthFailureReason.PROVIDER_UNSUPPORTED,
                    "No OAuth provider handles ${connection.type.displayName}.",
                    connection,
                ),
            )

        val refreshToken = stored.refreshToken
        if (refreshToken.isNullOrBlank() || !provider.descriptor.supportsRefresh) {
            return failure(
                oauthFailure(
                    ForgeErrorCode.CONNECTION_CREDENTIAL_EXPIRED,
                    OAuthFailureReason.REFRESH_UNSUPPORTED,
                    "The grant for '${connection.displayName}' expired and cannot be refreshed; " +
                        "reconnect to authorize again.",
                    connection,
                ),
            )
        }

        return when (val result = provider.refresh(refreshToken)) {
            is OAuthTokenResult.Success -> {
                // Providers may omit the refresh token when they keep the same one.
                val refreshed = result.tokens.copy(
                    refreshToken = result.tokens.refreshToken ?: refreshToken,
                    accountLabel = result.tokens.accountLabel ?: stored.accountLabel,
                    scopes = result.tokens.scopes.ifEmpty { stored.scopes },
                )
                writeTokens(credentialRef, refreshed)
                success(refreshed)
            }

            is OAuthTokenResult.Failure -> failure(
                oauthFailure(
                    ForgeErrorCode.CONNECTION_CREDENTIAL_EXPIRED,
                    result.failure.reason,
                    result.failure.message,
                    connection,
                ),
            )
        }
    }

    /**
     * Re-checks stored credentials with the provider, refreshing them first when
     * they are close to expiring. This is what "Test" means for an OAuth
     * connection: a real authenticated call, never an optimistic status.
     */
    suspend fun validate(connection: Connection, credentialRef: String): ForgeResult<OAuthValidation, ForgeError> {
        val tokens = when (val fresh = ensureFresh(connection, credentialRef)) {
            is ForgeResult.Success -> fresh.value
            is ForgeResult.Failure -> return fresh
        }
        val provider = providers.provider(connection.type)
            ?: return failure(
                oauthFailure(
                    ForgeErrorCode.CONNECTION_OAUTH_UNAVAILABLE,
                    OAuthFailureReason.PROVIDER_UNSUPPORTED,
                    "No OAuth provider handles ${connection.type.displayName}.",
                    connection,
                ),
            )
        val validation = provider.validate(tokens)
        if (!validation.valid) {
            return failure(
                oauthFailure(
                    ForgeErrorCode.CONNECTION_OAUTH_FAILED,
                    OAuthFailureReason.VALIDATION_FAILED,
                    validation.message.ifBlank { OAuthFailureReason.VALIDATION_FAILED.userMessage },
                    connection,
                ),
            )
        }
        return success(validation)
    }

    /**
     * Asks the provider to revoke [connection]. Providers that cannot revoke for a
     * public client report [OAuthRevocationResult.supported] = false; the caller
     * still deletes the local credentials.
     */
    suspend fun revoke(connection: Connection, credentialRef: String): OAuthRevocationResult {
        val provider = providers.provider(connection.type)
            ?: return OAuthRevocationResult(supported = false, revoked = false, message = "No provider")
        val tokens = OAuthTokenCodec.decode(secrets.get(credentialRef))
            ?: return OAuthRevocationResult(supported = false, revoked = false, message = "No stored grant")
        return provider.revoke(tokens)
    }

    /** Capabilities the granted scopes may produce, independent of a connection. */
    fun capabilitiesFor(type: ConnectionType, scopes: Set<String>): Set<ConnectionCapability> =
        providers.provider(type)?.capabilitiesFor(scopes).orEmpty()

    /**
     * Scopes the provider really granted, falling back to the scopes the OAuth app
     * was registered with for providers that do not echo them back.
     */
    fun grantedScopes(type: ConnectionType, tokens: OAuthTokenSet): Set<String> =
        providers.provider(type)?.grantedScopes(tokens).orEmpty()

    companion object {
        /** An authorization attempt is valid for ten minutes, like GitHub's code. */
        const val DEFAULT_AUTHORIZATION_TTL_MILLIS: Long = 10 * 60 * 1000L

        /** Treat credentials as expired one minute early to avoid a race with the API. */
        const val refreshLeewayMillis: Long = 60 * 1000L
    }
}

/** Builds a typed OAuth failure that carries no secret values. */
internal fun oauthFailure(
    code: ForgeErrorCode,
    reason: OAuthFailureReason,
    message: String,
    connection: Connection? = null,
    connectionId: ConnectionId? = null,
): ForgeError = connectionFailure(
    code = code,
    message = message,
    details = buildMap {
        put("reason", reason.name)
        (connectionId ?: connection?.id)?.let { put("connectionId", it.value) }
        connection?.let { put("type", it.type.name) }
    },
)
