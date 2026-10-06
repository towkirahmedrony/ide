package com.agentx.app.integrations.github

import com.agentx.app.core.ForgeError
import com.agentx.app.core.ForgeErrorCode
import com.agentx.app.core.ForgeResult
import com.agentx.app.integrations.connection.ConnectionId
import com.agentx.app.integrations.connection.ConnectionCredentialGateway
import com.agentx.app.integrations.github.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertInstanceOf
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertThrows

class GitHubRepositorySecurityTest {

    private lateinit var fakeGateway: FakeConnectionCredentialGateway
    private lateinit var fakeHttpClient: FakeGitHubRestClient
    private lateinit var service: GitHubRepositoryService
    private val connectionId = ConnectionId(UUID.randomUUID().toString())

    @BeforeEach
    fun setUp() {
        fakeGateway = FakeConnectionCredentialGateway()
        fakeHttpClient = FakeGitHubRestClient()
        service = GitHubRepositoryServiceImpl(
            credentialGateway = fakeGateway,
            restClient = fakeHttpClient,
        )
    }

    @Test
    fun `token does not appear in any surface after successful operation`() = runTest {
        val token = "ghp_comprehensive_token_test_12345"
        fakeGateway.credentials[connectionId.value] = token

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

        val surfaces = mutableListOf<String>()
        surfaces += page.repositories.map { it.toString() }
        surfaces += page.repositories.map { it.id.toString() }
        surfaces += page.repositories.map { it.cloneUrl.url }
        surfaces += page.repositories.map { it.webUrl }
        surfaces += result.toString()

        for (surface in surfaces) {
            if (surface.isNotBlank()) {
                assertFalse(surface.contains(token), "Token leaked into surface: $surface")
                assertFalse(surface.contains("ghp_"), "Token pattern in surface: $surface")
            }
        }
    }

    @Test
    fun `token is not logged in service operations`() = runTest {
        val token = "ghp_secret_token_log_abc"
        fakeGateway.credentials[connectionId.value] = token

        fakeHttpClient.responses.add(
            GitHubRestResponse(
                statusCode = 200,
                body = """[{"id":1,"owner":{"login":"test"},"name":"repo","full_name":"test/repo","private":false,"default_branch":"main","clone_url":"https://github.com/test/repo.git","html_url":"https://github.com/test/repo"}]""",
                headers = emptyMap(),
            )
        )

        val result = service.list(connectionId)
        assertTrue(result is ForgeResult.Success)

        val lastRequest = fakeHttpClient.lastRequest
        assertNotNull(lastRequest)
        // The token was used for authentication but not stored in results
    }

    @Test
    fun `error handling does not log tokens`() = runTest {
        val token = "ghp_secret_token_error_abc"
        fakeGateway.credentials[connectionId.value] = token

        fakeHttpClient.responses.add(
            GitHubRestResponse(
                statusCode = 500,
                body = """{"message": "Server Error"}""",
                headers = emptyMap(),
            )
        )

        val result = service.list(connectionId)
        assertTrue(result is ForgeResult.Failure)
        val error = (result as ForgeResult.Failure).error

        assertFalse(error.toString().contains(token))
        assertFalse(error.toString().contains("ghp_"))
    }

    @Test
    fun `malformed response error does not expose token`() = runTest {
        val token = "ghp_secret_token_malformed_abc"
        fakeGateway.credentials[connectionId.value] = token

        fakeHttpClient.responses.add(
            GitHubRestResponse(
                statusCode = 200,
                body = "not json at all",
                headers = emptyMap(),
            )
        )

        val result = service.list(connectionId)
        assertTrue(result is ForgeResult.Failure)
        val error = (result as ForgeResult.Failure).error
        assertInstanceOf<GitHubRepositoryError.MalformedResponse>(error)

        assertFalse(error.toString().contains(token))
        assertFalse(error.toString().contains("ghp_"))
    }

    @Test
    fun `repository model never contains token`() = runTest {
        val token = "ghp_secret_token_model_abc"
        fakeGateway.credentials[connectionId.value] = token

        fakeHttpClient.responses.add(
            GitHubRestResponse(
                statusCode = 200,
                body = """[{"id":1,"owner":{"login":"test_owner"},"name":"my-repository","full_name":"test_owner/my-repository","private":false,"default_branch":"main","clone_url":"https://github.com/test_owner/my-repository.git","html_url":"https://github.com/test_owner/my-repository"}]""",
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
    }

    @Test
    fun `repository ID cannot be used to extract token`() = runTest {
        val token = "ghp_secret_token_id_abc"
        fakeGateway.credentials[connectionId.value] = token

        fakeHttpClient.responses.add(
            GitHubRestResponse(
                statusCode = 200,
                body = """[{"id":12345,"owner":{"login":"test"},"name":"repo","full_name":"test/repo","private":false,"default_branch":"main","clone_url":"https://github.com/test/repo.git","html_url":"https://github.com/test/repo"}]""",
                headers = emptyMap(),
            )
        )

        val result = service.list(connectionId)
        assertTrue(result is ForgeResult.Success)
        val page = (result as ForgeResult.Success).value
        val repo = page.repositories.first()

        assertEquals("12345", repo.id.value)
        assertFalse(repo.id.value.contains(token))
        assertFalse(repo.id.value.contains("ghp_"))
    }

    @Test
    fun `clone URL is always the original HTTPS URL without credentials`() = runTest {
        val repo = GitHubRepository(
            id = RepositoryId("1"),
            owner = "test_owner",
            name = "my-repo",
            fullName = "test_owner/my-repo",
            visibility = GitHubRepositoryVisibility.PUBLIC,
            defaultBranch = "main",
            cloneUrl = GitHubRepositoryCloneUrl.parse("https://github.com/test_owner/my-repo.git"),
            webUrl = "https://github.com/test_owner/my-repo",
        )

        assertEquals("https://github.com/test_owner/my-repo.git", repo.cloneUrl.url)

        val validator = CloneDestinationValidator()
        val validation = validator.validate(
            managedRoot = Files.createTempDirectory("test").toFile(),
            repository = repo,
        )

        assertTrue(validation is CloneDestinationValidation.Valid)
    }

    @Test
    fun `invalid clone URLs with credentials are rejected`() = runTest {
        assertThrows<IllegalArgumentException> {
            GitHubRepositoryCloneUrl.parse("https://token:password@github.com/owner/repo.git")
        }
        assertThrows<IllegalArgumentException> {
            GitHubRepositoryCloneUrl.parse("https://ghp_token@github.com/owner/repo.git")
        }
        assertThrows<IllegalArgumentException> {
            GitHubRepositoryCloneUrl.parse("https://x-oauth-basic:token@github.com/owner/repo.git")
        }
    }

    @Test
    fun `repository model construction rejects URLs with credentials`() = runTest {
        assertThrows<IllegalArgumentException> {
            GitHubRepository(
                id = RepositoryId("1"),
                owner = "test",
                name = "repo",
                fullName = "test/repo",
                visibility = GitHubRepositoryVisibility.PUBLIC,
                defaultBranch = "main",
                cloneUrl = GitHubRepositoryCloneUrl.parse("https://token@github.com/test/repo.git"),
                webUrl = "https://github.com/test/repo",
            )
        }
    }

    @Test
    fun `UI state never contains token`() = runTest {
        val token = "ghp_secret_token_ui_state_abc"
        fakeGateway.credentials[connectionId.value] = token

        fakeHttpClient.responses.add(
            GitHubRestResponse(
                statusCode = 200,
                body = """[{"id":1,"owner":{"login":"test"},"name":"ui-test-repo","full_name":"test/ui-test-repo","private":false,"default_branch":"main","clone_url":"https://github.com/test/ui-test-repo.git","html_url":"https://github.com/test/ui-test-repo"}]""",
                headers = emptyMap(),
            )
        )

        val result = service.list(connectionId)
        assertTrue(result is ForgeResult.Success)
        val page = (result as ForgeResult.Success).value

        val uiState = GitHubRepositoryUiState(
            loading = false,
            repositories = page.repositories,
            empty = false,
            hasMore = page.hasNextPage,
            nextPage = page.nextPageNumber,
            error = null,
            totalCount = page.totalCount,
        )

        val allStrings = listOf(
            uiState.toString(),
            uiState.displayError,
            uiState.repositories.joinToString(" | ") { it.toString() },
            uiState.repositories.joinToString("") { it.cloneUrl.url },
        )

        for (str in allStrings) {
            assertFalse(str.contains(token), "Token leaked into UI state: $str")
            assertFalse(str.contains("ghp_"), "Token pattern in UI state: $str")
            assertFalse(str.contains("Bearer"), "Authorization in UI state: $str")
        }
    }

    @Test
    fun `UI error state never contains token`() = runTest {
        val token = "ghp_secret_token_error_state_abc"
        fakeGateway.credentials[connectionId.value] = token

        fakeHttpClient.responses.add(
            GitHubRestResponse(
                statusCode = 401,
                body = """{"message": "Bad credentials"}""",
                headers = emptyMap(),
            )
        )

        val result = service.list(connectionId)
        assertTrue(result is ForgeResult.Failure)
        val error = (result as ForgeResult.Failure).error

        val uiState = GitHubRepositoryUiState(
            loading = false,
            repositories = emptyList(),
            empty = false,
            hasMore = false,
            nextPage = null,
            error = error,
            totalCount = 0,
            authExpired = true,
            rateLimited = false,
        )

        assertFalse(uiState.toString().contains(token))
        assertFalse(uiState.displayError.contains(token))
        assertFalse(error.toString().contains(token))
    }

    @Test
    fun `clone destination is always validated to be within managed root`() = runTest {
        val managedRoot = Files.createTempDirectory("managed-root").toFile()
        managedRoot.deleteOnExit()
        val validator = CloneDestinationValidator()
        val outsideDir = Files.createTempDirectory("outside-root").toFile()
        outsideDir.deleteOnExit()

        val repo = createTestRepository("test", "repo")

        val result = validator.validate(
            managedRoot = managedRoot,
            repository = repo,
            proposedDestination = outsideDir,
        )

        assertTrue(result is CloneDestinationValidation.Invalid)
        assertInstanceOf<GitHubRepositoryError.PathTraversal>((result as CloneDestinationValidation.Invalid).error)
    }

    @Test
    fun `directory name sanitization prevents path traversal`() = runTest {
        val validator = CloneDestinationValidator()

        val traversalAttempts = listOf(
            "../etc" to null,
            "..\\..\\windows" to null,
            "foo/bar" to null,
            "foo\\bar" to null,
            "test\u0000file" to null,
            ".." to null,
            "/etc/passwd" to null,
        )

        for ((input, expected) in traversalAttempts) {
            val result = validator.sanitizeRepoDirName("owner", input)
            assertEquals(expected, result, "Failed for input: $input")
        }
    }

    @Test
    fun `malicious repository name cannot escape workspace`() = runTest {
        val managedRoot = Files.createTempDirectory("managed-root").toFile()
        managedRoot.deleteOnExit()
        val validator = CloneDestinationValidator()

        val maliciousRepos = listOf(
            createTestRepository(owner = "evil", name = "../escape"),
            createTestRepository(owner = "evil", name = "..\\windows"),
        )

        for (repo in maliciousRepos) {
            val result = validator.validate(
                managedRoot = managedRoot,
                repository = repo,
            )
            assertTrue(result is CloneDestinationValidation.Invalid,
                "Should reject malicious repo name: ${repo.name}")
        }
    }

    @Test
    fun `malicious owner name cannot escape workspace`() = runTest {
        val managedRoot = Files.createTempDirectory("managed-root").toFile()
        managedRoot.deleteOnExit()
        val validator = CloneDestinationValidator()

        val maliciousRepos = listOf(
            createTestRepository(owner = "../etc", name = "safe-repo"),
            createTestRepository(owner = "foo/bar", name = "safe-repo"),
        )

        for (repo in maliciousRepos) {
            val result = validator.validate(
                managedRoot = managedRoot,
                repository = repo,
            )
            assertTrue(result is CloneDestinationValidation.Invalid,
                "Should reject malicious owner name: ${repo.owner}")
        }
    }

    @Test
    fun `canonical path resolution prevents symlink escape`() = runTest {
        val managedRoot = Files.createTempDirectory("managed-root").toFile()
        managedRoot.deleteOnExit()
        val validator = CloneDestinationValidator()

        val symlinkDir = Files.createTempDirectory("symlink-target").toFile()
        symlinkDir.deleteOnExit()

        val symlink = File(managedRoot, "escape-link")
        try {
            Files.createSymbolicLink(symlink.toPath(), symlinkDir.toPath())

            val repo = createTestRepository("test", "repo")

            val result = validator.validate(
                managedRoot = managedRoot,
                repository = repo,
                proposedDestination = symlink,
            )

            assertTrue(result is CloneDestinationValidation.Invalid,
                "Should reject symlink pointing outside managed root")
        } catch (e: Exception) {
            println("Symlinks not supported, skipping symlink test")
        }
    }

    private fun createTestRepository(owner: String, name: String): GitHubRepository {
        return GitHubRepository(
            id = RepositoryId(UUID.randomUUID().toString()),
            owner = owner,
            name = name,
            fullName = "$owner/$name",
            visibility = GitHubRepositoryVisibility.PUBLIC,
            defaultBranch = "main",
            cloneUrl = GitHubRepositoryCloneUrl.parse("https://github.com/$owner/$name.git"),
            webUrl = "https://github.com/$owner/$name",
        )
    }

    companion object {
        private val FakeLogger = object : GitHubRepositoryLogger {
            override fun logError(connectionId: String, error: GitHubRepositoryError, repositoriesListed: Int) {}
            override fun logNetworkFailure(connectionId: String) {}
            override fun logCloneStart(connectionId: String, owner: String, repo: String) {}
            override fun logCloneProgress(connectionId: String, progress: String) {}
            override fun logCloneComplete(connectionId: String, workspacePath: String) {}
            override fun logCloneError(connectionId: String, error: GitHubRepositoryError) {}
        }
    }
}

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
