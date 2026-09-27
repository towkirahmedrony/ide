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

    /** Removes a workspace from the recent list. */
    fun forget(id: String) {
        viewModelScope.launch {
            manager.forget(WorkspaceId(id))
            loadRecents()
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
