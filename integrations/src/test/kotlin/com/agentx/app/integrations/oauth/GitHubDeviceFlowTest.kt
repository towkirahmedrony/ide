package com.agentx.app.integrations.oauth

import com.agentx.app.core.errorOrNull
import com.agentx.app.core.valueOrNull
import com.agentx.app.integrations.ConnectionManagers
import com.agentx.app.integrations.connection.Connection
import com.agentx.app.integrations.connection.ConnectionAuthMethod
import com.agentx.app.integrations.connection.ConnectionCapabilities
import com.agentx.app.integrations.connection.ConnectionCapability
import com.agentx.app.integrations.connection.ConnectionConfig
import com.agentx.app.integrations.connection.ConnectionDraft
import com.agentx.app.integrations.connection.ConnectionId
import com.agentx.app.integrations.connection.ConnectionManager
import com.agentx.app.integrations.connection.ConnectionStatus
import com.agentx.app.integrations.connection.ConnectionType
import com.agentx.app.integrations.connection.DeviceAuthorization
import com.agentx.app.integrations.connection.InMemoryConnectionSecretStore
import com.agentx.app.integrations.connection.InMemoryConnectionStore
import com.agentx.app.integrations.providers.ConnectionProviders
import com.agentx.app.integrations.providers.GitHubConnectionProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the GitHub OAuth Device Flow.
 *
 * No network and no real GitHub account is involved: a fake [OAuthHttpClient]
 * answers the protocol tests, and a fake [DeviceFlowClient] drives the runner and
 * the Connection Manager. Nothing here talks to github.com.
 */
class GitHubDeviceFlowTest {

    private val clock = { 1_000_000L }
    private val accessToken = "gho-secret-access-token"

    // --- Device-code response parsing ---------------------------------------

    @Test
    fun `a device-code response is parsed into its documented fields`() = runBlocking {
        val (client, _) = deviceClient { request ->
            assertEquals("POST", request.method)
            assertEquals(GitHubDeviceFlowClient.DEVICE_CODE_ENDPOINT, request.url)
            OAuthHttpResponse(200, DEVICE_CODE_JSON)
        }

        val result = client.requestDeviceCode(setOf("repo", "read:user"))
        val code = assertNotNull(assertIs<OAuthDeviceCodeResult.Success>(result).code)

        assertEquals("dev-code-secret", code.deviceCode)
        assertEquals("WXYZ-1234", code.userCode)
        assertEquals("https://github.com/login/device", code.verificationUri)
        assertEquals(900L, code.expiresInSeconds)
        assertEquals(5L, code.intervalSeconds)
    }

    @Test
    fun `a form-encoded device-code response is parsed too`() = runBlocking {
        val (client, _) = deviceClient {
            OAuthHttpResponse(
                statusCode = 200,
                body = "device_code=dev&user_code=ABCD-0000&verification_uri=https%3A%2F%2Fgithub.com%2Flogin%2Fdevice&expires_in=600&interval=8",
                contentType = OAuthHttpResponse.CONTENT_TYPE_FORM,
            )
        }

        val code = assertIs<OAuthDeviceCodeResult.Success>(client.requestDeviceCode(emptySet())).code
        assertEquals("ABCD-0000", code.userCode)
        assertEquals(600L, code.expiresInSeconds)
        assertEquals(8L, code.intervalSeconds)
    }

    // --- Token response parsing ---------------------------------------------

    @Test
    fun `a successful token response is parsed into a token set`() = runBlocking {
        val (client, http) = deviceClient { request ->
            assertEquals(GitHubDeviceFlowClient.TOKEN_ENDPOINT, request.url)
            assertTrue(request.body?.contains("grant_type") == true, "the device grant type is sent")
            assertNull(request.headers["Authorization"], "device flow sends no bearer token")
            OAuthHttpResponse(200, """{"access_token":"$accessToken","token_type":"bearer","scope":"repo read:user"}""")
        }

        val result = client.poll("dev-code-secret")
        val tokens = assertIs<OAuthDevicePollResult.Success>(result).tokens

        assertEquals(accessToken, tokens.accessToken)
        assertEquals("bearer", tokens.tokenType)
        assertEquals(setOf("repo", "read:user"), tokens.scopes)
        assertEquals(1, http.requests.size)
    }

    // --- Protocol error mapping ---------------------------------------------

    @Test
    fun `authorization_pending keeps the flow polling`(): Unit = runBlocking {
        val (client, _) = deviceClient { OAuthHttpResponse(200, """{"error":"authorization_pending"}""") }
        assertIs<OAuthDevicePollResult.Pending>(client.poll("dev-code-secret"))
    }

    @Test
    fun `slow_down raises the interval without failing`() = runBlocking {
        val (client, _) = deviceClient { OAuthHttpResponse(200, """{"error":"slow_down","interval":12}""") }
        val result = assertIs<OAuthDevicePollResult.SlowDown>(client.poll("dev-code-secret"))
        assertEquals(12L, result.intervalSeconds)

        // Without an echoed interval the documented five-second step is used.
        val (bare, _) = deviceClient { OAuthHttpResponse(200, """{"error":"slow_down"}""") }
        assertEquals(
            GitHubDeviceFlowClient.DEFAULT_SLOW_DOWN_INTERVAL_SECONDS,
            assertIs<OAuthDevicePollResult.SlowDown>(bare.poll("dev-code-secret")).intervalSeconds,
        )
    }

    @Test
    fun `access_denied is a terminal authorization error`() = runBlocking {
        val (client, _) = deviceClient {
            OAuthHttpResponse(200, """{"error":"access_denied","error_description":"user refused"}""")
        }
        val failure = assertIs<OAuthDevicePollResult.Failure>(client.poll("dev-code-secret"))
        assertEquals(OAuthFailureReason.AUTHORIZATION_DENIED, failure.reason)
        assertTrue(failure.message.contains("user refused"))
    }

    @Test
    fun `expired_token is a terminal expired state`() = runBlocking {
        val (client, _) = deviceClient { OAuthHttpResponse(200, """{"error":"expired_token"}""") }
        val failure = assertIs<OAuthDevicePollResult.Failure>(client.poll("dev-code-secret"))
        assertEquals(OAuthFailureReason.DEVICE_CODE_EXPIRED, failure.reason)
    }

    @Test
    fun `a malformed device-code response is refused`(): Unit = runBlocking {
        val (client, _) = deviceClient { OAuthHttpResponse(200, """{"user_code":"ABCD-1234"}""") }
        val failure = assertIs<OAuthDeviceCodeResult.Failure>(client.requestDeviceCode(emptySet()))
        assertEquals(OAuthFailureReason.AUTHORIZATION_ERROR, failure.reason)

        val (garbage, _) = deviceClient { OAuthHttpResponse(200, "not json at all") }
        assertIs<OAuthDeviceCodeResult.Failure>(garbage.requestDeviceCode(emptySet()))
    }

    @Test
    fun `a malformed token response is refused`(): Unit = runBlocking {
        val (client, _) = deviceClient { OAuthHttpResponse(200, """{"token_type":"bearer"}""") }
        assertIs<OAuthDevicePollResult.Failure>(client.poll("dev-code-secret"))

        val (empty, _) = deviceClient { OAuthHttpResponse(200, "") }
        assertIs<OAuthDevicePollResult.Failure>(empty.poll("dev-code-secret"))
    }

    @Test
    fun `a network failure is reported as a network error`() = runBlocking {
        val (client, _) = deviceClient { throw OAuthHttpException("github is unreachable") }

        val codeFailure = assertIs<OAuthDeviceCodeResult.Failure>(client.requestDeviceCode(emptySet()))
        assertEquals(OAuthFailureReason.NETWORK, codeFailure.reason)

        val pollFailure = assertIs<OAuthDevicePollResult.Failure>(client.poll("dev-code-secret"))
        assertEquals(OAuthFailureReason.NETWORK, pollFailure.reason)
    }

    @Test
    fun `http status codes are mapped to typed failures`() = runBlocking {
        val (badRequest, _) = deviceClient { OAuthHttpResponse(400, """{"error":"incorrect_device_code"}""") }
        assertEquals(
            OAuthFailureReason.AUTHORIZATION_ERROR,
            assertIs<OAuthDevicePollResult.Failure>(badRequest.poll("x")).reason,
        )

        val (serverError, _) = deviceClient { OAuthHttpResponse(503, "upstream down") }
        assertEquals(
            OAuthFailureReason.NETWORK,
            assertIs<OAuthDevicePollResult.Failure>(serverError.poll("x")).reason,
        )

        val (throttled, _) = deviceClient { OAuthHttpResponse(429, "slow down") }
        assertEquals(
            OAuthFailureReason.RATE_LIMITED,
            assertIs<OAuthDevicePollResult.Failure>(throttled.poll("x")).reason,
        )
    }

    // --- Polling loop --------------------------------------------------------

    @Test
    fun `the loop polls until it succeeds and respects the interval`() = runBlocking {
        val sleeps = mutableListOf<Long>()
        val client = FakeDeviceFlowClient(
            polls = listOf(
                OAuthDevicePollResult.Pending,
                OAuthDevicePollResult.SlowDown(7),
                OAuthDevicePollResult.Success(tokens()),
            ),
        )
        val runner = DeviceFlowRunner(client, clock = clock, sleep = { sleeps += it })
        val connection = githubConnection()
        assertNotNull(runner.begin(connection, setOf("repo")).valueOrNull())

        val result = runner.complete(connection.id) {}

        assertNotNull(result.valueOrNull())
        assertEquals(3, client.pollCount)
        // Initial interval is 5s; slow_down(7) is raised to at least 5 + 5 seconds.
        assertEquals(listOf(5_000L, 10_000L), sleeps)
        assertNull(runner.pendingFor(connection.id), "the pending attempt is cleared on success")
    }

    @Test
    fun `an expired device code ends the loop without polling again`() = runBlocking {
        val client = FakeDeviceFlowClient(polls = listOf(OAuthDevicePollResult.Pending))
        var now = 1_000_000L
        val runner = DeviceFlowRunner(client, clock = { now }, sleep = {})
        val connection = githubConnection()
        assertNotNull(runner.begin(connection, setOf("repo")).valueOrNull())

        // Advance past the device code's 900s lifetime: the attempt ends before it
        // reaches the provider again.
        now += 16 * 60 * 1000L

        val states = mutableListOf<DeviceFlowState>()
        val failure = runner.complete(connection.id) { states += it }.errorOrNull()

        assertNotNull(failure)
        assertEquals("DEVICE_CODE_EXPIRED", failure.details["reason"])
        assertEquals(0, client.pollCount, "an expired attempt never reaches the provider")
        assertEquals(DeviceFlowState.EXPIRED, states.last())
        assertNull(runner.pendingFor(connection.id))
    }

    @Test
    fun `cancelling the loop stops polling and drops the attempt`() = runBlocking {
        val client = FakeDeviceFlowClient(polls = listOf(OAuthDevicePollResult.Pending))
        val runner = DeviceFlowRunner(client, clock = clock, sleep = { delay(60_000) })
        val connection = githubConnection()
        assertNotNull(runner.begin(connection, setOf("repo")).valueOrNull())

        val job = launch { runner.complete(connection.id) {} }
        while (client.pollCount == 0) delay(5)
        job.cancelAndJoin()

        assertEquals(1, client.pollCount, "no request is made after cancellation")
        assertNull(runner.pendingFor(connection.id))
    }

    // --- Connection lifecycle through the manager ---------------------------

    @Test
    fun `a successful device authorization stores the credential and connects`() = runBlocking {
        val harness = harness()
        val authorization = harness.begin()

        val states = mutableListOf<DeviceFlowState>()
        val connection = assertNotNull(
            harness.manager.completeDeviceAuthorization(authorization.connectionId) { states += it }.valueOrNull(),
        )

        assertEquals(ConnectionStatus.CONNECTED, connection.status)
        assertEquals("@octocat", connection.accountLabel)
        assertEquals(ConnectionAuthMethod.OAUTH, connection.config.authMethod)
        assertTrue(connection.lastTestedAtMillis != null, "the account was verified with GitHub")

        // The token lives in the existing secret store only.
        val ref = assertNotNull(connection.credentialRef)
        assertTrue(assertNotNull(harness.secrets.get(ref)).contains(accessToken))
        assertEquals(DeviceFlowState.CONNECTED, states.last())
        assertTrue(states.contains(DeviceFlowState.POLLING))
    }

    @Test
    fun `reconnect reuses the existing connection id`() = runBlocking {
        val harness = harness()
        val first = harness.begin()
        assertNotNull(harness.manager.completeDeviceAuthorization(first.connectionId).valueOrNull())

        // Re-authorizing must not create a second record.
        val second = harness.begin()
        assertEquals(first.connectionId, second.connectionId)
        assertEquals(1, harness.manager.list().count { it.type == ConnectionType.GITHUB })
    }

    @Test
    fun `disconnect removes the credential without deleting unrelated data`(): Unit = runBlocking {
        val harness = harness()
        harness.manager.addConnection(
            ConnectionDraft(
                displayName = "My API",
                type = ConnectionType.CUSTOM_API,
                config = ConnectionConfig(
                    endpoint = "https://api.example.com",
                    authMethod = ConnectionAuthMethod.ACCESS_TOKEN,
                ),
                credential = "custom-secret-value",
            ),
        )
        val authorization = harness.begin()
        val connection = assertNotNull(
            harness.manager.completeDeviceAuthorization(authorization.connectionId).valueOrNull(),
        )
        val ref = assertNotNull(connection.credentialRef)

        val disconnected = assertNotNull(harness.manager.disconnect(connection.id).valueOrNull())

        assertEquals(ConnectionStatus.DISCONNECTED, disconnected.status)
        assertNull(disconnected.credentialRef)
        assertTrue(disconnected.grantedScopes.isEmpty())
        assertNull(harness.secrets.get(ref), "the stored grant is deleted")
        // Unrelated connection data is untouched.
        assertNotNull(harness.manager.list().firstOrNull { it.type == ConnectionType.CUSTOM_API })
    }

    @Test
    fun `a failed authorization leaves the connection metadata intact`() = runBlocking {
        val harness = harness(validation = false)
        val authorization = harness.begin()
        val failure = harness.manager.completeDeviceAuthorization(authorization.connectionId).errorOrNull()

        assertNotNull(failure)
        val stored = assertNotNull(harness.manager.list().firstOrNull { it.type == ConnectionType.GITHUB })
        assertEquals(authorization.connectionId, stored.id, "the connection is not deleted")
        assertFalse(stored.status == ConnectionStatus.CONNECTED)
    }

    // --- Credential isolation ------------------------------------------------

    @Test
    fun `the raw token never reaches a user-visible or domain surface`() = runBlocking {
        val harness = harness()
        val authorization = harness.begin()
        assertFalse(authorization.toString().contains(accessToken))
        assertFalse(authorization.toString().contains("dev-code-secret"))

        val connection = assertNotNull(
            harness.manager.completeDeviceAuthorization(authorization.connectionId).valueOrNull(),
        )
        val ref = assertNotNull(connection.credentialRef)
        assertTrue(assertNotNull(harness.secrets.get(ref)).contains(accessToken))

        val authorized = assertNotNull(
            harness.manager
                .authorize(ConnectionType.GITHUB, ConnectionCapabilities.REPOSITORY_READ)
                .valueOrNull(),
        )
        val surfaces = listOf(
            connection.toString(),
            authorized.toString(),
            harness.manager.state.value.connections.joinToString(),
            harness.manager.tools().joinToString(),
            harness.manager.providerDescriptors().joinToString(),
        )
        surfaces.forEach { surface ->
            assertFalse(surface.contains(accessToken), "no surface leaks the token: $surface")
        }
    }

    @Test
    fun `device models never print a credential`() {
        val code = OAuthDeviceCode("dev-code-secret", "WXYZ-1234", "https://github.com/login/device", 900, 5)
        assertFalse(code.toString().contains("dev-code-secret"))
        assertFalse(code.toString().contains("WXYZ-1234"))

        val tokens = OAuthTokenSet(accessToken = "gho-secret", refreshToken = "ghr-secret")
        assertFalse(tokens.toString().contains("gho-secret"))
        assertFalse(tokens.toString().contains("ghr-secret"))

        val authorization = DeviceAuthorization(
            connectionId = ConnectionId("c-1"),
            type = ConnectionType.GITHUB,
            displayName = "GitHub",
            userCode = "WXYZ-1234",
            verificationUri = "https://github.com/login/device",
            expiresAtMillis = 0L,
            intervalSeconds = 5,
        )
        assertFalse(authorization.toString().contains("WXYZ-1234"))
    }

    @Test
    fun `a non-device-flow provider reports the capability as unavailable`(): Unit = runBlocking {
        val harness = harness(withDeviceFlow = false)
        assertFalse(harness.manager.supportsDeviceAuthorization(ConnectionType.GITHUB))
        val failure = harness.manager.beginDeviceAuthorization(ConnectionType.GITHUB).errorOrNull()
        assertNotNull(failure)
    }

    // --- Helpers -------------------------------------------------------------

    private fun deviceClient(
        handler: (OAuthHttpRequest) -> OAuthHttpResponse,
    ): Pair<GitHubDeviceFlowClient, RecordingHttpClient> {
        val http = RecordingHttpClient(handler)
        val client = GitHubDeviceFlowClient(
            http = http,
            clientProvider = { OAuthClientConfig(clientId = "test-client-id", redirectUri = "agentx://oauth/github") },
            clock = clock,
        )
        return client to http
    }

    private fun harness(
        validation: Boolean = true,
        withDeviceFlow: Boolean = true,
    ): Harness {
        val deviceClient = FakeDeviceFlowClient()
        val oauthProvider = FakeGitHubOAuthProvider(
            client = OAuthClientConfig(clientId = "test-client-id", redirectUri = "agentx://oauth/github"),
            validationSucceeds = validation,
        )
        val flow = OAuthFlowRunner(
            sessions = InMemoryOAuthSessionStore(clock),
            clock = clock,
            providerFor = { oauthProvider },
        )
        val provider = GitHubConnectionProvider(
            oauthProvider = oauthProvider,
            flow = flow,
            clock = clock,
            deviceFlow = DeviceFlowRunner(deviceClient, clock = clock, sleep = {}).takeIf { withDeviceFlow },
        )
        val secrets = InMemoryConnectionSecretStore()
        val ids = ArrayDeque(listOf("c-1", "c-2", "c-3"))
        val manager = ConnectionManagers.create(
            store = InMemoryConnectionStore(),
            secrets = secrets,
            clock = clock,
            idFactory = { ids.removeFirst() },
            ioDispatcher = Dispatchers.Unconfined,
            providers = ConnectionProviders.registryOf(provider),
        )
        return Harness(manager, secrets, deviceClient)
    }

    private class Harness(
        val manager: ConnectionManager,
        val secrets: InMemoryConnectionSecretStore,
        val deviceClient: FakeDeviceFlowClient,
    ) {
        suspend fun begin(): DeviceAuthorization {
            val result = manager.beginDeviceAuthorization(ConnectionType.GITHUB)
            return assertNotNull(result.valueOrNull(), result.errorOrNull()?.message)
        }
    }
}

private const val DEVICE_CODE_JSON =
    """{"device_code":"dev-code-secret","user_code":"WXYZ-1234",""" +
        """"verification_uri":"https://github.com/login/device","expires_in":900,"interval":5}"""

private fun deviceCode(): OAuthDeviceCode =
    OAuthDeviceCode("dev-code-secret", "WXYZ-1234", "https://github.com/login/device", 900, 5)

private fun tokens(): OAuthTokenSet =
    OAuthTokenSet(accessToken = "gho-secret-access-token", tokenType = "bearer", scopes = setOf("repo"), obtainedAtMillis = 1_000_000L)

private fun githubConnection(): Connection = Connection(
    id = ConnectionId("c-1"),
    displayName = "GitHub",
    type = ConnectionType.GITHUB,
    config = ConnectionConfig(authMethod = ConnectionAuthMethod.OAUTH),
    capabilities = ConnectionCapabilities.GITHUB,
)

/** An [OAuthHttpClient] that answers with a scripted response and records requests. */
private class RecordingHttpClient(
    private val handler: (OAuthHttpRequest) -> OAuthHttpResponse,
) : OAuthHttpClient {

    val requests = mutableListOf<OAuthHttpRequest>()

    override suspend fun execute(request: OAuthHttpRequest): OAuthHttpResponse {
        requests += request
        return handler(request)
    }
}

/** A [DeviceFlowClient] scripted with the polls it should return, in order. */
private class FakeDeviceFlowClient(
    private val code: OAuthDeviceCode = deviceCode(),
    private val polls: List<OAuthDevicePollResult> = listOf(OAuthDevicePollResult.Success(tokens())),
) : DeviceFlowClient {

    var pollCount = 0
        private set

    private var pollIndex = 0

    override suspend fun requestDeviceCode(scopes: Set<String>): OAuthDeviceCodeResult =
        OAuthDeviceCodeResult.Success(code)

    override suspend fun poll(deviceCode: String): OAuthDevicePollResult {
        val result = polls.getOrNull(pollIndex) ?: polls.lastOrNull()
            ?: OAuthDevicePollResult.Failure(OAuthFailureReason.AUTHORIZATION_ERROR, "no scripted poll")
        pollIndex++
        pollCount++
        return result
    }
}

/** A GitHub-shaped OAuth provider with no network access. */
private class FakeGitHubOAuthProvider(
    override val client: OAuthClientConfig,
    private val validationSucceeds: Boolean = true,
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

    override fun scopesFor(capabilities: Set<ConnectionCapability>): Set<String> = setOf(GitHubOAuthProvider.SCOPE_REPO)

    override fun capabilitiesFor(scopes: Set<String>): Set<ConnectionCapability> = ConnectionCapabilities.GITHUB

    override fun authorizationUrl(request: OAuthAuthorizationRequest): String = descriptor.authorizationEndpoint

    override suspend fun exchange(code: String, codeVerifier: String?, redirectUri: String): OAuthTokenResult =
        OAuthTokenResult.Success(tokens())

    override suspend fun refresh(refreshToken: String): OAuthTokenResult =
        OAuthTokenResult.Success(tokens())

    override suspend fun validate(tokens: OAuthTokenSet): OAuthValidation =
        if (validationSucceeds) {
            OAuthValidation(valid = true, accountLabel = "@octocat", message = "Authorized as @octocat")
        } else {
            OAuthValidation(valid = false, message = "GitHub rejected the credentials")
        }

    override suspend fun revoke(tokens: OAuthTokenSet): OAuthRevocationResult =
        OAuthRevocationResult(supported = false, revoked = false, message = "GitHub keeps the grant")
}
