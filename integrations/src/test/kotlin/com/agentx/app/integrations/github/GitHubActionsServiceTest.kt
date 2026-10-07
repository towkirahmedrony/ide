package com.agentx.app.integrations.github

import com.agentx.app.core.ForgeResult
import com.agentx.app.core.success
import com.agentx.app.core.verification.CiConclusion
import com.agentx.app.core.verification.CiRepositoryRef
import com.agentx.app.core.verification.CiRun
import com.agentx.app.core.verification.CiRunState
import com.agentx.app.core.verification.CiVerificationError
import com.agentx.app.integrations.connection.ConnectionCredentialGateway
import com.agentx.app.integrations.connection.ConnectionId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The GitHub Actions client and its pure parsing, exercised against fake HTTP
 * responses. No test here contacts GitHub: the transport is a stub, so success,
 * failure, a failing job, a missing run, a malformed body, a network error and
 * cancellation are all deterministic.
 */
class GitHubActionsServiceTest {

    private val repository = CiRepositoryRef("towkirahmedrony", "ide")

    private class FakeRestClient(
        private val failure: Throwable? = null,
        private val responses: (String) -> GitHubRestResponse,
    ) : GitHubRestClient {
        val urls = mutableListOf<String>()

        override suspend fun get(url: String, headers: Map<String, String>): GitHubRestResponse {
            urls += url
            failure?.let { throw it }
            return responses(url)
        }

        override suspend fun post(
            url: String,
            headers: Map<String, String>,
            body: String,
        ): GitHubRestResponse {
            urls += url
            failure?.let { throw it }
            return responses(url)
        }
    }

    private val gateway = object : ConnectionCredentialGateway {
        override suspend fun <T> withCredential(
            connectionId: ConnectionId,
            block: suspend (String) -> T,
        ): ForgeResult<T, com.agentx.app.core.ForgeError> = success(block("token-that-must-not-leak"))
    }

    private fun service(client: GitHubRestClient) = GitHubActionsServiceImpl(
        credentialGateway = gateway,
        connections = GitHubActionsConnectionResolver { ConnectionId("c1") },
        restClient = client,
    )

    private fun <T> valueOf(result: ForgeResult<T, CiVerificationError>): T = when (result) {
        is ForgeResult.Success -> result.value
        is ForgeResult.Failure -> throw AssertionError("expected success but got ${result.error}")
    }

    private fun <T> failureOf(result: ForgeResult<T, CiVerificationError>): CiVerificationError = when (result) {
        is ForgeResult.Failure -> result.error
        is ForgeResult.Success -> throw AssertionError("expected failure but got success")
    }

    private val runsBody = """
        {"total_count":1,"workflow_runs":[
          {"id":100,"name":"Build Android Release APK","head_sha":"abc","head_branch":"main",
           "event":"push","status":"completed","conclusion":"failure","html_url":"https://x/100"}
        ]}
    """.trimIndent()

    private val jobsBody = """
        {"total_count":1,"jobs":[
          {"id":900,"name":"build","status":"completed","conclusion":"failure",
           "steps":[{"number":1,"name":"Checkout","status":"completed","conclusion":"success"},
                    {"number":2,"name":"Build Release APK","status":"completed","conclusion":"failure"}]}
        ]}
    """.trimIndent()

    @Test
    fun `the latest run is parsed with its head sha and conclusion`() = runBlocking {
        val client = FakeRestClient { GitHubRestResponse(200, runsBody) }

        val run = assertNotNull(valueOf(service(client).latestRun(repository, "main", "abc")))

        assertEquals(100L, run.id)
        assertEquals("abc", run.headSha)
        assertEquals(CiRunState.COMPLETED, run.state)
        assertEquals(CiConclusion.FAILURE, run.conclusion)
        assertTrue(client.urls.single().contains("head_sha=abc"))
    }

    @Test
    fun `an empty runs listing is a success with no run, not an error`() = runBlocking {
        val client = FakeRestClient { GitHubRestResponse(200, "{\"total_count\":0,\"workflow_runs\":[]}") }

        assertNull(valueOf(service(client).latestRun(repository, "main")))
    }

    @Test
    fun `a run with its jobs exposes the failing step`() = runBlocking {
        val client = FakeRestClient { url ->
            if (url.endsWith("/jobs")) {
                GitHubRestResponse(200, jobsBody)
            } else {
                GitHubRestResponse(
                    200,
                    """{"id":100,"name":"Build Android Release APK","head_sha":"abc","head_branch":"main",
                        "status":"completed","conclusion":"failure"}""",
                )
            }
        }

        val run = valueOf(service(client).run(repository, 100))
        val job = run.jobs.single()

        assertEquals("build", job.name)
        assertEquals("Build Release APK", job.failedStep?.name)
    }

    @Test
    fun `a successful run maps to success`() = runBlocking {
        val client = FakeRestClient {
            GitHubRestResponse(
                200,
                """{"total_count":1,"workflow_runs":[
                    {"id":5,"name":"ci","head_sha":"abc","head_branch":"main","status":"completed","conclusion":"success"}
                ]}""",
            )
        }

        val run: CiRun = assertNotNull(valueOf(service(client).latestRun(repository, "main")))

        assertTrue(run.succeeded)
    }

    @Test
    fun `a malformed body is a structured malformed error`() = runBlocking {
        val client = FakeRestClient { GitHubRestResponse(200, "not json") }

        assertIs<CiVerificationError.MalformedResponse>(failureOf(service(client).latestRun(repository, "main")))
    }

    @Test
    fun `a network failure is reported as a network failure`() = runBlocking {
        val client = FakeRestClient(
            responses = { GitHubRestResponse(200, runsBody) },
            failure = GitHubRestNetworkException("boom"),
        )

        assertEquals(CiVerificationError.NetworkFailure, failureOf(service(client).latestRun(repository, "main")))
    }

    @Test
    fun `a 401 is an authentication failure and never deletes the connection`() = runBlocking {
        val client = FakeRestClient { GitHubRestResponse(401, "{}") }

        assertEquals(CiVerificationError.Unauthenticated, failureOf(service(client).latestRun(repository, "main")))
    }

    @Test
    fun `cancellation propagates instead of being reported as a network failure`() = runBlocking {
        val client = FakeRestClient(
            responses = { GitHubRestResponse(200, runsBody) },
            failure = CancellationException("cancelled"),
        )

        val thrown = runCatching { service(client).latestRun(repository, "main") }.exceptionOrNull()

        assertIs<CancellationException>(thrown)
    }

    @Test
    fun `logs are bounded and never carry the token`() = runBlocking {
        val client = FakeRestClient { url ->
            when {
                url.endsWith("/jobs") -> GitHubRestResponse(200, jobsBody)
                url.endsWith("/logs") -> GitHubRestResponse(200, "line1\nBUILD FAILED\n")
                else -> GitHubRestResponse(200, jobsBody)
            }
        }

        val logs = valueOf(service(client).logs(repository, 100))

        assertEquals(900L, logs.jobId)
        assertTrue(logs.text.contains("BUILD FAILED"))
        assertTrue(!logs.text.contains("token-that-must-not-leak"))
    }

    // --- pure parsing ------------------------------------------------------

    @Test
    fun `state and conclusion mapping is total`() {
        assertEquals(CiRunState.QUEUED, GitHubActionsParsing.mapState("queued"))
        assertEquals(CiRunState.IN_PROGRESS, GitHubActionsParsing.mapState("in_progress"))
        assertEquals(CiRunState.COMPLETED, GitHubActionsParsing.mapState("completed"))
        assertEquals(CiRunState.UNKNOWN, GitHubActionsParsing.mapState("nonsense"))

        assertEquals(CiConclusion.SUCCESS, GitHubActionsParsing.mapConclusion("success"))
        assertEquals(CiConclusion.FAILURE, GitHubActionsParsing.mapConclusion("failure"))
        assertEquals(CiConclusion.TIMED_OUT, GitHubActionsParsing.mapConclusion("timed_out"))
        assertEquals(CiConclusion.UNKNOWN, GitHubActionsParsing.mapConclusion("whatever"))
        assertNull(GitHubActionsParsing.mapConclusion(null))
    }

    @Test
    fun `parsing tolerates a body that is the wrong shape`() {
        assertNull(GitHubActionsParsing.parseRunList("{}"))
        assertNull(GitHubActionsParsing.parseRun("[]"))
        assertNull(GitHubActionsParsing.parseJobs("<html>"))
        assertTrue(GitHubActionsParsing.parseJobs("{\"jobs\":[]}").orEmpty().isEmpty())
    }

    @Test
    fun `repository refs are derived from a credential-free clone url`() {
        assertEquals(
            CiRepositoryRef("me", "ide"),
            GitHubRepositoryRefs.fromCloneUrl("https://github.com/me/ide.git"),
        )
        assertEquals(
            CiRepositoryRef("me", "ide"),
            GitHubRepositoryRefs.fromCloneUrl("https://github.com/me/ide"),
        )
        assertNull(GitHubRepositoryRefs.fromCloneUrl("https://user:pass@github.com/me/ide.git"))
        assertNull(GitHubRepositoryRefs.fromCloneUrl("git@github.com:me/ide.git"))
        assertNull(GitHubRepositoryRefs.fromCloneUrl("https://gitlab.com/me/ide.git"))
    }
}
