package dev.forge.ide.workspace

import dev.forge.ide.core.errorOrNull
import dev.forge.ide.core.failure
import dev.forge.ide.core.valueOrNull
import dev.forge.ide.workspace.memory.InMemoryWorkspaceBackend
import dev.forge.ide.workspace.memory.InMemoryWorkspaceFileSystem
import dev.forge.ide.workspace.memory.InMemoryWorkspaceMetadataStore
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkspaceRuntimeTest {

    // --- path safety -------------------------------------------------------

    @Test
    fun `path normalizes the root and drops dot segments`() {
        assertEquals(WorkspacePath.ROOT, WorkspacePath.normalize("").valueOrNull())
        assertEquals(WorkspacePath.ROOT, WorkspacePath.normalize(".").valueOrNull())
        assertEquals(WorkspacePath.ROOT, WorkspacePath.normalize("/").valueOrNull())
        assertEquals("a/b/c", WorkspacePath.normalize("./a/./b/c").valueOrNull())
        assertEquals("a/b", WorkspacePath.normalize("a\\b").valueOrNull())
    }

    @Test
    fun `path rejects absolute paths`() {
        val error = WorkspacePath.normalize("/etc/passwd").errorOrNull()
        assertEquals(WorkspaceErrorCode.ABSOLUTE_PATH, error?.code)
    }

    @Test
    fun `path rejects traversal`() {
        val error = WorkspacePath.normalize("a/../../etc/passwd").errorOrNull()
        assertEquals(WorkspaceErrorCode.PATH_TRAVERSAL, error?.code)
    }

    @Test
    fun `path child rejects separators and traversal names`() {
        assertEquals("src/main.kt", WorkspacePath.child("src", "main.kt").valueOrNull())
        assertEquals("README.md", WorkspacePath.child(WorkspacePath.ROOT, "README.md").valueOrNull())
        assertEquals(WorkspaceErrorCode.INVALID_PATH, WorkspacePath.child("src", "../evil").errorOrNull()?.code)
        assertEquals(WorkspaceErrorCode.INVALID_PATH, WorkspacePath.child("src", "a/b").errorOrNull()?.code)
    }

    // --- in-memory filesystem ---------------------------------------------

    private fun fileSystem() = InMemoryWorkspaceFileSystem(
        mapOf(
            "README.md" to "root file",
            "src/main.kt" to "fun main() {}",
            "src/util/Strings.kt" to "package util",
        ),
    )

    @Test
    fun `filesystem lists directories before files`() = runBlocking {
        val nodes = fileSystem().list(WorkspacePath.ROOT).valueOrNull()
        assertNotNull(nodes)
        assertEquals(listOf("src", "README.md"), nodes.map { it.name })
        assertTrue(nodes.first() is WorkspaceDirectory)
    }

    @Test
    fun `filesystem reads and writes files`() = runBlocking {
        val fs = fileSystem()
        assertEquals("root file", fs.readFile("README.md").valueOrNull())

        assertTrue(fs.writeFile("README.md", "updated").valueOrNull() == Unit)
        assertEquals("updated", fs.readFile("README.md").valueOrNull())

        assertTrue(fs.exists("src/main.kt"))
        assertFalse(fs.exists("src/missing.kt"))
    }

    @Test
    fun `filesystem creates files and directories`() = runBlocking {
        val fs = fileSystem()
        val file = fs.createFile("src/new.txt").valueOrNull()
        assertNotNull(file)
        assertEquals("new.txt", file.name)
        assertEquals("", fs.readFile("src/new.txt").valueOrNull())

        assertNotNull(fs.createDirectory("assets").valueOrNull())
        assertTrue(fs.exists("assets"))
        assertEquals(WorkspaceErrorCode.ALREADY_EXISTS, fs.createDirectory("assets").errorOrNull()?.code)
    }

    @Test
    fun `filesystem renames and moves entries`() = runBlocking {
        val fs = fileSystem()
        assertNotNull(fs.rename("README.md", "readme.md").valueOrNull())
        assertTrue(fs.exists("readme.md"))
        assertFalse(fs.exists("README.md"))

        assertNotNull(fs.move("src/main.kt", "main.kt").valueOrNull())
        assertTrue(fs.exists("main.kt"))

        // Moving a directory keeps its contents.
        assertNotNull(fs.move("src", "lib").valueOrNull())
        assertTrue(fs.exists("lib/util/Strings.kt"))
        assertFalse(fs.exists("src/main.kt"))
    }

    @Test
    fun `filesystem deletes subtrees`() = runBlocking {
        val fs = fileSystem()
        assertTrue(fs.delete("src").valueOrNull() == Unit)
        assertFalse(fs.exists("src"))
        assertFalse(fs.exists("src/main.kt"))
        assertTrue(fs.exists("README.md"))
    }

    @Test
    fun `filesystem reports missing and invalid paths`() = runBlocking {
        val fs = fileSystem()
        assertEquals(WorkspaceErrorCode.NOT_FOUND, fs.readFile("nope.txt").errorOrNull()?.code)
        assertEquals(WorkspaceErrorCode.PATH_TRAVERSAL, fs.readFile("../secrets").errorOrNull()?.code)
        assertEquals(WorkspaceErrorCode.ABSOLUTE_PATH, fs.readFile("/etc/hosts").errorOrNull()?.code)
        assertEquals(
            WorkspaceErrorCode.NOT_A_DIRECTORY,
            fs.list("README.md").errorOrNull()?.code,
        )
        assertTrue(fs.metadata("src").valueOrNull() is WorkspaceDirectory)
    }

    // --- workspace manager -------------------------------------------------

    private fun manager(backend: WorkspaceBackend = InMemoryWorkspaceBackend()): DefaultWorkspaceManager =
        DefaultWorkspaceManager(
            backend = backend,
            store = InMemoryWorkspaceMetadataStore(),
            clock = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC),
        )

    @Test
    fun `manager opens and remembers a workspace`() = runBlocking {
        val manager = manager()
        val session = manager.open("project-a").valueOrNull()
        assertNotNull(session)
        assertEquals("project-a", session.workspace.metadata.name)
        assertEquals(session.workspace.id, manager.current?.workspace?.id)

        val recent = manager.recent().valueOrNull()
        assertEquals(1, recent?.size)
        assertEquals(session.workspace.id, recent?.first()?.id)
    }

    @Test
    fun `manager restores the last opened workspace`() = runBlocking {
        val manager = manager()
        manager.open("project-a")
        manager.close()
        assertNull(manager.current)

        val restored = manager.restoreLastOpened()
        assertNotNull(restored)
        assertEquals("project-a", restored.valueOrNull()?.workspace?.metadata?.name)
    }

    @Test
    fun `manager forgets a workspace`() = runBlocking {
        val manager = manager()
        val id = manager.open("project-a").valueOrNull()?.workspace?.id
        assertNotNull(id)

        assertTrue(manager.forget(id).valueOrNull() == Unit)
        assertTrue(manager.recent().valueOrNull().orEmpty().isEmpty())
        assertNull(manager.restoreLastOpened())
        assertNull(manager.current)
    }

    @Test
    fun `manager reopens a recent workspace by id`() = runBlocking {
        val manager = manager()
        val id = manager.open("project-a").valueOrNull()?.workspace?.id
        assertNotNull(id)
        manager.close()

        val reopened = manager.openRecent(id).valueOrNull()
        assertEquals("project-a", reopened?.workspace?.metadata?.name)
    }

    @Test
    fun `manager rejects a blank handle`() = runBlocking {
        val manager = manager()
        assertEquals(WorkspaceErrorCode.INVALID_HANDLE, manager.open("   ").errorOrNull()?.code)
    }

    @Test
    fun `manager propagates backend failures`() = runBlocking {
        val failing = object : WorkspaceBackend {
            override suspend fun open(handle: String): WorkspaceResult<Workspace> =
                failure(WorkspaceError(WorkspaceErrorCode.PERMISSION_DENIED, "denied"))
        }
        val manager = manager(failing)
        assertEquals(WorkspaceErrorCode.PERMISSION_DENIED, manager.open("x").errorOrNull()?.code)
    }

    @Test
    fun `manager returns null when no workspace was remembered`() = runBlocking {
        assertNull(manager().restoreLastOpened())
    }

    // --- process abstraction ----------------------------------------------

    @Test
    fun `stub process executor never runs a command`() = runBlocking {
        val executor = StubProcessExecutor()
        assertFalse(executor.allowsArbitraryExecution)

        val result = executor.execute(ProcessRequest(command = "rm", arguments = listOf("-rf", "/")))
        assertEquals(ProcessState.FAILED, result.state)
        assertEquals(WorkspaceErrorCode.PROCESS_EXECUTION_UNAVAILABLE, result.error?.code)
        assertFalse(result.isSuccess)
    }
}
