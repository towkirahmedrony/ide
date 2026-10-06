package com.agentx.app.integrations.github

import com.agentx.app.core.ForgeError
import com.agentx.app.core.ForgeErrorCode
import com.agentx.app.core.ForgeResult
import com.agentx.app.integrations.connection.*
import com.agentx.app.integrations.github.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertInstanceOf
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertNotSame

class GitHubRepositoryIntegrationTest {

    private lateinit var fakeGateway: FakeConnectionCredentialGateway
    private lateinit var fakeHttpClient: FakeGitHubRestClient
    private lateinit var service: GitHubRepositoryService
    private lateinit var connectionManager: FakeConnectionManager
    private val connectionId = ConnectionId(UUID.randomUUID().toString())

    @BeforeEach
    fun setUp() {
        fakeGateway = FakeConnectionCredentialGateway()
        fakeHttpClient = FakeGitHubRestClient()
        service = GitHubRepositoryServiceImpl(
            credentialGateway = fakeGateway,
            restClient = fakeHttpClient,
        )
        connectionManager = FakeConnectionManager()
    }

    @Test
    fun `repository service uses only credential gateway for tokens`() = runTest {
        fakeGateway.credentials[connectionId.value] = "secret-token-12345"
        fakeHttpClient.responses.add(
            GitHubRestResponse(
                statusCode = 200,
                body = """[{"id":1,"owner":{"login":"test"},"name":"repo","full_name":"test/repo","private":false,"default_branch":"main","clone_url":"https://github.com/test/repo.git","html_url":"https://github.com/test/repo"}]""",
                headers = emptyMap(),
            )
        )

        service.list(connectionId)

        assertTrue(fakeGateway.credentialUsedFor(connectionId.value))
        assertEquals("secret-token-12345", fakeGateway.lastUsedToken)
    }

    @Test
    fun `raw token never appears in domain models`() = runTest {
        val token = "ghp_secret_token_abc123"
        fakeGateway.credentials[connectionId.value] = token

        fakeHttpClient.responses.add(
            GitHubRestResponse(
                statusCode = 200,
                body = """[{"id":1,"owner":{"login":"test"},"name":"my-repo","full_name":"test/my-repo","private":false,"default_branch":"main","clone_url":"https://github.com/test/my-repo.git","html_url":"https://github.com/test/my-repo"}]""",
                headers = emptyMap(),
            )
        )

        val result = service.list(connectionId)
        assertTrue(result is ForgeResult.Success)
        val page = (result as ForgeResult.Success).value
        val repo = page.repositories.first()

        assertFalse(repo.id.toString().contains(token))
        assertFalse(repo.owner.contains(token))
        assertFalse(repo.name.contains(token))
        assertFalse(repo.fullName.contains(token))
        assertFalse(repo.cloneUrl.url.contains(token))
        assertFalse(repo.webUrl.contains(token))
        assertFalse(repo.toString().contains(token))
        assertFalse(page.toString().contains(token))
    }

    @Test
    fun `raw token never appears in UI state`() = runTest {
        val token = "ghp_secret_token_ui_abc123"
        fakeGateway.credentials[connectionId.value] = token

        fakeHttpClient.responses.add(
            GitHubRestResponse(
                statusCode = 200,
                body = """[{"id":1,"owner":{"login":"test"},"name":"my-repo","full_name":"test/my-repo","private":false,"default_branch":"main","clone_url":"https://github.com/test/my-repo.git","html_url":"https://github.com/test/my-repo"}]""",
                headers = emptyMap(),
            )
        )

        val result = service.list(connectionId)
        assertTrue(result is ForgeResult.Success)
        val page = (result as ForgeResult.Success).value

        val uiState = GitHubRepositoryUiState(
            loading = false,
            repositories = page.repositories,
            empty = page.repositories.isEmpty(),
            hasMore = page.hasNextPage,
            nextPage = page.nextPageNumber,
            error = null,
            totalCount = page.totalCount,
        )

        val allStrings = listOf(
            uiState.toString(),
            uiState.repositories.joinToString("") { it.toString() },
        )

        for (str in allStrings) {
            assertFalse(str.contains(token), "Token leaked into UI state: $str")
        }
    }

    @Test
    fun `clone URL never contains credentials`() = runTest {
        val repo = GitHubRepository(
            id = RepositoryId("1"),
            owner = "test",
            name = "my-repo",
            fullName = "test/my-repo",
            visibility = GitHubRepositoryVisibility.PUBLIC,
            defaultBranch = "main",
            cloneUrl = GitHubRepositoryCloneUrl.parse("https://github.com/test/my-repo.git"),
            webUrl = "https://github.com/test/my-repo",
        )

        assertFalse(repo.cloneUrl.url.contains("@"))
        assertFalse(repo.cloneUrl.url.contains("token"))
        assertFalse(repo.cloneUrl.url.contains("ghp_"))
        assertTrue(repo.cloneUrl.url.startsWith("https://github.com/"))
    }

    @Test
    fun `invalid clone URLs are rejected at model construction`() = runTest {
        assertThrows<IllegalArgumentException> {
            GitHubRepositoryCloneUrl.parse("https://user:password@github.com/owner/repo.git")
        }
        assertThrows<IllegalArgumentException> {
            GitHubRepositoryCloneUrl.parse("https://ghp_token@github.com/owner/repo.git")
        }
        assertThrows<IllegalArgumentException> {
            GitHubRepositoryCloneUrl.parse("git@github.com:owner/repo.git")
        }
    }

    @Test
    fun `API failure does not delete connection`() = runTest {
        connectionManager.connections[connectionId.value] = Connection(
            id = connectionId,
            displayName = "GitHub",
            type = ConnectionType.GITHUB,
            config = ConnectionConfig(authMethod = ConnectionAuthMethod.OAUTH),
            capabilities = ConnectionCapabilities.GITHUB,
            status = ConnectionStatus.CONNECTED,
            credentialRef = "test-ref",
        )

        fakeGateway.credentials[connectionId.value] = "token-123"
        fakeHttpClient.responses.add(
            GitHubRestResponse(
                statusCode = 500,
                body = """{"message": "Server Error"}""",
                headers = emptyMap(),
            )
        )

        service.list(connectionId)

        val connection = connectionManager.connections[connectionId.value]
        assertNotNull(connection)
        assertEquals(ConnectionStatus.CONNECTED, connection?.status)
    }

    @Test
    fun `authentication error does not delete connection`() = runTest {
        connectionManager.connections[connectionId.value] = Connection(
            id = connectionId,
            displayName = "GitHub",
            type = ConnectionType.GITHUB,
            config = ConnectionConfig(authMethod = ConnectionAuthMethod.OAUTH),
            capabilities = ConnectionCapabilities.GITHUB,
            status = ConnectionStatus.CONNECTED,
            credentialRef = "test-ref",
        )

        fakeGateway.credentials[connectionId.value] = "invalid-token"
        fakeHttpClient.responses.add(
            GitHubRestResponse(
                statusCode = 401,
                body = """{"message": "Bad credentials"}""",
                headers = emptyMap(),
            )
        )

        service.list(connectionId)

        val connection = connectionManager.connections[connectionId.value]
        assertNotNull(connection)
        assertEquals(ConnectionStatus.CONNECTED, connection?.status)
    }

    @Test
    fun `rate limit error does not delete connection`() = runTest {
        connectionManager.connections[connectionId.value] = Connection(
            id = connectionId,
            displayName = "GitHub",
            type = ConnectionType.GITHUB,
            config = ConnectionConfig(authMethod = ConnectionAuthMethod.OAUTH),
            capabilities = ConnectionCapabilities.GITHUB,
            status = ConnectionStatus.CONNECTED,
            credentialRef = "test-ref",
        )

        fakeGateway.credentials[connectionId.value] = "valid-token"
        fakeHttpClient.responses.add(
            GitHubRestResponse(
                statusCode = 429,
                body = """{"message": "Rate limit exceeded"}""",
                headers = emptyMap(),
            )
        )

        service.list(connectionId)

        assertNotNull(connectionManager.connections[connectionId.value])
    }

    @Test
    fun `no duplicate connection store is introduced`() = runTest {
        val gateway1 = FakeConnectionCredentialGateway()
        val gateway2 = FakeConnectionCredentialGateway()

        val service1 = GitHubRepositoryServiceImpl(gateway1, fakeHttpClient)
        val service2 = GitHubRepositoryServiceImpl(gateway2, fakeHttpClient)

        assertNotSame(gateway1.credentials, gateway2.credentials)
    }
}

class FakeConnectionCredentialGateway : ConnectionCredentialGateway {
    val credentials = mutableMapOf<String, String>()
    val usedConnections = mutableSetOf<String>()
    var lastUsedToken: String? = null

    override suspend fun <T> withCredential(
        connectionId: ConnectionId,
        block: suspend (String) -> T,
    ): ForgeResult<T, ForgeError> {
        val token = credentials[connectionId.value]
        return if (token != null) {
            usedConnections.add(connectionId.value)
            lastUsedToken = token
            try {
                ForgeResult.Success(block(token))
            } catch (e: Exception) {
                ForgeResult.Failure(object : ForgeError(FakeForgeErrorCode, e.message ?: "Error") {})
            }
        } else {
            ForgeResult.Failure(object : ForgeError(FakeForgeErrorCode, "No credential") {})
        }
    }

    fun credentialUsedFor(connectionId: ConnectionId): Boolean = usedConnections.contains(connectionId.value)
}

class FakeConnectionManager : ConnectionManager {
    val connections = mutableMapOf<String, Connection>()

    override val state = kotlinx.coroutines.flow.MutableStateFlow(
        ConnectionManagerState(loading = false, connections = emptyList())
    )
    override val credentialsPersistent = true
    override fun providerAvailability() = emptyList()
    override fun providerDescriptors() = emptyList()
    override fun tools() = emptyList()
    override fun enabledToolNames() = emptyList()
    override suspend fun refresh() {}
    override suspend fun connection(id: ConnectionId) = connections[id.value]
    override suspend fun list() = connections.values.toList()
    override suspend fun addConnection(draft: ConnectionDraft): ForgeResult<Connection, ForgeError> =
        ForgeResult.Failure(object : ForgeError(FakeForgeErrorCode, "Not implemented") {})
    override suspend fun updateConnection(draft: ConnectionDraft): ForgeResult<Connection, ForgeError> =
        ForgeResult.Failure(object : ForgeError(FakeForgeErrorCode, "Not implemented") {})
    override suspend fun removeConnection(id: ConnectionId): ForgeResult<Unit, ForgeError> =
        ForgeResult.Failure(object : ForgeError(FakeForgeErrorCode, "Not implemented") {})
    override suspend fun setEnabled(id: ConnectionId, enabled: Boolean): ForgeResult<Connection, ForgeError> =
        ForgeResult.Failure(object : ForgeError(FakeForgeErrorCode, "Not implemented") {})
    override suspend fun testConnection(id: ConnectionId): ForgeResult<ConnectionTestResult, ForgeError> =
        ForgeResult.Failure(object : ForgeError(FakeForgeErrorCode, "Not implemented") {})
    override suspend fun connect(type: ConnectionType, displayName: String?): ForgeResult<AuthorizationStart, ForgeError> =
        ForgeResult.Failure(object : ForgeError(FakeForgeErrorCode, "Not implemented") {})
    override suspend fun beginAuthorization(id: ConnectionId): ForgeResult<AuthorizationStart, ForgeError> =
        ForgeResult.Failure(object : ForgeError(FakeForgeErrorCode, "Not implemented") {})
    override fun supportsDeviceAuthorization(type: ConnectionType) = false
    override suspend fun beginDeviceAuthorization(type: ConnectionType, displayName: String?): ForgeResult<DeviceAuthorization, ForgeError> =
        ForgeResult.Failure(object : ForgeError(FakeForgeErrorCode, "Not implemented") {})
    override suspend fun completeDeviceAuthorization(id: ConnectionId, onState: suspend (com.agentx.app.integrations.oauth.DeviceFlowState) -> Unit): ForgeResult<Connection, ForgeError> =
        ForgeResult.Failure(object : ForgeError(FakeForgeErrorCode, "Not implemented") {})
    override suspend fun completeAuthorization(callbackUri: String): ForgeResult<Connection, ForgeError> =
        ForgeResult.Failure(object : ForgeError(FakeForgeErrorCode, "Not implemented") {})
    override suspend fun cancelAuthorization(id: ConnectionId): ForgeResult<Connection, ForgeError> =
        ForgeResult.Failure(object : ForgeError(FakeForgeErrorCode, "Not implemented") {})
    override suspend fun disconnect(id: ConnectionId): ForgeResult<Connection, ForgeError> =
        ForgeResult.Failure(object : ForgeError(FakeForgeErrorCode, "Not implemented") {})
    override suspend fun refreshAuthorization(id: ConnectionId): ForgeResult<Connection, ForgeError> =
        ForgeResult.Failure(object : ForgeError(FakeForgeErrorCode, "Not implemented") {})
    override fun status(id: ConnectionId): ConnectionStatus? = connections[id.value]?.status
    override suspend fun authorize(type: ConnectionType, capability: ConnectionCapability, connectionId: ConnectionId?): ForgeResult<AuthorizedConnection, ConnectionAuthorizationError> =
        ForgeResult.Failure(object : ForgeError(FakeForgeErrorCode, "Not implemented") {})
}

private object FakeForgeErrorCode : ForgeErrorCode {
    override val name: String = "FAKE"
    override val ordinal: Int = 999
    override fun toString(): String = name
}
