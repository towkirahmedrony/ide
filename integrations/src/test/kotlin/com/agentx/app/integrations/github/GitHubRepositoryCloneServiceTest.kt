package com.agentx.app.integrations.github

import com.agentx.app.core.ForgeError
import com.agentx.app.core.ForgeErrorCode
import com.agentx.app.core.ForgeResult
import com.agentx.app.integrations.connection.ConnectionId
import com.agentx.app.integrations.connection.ConnectionCredentialGateway
import com.agentx.app.integrations.github.GitHubRepository
import com.agentx.app.integrations.github.GitHubRepositoryCloneUrl
import com.agentx.app.integrations.github.GitHubRepositoryError
import com.agentx.app.integrations.github.GitHubRepositoryVisibility
import com.agentx.app.integrations.github.CloneDestinationValidation
import com.agentx.app.integrations.github.CloneDestinationValidator
import com.agentx.app.integrations.github.JGitGitHubRepositoryCloneService
import com.agentx.app.integrations.github.GitHubRepositoryCloneService
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertInstanceOf
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertThrows

class GitHubRepositoryCloneServiceTest {

    private lateinit var fakeGateway: FakeConnectionCredentialGateway
    private lateinit var validator: CloneDestinationValidator
    private lateinit var service: GitHubRepositoryCloneService
    private val connectionId = ConnectionId(UUID.randomUUID().toString())

    @BeforeEach
    fun setUp() {
        fakeGateway = FakeConnectionCredentialGateway()
        validator = CloneDestinationValidator()
        service = JGitGitHubRepositoryCloneService(
            credentialGateway = fakeGateway,
            validator = validator,
            logger = FakeLogger,
        )
    }

    @Test
    fun `validates a valid repository clone destination`() {
        val tempDir = Files.createTempDirectory("agentx-workspace").toFile()
        tempDir.deleteOnExit()
        val repo = createTestRepository("octocat", "Hello-World")

        val result = validator.validate(managedRoot = tempDir, repository = repo)

        assertTrue(result is CloneDestinationValidation.Valid)
        val valid = result as CloneDestinationValidation.Valid
        assertTrue(valid.path.startsWith(tempDir.absolutePath + File.separator))
        assertTrue(valid.directory.name.contains("octocat-Hello-World"))
    }

    @Test
    fun `rejects path traversal with dot-dot-slash`() {
        val tempDir = Files.createTempDirectory("agentx-workspace").toFile()
        tempDir.deleteOnExit()
        val repo = createTestRepository("evil", "../etc")

        val result = validator.validate(managedRoot = tempDir, repository = repo)

        assertTrue(result is CloneDestinationValidation.Invalid)
        assertInstanceOf<GitHubRepositoryError.MalformedResponse>((result as CloneDestinationValidation.Invalid).error)
    }

    @Test
    fun `rejects absolute path in repository name`() {
        val tempDir = Files.createTempDirectory("agentx-workspace").toFile()
        tempDir.deleteOnExit()
        val repo = createTestRepository("attacker", "/etc/passwd")

        val result = validator.validate(managedRoot = tempDir, repository = repo)

        assertTrue(result is CloneDestinationValidation.Invalid)
    }

    @Test
    fun `rejects null bytes in repository name`() {
        val tempDir = Files.createTempDirectory("agentx-workspace").toFile()
        tempDir.deleteOnExit()
        val repo = createTestRepository("bad", "test\u0000file")

        val result = validator.validate(managedRoot = tempDir, repository = repo)

        assertTrue(result is CloneDestinationValidation.Invalid)
    }

    @Test
    fun `sanitizes repository name with special characters`() {
        val result = validator.sanitizeRepoDirName("owner", "my repo With Spaces!")
        assertNotNull(result)
        assertTrue(result!!.contains("my-repo"))
        assertFalse(result.contains(" "))
    }

    @Test
    fun `sanitizes long names by truncating`() {
        val longName = "a".repeat(200)
        val result = validator.sanitizeRepoDirName("owner", longName)
        assertNotNull(result)
        assertTrue(result!!.length <= 100)
    }

    @Test
    fun `rejects names with control characters`() {
        val result = validator.sanitizeRepoDirName("owner", "test\u0001file")
        assertNull(result)
    }

    @Test
    fun `rejects names with backslashes`() {
        val result = validator.sanitizeRepoDirName("owner", "test\\file")
        assertNull(result)
    }

    @Test
    fun `rejects destination outside managed root via symlink`() {
        val tempDir = Files.createTempDirectory("agentx-workspace").toFile()
        tempDir.deleteOnExit()
        val outsideDir = Files.createTempDirectory("outside-workspace").toFile()
        outsideDir.deleteOnExit()

        val symlink = File(tempDir, "escape-link")
        try {
            Files.createSymbolicLink(symlink.toPath(), outsideDir.toPath())
        } catch (e: Exception) {
            return // Symlinks not supported
        }

        val repo = createTestRepository("test", "repo")
        val result = validator.validate(
            managedRoot = tempDir,
            repository = repo,
            proposedDestination = symlink,
        )

        assertTrue(result is CloneDestinationValidation.Invalid)
    }

    @Test
    fun `rejects destination that already exists as file`() {
        val tempDir = Files.createTempDirectory("agentx-workspace").toFile()
        tempDir.deleteOnExit()
        val existingFile = File(tempDir, "existing-file")
        existingFile.createNewFile()
        existingFile.deleteOnExit()

        val repo = createTestRepository("test", "repo")
        val result = validator.validate(
            managedRoot = tempDir,
            repository = repo,
            proposedDestination = existingFile,
        )

        assertTrue(result is CloneDestinationValidation.Invalid)
        assertInstanceOf<GitHubRepositoryError.InvalidDestination>((result as CloneDestinationValidation.Invalid).error)
    }

    @Test
    fun `token is not present in domain models`() {
        val repo = createTestRepository("octocat", "Hello-World")
        val token = "ghp_super_secret_token_12345"

        assertFalse(repo.toString().contains(token), "Token leaked into repository model")
        assertFalse(repo.toString().contains("Bearer"), "Authorization header in model")
        assertFalse(repo.cloneUrl.url.contains("ghp_"), "Token in clone URL")
        assertFalse(repo.cloneUrl.url.contains("@"), "Credentials in clone URL")
    }

    @Test
    fun `clone URL is always plain HTTPS`() {
        val validUrl = GitHubRepositoryCloneUrl.parse("https://github.com/owner/repo.git")
        assertEquals("https://github.com/owner/repo.git", validUrl.url)

        assertThrows<IllegalArgumentException> {
            GitHubRepositoryCloneUrl.parse("https://user:pass@github.com/owner/repo.git")
        }

        assertThrows<IllegalArgumentException> {
            GitHubRepositoryCloneUrl.parse("https://ghp_token@github.com/owner/repo.git")
        }
    }

    @Test
    fun `authentication failure is mapped correctly`() {
        val error = JGitGitHubRepositoryCloneService().mapGitException(
            object : org.eclipse.jgit.api.errors.GitAPIException("Unauthorized") {
                override val statusCode: Int get() = 401
            }
        )
        assertInstanceOf<GitHubRepositoryError.Unauthenticated>(error)
    }

    @Test
    fun `network failure is mapped correctly`() {
        val error = JGitGitHubRepositoryCloneService().mapGitException(
            object : org.eclipse.jgit.api.errors.GitAPIException("Connection refused") {
                override val statusCode: Int get() = -1
            }
        )
        assertInstanceOf<GitHubRepositoryError.NetworkFailure>(error)
    }

    @Test
    fun `partial clone is cleaned up on failure`() {
        val tempDir = Files.createTempDirectory("agentx-workspace").toFile()
        tempDir.deleteOnExit()
        val partialClone = File(tempDir, "partial-clone")
        partialClone.mkdirs()
        File(partialClone, "some-file.txt").writeText("partial data")
        assertTrue(partialClone.exists())

        JGitGitHubRepositoryCloneService().cleanupClone(partialClone, partialClone.absolutePath)

        assertFalse(partialClone.exists())
    }

    @Test
    fun `cleanup does not delete other directories`() {
        val tempDir = Files.createTempDirectory("agentx-workspace").toFile()
        tempDir.deleteOnExit()
        val partialClone = File(tempDir, "partial-clone")
        partialClone.mkdirs()
        val otherDir = File(tempDir, "other-project")
        otherDir.mkdirs()

        JGitGitHubRepositoryCloneService().cleanupClone(partialClone, partialClone.absolutePath)

        assertFalse(partialClone.exists())
        assertTrue(otherDir.exists())
    }

    @Test
    fun `credential gateway is used for authentication`() = runTest {
        fakeGateway.credentials[connectionId.value] = "test-token-12345"
        var capturedToken: String? = null
        val result = fakeGateway.withCredential(connectionId) { t ->
            capturedToken = t
            "success"
        }

        assertTrue(result is ForgeResult.Success)
        assertEquals("test-token-12345", capturedToken)
    }

    @Test
    fun `credential is not returned from authorization`() = runTest {
        fakeGateway.credentials[connectionId.value] = "test-token-12345"
        var captured: String? = null
        val result = fakeGateway.withCredential(connectionId) { t ->
            captured = t
            Unit
        }

        assertTrue(result is ForgeResult.Success)
        assertEquals("test-token-12345", captured)
        assertFalse(result.toString().contains("test-token-12345"))
    }

    @Test
    fun `UI state reflects loading status`() {
        var state = GitHubRepositoryUiState()
        assertFalse(state.loading)
        state = state.copy(loading = true)
        assertTrue(state.loading)
    }

    @Test
    fun `UI state reflects auth expired`() {
        var state = GitHubRepositoryUiState()
        assertFalse(state.authExpired)
        state = state.copy(authExpired = true)
        assertTrue(state.authExpired)
    }

    @Test
    fun `UI state reflects rate limited`() {
        var state = GitHubRepositoryUiState()
        assertFalse(state.rateLimited)
        state = state.copy(rateLimited = true)
        assertTrue(state.rateLimited)
    }

    @Test
    fun `UI state shows recoverable errors`() {
        assertTrue(GitHubRepositoryUiState(error = GitHubRepositoryError.NetworkFailure).recoverableError)
        assertFalse(GitHubRepositoryUiState(error = GitHubRepositoryError.Unauthenticated).recoverableError)
        assertFalse(GitHubRepositoryUiState(error = GitHubRepositoryError.NotFound).recoverableError)
        assertFalse(GitHubRepositoryUiState(error = null).recoverableError)
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
