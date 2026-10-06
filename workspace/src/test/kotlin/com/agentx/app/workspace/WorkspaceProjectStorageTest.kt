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

    /** Managed project storage: projects AgentX created itself, one directory each. */
    private lateinit var managedProjects: File

    @BeforeTest
    fun setUp() {
        filesDir = Files.createTempDirectory("agentx-project-storage").toFile()
        developerWorkspaces = File(filesDir, "developer-runtime/workspaces").apply { mkdirs() }
        termuxWorkspaces = File(filesDir, "workspaces").apply { mkdirs() }
        managedProjects = File(filesDir, "projects").apply { mkdirs() }

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

    /**
     * A record for a project AgentX created itself: its handle is the real path of the directory
     * inside managed storage, exactly as `ManagedProjectDirectory` registers it.
     */
    private fun managedRecord(name: String, id: String = "managed-$name") = WorkspaceRecord(
        metadata = WorkspaceMetadata(
            id = WorkspaceId(id),
            name = name,
            displayLocation = File(managedProjects, name).canonicalPath,
            persisted = true,
        ),
        handle = File(managedProjects, name).canonicalPath,
    )

    /** A real project directory in managed storage, as the Create New Project flow would leave it. */
    private fun createManagedProject(name: String, vararg contents: String): File {
        val directory = File(managedProjects, name)
        directory.mkdirs()
        for (file in contents) {
            val target = File(directory, file)
            target.parentFile?.mkdirs()
            target.writeText("$name/$file")
        }
        return directory
    }

    private fun storageOver(
        naming: ProjectDirectoryNaming,
        roots: List<String> = listOf(developerWorkspaces.path, termuxWorkspaces.path),
        managedRoots: List<String> = listOf(managedProjects.path),
    ): OwnedWorkspaceProjectStorage = OwnedWorkspaceProjectStorage(
        ownedRoots = roots,
        naming = naming,
        managedRoots = managedRoots,
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

    // --- projects AgentX created itself ------------------------------------

    @Test
    fun `a project AgentX created is removed with the project`() {
        val record = managedRecord("MyApp")
        val directory = createManagedProject("MyApp", "src/Main.kt", "README.md")

        val storage = storageOver(naming("no-copy-for-this-project"))
        assertEquals(listOf(directory.canonicalPath), storage.ownedLocations(record))

        val report = storage.remove(record)

        assertTrue(report.ok, "a clean removal must not report failures: ${report.failed}")
        assertEquals(listOf(directory.canonicalPath), report.removed)
        assertFalse(directory.exists(), "the project directory AgentX created must be gone")
    }

    @Test
    fun `inspecting what a delete would remove removes nothing`() {
        // The confirmation dialog is the only thing between the user's tap and the delete, and it
        // is read-only: the inspection a UI does before asking must not be the delete.
        val record = managedRecord("MyApp")
        val directory = createManagedProject("MyApp", "src/Main.kt")

        val storage = storageOver(naming("no-copy-for-this-project"))
        assertTrue(storage.ownedLocations(record).isNotEmpty())

        assertTrue(directory.isDirectory, "inspecting is not deleting")
        assertTrue(File(directory, "src/Main.kt").isFile)
    }

    @Test
    fun `deleting one managed project leaves another untouched`() {
        val mine = createManagedProject("MyApp", "src/Main.kt")
        val theirs = createManagedProject("TheirApp", "src/Other.kt")

        storageOver(naming("nothing")).remove(managedRecord("MyApp"))

        assertFalse(mine.exists())
        assertTrue(theirs.isDirectory, "another project's directory must survive")
        assertEquals("TheirApp/src/Other.kt", File(theirs, "src/Other.kt").readText())
    }

    @Test
    fun `a copy whose name matches a managed project directory does not delete that project`() {
        // Project B is <projects>/app. Project A's copy of itself is named "app" in the copy root.
        // The same name in two different roots is two different directories: deleting A removes A's
        // copy, and B's managed directory is not in A's candidate set at all.
        val projectB = createManagedProject("app", "src/Main.kt")
        val copyOfA = createCopy("app", "src/Copy.kt")

        storageOver(naming("app")).remove(safRecord(id = "saf-a", name = "A"))

        assertFalse(copyOfA.exists(), "the copy belonging to the deleted project must go")
        assertTrue(projectB.isDirectory, "another project's managed directory must survive")
        assertEquals("app/src/Main.kt", File(projectB, "src/Main.kt").readText())
    }

    @Test
    fun `a project with the same name as a managed project cannot reach it`() {
        // A project in shared storage called "MyApp" is not <projects>/MyApp. Its handle's parent is
        // not a managed root, so the managed root is never even a candidate.
        val managed = createManagedProject("MyApp", "src/Main.kt")
        val userFolder = File(filesDir, "storage/emulated/0/MyApp").apply { mkdirs() }
        File(userFolder, "important.txt").writeText("the user's work")

        val sameNameElsewhere = WorkspaceRecord(
            metadata = WorkspaceMetadata(
                id = WorkspaceId("saf-same-name"),
                name = "MyApp",
                displayLocation = userFolder.canonicalPath,
                persisted = true,
            ),
            handle = userFolder.canonicalPath,
        )

        val storage = storageOver(naming("no-copy"))
        assertEquals(emptyList(), storage.ownedLocations(sameNameElsewhere))

        val report = storage.remove(sameNameElsewhere)
        assertTrue(report.isEmpty, "nothing outside a managed root may be a candidate")
        assertTrue(managed.isDirectory, "a same-named managed project must survive")
        assertEquals("the user's work", File(userFolder, "important.txt").readText())
    }

    @Test
    fun `a SAF project is never matched to a managed root`() {
        // A SAF project's handle is a tree URI — it is never a path inside app-private storage, so
        // even a managed project with the same name is out of reach.
        val managed = createManagedProject("MyProject", "src/Main.kt")
        val storage = storageOver(naming("no-copy"))

        assertEquals(emptyList(), storage.ownedLocations(safRecord()))

        val report = storage.remove(safRecord())
        assertTrue(report.isEmpty)
        assertTrue(managed.isDirectory, "a SAF delete must not reach managed project storage")
    }

    @Test
    fun `a managed directory that cannot be removed is reported instead of assumed`() {
        val directory = createManagedProject("MyApp", "src/Main.kt")
        val refusing = OwnedWorkspaceProjectStorage(
            ownedRoots = emptyList(),
            naming = naming("nothing"),
            managedRoots = listOf(managedProjects.path),
            deleteRecursively = { false },
        )

        val report = refusing.remove(managedRecord("MyApp"))

        assertFalse(report.ok, "a delete that did not happen must not report success")
        assertEquals(listOf(directory.canonicalPath), report.failed)
        assertTrue(report.removed.isEmpty())
        assertTrue(directory.isDirectory, "the caller still has something to retry")
    }

    @Test
    fun `a managed project delete leaves the shared runtime alone`() {
        createManagedProject("MyApp", "src/Main.kt")

        storageOver(naming("nothing")).remove(managedRecord("MyApp"))

        val runtime = File(filesDir, "developer-runtime")
        assertTrue(File(runtime, "rootfs/bin/bash").isFile, "the shared rootfs must survive")
        assertTrue(File(runtime, "downloads/ubuntu.tar.gz").isFile, "the cached archive must survive")
        assertTrue(File(runtime, "tmp/proot-scratch").isFile, "PRoot scratch must survive")
        assertTrue(developerWorkspaces.isDirectory)
        assertTrue(termuxWorkspaces.isDirectory)
        assertTrue(managedProjects.isDirectory, "the managed root itself must survive")
    }

    @Test
    fun `a directory that was never created is not reported as removed`() {
        // The record survives a delete whose directory the user already removed by hand.
        val report = storageOver(naming("nothing")).remove(managedRecord("NeverCreated"))

        assertTrue(report.ok)
        assertTrue(report.isEmpty)
        assertTrue(managedProjects.isDirectory)
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
    fun `a project created in managed storage is deleted together with its directory`() = runBlocking {
        // End to end through the real path the app uses: Create New Project makes the directory,
        // the manager registers it, and deleting the project removes both.
        val manager = DefaultWorkspaceManager(
            backend = FileWorkspaceBackend(),
            store = InMemoryWorkspaceMetadataStore(),
            projects = ManagedProjectDirectory(managedProjects),
            clock = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC),
            projectStorage = storageOver(naming("nothing"), roots = emptyList()),
        )

        val created = assertNotNull(manager.createProject("MyApp").valueOrNull())
        val directory = File(managedProjects, "MyApp")
        val id = created.workspace.id

        assertTrue(directory.isDirectory, "creating a project must create its directory")
        assertNotNull(manager.recent().valueOrNull()?.firstOrNull { it.id == id })

        assertTrue(manager.delete(id).valueOrNull() == Unit)

        assertFalse(directory.exists(), "the managed project directory must be gone")
        assertTrue(
            manager.recent().valueOrNull().orEmpty().none { it.id == id },
            "the project must be gone from the list",
        )
        assertTrue(managedProjects.isDirectory, "the managed root must survive")
    }

    @Test
    fun `a failed managed cleanup keeps the project visible and reports why`() = runBlocking {
        val refusing = OwnedWorkspaceProjectStorage(
            ownedRoots = emptyList(),
            naming = naming("nothing"),
            managedRoots = listOf(managedProjects.path),
            deleteRecursively = { false },
        )
        val setup = managerWith(
            Triple("managed-1", "MyApp", File(managedProjects, "MyApp").canonicalPath),
            projectStorage = refusing,
        )
        setup.manager.open(File(managedProjects, "MyApp").canonicalPath)
        val directory = createManagedProject("MyApp", "src/Main.kt")

        val error = assertNotNull(
            setup.manager.delete(WorkspaceId("managed-1")).errorOrNull(),
        )

        assertTrue(error.message.contains("left in your list"))
        assertNotNull(setup.store.find(WorkspaceId("managed-1")), "the project stays retryable")
        assertTrue(directory.isDirectory, "the directory is still there, unreported as deleted")
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
