package com.agentx.app.integrations.github

import com.agentx.app.core.ForgeError
import com.agentx.app.core.ForgeErrorCode
import com.agentx.app.core.errorOrNull
import com.agentx.app.core.valueOrNull
import com.agentx.app.git.GitProject
import com.agentx.app.git.GitProjectProvider
import com.agentx.app.git.GitPushFailure
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.RemoteRefUpdate
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The authenticated push path, without a network.
 *
 * The failure classification and refspec are pure. The transport itself is exercised
 * against a local bare repository — real Git work, no GitHub — and asserted to leave the
 * token out of `.git/config` and never force or delete a ref.
 */
class GitHubRepositoryPushServiceTest {

    private fun temporaryDirectory(): File = Files.createTempDirectory("agentx-push-test").toFile()

    private fun projects(dir: File): GitProjectProvider =
        GitProjectProvider { GitProject(workspaceId = "w1", displayLocation = dir.path, hostPath = dir.path) }

    private fun service(
        dir: File,
        gateway: FakeCredentialGateway = FakeCredentialGateway(),
        connections: GitHubRepositoryConnectionResolver = GitHubRepositoryConnectionResolver { TEST_CONNECTION_ID },
    ) = JGitGitHubRepositoryPushService(
        credentialGateway = gateway,
        connections = connections,
        projects = projects(dir),
    )

    private fun initRepository(dir: File, remoteUrl: String): Git {
        val git = Git.init().setDirectory(dir).call()
        val config = git.repository.config
        config.setString("user", null, "name", "AgentX Test")
        config.setString("user", null, "email", "agentx@example.com")
        config.setString("remote", "origin", "url", remoteUrl)
        config.save()
        // A real first commit, so HEAD resolves and the branch assertions below never
        // depend on how JGit reports an unborn branch.
        File(dir, "README.md").writeText("init\n")
        git.add().addFilepattern("README.md").call()
        git.commit().setMessage("init").call()
        return git
    }

    // --- refspec safety ----------------------------------------------------

    @Test
    fun `the refspec is a plain branch update, never forced or a deletion`() {
        listOf("main", "feature/x").forEach { branch ->
            val spec = JGitGitHubRepositoryPushService.pushRefSpec(branch)

            assertEquals("refs/heads/$branch:refs/heads/$branch", spec)
            assertFalse(spec.startsWith("+"), "a force refspec would start with '+'")
            assertFalse(spec.startsWith(":"), "a deletion refspec would start with ':'")
            assertFalse(spec.endsWith(":"))
        }
    }

    // --- failure classification -------------------------------------------

    @Test
    fun `transport messages map onto structured push failures`() {
        val cases = listOf(
            "rejected - non-fast-forward" to GitPushFailure.NON_FAST_FORWARD,
            "cannot lock ref 'refs/heads/main'" to GitPushFailure.NON_FAST_FORWARD,
            "Authentication is required but no CredentialsProvider has been registered" to
                GitPushFailure.AUTHENTICATION,
            "https://github.com/o/r.git: 401 Unauthorized" to GitPushFailure.AUTHENTICATION,
            "403 Forbidden" to GitPushFailure.AUTHORIZATION,
            "remote: Permission to o/r.git denied" to GitPushFailure.AUTHORIZATION,
            "Repository not found: o/r" to GitPushFailure.REPOSITORY_NOT_FOUND,
            "API rate limit exceeded for user ID 1" to GitPushFailure.RATE_LIMITED,
            "UnknownHostException: github.com" to GitPushFailure.NETWORK,
            "Connection refused: connect" to GitPushFailure.NETWORK,
            "! [remote rejected] main -> main (pre-receive hook declined)" to GitPushFailure.REMOTE_REJECTED,
            "something unexpected" to GitPushFailure.UNKNOWN,
        )

        cases.forEach { (message, expected) ->
            assertEquals(expected, JGitGitHubRepositoryPushService.classifyPushFailure(message), message)
        }
    }

    @Test
    fun `remote update statuses map onto structured push failures`() {
        assertEquals(
            GitPushFailure.NON_FAST_FORWARD,
            JGitGitHubRepositoryPushService.pushFailureFor(RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD),
        )
        assertEquals(
            GitPushFailure.REMOTE_REJECTED,
            JGitGitHubRepositoryPushService.pushFailureFor(RemoteRefUpdate.Status.REJECTED_NODELETE),
        )
        assertTrue(RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD.isRejection())
        assertFalse(RemoteRefUpdate.Status.OK.isRejection())
        assertFalse(RemoteRefUpdate.Status.UP_TO_DATE.isRejection())
    }

    // --- gating ------------------------------------------------------------

    @Test
    fun `a remote url carrying a credential is refused before the gateway is touched`() = runBlocking {
        val dir = temporaryDirectory()
        try {
            val git = initRepository(dir, "https://x-access-token:$TEST_TOKEN@github.com/octocat/hello-world.git")
            git.close()
            val gateway = FakeCredentialGateway()

            val error = service(dir, gateway).push("w1", "main").errorOrNull()

            assertNotNull(error)
            assertEquals(GitPushFailure.REMOTE_NOT_GITHUB, error.failure)
            assertEquals(0, gateway.calls, "a credential is never lent to a credential-bearing URL")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `pushing a branch other than the target main is refused`() = runBlocking {
        val dir = temporaryDirectory()
        try {
            val git = initRepository(dir, "https://github.com/octocat/hello-world.git")
            val branch = assertNotNull(git.repository.branch)
            git.close()
            val gateway = FakeCredentialGateway()
            val other = if (branch == "main") "feature" else "main"

            val error = service(dir, gateway).push("w1", other).errorOrNull()

            assertNotNull(error)
            assertEquals(GitPushFailure.BRANCH_MISMATCH, error.failure)
            assertEquals(0, gateway.calls)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `no connected account is a structured failure and never asks for a credential`() = runBlocking {
        val dir = temporaryDirectory()
        try {
            val git = initRepository(dir, "https://github.com/octocat/hello-world.git")
            val branch = assertNotNull(git.repository.branch)
            git.close()
            val gateway = FakeCredentialGateway()

            val error = service(
                dir,
                gateway,
                connections = GitHubRepositoryConnectionResolver { null },
            ).push("w1", branch).errorOrNull()

            assertNotNull(error)
            assertEquals(GitPushFailure.NO_CONNECTION, error.failure)
            assertEquals(0, gateway.calls)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `an expired credential maps to an authentication failure`() = runBlocking {
        val dir = temporaryDirectory()
        try {
            val git = initRepository(dir, "https://github.com/octocat/hello-world.git")
            val branch = assertNotNull(git.repository.branch)
            git.close()
            val gateway = FakeCredentialGateway(
                refusal = ForgeError(ForgeErrorCode.CONNECTION_CREDENTIAL_EXPIRED, "expired"),
            )

            val error = service(dir, gateway).push("w1", branch).errorOrNull()

            assertNotNull(error)
            assertEquals(GitPushFailure.AUTHENTICATION, error.failure)
            assertEquals(1, gateway.calls)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a call for a workspace that is not the active one is refused`() = runBlocking {
        val dir = temporaryDirectory()
        try {
            val git = initRepository(dir, "https://github.com/octocat/hello-world.git")
            val branch = assertNotNull(git.repository.branch)
            git.close()
            val gateway = FakeCredentialGateway()

            val error = service(dir, gateway).push("someone-else", branch).errorOrNull()

            assertNotNull(error)
            assertEquals(GitPushFailure.WORKSPACE_UNAVAILABLE, error.failure)
            assertEquals(0, gateway.calls)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `an unreachable project is refused without touching the gateway`() = runBlocking {
        val gateway = FakeCredentialGateway()
        val unreachable = JGitGitHubRepositoryPushService(
            credentialGateway = gateway,
            connections = GitHubRepositoryConnectionResolver { TEST_CONNECTION_ID },
            projects = GitProjectProvider {
                GitProject(workspaceId = "w1", displayLocation = "content://x", hostPath = null, available = false)
            },
        )

        val error = unreachable.push("w1", "main").errorOrNull()

        assertNotNull(error)
        assertEquals(GitPushFailure.WORKSPACE_UNAVAILABLE, error.failure)
        assertEquals(0, gateway.calls)
    }

    // --- real (local) transport -------------------------------------------

    @Test
    fun `a push to a local remote succeeds and leaves no credential in git config`() {
        val remoteDir = temporaryDirectory()
        val localDir = temporaryDirectory()
        var local: Git? = null
        try {
            Git.init().setDirectory(remoteDir).setBare(true).call().close()

            val repo = Git.init().setDirectory(localDir).call()
            local = repo
            val config = repo.repository.config
            config.setString("user", null, "name", "AgentX Test")
            config.setString("user", null, "email", "agentx@example.com")
            config.setString("remote", "origin", "url", remoteDir.absolutePath)
            config.save()

            File(localDir, "README.md").writeText("hello\n")
            repo.add().addFilepattern("README.md").call()
            repo.commit().setMessage("init").call()

            val branch = assertNotNull(repo.repository.branch)
            val sha = assertNotNull(repo.repository.resolve("HEAD")).name

            val result = service(localDir).pushWithCredentials(
                git = repo,
                remote = "origin",
                branch = branch,
                commitSha = sha,
                token = TEST_TOKEN,
            )

            val success = assertNotNull(result.valueOrNull())
            assertEquals("origin", success.remote)
            assertEquals(branch, success.branch)
            assertEquals(sha, success.commitSha)
            assertNull(result.errorOrNull())

            // The remote really received the branch, so this was a real push.
            val bare = Git.open(remoteDir)
            try {
                assertNotNull(bare.repository.resolve("refs/heads/$branch"), "the remote should hold the branch")
            } finally {
                bare.close()
            }

            // The transport authenticated with a credentials provider, so the token never
            // reached the repository configuration.
            assertFalse(
                File(localDir, ".git/config").readText().contains(TEST_TOKEN),
                "the credential must never be written into .git/config",
            )
        } finally {
            local?.close()
            remoteDir.deleteRecursively()
            localDir.deleteRecursively()
        }
    }

}
