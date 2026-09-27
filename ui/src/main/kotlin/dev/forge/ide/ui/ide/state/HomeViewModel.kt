package dev.forge.ide.ui.ide.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.forge.ide.ui.ide.data.ProjectCatalog
import dev.forge.ide.ui.ide.model.ProjectSummary
import kotlinx.coroutines.launch

data class HomeUiState(
    val loading: Boolean = true,
    val projects: List<ProjectSummary> = emptyList(),
    val error: String? = null,
) {
    val isEmpty: Boolean get() = !loading && error == null && projects.isEmpty()
}

/** Drives the Home / Projects screen. */
class HomeViewModel(private val catalog: ProjectCatalog) : ViewModel() {

    var uiState by mutableStateOf(HomeUiState())
        private set

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            uiState = uiState.copy(loading = true, error = null)
            uiState = runCatching { catalog.recentProjects() }
                .fold(
                    onSuccess = { HomeUiState(loading = false, projects = it) },
                    onFailure = { HomeUiState(loading = false, error = it.message ?: "Failed to load projects") },
                )
        }
    }

    /** Creates a workspace then hands its id back so the UI can open it. */
    fun createWorkspace(name: String, onCreated: (ProjectSummary) -> Unit) {
        viewModelScope.launch {
            runCatching { catalog.createWorkspace(name) }
                .onSuccess { project ->
                    uiState = uiState.copy(projects = uiState.projects + project)
                    onCreated(project)
                }
                .onFailure { uiState = uiState.copy(error = it.message ?: "Could not create workspace") }
        }
    }
}
