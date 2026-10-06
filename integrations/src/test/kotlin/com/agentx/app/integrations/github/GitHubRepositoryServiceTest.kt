package com.agentx.app.integrations.github

import com.agentx.app.core.ForgeError
import com.agentx.app.core.ForgeErrorCode
import com.agentx.app.core.errorOrNull
import com.agentx.app.core.valueOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The GitHub repository client, driven entirely by a fake transport: no request
 * in this file reaches GitHub, and nothing here holds a real credential.
 */
class GitHubRepositoryServiceTest {

    private fun newService(
        gateway: FakeCredentialGateway = FakeCredentialGateway(),
        handler: (String) -> GitHubRestResponse,
    ): Pair<GitHubRepositoryServiceImpl, RecordingGitHubRestClient> {
        val client = RecordingGitHubRestClient(handler)
        val service = GitHubRepositoryServiceImpl(credentialGateway = gateway, restClient = client)
        return service to client
    }

    // --- Parsing --------------------------------------------------------------

    @Test
    fun `a page of repositories is parsed into domain models`() = runBlocking {
        val (service, client) = newService {
            GitHubRestResponse(
                statusCode = 200,
                body = "[${repositoryJson()},${repositoryJson(id = 2, name = "secret", privateRepository = true)}]",
            )
        }

        val page = assertNotNull(service.list(TEST_CONNECTION_ID).valueOrNull())

        assertEquals(2, page.repositories.size)
        val first = page.repositories.first()
        assertEquals("1", first.id.value)
        assertEquals("octocat", first.owner)
        assertEquals("hello-world", first.name)
        assertEquals("octocat/hello-world", first.fullName)
        assertEquals(GitHubRepositoryVisibility.PUBLIC, first.visibility)
        assertEquals("main", first.defaultBranch)
        assertEquals("https://github.com/octocat/hello-world.git", first.cloneUrl.url)
        assertEquals("https://github.com/octocat/hello-world", first.webUrl)
        assertEquals(GitHubRepositoryVisibility.PRIVATE, page.repositories[1].visibility)

        assertFalse(page.hasNextPage)
        assertNull(page.nextPageNumber)
        assertEquals(2, page.totalCount)
        assertEquals(1, client.requests.size)
    }

    @Test
    fun `a repository GitHubs default branch is used when none is reported`() = runBlocking {
        val body = repositoryJson().replace("""  "default_branch": "main",""", "")
        val (service, _) = newService { GitHubRestResponse(200, body) }

        val page = assertNotNull(service.list(TEST_CONNECTION_ID).valueOrNull())

        assertEquals(GitHubRepository.DEFAULT_BRANCH, page.repositories.single().defaultBranch)
    }

    @Test
    fun `a repository with an unusable clone url is skipped rather than fatal`() = runBlocking {
        val broken = repositoryJson(id = 2, name = "broken", cloneUrl = "git@github.com:octocat/broken.git")
        val (service, _) = newService {
            GitHubRestResponse(200, "[$broken,${repositoryJson()}]")
        }

        val page = assertNotNull(service.list(TEST_CONNECTION_ID).valueOrNull())

        assertEquals(listOf("octocat/hello-world"), page.repositories.map { it.fullName })
    }

    // --- Request shape --------------------------------------------------------

    @Test
    fun `the request carries the documented query, the bearer token and no more`() = runBlocking {
        val (service, client) = newService { GitHubRestResponse(200, "[]") }

        service.list(TEST_CONNECTION_ID, GitHubRepositoryVisibility.PRIVATE, page = 3, perPage = 5)

        val request = client.requests.single()
        assertTrue(request.url.startsWith("https://api.github.com/user/repos?"), request.url)
        assertTrue(request.url.contains("visibility=private"), request.url)
        assertTrue(request.url.contains("per_page=5"), request.url)
        assertTrue(request.url.contains("page=3"), request.url)
        assertTrue(request.url.contains("sort=full_name"), request.url)
        assertTrue(request.url.contains("direction=asc"), request.url)
        assertEquals("Bearer $TEST_TOKEN", request.headers["Authorization"])
        assertEquals("application/vnd.github+json", request.headers["Accept"])
        assertEquals("2022-11-28", request.headers["X-GitHub-Api-Version"])
        assertEquals("AgentX-Android", request.headers["User-Agent"])
    }

    @Test
    fun `a public listing asks for public repositories`() = runBlocking {
        val (service, client) = newService { GitHubRestResponse(200, "[]") }

        service.list(TEST_CONNECTION_ID)

        assertTrue(client.requests.single().url.contains("visibility=public"))
    }

    // --- Empty accounts -------------------------------------------------------

    @Test
    fun `an account with no repositories lists empty instead of failing`() = runBlocking {
        val (service, _) = newService { GitHubRestResponse(200, "[]") }

        val result = service.list(TEST_CONNECTION_ID)
        val page = assertNotNull(result.valueOrNull())

        assertTrue(page.repositories.isEmpty())
        assertFalse(page.hasNextPage)
        assertNull(page.nextPageNumber)
        assertEquals(0, page.totalCount)
        assertNull(result.errorOrNull())
    }

    // --- Pagination -----------------------------------------------------------

    @Test
    fun `a next link decides there is another page`() = runBlocking {
        val link = "<https://api.github.com/user/repos?per_page=2&page=2>; rel=\"next\", " +
            "<https://api.github.com/user/repos?per_page=2&page=7>; rel=\"last\""
        val (service, _) = newService {
            GitHubRestResponse(
                statusCode = 200,
                body = "[${repositoryJson()},${repositoryJson(id = 2, name = "second")}]",
                headers = mapOf("link" to listOf(link)),
            )
        }

        val page = assertNotNull(service.list(TEST_CONNECTION_ID, perPage = 2).valueOrNull())

        assertTrue(page.hasNextPage)
        assertEquals(2, page.nextPageNumber)
        // Page 7 is the last one at two per page, so at least twelve came before it.
        assertEquals(6 * 2 + 2, page.totalCount)
    }

    @Test
    fun `the last page does not claim another one`() = runBlocking {
        val link = "<https://api.github.com/user/repos?per_page=2&page=7>; rel=\"last\""
        val (service, _) = newService {
            GitHubRestResponse(
                statusCode = 200,
                body = "[${repositoryJson()}]",
                headers = mapOf("link" to listOf(link)),
            )
        }

        val page = assertNotNull(service.list(TEST_CONNECTION_ID, perPage = 2).valueOrNull())

        assertFalse(page.hasNextPage)
        assertNull(page.nextPageNumber)
    }

    @Test
    fun `an unreadable body is a malformed response`() = runBlocking {
        val bodies = listOf(
            "",
            "not json",
            """{"message":"Not Found"}""",
            "[1,2]",
        )

        for (body in bodies) {
            val (service, _) = newService { GitHubRestResponse(200, body) }
            val error = assertIs<GitHubRepositoryError.MalformedResponse>(
                service.list(TEST_CONNECTION_ID).errorOrNull(),
            )
            assertTrue(error.detail.isNotBlank(), "a malformed response explains itself: $body")
        }
    }

    // --- Status mapping -------------------------------------------------------

    @Test
    fun `HTTP statuses map onto typed errors`() = runBlocking {
        val cases = mapOf(
            401 to GitHubRepositoryError.Unauthenticated,
            403 to GitHubRepositoryError.Forbidden,
            404 to GitHubRepositoryError.NotFound,
            429 to GitHubRepositoryError.RateLimited,
        )

        for ((status, expected) in cases) {
            val (service, _) = newService { GitHubRestResponse(status, "") }
            assertEquals(expected, service.list(TEST_CONNECTION_ID).errorOrNull(), "status $status")
        }

        val (service, _) = newService { GitHubRestResponse(503, "") }
        assertEquals(GitHubRepositoryError.ServerError(503), service.list(TEST_CONNECTION_ID).errorOrNull())
    }

    @Test
    fun `a forbidden response with an exhausted quota is a rate limit`() = runBlocking {
        val (service, _) = newService {
            GitHubRestResponse(403, "", mapOf("X-RateLimit-Remaining" to listOf("0")))
        }

        assertEquals(GitHubRepositoryError.RateLimited, service.list(TEST_CONNECTION_ID).errorOrNull())
    }

    @Test
    fun `a transport failure is a network error`() = runBlocking {
        val (service, _) = newService { throw GitHubRestNetworkException("offline") }

        assertEquals(GitHubRepositoryError.NetworkFailure, service.list(TEST_CONNECTION_ID).errorOrNull())
    }

    // --- Credentials ----------------------------------------------------------

    @Test
    fun `a connection with no usable credential never reaches GitHub`() = runBlocking {
        val gateway = FakeCredentialGateway(token = null)
        var requests = 0
        val (service, _) = newService(gateway) {
            requests++
            GitHubRestResponse(200, "[]")
        }

        assertEquals(GitHubRepositoryError.NoCredential, service.list(TEST_CONNECTION_ID).errorOrNull())
        assertEquals(1, gateway.calls)
        assertEquals(0, requests, "no request is made without a credential")
    }

    @Test
    fun `an expired grant asks for re-authorization instead of a network call`() = runBlocking {
        val gateway = FakeCredentialGateway(
            refusal = ForgeError(ForgeErrorCode.CONNECTION_CREDENTIAL_EXPIRED, "The grant expired"),
        )
        var requests = 0
        val (service, _) = newService(gateway) {
            requests++
            GitHubRestResponse(200, "[]")
        }

        assertEquals(GitHubRepositoryError.Unauthenticated, service.list(TEST_CONNECTION_ID).errorOrNull())
        assertEquals(0, requests)
    }

    @Test
    fun `a refused listing preserves the connection for the next attempt`() = runBlocking {
        var body = ""
        val (service, _) = newService { GitHubRestResponse(if (body.isEmpty()) 401 else 200, body) }

        assertEquals(GitHubRepositoryError.Unauthenticated, service.list(TEST_CONNECTION_ID).errorOrNull())

        // Re-authorizing produced a usable credential; the same connection id works again.
        body = "[${repositoryJson()}]"
        val page = assertNotNull(service.list(TEST_CONNECTION_ID).valueOrNull())

        assertEquals(1, page.repositories.size)
    }

    @Test
    fun `cancellation propagates instead of becoming an error value`() {
        val (service, _) = newService { throw CancellationException("cancelled") }

        runBlocking {
            assertFailsWith<CancellationException> { service.list(TEST_CONNECTION_ID) }
        }
    }

    @Test
    fun `an out-of-range page size is refused before any request`() = runBlocking {
        val (service, client) = newService { GitHubRestResponse(200, "[]") }

        assertFailsWith<IllegalArgumentException> { service.list(TEST_CONNECTION_ID, perPage = 1_000) }
        assertFailsWith<IllegalArgumentException> { service.list(TEST_CONNECTION_ID, page = 0) }
        assertTrue(client.requests.isEmpty())
    }

    // --- Totals ---------------------------------------------------------------

    @Test
    fun `the implied total is remembered per connection and cleared on failure`() = runBlocking {
        var body = "[${repositoryJson()}]"
        val (service, _) = newService { GitHubRestResponse(200, body) }

        service.list(TEST_CONNECTION_ID)
        assertEquals(1, service.totalKnown(TEST_CONNECTION_ID))

        body = "not json"
        assertIs<GitHubRepositoryError.MalformedResponse>(service.list(TEST_CONNECTION_ID).errorOrNull())
        assertEquals(0, service.totalKnown(TEST_CONNECTION_ID))
    }

    // --- Token isolation ------------------------------------------------------

    @Test
    fun `the token exists only in the Authorization header`() = runBlocking {
        val (service, client) = newService { GitHubRestResponse(200, "[${repositoryJson()}]") }

        val page = assertNotNull(service.list(TEST_CONNECTION_ID).valueOrNull())

        assertEquals("Bearer $TEST_TOKEN", client.requests.single().headers["Authorization"])

        val repository = page.repositories.single()
        // Everything that leaves the transport. The recorded request is deliberately
        // not in this list: the Authorization header is where the token legitimately is.
        val surfaces = listOf(
            page.toString(),
            repository.toString(),
            repository.cloneUrl.toString(),
            repository.id.toString(),
            GitHubRepositoryUiState(repositories = page.repositories).toString(),
        )
        surfaces.forEach { surface ->
            assertFalse(surface.contains(TEST_TOKEN), "no surface leaks the token: $surface")
        }
    }
}
