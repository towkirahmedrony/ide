package com.agentx.app.workspace

import com.agentx.app.core.errorOrNull
import com.agentx.app.core.valueOrNull
import com.agentx.app.workspace.memory.InMemoryWorkspaceMetadataStore
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 7: creating a new empty project in AgentX-managed storage and making it the active
 * workspace. Covers validation, the container-only contents, active registration, persistence and
 * the guarantee that a failure never becomes active.
 */
class ProjectCreationTest {

    private val tempDirs = mutableListOf<File>()

    private fun freshDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also { tempDirs += it }

    @AfterTest
    fun tearDown() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    private fun manager(
        root: File,
        store: WorkspaceMetadataStore = InMemoryWorkspaceMetadataStore(),
    ): DefaultWorkspaceManager = DefaultWorkspaceManager(
        backend = FileWorkspaceBackend(),
        store = store,
        projects = ManagedProjectDirectory(root),
    )

    // --- valid creation ----------------------------------------------------

    @Test
    fun `a created project is an empty directory in managed storage`() {
        val root = freshDir("agentx-managed")
        val created = ManagedProjectDirectory(root).create("MyProject").valueOrNull()

        assertNotNull(created)
        assertEquals(File(root.canonicalFile, "MyProject").path, created)

        val directory = File(created)
        assertTrue(directory.isDirectory)
        // Only the container: no template, README, source files or Git repository is generated.
        assertEquals(emptyList(), directory.list()?.toList())
        assertFalse(File(directory, ".git").exists())
        assertFalse(File(directory, "README.md").exists())
    }

    @Test
    fun `project names are trimmed to a single safe segment`() {
        assertEquals("MyProject", ProjectName.validate("MyProject").valueOrNull())
        assertEquals("My Project", ProjectName.validate("  My Project  ").valueOrNull())
        assertEquals("my-app_2", ProjectName.validate("my-app_2").valueOrNull())
    }

    // --- name validation ---------------------------------------------------

    @Test
    fun `empty project names are rejected`() {
        assertEquals(WorkspaceErrorCode.INVALID_PROJECT_NAME, ProjectName.validate("").errorOrNull()?.code)
        assertEquals(WorkspaceErrorCode.INVALID_PROJECT_NAME, ProjectName.validate("   ").errorOrNull()?.code)
    }

    @Test
    fun `path traversal and separator names are rejected`() {
        val rejected = listOf("..", ".", "...", "../evil", "a/b", "a\\b", "/etc/passwd", "a\u0000b")
        for (name in rejected) {
            assertEquals(
                WorkspaceErrorCode.INVALID_PROJECT_NAME,
                ProjectName.validate(name).errorOrNull()?.code,
                "expected \"$name\" to be rejected",
            )
        }
    }

    @Test
    fun `a name can never escape the managed projects folder`() {
        val root = freshDir("agentx-contained")
        val projects = ManagedProjectDirectory(root)

        for (name in listOf("..", "../escape", "a/b", "/tmp/escape", "a\\b")) {
            assertEquals(
                WorkspaceErrorCode.INVALID_PROJECT_NAME,
                projects.create(name).errorOrNull()?.code,
                "expected \"$name\" to be rejected",
            )
        }

        // Nothing was created beside or above the managed root.
        assertEquals(0, root.list()?.size)
        assertFalse(File(root.parentFile, "escape").exists())
    }

    // --- duplicates --------------------------------------------------------

    @Test
    fun `creating an existing project is rejected and never overwrites it`() = runBlocking {
        val root = freshDir("agentx-duplicate")
        val manager = manager(root)

        val first = manager.createProject("MyProject").valueOrNull()
        assertNotNull(first)
        File(first.workspace.metadata.displayLocation, "keep.txt").writeText("mine")

        val duplicate = manager.createProject("MyProject")
        assertEquals(WorkspaceErrorCode.PROJECT_ALREADY_EXISTS, duplicate.errorOrNull()?.code)
        assertEquals(first.workspace.id, manager.current?.workspace?.id)
        assertEquals("mine", File(first.workspace.metadata.displayLocation, "keep.txt").readText())
    }

    // --- active registration + persistence ---------------------------------

    @Test
    fun `creating a project registers it as active and persists it`() = runBlocking {
        val root = freshDir("agentx-active")
        val store = InMemoryWorkspaceMetadataStore()
        val manager = manager(root, store)

        val session = manager.createProject("MyProject").valueOrNull()
        assertNotNull(session)
        assertEquals("MyProject", session.workspace.metadata.name)
        assertEquals(session.workspace.id, manager.current?.workspace?.id)
        assertEquals(File(root.canonicalFile, "MyProject").path, manager.currentHandle)
        assertEquals(session.workspace.id, store.lastOpenedId())

        // A fresh manager over the same store restores it, exactly as reopening AgentX does.
        val restored = manager(root, store).restoreLastOpened()
        assertNotNull(restored)
        assertEquals("MyProject", restored.valueOrNull()?.workspace?.metadata?.name)
    }

    // --- failures never become active --------------------------------------

    @Test
    fun `a failed creation does not become the active project`() = runBlocking {
        val root = freshDir("agentx-fail")
        val manager = manager(root)

        // Nothing is open, and an invalid name cannot create one.
        assertEquals(
            WorkspaceErrorCode.INVALID_PROJECT_NAME,
            manager.createProject("../escape").errorOrNull()?.code,
        )
        assertNull(manager.current)

        // A valid project becomes active ...
        val active = manager.createProject("First").valueOrNull()
        assertNotNull(active)
        assertEquals(active.workspace.id, manager.current?.workspace?.id)

        // ... and a duplicate leaves it active rather than replacing it.
        assertEquals(
            WorkspaceErrorCode.PROJECT_ALREADY_EXISTS,
            manager.createProject("First").errorOrNull()?.code,
        )
        assertEquals(active.workspace.id, manager.current?.workspace?.id)
    }

    @Test
    fun `unavailable storage is reported instead of crashing`() = runBlocking {
        val notADirectory = File(freshDir("agentx-storage"), "file").apply { writeText("not a dir") }
        assertTrue(notADirectory.isFile)

        val manager = manager(notADirectory)
        assertEquals(
            WorkspaceErrorCode.PROJECT_STORAGE_UNAVAILABLE,
            manager.createProject("MyProject").errorOrNull()?.code,
        )
        assertNull(manager.current)
    }

    @Test
    fun `without managed storage creation is unsupported and nothing becomes active`() = runBlocking {
        val manager = DefaultWorkspaceManager(
            backend = FileWorkspaceBackend(),
            store = InMemoryWorkspaceMetadataStore(),
        )

        assertEquals(
            WorkspaceErrorCode.UNSUPPORTED_OPERATION,
            manager.createProject("MyProject").errorOrNull()?.code,
        )
        assertNull(manager.current)
    }
}
