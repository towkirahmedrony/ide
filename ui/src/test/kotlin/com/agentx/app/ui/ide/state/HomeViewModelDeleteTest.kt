package com.agentx.app.ui.ide.state

import com.agentx.app.core.failure
import com.agentx.app.core.success
import com.agentx.app.ui.ide.data.WorkspacePicker
import com.agentx.app.ui.ide.model.ProjectSummary
import com.agentx.app.workspace.WorkspaceError
import com.agentx.app.workspace.WorkspaceErrorCode
import com.agentx.app.workspace.WorkspaceId
import com.agentx.app.workspace.WorkspaceManager
import com.agentx.app.workspace.WorkspaceMetadata
import com.agentx.app.workspace.WorkspaceResult
import com.agentx.app.workspace.WorkspaceSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The delete confirmation on Home.
 *
 * Deleting a project is the only destructive thing on the list, so the behaviour that matters is
 * what happens *before* the delete: a tap asks, Cancel changes nothing, and only a confirmed
 * Delete reaches the runtime.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelDeleteTest {

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Records what the screen asked the runtime to do, without touching a filesystem. */
    private class FakeWorkspaceManager(
        projects: List<WorkspaceMetadata> = emptyList(),
    ) : WorkspaceManager {

        private val records = LinkedHashMap<WorkspaceId, WorkspaceMetadata>()

        val deleted = mutableListOf<WorkspaceId>()
        val forgotten = mutableListOf<WorkspaceId>()
        var deleteFailure: WorkspaceError? = null

        init {
            projects.forEach { records[it.id] = it }
        }

        override val current: WorkspaceSession? get() = null

        override suspend fun recent(): WorkspaceResult<List<WorkspaceMetadata>> =
            success(records.values.toList())

        override suspend fun open(handle: String): WorkspaceResult<WorkspaceSession> =
            failure(WorkspaceError(WorkspaceErrorCode.WORKSPACE_NOT_FOUND, "not used by this test"))

        override suspend fun openRecent(id: WorkspaceId): WorkspaceResult<WorkspaceSession> =
            failure(WorkspaceError(WorkspaceErrorCode.WORKSPACE_NOT_FOUND, "not used by this test"))

        override suspend fun restoreLastOpened(): WorkspaceResult<WorkspaceSession>? = null

        override suspend fun createProject(name: String): WorkspaceResult<WorkspaceSession> =
            failure(WorkspaceError(WorkspaceErrorCode.UNKNOWN, "not used by this test"))

        override suspend fun close() = Unit

        override suspend fun forget(id: WorkspaceId): WorkspaceResult<Unit> {
            forgotten += id
            records.remove(id)
            return success(Unit)
        }

        override suspend fun delete(id: WorkspaceId): WorkspaceResult<Unit> {
            deleted += id
            deleteFailure?.let { return failure(it) }
            records.remove(id)
            return success(Unit)
        }
    }

    private fun metadata(id: String, name: String) = WorkspaceMetadata(
        id = WorkspaceId(id),
        name = name,
        displayLocation = "content://com.android.externalstorage.documents/tree/primary%3A$name",
        persisted = true,
    )

    private val myProject = ProjectSummary(
        id = "saf-1a2b3c4d",
        name = "My Project",
        rootPath = "content://tree/primary%3AMyProject",
    )

    private val otherProject = ProjectSummary(
        id = "saf-9999",
        name = "Other Project",
        rootPath = "content://tree/primary%3AOther",
    )

    private fun viewModel(
        vararg projects: WorkspaceMetadata,
        picker: WorkspacePicker = WorkspacePicker { },
    ): Pair<HomeViewModel, FakeWorkspaceManager> {
        val manager = FakeWorkspaceManager(projects.toList())
        return HomeViewModel(manager, picker) to manager
    }

    @Test
    fun `the list alone never deletes anything`() {
        val (model, manager) = viewModel(metadata("saf-1a2b3c4d", "My Project"))

        assertEquals(listOf("My Project"), model.uiState.projects.map { it.name })
        assertTrue(manager.deleted.isEmpty())
        assertNull(model.uiState.pendingDelete)
    }

    @Test
    fun `tapping delete asks first and names the project`() {
        val (model, manager) = viewModel(metadata("saf-1a2b3c4d", "My Project"))

        model.requestDelete(myProject)

        val pending = assertNotNull(model.uiState.pendingDelete)
        assertEquals(myProject.id, pending.id)
        assertEquals("My Project", pending.name, "the dialog must say which project would go")
        assertTrue(manager.deleted.isEmpty(), "asking is not deleting")
        assertEquals(listOf("My Project"), model.uiState.projects.map { it.name })
    }

    @Test
    fun `cancel leaves the project completely unchanged`() {
        val (model, manager) = viewModel(metadata("saf-1a2b3c4d", "My Project"))
        model.requestDelete(myProject)

        model.cancelDelete()

        assertNull(model.uiState.pendingDelete)
        assertNull(model.uiState.deleteError)
        assertTrue(manager.deleted.isEmpty(), "Cancel must not reach the runtime")
        assertEquals(listOf("My Project"), model.uiState.projects.map { it.name })
    }

    @Test
    fun `requesting delete for another project replaces the pending one`() {
        val (model, _) = viewModel(
            metadata("saf-1a2b3c4d", "My Project"),
            metadata("saf-9999", "Other Project"),
        )

        model.requestDelete(myProject)
        model.requestDelete(otherProject)

        assertEquals(otherProject.id, model.uiState.pendingDelete?.id)
    }

    @Test
    fun `confirmed delete removes the managed project`() {
        val (model, manager) = viewModel(
            metadata("saf-1a2b3c4d", "My Project"),
            metadata("saf-9999", "Other Project"),
        )
        model.requestDelete(myProject)

        model.confirmDelete()

        assertEquals(listOf(WorkspaceId("saf-1a2b3c4d")), manager.deleted)
        assertNull(model.uiState.pendingDelete, "the dialog closes once the delete happened")
        assertNull(model.uiState.deleteError)
        assertTrue(model.uiState.projects.none { it.id == "saf-1a2b3c4d" })
        assertEquals(listOf("Other Project"), model.uiState.projects.map { it.name })
    }

    @Test
    fun `a delete that fails is reported and the project stays in the list`() {
        val (model, manager) = viewModel(
            metadata("saf-1a2b3c4d", "My Project"),
            metadata("saf-9999", "Other Project"),
        )
        manager.deleteFailure = WorkspaceError(
            WorkspaceErrorCode.UNKNOWN,
            "Could not remove everything AgentX created for this project, so it has been left in your list.",
        )
        model.requestDelete(myProject)

        model.confirmDelete()

        assertNotNull(model.uiState.deleteError, "the user must be told the delete did not happen")
        assertNotNull(model.uiState.pendingDelete, "the dialog stays up so the delete can be retried")
        assertTrue(model.uiState.projects.any { it.id == "saf-1a2b3c4d" })
        assertEquals(listOf("My Project", "Other Project"), model.uiState.projects.map { it.name })
    }

    @Test
    fun `confirm without a pending request does nothing`() {
        val (model, manager) = viewModel(metadata("saf-1a2b3c4d", "My Project"))

        model.confirmDelete()

        assertTrue(manager.deleted.isEmpty())
        assertEquals(listOf("My Project"), model.uiState.projects.map { it.name })
    }
}
