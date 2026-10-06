package com.agentx.app.integrations.github

import com.agentx.app.core.errorOrNull
import com.agentx.app.core.valueOrNull
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.GitAPIException
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
 * Clone destination handling and clone failure modes.
 *
 * No test here clones over the network: repository payloads are fixed and the
 * only real Git work is a local `git init`, which touches nothing outside a
 * temporary directory.
 */
class GitHubRepositoryCloneServiceTest {

    private val validator = CloneDestinationValidator()

    private fun newService(gateway: FakeCredentialGateway = FakeCredentialGateway()): JGitGitHubRepositoryCloneService =
        JGitGitHubRepositoryCloneService(gateway)

    private fun temporaryDirectory(): File = Files.createTempDirectory("agentx-clone-test").toFile()

    /** A JGit failure, constructed through a subclass because JGit's own constructors are protected. */
    private class TestGitApiException(message: String) : GitAPIException(message)

    // --- Destination naming ---------------------------------------------------

    @Test
    fun `a repository becomes an owner-name folder`() {
        assertEquals("octocat-hello-world", validator.directoryName("octocat", "hello-world"))
    }

    @Test
    fun `a name that is not one path segment is refused`() {
        val rejected = listOf(
            "" to "repo",
            "octocat" to "",
            "   " to "repo",
            "octocat" to "   ",
            "." to "repo",
            "octocat" to "..",
            ".." to "repo",
            "octocat" to "..",
            "oct/cat" to "repo",
            "octocat" to "hello/world",
            "octocat" to "hello\\world",
            "octocat" to "hello\u0000world",
            "oc\u0007tocat" to "repo",
        )

        rejected.forEach { (owner, name) ->
            assertNull(validator.directoryName(owner, name), "\"$owner\"/\"$name\" must be refused")
        }
    }

    @Test
    fun `an over-long name is truncated to a usable length`() {
        val name = assertNotNull(validator.directoryName("a".repeat(200), "b".repeat(200)))

        assertEquals(CloneDestinationValidator.MAX_DIRECTORY_NAME_LENGTH, name.length)
    }

    // --- Destination containment ---------------------------------------------

    @Test
    fun `a destination is derived under the managed root`() {
        val root = temporaryDirectory()
        try {
            val valid = assertIs<CloneDestinationValidation.Valid>(validator.validate(root, githubRepository()))

            assertEquals(File(root, "octocat-hello-world").canonicalPath, valid.directory.path)
            assertTrue(valid.directory.path.startsWith(root.canonicalPath + File.separator))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `the managed root is created on first use`() {
        val parent = temporaryDirectory()
        val root = File(parent, "nested/projects")
        try {
            assertFalse(root.exists())

            assertIs<CloneDestinationValidation.Valid>(validator.validate(root, githubRepository()))
            assertTrue(root.isDirectory)
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun `a destination that already exists is refused`() {
        val root = temporaryDirectory()
        try {
            File(root, "octocat-hello-world").mkdirs()

            val invalid = assertIs<CloneDestinationValidation.Invalid>(validator.validate(root, githubRepository()))

            assertIs<GitHubRepositoryError.InvalidDestination>(invalid.error)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a symlinked destination cannot escape the root`() {
        val root = temporaryDirectory()
        val outside = temporaryDirectory()
        try {
            Files.createSymbolicLink(File(root, "octocat-hello-world").toPath(), outside.toPath())

            val invalid = assertIs<CloneDestinationValidation.Invalid>(validator.validate(root, githubRepository()))

            assertIs<GitHubRepositoryError.PathTraversal>(invalid.error)
        } finally {
            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test
    fun `a repository whose name tries to walk out of the root is refused`() {
        val root = temporaryDirectory()
        try {
            val invalid = assertIs<CloneDestinationValidation.Invalid>(
                validator.validate(root, githubRepository(name = "../escape")),
            )

            assertIs<GitHubRepositoryError.InvalidDestination>(invalid.error)
            assertFalse(File(root.parentFile, "escape").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    // --- Clone gating ---------------------------------------------------------

    @Test
    fun `an unusable destination is refused before the credential is asked for`() = runBlocking {
        val root = temporaryDirectory()
        try {
            File(root, "octocat-hello-world").mkdirs()
            val gateway = FakeCredentialGateway()
            val service = newService(gateway)

            val result = service.clone(TEST_CONNECTION_ID, githubRepository(), root)

            assertIs<GitHubRepositoryError.InvalidDestination>(result.errorOrNull())
            assertEquals(0, gateway.calls, "the credential is not touched for a refused destination")
            assertNull(result.valueOrNull())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a connection without a credential fails and leaves nothing behind`() = runBlocking {
        val root = temporaryDirectory()
        try {
            File(root, "keep-me").mkdirs()
            val service = newService(FakeCredentialGateway(token = null))

            val result = service.clone(TEST_CONNECTION_ID, githubRepository(), root)

            assertIs<GitHubRepositoryError.NoCredential>(result.errorOrNull())
            assertFalse(File(root, "octocat-hello-world").exists(), "no partial clone is left behind")
            assertTrue(File(root, "keep-me").isDirectory, "an unrelated folder is untouched")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a failed clone leaves the connection usable`() = runBlocking {
        val root = temporaryDirectory()
        try {
            val gateway = FakeCredentialGateway(token = null)
            val service = newService(gateway)

            assertIs<GitHubRepositoryError.NoCredential>(
                service.clone(TEST_CONNECTION_ID, githubRepository(), root).errorOrNull(),
            )
            // The connection was not disconnected or forgotten: the gateway still serves it.
            assertIs<GitHubRepositoryError.NoCredential>(
                service.clone(TEST_CONNECTION_ID, githubRepository(), root).errorOrNull(),
            )
            assertEquals(2, gateway.calls)
        } finally {
            root.deleteRecursively()
        }
    }

    // --- Failure mapping ------------------------------------------------------

    @Test
    fun `JGit failures map onto typed errors`() {
        val service = newService()

        val cases = listOf(
            "https://github.com/octocat/hello-world.git: Authentication is required but no CredentialsProvider has been registered" to
                GitHubRepositoryError.Unauthenticated,
            "https://github.com/octocat/hello-world.git: not authorized" to GitHubRepositoryError.Unauthenticated,
            "https://github.com/octocat/hello-world.git: 403 Forbidden" to GitHubRepositoryError.Forbidden,
            "Repository not found: octocat/hello-world" to GitHubRepositoryError.NotFound,
            "API rate limit exceeded for user ID 1" to GitHubRepositoryError.RateLimited,
            "UnknownHostException: github.com" to GitHubRepositoryError.NetworkFailure,
            "Connection refused: connect" to GitHubRepositoryError.NetworkFailure,
        )

        cases.forEach { (message, expected) ->
            assertEquals(expected, service.mapGitException(TestGitApiException(message)), message)
        }

        assertIs<GitHubRepositoryError.Unknown>(service.mapGitException(TestGitApiException("something odd")))
    }

    // --- Config hygiene -------------------------------------------------------

    @Test
    fun `the remote url is reset and no credential reaches git config`() {
        val directory = temporaryDirectory()
        try {
            val git = Git.init().setDirectory(directory).call()
            try {
                val config = git.repository.config
                config.setString(
                    "remote",
                    "origin",
                    "url",
                    "https://x-access-token:$TEST_TOKEN@github.com/octocat/hello-world.git",
                )
                config.setString("credential", null, "helper", "store")
                config.save()

                assertTrue(File(directory, ".git/config").readText().contains(TEST_TOKEN))

                newService().resetRemoteUrl(git, githubRepository())

                val rewritten = File(directory, ".git/config").readText()
                assertFalse(rewritten.contains(TEST_TOKEN), "the credential is gone from git config")
                assertEquals(
                    "https://github.com/octocat/hello-world.git",
                    config.getString("remote", "origin", "url"),
                )
                assertNull(config.getString("credential", null, "helper"))
            } finally {
                git.close()
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
