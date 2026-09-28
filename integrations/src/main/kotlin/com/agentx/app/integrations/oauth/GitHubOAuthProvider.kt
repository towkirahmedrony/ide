package com.agentx.app.integrations.oauth

import com.agentx.app.integrations.connection.ConnectionCapabilities
import com.agentx.app.integrations.connection.ConnectionCapability
import com.agentx.app.integrations.connection.ConnectionType

/**
 * Official GitHub authorization (OAuth 2.0 authorization code + PKCE).
 *
 * Endpoints and parameters follow the documented web application flow:
 * `GET https://github.com/login/oauth/authorize` and
 * `POST https://github.com/login/oauth/access_token`. GitHub accepts PKCE for
 * OAuth apps and only `S256`; the verifier is generated per attempt and kept in
 * memory.
 *
 * GitHub documents `client_secret` as required for the code exchange, and a
 * public Android client cannot hold one. The exchange therefore goes through the
 * configured server-side broker when one exists; without it the token endpoint
 * answers with an error, which is surfaced honestly instead of being faked.
 *
 * Access tokens issued without `offline_access` do not expire, so a connection
 * normally has nothing to refresh. If the OAuth app is configured for expiring
 * tokens, GitHub returns `expires_in` and `refresh_token`, and the refresh then
 * follows the same broker/direct strategy.
 */
class GitHubOAuthProvider(
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
        type = ConnectionType.GITHUB,
        displayName = "GitHub",
        authorizationEndpoint = authorizationEndpoint,
        tokenEndpoint = TOKEN_ENDPOINT,
        supportsPkce = true,
        // Refreshing only matters when the app issues expiring tokens; it uses the
        // same exchange strategy as the initial exchange.
        supportsRefresh = true,
        // GitHub requires client credentials to revoke an app's grant, so a public
        // client can only forget the token locally.
        supportsRevocation = false,
        defaultScopes = DEFAULT_SCOPES,
        note = "GitHub issues a non-expiring token unless the OAuth app is configured for expiring tokens.",
    )

    private val exchange = OAuthTokenExchange(
        descriptor = descriptor,
        http = http,
        clientProvider = { liveClient },
        clock = clock,
    )

    override fun scopesFor(capabilities: Set<ConnectionCapability>): Set<String> {
        if (capabilities.isEmpty()) return emptySet()
        // `repo` is the only scope that covers private repositories and the
        // issues/PR APIs; `public_repo` is the narrower choice when the connection
        // only ever reads repositories.
        val needsWrite = ConnectionCapabilities.REPOSITORY_WRITE in capabilities
        val readOnly = capabilities.all { it == ConnectionCapabilities.REPOSITORY_READ }
        return setOf(if (!needsWrite && readOnly) SCOPE_PUBLIC_REPO else SCOPE_REPO)
    }

    override fun capabilitiesFor(scopes: Set<String>): Set<ConnectionCapability> {
        val normalized = scopes.mapTo(mutableSetOf()) { it.trim().lowercase() }
        return when {
            SCOPE_REPO in normalized -> ConnectionCapabilities.GITHUB
            SCOPE_PUBLIC_REPO in normalized -> setOf(
                ConnectionCapabilities.REPOSITORY_READ,
                ConnectionCapabilities.PULL_REQUEST,
                ConnectionCapabilities.ISSUES,
            )
            // `read:user` and friends carry no repository access at all.
            else -> emptySet()
        }
    }

    override fun authorizationUrl(request: OAuthAuthorizationRequest): String = OAuthUrl.withQuery(
        baseUrl = authorizationEndpoint,
        parameters = buildMap {
            put("client_id", request.clientId)
            put("redirect_uri", request.redirectUri)
            put("state", request.state)
            request.scopes.takeIf { it.isNotEmpty() }?.let { put("scope", it.joinToString(" ")) }
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
                    url = USER_ENDPOINT,
                    headers = mapOf(
                        "Authorization" to "${tokens.tokenType} ${tokens.accessToken}",
                        "Accept" to "application/vnd.github+json",
                    ),
                ),
            )
        } catch (error: OAuthHttpException) {
            return OAuthValidation(
                valid = false,
                message = error.message ?: "GitHub could not be reached.",
            )
        }

        if (!response.isSuccess) {
            return OAuthValidation(
                valid = false,
                message = "GitHub rejected the new credentials (HTTP ${response.statusCode}).",
            )
        }

        val root = OAuthJson.parse(response.body)
        val login = root.string("login") ?: root.string("name")
        return OAuthValidation(
            valid = true,
            accountLabel = login,
            message = if (login != null) "Authorized as $login" else "Credentials verified",
        )
    }

    override suspend fun revoke(tokens: OAuthTokenSet): OAuthRevocationResult = OAuthRevocationResult(
        supported = false,
        revoked = false,
        message = "GitHub only revokes an app's grant with client credentials, so access was forgotten " +
            "locally. Remove it under GitHub → Settings → Applications to revoke it remotely.",
    )

    companion object {
        const val AUTHORIZATION_ENDPOINT: String = "https://github.com/login/oauth/authorize"
        const val TOKEN_ENDPOINT: String = "https://github.com/login/oauth/access_token"
        const val USER_ENDPOINT: String = "https://api.github.com/user"

        /** `repo` covers every declared GitHub capability. */
        val DEFAULT_SCOPES: Set<String> = setOf(SCOPE_REPO)

        const val SCOPE_REPO: String = "repo"
        const val SCOPE_PUBLIC_REPO: String = "public_repo"
    }
}
