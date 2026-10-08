package com.agentx.app.integrations.oauth

import com.agentx.app.core.ForgeError
import com.agentx.app.core.ForgeErrorCode
import com.agentx.app.core.ForgeResult
import com.agentx.app.core.failure
import com.agentx.app.core.success
import com.agentx.app.integrations.connection.Connection
import com.agentx.app.integrations.connection.ConnectionCapability
import com.agentx.app.integrations.connection.ConnectionId
import com.agentx.app.integrations.connection.ConnectionType
import com.agentx.app.integrations.connection.connectionFailure
import com.agentx.app.integrations.github.GitHubDiagnostics
import java.security.SecureRandom

/** What the app has to open to let the user authorize. */
data class OAuthAuthorizationPlan(
    val authorizationUrl: String,
    /** Kept for diagnostics only; the pending attempt is the source of truth. */
    val state: String,
    val scopes: Set<String>,
    val expiresAtMillis: Long,
) {
    override fun toString(): String = "OAuthAuthorizationPlan(scopes=${scopes.sorted()})"
}

/** Tokens plus what they authorize, before the manager persists them. */
data class OAuthGrant(
    val connectionId: ConnectionId,
    val tokens: OAuthTokenSet,
    val validation: OAuthValidation,
    val grantedCapabilities: Set<ConnectionCapability>,
) {
    override fun toString(): String =
        "OAuthGrant(connectionId=$connectionId, capabilities=${grantedCapabilities.map { it.id }})"
}

/**
 * The OAuth 2.0 authorization-code + PKCE mechanism, independent of any service.
 *
 * Providers use it for the protocol only: building the provider's authorization
 * URL, keeping the pending attempt in memory, validating `state`/PKCE on the way
 * back, and exchanging the code. Storing the resulting credentials, deciding
 * which account is connected and mapping scopes onto capabilities stay with the
 * provider and the Connection Manager.
 *
 * Codes, verifiers and tokens exist only inside these call frames and the
 * provider's token payload: nothing is logged or attached to a connection record.
 */
class OAuthFlowRunner(
    private val sessions: OAuthSessionStore = InMemoryOAuthSessionStore(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
    private val authorizationTtlMillis: Long = DEFAULT_AUTHORIZATION_TTL_MILLIS,
    /**
     * Resolves the provider a pending authorization belongs to. A pending attempt
     * only stores the connection type, so the runner never holds a provider
     * reference for longer than a request.
     */
    private val providerFor: (ConnectionType) -> OAuthProvider? = { null },
) {

    /** The authorization in flight for [connectionId], if any. */
    fun pendingFor(connectionId: ConnectionId): OAuthPendingAuthorization? = sessions.pendingFor(connectionId)

    fun cancel(connectionId: ConnectionId) = sessions.cancel(connectionId)

    /**
     * Builds the provider's authorization URL with a fresh `state` and PKCE
     * verifier, and remembers the attempt in memory only.
     */
    fun begin(
        provider: OAuthProvider,
        connection: Connection,
        requestedCapabilities: Set<ConnectionCapability> = connection.capabilities,
    ): ForgeResult<OAuthAuthorizationPlan, ForgeError> {
        if (!provider.client.isConfigured) {
            GitHubDiagnostics.failure(
                GitHubDiagnostics.STAGE_AUTH,
                "authorization flow start refused: provider not configured",
                fields = mapOf(
                    "type" to connection.type.name,
                    "connectionId" to connection.id.value,
                    "problem" to (provider.client.configurationProblem ?: "missing client configuration"),
                ),
            )
            return failure(
                oauthFailure(
                    code = ForgeErrorCode.CONNECTION_OAUTH_UNAVAILABLE,
                    reason = OAuthFailureReason.PROVIDER_NOT_CONFIGURED,
                    message = "${provider.descriptor.displayName} authorization is not configured: " +
                        (provider.client.configurationProblem ?: "missing client configuration"),
                    connection = connection,
                ),
            )
        }

        GitHubDiagnostics.auth(
            "authorization flow initialization started",
            mapOf(
                "type" to connection.type.name,
                "connectionId" to connection.id.value,
                "supportsPkce" to provider.descriptor.supportsPkce,
            ),
        )
        val scopes = provider.scopesFor(requestedCapabilities)
        val state = OAuthPkce.createState(random)
        val verifier = if (provider.descriptor.supportsPkce) OAuthPkce.createVerifier(random) else null
        val challenge = verifier?.let(OAuthPkce::challenge)
        val now = clock()
        GitHubDiagnostics.auth(
            if (verifier != null) "PKCE preparation succeeded" else "PKCE not required",
            mapOf("type" to connection.type.name),
        )

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
                requestedCapabilities = requestedCapabilities,
                createdAtMillis = now,
                expiresAtMillis = now + authorizationTtlMillis,
            ),
        )

        GitHubDiagnostics.auth(
            "authorization URL prepared",
            mapOf(
                "type" to connection.type.name,
                "connectionId" to connection.id.value,
                "scopes" to scopes.sorted(),
            ),
        )
        return success(
            OAuthAuthorizationPlan(
                authorizationUrl = url,
                state = state,
                scopes = scopes,
                expiresAtMillis = now + authorizationTtlMillis,
            ),
        )
    }

    /**
     * Handles the provider's redirect.
     *
     * `state` is single use, so a duplicate delivery is reported as a replay and
     * never triggers a second exchange; an unknown `state` matches no connection
     * and is refused without touching any record.
     */
    suspend fun complete(callbackUri: String): ForgeResult<OAuthGrant, ForgeError> {
        GitHubDiagnostics.callback("callback processing started")
        val callback = OAuthCallback.parse(callbackUri)
        GitHubDiagnostics.callback(
            "callback parsed",
            mapOf(
                "codePresent" to callback.hasCode,
                "statePresent" to !callback.state.isNullOrBlank(),
                "error" to (callback.error ?: "(none)"),
            ),
        )

        if (callback.isDenied) {
            sessions.consume(callback.state.orEmpty())
            GitHubDiagnostics.failure(
                GitHubDiagnostics.STAGE_CALLBACK,
                "callback reported access denied",
            )
            return failure(
                oauthFailure(
                    code = ForgeErrorCode.CONNECTION_OAUTH_DENIED,
                    reason = OAuthFailureReason.AUTHORIZATION_DENIED,
                    message = callback.errorDescription ?: "Access was denied on the authorization page.",
                ),
            )
        }

        if (callback.isError) {
            sessions.consume(callback.state.orEmpty())
            GitHubDiagnostics.failure(
                GitHubDiagnostics.STAGE_CALLBACK,
                "callback reported an authorization error",
                fields = mapOf("error" to (callback.error ?: "(none)")),
            )
            return failure(
                oauthFailure(
                    code = ForgeErrorCode.CONNECTION_OAUTH_FAILED,
                    reason = OAuthFailureReason.AUTHORIZATION_ERROR,
                    message = callback.errorDescription ?: "The provider reported '${callback.error}'.",
                ),
            )
        }

        val state = callback.state
        if (state.isNullOrBlank()) {
            return failure(
                oauthFailure(
                    code = ForgeErrorCode.CONNECTION_OAUTH_STATE_INVALID,
                    reason = OAuthFailureReason.STATE_MISMATCH,
                    message = "The authorization response carried no state, so it was not accepted.",
                ),
            )
        }

        val pending = when (val lookup = sessions.consume(state)) {
            is OAuthSessionLookup.Consumed -> lookup.pending

            is OAuthSessionLookup.AlreadyHandled -> return failure(
                oauthFailure(
                    code = ForgeErrorCode.CONNECTION_OAUTH_STATE_INVALID,
                    reason = OAuthFailureReason.STATE_REPLAY,
                    message = "This authorization response was already handled.",
                    connectionId = lookup.pending?.connectionId,
                ),
            )

            is OAuthSessionLookup.Expired -> return failure(
                oauthFailure(
                    code = ForgeErrorCode.CONNECTION_OAUTH_STATE_INVALID,
                    reason = OAuthFailureReason.STATE_EXPIRED,
                    message = "The authorization request expired before it was answered.",
                    connectionId = lookup.pending.connectionId,
                ),
            )

            OAuthSessionLookup.Unknown -> return failure(
                oauthFailure(
                    code = ForgeErrorCode.CONNECTION_OAUTH_STATE_INVALID,
                    reason = OAuthFailureReason.STATE_MISMATCH,
                    message = "The authorization response did not match any request started by this app.",
                ),
            )
        }

        val provider = providerFor(pending.type)
            ?: return failure(
                oauthFailure(
                    code = ForgeErrorCode.CONNECTION_OAUTH_FAILED,
                    reason = OAuthFailureReason.PROVIDER_UNSUPPORTED,
                    message = "The pending authorization has no provider attached.",
                    connectionId = pending.connectionId,
                ),
            )

        val code = callback.code
        if (code.isNullOrBlank()) {
            return failure(
                oauthFailure(
                    code = ForgeErrorCode.CONNECTION_OAUTH_FAILED,
                    reason = OAuthFailureReason.AUTHORIZATION_ERROR,
                    message = "The authorization response carried no code.",
                    connectionId = pending.connectionId,
                ),
            )
        }

        GitHubDiagnostics.auth(
            "token exchange started",
            mapOf("type" to pending.type.name, "connectionId" to pending.connectionId.value),
        )
        val tokens = when (val result = provider.exchange(code, pending.codeVerifier, pending.redirectUri)) {
            is OAuthTokenResult.Success -> result.tokens

            is OAuthTokenResult.Failure -> {
                GitHubDiagnostics.failure(
                    GitHubDiagnostics.STAGE_AUTH,
                    "token exchange failed",
                    fields = mapOf(
                        "reason" to result.failure.reason.name,
                        "connectionId" to pending.connectionId.value,
                    ),
                )
                return failure(
                    oauthFailure(
                        code = ForgeErrorCode.CONNECTION_OAUTH_FAILED,
                        reason = result.failure.reason,
                        message = result.failure.message,
                        connectionId = pending.connectionId,
                    ),
                )
            }
        }
        GitHubDiagnostics.auth(
            "token exchange succeeded",
            mapOf("accessTokenPresent" to true, "refreshTokenPresent" to tokens.hasRefreshToken),
        )

        val validation = provider.validate(tokens)
        if (!validation.valid) {
            GitHubDiagnostics.failure(
                GitHubDiagnostics.STAGE_AUTH,
                "token validation failed after exchange",
                fields = mapOf("connectionId" to pending.connectionId.value),
            )
            return failure(
                oauthFailure(
                    code = ForgeErrorCode.CONNECTION_OAUTH_FAILED,
                    reason = OAuthFailureReason.VALIDATION_FAILED,
                    message = validation.message.ifBlank { OAuthFailureReason.VALIDATION_FAILED.userMessage },
                    connectionId = pending.connectionId,
                ),
            )
        }

        // The provider only grants what the scopes allow, and the connection never
        // gains a capability the user did not ask for.
        val granted = provider.capabilitiesFor(provider.grantedScopes(tokens))
            .intersect(pending.requestedCapabilities)

        return success(
            OAuthGrant(
                connectionId = pending.connectionId,
                tokens = tokens.copy(accountLabel = validation.accountLabel ?: tokens.accountLabel),
                validation = validation,
                grantedCapabilities = granted,
            ),
        )
    }

    /** The provider type a pending authorization belongs to, for diagnostics. */
    fun pendingType(connectionId: ConnectionId): ConnectionType? = sessions.pendingFor(connectionId)?.type

    companion object {
        /** An authorization attempt is valid for ten minutes, like GitHub's code. */
        const val DEFAULT_AUTHORIZATION_TTL_MILLIS: Long = 10 * 60 * 1000L
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
