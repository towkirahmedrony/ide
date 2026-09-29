package com.agentx.app.context

import com.agentx.app.core.valueOrNull
import com.agentx.app.workspace.WorkspaceFileSystem
import com.agentx.app.workspace.WorkspaceManager
import com.agentx.app.workspace.WorkspacePath

/**
 * In-memory selection state for the open workspace: which file is in the
 * editor, which tabs are open, and what was touched recently. All paths are
 * workspace-relative; anything the workspace runtime would reject is ignored.
 *
 * It is intentionally plain state, so the UI can update it without knowing
 * anything about the Context Engine.
 */
class WorkspaceSelectionState {

    private val lock = Any()
    private val openPaths = LinkedHashSet<String>()
    private val recent = ArrayDeque<String>()
    private var selected: String? = null

    fun select(path: String) = synchronized(lock) {
        val normalized = normalize(path) ?: return@synchronized
        selected = normalized
        noteRecentLocked(normalized)
    }

    fun openFile(path: String) = synchronized(lock) {
        val normalized = normalize(path) ?: return@synchronized
        openPaths.clear()
        openPaths += normalized
        selected = normalized
        noteRecentLocked(normalized)
    }

    fun closeFile(path: String) = synchronized(lock) {
        val normalized = normalize(path) ?: return@synchronized
        openPaths -= normalized
        if (selected == normalized) selected = openPaths.lastOrNull()
    }

    /** Records a file as recently used without changing the editor selection. */
    fun noteRecent(path: String) = synchronized(lock) {
        normalize(path)?.let { noteRecentLocked(it) }
    }

    fun selectedFile(): String? = synchronized(lock) { selected }

    fun openFiles(): List<String> = synchronized(lock) { openPaths.toList() }

    fun recentFiles(limit: Int = MAX_RECENT): List<String> =
        synchronized(lock) { recent.take(limit.coerceAtLeast(0)) }

    fun clear() = synchronized(lock) {
        selected = null
        openPaths.clear()
        recent.clear()
    }

    private fun noteRecentLocked(path: String) {
        recent.remove(path)
        recent.addFirst(path)
        while (recent.size > MAX_RECENT) recent.removeLast()
    }

    private fun normalize(path: String): String? =
        WorkspacePath.normalize(path).valueOrNull()?.takeIf { it.isNotEmpty() }

    companion object {
        /** Upper bound for the recent list; the oldest entry falls off. */
        const val MAX_RECENT = 20
    }
}

/**
 * [WorkspaceContextProvider] backed by the Workspace Runtime and the editor's
 * selection state. It reads only metadata the manager already holds: no folder
 * is walked and no file is read here.
 */
class WorkspaceRuntimeContextProvider(
    private val manager: WorkspaceManager,
    val selection: WorkspaceSelectionState = WorkspaceSelectionState(),
) : WorkspaceContextProvider {

    override suspend fun snapshot(): WorkspaceSnapshot? {
        val session = manager.current ?: return null
        return WorkspaceSnapshot(
            workspaceId = session.workspace.id.value,
            name = session.workspace.metadata.name,
            rootPath = session.workspace.metadata.displayLocation,
            selectedFile = selection.selectedFile(),
            openFiles = selection.openFiles(),
            recentFiles = selection.recentFiles(),
        )
    }

    override suspend fun fileSystem(): WorkspaceFileSystem? = manager.current?.fileSystem

    override fun markAccessed(path: String) {
        selection.noteRecent(path)
    }
}

/**
 * Provider that can be bound after the Workspace Runtime is created. The
 * Context Engine fails closed (no workspace context at all) until [bind] is
 * called, mirroring how the Tool System binds its workspace resolver.
 */
class DelegatingWorkspaceContextProvider(
    initial: WorkspaceContextProvider? = null,
) : WorkspaceContextProvider {

    @Volatile
    private var delegate: WorkspaceContextProvider? = initial

    fun isBound(): Boolean = delegate != null

    fun bind(provider: WorkspaceContextProvider) {
        delegate = provider
    }

    override suspend fun snapshot(): WorkspaceSnapshot? = delegate?.snapshot()

    override suspend fun fileSystem(): WorkspaceFileSystem? = delegate?.fileSystem()

    override fun markAccessed(path: String) {
        delegate?.markAccessed(path)
    }
}
