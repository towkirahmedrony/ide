package com.agentx.app.integrations.oauth

/**
 * Typed lifecycle of a device-flow authorization.
 *
 * This is deliberately **separate** from [com.agentx.app.integrations.connection.ConnectionStatus]:
 * a connection has one saved state, while an authorization attempt has its own
 * progress (waiting for the user, polling, rate limited, ...). The UI renders this
 * for the GitHub device flow; nothing here carries a token.
 */
enum class DeviceFlowState(val displayName: String) {
    DISCONNECTED("Not connected"),
    AUTHORIZING("Requesting authorization code"),
    WAITING_FOR_USER("Waiting for you to authorize"),
    POLLING("Checking authorization"),
    CONNECTED("Connected"),
    EXPIRED("Authorization expired"),
    AUTH_ERROR("Authorization failed"),
    RATE_LIMITED("Rate limited by the provider"),
    NETWORK_ERROR("Network unavailable"),
    ;

    /** True while the attempt is still running. */
    val isInProgress: Boolean get() = this == AUTHORIZING || this == WAITING_FOR_USER || this == POLLING

    /** True once the attempt has settled, one way or another. */
    val isTerminal: Boolean
        get() = this == CONNECTED || this == EXPIRED || this == AUTH_ERROR ||
            this == RATE_LIMITED || this == NETWORK_ERROR

    companion object {
        /**
         * The state an attempt settles into for a protocol failure. A denied or
         * malformed response is an authentication error; the other categories are
         * distinguished so the user can tell "try again" from "authorize again".
         */
        fun forFailure(reason: OAuthFailureReason?): DeviceFlowState = when (reason) {
            OAuthFailureReason.DEVICE_CODE_EXPIRED -> EXPIRED
            OAuthFailureReason.RATE_LIMITED -> RATE_LIMITED
            OAuthFailureReason.NETWORK -> NETWORK_ERROR
            else -> AUTH_ERROR
        }
    }
}

/**
 * The typed device-authorization response. [deviceCode] is the credential the
 * client polls with and must never be shown or logged; [userCode] and
 * [verificationUri] are the values the user needs to authorize on the provider.
 */
data class OAuthDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresInSeconds: Long,
    val intervalSeconds: Long,
) {
    /** Never prints the device code or the user code. */
    override fun toString(): String =
        "OAuthDeviceCode(userCode=present, verificationUri=$verificationUri, " +
            "expiresInSeconds=$expiresInSeconds, intervalSeconds=$intervalSeconds)"
}

/** Result of requesting a device authorization. */
sealed interface OAuthDeviceCodeResult {
    data class Success(val code: OAuthDeviceCode) : OAuthDeviceCodeResult

    data class Failure(val reason: OAuthFailureReason, val message: String) : OAuthDeviceCodeResult
}

/** Result of one poll against the device token endpoint. */
sealed interface OAuthDevicePollResult {
    /** `authorization_pending`: the user has not approved yet; keep polling. */
    data object Pending : OAuthDevicePollResult

    /** `slow_down`: poll no faster than [intervalSeconds] from now on. */
    data class SlowDown(val intervalSeconds: Long) : OAuthDevicePollResult

    /** The user approved; the tokens are ready to store. */
    data class Success(val tokens: OAuthTokenSet) : OAuthDevicePollResult

    /** A terminal protocol failure. */
    data class Failure(val reason: OAuthFailureReason, val message: String) : OAuthDevicePollResult
}

/**
 * Focused port for the OAuth 2.0 Device Authorization Grant (RFC 8628) as GitHub
 * implements it.
 *
 * It owns device-code requests, token polling, typed response parsing and typed
 * error mapping, and nothing else: no UI state, no persistence, no connection
 * lifecycle. Implementations must not log a device code, a user code or a token.
 */
interface DeviceFlowClient {

    suspend fun requestDeviceCode(scopes: Set<String>): OAuthDeviceCodeResult

    suspend fun poll(deviceCode: String): OAuthDevicePollResult
}
