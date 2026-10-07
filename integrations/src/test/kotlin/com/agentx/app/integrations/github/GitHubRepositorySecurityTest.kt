package com.agentx.app.integrations.github

import com.agentx.app.core.errorOrNull
import com.agentx.app.core.valueOrNull
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What Phase 2 must never do: carry a credential outside the transport, or let a
 * repository name decide where a clone lands.
 */
class GitHubRepositorySecurityTest {

    private fun temporaryDirectory(): File = Files.createTempDirectory("agentx-github-security").toFile()

    // --- Clone URL validation -------------------------------------------------

    @Test
    fun `only credential-free github https urls are accepted`() {
        val rejected = listOf(
            "",
            "   ",
            "https://gitlab.com/octocat/hello-world.git",
            "http://github.com/octocat/hello-world.git",
            "git@github.com:octocat/hello-world.git",
            "ssh://git@github.com/octocat/hello-world.git",
            "https://user:password@github.com/octocat/hello-world.git",
            "https://x-access-token:$TEST_TOKEN@github.com/octocat/hello-world.git",
            "https://github.com.evil.test/octocat/hello-world.git",
        )

        rejected.forEach { candidate ->
            assertNull(GitHubRepositoryCloneUrl.parse(candidate), "\"$candidate\" must be refused")
        }

        val accepted = assertNotNull(
            GitHubRepositoryCloneUrl.parse("  https://github.com/octocat/hello-world.git  "),
        )
        assertEquals("https://github.com/octocat/hello-world.git", accepted.url)
    }

    // --- Destination traversal ------------------------------------------------

    @Test
    fun `no repository name can place a clone outside the managed root`() {
        val validator = CloneDestinationValidator()
        val root = temporaryDirectory()
        try {
            val hostile = listOf(
                "..",
                "../..",
                "../../etc",
                "..\\..\\windows",
                "/absolute/path",
                "/etc/passwd",
                "octocat/../../escape",
                "name\u0000suffix",
                "name\nnewline",
            )

            val escapes = hostile.flatMap { hostileName ->
                listOf(
                    hostileName to "repo",
                    "octocat" to hostileName,
                )
            }

            escapes.forEach { (owner, name) ->
                val validation = validator.validate(root, githubRepository(owner = owner, name = name))
                assertIs<CloneDestinationValidation.Invalid>(
                    validation,
                    "\"$owner\"/\"$name\" must not resolve to a destination",
                )
                assertTrue(
                    validator.directoryName(owner, name) == null,
                    "\"$owner\"/\"$name\" must not become a folder name",
                )
            }

            // Nothing was created inside the root for any of them.
            assertTrue(root.listFiles().orEmpty().isEmpty(), "a refused destination creates nothing")
        } finally {
            root.deleteRecursively()
        }
    }

    // --- Credential isolation -------------------------------------------------

    @Test
    fun `a listing never exposes the token on any returned surface`() = runBlocking {
        val client = RecordingGitHubRestClient { GitHubRestResponse(200, "[${repositoryJson()}]") }
        val service = GitHubRepositoryServiceImpl(
            credentialGateway = FakeCredentialGateway(),
            restClient = client,
        )

        val page = assertNotNull(service.list(TEST_CONNECTION_ID).valueOrNull())
        val state = GitHubRepositoryUiState(
            repositories = page.repositories,
            hasMore = page.hasNextPage,
            nextPage = page.nextPageNumber,
            totalCount = page.totalCount,
        )

        val surfaces = listOf(
            page.toString(),
            state.toString(),
            state.displayError,
            page.repositories.single().toString(),
            page.repositories.single().cloneUrl.toString(),
        )
        surfaces.forEach { surface ->
            assertFalse(surface.contains(TEST_TOKEN), "no surface leaks the token: $surface")
        }

        // The one place it is allowed to be, because that is where it is used.
        assertEquals("Bearer $TEST_TOKEN", client.requests.single().headers["Authorization"])
    }

    @Test
    fun `a credential refusal surfaces as a typed error with nothing leaked`() = runBlocking {
        val service = GitHubRepositoryServiceImpl(
            credentialGateway = FakeCredentialGateway(token = null),
            restClient = RecordingGitHubRestClient { GitHubRestResponse(200, "[]") },
        )

        val error = service.list(TEST_CONNECTION_ID).errorOrNull()

        assertIs<GitHubRepositoryError.NoCredential>(error)
        assertFalse(error.toString().contains(TEST_TOKEN))
    }

    @Test
    fun `a failed clone never reaches the network and never deletes a sibling`() = runBlocking {
        val root = temporaryDirectory()
        try {
            val sibling = File(root, "hello-world").also { it.mkdirs() }
            File(sibling, "notes.txt").writeText("keep me")

            val service = JGitGitHubRepositoryCloneService(FakeCredentialGateway())
            val result = service.clone(TEST_CONNECTION_ID, githubRepository(), root)

            assertIs<GitHubRepositoryError.InvalidDestination>(result.errorOrNull())
            assertTrue(sibling.isDirectory, "an existing folder is not deleted")
            assertEquals("keep me", File(sibling, "notes.txt").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    // --- UI state -------------------------------------------------------------

    @Test
    fun `an unauthenticated listing asks for re-authorization instead of a retry`() {
        val state = GitHubRepositoryUiState(
            error = GitHubRepositoryError.Unauthenticated,
            authExpired = true,
        )

        assertEquals("GitHub access needs re-authorization", state.displayError)
        assertFalse(state.recoverableError)
    }

    @Test
    fun `a transient listing failure offers a retry`() {
        assertTrue(GitHubRepositoryUiState(error = GitHubRepositoryError.NetworkFailure).recoverableError)
        assertTrue(GitHubRepositoryUiState(error = GitHubRepositoryError.ServerError(503)).recoverableError)
        assertTrue(GitHubRepositoryUiState(error = GitHubRepositoryError.Forbidden).recoverableError)
        assertFalse(GitHubRepositoryUiState(error = GitHubRepositoryError.RateLimited).recoverableError)

        val empty = GitHubRepositoryUiState()
        assertEquals("", empty.displayError)
    }
}
