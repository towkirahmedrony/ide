package com.agentx.app.ui.ide.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentx.app.core.ForgeResult
import com.agentx.app.ui.ide.data.WorkspacePicker
import com.agentx.app.ui.ide.model.ProjectSummary
import com.agentx.app.ui.ide.model.toSummary
import com.agentx.app.workspace.WorkspaceId
import com.agentx.app.workspace.WorkspaceManager
import kotlinx.coroutines.launch

data class HomeUiState(
    val loading: Boolean = true,
    val projects: List<ProjectSummary> = emptyList(),
    val error: String? = null,
    val opening: Boolean = false,
    /** A new project is being created; the create dialog stays open and disables its actions. */
    val creating: Boolean = false,
    /** Why the last create attempt failed, shown inside the dialog. Cleared on the next attempt. */
    val createError: String? = null,
    /**
     * The project the user asked to delete, while the confirmation is on screen. `null` when no
     * confirmation is up; nothing is deleted until [HomeViewModel.confirmDelete] runs.
     */
    val pendingDelete: ProjectSummary? = null,
    /** True while a confirmed delete is running, so the dialog cannot be submitted twice. */
    val deleting: Boolean = false,
    /**
     * Why the last confirmed delete did not happen. Kept inside the confirmation dialog rather than
     * in [error], so a failed cleanup explains itself without replacing the project list the user
     * needs to retry from.
     */
    val deleteError: String? = null,
) {
    val isEmpty: Boolean get() = !loading && error == null && projects.isEmpty()
}

/** Drives the Home / Projects screen against the real workspace runtime. */
class HomeViewModel(
    private val manager: WorkspaceManager,
    private val picker: WorkspacePicker,
) : ViewModel() {

    var uiState by mutableStateOf(HomeUiState())
        private set

    private var restoreAttempted = false

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { loadRecents() }
    }

    private suspend fun loadRecents() {
        uiState = uiState.copy(loading = true, error = null)
        uiState = when (val result = manager.recent()) {
            is ForgeResult.Success -> uiState.copy(
                loading = false,
                projects = result.value.map { metadata -> metadata.toSummary() },
                error = null,
            )

            is ForgeResult.Failure -> uiState.copy(loading = false, error = result.error.userMessage)
        }
    }

    /** Launches the system folder picker and opens the chosen workspace. */
    fun pickWorkspace(onOpened: (String) -> Unit) {
        picker.pick { handle ->
            if (handle == null) return@pick
            viewModelScope.launch { openHandle(handle, onOpened) }
        }
    }

    /** Reopens a remembered workspace; failures are surfaced to the user. */
    fun openRecent(id: String, onOpened: (String) -> Unit) {
        viewModelScope.launch {
            uiState = uiState.copy(opening = true, error = null)
            when (val result = manager.openRecent(WorkspaceId(id))) {
                is ForgeResult.Success -> {
                    uiState = uiState.copy(opening = false)
                    onOpened(result.value.workspace.id.value)
                }

                is ForgeResult.Failure -> {
                    uiState = uiState.copy(opening = false, error = result.error.userMessage)
                    loadRecents()
                }
            }
        }
    }

    /**
     * Creates a new empty project in AgentX-managed storage and opens it as the active
     * workspace. Nothing changes when creation fails: the failure is shown in the dialog and any
     * previously open project stays active.
     */
    fun createProject(name: String, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            uiState = uiState.copy(creating = true, createError = null)
            when (val result = manager.createProject(name)) {
                is ForgeResult.Success -> {
                    uiState = uiState.copy(creating = false, createError = null)
                    onCreated(result.value.workspace.id.value)
                    loadRecents()
                }

                is ForgeResult.Failure -> {
                    uiState = uiState.copy(creating = false, createError = result.error.userMessage)
                }
            }
        }
    }

    /** Clears a previous create error, e.g. when the dialog is reopened. */
    fun clearCreateError() {
        if (uiState.createError != null) uiState = uiState.copy(createError = null)
    }

    /** Removes a workspace from the recent list. */
    fun forget(id: String) {
        viewModelScope.launch {
            manager.forget(WorkspaceId(id))
            loadRecents()
        }
    }

    /**
     * Asks to delete [project] — the confirmation step, not the delete.
     *
     * Deleting a project is the one action on this screen that removes data, so it is never taken
     * straight from a tap on the list. The request is held in [HomeUiState.pendingDelete] and the
     * screen shows the project's name with a Cancel and a Delete; only [confirmDelete] proceeds.
     * Until then the project and everything AgentX knows about it is untouched, which is what makes
     * Cancel a no-op rather than an undo.
     */
    fun requestDelete(project: ProjectSummary) {
        uiState = uiState.copy(pendingDelete = project, deleting = false, deleteError = null)
    }

    /**
     * Dismisses the confirmation without deleting anything.
     *
     * The project keeps its place in the list, its stored record, and any data AgentX created for
     * it: this only clears the pending request.
     */
    fun cancelDelete() {
        uiState = uiState.copy(pendingDelete = null, deleting = false, deleteError = null)
    }

    /**
     * Deletes the project the user confirmed, through the workspace runtime.
     *
     * The runtime removes the project's record and the data AgentX created for it — including the
     * project directory of a project AgentX created itself. It never removes the folder the user
     * opened, the shared runtime, or any other project. A failure leaves the project in the list and
     * reports why in the dialog, so the user can retry or cancel — the app never claims a delete
     * that did not happen.
     */
    fun confirmDelete() {
        val project = uiState.pendingDelete ?: return
        if (uiState.deleting) return
        viewModelScope.launch {
            uiState = uiState.copy(deleting = true, deleteError = null)
            when (val result = manager.delete(WorkspaceId(project.id))) {
                is ForgeResult.Success -> {
                    uiState = uiState.copy(deleting = false, pendingDelete = null, deleteError = null)
                    loadRecents()
                }

                is ForgeResult.Failure -> uiState = uiState.copy(
                    deleting = false,
                    deleteError = result.error.userMessage,
                )
            }
        }
    }

    /**
     * Reopens the last used workspace once per session, so the app returns to
     * where the user left off. Failures are ignored (the Home screen stays put).
     */
    fun restoreLastWorkspace(onRestored: (String) -> Unit) {
        if (restoreAttempted) return
        restoreAttempted = true
        viewModelScope.launch {
            val result = manager.restoreLastOpened() ?: return@launch
            if (result is ForgeResult.Success) {
                onRestored(result.value.workspace.id.value)
            }
        }
    }

    private suspend fun openHandle(handle: String, onOpened: (String) -> Unit) {
        uiState = uiState.copy(opening = true, error = null)
        when (val result = manager.open(handle)) {
            is ForgeResult.Success -> {
                uiState = uiState.copy(opening = false)
                onOpened(result.value.workspace.id.value)
                loadRecents()
            }

            is ForgeResult.Failure -> uiState = uiState.copy(opening = false, error = result.error.userMessage)
        }
    }
}
