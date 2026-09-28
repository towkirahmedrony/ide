package com.agentx.app.integrations.oauth

/**
 * Turns authorization codes and refresh tokens into tokens for a provider.
 *
 * Two strategies exist because this is a **public** Android client:
 *
 * - [OAuthExchangeStrategy.EXCHANGE_BROKER] posts the public parts of the request
 *   to a configured server endpoint that holds the client secret.
 * - [OAuthExchangeStrategy.DIRECT_TOKEN_ENDPOINT] posts straight to the
 *   provider's token endpoint. It carries no client secret, so providers that
 *   require a confidential client (GitHub and Supabase both document
 *   `client_secret` as required for the authorization-code exchange) answer with
 *   an error and the failure is reported as-is — never faked into a success.
 *
 * Nothing in this file logs a code, a verifier, a refresh token or an access token.
 */
class OAuthTokenExchange(
    private val descriptor: OAuthProviderDescriptor,
    private val http: OAuthHttpClient,
    private val clientProvider: () -> OAuthClientConfig,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val client: OAuthClientConfig get() = clientProvider()

    suspend fun exchangeCode(code: String, codeVerifier: String?, redirectUri: String): OAuthTokenResult {
        val parameters = buildMap {
            put("grant_type", GRANT_AUTHORIZATION_CODE)
            put("code", code)
            put("client_id", client.clientId)
            put("redirect_uri", redirectUri)
            codeVerifier?.takeIf { it.isNotBlank() }?.let { put("code_verifier", it) }
        }
        return call(parameters, failureReason = OAuthFailureReason.EXCHANGE_FAILED)
    }

    suspend fun refreshToken(refreshToken: String): OAuthTokenResult {
        if (!descriptor.supportsRefresh) {
            return OAuthTokenResult.Failure(OAuthFailure(OAuthFailureReason.REFRESH_UNSUPPORTED))
        }
        val parameters = buildMap {
            put("grant_type", GRANT_REFRESH_TOKEN)
            put("refresh_token", refreshToken)
            put("client_id", client.clientId)
        }
        return call(parameters, failureReason = OAuthFailureReason.REFRESH_FAILED)
    }

    private suspend fun call(parameters: Map<String, String>, failureReason: OAuthFailureReason): OAuthTokenResult {
        val request = when (client.exchangeStrategy) {
            OAuthExchangeStrategy.EXCHANGE_BROKER -> OAuthHttpRequest(
                method = "POST",
                url = client.exchangeBrokerUrl.orEmpty(),
                headers = mapOf("Accept" to "application/json"),
                contentType = OAuthHttpResponse.CONTENT_TYPE_FORM,
                body = encode(parameters + mapOf("provider" to descriptor.type.name.lowercase())),
            )

            OAuthExchangeStrategy.DIRECT_TOKEN_ENDPOINT -> OAuthHttpRequest(
                method = "POST",
                url = descriptor.tokenEndpoint,
                headers = mapOf("Accept" to "application/json"),
                contentType = OAuthHttpResponse.CONTENT_TYPE_FORM,
                body = encode(parameters),
            )
        }

        val response = try {
            http.execute(request)
        } catch (error: OAuthHttpException) {
            return OAuthTokenResult.Failure(OAuthFailure(OAuthFailureReason.NETWORK, error.message ?: "The provider could not be reached."))
        }

        val fields = TokenResponseParser.parse(response)
        val error = fields["error"]
        if (!response.isSuccess || !error.isNullOrBlank()) {
            return OAuthTokenResult.Failure(
                OAuthFailure(
                    reason = failureReason,
                    message = "${descriptor.displayName} refused the request: ${describe(error, fields)}",
                ),
            )
        }

        val accessToken = fields["access_token"]
        if (accessToken.isNullOrBlank()) {
            return OAuthTokenResult.Failure(OAuthFailure(OAuthFailureReason.TOKEN_MISSING))
        }

        return OAuthTokenResult.Success(
            OAuthTokenSet(
                accessToken = accessToken,
                refreshToken = fields["refresh_token"]?.takeIf { it.isNotBlank() },
                tokenType = fields["token_type"]?.takeIf { it.isNotBlank() } ?: "bearer",
                scopes = parseScopes(fields["scope"]),
                obtainedAtMillis = clock(),
                expiresAtMillis = fields["expires_in"]?.toLongOrNull()?.let { seconds ->
                    clock() + seconds * 1000
                },
                refreshExpiresAtMillis = fields["refresh_token_expires_in"]?.toLongOrNull()?.let { seconds ->
                    clock() + seconds * 1000
                },
            ),
        )
    }

    private fun encode(parameters: Map<String, String>): String =
        parameters.entries.joinToString("&") { (key, value) -> "${OAuthUrl.encode(key)}=${OAuthUrl.encode(value)}" }

    private fun describe(error: String?, fields: Map<String, String>): String {
        val description = fields["error_description"]?.takeIf { it.isNotBlank() }
        val code = error?.takeIf { it.isNotBlank() } ?: "request rejected"
        return if (description != null) "$code ($description)" else code
    }

    private companion object {
        const val GRANT_AUTHORIZATION_CODE = "authorization_code"
        const val GRANT_REFRESH_TOKEN = "refresh_token"

        /** Providers return scopes space- or comma-separated. */
        fun parseScopes(raw: String?): Set<String> = raw
            ?.split(' ', ',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            .orEmpty()
    }
}

/** Reads a token response in either JSON or form-encoded form. */
internal object TokenResponseParser {

    fun parse(response: OAuthHttpResponse): Map<String, String> {
        val contentType = response.contentType.orEmpty().lowercase()
        val body = response.body.trim()
        if (body.isEmpty()) return emptyMap()
        val looksJson = contentType.contains("json") || body.startsWith("{")
        return if (looksJson) parseJson(body) else OAuthQueryParameters.parse("?$body")
    }

    private fun parseJson(body: String): Map<String, String> {
        val root = OAuthJson.parse(body) ?: return emptyMap()
        val fields = (root as? OAuthJsonValue.Obj)?.fields ?: return emptyMap()
        return fields.mapValues { (_, value) ->
            when (value) {
                is OAuthJsonValue.Str -> value.value
                is OAuthJsonValue.Num -> value.value.toString()
                is OAuthJsonValue.Bool -> value.value.toString()
                else -> ""
            }
        }
    }
}
