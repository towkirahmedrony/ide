package com.agentx.app.integrations.github

import com.agentx.app.core.ForgeError
import com.agentx.app.core.ForgeErrorCode
import com.agentx.app.core.ForgeResult
import com.agentx.app.core.pullrequest.CreatedPullRequest
import com.agentx.app.core.pullrequest.NewPullRequest
import com.agentx.app.core.pullrequest.PullRequestError
import com.agentx.app.core.pullrequest.PullRequestRef
import com.agentx.app.integrations.connection.ConnectionCredentialGateway
import com.agentx.app.integrations.connection.ConnectionId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The GitHub pull-request client, exercised against fake HTTP responses.
 *
 * No test here contacts GitHub: the transport is a stub, so a 201, every error
 * status, a malformed body, a missing branch, a network failure and cancellation
 * are deterministic. The credential gateway is the real seam the production code
 * uses, so "the token never leaks into the wire, the result, or an error" is
 * asserted against the actual service, not a reimplementation of it.
 */
class GitHubPullRequestServiceTest {

    private val repository = PullRequestRef("octocat", "hello-world")

    /** A [GitHubRestClient] whose GET and POST answers (and failures) are scripted. */
    private class ScriptedRestClient(
        private val onGet: (String) -> GitHubRestResponse,
        private val onPost: (String, String) -> GitHubRestResponse = { _, _ -> GitHubRestResponse(201, "") },
    ) : GitHubRestClient {
        val gets = mutableListOf<String>()
        val posts = mutableListOf<String>()
        var getFailure: Throwable? = null
        var postFailure: Throwable? = null

        override suspend fun get(url: String, headers: Map<String, String>): GitHubRestResponse {
            getFailure?.let { throw it }
            gets += url
            return onGet(url)
        }

        override suspend fun post(
            url: String,
            headers: Map<String, String>,
            body: String,
        ): GitHubRestResponse {
            postFailure?.let { throw it }
            posts += body
            return onPost(url, body)
        }
    }

    private fun request(
        title: String = "Add the feature",
        head: String = "feature/readme",
        base: String = "main",
        body: String = "Adds the thing.",
    ) = NewPullRequest(repository = repository, title = title, body = body, head = head, base = base)

    private fun service(
        client: GitHubRestClient,
        resolver: GitHubPullRequestConnectionResolver = GitHubPullRequestConnectionResolver { TEST_CONNECTION_ID },
        gateway: ConnectionCredentialGateway = FakeCredentialGateway(),
    ) = GitHubPullRequestServiceImpl(
        credentialGateway = gateway,
        connections = resolver,
        restClient = client,
    )

    private fun pullRequestJson(
        number: Int = 7,
        title: String = "Add the feature",
        state: String = "open",
        head: String = "feature/readme",
        base: String = "main",
        url: String = "https://github.com/octocat/hello-world/pull/7",
        draft: Boolean = false,
    ) = """
        {"number":$number,"title":"$title","state":"$state","draft":$draft,
         "html_url":"$url","head":{"ref":"$head"},"base":{"ref":"$base"}}
    """.trimIndent()

    private fun createdOf(result: ForgeResult<CreatedPullRequest, PullRequestError>): CreatedPullRequest =
        assertIs<ForgeResult.Success<CreatedPullRequest>>(result).value

    private fun errorOf(result: ForgeResult<CreatedPullRequest, PullRequestError>): PullRequestError =
        assertIs<ForgeResult.Failure<PullRequestError>>(result).error

    /** Answers every branch GET with 200 so a test can isolate the create call. */
    private fun branchesExistClient(
        onPost: (String, String) -> GitHubRestResponse = { _, _ -> GitHubRestResponse(201, pullRequestJson()) },
    ) = ScriptedRestClient(onGet = { GitHubRestResponse(200, "{}") }, onPost = onPost)

    // --- success -----------------------------------------------------------

    @Test
    fun `a created pull request is parsed into the safe result`() = runBlocking {
        val client = branchesExistClient()

        val created = createdOf(service(client).create(request()))

        assertEquals(7, created.number)
        assertEquals("Add the feature", created.title)
        assertEquals("open", created.state)
        assertEquals("feature/readme", created.headBranch)
        assertEquals("main", created.baseBranch)
        assertEquals(repository, created.repository)
        assertEquals("https://github.com/octocat/hello-world/pull/7", created.htmlUrl)
        assertFalse(created.draft)
    }

    @Test
    fun `the request maps repository, head, base, title and body exactly`() = runBlocking {
        val client = branchesExistClient()

        service(client).create(request(title = "T", head = "feature/x", base = "develop", body = "B"))

        assertEquals(2, client.gets.size, "both branches are checked before the write")
        assertTrue(client.gets.all { it.contains("/repos/octocat/hello-world/branches/") })
        val body = client.posts.single()
        assertEquals(
            """{"title":"T","head":"feature/x","base":"develop","body":"B"}""",
            body,
        )
    }

    @Test
    fun `an empty body is omitted from the request`() = runBlocking {
        val client = branchesExistClient()

        service(client).create(request(body = ""))

        assertEquals("""{"title":"Add the feature","head":"feature/readme","base":"main"}""", client.posts.single())
    }

    // --- authentication and security --------------------------------------

    @Test
    fun `the credential gateway is used exactly once and the token never touches the wire`() = runBlocking {
        val gateway = FakeCredentialGateway()
        val client = branchesExistClient()

        service(client, gateway = gateway).create(request())

        assertEquals(1, gateway.calls)
        assertFalse(client.posts.single().contains(TEST_TOKEN))
    }

    @Test
    fun `the token never appears in the created result`() = runBlocking {
        val created = createdOf(service(branchesExistClient()).create(request()))

        assertFalse(created.toString().contains(TEST_TOKEN))
        assertFalse(created.title.contains(TEST_TOKEN))
    }

    @Test
    fun `the token never appears in a mapped error`() = runBlocking {
        val client = branchesExistClient(onPost = { _, _ -> GitHubRestResponse(422, "{\"message\":\"nope\"}") })

        val error = errorOf(service(client).create(request()))

        assertFalse(error.toString().contains(TEST_TOKEN))
    }

    @Test
    fun `a gateway refusal maps to a typed error and no request is sent`() = runBlocking {
        val client = branchesExistClient()
        val gateway = FakeCredentialGateway(
            token = null,
            refusal = ForgeError(ForgeErrorCode.CONNECTION_CREDENTIAL_EXPIRED, "expired"),
        )

        assertEquals(
            PullRequestError.Unauthenticated,
            errorOf(service(client, gateway = gateway).create(request())),
        )
        assertTrue(client.gets.isEmpty())
        assertTrue(client.posts.isEmpty())
    }

    @Test
    fun `no connection is reported before any credential is requested`() = runBlocking {
        val client = branchesExistClient()
        val gateway = FakeCredentialGateway()

        val result = service(
            client,
            resolver = GitHubPullRequestConnectionResolver { null },
            gateway = gateway,
        ).create(request())

        assertEquals(PullRequestError.NoConnection, errorOf(result))
        assertEquals(0, gateway.calls)
    }

    // --- validation --------------------------------------------------------

    @Test
    fun `head equal to base is refused without any HTTP call`() = runBlocking {
        val client = branchesExistClient()

        val error = errorOf(service(client).create(request(head = "main", base = "main")))

        assertIs<PullRequestError.ValidationFailed>(error)
        assertTrue(client.gets.isEmpty() && client.posts.isEmpty())
    }

    @Test
    fun `a blank title, head or base is a validation failure`() = runBlocking {
        val client = branchesExistClient()
        val service = service(client)

        assertIs<PullRequestError.ValidationFailed>(errorOf(service.create(request(title = " "))))
        assertIs<PullRequestError.ValidationFailed>(errorOf(service.create(request(head = ""))))
        assertIs<PullRequestError.ValidationFailed>(errorOf(service.create(request(base = "  "))))
        assertTrue(client.gets.isEmpty())
    }

    @Test
    fun `a missing branch is a not-found error`() = runBlocking {
        val client = ScriptedRestClient(onGet = { url ->
            if (url.endsWith("/branches/feature/readme")) {
                GitHubRestResponse(404, "{}")
            } else {
                GitHubRestResponse(200, "{}")
            }
        })

        val error = errorOf(service(client).create(request()))

        assertIs<PullRequestError.NotFound>(error)
        assertTrue(client.posts.isEmpty(), "no pull request is created when the head branch is missing")
    }

    // --- API errors --------------------------------------------------------

    @Test
    fun `a 401 is an authentication failure`() = runBlocking {
        val client = ScriptedRestClient(
            onGet = { GitHubRestResponse(200, "{}") },
            onPost = { _, _ -> GitHubRestResponse(401, "{}") },
        )
        assertEquals(PullRequestError.Unauthenticated, errorOf(service(client).create(request())))
    }

    @Test
    fun `a 403 is forbidden, and 403 with zero quota is rate limited`() = runBlocking {
        val forbidden = branchesExistClient(onPost = { _, _ -> GitHubRestResponse(403, "{}") })
        assertEquals(PullRequestError.Forbidden, errorOf(service(forbidden).create(request())))

        val limited = branchesExistClient(onPost = { _, _ ->
            GitHubRestResponse(403, "{}", mapOf("X-RateLimit-Remaining" to listOf("0")))
        })
        assertEquals(PullRequestError.RateLimited, errorOf(service(limited).create(request())))
    }

    @Test
    fun `a 404 on create is a not-found error`() = runBlocking {
        val client = branchesExistClient(onPost = { _, _ -> GitHubRestResponse(404, "{}") })
        assertIs<PullRequestError.NotFound>(errorOf(service(client).create(request())))
    }

    @Test
    fun `a 409 is a conflict`() = runBlocking {
        val client = branchesExistClient(onPost = { _, _ -> GitHubRestResponse(409, "{}") })
        assertIs<PullRequestError.Conflict>(errorOf(service(client).create(request())))
    }

    @Test
    fun `a 422 keeps GitHub's own message and is a validation failure`() = runBlocking {
        val client = branchesExistClient(onPost = { _, _ ->
            GitHubRestResponse(422, "{\"message\":\"No commits between main and feature/readme\"}")
        })

        val error = errorOf(service(client).create(request()))

        val validation = assertIs<PullRequestError.ValidationFailed>(error)
        assertTrue(validation.detail.contains("No commits between"))
    }

    @Test
    fun `a 429 is rate limited`() = runBlocking {
        val client = branchesExistClient(onPost = { _, _ -> GitHubRestResponse(429, "{}") })
        assertEquals(PullRequestError.RateLimited, errorOf(service(client).create(request())))
    }

    @Test
    fun `a 5xx is a server error with the code`() = runBlocking {
        val client = branchesExistClient(onPost = { _, _ -> GitHubRestResponse(503, "{}") })
        assertEquals(PullRequestError.ServerError(503), errorOf(service(client).create(request())))
    }

    @Test
    fun `a body that is not a pull request is a malformed response`() = runBlocking {
        val client = branchesExistClient(onPost = { _, _ -> GitHubRestResponse(201, "<html>nope</html>") })
        assertIs<PullRequestError.MalformedResponse>(errorOf(service(client).create(request())))
    }

    @Test
    fun `a network failure is reported as a network failure`() = runBlocking {
        val client = branchesExistClient()
        client.postFailure = GitHubRestNetworkException("boom")

        assertEquals(PullRequestError.NetworkFailure, errorOf(service(client).create(request())))
    }

    @Test
    fun `cancellation propagates instead of being reported as a network failure`() = runBlocking {
        val client = branchesExistClient()
        client.postFailure = CancellationException("cancelled")

        val thrown = runCatching { service(client).create(request()) }.exceptionOrNull()

        assertIs<CancellationException>(thrown)
    }

    // --- pure parsing ------------------------------------------------------

    @Test
    fun `parsing rejects a body that is not a pull request shape`() {
        assertNull(GitHubPullRequestParsing.parseCreated("{}", repository))
        assertNull(GitHubPullRequestParsing.parseCreated("[]", repository))
        assertNull(GitHubPullRequestParsing.parseCreated("<html>", repository))
        assertIs<PullRequestError.MalformedResponse>(
            GitHubPullRequestParsing.malformedDetail(""),
        )
    }
}
