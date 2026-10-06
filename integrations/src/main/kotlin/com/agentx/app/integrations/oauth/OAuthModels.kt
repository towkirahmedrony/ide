package com.agentx.app.integrations.oauth

import com.agentx.app.integrations.connection.ConnectionCapability
import com.agentx.app.integrations.connection.ConnectionId
import com.agentx.app.integrations.connection.ConnectionType

/**
 * What a provider exposes for authorization. Values are public metadata; no
 * client secret is ever part of a descriptor.
 */
data class OAuthProviderDescriptor(
    val type: ConnectionType,
    val displayName: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val supportsPkce: Boolean,
    val supportsRefresh: Boolean,
    val supportsRevocation: Boolean,
    /** Scopes requested for every default capability of the service. */
    val defaultScopes: Set<String> = emptySet(),
    /** Provider-specific note shown in the Connections UI, when useful. */
    val note: String? = null,
)

/** Public client values for one provider. Never contains a client secret. */
data class OAuthClientConfig(
    val clientId: String,
    val redirectUri: String,
    /**
     * Server-side endpoint that performs the confidential authorization-code
     * exchange. Public Android clients cannot hold a client secret, so the code
     * is exchanged where the secret lives.
     */
    val exchangeBrokerUrl: String? = null,
    /** Scopes registered for the OAuth app; used when the provider does not echo them. */
    val configuredScopes: Set<String> = emptySet(),
) {
    val isConfigured: Boolean get() = clientId.isNotBlank() && redirectUri.isNotBlank()

    val exchangeStrategy: OAuthExchangeStrategy
        get() = if (exchangeBrokerUrl.isNullOrBlank()) {
            OAuthExchangeStrategy.DIRECT_TOKEN_ENDPOINT
        } else {
            OAuthExchangeStrategy.EXCHANGE_BROKER
        }

    /** Explanation for the UI when [isConfigured] is false. */
    val configurationProblem: String?
        get() = when {
            clientId.isBlank() && redirectUri.isBlank() ->
                "No OAuth client id or redirect URI is configured."
            clientId.isBlank() -> "No OAuth client id is configured."
            redirectUri.isBlank() -> "No OAuth redirect URI is configured."
            else -> null
        }
}

/** Where the authorization code is turned into tokens. */
enum class OAuthExchangeStrategy(val displayName: String) {
    /** The app posts the code to a configured endpoint that holds the client secret. */
    EXCHANGE_BROKER("Server exchange"),

    /** The app posts the code straight to the provider's token endpoint. */
    DIRECT_TOKEN_ENDPOINT("Direct exchange"),
}

/** Everything a provider needs to build an authorization URL. */
data class OAuthAuthorizationRequest(
    val clientId: String,
    val redirectUri: String,
    val state: String,
    val scopes: Set<String>,
    val codeChallenge: String? = null,
    val codeChallengeMethod: String? = null,
    val additionalParameters: Map<String, String> = emptyMap(),
)

/**
 * A stored OAuth grant. The values are secrets: [toString] never prints a token,
 * and instances must not be logged, persisted outside [com.agentx.app.integrations.connection.ConnectionSecretStore]
 * or handed to an agent.
 */
data class OAuthTokenSet(
    val accessToken: String,
    val refreshToken: String? = null,
    val tokenType: String = "bearer",
    val scopes: Set<String> = emptySet(),
    val obtainedAtMillis: Long = 0L,
    val expiresAtMillis: Long? = null,
    val refreshExpiresAtMillis: Long? = null,
    val accountLabel: String? = null,
) {
    val hasRefreshToken: Boolean get() = !refreshToken.isNullOrBlank()

    fun isExpired(now: Long, leewayMillis: Long = 0L): Boolean {
        val expires = expiresAtMillis ?: return false
        return now + leewayMillis >= expires
    }

    override fun toString(): String =
        "OAuthTokenSet(tokenType=$tokenType, scopes=${scopes.sorted()}, " +
            "refreshToken=${if (hasRefreshToken) "present" else "absent"}, expiresAtMillis=$expiresAtMillis)"
}

/** Why authorization did not succeed. Safe to show to the user. */
enum class OAuthFailureReason(val userMessage: String) {
    PROVIDER_UNSUPPORTED("This service has no OAuth support."),
    PROVIDER_NOT_CONFIGURED("OAuth is not configured for this provider in this build."),
    NO_PENDING_AUTHORIZATION("No authorization is waiting for a response."),
    STATE_MISMATCH("The authorization response did not match the request that started it."),
    STATE_EXPIRED("The authorization request expired. Start the connection again."),
    STATE_REPLAY("This authorization response was already handled."),
    AUTHORIZATION_DENIED("Access was denied on the provider's authorization page."),
    AUTHORIZATION_ERROR("The provider reported an authorization error."),
    EXCHANGE_FAILED("The authorization code could not be exchanged for credentials."),
    TOKEN_MISSING("The provider did not return an access token."),
    VALIDATION_FAILED("The new credentials could not be verified with the provider."),
    REFRESH_UNSUPPORTED("This provider does not support refreshing tokens."),
    REFRESH_FAILED("The credentials could not be refreshed."),
    REVOKE_FAILED("The provider did not confirm that access was revoked."),
    NETWORK("The provider could not be reached."),
    NOT_CONNECTED("The connection is not authorized."),
    /** The device code the user was approving has expired. */
    DEVICE_CODE_EXPIRED("The authorization code expired. Start the connection again."),
    /** The provider is throttling this app; the user should try again later. */
    RATE_LIMITED("The provider is rate limiting this app. Try again later."),
    /** The provider has device flow disabled for this OAuth app. */
    DEVICE_FLOW_DISABLED("Device authorization is disabled for this OAuth app."),
}

/** A failure from the OAuth layer: a coarse reason plus a user-facing message. */
data class OAuthFailure(
    val reason: OAuthFailureReason,
    val message: String = reason.userMessage,
) {
    override fun toString(): String = "OAuthFailure(reason=$reason)"
}

/** Result of a token exchange or refresh. Never carries a failure token value. */
sealed interface OAuthTokenResult {
    data class Success(val tokens: OAuthTokenSet) : OAuthTokenResult
    data class Failure(val failure: OAuthFailure) : OAuthTokenResult
}

/** Outcome of probing the new credentials with the provider. */
data class OAuthValidation(
    val valid: Boolean,
    val accountLabel: String? = null,
    val message: String = "",
) {
    override fun toString(): String = "OAuthValidation(valid=$valid, accountLabel=$accountLabel)"
}

/** Outcome of asking the provider to revoke a grant. */
data class OAuthRevocationResult(
    val supported: Boolean,
    val revoked: Boolean,
    val message: String = "",
) {
    override fun toString(): String = "OAuthRevocationResult(supported=$supported, revoked=$revoked)"
}

/**
 * A parsed authorization response. [code] and [state] are sensitive: they are
 * never logged and never leave this object's narrow hand-off into the authorizer.
 */
data class OAuthCallback(
    val code: String? = null,
    val state: String? = null,
    val error: String? = null,
    val errorDescription: String? = null,
) {
    val isDenied: Boolean get() = error.equals(ERROR_ACCESS_DENIED, ignoreCase = true)

    val isError: Boolean get() = !error.isNullOrBlank()

    val hasCode: Boolean get() = !code.isNullOrBlank()

    override fun toString(): String =
        "OAuthCallback(code=${if (hasCode) "present" else "absent"}, state=${if (state.isNullOrBlank()) "absent" else "present"}, error=$error)"

    companion object {
        const val ERROR_ACCESS_DENIED = "access_denied"

        /** Reads the authorization response out of a redirect URI's query or fragment. */
        fun parse(callbackUri: String): OAuthCallback {
            val parameters = OAuthQueryParameters.parse(callbackUri)
            return OAuthCallback(
                code = parameters["code"]?.takeIf { it.isNotBlank() },
                state = parameters["state"]?.takeIf { it.isNotBlank() },
                error = parameters["error"]?.takeIf { it.isNotBlank() },
                errorDescription = parameters["error_description"]?.takeIf { it.isNotBlank() },
            )
        }
    }
}

/** An authorization that has been started and is waiting for the provider. */
data class OAuthPendingAuthorization(
    val connectionId: ConnectionId,
    val type: ConnectionType,
    val state: String,
    val codeVerifier: String?,
    val codeChallenge: String?,
    val redirectUri: String,
    val scopes: Set<String>,
    val requestedCapabilities: Set<ConnectionCapability>,
    val createdAtMillis: Long,
    val expiresAtMillis: Long,
) {
    fun isExpired(now: Long): Boolean = now >= expiresAtMillis

    /** Never prints the verifier or the state value. */
    override fun toString(): String =
        "OAuthPendingAuthorization(connectionId=$connectionId, type=${type.name}, " +
            "capabilities=${requestedCapabilities.map { it.id }}, expiresAtMillis=$expiresAtMillis)"
}

/** What happened when a callback was matched against the pending authorizations. */
sealed interface OAuthSessionLookup {
    data class Consumed(val pending: OAuthPendingAuthorization) : OAuthSessionLookup

    data class AlreadyHandled(val pending: OAuthPendingAuthorization?) : OAuthSessionLookup

    data class Expired(val pending: OAuthPendingAuthorization) : OAuthSessionLookup

    /** No authorization was ever started with this state value. */
    data object Unknown : OAuthSessionLookup
}

/** Handed to the UI so it can open the provider's authorization page. */
data class OAuthAuthorizationStart(
    val connectionId: ConnectionId,
    val type: ConnectionType,
    val displayName: String,
    val authorizationUrl: String,
    val scopes: Set<String>,
    val expiresAtMillis: Long,
) {
    override fun toString(): String =
        "OAuthAuthorizationStart(connectionId=$connectionId, type=${type.name}, scopes=${scopes.sorted()})"
}

/** Everything the Connection Manager needs to persist a completed authorization. */
data class OAuthCompletion(
    val connectionId: ConnectionId,
    val tokens: OAuthTokenSet,
    val validation: OAuthValidation,
    val grantedCapabilities: Set<ConnectionCapability>,
) {
    override fun toString(): String =
        "OAuthCompletion(connectionId=$connectionId, capabilities=${grantedCapabilities.map { it.id }})"
}

