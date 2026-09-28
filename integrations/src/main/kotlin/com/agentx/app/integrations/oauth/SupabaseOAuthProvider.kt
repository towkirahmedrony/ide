package com.agentx.app.integrations.oauth

import com.agentx.app.integrations.connection.ConnectionCapabilities
import com.agentx.app.integrations.connection.ConnectionCapability
import com.agentx.app.integrations.connection.ConnectionType

/**
 * Official Supabase authorization (OAuth 2.0 authorization code + PKCE).
 *
 * Supabase publishes a single OAuth surface for integrations:
 * `GET https://api.supabase.com/v1/oauth/authorize` and
 * `POST https://api.supabase.com/v1/oauth/token`. PKCE with `S256` is the
 * recommended flow, and the same token endpoint refreshes access tokens.
 *
 * Two documented properties drive this implementation:
 *
 * - The `scope` query parameter is deprecated: scopes are fixed when the OAuth
 *   app is registered, so the granted set is read from
 *   [OAuthClientConfig.configuredScopes] whenever a token response does not echo
 *   `scope`.
 * - The code exchange authenticates the client with the client secret, which the
 *   public Android client does not hold. It therefore goes through the configured
 *   server-side broker; without one the token endpoint refuses the request and
 *   that refusal is reported as-is.
 */
class SupabaseOAuthProvider(
    client: OAuthClientConfig,
    private val http: OAuthHttpClient,
    private val clock: () -> Long = System::currentTimeMillis,
    private val authorizationEndpoint: String = AUTHORIZATION_ENDPOINT,
) : OAuthProvider {

    @Volatile
    private var liveClient: OAuthClientConfig = client

    override val client: OAuthClientConfig get() = liveClient

    /** Hot-applies a personal Client ID / redirect URI. Never a client secret. */
    fun replaceClient(next: OAuthClientConfig) {
        liveClient = next
    }

    override val descriptor: OAuthProviderDescriptor = OAuthProviderDescriptor(
        type = ConnectionType.SUPABASE,
        displayName = "Supabase",
        authorizationEndpoint = authorizationEndpoint,
        tokenEndpoint = TOKEN_ENDPOINT,
        supportsPkce = true,
        supportsRefresh = true,
        supportsRevocation = false,
        defaultScopes = DEFAULT_SCOPES,
        note = "Supabase scopes are configured on the OAuth app, not requested per authorization.",
    )

    private val exchange = OAuthTokenExchange(
        descriptor = descriptor,
        http = http,
        clientProvider = { liveClient },
        clock = clock,
    )

    override fun scopesFor(capabilities: Set<ConnectionCapability>): Set<String> =
        capabilities.mapNotNullTo(linkedSetOf()) { SCOPE_BY_CAPABILITY[it] }

    override fun capabilitiesFor(scopes: Set<String>): Set<ConnectionCapability> {
        val normalized = scopes.mapTo(mutableSetOf()) { it.trim().lowercase() }
        if (SCOPE_ALL in normalized) return ConnectionCapabilities.SUPABASE
        return SCOPE_BY_CAPABILITY.entries
            .filter { (_, scope) -> scope.lowercase() in normalized }
            .mapTo(linkedSetOf()) { (capability, _) -> capability }
    }

    override fun authorizationUrl(request: OAuthAuthorizationRequest): String = OAuthUrl.withQuery(
        baseUrl = authorizationEndpoint,
        parameters = buildMap {
            put("client_id", request.clientId)
            put("redirect_uri", request.redirectUri)
            put("response_type", "code")
            put("state", request.state)
            request.codeChallenge?.let { put("code_challenge", it) }
            request.codeChallengeMethod?.let { put("code_challenge_method", it) }
            putAll(request.additionalParameters)
        },
    )

    override suspend fun exchange(code: String, codeVerifier: String?, redirectUri: String): OAuthTokenResult =
        exchange.exchangeCode(code, codeVerifier, redirectUri)

    override suspend fun refresh(refreshToken: String): OAuthTokenResult = exchange.refreshToken(refreshToken)

    override suspend fun validate(tokens: OAuthTokenSet): OAuthValidation {
        val response = try {
            http.execute(
                OAuthHttpRequest(
                    method = "GET",
                    url = ORGANIZATIONS_ENDPOINT,
                    headers = mapOf(
                        "Authorization" to "${tokens.tokenType} ${tokens.accessToken}",
                        "Accept" to "application/json",
                    ),
                ),
            )
        } catch (error: OAuthHttpException) {
            return OAuthValidation(
                valid = false,
                message = error.message ?: "Supabase could not be reached.",
            )
        }

        if (!response.isSuccess) {
            return OAuthValidation(
                valid = false,
                message = "Supabase rejected the new credentials (HTTP ${response.statusCode}).",
            )
        }

        val organization = OAuthJson.parse(response.body).firstElement()
        val label = organization.string("name") ?: organization.string("slug")
        return OAuthValidation(
            valid = true,
            accountLabel = label,
            message = if (label != null) "Authorized for $label" else "Credentials verified",
        )
    }

    override suspend fun revoke(tokens: OAuthTokenSet): OAuthRevocationResult = OAuthRevocationResult(
        supported = false,
        revoked = false,
        message = "Supabase does not expose a client-side revocation endpoint, so access was forgotten " +
            "locally. Revoke the app in your Supabase organization settings to remove it remotely.",
    )

    companion object {
        const val AUTHORIZATION_ENDPOINT: String = "https://api.supabase.com/v1/oauth/authorize"
        const val TOKEN_ENDPOINT: String = "https://api.supabase.com/v1/oauth/token"
        const val ORGANIZATIONS_ENDPOINT: String = "https://api.supabase.com/v1/organizations"

        const val SCOPE_ALL: String = "all"

        /** Scope per capability, using Supabase's `resource:access` names. */
        val SCOPE_BY_CAPABILITY: Map<ConnectionCapability, String> = mapOf(
            ConnectionCapabilities.PROJECT_METADATA to "projects:read",
            ConnectionCapabilities.DATABASE_READ to "database:read",
            ConnectionCapabilities.DATABASE_WRITE to "database:write",
            ConnectionCapabilities.STORAGE to "storage:read",
        )

        val DEFAULT_SCOPES: Set<String> = SCOPE_BY_CAPABILITY.values.toSet()
    }
}
