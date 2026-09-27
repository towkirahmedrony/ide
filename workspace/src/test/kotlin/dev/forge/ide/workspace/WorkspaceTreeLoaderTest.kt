package dev.forge.ide.workspace

import dev.forge.ide.core.valueOrNull
import dev.forge.ide.workspace.memory.InMemoryWorkspaceBackend
import dev.forge.ide.workspace.memory.InMemoryWorkspaceMetadataStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression tests for the Files page hanging on an endless spinner.
 *
 * The old implementation walked the entire workspace recursively and only left
 * its loading state once the whole repository had been read, so a real project
 * never rendered. These tests pin down the replacement contract: one directory
 * per request, always a terminal state, and never a silent empty result.
 */
class WorkspaceTreeLoaderTest {

    private fun dir(path: String) = WorkspaceDirectory(path = path, name = WorkspacePath.name(path))

    private fun file(path: String) = WorkspaceFile(path = path, name = WorkspacePath.name(path))

    /** A small project tree, plus a deep unexplored branch. */
    private fun project(): FakeWorkspaceFileSystem = FakeWorkspaceFileSystem(
        listings = mapOf(
            WorkspacePath.ROOT to listOf(dir("app"), dir("gradle"), file("README.md")),
            "app" to listOf(dir("app/src"), file("app/build.gradle.kts")),
            "app/src" to listOf(dir("app/src/main")),
            "app/src/main" to listOf(file("app/src/main/Main.kt")),
            "gradle" to listOf(file("gradle/libs.versions.toml")),
        ),
        contents = mapOf(
            "README.md" to "# Project",
            "app/src/main/Main.kt" to "fun main() {}",
            "app/build.gradle.kts" to "plugins { }",
        ),
    )

    // 1. Opening a valid workspace loads the root children.
    @Test
    fun `opening a workspace loads only the root children`() = runBlocking {
        val fileSystem = project()
        val loader = WorkspaceTreeLoader(fileSystem)

        val state = loader.load(WorkspacePath.ROOT)

        assertIs<DirectoryState.Loaded>(state)
        assertEquals(listOf("app", "gradle", "README.md"), state.children.map { it.name })
        assertEquals(listOf(WorkspacePath.ROOT), fileSystem.listCalls)
    }

    // 2. An empty directory reaches Loaded/empty instead of spinning.
    @Test
    fun `an empty directory is loaded and reports no children`() = runBlocking {
        val fileSystem = FakeWorkspaceFileSystem(
            listings = mapOf(WorkspacePath.ROOT to listOf(dir("empty"))),
        )
        val loader = WorkspaceTreeLoader(fileSystem)

        loader.load(WorkspacePath.ROOT)
        val state = loader.load("empty")

        assertEquals(DirectoryState.Loaded(emptyList()), state)
        assertNotEquals(DirectoryState.Loading, loader.stateOf("empty"))
    }

    // 3. A directory that cannot be read reaches Error, never Loading.
    @Test
    fun `a directory that cannot be read reaches an error state`() = runBlocking {
        val denied = WorkspaceError(WorkspaceErrorCode.PERMISSION_DENIED, "denied", "app")
        val fileSystem = FakeWorkspaceFileSystem(
            listings = mapOf(WorkspacePath.ROOT to listOf(dir("app"))),
            failures = mapOf("app" to denied),
        )
        val loader = WorkspaceTreeLoader(fileSystem)

        loader.load(WorkspacePath.ROOT)
        val state = loader.load("app")

        val failure = assertIs<DirectoryState.Failed>(state)
        assertEquals(WorkspaceErrorCode.PERMISSION_DENIED, failure.error.code)
        assertTrue(failure.error.userMessage.isNotBlank())
        assertNotEquals(DirectoryState.Loading, loader.stateOf("app"))
    }

    // 4 + 5. Loading always terminates, on success and on failure.
    @Test
    fun `loading always terminates in a terminal state`() = runBlocking {
        val denied = WorkspaceError(WorkspaceErrorCode.IO_FAILED, "io", "broken")
        val fileSystem = FakeWorkspaceFileSystem(
            listings = mapOf(
                WorkspacePath.ROOT to listOf(dir("ok"), dir("broken")),
                "ok" to listOf(file("ok/file.txt")),
            ),
            failures = mapOf("broken" to denied),
        )
        val loader = WorkspaceTreeLoader(fileSystem)
        loader.load(WorkspacePath.ROOT)

        assertIs<DirectoryState.Loaded>(loader.load("ok"))
        assertIs<DirectoryState.Failed>(loader.load("broken"))

        listOf(WorkspacePath.ROOT, "ok", "broken").forEach { path ->
            assertNotEquals(DirectoryState.Loading, loader.stateOf(path), "stuck loading: $path")
        }
    }

    // A thrown storage exception is also a terminal state (never a stuck spinner).
    @Test
    fun `an exception while reading leaves an error state`() = runBlocking {
        val fileSystem = FakeWorkspaceFileSystem(
            listings = mapOf(WorkspacePath.ROOT to listOf(dir("explodes"))),
            throwers = mapOf("explodes" to IllegalStateException("provider died")),
        )
        val loader = WorkspaceTreeLoader(fileSystem)
        loader.load(WorkspacePath.ROOT)

        val state = loader.load("explodes")

        val failure = assertIs<DirectoryState.Failed>(state)
        assertEquals(WorkspaceTreeLoader.UNREADABLE_FOLDER, failure.error.userMessage)
        assertEquals("provider died", failure.error.cause?.message)
        assertNotEquals(DirectoryState.Loading, loader.stateOf("explodes"))
    }

    // 6. Nested folders load on demand, one level at a time.
    @Test
    fun `nested folders load on demand`() = runBlocking {
        val fileSystem = project()
        val loader = WorkspaceTreeLoader(fileSystem)

        loader.load(WorkspacePath.ROOT)
        val app = assertIs<DirectoryState.Loaded>(loader.load("app"))
        assertEquals(listOf("src", "build.gradle.kts"), app.children.map { it.name })

        val src = assertIs<DirectoryState.Loaded>(loader.load("app/src"))
        assertEquals(listOf("main"), src.children.map { it.name })

        val snapshot = loader.snapshot("project")
        assertEquals("project", snapshot.name)
        val appEntry = snapshot.children.first { it.path == "app" }
        val srcEntry = appEntry.children.first { it.path == "app/src" }
        assertEquals("app/src/main", srcEntry.children.single().path)
        assertEquals(3, fileSystem.listCalls.size)
    }

    // 7. Folder loading never scans the whole repository.
    @Test
    fun `expanding one folder does not scan the rest of the project`() = runBlocking {
        // 60 directories with two files each, plus a deep chain nobody opens.
        val listings = buildMap<String, List<WorkspaceNode>> {
            put(WorkspacePath.ROOT, (1..60).map { dir("module$it") } + file("README.md"))
            (1..60).forEach { index ->
                put("module$index", listOf(file("module$index/src.kt"), file("module$index/build.gradle.kts")))
            }
            put("deep", listOf(dir("deep/one")))
            put("deep/one", listOf(dir("deep/one/two")))
            put("deep/one/two", listOf(file("deep/one/two/buried.kt")))
        }
        val fileSystem = FakeWorkspaceFileSystem(listings = listings)
        val loader = WorkspaceTreeLoader(fileSystem)

        loader.load(WorkspacePath.ROOT)
        assertEquals(listOf(WorkspacePath.ROOT), fileSystem.listCalls)

        loader.load("module7")
        assertEquals(listOf(WorkspacePath.ROOT, "module7"), fileSystem.listCalls)
        assertTrue(
            fileSystem.listCalls.none { it.startsWith("deep") },
            "the loader must not walk folders the user did not open",
        )
        assertEquals(2, loader.loadedDirectoryCount)
    }

    // 10. Recomposition / repeated taps cannot start a second scan.
    @Test
    fun `repeated requests for the same folder list it once`() = runBlocking {
        val fileSystem = project()
        val loader = WorkspaceTreeLoader(fileSystem)
        loader.load(WorkspacePath.ROOT)

        repeat(5) { loader.load("app") }
        loader.beginLoad("app")
        loader.read("app")

        assertEquals(1, fileSystem.listCount("app"))
        assertEquals(2, fileSystem.listCalls.size)
    }

    // 11. Cancellation never leaves a stale loading state.
    @Test
    fun `a cancelled read does not leave the folder loading`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val fileSystem = FakeWorkspaceFileSystem(
            listings = mapOf(WorkspacePath.ROOT to listOf(dir("app"))),
            gates = mapOf("app" to gate),
        )
        val loader = WorkspaceTreeLoader(fileSystem)
        loader.load(WorkspacePath.ROOT)

        assertTrue(loader.beginLoad("app"))
        val job = launch { loader.read("app") }
        while (fileSystem.listCalls.none { it == "app" }) yield()
        assertEquals(DirectoryState.Loading, loader.stateOf("app"))

        job.cancelAndJoin()

        assertEquals(DirectoryState.Unloaded, loader.stateOf("app"))
    }

    @Test
    fun `a cancelled refresh restores the previously loaded folder`() = runBlocking {
        val fileSystem = FakeWorkspaceFileSystem(
            listings = mapOf(WorkspacePath.ROOT to listOf(dir("app"))),
        )
        val loader = WorkspaceTreeLoader(fileSystem)
        loader.load(WorkspacePath.ROOT)
        val loaded = assertIs<DirectoryState.Loaded>(loader.load("app"))

        val gate = CompletableDeferred<Unit>()
        fileSystem.gates = mapOf("app" to gate)
        val job = launch { loader.load("app", force = true) }
        while (fileSystem.listCount("app") < 2) yield()
        assertEquals(DirectoryState.Loading, loader.stateOf("app"))

        job.cancelAndJoin()

        assertEquals(loaded, loader.stateOf("app"))
    }

    // 12. Revoked/invalid access surfaces a visible error, never a spinner.
    @Test
    fun `revoked permission produces a visible error message`() = runBlocking {
        val fileSystem = FakeWorkspaceFileSystem(
            failures = mapOf(
                WorkspacePath.ROOT to WorkspaceError(WorkspaceErrorCode.PERMISSION_DENIED, "denied"),
            ),
        )
        val loader = WorkspaceTreeLoader(fileSystem)

        val state = loader.load(WorkspacePath.ROOT)

        val failure = assertIs<DirectoryState.Failed>(state)
        assertEquals(
            "Access to this workspace was denied or revoked. Open it again to restore access.",
            failure.error.userMessage,
        )
    }

    @Test
    fun `an invalid path fails without touching storage`() = runBlocking {
        val fileSystem = project()
        val loader = WorkspaceTreeLoader(fileSystem)

        val state = loader.load("/etc/passwd")

        assertIs<DirectoryState.Failed>(state)
        assertTrue(fileSystem.listCalls.isEmpty())
    }

    // 9. The loader only ever talks to the workspace abstraction.
    @Test
    fun `an opaque workspace handle is never turned into a filesystem path`() = runBlocking {
        val handle = "content://com.android.externalstorage.documents/tree/primary%3Aproject"
        val manager = DefaultWorkspaceManager(
            backend = InMemoryWorkspaceBackend(
                mapOf("app/src/Main.kt" to "fun main() {}", "README.md" to "# Project"),
            ),
            store = InMemoryWorkspaceMetadataStore(),
        )
        val session = assertNotNull(manager.open(handle).valueOrNull())

        val fileSystem = session.fileSystem
        val loader = WorkspaceTreeLoader(fileSystem)
        val root = assertIs<DirectoryState.Loaded>(loader.load(WorkspacePath.ROOT))

        assertEquals(listOf("app", "README.md"), root.children.map { it.name })

        val app = assertIs<DirectoryState.Loaded>(loader.load("app"))
        assertEquals("app/src", app.children.single().path)

        // The handle stays opaque: only the manager knows how to turn it back
        // into a workspace, and only workspace-relative paths reach storage.
        val recent = assertNotNull(manager.recent().valueOrNull())
        assertEquals(session.workspace.id, recent.single().id)
        assertNotNull(manager.openRecent(session.workspace.id).valueOrNull())

        // Everything the Files page sees is workspace-relative; the content URI
        // itself never becomes a path.
        assertTrue(root.children.all { !it.path.startsWith("/") && !it.path.contains("://") })
        assertTrue(!app.children.single().path.contains("://"))
    }

    @Test
    fun `the root snapshot exposes the runtime state of every folder`() = runBlocking {
        val fileSystem = project()
        val loader = WorkspaceTreeLoader(fileSystem)

        assertEquals(DirectoryState.Unloaded, loader.snapshot().directory)
        loader.load(WorkspacePath.ROOT)

        val snapshot = loader.snapshot("project")
        assertEquals(DirectoryState.Loaded(fileSystem.listings.getValue(WorkspacePath.ROOT)), snapshot.directory)
        val app = snapshot.children.first { it.path == "app" }
        assertEquals(DirectoryState.Unloaded, app.directory)
        assertTrue(app.children.isEmpty())
        assertEquals(WorkspaceEntryKind.FILE, snapshot.children.first { it.path == "README.md" }.kind)
    }
}
