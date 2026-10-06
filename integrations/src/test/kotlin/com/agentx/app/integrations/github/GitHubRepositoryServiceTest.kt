package com.agentx.app.integrations.github

import com.agentx.app.core.ForgeError
import com.agentx.app.core.ForgeErrorCode
import com.agentx.app.core.ForgeResult
import com.agentx.app.integrations.connection.ConnectionId
import com.agentx.app.integrations.connection.ConnectionCredentialGateway
import com.agentx.app.integrations.github.GitHubRepository
import com.agentx.app.integrations.github.GitHubRepositoryCloneUrl
import com.agentx.app.integrations.github.GitHubRepositoryError
import com.agentx.app.integrations.github.GitHubRepositoryPage
import com.agentx.app.integrations.github.GitHubRepositoryService
import com.agentx.app.integrations.github.GitHubRepositoryServiceImpl
import com.agentx.app.integrations.github.GitHubRepositoryVisibility
import com.agentx.app.integrations.github.GitHubRestClient
import com.agentx.app.integrations.github.GitHubRestNetworkException
import com.agentx.app.integrations.github.GitHubRestResponse
import com.agentx.app.integrations.github.GitHubRestRequest
import com.agentx.app.integrations.github.RepositoryId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertInstanceOf
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertThrows

/**
 * Tests for GitHub authenticated repository discovery.
 *
 * Tests use a fake HTTP client so no network calls are made.
 */
class GitHubRepositoryServiceTest {

    private lateinit var fakeHttpClient: FakeGitHubRestClient
    private lateinit var fakeCredentialGateway: FakeConnectionCredentialGateway
    private lateinit var service: GitHubRepositoryService
    private val connectionId = ConnectionId(UUID.randomUUID().toString())

    @BeforeEach
    fun setUp() {
        fakeHttpClient = FakeGitHubRestClient()
        fakeCredentialGateway = FakeConnectionCredentialGateway()
        service = GitHubRepositoryServiceImpl(
            credentialGateway = fakeCredentialGateway,
            restClient = fakeHttpClient,
        )
    }

    // --- Successful repository parsing ---

    @Test
    fun `parses a successful repository response`() = runTest {
        val body = """
            [
              {
                "id": 123456,
                "owner": {"login": "octocat"},
                "name": "Hello-World",
                "full_name": "octocat/Hello-World",
                "private": false,
                "default_branch": "main",
                "clone_url": "https://github.com/octocat/Hello-World.git",
                "html_url": "https://github.com/octocat/Hello-World"
              }
            ]
        """.trimIndent()

        fakeHttpClient.responses.add(
            GitHubRestResponse(
                statusCode = 200,
                body = body,
                headers = mapOf("link" to listOf("")),
            )
        )

        fakeCredentialGateway.credentials[connectionId.value] = "fake-token-12345"
        val result = service.list(connectionId)

        assertTrue(result is ForgeResult.Success)
        val page = (result as ForgeResult.Success).value
        assertEquals(1, page.repositories.size)
        assertEquals(RepositoryId("123456"), page.repositories[0].id)
        assertEquals("octocat", page.repositories[0].owner)
        assertEquals("Hello-World", page.repositories[0].name)
        assertEquals("octocat/Hello-World", page.repositories[0].fullName)
        assertEquals(GitHubRepositoryVisibility.PUBLIC, page.repositories[0].visibility)
        assertEquals("main", page.repositories[0].defaultBranch)
        assertEquals("https://github.com/octocat/Hello-World.git", page.repositories[0].cloneUrl.url)
        assertEquals("https://github.com/octocat/Hello-World", page.repositories[0].webUrl)
    }

    @Test
    fun `handles empty repository list`() = runTest {
        fakeHttpClient.responses.add(
            GitHubRestResponse(
                statusCode = 200,
                body = "[]",
                headers = emptyMap(),
            )
        )
        fakeCredentialGateway.credentials[connectionId.value] = "fake-token"

        val result = service.list(connectionId)
        assertTrue(result is ForgeResult.Success)
        val page = (result as ForgeResult.Success).value
        assertTrue(page.repositories.isEmpty())
    }

    @Test
    fun `handles malformed JSON`() = runTest {
        fakeHttpClient.responses.add(
            GitHubRestResponse(
                statusCode = 200,
                body = "not json at all",
                headers = emptyMap(),
            )
        )
        fakeCredentialGateway.credentials[connectionId.value] = "fake-token"

        val result = service.list(connectionId)
        assertTrue(result is ForgeResult.Failure)
        assertInstanceOf<GitHubRepositoryError.MalformedResponse>((result as ForgeResult.Failure).error)
    }

    // --- Authentication errors ---

    @Test
    fun `handles 401 unauthenticated`() = runTest {
        fakeHttpClient.responses.add(
            GitHubRestResponse(
                statusCode = 401,
                body = """{"message": "Bad credentials"}""",
                headers = emptyMap(),
            )
        )
        fakeCredentialGateway.credentials[connectionId.value] = "invalid-token"

        val result = service.list(connectionId)
        assertTrue(result is ForgeResult.Failure)
        assertInstanceOf<GitHubRepositoryError.Unauthenticated>((result as ForgeResult.Failure).error)
    }

    @Test
    fun `handles 403 forbidden`() = runTest {
        fakeHttpClient.responses.add(
            GitHubRestResponse(
                statusCode = 403,
                body = """{"message": "Forbidden"}""",
                headers = emptyMap(),
            )
        )
        fakeCredentialGateway.credentials[connectionId.value] = "fake-token"

        val result = service.list(connectionId)
        assertTrue(result is ForgeResult.Failure)
        assertInstanceOf<GitHubRepositoryError.Forbidden>((result as ForgeResult.Failure).error)
    }

    @Test
    fun `handles 404 not found`() = runTest {
        fakeHttpClient.responses.add(
            GitHubRestResponse(
                statusCode = 404,
                body = """{"message": "Not Found"}""",
                headers = emptyMap(),
            )
        )
        fakeCredentialGateway.credentials[connectionId.value] = "fake-token"

        val result = service.list(connectionId)
        assertTrue(result is ForgeResult.Failure)
        assertInstanceOf<GitHubRepositoryError.NotFound>((result as ForgeResult.Failure).error)
    }

    @Test
    fun `handles 429 rate limited`() = runTest {
        fakeHttpClient.responses.add(
            GitHubRestResponse(
                statusCode = 429,
                body = """{"message": "Rate limit exceeded"}""",
                headers = emptyMap(),
            )
        )
        fakeCredentialGateway.credentials[connectionId.value] = "fake-token"

        val result = service.list(connectionId)
        assertTrue(result is ForgeResult.Failure)
        assertInstanceOf<GitHubRepositoryError.RateLimited>((result as ForgeResult.Failure).error)
    }

    @Test
    fun `handles 500 server error`() = runTest {
        fakeHttpClient.responses.add(
            GitHubRestResponse(
                statusCode = 500,
                body = """{"message": "Server Error"}""",
                headers = emptyMap(),
            )
        )
        fakeCredentialGateway.credentials[connectionId.value] = "fake-token"

        val result = service.list(connectionId)
        assertTrue(result is ForgeResult.Failure)
        val error = (result as ForgeResult.Failure).error
        assertInstanceOf<GitHubRepositoryError.ServerError>(error)
        assertEquals(500, (error as GitHubRepositoryError.ServerError).code)
    }

    @Test
    fun `handles network failure`() = runTest {
        fakeHttpClient.responses.add(null)
        fakeCredentialGateway.credentials[connectionId.value] = "fake-token"

        val result = service.list(connectionId)
        assertTrue(result is ForgeResult.Failure)
        assertInstanceOf<GitHubRepositoryError.NetworkFailure>((result as ForgeResult.Failure).error)
    }

    @Test
    fun `propagates cancellation`() = runTest {
        fakeHttpClient.responses.add {
            delay(1000)
            GitHubRestResponse(200, "[]", emptyMap())
        }
        fakeCredentialGateway.credentials[connectionId.value] = "fake-token"

        val job = async(Dispatchers.Unconfined) {
            service.list(connectionId)
        }

        delay(10)
        job.cancel()
        job.join()

        val result = job.getCompleted()
        assertTrue(result is ForgeResult.Failure)
    }

    @Test
    fun `handles missing credential`() = runTest {
        fakeHttpClient.responses.add(
            GitHubRestResponse(200, "[]", emptyMap())
        )
        // No credential set

        val result = service.list(connectionId)
        assertTrue(result is ForgeResult.Failure)
        assertInstanceOf<GitHubRepositoryError.NoCredential>((result as ForgeResult.Failure).error)
    }

    @Test
    fun `token is never exposed in response models`() = runTest {
        val token = "ghp_secret_token_12345"
        fakeCredentialGateway.credentials[connectionId.value] = token

        fakeHttpClient.responses.add(
            GitHubRestResponse(
                statusCode = 200,
                body = """[{"id":1,"owner":{"login":"test"},"name":"repo","full_name":"test/repo","private":false,"default_branch":"main","clone_url":"https://github.com/test/repo.git","html_url":"https://github.com/test/repo"}]""",
                headers = emptyMap(),
            )
        )

        val result = service.list(connectionId)
        assertTrue(result is ForgeResult.Success)
        val page = (result as ForgeResult.Success).value

        val allStrings = listOf(
            page.toString(),
            page.repositories.joinToString("") { it.toString() },
        )

        for (str in allStrings) {
            assertFalse(str.contains(token), "Token leaked into model: $str")
            assertFalse(str.contains("Bearer"), "Authorization header leaked into model: $str")
        }
    }
}

// Fake implementations for testing

class FakeGitHubRestClient : GitHubRestClient {
    val responses = mutableListOf<GitHubRestResponse?>()
    var throwOnNext: Throwable? = null
    var lastRequest: GitHubRestRequest? = null

    override suspend fun get(url: String, headers: Map<String, String>): GitHubRestResponse {
        lastRequest = GitHubRestRequest(url, headers)
        throwOnNext?.let { throw it }
        return responses.removeAt(0) ?: throw GitHubRestNetworkException("No response configured")
    }
}

data class GitHubRestRequest(val url: String, val headers: Map<String, String>)

class FakeConnectionCredentialGateway : ConnectionCredentialGateway {
    val credentials = mutableMapOf<String, String>()

    override suspend fun <T> withCredential(
        connectionId: ConnectionId,
        block: suspend (String) -> T,
    ): ForgeResult<T, ForgeError> {
        val token = credentials[connectionId.value]
        return if (token != null) {
            try {
                ForgeResult.Success(block(token))
            } catch (e: Exception) {
                ForgeResult.Failure(object : ForgeError(FakeForgeErrorCode, e.message ?: "Error") {})
            }
        } else {
            ForgeResult.Failure(object : ForgeError(FakeForgeErrorCode, "No credential") {})
        }
    }
}

private object FakeForgeErrorCode : ForgeErrorCode {
    override val name: String = "FAKE"
    override val ordinal: Int = 999
    override fun toString(): String = name
}
