package dev.forge.ide.ui.ide.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.forge.ide.core.ForgeResult
import dev.forge.ide.core.success
import dev.forge.ide.ui.ide.model.FileNode
import dev.forge.ide.ui.ide.model.FileNodeKind
import dev.forge.ide.ui.ide.model.OpenFile
import dev.forge.ide.ui.ide.model.ProjectSummary
import dev.forge.ide.ui.ide.model.toSummary
import dev.forge.ide.workspace.WorkspaceDirectory
import dev.forge.ide.workspace.WorkspaceFile
import dev.forge.ide.workspace.WorkspaceFileSystem
import dev.forge.ide.workspace.WorkspaceId
import dev.forge.ide.workspace.WorkspaceManager
import dev.forge.ide.workspace.WorkspacePath
import dev.forge.ide.workspace.WorkspaceResult
import dev.forge.ide.workspace.WorkspaceSession
import kotlinx.coroutines.launch

private const val MAX_TREE_DEPTH = 12

data class FilesUiState(
    val loading: Boolean = true,
    val root: List<FileNode> = emptyList(),
    val expanded: Set<String> = emptySet(),
    val selectedPath: String? = null,
    val error: String? = null,
) {
    val isEmpty: Boolean get() = !loading && error == null && root.isEmpty()
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
 * State holder for the workspace shell. It reads the file tree and file contents
 * through the domain [WorkspaceFileSystem] only, never through Android APIs.
 */
class WorkspaceViewModel(
    private val workspaceId: String,
    private val manager: WorkspaceManager,
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

    init {
        loadWorkspace()
    }

    fun loadWorkspace() {
        viewModelScope.launch {
            filesState = filesState.copy(loading = true, error = null)
            when (val resolved = resolveSession()) {
                is ForgeResult.Failure -> {
                    session = null
                    filesState = filesState.copy(
                        loading = false,
                        root = emptyList(),
                        error = resolved.error.userMessage,
                    )
                }

                is ForgeResult.Success -> {
                    val active = resolved.value
                    session = active
                    project = active.workspace.metadata.toSummary()

                    when (val tree = buildTree(active.fileSystem, WorkspacePath.ROOT)) {
                        is ForgeResult.Success -> filesState = filesState.copy(
                            loading = false,
                            root = tree.value,
                            expanded = tree.value.filter { it.isDirectory }.map { it.path }.toSet(),
                            error = null,
                        )

                        is ForgeResult.Failure -> filesState = filesState.copy(
                            loading = false,
                            root = emptyList(),
                            error = tree.error.userMessage,
                        )
                    }
                }
            }
        }
    }

    fun toggleDirectory(path: String) {
        val expanded = filesState.expanded
        filesState = filesState.copy(
            expanded = if (path in expanded) expanded - path else expanded + path,
        )
    }

    fun openFile(path: String) {
        val active = session ?: return
        filesState = filesState.copy(selectedPath = path)
        viewModelScope.launch {
            when (val result = active.fileSystem.readFile(path)) {
                is ForgeResult.Success -> editorState = EditorUiState(
                    file = OpenFile(path = path, name = WorkspacePath.name(path), content = result.value),
                    draft = result.value,
                )

                is ForgeResult.Failure -> editorState = editorState.copy(
                    statusMessage = result.error.userMessage,
                )
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

    private suspend fun resolveSession(): WorkspaceResult<WorkspaceSession> {
        val current = manager.current
        if (current != null && current.workspace.id.value == workspaceId) return success(current)
        return manager.openRecent(WorkspaceId(workspaceId))
    }

    private suspend fun buildTree(
        fileSystem: WorkspaceFileSystem,
        path: String,
        depth: Int = 0,
    ): WorkspaceResult<List<FileNode>> {
        if (depth > MAX_TREE_DEPTH) return success(emptyList())

        val listing = when (val result = fileSystem.list(path)) {
            is ForgeResult.Success -> result.value
            is ForgeResult.Failure -> return result
        }

        val nodes = ArrayList<FileNode>(listing.size)
        for (node in listing) {
            when (node) {
                is WorkspaceDirectory -> {
                    val children = when (val result = buildTree(fileSystem, node.path, depth + 1)) {
                        is ForgeResult.Success -> result.value
                        is ForgeResult.Failure -> emptyList()
                    }
                    nodes += FileNode(
                        path = node.path,
                        name = node.name,
                        kind = FileNodeKind.DIRECTORY,
                        children = children,
                    )
                }

                is WorkspaceFile -> nodes += FileNode(
                    path = node.path,
                    name = node.name,
                    kind = FileNodeKind.FILE,
                )
            }
        }
        return success(nodes)
    }
}
