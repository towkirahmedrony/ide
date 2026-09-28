package com.agentx.app.integrations.oauth

import com.agentx.app.core.ForgeErrorCode
import com.agentx.app.core.errorOrNull
import com.agentx.app.core.valueOrNull
import com.agentx.app.integrations.connection.Connection
import com.agentx.app.integrations.connection.ConnectionAuthMethod
import com.agentx.app.integrations.connection.ConnectionCapabilities
import com.agentx.app.integrations.connection.ConnectionCapability
import com.agentx.app.integrations.connection.ConnectionConfig
import com.agentx.app.integrations.connection.ConnectionId
import com.agentx.app.integrations.connection.ConnectionType
import kotlinx.coroutines.runBlocking
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Protocol-level tests for the OAuth layer.
 *
 * Nothing here touches the network or a real provider: the flow runner is driven
 * by a fake [OAuthProvider], so these tests describe the app's own behaviour —
 * entropy and shape of `state`/PKCE values, single-use callbacks, expiry, and the
 * refusal to start an authorization the build is not configured for.
 */
class OAuthProtocolTest {

    private var now = 1_000_000L
    private val clock = { now }

    // --- State and PKCE -----------------------------------------------------

    @Test
    fun `state values are unguessable and never repeat`() {
        val states = List(200) { OAuthPkce.createState() }

        assertEquals(200, states.toSet().size, "every state value must be unique")
        states.forEach { value ->
            assertEquals(43, value.length, "32 random bytes are 43 base64url characters")
            assertTrue(BASE64URL.matches(value), "state must be url-safe base64 without padding: $value")
        }
    }

    @Test
    fun `the code verifier is rfc 7636 shaped and the challenge is its s256 digest`() {
        val verifier = OAuthPkce.createVerifier()

        assertEquals(43, verifier.length)
        assertTrue(BASE64URL.matches(verifier), "the verifier must be url-safe: $verifier")
        assertEquals(OAuthPkce.METHOD_S256, "S256")

        // RFC 7636 appendix B: the worked example, so the digest is provably
        // SHA-256 over the ASCII verifier and base64url encoded without padding.
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            OAuthPkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun `two authorizations never share a verifier or a state`() {
        val first = OAuthPkce.createVerifier()
        val second = OAuthPkce.createVerifier()
        assertFalse(first == second, "a verifier must not be reused across attempts")
        assertFalse(OAuthPkce.challenge(first) == OAuthPkce.challenge(second))
    }

    // --- Callback parsing ---------------------------------------------------

    @Test
    fun `an authorization response is read from the query the fragment and an error`() {
        val query = OAuthCallback.parse("agentx://oauth/github?code=abc123&state=st-1")
        assertTrue(query.hasCode)
        assertEquals("abc123", query.code)
        assertEquals("st-1", query.state)
        assertFalse(query.isError)

        val fragment = OAuthCallback.parse("agentx://oauth/github#code=abc123&state=st-2")
        assertTrue(fragment.hasCode)
        assertEquals("st-2", fragment.state)

        val denied = OAuthCallback.parse(
            "agentx://oauth/github?error=access_denied&error_description=user%20refused&state=st-3",
        )
        assertTrue(denied.isError)
        assertTrue(denied.isDenied)
        assertEquals("user refused", denied.errorDescription)
        assertEquals("st-3", denied.state)

        val empty = OAuthCallback.parse("agentx://oauth/github")
        assertFalse(empty.hasCode)
        assertFalse(empty.isError)
        assertNull(empty.state)
    }

    @Test
    fun `a callback never prints the code it carries`() {
        val callback = OAuthCallback.parse("agentx://oauth/github?code=secret-code&state=secret-state")
        val rendered = callback.toString()

        assertFalse(rendered.contains("secret-code"))
        assertFalse(rendered.contains("secret-state"))
    }

    // --- Pending authorizations --------------------------------------------

    @Test
    fun `a state value is single use and an unknown value is never matched`() {
        val store = InMemoryOAuthSessionStore(clock)
        store.save(pending(state = "st-1", expiresAtMillis = now + 60_000))

        assertIs<OAuthSessionLookup.Consumed>(store.consume("st-1"))
        // The second delivery of the same value is reported as a replay, so it can
        // never trigger a second token exchange.
        assertIs<OAuthSessionLookup.AlreadyHandled>(store.consume("st-1"))
        // A value this app never issued stays unrecognised.
        assertIs<OAuthSessionLookup.Unknown>(store.consume("st-stranger"))
        assertTrue(store.pending().isEmpty())
    }

    @Test
    fun `an expired authorization is refused as expired`() {
        val store = InMemoryOAuthSessionStore(clock)
        store.save(pending(state = "st-old", expiresAtMillis = now))

        assertIs<OAuthSessionLookup.Expired>(store.consume("st-old"))
    }

    @Test
    fun `cancelling drops the pending authorization for that connection`() {
        val store = InMemoryOAuthSessionStore(clock)
        store.save(pending(state = "st-1", expiresAtMillis = now + 60_000))
        assertEquals(CONNECTION_ID, assertNotNull(store.pendingFor(CONNECTION_ID)).connectionId)

        store.cancel(CONNECTION_ID)

        assertNull(store.pendingFor(CONNECTION_ID))
        assertTrue(store.pending().isEmpty())
    }

    // --- Flow runner --------------------------------------------------------

    @Test
    fun `an unconfigured provider refuses to start authorization`() {
        val provider = TestOAuthProvider(
            client = OAuthClientConfig(clientId = "", redirectUri = "agentx://oauth/github"),
        )
        val result = runner(provider).begin(provider, connection())

        val error = assertNotNull(result.errorOrNull())
        assertEquals(ForgeErrorCode.CONNECTION_OAUTH_UNAVAILABLE, error.code)
        assertEquals("PROVIDER_NOT_CONFIGURED", error.details["reason"])
        assertNull(result.valueOrNull())
    }

    @Test
    fun `the authorization url carries state and pkce and never a client secret`() {
        val provider = runnerProvider()
        val flow = runner(provider)
        val plan = assertNotNull(flow.begin(provider, connection()).valueOrNull())
        val parameters = OAuthQueryParameters.parse(plan.authorizationUrl)

        assertEquals("test-client-id", parameters["client_id"])
        assertEquals("agentx://oauth/github", parameters["redirect_uri"])
        assertEquals(plan.state, parameters["state"])
        assertEquals(OAuthPkce.METHOD_S256, parameters["code_challenge_method"])
        assertEquals(setOf(GitHubOAuthProvider.SCOPE_REPO), plan.scopes)
        assertFalse(plan.authorizationUrl.contains("client_secret"))
        assertFalse(plan.toString().contains(plan.state), "diagnostics never print the state")

        val pending = assertNotNull(flow.pendingFor(connection().id))
        val verifier = assertNotNull(pending.codeVerifier)
        assertEquals(parameters["code_challenge"], OAuthPkce.challenge(verifier))
        // The verifier and the state stay private to the pending attempt.
        assertFalse(pending.toString().contains(verifier))
        assertFalse(pending.toString().contains(plan.state))
    }

    @Test
    fun `an expired state cannot be exchanged`() = runBlocking {
        val provider = runnerProvider()
        val flow = runner(provider)
        val plan = assertNotNull(flow.begin(provider, connection()).valueOrNull())

        now += OAuthFlowRunner.DEFAULT_AUTHORIZATION_TTL_MILLIS

        val result = flow.complete(callback(plan.state))
        assertEquals("STATE_EXPIRED", assertNotNull(result.errorOrNull()).details["reason"])
        assertEquals(0, provider.exchangeCount, "an expired attempt never reaches the provider")
    }

    @Test
    fun `a callback without a state is refused without exchanging anything`() = runBlocking {
        val provider = runnerProvider()
        val flow = runner(provider)
        assertNotNull(flow.begin(provider, connection()).valueOrNull())

        val result = flow.complete("agentx://oauth/github?code=a-code")

        assertEquals("STATE_MISMATCH", assertNotNull(result.errorOrNull()).details["reason"])
        assertEquals(0, provider.exchangeCount)
    }

    @Test
    fun `a replayed callback is refused and exchanged once`() = runBlocking {
        val provider = runnerProvider()
        val flow = runner(provider)
        val plan = assertNotNull(flow.begin(provider, connection()).valueOrNull())
        val url = callback(plan.state)

        val first = assertNotNull(flow.complete(url).valueOrNull())
        assertEquals(setOf(GitHubOAuthProvider.SCOPE_REPO), first.tokens.scopes)
        assertEquals("@octocat", first.validation.accountLabel)

        val second = flow.complete(url)
        assertEquals("STATE_REPLAY", assertNotNull(second.errorOrNull()).details["reason"])
        assertEquals(1, provider.exchangeCount)
    }

    @Test
    fun `a denial from the provider is reported and never produces a grant`() = runBlocking {
        val provider = runnerProvider()
        val flow = runner(provider)
        val plan = assertNotNull(flow.begin(provider, connection()).valueOrNull())

        val result = flow.complete(
            "agentx://oauth/github?error=access_denied&error_description=user%20refused&state=${plan.state}",
        )

        val error = assertNotNull(result.errorOrNull())
        assertEquals(ForgeErrorCode.CONNECTION_OAUTH_DENIED, error.code)
        assertEquals("AUTHORIZATION_DENIED", error.details["reason"])
        assertEquals(0, provider.exchangeCount)
        assertNull(flow.pendingFor(connection().id))
    }

    // --- Credential handling ------------------------------------------------

    @Test
    fun `an encoded grant round-trips and never renders a token`() {
        val tokens = OAuthTokenSet(
            accessToken = "gho-secret-access",
            refreshToken = "ghr-secret-refresh",
            scopes = setOf("repo", "read:user"),
            obtainedAtMillis = now,
            expiresAtMillis = now + 3_600_000,
            accountLabel = "@octocat",
        )
        val encoded = OAuthTokenCodec.encode(tokens)

        assertTrue(OAuthTokenCodec.isOAuthGrant(encoded))
        val decoded = assertNotNull(OAuthTokenCodec.decode(encoded))
        assertEquals("gho-secret-access", decoded.accessToken)
        assertEquals("ghr-secret-refresh", decoded.refreshToken)
        assertEquals(setOf("repo", "read:user"), decoded.scopes)
        assertEquals("@octocat", decoded.accountLabel)
        assertEquals(now + 3_600_000, decoded.expiresAtMillis)

        // The type never renders a token, so it is also safe to appear in a log or
        // a diagnostics screen.
        val rendered = decoded.toString()
        assertFalse(rendered.contains("gho-secret-access"))
        assertFalse(rendered.contains("ghr-secret-refresh"))

        assertNull(OAuthTokenCodec.decode("not-a-grant"), "a foreign payload degrades to no grant")
        assertNull(OAuthTokenCodec.decode(""))
    }

    @Test
    fun `the exchange strategy follows the configured broker`() {
        val direct = OAuthClientConfig(clientId = "test-client-id", redirectUri = "agentx://oauth/github")
        assertEquals(OAuthExchangeStrategy.DIRECT_TOKEN_ENDPOINT, direct.exchangeStrategy)
        assertTrue(direct.isConfigured)

        val brokered = direct.copy(exchangeBrokerUrl = "https://broker.example.com/exchange")
        assertEquals(OAuthExchangeStrategy.EXCHANGE_BROKER, brokered.exchangeStrategy)

        val incomplete = OAuthClientConfig(clientId = "", redirectUri = "agentx://oauth/github")
        assertFalse(incomplete.isConfigured)
        assertNotNull(incomplete.configurationProblem)
    }

    // --- Helpers ------------------------------------------------------------

    private fun runnerProvider(): TestOAuthProvider = TestOAuthProvider(
        client = OAuthClientConfig(
            clientId = "test-client-id",
            redirectUri = "agentx://oauth/github",
            exchangeBrokerUrl = "https://broker.test/exchange",
        ),
    )

    private fun runner(provider: OAuthProvider): OAuthFlowRunner = OAuthFlowRunner(
        sessions = InMemoryOAuthSessionStore(clock),
        clock = clock,
        random = SecureRandom(),
        providerFor = { provider },
    )

    private fun callback(state: String): String =
        OAuthUrl.withQuery("agentx://oauth/github", mapOf("code" to "the-code", "state" to state))

    private fun connection(): Connection = Connection(
        id = CONNECTION_ID,
        displayName = "Personal GitHub",
        type = ConnectionType.GITHUB,
        config = ConnectionConfig(authMethod = ConnectionAuthMethod.OAUTH),
        capabilities = ConnectionCapabilities.GITHUB,
    )

    private fun pending(state: String, expiresAtMillis: Long): OAuthPendingAuthorization =
        OAuthPendingAuthorization(
            connectionId = CONNECTION_ID,
            type = ConnectionType.GITHUB,
            state = state,
            codeVerifier = "a-verifier",
            codeChallenge = "a-challenge",
            redirectUri = "agentx://oauth/github",
            scopes = setOf(GitHubOAuthProvider.SCOPE_REPO),
            requestedCapabilities = setOf(ConnectionCapabilities.REPOSITORY_READ),
            createdAtMillis = now,
            expiresAtMillis = expiresAtMillis,
        )
}

private val CONNECTION_ID = ConnectionId("c-test")

private val BASE64URL = Regex("[A-Za-z0-9_-]+")

/**
 * A GitHub-shaped provider with no network access.
 *
 * It implements the provider port only; the token endpoint is replaced by an
 * in-memory result, so the flow runner can be tested without a real account.
 */
private class TestOAuthProvider(
    override val client: OAuthClientConfig,
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

    var exchangeCount = 0
        private set

    override fun scopesFor(capabilities: Set<ConnectionCapability>): Set<String> =
        setOf(GitHubOAuthProvider.SCOPE_REPO)

    override fun capabilitiesFor(scopes: Set<String>): Set<ConnectionCapability> =
        ConnectionCapabilities.GITHUB

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
        return OAuthTokenResult.Success(
            OAuthTokenSet(
                accessToken = "fake-access-token",
                scopes = setOf(GitHubOAuthProvider.SCOPE_REPO),
                obtainedAtMillis = 1_000_000L,
            ),
        )
    }

    override suspend fun refresh(refreshToken: String): OAuthTokenResult =
        OAuthTokenResult.Success(OAuthTokenSet(accessToken = "refreshed-access-token"))

    override suspend fun validate(tokens: OAuthTokenSet): OAuthValidation =
        OAuthValidation(valid = true, accountLabel = "@octocat", message = "Authorized as @octocat")

    override suspend fun revoke(tokens: OAuthTokenSet): OAuthRevocationResult =
        OAuthRevocationResult(supported = false, revoked = false, message = "GitHub keeps the grant")
}
