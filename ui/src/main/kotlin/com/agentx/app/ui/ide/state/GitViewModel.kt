package com.agentx.app.ui.ide.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentx.app.ui.ide.data.GitRepository
import com.agentx.app.ui.ide.model.GitSnapshot
import kotlinx.coroutines.launch

data class GitUiState(
    val loading: Boolean = true,
    val snapshot: GitSnapshot? = null,
    val error: String? = null,
)

/** Drives the Git screen. Read-only until the Git layer is implemented. */
class GitViewModel(
    private val workspaceId: String,
    private val repository: GitRepository,
) : ViewModel() {

    var uiState by mutableStateOf(GitUiState())
        private set

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            uiState = uiState.copy(loading = true, error = null)
            uiState = runCatching { repository.snapshot(workspaceId) }
                .fold(
                    onSuccess = { GitUiState(loading = false, snapshot = it) },
                    onFailure = { GitUiState(loading = false, error = it.message ?: "Git unavailable") },
                )
        }
    }
}
