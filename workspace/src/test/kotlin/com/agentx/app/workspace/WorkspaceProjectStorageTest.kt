package com.agentx.app.workspace

import com.agentx.app.core.errorOrNull
import com.agentx.app.core.failure
import com.agentx.app.core.success
import com.agentx.app.core.valueOrNull
import com.agentx.app.workspace.memory.InMemoryWorkspaceFileSystem
import com.agentx.app.workspace.memory.InMemoryWorkspaceMetadataStore
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What deleting a project is allowed to remove.
 *
 * These run against a real temporary directory laid out like app-private storage, so every claim is
 * about bytes on disk: what survives, what goes, and — the part that matters most — what the
 * cleanup can never reach however the names or handles are crafted.
 *
 * The per-project directory names themselves come from `TermuxWorkspaceBindings` in the Android
 * composition root; `:workspace` cannot depend on that module, so the names are injected here the
 * same way the root injects them. The naming functions' own safety is covered by
 * `TermuxWorkspaceBindingsTest`.
 */
class WorkspaceProjectStorageTest {

    private val safHandle = "content://com.android.externalstorage.documents/tree/primary%3AMyProject"
    private val safId = "saf-1a2b3c4d"

    /** Stands in for `context.filesDir`; everything the app owns lives under it. */
    private lateinit var filesDir: File

    /** The Ubuntu runtime's project-copy root: the one place a SAF project is duplicated. */
    private lateinit var developerWorkspaces: File

    /** The legacy Termux mirror root: the other place a project copy can appear. */
    private lateinit var termuxWorkspaces: File

    @BeforeTest
    fun setUp() {
        filesDir = Files.createTempDirectory("agentx-project-storage").toFile()
        developerWorkspaces = File(filesDir, "developer-runtime/workspaces").apply { mkdirs() }
        termuxWorkspaces = File(filesDir, "workspaces").apply { mkdirs() }

        // The shared developer runtime, next to the project copies. One runtime serves every
        // project, so no project delete may touch any of it.
        File(filesDir, "developer-runtime/rootfs/bin").apply { mkdirs() }
        File(filesDir, "developer-runtime/rootfs/bin/bash").writeText("#!/bin/sh\n")
        File(filesDir, "developer-runtime/downloads").apply { mkdirs() }
        File(filesDir, "developer-runtime/downloads/ubuntu.tar.gz").writeText("archive")
        File(filesDir, "developer-runtime/tmp").apply { mkdirs() }
        File(filesDir, "developer-runtime/tmp/proot-scratch").writeText("scratch")
    }

    @AfterTest
    fun tearDown() {
        filesDir.deleteRecursively()
    }

    // --- fixtures ----------------------------------------------------------

    private fun safRecord(
        id: String = safId,
        handle: String = safHandle,
        name: String = "My Project",
    ) = WorkspaceRecord(
        metadata = WorkspaceMetadata(
            id = WorkspaceId(id),
            name = name,
            displayLocation = safHandle,
            persisted = true,
        ),
        handle = handle,
    )

    /** The two names the Android composition root supplies for one project. */
    private fun naming(vararg names: String): ProjectDirectoryNaming =
        ProjectDirectoryNaming { names.toList() }

    /** A real directory standing in for a project copy AgentX made. */
    private fun createCopy(name: String, vararg contents: String): File {
        val directory = File(developerWorkspaces, name)
        for (file in contents) {
            val target = File(directory, file)
            target.parentFile?.mkdirs()
            target.writeText("copy of $name/$file")
        }
        return directory
    }

    private fun storageOver(
        naming: ProjectDirectoryNaming,
        roots: List<String> = listOf(developerWorkspaces.path, termuxWorkspaces.path),
    ): OwnedWorkspaceProjectStorage = OwnedWorkspaceProjectStorage(
        ownedRoots = roots,
        naming = naming,
    )

    // --- what a project delete removes -------------------------------------

    @Test
    fun `a project's owned copy under each root is removed`() {
        val record = safRecord()
        val ubuntu = createCopy("myproject-1a2b3c4d", "src/Main.kt", "README.md")
        val termux = File(termuxWorkspaces, safId).apply { mkdirs() }
        File(termux, "notes.md").writeText("mirror")

        val report = storageOver(naming("myproject-1a2b3c4d", safId)).remove(record)

        assertTrue(report.ok, "a clean removal must not report failures: ${report.failed}")
        assertEquals(
            setOf(ubuntu.path, termux.path),
            report.removed.toSet(),
            "both the materialiser copy and the legacy mirror belong to this project",
        )
        assertFalse(ubuntu.exists(), "the project's own copy must be gone")
        assertFalse(termux.exists())
    }

    @Test
    fun `only the project's own directories are removed`() {
        val record = safRecord()
        createCopy("myproject-1a2b3c4d", "src/Main.kt")
        val other = createCopy("otherproject-9999", "src/Other.kt")

        storageOver(naming("myproject-1a2b3c4d", safId)).remove(record)

        assertTrue(other.exists(), "another project's copy must survive")
        assertTrue(File(other, "src/Other.kt").isFile)
    }

    @Test
    fun `a project with no copy of its own removes nothing and still succeeds`() {
        val record = safRecord()
        createCopy("unrelated-0000", "src/File.kt")

        val storage = storageOver(naming("myproject-1a2b3c4d", safId))
        assertEquals(emptyList(), storage.ownedLocations(record))

        val report = storage.remove(record)
        assertTrue(report.ok)
        assertTrue(report.isEmpty, "nothing to remove is not a failure: ${report.removed}")
        assertTrue(File(developerWorkspaces, "unrelated-0000").exists())
    }

    // --- what a project delete must never reach ----------------------------

    @Test
    fun `a SAF project folder is never touched by managed-project cleanup`() {
        // The user's folder, as the app sees it: a directory it may read but does not own. The
        // cleanup is given the SAF tree URI as both the handle and the display location — the
        // strongest form of the temptation to "just delete the project".
        val userFolder = File(filesDir, "storage/emulated/0/MyProject").apply { mkdirs() }
        val userFile = File(userFolder, "important.txt").apply { writeText("the user's work") }
        val record = safRecord(handle = "content://com.android.externalstorage.documents/tree/primary%3AMyProject")

        val projectCopy = createCopy("myproject-1a2b3c4d", "src/Main.kt")

        val report = storageOver(naming("myproject-1a2b3c4d", safId)).remove(record)

        assertEquals(listOf(projectCopy.path), report.removed)
        assertTrue(userFolder.isDirectory, "the folder the user opened must survive")
        assertEquals("the user's work", userFile.readText())
        assertTrue(
            File(filesDir, "storage/emulated/0").isDirectory,
            "a SAF tree and the storage it lives in are never a delete target",
        )
    }

    @Test
    fun `the shared runtime survives a project delete`() {
        val record = safRecord()
        createCopy("myproject-1a2b3c4d", "src/Main.kt")

        storageOver(naming("myproject-1a2b3c4d", safId)).remove(record)

        val runtime = File(filesDir, "developer-runtime")
        assertTrue(File(runtime, "rootfs/bin/bash").isFile, "the shared rootfs must survive")
        assertTrue(File(runtime, "downloads/ubuntu.tar.gz").isFile, "the cached archive must survive")
        assertTrue(File(runtime, "tmp/proot-scratch").isFile, "PRoot scratch must survive")
        assertTrue(developerWorkspaces.isDirectory, "the project-copy root itself must survive")
        assertTrue(termuxWorkspaces.isDirectory)
    }

    @Test
    fun `a hostile directory name is refused instead of resolved`() {
        val record = safRecord()
        // Everything that would let a name escape a root, or target the root itself.
        val hostile = listOf(
            "..",
            ".",
            "",
            "   ",
            "../escape",
            "..\\escape",
            "/absolute",
            "nested/name",
            "nested\\name",
            "\u0000nul",
        )
        val escapee = File(filesDir, "escape").apply { mkdirs() }
        val rootFile = File(developerWorkspaces, "keep-me").apply { writeText("not a project copy") }

        val storage = storageOver(naming(*hostile.toTypedArray()))
        assertEquals(emptyList(), storage.ownedLocations(record), "no candidate may be constructed")

        val report = storage.remove(record)
        assertTrue(report.ok)
        assertTrue(report.isEmpty)
        assertTrue(escapee.isDirectory, "a traversal name must not reach outside the root")
        assertTrue(developerWorkspaces.isDirectory, "a name must never target the root itself")
        assertTrue(rootFile.exists(), "a file in the root is not a project directory")
    }

    @Test
    fun `a file where a directory is expected is left alone`() {
        val record = safRecord()
        val file = File(developerWorkspaces, "myproject-1a2b3c4d").apply { writeText("not a directory") }

        val report = storageOver(naming("myproject-1a2b3c4d")).remove(record)

        assertTrue(report.ok)
        assertTrue(report.isEmpty)
        assertTrue(file.isFile, "only directories this app created are in scope")
    }

    @Test
    fun `a removal that cannot finish is reported instead of assumed`() {
        val record = safRecord()
        val copy = createCopy("myproject-1a2b3c4d", "src/Main.kt")
        val refusing = OwnedWorkspaceProjectStorage(
            ownedRoots = listOf(developerWorkspaces.path),
            naming = naming("myproject-1a2b3c4d"),
            deleteRecursively = { false },
        )

        val report = refusing.remove(record)

        assertFalse(report.ok, "a delete that did not happen must not report success")
        assertEquals(listOf(copy.path), report.failed)
        assertTrue(report.removed.isEmpty())
        assertTrue(copy.exists(), "the caller still has something to retry")
    }

    @Test
    fun `a build with no project-copy runtime owns nothing`() {
        val record = safRecord()
        createCopy("myproject-1a2b3c4d", "src/Main.kt")

        val report = NoWorkspaceProjectStorage.remove(record)

        assertTrue(report.isEmpty)
        assertTrue(NoWorkspaceProjectStorage.ownedLocations(record).isEmpty())
        assertTrue(File(developerWorkspaces, "myproject-1a2b3c4d").exists())
    }

    // --- deleting a project through the manager ----------------------------

    private class FixedBackend(private val byHandle: Map<String, Workspace>) : WorkspaceBackend {
        override suspend fun open(handle: String): WorkspaceResult<Workspace> =
            byHandle[handle]?.let { success(it) }
                ?: failure(WorkspaceError(WorkspaceErrorCode.WORKSPACE_NOT_FOUND, "No such workspace."))
    }

    private fun workspace(id: String, name: String, location: String): Workspace = DefaultWorkspace(
        metadata = WorkspaceMetadata(
            id = WorkspaceId(id),
            name = name,
            displayLocation = location,
            persisted = true,
        ),
        fileSystem = InMemoryWorkspaceFileSystem(mapOf("README.md" to "# $name\n")),
    )

    private class Manager(
        val manager: DefaultWorkspaceManager,
        val store: InMemoryWorkspaceMetadataStore,
    )

    private fun managerWith(
        vararg projects: Triple<String, String, String>,
        projectStorage: WorkspaceProjectStorage = NoWorkspaceProjectStorage,
    ): Manager {
        val backend = FixedBackend(
            projects.associate { (id, name, location) ->
                location to workspace(id, name, location)
            },
        )
        val store = InMemoryWorkspaceMetadataStore()
        return Manager(
            DefaultWorkspaceManager(
                backend = backend,
                store = store,
                clock = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC),
                projectStorage = projectStorage,
            ),
            store,
        )
    }

    @Test
    fun `delete removes the project and the data AgentX created for it`() = runBlocking {
        val setup = managerWith(
            Triple(safId, "My Project", safHandle),
            projectStorage = storageOver(naming("myproject-1a2b3c4d", safId)),
        )
        val opened = setup.manager.open(safHandle).valueOrNull()
        assertNotNull(opened)
        assertEquals(safId, opened.workspace.id.value)
        val copy = createCopy("myproject-1a2b3c4d", "src/Main.kt")

        assertTrue(setup.manager.delete(WorkspaceId(safId)).valueOrNull() == Unit)

        assertNull(setup.store.find(WorkspaceId(safId)), "the project must be gone from the list")
        assertTrue(setup.manager.recent().valueOrNull().orEmpty().isEmpty())
        assertFalse(copy.exists(), "the copy AgentX made of the project must be gone too")
        assertNull(setup.manager.current, "a session pointing at a deleted project is stale")
    }

    @Test
    fun `deleting one project does not affect another`() = runBlocking {
        val storage = storageOver(naming("myproject-1a2b3c4d", "otherproject-9999"))
        val setup = managerWith(
            Triple(safId, "My Project", safHandle),
            Triple("saf-9999", "Other Project", "content://provider/tree/primary%3AOther"),
            projectStorage = storage,
        )
        setup.manager.open(safHandle)
        setup.manager.open("content://provider/tree/primary%3AOther")
        val mine = createCopy("myproject-1a2b3c4d", "src/Main.kt")
        val theirs = createCopy("otherproject-9999", "src/Other.kt")

        assertTrue(setup.manager.delete(WorkspaceId(safId)).valueOrNull() == Unit)

        assertFalse(mine.exists())
        assertTrue(theirs.exists(), "another project's copy must survive")
        assertNotNull(setup.store.find(WorkspaceId("saf-9999")))
        assertEquals(
            listOf("Other Project"),
            setup.manager.recent().valueOrNull()?.map { it.name },
        )
    }

    @Test
    fun `delete never removes the shared runtime`() = runBlocking {
        // The owned root is the project-copy directory only. The rootfs, the archive and PRoot's
        // scratch are its siblings, and they are not this project's data.
        val storage = storageOver(naming("myproject-1a2b3c4d"), roots = listOf(developerWorkspaces.path))
        val setup = managerWith(Triple(safId, "My Project", safHandle), projectStorage = storage)
        setup.manager.open(safHandle)
        createCopy("myproject-1a2b3c4d", "src/Main.kt")

        assertTrue(setup.manager.delete(WorkspaceId(safId)).valueOrNull() == Unit)

        assertTrue(File(filesDir, "developer-runtime/rootfs/bin/bash").isFile)
        assertTrue(File(filesDir, "developer-runtime/downloads/ubuntu.tar.gz").isFile)
        assertTrue(File(filesDir, "developer-runtime/tmp/proot-scratch").isFile)
    }

    @Test
    fun `a cleanup that could not finish keeps the project so it can be retried`() = runBlocking {
        val refusing = OwnedWorkspaceProjectStorage(
            ownedRoots = listOf(developerWorkspaces.path),
            naming = naming("myproject-1a2b3c4d"),
            deleteRecursively = { false },
        )
        val setup = managerWith(Triple(safId, "My Project", safHandle), projectStorage = refusing)
        setup.manager.open(safHandle)
        val copy = createCopy("myproject-1a2b3c4d", "src/Main.kt")

        val error = assertNotNull(setup.manager.delete(WorkspaceId(safId)).errorOrNull())

        assertTrue(error.message.contains("left in your list"))
        assertNotNull(setup.store.find(WorkspaceId(safId)), "the project stays visible and retryable")
        assertTrue(copy.exists(), "nothing may be half-deleted")
    }

    @Test
    fun `deleting an unknown project is a success`() = runBlocking {
        val setup = managerWith(Triple(safId, "My Project", safHandle))

        assertTrue(setup.manager.delete(WorkspaceId("saf-missing")).valueOrNull() == Unit)
    }

    @Test
    fun `forget still removes only the record and leaves the copy in place`() = runBlocking {
        // The pre-existing behaviour is unchanged: `forget` is the record-level operation a
        // delete builds on, not a second project-management system.
        val setup = managerWith(Triple(safId, "My Project", safHandle))
        setup.manager.open(safHandle)
        val copy = createCopy("myproject-1a2b3c4d", "src/Main.kt")

        assertTrue(setup.manager.forget(WorkspaceId(safId)).valueOrNull() == Unit)

        assertNull(setup.store.find(WorkspaceId(safId)))
        assertTrue(copy.exists())
    }
}
