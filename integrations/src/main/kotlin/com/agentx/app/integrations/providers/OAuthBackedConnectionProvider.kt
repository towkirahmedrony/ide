package com.agentx.app.integrations.providers

import com.agentx.app.core.ForgeError
import com.agentx.app.core.ForgeErrorCode
import com.agentx.app.core.ForgeResult
import com.agentx.app.core.errorOrNull
import com.agentx.app.core.failure
import com.agentx.app.core.success
import com.agentx.app.core.valueOrNull
import com.agentx.app.integrations.connection.AuthorizationStart
import com.agentx.app.integrations.connection.Connection
import com.agentx.app.integrations.connection.ConnectionAuthMethod
import com.agentx.app.integrations.connection.ConnectionCapability
import com.agentx.app.integrations.connection.ConnectionId
import com.agentx.app.integrations.connection.ConnectionProvider
import com.agentx.app.integrations.connection.ProviderGrant
import com.agentx.app.integrations.connection.ProviderIdentity
import com.agentx.app.integrations.connection.ProviderRevocation
import com.agentx.app.integrations.connection.connectionFailure
import com.agentx.app.integrations.oauth.OAuthAuthorizationPlan
import com.agentx.app.integrations.oauth.OAuthFailureReason
import com.agentx.app.integrations.oauth.OAuthFlowRunner
import com.agentx.app.integrations.oauth.OAuthProvider
import com.agentx.app.integrations.oauth.OAuthTokenResult
import com.agentx.app.integrations.oauth.OAuthTokenSet
import com.agentx.app.integrations.oauth.OAuthTokenCodec

/**
 * Base for services whose primary authorization is OAuth.
 *
 * It adapts the OAuth mechanism ([OAuthProvider] + [OAuthFlowRunner]) onto the
 * provider-agnostic [ConnectionProvider] port, and turns tokens into an **opaque
 * payload**: the credential the Connection Manager stores is the encoded token
 * set, which only this class can read back. The manager never parses it, and
 * nothing here logs, echoes or displays a token.
 *
 * Manual API keys / access tokens stay available as a fallback: they are verified
 * against the provider's own API before anything is stored.
 */
abstract class OAuthBackedConnectionProvider(
    protected val oauthProvider: OAuthProvider,
    protected val flow: OAuthFlowRunner,
    protected val clock: () -> Long = System::currentTimeMillis,
    /** Leeway used when deciding whether stored credentials need a refresh. */
    private val refreshLeewayMillis: Long = DEFAULT_REFRESH_LEEWAY_MILLIS,
) : ConnectionProvider {

    override val configured: Boolean get() = oauthProvider.client.isConfigured

    override val configurationProblem: String? get() = oauthProvider.client.configurationProblem

    /** OAuth first, with manual credentials allowed as a fallback. */
    override fun authMethods(): Set<ConnectionAuthMethod> = setOf(
        ConnectionAuthMethod.OAUTH,
        ConnectionAuthMethod.ACCESS_TOKEN,
        ConnectionAuthMethod.API_KEY,
    )

    override fun scopesFor(connection: Connection): Set<String> =
        oauthProvider.scopesFor(connection.capabilities)

    override fun capabilitiesFor(connection: Connection, scopes: Set<String>): Set<ConnectionCapability> =
        oauthProvider.capabilitiesFor(scopes)

    override suspend fun beginAuthorization(connection: Connection): ForgeResult<AuthorizationStart, ForgeError> {
        val started = flow.begin(provider = oauthProvider, connection = connection)
        val plan = started.valueOrNull()
            ?: return failure(started.errorOrNull() ?: unavailable(connection, "The authorization could not start"))
        return success(plan.toStart(connection, oauthProvider.descriptor.displayName))
    }

    override suspend fun completeAuthorization(
        connection: Connection,
        callbackUri: String,
    ): ForgeResult<ProviderGrant, ForgeError> {
        val completed = flow.complete(callbackUri)
        val grant = completed.valueOrNull()
            ?: return failure(completed.errorOrNull() ?: unavailable(connection, "The callback could not be processed"))
        return success(
            ProviderGrant(
                connectionId = grant.connectionId,
                payload = OAuthTokenCodec.encode(grant.tokens),
                scopes = oauthProvider.grantedScopes(grant.tokens),
                identity = ProviderIdentity(
                    accountLabel = grant.validation.accountLabel ?: grant.tokens.accountLabel ?: handleFor(connection),
                    message = grant.validation.message,
                    expiresAtMillis = grant.tokens.expiresAtMillis,
                ),
                capabilities = grant.grantedCapabilities,
                expiresAtMillis = grant.tokens.expiresAtMillis,
                refreshable = grant.tokens.hasRefreshToken && oauthProvider.descriptor.supportsRefresh,
            ),
        )
    }

    override fun hasPendingAuthorization(connectionId: ConnectionId): Boolean =
        flow.pendingFor(connectionId) != null

    override suspend fun cancelAuthorization(connectionId: ConnectionId) = flow.cancel(connectionId)

    override suspend fun verify(connection: Connection, payload: String): ForgeResult<ProviderIdentity, ForgeError> {
        val tokens = OAuthTokenCodec.decode(payload)
            ?: return failure(notAStoredGrant(connection))
        val validation = oauthProvider.validate(tokens)
        if (!validation.valid) {
            return failure(
                unavailable(
                    connection,
                    validation.message.ifBlank { "The stored authorization could not be verified" },
                    code = ForgeErrorCode.CONNECTION_OAUTH_FAILED,
                ),
            )
        }
        return success(
            ProviderIdentity(
                accountLabel = validation.accountLabel ?: tokens.accountLabel ?: handleFor(connection),
                message = validation.message,
                expiresAtMillis = tokens.expiresAtMillis,
            ),
        )
    }

    /**
     * Refreshes the payload when the provider supports it. A grant that is already
     * expired and cannot be refreshed fails, so the connection is marked expired
     * instead of being used.
     */
    override suspend fun refresh(connection: Connection, payload: String): ForgeResult<String, ForgeError> {
        val tokens = OAuthTokenCodec.decode(payload)
            ?: return failure(notAStoredGrant(connection))

        val expired = tokens.isExpired(clock(), refreshLeewayMillis)
        if (!expired) return success(payload)

        val refreshToken = tokens.refreshToken
        if (refreshToken.isNullOrBlank() || !oauthProvider.descriptor.supportsRefresh) {
            return failure(
                unavailable(
                    connection,
                    "${oauthProvider.descriptor.displayName} cannot refresh this grant; reconnect to authorize again.",
                    code = ForgeErrorCode.CONNECTION_CREDENTIAL_EXPIRED,
                    reason = OAuthFailureReason.REFRESH_UNSUPPORTED,
                ),
            )
        }

        return when (val result = oauthProvider.refresh(refreshToken)) {
            is OAuthTokenResult.Success -> {
                val refreshed = result.tokens.copy(
                    // Providers may keep the same refresh token across refreshes.
                    refreshToken = result.tokens.refreshToken ?: refreshToken,
                    accountLabel = result.tokens.accountLabel ?: tokens.accountLabel,
                    scopes = result.tokens.scopes.ifEmpty { tokens.scopes },
                )
                success(OAuthTokenCodec.encode(refreshed))
            }

            is OAuthTokenResult.Failure -> failure(
                unavailable(
                    connection,
                    result.failure.message,
                    code = ForgeErrorCode.CONNECTION_CREDENTIAL_EXPIRED,
                    reason = result.failure.reason,
                ),
            )
        }
    }

    /**
     * True when the stored payload must be refreshed before use. An expired payload
     * is reported even when the provider cannot refresh it: the manager then marks
     * the connection expired and asks the user to re-authorize, instead of lending a
     * credential that is already known to be dead.
     */
    override fun needsRefresh(connection: Connection, payload: String): Boolean {
        val tokens = OAuthTokenCodec.decode(payload) ?: return false
        return tokens.isExpired(clock(), refreshLeewayMillis)
    }

    override suspend fun revoke(connection: Connection, payload: String): ProviderRevocation {
        val tokens = OAuthTokenCodec.decode(payload)
            ?: return ProviderRevocation(supported = false, revoked = false, message = "No stored grant")
        return oauthProvider.revoke(tokens)
    }

    /**
     * Verifies a manually supplied token against the provider's API before it is
     * stored. The token is passed straight through; it is never persisted here.
     */
    override suspend fun verifyManualCredential(
        connection: Connection,
        credential: String,
    ): ForgeResult<ProviderIdentity, ForgeError> {
        if (credential.isBlank()) {
            return failure(
                connectionFailure(
                    code = ForgeErrorCode.CONNECTION_INVALID,
                    message = "The credential must not be blank",
                    details = mapOf("connectionId" to connection.id.value),
                ),
            )
        }
        val scopes = oauthProvider.client.configuredScopes.ifEmpty { scopesFor(connection) }
        val tokens = OAuthTokenSet(
            accessToken = credential,
            tokenType = "bearer",
            scopes = scopes,
            obtainedAtMillis = clock(),
        )
        val validation = oauthProvider.validate(tokens)
        if (!validation.valid) {
            return failure(
                unavailable(
                    connection,
                    validation.message.ifBlank {
                        "The credential was rejected by ${oauthProvider.descriptor.displayName}"
                    },
                    code = ForgeErrorCode.CONNECTION_OAUTH_FAILED,
                    reason = OAuthFailureReason.VALIDATION_FAILED,
                ),
            )
        }
        return success(
            ProviderIdentity(
                accountLabel = validation.accountLabel ?: handleFor(connection),
                message = validation.message,
            ),
        )
    }

    /** Fallback label when the provider did not report an account name. */
    protected open fun handleFor(connection: Connection): String = connection.displayName

    private fun OAuthAuthorizationPlan.toStart(connection: Connection, displayName: String): AuthorizationStart =
        AuthorizationStart.OpenUrl(
            connectionId = connection.id,
            type = connection.type,
            displayName = displayName,
            authorizationUrl = authorizationUrl,
            scopes = scopes,
            expiresAtMillis = expiresAtMillis,
        )

    private fun notAStoredGrant(connection: Connection): ForgeError = unavailable(
        connection,
        "No stored authorization exists for this connection.",
        code = ForgeErrorCode.CONNECTION_CREDENTIAL_EXPIRED,
        reason = OAuthFailureReason.NOT_CONNECTED,
    )

    companion object {
        /** Treat credentials as expired one minute early to avoid racing the API. */
        const val DEFAULT_REFRESH_LEEWAY_MILLIS: Long = 60 * 1000L
    }
}

/** Builds a provider failure that contains no credential material. */
internal fun unavailable(
    connection: Connection,
    message: String,
    code: ForgeErrorCode = ForgeErrorCode.CONNECTION_OAUTH_FAILED,
    reason: OAuthFailureReason? = null,
): ForgeError = connectionFailure(
    code = code,
    message = message,
    details = buildMap {
        put("connectionId", connection.id.value)
        put("type", connection.type.name)
        reason?.let { put("reason", it.name) }
    },
)
