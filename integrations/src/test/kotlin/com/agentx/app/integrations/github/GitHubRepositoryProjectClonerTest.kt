package com.agentx.app.integrations.github

import com.agentx.app.core.ForgeResult
import com.agentx.app.core.errorOrNull
import com.agentx.app.core.failure
import com.agentx.app.core.success
import com.agentx.app.core.valueOrNull
import com.agentx.app.integrations.connection.ConnectionId
import com.agentx.app.workspace.AgentxProjectRoot
import com.agentx.app.workspace.DefaultWorkspaceManager
import com.agentx.app.workspace.FileWorkspaceBackend
import com.agentx.app.workspace.memory.InMemoryWorkspaceMetadataStore
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cloning a GitHub repository and becoming an AgentX project.
 *
 * The transport is faked — no test here reaches the network — but everything downstream of it is
 * real: the same [CloneDestinationValidator] the production clone uses chooses the destination, and
 * the same [DefaultWorkspaceManager] the app runs opens the result. So these cover the two claims
 * that matter: a clone lands at `<AgentX>/<repository name>/`, and it comes back as the active,
 * persisted project with `.git` untouched.
 */
class GitHubRepositoryProjectClonerTest {

    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun tearDown() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    private fun freshDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also { tempDirs += it }

    /**
     * A clone transport that does what the real one does to the filesystem — validates the
     * destination through [CloneDestinationValidator], then writes a working tree with a real
     * `.git` — without a credential or a network.
     */
    private class RecordingCloneService(
        private val validator: CloneDestinationValidator = CloneDestinationValidator(),
        /** When set, the transport refuses the clone instead of writing anything. */
        private val refusal: GitHubRepositoryError? = null,
    ) : GitHubRepositoryCloneService {

        /** The root the cloner handed the transport, so a test can assert where it points. */
        var managedRoot: File? = null
            private set

        override suspend fun clone(
            connectionId: ConnectionId,
            repository: GitHubRepository,
            managedRoot: File,
            branch: String?,
            onProgress: (String) -> Unit,
        ): ForgeResult<String, GitHubRepositoryError> {
            this.managedRoot = managedRoot
            refusal?.let { return failure(it) }

            val destination = when (val validation = validator.validate(managedRoot, repository)) {
                is CloneDestinationValidation.Valid -> validation.directory
                is CloneDestinationValidation.Invalid -> return failure(validation.error)
            }
            destination.mkdirs()
            Git.init().setDirectory(destination).call().close()
            File(destination, "README.md").writeText("# ${repository.fullName}\n")
            onProgress("Cloned ${repository.fullName}")
            return success(destination.path)
        }
    }

    private fun manager() = DefaultWorkspaceManager(
        backend = FileWorkspaceBackend(),
        store = InMemoryWorkspaceMetadataStore(),
    )

    // --- destination ---------------------------------------------------------

    @Test
    fun `a clone lands in the AgentX folder under the repository name`() = runBlocking {
        val base = freshDir("agentx-project-clone")
        val root = AgentxProjectRoot.under(base)
        val transport = RecordingCloneService()
        val cloner = GitHubRepositoryProjectCloner(transport, manager(), root)

        val session = assertNotNull(cloner.cloneAndOpen(TEST_CONNECTION_ID, githubRepository()).valueOrNull())

        // The transport was pointed at the AgentX folder itself, not at app-private storage and not
        // at some second clone location.
        assertEquals(root, transport.managedRoot)
        // `<AgentX>/hello-world/`, never `<AgentX>/octocat-hello-world/`.
        assertEquals(File(root, "hello-world").canonicalPath, session.workspace.metadata.displayLocation)
        assertTrue(File(root, "hello-world").isDirectory)
        assertFalse(File(root, "octocat-hello-world").exists())
        assertEquals(listOf("hello-world"), root.list()?.toList())
    }

    // --- registration --------------------------------------------------------

    @Test
    fun `a cloned repository comes back as the current, persisted project`() = runBlocking {
        val base = freshDir("agentx-project-register")
        val root = AgentxProjectRoot.under(base)
        val workspaces = manager()
        val cloner = GitHubRepositoryProjectCloner(RecordingCloneService(), workspaces, root)

        val session = assertNotNull(cloner.cloneAndOpen(TEST_CONNECTION_ID, githubRepository()).valueOrNull())

        val directory = File(root, "hello-world")
        assertEquals("hello-world", session.workspace.metadata.name)
        assertEquals(directory.canonicalPath, session.workspace.metadata.displayLocation)
        // Made active ...
        assertEquals(session.workspace.id, workspaces.current?.workspace?.id)
        assertEquals(directory.canonicalPath, workspaces.currentHandle)
        // ... and persisted, so it is in the project list exactly like a project created by hand.
        assertEquals(
            listOf(session.workspace.id),
            workspaces.recent().valueOrNull().orEmpty().map { it.id },
        )
    }

    @Test
    fun `the cloned working tree is left exactly as the transport wrote it`() = runBlocking {
        val base = freshDir("agentx-project-tree")
        val root = AgentxProjectRoot.under(base)
        val cloner = GitHubRepositoryProjectCloner(RecordingCloneService(), manager(), root)

        assertNotNull(cloner.cloneAndOpen(TEST_CONNECTION_ID, githubRepository()).valueOrNull())

        val directory = File(root, "hello-world")
        // .git is preserved: opening a workspace reads it, it never rewrites or removes it.
        assertTrue(File(directory, ".git").isDirectory, ".git must survive registration")
        assertTrue(File(directory, ".git/config").isFile)
        assertEquals("# octocat/hello-world\n", File(directory, "README.md").readText())
    }

    @Test
    fun `a second clone of another repository is a sibling project`() = runBlocking {
        val base = freshDir("agentx-project-siblings")
        val root = AgentxProjectRoot.under(base)
        val workspaces = manager()
        val cloner = GitHubRepositoryProjectCloner(RecordingCloneService(), workspaces, root)

        val first = assertNotNull(
            cloner.cloneAndOpen(TEST_CONNECTION_ID, githubRepository(name = "hello-world")).valueOrNull(),
        )
        val second = assertNotNull(
            cloner.cloneAndOpen(TEST_CONNECTION_ID, githubRepository(name = "goodbye-world")).valueOrNull(),
        )

        assertEquals(setOf("hello-world", "goodbye-world"), root.list()?.toSet())
        assertEquals(second.workspace.id, workspaces.current?.workspace?.id)
        assertEquals(
            setOf(first.workspace.id, second.workspace.id),
            workspaces.recent().valueOrNull().orEmpty().map { it.id }.toSet(),
        )
    }

    // --- failures ------------------------------------------------------------

    @Test
    fun `a failed clone registers nothing and leaves nothing active`() = runBlocking {
        val base = freshDir("agentx-project-failed")
        val root = AgentxProjectRoot.under(base)
        val workspaces = manager()
        val cloner = GitHubRepositoryProjectCloner(
            RecordingCloneService(refusal = GitHubRepositoryError.NoCredential),
            workspaces,
            root,
        )

        val result = cloner.cloneAndOpen(TEST_CONNECTION_ID, githubRepository())

        assertIs<GitHubRepositoryError.NoCredential>(result.errorOrNull())
        assertNull(result.valueOrNull())
        assertNull(workspaces.current)
        assertTrue(workspaces.recent().valueOrNull().orEmpty().isEmpty())
        assertFalse(root.exists(), "a failed clone must not create the project folder")
    }

    @Test
    fun `a clone that cannot be opened is reported and never deleted`() = runBlocking {
        val base = freshDir("agentx-project-unopenable")
        val root = AgentxProjectRoot.under(base)
        val workspaces = manager()
        // The transport "clones" a file: something real is on disk, but it is not a project.
        val notADirectory = File(root, "hello-world").apply {
            parentFile?.mkdirs()
            writeText("cloned")
        }
        val cloner = GitHubRepositoryProjectCloner(
            FixedPathCloneService(notADirectory.path),
            workspaces,
            root,
        )

        val error = assertIs<GitHubRepositoryError.Unknown>(
            cloner.cloneAndOpen(TEST_CONNECTION_ID, githubRepository()).errorOrNull(),
        )

        // The failure says where the clone is, so the user is not left with an invisible download.
        assertTrue(error.message.contains(notADirectory.path), error.message)
        assertNull(workspaces.current)
        assertTrue(notADirectory.isFile, "a successful clone is never deleted because opening failed")
        assertEquals("cloned", notADirectory.readText())
    }

    /** Returns a fixed path without touching the filesystem, so opening it is what fails. */
    private class FixedPathCloneService(private val path: String) : GitHubRepositoryCloneService {
        override suspend fun clone(
            connectionId: ConnectionId,
            repository: GitHubRepository,
            managedRoot: File,
            branch: String?,
            onProgress: (String) -> Unit,
        ): ForgeResult<String, GitHubRepositoryError> = success(path)
    }
}
