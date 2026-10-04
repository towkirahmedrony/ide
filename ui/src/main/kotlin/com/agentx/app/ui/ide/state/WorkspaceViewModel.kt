package com.agentx.app.ui.ide.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentx.app.codeintel.CodeIntelligence
import com.agentx.app.codeintel.CodeLanguage
import com.agentx.app.codeintel.CodeSymbol
import com.agentx.app.codeintel.FileOutline
import com.agentx.app.codeintel.OutlineNode
import com.agentx.app.codeintel.SourceFile
import com.agentx.app.codeintel.SourcePosition
import com.agentx.app.core.ForgeResult
import com.agentx.app.core.logging.ForgeLogger
import com.agentx.app.core.logging.ForgeLoggers
import com.agentx.app.core.logging.LogLevel
import com.agentx.app.core.success
import com.agentx.app.context.WorkspaceSelectionState
import com.agentx.app.ui.ide.model.DirectoryLoadState
import com.agentx.app.ui.ide.model.FileNode
import com.agentx.app.ui.ide.model.FileNodeKind
import com.agentx.app.ui.ide.model.OpenFile
import com.agentx.app.ui.ide.model.ProjectSummary
import com.agentx.app.ui.ide.model.toFileNode
import com.agentx.app.ui.ide.model.toSummary
import com.agentx.app.workspace.WorkspaceError
import com.agentx.app.workspace.WorkspaceErrorCode
import com.agentx.app.workspace.WorkspaceFileOpener
import com.agentx.app.workspace.WorkspaceId
import com.agentx.app.workspace.WorkspaceManager
import com.agentx.app.workspace.WorkspacePath
import com.agentx.app.workspace.WorkspaceResult
import com.agentx.app.workspace.WorkspaceSession
import com.agentx.app.workspace.WorkspaceTreeLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    /** Result of the last create/rename/delete, shown as a dismissible banner. */
    val message: String? = null,
    val messageIsError: Boolean = false,
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
    /**
     * The file changed on disk (a terminal command, an agent, an external tool) while the editor
     * held unsaved edits. The user's draft is never replaced in this case — only reported.
     */
    val externallyModified: Boolean = false,
    /** A save was stopped because the file changed on disk; the user must choose to overwrite. */
    val saveConflict: Boolean = false,
) {
    val isDirty: Boolean get() = file != null && draft != file.content
    val lineCount: Int get() = if (draft.isEmpty()) 1 else draft.count { it == '\n' } + 1
}

/**
 * Structure of the file open in the editor.
 *
 * [unavailableReason] is set when the file was analysed but no structure could
 * be produced — an unsupported language, no parser for it in this build, a file
 * beyond the analysis limits. It is shown as-is, so the editor never implies an
 * empty file is an unparsed one.
 */
data class EditorStructureUiState(
    val language: CodeLanguage = CodeLanguage.UNKNOWN,
    val outline: FileOutline? = null,
    val unavailableReason: String? = null,
    val analyzing: Boolean = false,
    val cursorSymbol: CodeSymbol? = null,
) {
    val hasOutline: Boolean get() = outline != null && !outline.isEmpty

    val symbolCount: Int get() = outline?.symbolCount ?: 0

    val truncated: Boolean get() = outline?.truncated == true

    val hasSyntaxErrors: Boolean get() = outline?.hasSyntaxErrors == true

    /** Symbols in source order, each with how deep it is nested. */
    fun flattened(limit: Int = MAX_PANEL_SYMBOLS): List<Pair<Int, CodeSymbol>> {
        val outline = outline ?: return emptyList()
        val result = ArrayList<Pair<Int, CodeSymbol>>(minOf(outline.symbolCount, limit))

        fun visit(node: OutlineNode, depth: Int) {
            if (result.size >= limit) return
            result += depth to node.symbol
            node.children.forEach { child -> visit(child, depth + 1) }
        }

        outline.roots.forEach { root -> visit(root, 0) }
        return result
    }

    companion object {
        /** Rows the editor panel renders; the outline itself is not limited by this. */
        const val MAX_PANEL_SYMBOLS = 60
    }
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
    /**
     * Shared with the Context Engine: the file this editor opens becomes the
     * agent's selected file and the newest recently used file.
     */
    private val selection: WorkspaceSelectionState = WorkspaceSelectionState(),
    /**
     * Structural understanding of the open file. Optional: without it the editor
     * has no outline, and it is never simulated.
     */
    private val codeIntelligence: CodeIntelligence? = null,
    private val logger: ForgeLogger = ForgeLoggers.create(LogLevel.WARN, baseFields = mapOf("screen" to "files")),
) : ViewModel() {

    var project by mutableStateOf<ProjectSummary?>(null)
        private set

    var filesState by mutableStateOf(FilesUiState())
        private set

    var editorState by mutableStateOf(EditorUiState())
        private set

    /** Outline of the file in the editor, for the structure panel. */
    var structureState by mutableStateOf(EditorStructureUiState())
        private set

    /** Set when the user asked to open a file while the editor had unsaved changes. */
    var pendingOpenPath by mutableStateOf<String?>(null)
        private set

    private var session: WorkspaceSession? = null
    private var tree: WorkspaceTreeLoader? = null
    private var rootName: String = ""
    private var structureJob: Job? = null
    private var structureRequest: Int = 0
    private var cursorPosition: SourcePosition? = null

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
        // Recorded before the read, so the agent's context reflects what the
        // user is looking at even if the file turns out to be unreadable.
        selection.openFile(path)
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
                is WorkspaceFileOpener.Opened.Text -> {
                    editorState = EditorUiState(
                        file = OpenFile(path = outcome.path, name = outcome.name, content = outcome.content),
                        draft = outcome.content,
                    )
                    cursorPosition = null
                    // First paint analyses immediately; later keystrokes are debounced.
                    refreshStructure(debounceMillis = 0L)
                }

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
        refreshStructure(debounceMillis = STRUCTURE_DEBOUNCE_MILLIS)
    }

    /**
     * Writes the draft back to the file the editor opened.
     *
     * Before writing, the file is re-read and compared with the content the editor loaded. When
     * they differ, the file changed on disk since it was opened, so the save is stopped and
     * [EditorUiState.saveConflict] is raised instead of silently overwriting the newer content.
     * [overwrite] is the user's explicit choice to proceed anyway.
     */
    fun save(overwrite: Boolean = false) {
        val active = session ?: return
        val file = editorState.file ?: return
        viewModelScope.launch {
            editorState = editorState.copy(saving = true, statusMessage = null)
            if (!overwrite) {
                when (val disk = active.fileSystem.readFile(file.path)) {
                    is ForgeResult.Success -> if (disk.value != file.content) {
                        editorState = editorState.copy(saving = false, saveConflict = true)
                        return@launch
                    }

                    is ForgeResult.Failure -> if (disk.error.code == WorkspaceErrorCode.NOT_FOUND) {
                        editorState = editorState.copy(
                            saving = false,
                            saveConflict = true,
                            statusMessage = "“${file.name}” no longer exists on disk.",
                        )
                        return@launch
                    }
                }
            }
            when (val result = active.fileSystem.writeFile(file.path, editorState.draft)) {
                is ForgeResult.Success -> editorState = editorState.copy(
                    file = file.copy(content = editorState.draft),
                    saving = false,
                    saveConflict = false,
                    externallyModified = false,
                    statusMessage = "Saved ${file.name}",
                )

                is ForgeResult.Failure -> editorState = editorState.copy(
                    saving = false,
                    statusMessage = result.error.userMessage,
                )
            }
        }
    }

    fun dismissSaveConflict() {
        editorState = editorState.copy(saveConflict = false)
    }

    /** Discards the editor's unsaved changes and shows what is on disk right now. */
    fun reloadOpenFile() {
        val active = session ?: return
        val file = editorState.file ?: return
        viewModelScope.launch {
            when (val result = active.fileSystem.readFile(file.path)) {
                is ForgeResult.Success -> {
                    editorState = editorState.copy(
                        file = file.copy(content = result.value),
                        draft = result.value,
                        externallyModified = false,
                        saveConflict = false,
                        statusMessage = "Reloaded ${file.name} from disk",
                    )
                    cursorPosition = null
                    refreshStructure(debounceMillis = 0L)
                }

                is ForgeResult.Failure -> editorState = editorState.copy(statusMessage = result.error.userMessage)
            }
        }
    }

    fun dismissEditorStatus() {
        editorState = editorState.copy(statusMessage = null)
    }

    // --- file operations -----------------------------------------------------

    /**
     * Re-reads the folders the user has open and reconciles the editor with disk.
     *
     * This is how a change made anywhere else — a command in the terminal, an agent, another
     * tool — reaches the browser. It re-reads only the folders that were actually opened, so it
     * costs one directory read per folder on screen and never walks the whole project.
     */
    fun reload() {
        val active = session ?: return
        val loader = tree ?: return
        val paths = filesState.expanded + WorkspacePath.ROOT
        viewModelScope.launch {
            paths.forEach { path -> reloadDirectory(loader, path) }
            publish(loader)
            reconcileOpenFile(active)
        }
    }

    /** Creates an empty file named [name] inside [parentPath]. */
    fun createFile(parentPath: String, name: String) {
        val active = session ?: return
        viewModelScope.launch {
            val target = childPath(parentPath, name) ?: return@launch
            when (val result = active.fileSystem.createFile(target)) {
                is ForgeResult.Success -> {
                    filesState = filesState.copy(selectedPath = target)
                    report("Created ${result.value.name}")
                    refreshDirectory(parentPath)
                }

                is ForgeResult.Failure -> reportError(result.error)
            }
        }
    }

    /** Creates a directory named [name] inside [parentPath]. */
    fun createDirectory(parentPath: String, name: String) {
        val active = session ?: return
        viewModelScope.launch {
            val target = childPath(parentPath, name) ?: return@launch
            when (val result = active.fileSystem.createDirectory(target)) {
                is ForgeResult.Success -> {
                    filesState = filesState.copy(expanded = filesState.expanded + parentPath)
                    report("Created folder ${result.value.name}")
                    refreshDirectory(parentPath)
                }

                is ForgeResult.Failure -> reportError(result.error)
            }
        }
    }

    /** Renames [path] within its current folder, retargeting the editor when it held the file. */
    fun rename(path: String, newName: String) {
        val active = session ?: return
        viewModelScope.launch {
            val parent = WorkspacePath.parent(path)
            val target = childPath(parent, newName) ?: return@launch
            when (val result = active.fileSystem.rename(path, newName)) {
                is ForgeResult.Success -> {
                    retargetEditorAfterRename(path, target)
                    prunePaths(path)
                    report("Renamed to ${result.value.name}")
                    refreshDirectory(parent)
                }

                is ForgeResult.Failure -> reportError(result.error)
            }
        }
    }

    /** Deletes [path] and everything under it, closing the editor when it held the file. */
    fun delete(path: String) {
        val active = session ?: return
        viewModelScope.launch {
            when (val result = active.fileSystem.delete(path)) {
                is ForgeResult.Success -> {
                    val open = editorState.file
                    if (open != null && (open.path == path || open.path.startsWith("$path/"))) {
                        editorState = EditorUiState(statusMessage = "“${open.name}” was deleted.")
                    }
                    prunePaths(path)
                    report("Deleted ${WorkspacePath.name(path)}")
                    refreshDirectory(WorkspacePath.parent(path))
                }

                is ForgeResult.Failure -> reportError(result.error)
            }
        }
    }

    fun dismissFilesMessage() {
        filesState = filesState.copy(message = null, messageIsError = false)
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

    // --- code structure ----------------------------------------------------

    /**
     * Reports the caret so the panel can name the symbol it is inside. Lines and
     * columns are 1-based, exactly as the editor shows them.
     */
    fun onCursorMoved(line: Int, column: Int) {
        cursorPosition = SourcePosition(line.coerceAtLeast(1), column.coerceAtLeast(1))
        refreshCursorSymbol()
    }

    /** Re-analyses the open file; used when the structure panel is reopened. */
    fun refreshStructure() = refreshStructure(debounceMillis = 0L)

    /**
     * Analyses the editor's current text off the main thread.
     *
     * Typing is debounced, cancellation drops work the user has already moved on
     * from, and the engine reuses the analysis of unchanged content — so the
     * outline follows the file without re-parsing on every recomposition. Only
     * the newest request may publish a result, so a slow earlier parse can never
     * overwrite a newer one.
     */
    private fun refreshStructure(debounceMillis: Long) {
        val intelligence = codeIntelligence ?: return
        val file = editorState.file
        if (file == null) {
            structureJob?.cancel()
            structureRequest++
            structureState = EditorStructureUiState()
            return
        }

        val content = editorState.draft
        val request = ++structureRequest
        structureJob?.cancel()
        structureState = structureState.copy(analyzing = true)
        structureJob = viewModelScope.launch {
            if (debounceMillis > 0L) delay(debounceMillis)
            val outcome = try {
                intelligence.parseFile(SourceFile(path = file.path, content = content))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (cause: Throwable) {
                logger.error("Analysing a file failed", cause, mapOf("path" to file.path))
                null
            }
            if (request != structureRequest) return@launch
            structureState = when (outcome) {
                null -> structureState.copy(analyzing = false)
                is ForgeResult.Success -> EditorStructureUiState(
                    language = outcome.value.language,
                    outline = outcome.value.outline,
                )
                is ForgeResult.Failure -> EditorStructureUiState(
                    language = intelligence.detectLanguage(file.path),
                    unavailableReason = outcome.error.message,
                )
            }
            refreshCursorSymbol()
        }
    }

    private fun refreshCursorSymbol() {
        val position = cursorPosition ?: return
        structureState = structureState.copy(cursorSymbol = structureState.outline?.symbolAt(position))
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

    /** Force re-reads [path] after a mutation so the browser shows the real project at once. */
    private fun refreshDirectory(path: String) {
        readDirectory(path, force = true)
    }

    /** Re-reads one already-open folder during [reload]; cancellation always propagates. */
    private suspend fun reloadDirectory(loader: WorkspaceTreeLoader, path: String) {
        if (!loader.beginLoad(path, force = true)) return
        try {
            loader.read(path)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (cause: Throwable) {
            logger.error("Refreshing a folder failed", cause, mapOf("path" to path))
        }
    }

    /**
     * Brings the editor back in step with the file on disk.
     *
     * A clean editor is reloaded transparently. An editor with unsaved edits is never
     * overwritten: it is only flagged as [EditorUiState.externallyModified], so the user keeps
     * their draft and decides what to do.
     */
    private suspend fun reconcileOpenFile(active: WorkspaceSession) {
        val file = editorState.file ?: return
        when (val result = active.fileSystem.readFile(file.path)) {
            is ForgeResult.Success -> {
                val disk = result.value
                when {
                    disk == file.content -> Unit
                    editorState.isDirty -> editorState = editorState.copy(externallyModified = true)
                    else -> {
                        editorState = editorState.copy(
                            file = file.copy(content = disk),
                            draft = disk,
                            externallyModified = false,
                            saveConflict = false,
                            statusMessage = "Reloaded ${file.name} from disk",
                        )
                        cursorPosition = null
                        refreshStructure(debounceMillis = 0L)
                    }
                }
            }

            is ForgeResult.Failure -> if (result.error.code == WorkspaceErrorCode.NOT_FOUND) {
                editorState = editorState.copy(
                    externallyModified = true,
                    statusMessage = "“${file.name}” was changed on disk.",
                )
            }
        }
    }

    /** Validates a single-segment [name] under [parent]; reports and returns null when invalid. */
    private fun childPath(parent: String, name: String): String? =
        when (val child = WorkspacePath.child(parent, name.trim())) {
            is ForgeResult.Success -> child.value
            is ForgeResult.Failure -> {
                reportError(child.error)
                null
            }
        }

    /** Retargets the open editor when the file it holds was renamed or moved. */
    private fun retargetEditorAfterRename(oldPath: String, newPath: String) {
        val open = editorState.file ?: return
        val moved = when {
            open.path == oldPath -> newPath
            open.path.startsWith("$oldPath/") -> newPath + open.path.removePrefix(oldPath)
            else -> return
        }
        editorState = editorState.copy(file = open.copy(path = moved, name = WorkspacePath.name(moved)))
    }

    /** Drops expanded/selected/focused paths that sat inside a renamed or deleted subtree. */
    private fun prunePaths(prefix: String) {
        val inside = { path: String -> path == prefix || path.startsWith("$prefix/") }
        filesState = filesState.copy(
            expanded = filesState.expanded.filterNot(inside).toSet(),
            selectedPath = filesState.selectedPath?.takeIf { !inside(it) },
            focusedPath = if (inside(filesState.focusedPath)) WorkspacePath.parent(prefix) else filesState.focusedPath,
        )
    }

    private fun report(message: String) {
        filesState = filesState.copy(message = message, messageIsError = false)
    }

    private fun reportError(error: WorkspaceError) {
        filesState = filesState.copy(message = error.userMessage, messageIsError = true)
    }

    private suspend fun resolveSession(): WorkspaceResult<WorkspaceSession> {
        val current = manager.current
        if (current != null && current.workspace.id.value == workspaceId) return success(current)
        return manager.openRecent(WorkspaceId(workspaceId))
    }

    private companion object {
        /** Quiet period after the last keystroke before the file is re-analysed. */
        const val STRUCTURE_DEBOUNCE_MILLIS = 250L

        const val UNREADABLE_WORKSPACE =
            "Could not open this workspace. It may have been moved, or access was revoked."
    }
}
