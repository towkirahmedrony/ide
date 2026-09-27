package com.agentx.app.ui.ide.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentx.app.core.ForgeResult
import com.agentx.app.core.logging.ForgeLogger
import com.agentx.app.core.logging.ForgeLoggers
import com.agentx.app.core.logging.LogLevel
import com.agentx.app.core.success
import com.agentx.app.ui.ide.model.DirectoryLoadState
import com.agentx.app.ui.ide.model.FileNode
import com.agentx.app.ui.ide.model.FileNodeKind
import com.agentx.app.ui.ide.model.OpenFile
import com.agentx.app.ui.ide.model.ProjectSummary
import com.agentx.app.ui.ide.model.toFileNode
import com.agentx.app.ui.ide.model.toSummary
import com.agentx.app.workspace.WorkspaceFileOpener
import com.agentx.app.workspace.WorkspaceId
import com.agentx.app.workspace.WorkspaceManager
import com.agentx.app.workspace.WorkspacePath
import com.agentx.app.workspace.WorkspaceResult
import com.agentx.app.workspace.WorkspaceSession
import com.agentx.app.workspace.WorkspaceTreeLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Files page state.
 *
 * The root node is a normal [FileNode] that carries its own [DirectoryLoadState],
 * so "loading", "loaded", "empty" and "error" are all represented for the root
 * and for every folder below it. Children only exist for folders that were
 * actually opened.
 */
data class FilesUiState(
    /** The workspace session itself is being opened (permissions, root folder). */
    val loading: Boolean = true,
    /** The workspace could not be opened at all; the page shows an error + Retry. */
    val error: String? = null,
    val root: FileNode = FileNode(
        path = WorkspacePath.ROOT,
        name = "",
        kind = FileNodeKind.DIRECTORY,
        loadState = DirectoryLoadState.LOADING,
    ),
    val expanded: Set<String> = emptySet(),
    /** Directory the user last worked in; drives the breadcrumb and "up". */
    val focusedPath: String = WorkspacePath.ROOT,
    val selectedPath: String? = null,
) {
    val rootState: DirectoryLoadState get() = root.loadState
    val rootError: String? get() = root.errorMessage

    /** A root folder that was read successfully and contains nothing. */
    val isEmpty: Boolean get() = !loading && error == null && root.isEmptyDirectory

    /** Readable location of [path] inside the workspace, for the breadcrumb. */
    fun breadcrumb(path: String = focusedPath): String =
        if (WorkspacePath.isRoot(path)) root.name else (listOf(root.name) + path.split('/')).joinToString(SEPARATOR)

    private companion object {
        const val SEPARATOR = " / "
    }
}

data class EditorUiState(
    val file: OpenFile? = null,
    val draft: String = "",
    val saving: Boolean = false,
    val statusMessage: String? = null,
) {
    val isDirty: Boolean get() = file != null && draft != file.content
    val lineCount: Int get() = if (draft.isEmpty()) 1 else draft.count { it == '\n' } + 1
}

/**
 * State holder for the workspace shell.
 *
 * Reading the tree is delegated to the workspace runtime: only the root folder
 * is read when a workspace opens, and each folder is read the first time it is
 * expanded. No code path here walks a project recursively, so opening a large
 * repository shows its first level immediately.
 */
class WorkspaceViewModel(
    private val workspaceId: String,
    private val manager: WorkspaceManager,
    private val logger: ForgeLogger = ForgeLoggers.create(LogLevel.WARN, baseFields = mapOf("screen" to "files")),
) : ViewModel() {

    var project by mutableStateOf<ProjectSummary?>(null)
        private set

    var filesState by mutableStateOf(FilesUiState())
        private set

    var editorState by mutableStateOf(EditorUiState())
        private set

    /** Set when the user asked to open a file while the editor had unsaved changes. */
    var pendingOpenPath by mutableStateOf<String?>(null)
        private set

    private var session: WorkspaceSession? = null
    private var tree: WorkspaceTreeLoader? = null
    private var rootName: String = ""

    init {
        loadWorkspace()
    }

    /**
     * Opens (or restores) the workspace and reads its root folder only.
     *
     * Any failure — including one thrown by the platform storage layer — ends in
     * a visible error state: the Files page must never be left loading.
     */
    fun loadWorkspace() {
        filesState = FilesUiState(loading = true)
        viewModelScope.launch {
            try {
                when (val resolved = resolveSession()) {
                    is ForgeResult.Failure -> {
                        session = null
                        tree = null
                        filesState = FilesUiState(loading = false, error = resolved.error.userMessage)
                    }

                    is ForgeResult.Success -> {
                        val opened = resolved.value
                        session = opened
                        project = opened.workspace.metadata.toSummary()
                        rootName = opened.workspace.metadata.name
                        val loader = WorkspaceTreeLoader(opened.fileSystem)
                        tree = loader

                        publish(loader)                                  // the root folder starts loading
                        loader.load(WorkspacePath.ROOT)                   // one level, never the whole project
                        publish(loader)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (cause: Throwable) {
                logger.error("Opening the workspace failed", cause, mapOf("workspace" to workspaceId))
                session = null
                tree = null
                filesState = FilesUiState(loading = false, error = UNREADABLE_WORKSPACE)
            }
        }
    }

    /**
     * Expands or collapses a folder. Expanding reads that folder's children the
     * first time only; a folder that is already loaded is not read again, so
     * repeated taps or recomposition cannot start another scan.
     */
    fun toggleDirectory(path: String) {
        val expanded = filesState.expanded
        if (path in expanded) {
            filesState = filesState.copy(
                expanded = expanded - path,
                focusedPath = WorkspacePath.parent(path),
            )
            return
        }
        filesState = filesState.copy(expanded = expanded + path, focusedPath = path)
        readDirectory(path)
    }

    /** Retries a folder that failed to load. */
    fun retryDirectory(path: String) {
        filesState = filesState.copy(expanded = filesState.expanded + path, focusedPath = path)
        readDirectory(path, force = true)
    }

    /** Retreats to the parent folder, collapsing the current one. */
    fun navigateUp() {
        val current = filesState.focusedPath
        if (WorkspacePath.isRoot(current)) return
        filesState = filesState.copy(
            expanded = filesState.expanded - current,
            focusedPath = WorkspacePath.parent(current),
        )
    }

    /** Selects and reads a file so the editor can show it. */
    fun openFile(path: String) {
        val active = session ?: return
        filesState = filesState.copy(selectedPath = path, focusedPath = WorkspacePath.parent(path))
        viewModelScope.launch {
            val outcome = try {
                WorkspaceFileOpener(active.fileSystem).open(path)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (cause: Throwable) {
                logger.error("Opening a file failed", cause, mapOf("path" to path))
                return@launch
            }

            when (outcome) {
                is WorkspaceFileOpener.Opened.Text -> editorState = EditorUiState(
                    file = OpenFile(path = outcome.path, name = outcome.name, content = outcome.content),
                    draft = outcome.content,
                )

                // The open file (if any) is kept: an unreadable file must not
                // silently discard unsaved edits elsewhere.
                is WorkspaceFileOpener.Opened.Unsupported ->
                    editorState = editorState.copy(statusMessage = "“${outcome.name}”: ${outcome.error.userMessage}")

                is WorkspaceFileOpener.Opened.Failed ->
                    editorState = editorState.copy(statusMessage = outcome.error.userMessage)
            }
        }
    }

    fun editDraft(text: String) {
        editorState = editorState.copy(draft = text, statusMessage = null)
    }

    fun save() {
        val active = session ?: return
        val file = editorState.file ?: return
        viewModelScope.launch {
            editorState = editorState.copy(saving = true, statusMessage = null)
            when (val result = active.fileSystem.writeFile(file.path, editorState.draft)) {
                is ForgeResult.Success -> editorState = editorState.copy(
                    file = file.copy(content = editorState.draft),
                    saving = false,
                    statusMessage = "Saved ${file.name}",
                )

                is ForgeResult.Failure -> editorState = editorState.copy(
                    saving = false,
                    statusMessage = result.error.userMessage,
                )
            }
        }
    }

    fun dismissEditorStatus() {
        editorState = editorState.copy(statusMessage = null)
    }

    // --- unsaved-changes guard --------------------------------------------

    /** Whether opening [path] would discard unsaved edits in another file. */
    fun needsDiscardConfirmation(path: String): Boolean =
        editorState.isDirty && editorState.file?.path != path

    fun stagePendingOpen(path: String) {
        pendingOpenPath = path
    }

    fun clearPendingOpen() {
        pendingOpenPath = null
    }

    // --- internals ---------------------------------------------------------

    private fun readDirectory(path: String, force: Boolean = false) {
        val loader = tree ?: return
        // Nothing to do when the folder is already loaded (or already loading),
        // which is what keeps expansion idempotent across recomposition.
        if (!loader.beginLoad(path, force)) {
            publish(loader)
            return
        }
        publish(loader)                                            // show this folder as loading
        viewModelScope.launch {
            try {
                loader.read(path)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (cause: Throwable) {
                logger.error("Reading a folder failed", cause, mapOf("path" to path))
            } finally {
                // Always leaves the folder in a terminal state, on success,
                // failure and cancellation alike.
                publish(loader)
            }
        }
    }

    private fun publish(loader: WorkspaceTreeLoader) {
        filesState = filesState.copy(
            loading = false,
            error = null,
            root = loader.snapshot(rootName).toFileNode(),
        )
    }

    private suspend fun resolveSession(): WorkspaceResult<WorkspaceSession> {
        val current = manager.current
        if (current != null && current.workspace.id.value == workspaceId) return success(current)
        return manager.openRecent(WorkspaceId(workspaceId))
    }

    private companion object {
        const val UNREADABLE_WORKSPACE =
            "Could not open this workspace. It may have been moved, or access was revoked."
    }
}
