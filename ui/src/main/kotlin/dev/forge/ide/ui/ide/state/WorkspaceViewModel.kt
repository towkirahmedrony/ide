package dev.forge.ide.ui.ide.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.forge.ide.ui.ide.data.ProjectCatalog
import dev.forge.ide.ui.ide.data.WorkspaceFileSource
import dev.forge.ide.ui.ide.model.FileNode
import dev.forge.ide.ui.ide.model.OpenFile
import dev.forge.ide.ui.ide.model.ProjectSummary
import kotlinx.coroutines.launch

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

/** Shared state for the workspace shell: current project, file tree, editor. */
class WorkspaceViewModel(
    private val workspaceId: String,
    private val catalog: ProjectCatalog,
    private val files: WorkspaceFileSource,
) : ViewModel() {

    var project by mutableStateOf<ProjectSummary?>(null)
        private set

    var filesState by mutableStateOf(FilesUiState())
        private set

    var editorState by mutableStateOf(EditorUiState())
        private set

    init {
        loadWorkspace()
    }

    fun loadWorkspace() {
        viewModelScope.launch {
            project = runCatching { catalog.find(workspaceId) }.getOrNull()
            filesState = filesState.copy(loading = true, error = null)
            runCatching { files.fileTree(workspaceId) }
                .onSuccess { tree ->
                    val rootDirs = tree.filter { it.isDirectory }.map { it.path }.toSet()
                    filesState = filesState.copy(loading = false, root = tree, expanded = rootDirs)
                }
                .onFailure {
                    filesState = filesState.copy(
                        loading = false,
                        error = it.message ?: "Failed to load files",
                    )
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
        filesState = filesState.copy(selectedPath = path)
        viewModelScope.launch {
            runCatching { files.readFile(path) }
                .onSuccess { content ->
                    editorState = EditorUiState(
                        file = OpenFile(path = path, name = path.substringAfterLast('/'), content = content),
                        draft = content,
                    )
                }
                .onFailure {
                    editorState = editorState.copy(statusMessage = "Could not open $path")
                }
        }
    }

    fun editDraft(text: String) {
        editorState = editorState.copy(draft = text, statusMessage = null)
    }

    fun save() {
        val file = editorState.file ?: return
        viewModelScope.launch {
            editorState = editorState.copy(saving = true, statusMessage = null)
            runCatching { files.writeFile(file.path, editorState.draft) }
                .onSuccess {
                    editorState = editorState.copy(
                        file = file.copy(content = editorState.draft),
                        saving = false,
                        statusMessage = "Saved ${file.name}",
                    )
                }
                .onFailure {
                    editorState = editorState.copy(
                        saving = false,
                        statusMessage = "Save failed: ${it.message ?: "unknown error"}",
                    )
                }
        }
    }

    fun dismissEditorStatus() {
        editorState = editorState.copy(statusMessage = null)
    }
}
