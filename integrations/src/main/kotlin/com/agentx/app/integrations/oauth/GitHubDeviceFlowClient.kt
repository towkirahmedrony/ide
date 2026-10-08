package com.agentx.app.integrations.oauth

import com.agentx.app.integrations.github.GitHubDiagnostics
import kotlin.coroutines.cancellation.CancellationException

/**
 * GitHub's OAuth Device Flow (RFC 8628), the flow a public Android client can run
 * without a browser redirect and **without a client secret**.
 *
 * Endpoints and fields follow GitHub's current documentation:
 * `POST https://github.com/login/device/code` returns `device_code`, `user_code`,
 * `verification_uri`, `expires_in` and `interval`; `POST
 * https://github.com/login/oauth/access_token` is then polled with `client_id`,
 * `device_code` and the device grant type, and answers with a token or one of
 * `authorization_pending`, `slow_down`, `access_denied`, `expired_token`.
 *
 * The client owns the HTTP boundary only. It never stores, logs or displays a
 * device code, user code or token; only [OAuthDeviceCode]'s user-facing fields and
 * [OAuthTokenSet]'s opaque value leave it.
 */
class GitHubDeviceFlowClient(
    private val http: OAuthHttpClient,
    private val clientProvider: () -> OAuthClientConfig,
    private val clock: () -> Long = System::currentTimeMillis,
    private val deviceCodeEndpoint: String = DEVICE_CODE_ENDPOINT,
    private val tokenEndpoint: String = TOKEN_ENDPOINT,
) : DeviceFlowClient {

    override suspend fun requestDeviceCode(scopes: Set<String>): OAuthDeviceCodeResult {
        val clientId = clientProvider().clientId
        if (clientId.isBlank()) {
            GitHubDiagnostics.failure(
                GitHubDiagnostics.STAGE_AUTH,
                "device authorization refused: no client id configured",
                fields = mapOf("clientIdPresent" to false),
            )
            return OAuthDeviceCodeResult.Failure(
                reason = OAuthFailureReason.PROVIDER_NOT_CONFIGURED,
                message = "GitHub authorization is not configured: no Client ID is set.",
            )
        }
        GitHubDiagnostics.auth(
            "device authorization request prepared",
            mapOf("endpoint" to deviceCodeEndpoint, "scopes" to scopes.sorted()),
        )

        val request = OAuthHttpRequest(
            method = "POST",
            url = deviceCodeEndpoint,
            headers = JSON_HEADERS,
            contentType = OAuthHttpResponse.CONTENT_TYPE_FORM,
            body = encode(
                buildMap {
                    put("client_id", clientId)
                    scopes.takeIf { it.isNotEmpty() }?.let { put("scope", it.joinToString(" ")) }
                },
            ),
        )
        GitHubDiagnostics.auth("device authorization request dispatched")
        val response = call(request)
        if (response == null) {
            GitHubDiagnostics.failure(
                GitHubDiagnostics.STAGE_AUTH,
                "device authorization request failed: GitHub could not be reached",
            )
            return OAuthDeviceCodeResult.Failure(OAuthFailureReason.NETWORK, "GitHub could not be reached.")
        }
        GitHubDiagnostics.auth(
            "device authorization response received",
            mapOf("httpStatus" to response.statusCode),
        )

        GitHubDiagnostics.auth("device authorization response parsing started")
        val fields = TokenResponseParser.parse(response)
        val error = fields["error"]?.takeIf { it.isNotBlank() }
        if (error != null || !response.isSuccess) {
            GitHubDiagnostics.failure(
                GitHubDiagnostics.STAGE_AUTH,
                "device authorization request rejected",
                fields = mapOf(
                    "httpStatus" to response.statusCode,
                    "errorCode" to (error ?: "(none)"),
                ),
            )
            return OAuthDeviceCodeResult.Failure(
                reason = failureReasonFor(response.statusCode, error),
                message = describe("GitHub refused the device authorization request", error, fields),
            )
        }

        val deviceCode = fields["device_code"]?.takeIf { it.isNotBlank() }
            ?: return malformed(missingField("device_code"))
        val userCode = fields["user_code"]?.takeIf { it.isNotBlank() }
            ?: return malformed(missingField("user_code"))
        // GitHub has used both names over time; accept either documented form.
        val verificationUri = (fields["verification_uri"] ?: fields["verification_url"])?.takeIf { it.isNotBlank() }
            ?: return malformed(missingField("verification_uri"))
        val expiresIn = positiveSeconds(fields, "expires_in") ?: DEFAULT_EXPIRES_IN_SECONDS
        val interval = positiveSeconds(fields, "interval") ?: DEFAULT_INTERVAL_SECONDS

        GitHubDiagnostics.auth(
            "device authorization initialized",
            mapOf(
                "verificationUriPresent" to verificationUri.isNotBlank(),
                "expiresInSeconds" to expiresIn,
                "intervalSeconds" to interval,
            ),
        )
        return OAuthDeviceCodeResult.Success(
            OAuthDeviceCode(
                deviceCode = deviceCode,
                userCode = userCode,
                verificationUri = verificationUri,
                expiresInSeconds = expiresIn,
                intervalSeconds = interval,
            ),
        )
    }

    override suspend fun poll(deviceCode: String): OAuthDevicePollResult {
        val clientId = clientProvider().clientId
        if (clientId.isBlank()) {
            GitHubDiagnostics.failure(
                GitHubDiagnostics.STAGE_AUTH,
                "token poll refused: no client id configured",
            )
            return OAuthDevicePollResult.Failure(
                reason = OAuthFailureReason.PROVIDER_NOT_CONFIGURED,
                message = "GitHub authorization is not configured: no Client ID is set.",
            )
        }

        val request = OAuthHttpRequest(
            method = "POST",
            url = tokenEndpoint,
            headers = JSON_HEADERS,
            contentType = OAuthHttpResponse.CONTENT_TYPE_FORM,
            body = encode(
                mapOf(
                    "client_id" to clientId,
                    "device_code" to deviceCode,
                    "grant_type" to GRANT_TYPE_DEVICE_CODE,
                ),
            ),
        )
        val response = call(request)
        if (response == null) {
            GitHubDiagnostics.failure(
                GitHubDiagnostics.STAGE_AUTH,
                "token poll failed: GitHub could not be reached",
            )
            return OAuthDevicePollResult.Failure(OAuthFailureReason.NETWORK, "GitHub could not be reached.")
        }

        // A throttled or failing endpoint is reported before the body is trusted.
        if (response.statusCode == HTTP_TOO_MANY_REQUESTS) {
            GitHubDiagnostics.warn(
                GitHubDiagnostics.STAGE_AUTH,
                "token poll rate limited",
                mapOf("httpStatus" to response.statusCode),
            )
            return OAuthDevicePollResult.Failure(
                reason = OAuthFailureReason.RATE_LIMITED,
                message = "GitHub is rate limiting this app.",
            )
        }
        if (response.statusCode >= HTTP_SERVER_ERROR) {
            GitHubDiagnostics.failure(
                GitHubDiagnostics.STAGE_AUTH,
                "token poll failed: GitHub temporarily unavailable",
                fields = mapOf("httpStatus" to response.statusCode),
            )
            return OAuthDevicePollResult.Failure(
                reason = OAuthFailureReason.NETWORK,
                message = "GitHub is temporarily unavailable (HTTP ${response.statusCode}).",
            )
        }

        val fields = TokenResponseParser.parse(response)
        val error = fields["error"]?.takeIf { it.isNotBlank() }
        if (error != null) {
            // `authorization_pending` is the normal "not approved yet" answer and is
            // reported by the polling loop, so it is not logged on every poll here.
            if (error != ERROR_AUTHORIZATION_PENDING) {
                GitHubDiagnostics.warn(
                    GitHubDiagnostics.STAGE_AUTH,
                    "token poll reported '$error'",
                    mapOf("httpStatus" to response.statusCode),
                )
            }
            return when (error) {
                ERROR_AUTHORIZATION_PENDING -> OAuthDevicePollResult.Pending

                ERROR_SLOW_DOWN -> OAuthDevicePollResult.SlowDown(
                    positiveSeconds(fields, "interval") ?: DEFAULT_SLOW_DOWN_INTERVAL_SECONDS,
                )

                ERROR_ACCESS_DENIED -> OAuthDevicePollResult.Failure(
                    reason = OAuthFailureReason.AUTHORIZATION_DENIED,
                    message = fields["error_description"]?.takeIf { it.isNotBlank() }
                        ?: "Access was denied on GitHub.",
                )

                ERROR_EXPIRED_TOKEN -> OAuthDevicePollResult.Failure(
                    reason = OAuthFailureReason.DEVICE_CODE_EXPIRED,
                    message = "The device code expired before it was approved.",
                )

                ERROR_DEVICE_FLOW_DISABLED -> OAuthDevicePollResult.Failure(
                    reason = OAuthFailureReason.DEVICE_FLOW_DISABLED,
                    message = "Device authorization is disabled for this GitHub OAuth app.",
                )

                else -> OAuthDevicePollResult.Failure(
                    reason = OAuthFailureReason.AUTHORIZATION_ERROR,
                    message = describe("GitHub refused the device authorization", error, fields),
                )
            }
        }

        if (!response.isSuccess) {
            GitHubDiagnostics.failure(
                GitHubDiagnostics.STAGE_AUTH,
                "token poll rejected",
                fields = mapOf("httpStatus" to response.statusCode),
            )
            return OAuthDevicePollResult.Failure(
                reason = failureReasonFor(response.statusCode, error = null),
                message = "GitHub rejected the request (HTTP ${response.statusCode}).",
            )
        }

        val accessToken = fields["access_token"]?.takeIf { it.isNotBlank() }
            ?: return OAuthDevicePollResult.Failure(
                reason = OAuthFailureReason.AUTHORIZATION_ERROR,
                message = "GitHub did not return an access token.",
            ).also {
                GitHubDiagnostics.failure(
                    GitHubDiagnostics.STAGE_AUTH,
                    "token poll response missing access_token",
                    fields = mapOf("httpStatus" to response.statusCode),
                )
            }

        GitHubDiagnostics.auth(
            "token response received",
            mapOf(
                "accessTokenPresent" to true,
                "refreshTokenPresent" to !fields["refresh_token"].isNullOrBlank(),
                "scopes" to parseScopes(fields["scope"]).sorted(),
            ),
        )
        return OAuthDevicePollResult.Success(
            OAuthTokenSet(
                accessToken = accessToken,
                refreshToken = fields["refresh_token"]?.takeIf { it.isNotBlank() },
                tokenType = fields["token_type"]?.takeIf { it.isNotBlank() } ?: "bearer",
                scopes = parseScopes(fields["scope"]),
                obtainedAtMillis = clock(),
                expiresAtMillis = positiveSeconds(fields, "expires_in")?.let { seconds ->
                    clock() + seconds * 1000
                },
                refreshExpiresAtMillis = positiveSeconds(fields, "refresh_token_expires_in")
                    ?.let { seconds -> clock() + seconds * 1000 },
            ),
        )
    }

    private fun malformed(message: String): OAuthDeviceCodeResult {
        GitHubDiagnostics.failure(
            GitHubDiagnostics.STAGE_AUTH,
            "device authorization response malformed: $message",
        )
        return OAuthDeviceCodeResult.Failure(OAuthFailureReason.AUTHORIZATION_ERROR, message)
    }

    /** Names the response field that was missing, which is safe (it is not a value). */
    private fun missingField(name: String): String = "GitHub did not return $name."

    /**
     * Reads a positive integer field. GitHub answers with a JSON number and the
     * shared parser stringifies it (`900` becomes `900.0`), so both shapes are
     * accepted instead of silently falling back to the default.
     */
    private fun positiveSeconds(fields: Map<String, String>, name: String): Long? = fields[name]
        ?.let { raw -> raw.toLongOrNull() ?: raw.toDoubleOrNull()?.toLong() }
        ?.takeIf { it > 0 }

    /**
     * Performs the call, turning a transport failure into `null`. Cancellation is
     * never swallowed: a cancelled coroutine must stop the flow, not be reported as
     * a network error.
     */
    private suspend fun call(request: OAuthHttpRequest): OAuthHttpResponse? = try {
        http.execute(request)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: OAuthHttpException) {
        null
    } catch (_: Exception) {
        null
    }

    private fun failureReasonFor(statusCode: Int, error: String?): OAuthFailureReason = when {
        statusCode == HTTP_TOO_MANY_REQUESTS -> OAuthFailureReason.RATE_LIMITED
        statusCode >= HTTP_SERVER_ERROR -> OAuthFailureReason.NETWORK
        error == ERROR_DEVICE_FLOW_DISABLED -> OAuthFailureReason.DEVICE_FLOW_DISABLED
        error == ERROR_EXPIRED_TOKEN -> OAuthFailureReason.DEVICE_CODE_EXPIRED
        error == ERROR_ACCESS_DENIED -> OAuthFailureReason.AUTHORIZATION_DENIED
        else -> OAuthFailureReason.AUTHORIZATION_ERROR
    }

    private fun describe(prefix: String, error: String?, fields: Map<String, String>): String {
        val detail = fields["error_description"]?.takeIf { it.isNotBlank() }
        return when {
            error != null && detail != null -> "$prefix: $error ($detail)"
            error != null -> "$prefix: $error"
            else -> prefix
        }
    }

    private fun encode(parameters: Map<String, String>): String =
        parameters.entries.joinToString("&") { (key, value) -> "${OAuthUrl.encode(key)}=${OAuthUrl.encode(value)}" }

    companion object {
        const val DEVICE_CODE_ENDPOINT: String = "https://github.com/login/device/code"
        const val TOKEN_ENDPOINT: String = "https://github.com/login/oauth/access_token"

        /** The grant type RFC 8628 defines for the device flow. */
        const val GRANT_TYPE_DEVICE_CODE: String = "urn:ietf:params:oauth:grant-type:device_code"

        const val ERROR_AUTHORIZATION_PENDING: String = "authorization_pending"
        const val ERROR_SLOW_DOWN: String = "slow_down"
        const val ERROR_ACCESS_DENIED: String = "access_denied"
        const val ERROR_EXPIRED_TOKEN: String = "expired_token"
        const val ERROR_DEVICE_FLOW_DISABLED: String = "device_flow_disabled"

        /** GitHub's default device code lifetime when it does not echo one. */
        const val DEFAULT_EXPIRES_IN_SECONDS: Long = 900L

        /** GitHub's default polling interval when it does not echo one. */
        const val DEFAULT_INTERVAL_SECONDS: Long = 5L

        /** RFC 8628 asks the client to add five seconds when told to slow down. */
        const val DEFAULT_SLOW_DOWN_INTERVAL_SECONDS: Long = DEFAULT_INTERVAL_SECONDS + 5L

        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val HTTP_SERVER_ERROR = 500

        private val JSON_HEADERS = mapOf("Accept" to "application/json")

        /** Providers return scopes space- or comma-separated. */
        private fun parseScopes(raw: String?): Set<String> = raw
            ?.split(' ', ',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            .orEmpty()
    }
}
