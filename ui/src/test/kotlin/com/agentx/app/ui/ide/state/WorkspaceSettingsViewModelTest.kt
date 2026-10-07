package com.agentx.app.ui.ide.state

import com.agentx.app.core.failure
import com.agentx.app.core.success
import com.agentx.app.git.GitBranch
import com.agentx.app.git.GitChangeType
import com.agentx.app.git.GitDetection
import com.agentx.app.git.GitFileChange
import com.agentx.app.git.GitLogEntry
import com.agentx.app.git.GitOperationResult
import com.agentx.app.git.GitRemote
import com.agentx.app.git.GitResult
import com.agentx.app.git.GitService
import com.agentx.app.git.GitStatus
import com.agentx.app.skills.DefaultSkillManager
import com.agentx.app.skills.SkillDefinition
import com.agentx.app.skills.SkillDiscoverySource
import com.agentx.app.skills.SkillSource
import com.agentx.app.ui.ide.data.WorkspacePicker
import com.agentx.app.ui.ide.model.WorkspaceStorageKind
import com.agentx.app.workspace.DefaultWorkspace
import com.agentx.app.workspace.DefaultWorkspaceSession
import com.agentx.app.workspace.WorkspaceError
import com.agentx.app.workspace.WorkspaceErrorCode
import com.agentx.app.workspace.WorkspaceId
import com.agentx.app.workspace.WorkspaceManager
import com.agentx.app.workspace.WorkspaceMetadata
import com.agentx.app.workspace.WorkspaceRecord
import com.agentx.app.workspace.WorkspaceResult
import com.agentx.app.workspace.WorkspaceSession
import com.agentx.app.workspace.memory.InMemoryWorkspaceFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Settings → Workspace.
 *
 * The page's destructive controls are the reason it exists, so the behaviour that matters most is
 * what happens *before* one runs: a request asks, Cancel changes nothing, and only a confirmed
 * action reaches the runtime. Switching is checked to go through the one existing workspace runtime.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceSettingsViewModelTest {

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
        tempDirs.forEach { it.deleteRecursively() }
    }

    private val tempDirs = mutableListOf<File>()

    private fun managedRoot(): File {
        val base = Files.createTempDirectory("agentx-ws").toFile()
        tempDirs += base
        return File(base, "AgentX")
    }

    /**
     * A remembered workspace. [location] is the runtime's own `displayLocation`: the same string as the
     * handle for a filesystem workspace, and a folder *name* for a SAF workspace — which is the whole
     * point of passing it separately.
     */
    private fun record(
        id: String,
        name: String,
        handle: String,
        location: String = handle,
        opened: Long = 1_000L,
    ) = WorkspaceRecord(
        metadata = WorkspaceMetadata(
            id = WorkspaceId(id),
            name = name,
            displayLocation = location,
            lastOpenedAtEpochMillis = opened,
            persisted = true,
        ),
        handle = handle,
    )

    private fun viewModel(
        manager: FakeWorkspaceManager,
        root: File? = null,
        picker: WorkspacePicker = WorkspacePicker { },
        git: GitService = FakeGitService(),
        skills: com.agentx.app.skills.SkillManager = DefaultSkillManager(builtins = emptyList()),
    ): WorkspaceSettingsViewModel = WorkspaceSettingsViewModel(
        manager = manager,
        picker = picker,
        git = git,
        skills = skills,
        managedRoots = listOfNotNull(root?.path),
    )

    // --- current workspace --------------------------------------------------

    @Test
    fun `an AgentX-created project is reported as managed storage`() {
        val root = managedRoot()
        val project = File(root, "MyProject").apply { mkdirs() }
        val record = record("file-1", "MyProject", project.canonicalPath)
        val manager = FakeWorkspaceManager().apply { makeCurrent(record) }

        val model = viewModel(manager, root = root)

        assertEquals("MyProject", model.uiState.current?.name)
        assertEquals(WorkspaceStorageKind.AGENTX_MANAGED, model.uiState.current?.storageKind)
        assertEquals(root.path, model.uiState.managedProjectRoot)
    }

    @Test
    fun `a SAF workspace is reported without a fabricated path`() {
        val manager = FakeWorkspaceManager().apply {                makeCurrent(
                    record(
                        id = "saf-1",
                        name = "Notes",
                        handle = "content://com.android.externalstorage.documents/tree/primary%3ANotes",
                        location = "Notes",
                    ),
                )
        }

        val model = viewModel(manager)

        assertEquals(WorkspaceStorageKind.SAF_FOLDER, model.uiState.current?.storageKind)
        assertEquals("Notes", model.uiState.current?.location)
        assertEquals(false, model.uiState.current?.managed)
    }

    @Test
    fun `with nothing open the page reports no workspace`() {
        val model = viewModel(FakeWorkspaceManager())

        assertNull(model.uiState.current)
    }

    // --- git + skills -------------------------------------------------------

    @Test
    fun `git and workspace skills are read through the existing services`() {
        val manager = FakeWorkspaceManager().apply {
            makeCurrent(record("file-1", "Repo", "/tmp/repo"))
        }
        val git = FakeGitService(isRepository = true, branch = "main", changedCount = 2)
        val skills = DefaultSkillManager(
            builtins = emptyList(),
            sources = listOf(
                SkillDiscoverySource {
                    listOf(
                        SkillDefinition(
                            id = "workspace-one",
                            name = "Workspace One",
                            description = "from the project",
                            instructions = "do the thing",
                            source = SkillSource.WORKSPACE,
                        ),
                    )
                },
            ),
        )

        val model = viewModel(manager, git = git, skills = skills)

        assertEquals(true, model.uiState.git?.repository)
        assertEquals("main", model.uiState.git?.branch)
        assertEquals(false, model.uiState.git?.clean)
        assertEquals(2, model.uiState.git?.changedCount)
        assertEquals(1, model.uiState.workspaceSkillCount)
    }

    @Test
    fun `a project that is not a Git repository is reported as not detected`() {
        val manager = FakeWorkspaceManager().apply { makeCurrent(record("file-1", "Plain", "/tmp/plain")) }

        val model = viewModel(manager, git = FakeGitService(isRepository = false, detectionReason = "Not a Git repository."))

        assertEquals(false, model.uiState.git?.repository)
        assertEquals("Not a Git repository.", model.uiState.git?.note)
    }

    // --- switching ----------------------------------------------------------

    @Test
    fun `switching opens the picked handle through the one workspace runtime`() {
        val manager = FakeWorkspaceManager()
        val opened = mutableListOf<String>()
        val model = viewModel(manager, picker = WorkspacePicker { onPicked -> onPicked("content://picked") })

        model.switchWorkspace { id -> opened += id }

        assertEquals(listOf("content://picked"), manager.opened)
        assertEquals(1, opened.size, "a successful open navigates into the new workspace")
    }

    @Test
    fun `a cancelled pick leaves the current workspace untouched`() {
        val manager = FakeWorkspaceManager().apply { makeCurrent(record("file-1", "Mine", "/tmp/mine")) }
        val model = viewModel(manager, picker = WorkspacePicker { onPicked -> onPicked(null) })

        model.switchWorkspace { }

        assertTrue(manager.opened.isEmpty())
        assertEquals("Mine", model.uiState.current?.name)
    }

    @Test
    fun `opening the already active workspace does not reopen it`() {
        val manager = FakeWorkspaceManager().apply { makeCurrent(record("file-1", "Mine", "/tmp/mine")) }
        val model = viewModel(manager)
        val navigated = mutableListOf<String>()

        model.openWorkspace("file-1") { navigated += it }

        assertEquals(listOf("file-1"), navigated)
        assertTrue(manager.openedRecently.isEmpty(), "the open workspace must not be reopened")
    }

    @Test
    fun `creating a project activates it and reports the new id`() {
        val manager = FakeWorkspaceManager()
        val created = mutableListOf<String>()
        val model = viewModel(manager)

        model.createProject("Fresh") { created += it }

        assertEquals(listOf("Fresh"), manager.created)
        assertEquals(1, created.size)
    }

    // --- destructive safeguards --------------------------------------------

    @Test
    fun `remove asks first and cancel changes nothing`() {
        val manager = FakeWorkspaceManager().apply { makeCurrent(record("file-1", "Mine", "/tmp/mine")) }
        val model = viewModel(manager)

        model.requestForget()
        assertEquals("Mine", model.uiState.pendingForget?.name)
        assertTrue(manager.forgotten.isEmpty(), "asking is not removing")

        model.cancelForget()
        assertNull(model.uiState.pendingForget)
        assertTrue(manager.forgotten.isEmpty(), "Cancel must not reach the runtime")
    }

    @Test
    fun `confirmed remove only forgets the workspace`() {
        val manager = FakeWorkspaceManager().apply { makeCurrent(record("file-1", "Mine", "/tmp/mine")) }
        val model = viewModel(manager)
        model.requestForget()

        var removed = false
        model.confirmForget { removed = true }

        assertEquals(listOf(WorkspaceId("file-1")), manager.forgotten)
        assertTrue(manager.deleted.isEmpty(), "remove must never delete project data")
        assertTrue(removed)
        assertNull(model.uiState.pendingForget)
    }

    @Test
    fun `delete asks first and cancel changes nothing`() {
        val manager = FakeWorkspaceManager().apply { makeCurrent(record("file-1", "Mine", "/tmp/mine")) }
        val model = viewModel(manager)

        model.requestDelete()
        assertEquals("Mine", model.uiState.pendingDelete?.name)
        assertTrue(manager.deleted.isEmpty(), "asking is not deleting")

        model.cancelDelete()
        assertNull(model.uiState.pendingDelete)
        assertNull(model.uiState.deleteError)
        assertTrue(manager.deleted.isEmpty())
    }

    @Test
    fun `confirmed delete reaches the runtime once`() {
        val manager = FakeWorkspaceManager().apply { makeCurrent(record("file-1", "Mine", "/tmp/mine")) }
        val model = viewModel(manager)
        model.requestDelete()

        var removed = false
        model.confirmDelete { removed = true }

        assertEquals(listOf(WorkspaceId("file-1")), manager.deleted)
        assertTrue(removed)
        assertNull(model.uiState.pendingDelete)
    }

    @Test
    fun `a delete that fails is reported and the confirmation stays up`() {
        val manager = FakeWorkspaceManager().apply { makeCurrent(record("file-1", "Mine", "/tmp/mine")) }
        manager.deleteFailure = WorkspaceError(
            WorkspaceErrorCode.UNKNOWN,
            "Could not remove everything AgentX created for this project, so it has been left in your list.",
        )
        val model = viewModel(manager)
        model.requestDelete()

        var removed = false
        model.confirmDelete { removed = true }

        assertNotNull(model.uiState.deleteError)
        assertNotNull(model.uiState.pendingDelete, "the confirmation stays up so the delete can be retried")
        assertFalse(removed, "a failed delete must not navigate away as if it succeeded")
    }

    @Test
    fun `confirming without a pending request does nothing`() {
        val manager = FakeWorkspaceManager().apply { makeCurrent(record("file-1", "Mine", "/tmp/mine")) }
        val model = viewModel(manager)

        model.confirmDelete { }
        model.confirmForget { }

        assertTrue(manager.deleted.isEmpty())
        assertTrue(manager.forgotten.isEmpty())
    }

    // --- fakes --------------------------------------------------------------

    private class FakeWorkspaceManager(
        records: List<WorkspaceRecord> = emptyList(),
    ) : WorkspaceManager {

        private val entries = LinkedHashMap<WorkspaceId, WorkspaceRecord>()
        private var currentSession: WorkspaceSession? = null
        private var currentHandleValue: String? = null

        val opened = mutableListOf<String>()
        val openedRecently = mutableListOf<WorkspaceId>()
        val forgotten = mutableListOf<WorkspaceId>()
        val deleted = mutableListOf<WorkspaceId>()
        val created = mutableListOf<String>()
        var deleteFailure: WorkspaceError? = null

        init {
            records.forEach { entries[it.metadata.id] = it }
        }

        override val current: WorkspaceSession? get() = currentSession

        override val currentHandle: String? get() = currentHandleValue

        fun makeCurrent(record: WorkspaceRecord) {
            entries[record.metadata.id] = record
            currentHandleValue = record.handle
            currentSession = DefaultWorkspaceSession(
                DefaultWorkspace(record.metadata, InMemoryWorkspaceFileSystem()),
            )
        }

        override suspend fun recent(): WorkspaceResult<List<WorkspaceMetadata>> =
            success(entries.values.map { it.metadata })

        override suspend fun open(handle: String): WorkspaceResult<WorkspaceSession> {
            opened += handle
            val name = handle.substringAfterLast('/').ifBlank { "Workspace" }
            val metadata = WorkspaceMetadata(
                id = WorkspaceId("opened-$name"),
                name = name,
                displayLocation = handle,
                persisted = true,
            )
            val session = DefaultWorkspaceSession(DefaultWorkspace(metadata, InMemoryWorkspaceFileSystem()))
            entries[metadata.id] = WorkspaceRecord(metadata, handle)
            currentSession = session
            currentHandleValue = handle
            return success(session)
        }

        override suspend fun openRecent(id: WorkspaceId): WorkspaceResult<WorkspaceSession> {
            openedRecently += id
            val record = entries[id]
                ?: return failure(WorkspaceError(WorkspaceErrorCode.WORKSPACE_NOT_FOUND, "not found"))
            return open(record.handle)
        }

        override suspend fun createProject(name: String): WorkspaceResult<WorkspaceSession> {
            created += name
            return open("/tmp/$name")
        }

        override suspend fun restoreLastOpened(): WorkspaceResult<WorkspaceSession>? = null

        override suspend fun close() {
            currentSession = null
            currentHandleValue = null
        }

        override suspend fun forget(id: WorkspaceId): WorkspaceResult<Unit> {
            forgotten += id
            entries.remove(id)
            if (currentSession?.workspace?.id == id) {
                currentSession = null
                currentHandleValue = null
            }
            return success(Unit)
        }

        override suspend fun delete(id: WorkspaceId): WorkspaceResult<Unit> {
            deleted += id
            deleteFailure?.let { return failure(it) }
            entries.remove(id)
            if (currentSession?.workspace?.id == id) {
                currentSession = null
                currentHandleValue = null
            }
            return success(Unit)
        }
    }

    private class FakeGitService(
        private val isRepository: Boolean = false,
        private val branch: String? = null,
        private val changedCount: Int = 0,
        private val detectionReason: String? = null,
    ) : GitService {

        override suspend fun detect(workspaceId: String): GitResult<GitDetection> =
            success(GitDetection(isRepository = isRepository, reason = detectionReason))

        override suspend fun status(workspaceId: String): GitResult<GitStatus> = success(
            GitStatus(
                branch = branch,
                changes = List(changedCount) { index ->
                    GitFileChange(path = "file-$index.txt", type = GitChangeType.MODIFIED)
                },
            ),
        )

        override suspend fun diff(
            workspaceId: String,
            staged: Boolean,
            paths: List<String>,
        ): GitResult<String> = success("")

        override suspend fun branches(workspaceId: String): GitResult<List<GitBranch>> = success(emptyList())

        override suspend fun checkout(workspaceId: String, branch: String): GitResult<GitOperationResult> =
            success(GitOperationResult(success = true, output = ""))

        override suspend fun log(workspaceId: String, limit: Int): GitResult<List<GitLogEntry>> = success(emptyList())

        override suspend fun remotes(workspaceId: String): GitResult<List<GitRemote>> = success(emptyList())

        override suspend fun add(workspaceId: String, paths: List<String>): GitResult<GitOperationResult> =
            success(GitOperationResult(success = true, output = ""))

        override suspend fun commit(workspaceId: String, message: String): GitResult<GitOperationResult> =
            success(GitOperationResult(success = true, output = ""))

        override suspend fun pull(workspaceId: String): GitResult<GitOperationResult> =
            success(GitOperationResult(success = true, output = ""))

        override suspend fun push(workspaceId: String): GitResult<GitOperationResult> =
            success(GitOperationResult(success = true, output = ""))
    }
}
