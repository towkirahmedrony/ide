package com.agentx.app.ui.ide.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentx.app.core.ForgeResult
import com.agentx.app.git.GitService
import com.agentx.app.skills.SkillManager
import com.agentx.app.skills.SkillSource
import com.agentx.app.ui.ide.data.WorkspacePicker
import com.agentx.app.ui.ide.model.ProjectSummary
import com.agentx.app.ui.ide.model.WorkspaceGitInfo
import com.agentx.app.ui.ide.model.WorkspaceInfo
import com.agentx.app.ui.ide.model.toSummary
import com.agentx.app.ui.ide.model.toWorkspaceInfo
import com.agentx.app.workspace.WorkspaceId
import com.agentx.app.workspace.WorkspaceManager
import kotlinx.coroutines.launch

/**
 * State of Settings → Workspace.
 *
 * [current] always reflects the manager's live session rather than a copy the screen took, so the page
 * cannot show a workspace AgentX no longer has open. [pendingForget] and [pendingDelete] are the two
 * confirmation steps: everything else on the page is read-only, and nothing destructive happens until
 * one of them is confirmed.
 */
data class WorkspaceSettingsUiState(
    val loading: Boolean = true,
    /** The workspace AgentX currently has open, or null when none is. */
    val current: WorkspaceInfo? = null,
    /** Remembered projects, newest first, used for switching. Includes the current one. */
    val recents: List<ProjectSummary> = emptyList(),
    /** The folder AgentX creates projects in, from the composition root. Information, not a setting. */
    val managedProjectRoot: String? = null,
    /** Workspace skills discovered in the open workspace's `skills/` folder. */
    val workspaceSkillCount: Int = 0,
    val git: WorkspaceGitInfo? = null,
    val gitLoading: Boolean = false,
    /** A workspace operation is running (opening, creating, removing, deleting). */
    val busy: Boolean = false,
    val creating: Boolean = false,
    val createError: String? = null,
    /** The workspace a "Remove from AgentX" confirmation is up for. */
    val pendingForget: WorkspaceInfo? = null,
    /** The workspace a "Delete project" confirmation is up for. */
    val pendingDelete: WorkspaceInfo? = null,
    /** Why the confirmed delete did not happen, kept beside the confirmation so it can be retried. */
    val deleteError: String? = null,
    val message: String? = null,
    val error: String? = null,
) {
    val hasWorkspace: Boolean get() = current != null

    /** Remembered projects other than the open one; what "Switch workspace" can move to. */
    val otherProjects: List<ProjectSummary> get() = recents.filter { it.id != current?.id }
}

/**
 * Drives Settings → Workspace.
 *
 * It reuses the existing workspace runtime for every action — the same [WorkspaceManager] Home and the
 * workspace shell use — so opening, creating, forgetting and deleting here have exactly the effects
 * they have anywhere else: the active workspace is switched through the one existing mechanism, and
 * the agent's filesystem tools (which resolve the manager's current workspace) follow the switch
 * without any second workspace state being created.
 *
 * Git and workspace-skill information are read through the existing [GitService] and [SkillManager];
 * nothing here invents a repository or a skill.
 */
class WorkspaceSettingsViewModel(
    private val manager: WorkspaceManager,
    private val picker: WorkspacePicker,
    private val git: GitService,
    private val skills: SkillManager,
    managedRoots: List<String> = emptyList(),
) : ViewModel() {

    private val managedRoots: List<String> = managedRoots

    var uiState by mutableStateOf(
        WorkspaceSettingsUiState(managedProjectRoot = managedRoots.firstOrNull { it.isNotBlank() }),
    )
        private set

    init {
        refresh()
    }

    /** Re-reads the open workspace, the recent list, the workspace skills and Git. */
    fun refresh() {
        viewModelScope.launch {
            uiState = uiState.copy(loading = true, error = null)
            loadCurrent()
            loadRecents()
            loadSkills()
            uiState = uiState.copy(loading = false)
        }
    }

    /**
     * Opens the system picker and switches the active workspace to the chosen folder.
     *
     * Only the runtime's opaque handle moves through the UI, and a cancelled pick is a no-op: the
     * current workspace is untouched until the runtime reports it opened the new one.
     */
    fun switchWorkspace(onOpened: (String) -> Unit) {
        picker.pick { handle ->
            if (handle.isNullOrBlank()) return@pick
            viewModelScope.launch {
                uiState = uiState.copy(busy = true, error = null, message = null)
                when (val result = manager.open(handle)) {
                    is ForgeResult.Success -> {
                        uiState = uiState.copy(busy = false)
                        onOpened(result.value.workspace.id.value)
                    }

                    is ForgeResult.Failure ->
                        uiState = uiState.copy(busy = false, error = result.error.userMessage)
                }
            }
        }
    }

    /** Switches to an already-remembered project. */
    fun openWorkspace(id: String, onOpened: (String) -> Unit) {
        if (id == uiState.current?.id) {
            onOpened(id)
            return
        }
        viewModelScope.launch {
            uiState = uiState.copy(busy = true, error = null, message = null)
            when (val result = manager.openRecent(WorkspaceId(id))) {
                is ForgeResult.Success -> {
                    uiState = uiState.copy(busy = false)
                    onOpened(result.value.workspace.id.value)
                }

                is ForgeResult.Failure ->
                    uiState = uiState.copy(busy = false, error = result.error.userMessage)
            }
        }
    }

    /**
     * Creates a new empty project in AgentX-managed storage and opens it. Nothing becomes active until
     * the runtime created and opened the directory, so a failure leaves the current workspace in place.
     */
    fun createProject(name: String, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            uiState = uiState.copy(creating = true, createError = null)
            when (val result = manager.createProject(name)) {
                is ForgeResult.Success -> {
                    uiState = uiState.copy(creating = false, createError = null)
                    onCreated(result.value.workspace.id.value)
                }

                is ForgeResult.Failure ->
                    uiState = uiState.copy(creating = false, createError = result.error.userMessage)
            }
        }
    }

    fun clearCreateError() {
        if (uiState.createError != null) uiState = uiState.copy(createError = null)
    }

    // --- destructive actions ------------------------------------------------

    /**
     * Asks to remove the open workspace from AgentX — the confirmation step, not the removal.
     *
     * "Remove" only stops AgentX tracking the project; its files are untouched. The request is held
     * until [confirmForget] runs, which is what makes Cancel a no-op.
     */
    fun requestForget() {
        val current = uiState.current ?: return
        uiState = uiState.copy(pendingForget = current, pendingDelete = null, deleteError = null)
    }

    fun cancelForget() {
        uiState = uiState.copy(pendingForget = null)
    }

    /**
     * Removes the open workspace from AgentX's memory. The folder stays exactly where it is — this is
     * the runtime's `forget`, which only drops the record and closes the session.
     */
    fun confirmForget(onRemoved: () -> Unit) {
        val info = uiState.pendingForget ?: return
        if (uiState.busy) return
        viewModelScope.launch {
            uiState = uiState.copy(busy = true)
            when (val result = manager.forget(WorkspaceId(info.id))) {
                is ForgeResult.Success -> {
                    uiState = uiState.copy(
                        busy = false,
                        pendingForget = null,
                        message = "\"${info.name}\" was removed from AgentX.",
                    )
                    refresh()
                    onRemoved()
                }

                is ForgeResult.Failure ->
                    uiState = uiState.copy(busy = false, error = result.error.userMessage)
            }
        }
    }

    /**
     * Asks to delete the open project — the confirmation step, not the delete.
     *
     * Deleting removes the data AgentX created for the project, which for a project AgentX created is
     * its directory. The folder a user opened is never deleted, and neither is another project: the
     * confirmation says which case this is before anything runs.
     */
    fun requestDelete() {
        val current = uiState.current ?: return
        uiState = uiState.copy(pendingDelete = current, pendingForget = null, deleteError = null)
    }

    fun cancelDelete() {
        uiState = uiState.copy(pendingDelete = null, deleteError = null)
    }

    /**
     * Deletes the project the user confirmed, through the runtime. A failure keeps the project in the
     * list and reports why beside the confirmation, so the app never claims a delete that did not
     * happen.
     */
    fun confirmDelete(onRemoved: () -> Unit) {
        val info = uiState.pendingDelete ?: return
        if (uiState.busy) return
        viewModelScope.launch {
            uiState = uiState.copy(busy = true, deleteError = null)
            when (val result = manager.delete(WorkspaceId(info.id))) {
                is ForgeResult.Success -> {
                    uiState = uiState.copy(
                        busy = false,
                        pendingDelete = null,
                        deleteError = null,
                        message = "\"${info.name}\" was deleted.",
                    )
                    refresh()
                    onRemoved()
                }

                is ForgeResult.Failure ->
                    uiState = uiState.copy(busy = false, deleteError = result.error.userMessage)
            }
        }
    }

    fun dismissMessage() {
        uiState = uiState.copy(message = null, error = null)
    }

    // --- loading ------------------------------------------------------------

    private fun loadCurrent() {
        val metadata = manager.current?.workspace?.metadata
        val info = metadata?.toWorkspaceInfo(handle = manager.currentHandle, managedRoots = managedRoots)
        uiState = uiState.copy(current = info)
        loadGit(info?.id)
    }

    private fun loadRecents() {
        viewModelScope.launch {
            when (val result = manager.recent()) {
                is ForgeResult.Success -> uiState = uiState.copy(recents = result.value.map { it.toSummary() })
                is ForgeResult.Failure -> uiState = uiState.copy(recents = emptyList())
            }
        }
    }

    private fun loadSkills() {
        viewModelScope.launch {
            if (manager.current == null) {
                uiState = uiState.copy(workspaceSkillCount = 0)
                return@launch
            }
            runCatching { skills.refresh() }
            val count = runCatching {
                skills.installed().count { it.source == SkillSource.WORKSPACE }
            }.getOrDefault(0)
            uiState = uiState.copy(workspaceSkillCount = count)
        }
    }

    /**
     * Queries Git for the open workspace off the caller's thread. The result is dropped when the open
     * workspace changed while it was in flight, so one project's status never appears for another.
     */
    private fun loadGit(workspaceId: String?) {
        if (workspaceId == null) {
            uiState = uiState.copy(git = null, gitLoading = false)
            return
        }
        uiState = uiState.copy(git = null, gitLoading = true)
        viewModelScope.launch {
            val info = runCatching { queryGit(workspaceId) }
                .getOrElse { WorkspaceGitInfo(repository = false, note = "Git information is unavailable.") }
            if (uiState.current?.id != workspaceId) return@launch
            uiState = uiState.copy(git = info, gitLoading = false)
        }
    }

    private suspend fun queryGit(workspaceId: String): WorkspaceGitInfo {
        val detection = when (val result = git.detect(workspaceId)) {
            is ForgeResult.Success -> result.value
            is ForgeResult.Failure -> return WorkspaceGitInfo(repository = false, note = result.error.userMessage)
        }
        if (!detection.isRepository) {
            return WorkspaceGitInfo(
                repository = false,
                note = detection.reason ?: "This project is not a Git repository.",
            )
        }
        return when (val status = git.status(workspaceId)) {
            is ForgeResult.Success -> WorkspaceGitInfo(
                repository = true,
                branch = status.value.branch,
                clean = status.value.isClean,
                changedCount = status.value.changes.size,
            )

            is ForgeResult.Failure -> WorkspaceGitInfo(repository = true, note = status.error.userMessage)
        }
    }
}
