package com.agentx.app.integrations.providers

import com.agentx.app.core.valueOrNull
import com.agentx.app.integrations.connection.AuthorizationStart
import com.agentx.app.integrations.connection.Connection
import com.agentx.app.integrations.connection.ConnectionAuthMethod
import com.agentx.app.integrations.connection.ConnectionCapabilities
import com.agentx.app.integrations.connection.ConnectionConfig
import com.agentx.app.integrations.connection.ConnectionDraft
import com.agentx.app.integrations.connection.ConnectionCredentialGateway
import com.agentx.app.integrations.connection.ConnectionId
import com.agentx.app.integrations.ConnectionManagers
import com.agentx.app.integrations.connection.ConnectionStatus
import com.agentx.app.integrations.connection.ConnectionType
import com.agentx.app.integrations.connection.InMemoryConnectionSecretStore
import com.agentx.app.integrations.connection.InMemoryConnectionStore
import com.agentx.app.integrations.oauth.InMemoryOAuthSessionStore
import com.agentx.app.integrations.oauth.OAuthAuthorizationRequest
import com.agentx.app.integrations.oauth.OAuthClientConfig
import com.agentx.app.integrations.oauth.OAuthFailureReason
import com.agentx.app.integrations.oauth.OAuthFlowRunner
import com.agentx.app.integrations.oauth.OAuthProvider
import com.agentx.app.integrations.oauth.OAuthProviderDescriptor
import com.agentx.app.integrations.oauth.OAuthQueryParameters
import com.agentx.app.integrations.oauth.OAuthRevocationResult
import com.agentx.app.integrations.oauth.OAuthTokenResult
import com.agentx.app.integrations.oauth.OAuthTokenSet
import com.agentx.app.integrations.oauth.OAuthUrl
import com.agentx.app.integrations.oauth.OAuthValidation
import com.agentx.app.integrations.oauth.GitHubOAuthProvider
import com.agentx.app.integrations.oauth.OAuthFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end tests for the connection flow, driven by a fake provider.
 *
 * No network and no real GitHub/Supabase account is involved: the fake implements
 * the provider port, so these tests exercise the app's own logic — state and PKCE
 * handling, token storage, capability mapping, tool enablement, expiry and
 * disconnect — rather than a provider's behaviour.
 */
class ConnectionFlowTest {

    private val accessToken = "fake-access-token-1"
    private val refreshToken = "fake-refresh-token-1"
    private val clock = { 1_000_000L }

    // --- Authorization start ------------------------------------------------

    @Test
    fun `connect opens the provider page and does not mark anything connected`() = runBlocking {
        val harness = harness()
        harness.connect()

        val url = harness.lastAuthorizationUrl
        assertTrue(url.startsWith("https://github.test/authorize"), "the official page is opened: $url")

        val parameters = OAuthQueryParameters.parse(url)
        assertEquals(harness.provider.client.clientId, parameters["client_id"])
        assertEquals(harness.provider.client.redirectUri, parameters["redirect_uri"])
        assertEquals("S256", parameters["code_challenge_method"])
        assertNotNull(parameters["code_challenge"], "PKCE challenge is sent")
        assertNotNull(parameters["state"], "a state value is generated")

        val connection = harness.connection()
        assertEquals(ConnectionStatus.AUTHORIZING, connection.status)
        assertNull(connection.credentialRef, "no credential exists before the callback")
        assertFalse(connection.isConnected)
    }

    // --- Callback handling --------------------------------------------------

    @Test
    fun `successful callback stores the grant, verifies the account and connects`() = runBlocking {
        val harness = harness()
        val connection = harness.completeAuthorization()

        assertEquals(ConnectionStatus.CONNECTED, connection.status)
        assertEquals("@octocat", connection.accountLabel)
        assertEquals(ConnectionAuthMethod.OAUTH, connection.config.authMethod)
        assertNotNull(connection.credentialRef)
        assertEquals(setOf(GitHubOAuthProvider.SCOPE_REPO), connection.grantedScopes)
        assertTrue(connection.lastTestedAtMillis != null, "the account was verified with the provider")

        // The token lives in the secret store only.
        val stored = harness.storedCredential()
        assertNotNull(stored)
        assertTrue(stored.contains(accessToken))
        assertFalse(connection.toString().contains(accessToken), "records never carry the token")
        assertFalse(
            harness.manager.state.value.connections.joinToString().contains(accessToken),
            "manager state never carries the token",
        )
    }

    @Test
    fun `the pkce verifier is replayed at exchange time`() = runBlocking {
        val harness = harness()
        harness.completeAuthorization()

        val verifier = harness.provider.lastVerifier
        assertNotNull(verifier, "the exchange sent a verifier")
        val challenge = OAuthQueryParameters.parse(harness.lastAuthorizationUrl)["code_challenge"]
        assertEquals(challenge, com.agentx.app.integrations.oauth.OAuthPkce.challenge(verifier))
    }

    @Test
    fun `a state that was never issued is refused and changes nothing`() = runBlocking {
        val harness = harness()
        harness.begin()
        val result = harness.manager.completeAuthorization("agentx://oauth/callback?code=abc&state=forged")

        val error = result.failureOrNull()
        assertNotNull(error)
        assertEquals(
            "STATE_MISMATCH",
            error.details["reason"],
            "the callback did not match a pending authorization",
        )
        assertEquals(ConnectionStatus.AUTHORIZING, harness.connection().status)
        assertNull(harness.connection().credentialRef)
    }

    @Test
    fun `a replayed callback cannot connect twice`() = runBlocking {
        val harness = harness()
        val callback = harness.beginAndBuildCallback()
        val first = harness.manager.completeAuthorization(callback)
        assertEquals(ConnectionStatus.CONNECTED, first.valueOrNull()?.status)

        // The second delivery is refused — either because the provider's state value
        // was already consumed, or because nothing is waiting any more — and it never
        // reaches a second exchange.
        val second = harness.manager.completeAuthorization(callback)
        assertNotNull(second.failureOrNull(), "a replayed callback is refused")
        assertEquals(ConnectionStatus.CONNECTED, harness.connection().status)
        assertEquals(1, harness.provider.exchangeCount, "the code was exchanged exactly once")
    }

    @Test
    fun `a denied authorization leaves the connection disconnected`() = runBlocking {
        val harness = harness()
        val state = harness.begin()
        val result = harness.manager.completeAuthorization(
            OAuthUrl.withQuery(
                "agentx://oauth/callback",
                mapOf("error" to "access_denied", "state" to state),
            ),
        )

        val failure = result.failureOrNull()
        val message = failure?.message.orEmpty()
        assertTrue(message.contains("denied", ignoreCase = true), message)
        val connection = harness.connection()
        assertEquals(ConnectionStatus.NOT_CONNECTED, connection.status)
        assertNull(connection.credentialRef, "a refusal stores nothing")
    }

    @Test
    fun `a failing exchange reports an error instead of connecting`() = runBlocking {
        val harness = harness(exchangeSucceeds = false)
        val state = harness.begin()
        val result = harness.manager.completeAuthorization(harness.callback(state))

        assertNotNull(result.failureOrNull())
        assertNull(harness.storedCredential())
        assertEquals(ConnectionStatus.ERROR, harness.connection().status)
    }

    @Test
    fun `credentials that the provider rejects never connect`() = runBlocking {
        val harness = harness(validationSucceeds = false)
        val state = harness.begin()
        val result = harness.manager.completeAuthorization(harness.callback(state))

        assertNotNull(result.failureOrNull())
        assertEquals(ConnectionStatus.ERROR, harness.connection().status)
        assertNull(harness.storedCredential(), "unverified credentials are not kept")
    }

    // --- Capabilities and tools --------------------------------------------

    @Test
    fun `capabilities follow the granted scopes and never exceed them`() = runBlocking {
        val harness = harness(scopes = setOf(GitHubOAuthProvider.SCOPE_PUBLIC_REPO))
        // Only reading is declared, so only the public scope is requested.
        harness.connect(capabilities = setOf(ConnectionCapabilities.REPOSITORY_READ))
        val connection = harness.completeAuthorization()

        assertEquals(setOf(ConnectionCapabilities.REPOSITORY_READ), connection.capabilities)
        assertTrue(harness.manager.authorize(ConnectionType.GITHUB, ConnectionCapabilities.REPOSITORY_READ).valueOrNull() != null)
        val refusal = harness.manager.authorize(ConnectionType.GITHUB, ConnectionCapabilities.REPOSITORY_WRITE)
        assertEquals(
            "MISSING_CAPABILITY",
            refusal.failureOrNull()?.failure?.name,
        )
    }

    @Test
    fun `tool availability follows the connection and the granted capability`() = runBlocking {
        val harness = harness(scopes = setOf(GitHubOAuthProvider.SCOPE_PUBLIC_REPO))

        // Nothing is connected yet: every tool says so.
        assertTrue(
            harness.manager.tools().all { !it.enabled && it.reason?.contains("Connect GitHub") == true },
            "unconnected providers offer nothing to the agent",
        )

        harness.connect(capabilities = setOf(ConnectionCapabilities.REPOSITORY_READ))
        val connection = harness.completeAuthorization()

        val tools = harness.manager.tools().associateBy { it.toolName }
        val read = tools.getValue("github.get_repository")
        val write = tools.getValue("github.create_commit")

        // The read tool passed the connection and capability gates; it is still not
        // enabled, because its service call is not written in this build.
        assertTrue(
            read.reason?.contains("not enabled in this build") == true,
            "a tool whose call is missing never reports enabled: ${read.reason}",
        )
        assertNull(read.connectionId?.let { null }, "sanity")

        // A write capability the account did not grant is refused for that reason.
        assertTrue(
            write.reason?.contains("does not grant") == true,
            "a write tool is refused on capability grounds: ${write.reason}",
        )
        assertFalse(write.enabled, "connecting never enables a write tool implicitly")

        // No tool is ever advertised as runnable while its call is missing.
        assertTrue(harness.manager.enabledToolNames().isEmpty())

        harness.manager.disconnect(connection.id)
        assertTrue(
            harness.manager.tools().all { !it.enabled },
            "disconnect leaves nothing enabled",
        )
    }

    @Test
    fun `authorizing an unknown service reports a missing connection`() = runBlocking {
        val harness = harness()
        val result = harness.manager.authorize(ConnectionType.SUPABASE, ConnectionCapabilities.DATABASE_READ)
        assertEquals("NOT_FOUND", result.failureOrNull()?.failure?.name)
    }

    // --- Expiry, refresh and disconnect -------------------------------------

    @Test
    fun `an expired grant that cannot be refreshed becomes expired and is not handed out`() = runBlocking {
        val harness = harness(refreshable = false, expiresInSeconds = 10)
        harness.completeAuthorization()

        val connection = harness.connection()
        assertEquals(ConnectionStatus.CONNECTED, connection.status)

        val lent = harness.credentials.withCredential(connection.id) { token -> token }
        assertNotNull(lent.failureOrNull(), "an unusable grant is never lent out")
        assertEquals(ConnectionStatus.EXPIRED, harness.connection().status)
        assertEquals(
            "EXPIRED",
            harness.manager.authorize(ConnectionType.GITHUB, ConnectionCapabilities.REPOSITORY_READ)
                .failureOrNull()?.failure?.name,
        )
    }

    @Test
    fun `an expired grant with a refresh token is refreshed with the provider`() = runBlocking {
        val harness = harness(refreshable = true, expiresInSeconds = 10)
        harness.completeAuthorization()

        val lent = harness.credentials.withCredential(harness.connection().id) { token -> token }
        assertEquals("refreshed-access-token", lent.valueOrNull(), "the call sees the refreshed token")
        assertTrue(harness.provider.refreshCount == 1)

        val stored = harness.storedCredential()
        assertNotNull(stored)
        assertTrue(stored.contains("refreshed-access-token"), "the refreshed grant replaced the old one")
        assertFalse(stored.contains(accessToken), "the superseded token is gone")
    }

    @Test
    fun `disconnect deletes the credential and clears the grant`() = runBlocking {
        val harness = harness()
        val connection = harness.completeAuthorization()
        val disconnected = harness.manager.disconnect(connection.id).valueOrNull()

        assertNotNull(disconnected)
        assertEquals(ConnectionStatus.DISCONNECTED, disconnected.status)
        assertNull(disconnected.credentialRef)
        assertTrue(disconnected.grantedScopes.isEmpty())
        assertNull(disconnected.accountLabel)
        assertNull(harness.storedCredential(), "no stale credential survives a disconnect")
        assertNull(harness.manager.authorize(ConnectionType.GITHUB, ConnectionCapabilities.REPOSITORY_READ).valueOrNull())
    }

    // --- Credential isolation ----------------------------------------------

    @Test
    fun `a service client receives the credential but no agent-facing surface does`() = runBlocking {
        val harness = harness()
        val connection = harness.completeAuthorization()

        val seen = harness.credentials.withCredential(connection.id) { token -> token }
        assertEquals(accessToken, seen.valueOrNull())

        val authorized = harness.manager
            .authorize(ConnectionType.GITHUB, ConnectionCapabilities.REPOSITORY_READ)
            .valueOrNull()
        assertNotNull(authorized)
        assertFalse(authorized.toString().contains(accessToken))
        assertEquals("connections.secret.${connection.id.value}", authorized.credentialRef)

        val surfaces = listOf(
            authorized.toString(),
            connection.toString(),
            harness.manager.tools().joinToString(),
            harness.manager.providerDescriptors().joinToString(),
            harness.manager.state.value.connections.joinToString(),
        )
        surfaces.forEach { surface ->
            assertFalse(surface.contains(accessToken), "no surface leaks the token: $surface")
        }
    }

    @Test
    fun `a manual credential is verified with the provider before it is stored`() = runBlocking {
        val harness = harness()
        val stored = harness.manager.addConnection(
            ConnectionDraft(
                displayName = "Personal GitHub",
                type = ConnectionType.GITHUB,
                config = ConnectionConfig(authMethod = ConnectionAuthMethod.ACCESS_TOKEN),
                capabilities = ConnectionCapabilities.GITHUB,
                credential = accessToken,
            ),
        ).valueOrNull()

        assertNotNull(stored)
        assertEquals(ConnectionStatus.CONNECTED, stored.status)
        assertEquals("@octocat", stored.accountLabel)
        assertEquals(1, harness.provider.manualVerifications)
    }

    @Test
    fun `a manual credential the provider rejects is reported as an error`() = runBlocking {
        val harness = harness(validationSucceeds = false)
        val stored = harness.manager.addConnection(
            ConnectionDraft(
                displayName = "Personal GitHub",
                type = ConnectionType.GITHUB,
                config = ConnectionConfig(authMethod = ConnectionAuthMethod.ACCESS_TOKEN),
                capabilities = ConnectionCapabilities.GITHUB,
                credential = "wrong-token",
            ),
        ).valueOrNull()

        assertNotNull(stored)
        assertEquals(ConnectionStatus.ERROR, stored.status)
    }

    // --- Harness ------------------------------------------------------------

    private fun harness(
        scopes: Set<String> = setOf(GitHubOAuthProvider.SCOPE_REPO),
        exchangeSucceeds: Boolean = true,
        validationSucceeds: Boolean = true,
        refreshable: Boolean = false,
        expiresInSeconds: Long? = null,
    ): Harness {
        val provider = FakeOAuthProvider(
            scopes = scopes,
            accessToken = accessToken,
            refreshToken = if (refreshable) refreshToken else null,
            exchangeSucceeds = exchangeSucceeds,
            validationSucceeds = validationSucceeds,
            expiresInSeconds = expiresInSeconds,
            clock = clock,
        )
        val sessions = InMemoryOAuthSessionStore(clock)
        val flow = OAuthFlowRunner(
            sessions = sessions,
            clock = clock,
            random = SecureRandom(),
            providerFor = { type -> provider.takeIf { it.descriptor.type == type } },
        )
        val secretStore = InMemoryConnectionSecretStore()
        val manager = ConnectionManagers.create(
            store = InMemoryConnectionStore(),
            secrets = secretStore,
            clock = clock,
            idFactory = { CONNECTION_ID },
            ioDispatcher = Dispatchers.Unconfined,
            providers = ConnectionProviders.registryOf(GitHubConnectionProvider(provider, flow, clock)),
        )
        return Harness(
            manager = manager,
            credentials = manager as ConnectionCredentialGateway,
            provider = provider,
            secrets = secretStore,
            clock = clock,
        )
    }

    private class Harness(
        val manager: com.agentx.app.integrations.connection.ConnectionManager,
        val credentials: ConnectionCredentialGateway,
        val provider: FakeOAuthProvider,
        val secrets: InMemoryConnectionSecretStore,
        val clock: () -> Long,
    ) {
        var lastAuthorizationUrl: String = ""

        /** The credential the manager would have stored, if any. */
        suspend fun storedCredential(): String? = secrets.get("connections.secret.$CONNECTION_ID")

        /** Records the connection with [capabilities], then starts the flow. */
        suspend fun connect(
            capabilities: Set<com.agentx.app.integrations.connection.ConnectionCapability> = ConnectionCapabilities.GITHUB,
        ) {
            if (manager.state.value.connections.none { it.type == ConnectionType.GITHUB }) {
                val created = manager.addConnection(
                    ConnectionDraft(
                        displayName = "GitHub",
                        type = ConnectionType.GITHUB,
                        id = ConnectionId(CONNECTION_ID),
                        config = ConnectionConfig(authMethod = ConnectionAuthMethod.OAUTH),
                        capabilities = capabilities,
                    ),
                )
                assertNotNull(created.valueOrNull(), "the connection record should be created")
            }
            val start = manager.connect(ConnectionType.GITHUB).valueOrNull()
            assertNotNull(start, "the authorization should start")
            assertTrue(start is AuthorizationStart.OpenUrl, "GitHub has a hosted authorization page")
            lastAuthorizationUrl = (start as AuthorizationStart.OpenUrl).authorizationUrl
        }

        /** Starts an authorization and returns the state the provider would send back. */
        suspend fun begin(): String {
            connect()
            return OAuthQueryParameters.parse(lastAuthorizationUrl)["state"] ?: error("no state")
        }

        suspend fun beginAndBuildCallback(): String = callback(begin())

        suspend fun callback(state: String): String =
            OAuthUrl.withQuery("agentx://oauth/callback", mapOf("code" to "fake-code", "state" to state))

        suspend fun completeAuthorization(): Connection {
            val state = begin()
            val result = manager.completeAuthorization(callback(state))
            val connection = result.valueOrNull()
            assertNotNull(connection, "authorization should complete: ${result.failureOrNull()?.message}")
            return connection
        }

        fun connection(): Connection =
            manager.state.value.connections.firstOrNull { it.type == ConnectionType.GITHUB }
                ?: error("no GitHub connection")
    }
}

private const val CONNECTION_ID = "c-fixed"

/**
 * Reads the failure of any typed result. Authorizing a connection fails with a
 * [com.agentx.app.integrations.connection.ConnectionAuthorizationError] rather than a
 * [com.agentx.app.core.ForgeError], so the reader stays generic.
 */
private fun <T, E> com.agentx.app.core.ForgeResult<T, E>.failureOrNull(): E? =
    (this as? com.agentx.app.core.ForgeResult.Failure)?.error

/**
 * A fake GitHub-shaped OAuth provider.
 *
 * It implements the provider port only: authorization URL building and the token
 * endpoints are replaced by in-memory behaviour, so the test suite never touches
 * the network and never needs a real account.
 */
private class FakeOAuthProvider(
    private val scopes: Set<String>,
    private val accessToken: String,
    private val refreshToken: String?,
    private val exchangeSucceeds: Boolean,
    private val validationSucceeds: Boolean,
    private val expiresInSeconds: Long?,
    private val clock: () -> Long,
) : OAuthProvider {

    override val descriptor = OAuthProviderDescriptor(
        type = ConnectionType.GITHUB,
        displayName = "GitHub",
        authorizationEndpoint = "https://github.test/authorize",
        tokenEndpoint = "https://github.test/token",
        supportsPkce = true,
        supportsRefresh = true,
        supportsRevocation = false,
        defaultScopes = setOf(GitHubOAuthProvider.SCOPE_REPO),
    )

    override val client = OAuthClientConfig(
        clientId = "fake-client-id",
        redirectUri = "agentx://oauth/callback",
        exchangeBrokerUrl = "https://broker.test/exchange",
    )

    var exchangeCount = 0
        private set

    var refreshCount = 0
        private set

    var manualVerifications = 0
        private set

    var lastVerifier: String? = null
        private set

    override fun scopesFor(capabilities: Set<com.agentx.app.integrations.connection.ConnectionCapability>): Set<String> {
        val write = ConnectionCapabilities.REPOSITORY_WRITE in capabilities
        val readOnly = capabilities.all { it == ConnectionCapabilities.REPOSITORY_READ }
        return setOf(if (!write && readOnly) GitHubOAuthProvider.SCOPE_PUBLIC_REPO else GitHubOAuthProvider.SCOPE_REPO)
    }

    override fun capabilitiesFor(scopes: Set<String>): Set<com.agentx.app.integrations.connection.ConnectionCapability> =
        when {
            GitHubOAuthProvider.SCOPE_REPO in scopes -> ConnectionCapabilities.GITHUB
            GitHubOAuthProvider.SCOPE_PUBLIC_REPO in scopes -> setOf(
                ConnectionCapabilities.REPOSITORY_READ,
                ConnectionCapabilities.PULL_REQUEST,
                ConnectionCapabilities.ISSUES,
            )
            else -> emptySet()
        }

    override fun authorizationUrl(request: OAuthAuthorizationRequest): String = OAuthUrl.withQuery(
        baseUrl = descriptor.authorizationEndpoint,
        parameters = buildMap {
            put("client_id", request.clientId)
            put("redirect_uri", request.redirectUri)
            put("state", request.state)
            request.scopes.takeIf { it.isNotEmpty() }?.let { put("scope", it.joinToString(" ")) }
            request.codeChallenge?.let { put("code_challenge", it) }
            request.codeChallengeMethod?.let { put("code_challenge_method", it) }
        },
    )

    override suspend fun exchange(code: String, codeVerifier: String?, redirectUri: String): OAuthTokenResult {
        exchangeCount++
        lastVerifier = codeVerifier
        if (!exchangeSucceeds) {
            return OAuthTokenResult.Failure(
                OAuthFailure(
                    reason = OAuthFailureReason.EXCHANGE_FAILED,
                    message = "the fake provider refused the code",
                ),
            )
        }
        return OAuthTokenResult.Success(
            OAuthTokenSet(
                accessToken = accessToken,
                refreshToken = refreshToken,
                scopes = scopes,
                obtainedAtMillis = clock(),
                expiresAtMillis = expiresInSeconds?.let { clock() + it * 1000 },
            ),
        )
    }

    override suspend fun refresh(refreshToken: String): OAuthTokenResult {
        refreshCount++
        return OAuthTokenResult.Success(
            OAuthTokenSet(
                accessToken = "refreshed-access-token",
                refreshToken = refreshToken,
                scopes = scopes,
                obtainedAtMillis = clock(),
            ),
        )
    }

    override suspend fun validate(tokens: OAuthTokenSet): OAuthValidation =
        if (validationSucceeds) {
            manualVerifications++
            OAuthValidation(valid = true, accountLabel = "@octocat", message = "Authorized as @octocat")
        } else {
            OAuthValidation(valid = false, message = "GitHub rejected the credentials")
        }

    override suspend fun revoke(tokens: OAuthTokenSet): OAuthRevocationResult =
        OAuthRevocationResult(supported = false, revoked = false, message = "GitHub keeps the grant")
}
