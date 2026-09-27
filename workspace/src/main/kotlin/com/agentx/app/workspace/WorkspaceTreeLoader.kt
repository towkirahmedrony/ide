package com.agentx.app.workspace

import com.agentx.app.core.ForgeResult
import com.agentx.app.core.errorOrNull
import com.agentx.app.core.valueOrNull
import kotlinx.coroutines.CancellationException

/** What a [WorkspaceTreeEntry] describes. */
enum class WorkspaceEntryKind { FILE, DIRECTORY }

/**
 * The lifecycle of a single directory.
 *
 * A directory is `Unloaded` until something asks for it, which is what keeps
 * opening a large project cheap: only the levels the user actually looks at are
 * ever read from storage. A directory is never left in [Loading] — every read
 * ends in [Loaded] or [Failed], including when it is cancelled.
 */
sealed interface DirectoryState {

    /** Children have not been requested yet. Reading this directory costs nothing. */
    data object Unloaded : DirectoryState

    /** A read is in flight. */
    data object Loading : DirectoryState

    /** Children were read. [children] may be empty, which means "empty folder". */
    data class Loaded(val children: List<WorkspaceNode>) : DirectoryState

    /** The read failed. The directory can be retried; the error is user-presentable. */
    data class Failed(val error: WorkspaceError) : DirectoryState
}

/**
 * One entry of the lazily loaded workspace tree.
 *
 * [directory] is the state of a directory and is `null` for files; [children] is
 * only populated once the directory reports [DirectoryState.Loaded].
 */
data class WorkspaceTreeEntry(
    val path: String,
    val name: String,
    val kind: WorkspaceEntryKind,
    val directory: DirectoryState? = null,
    val children: List<WorkspaceTreeEntry> = emptyList(),
) {
    val isDirectory: Boolean get() = kind == WorkspaceEntryKind.DIRECTORY
}

/**
 * Reads a workspace one directory level at a time.
 *
 * The loader deliberately has no recursive "build the whole tree" entry point:
 * callers expand exactly the folders the user opened, so browsing a project with
 * thousands of files costs one directory read per folder on screen. It also
 * never reads file contents — that is [WorkspaceFileOpener]'s job.
 *
 * State transitions are idempotent and guarded:
 * - reading a directory that is already [DirectoryState.Loaded] does nothing,
 *   so repeated recomposition or double taps cannot start a second scan;
 * - a directory that is already being read is not read twice;
 * - a read that fails or is cancelled always leaves a terminal state behind,
 *   never [DirectoryState.Loading].
 *
 * This class is not thread-safe: drive it from a single coroutine context (the
 * UI dispatcher's), which is how the IDE shell uses it.
 */
class WorkspaceTreeLoader(private val fileSystem: WorkspaceFileSystem) {

    private val states = linkedMapOf<String, DirectoryState>()
    private val inFlight = mutableSetOf<String>()

    /** What a directory looked like before the in-flight read replaced it. */
    private val previousStates = mutableMapOf<String, DirectoryState>()

    /** The state of [path]; [DirectoryState.Unloaded] when it was never requested. */
    fun stateOf(path: String): DirectoryState = states[path] ?: DirectoryState.Unloaded

    /** How many directories have actually been read. Never grows on its own. */
    val loadedDirectoryCount: Int get() = states.values.count { it is DirectoryState.Loaded }

    /**
     * Marks [path] as loading and reports whether a read is required.
     *
     * Returns `false` — and changes nothing — when the directory is already
     * loaded (unless [force]) or when a read for it is already in flight, so the
     * caller can publish the [DirectoryState.Loading] state before awaiting.
     */
    fun beginLoad(path: String, force: Boolean = false): Boolean {
        val normalized = WorkspacePath.normalize(path)
        val rel = normalized.valueOrNull()
        if (rel == null) {
            states[path] = DirectoryState.Failed(normalized.errorOrNull() ?: invalidPath(path))
            return false
        }
        if (rel in inFlight) return false
        val previous = states[rel]
        if (!force && previous is DirectoryState.Loaded) return false
        previousStates[rel] = previous ?: DirectoryState.Unloaded
        inFlight += rel
        states[rel] = DirectoryState.Loading
        return true
    }

    /**
     * Performs the read for [path] and records the outcome.
     *
     * Throws only [CancellationException]; cancellation restores the state the
     * directory had before the read so a cancelled load cannot leave a folder
     * spinning forever.
     */
    suspend fun read(path: String): DirectoryState {
        val normalized = WorkspacePath.normalize(path)
        val rel = normalized.valueOrNull()
        if (rel == null) {
            return DirectoryState.Failed(normalized.errorOrNull() ?: invalidPath(path))
                .also { states[path] = it }
        }
        if (rel !in inFlight && !beginLoad(rel)) return stateOf(rel)

        return try {
            when (val result = fileSystem.list(rel)) {
                is ForgeResult.Success -> DirectoryState.Loaded(result.value)
                is ForgeResult.Failure -> DirectoryState.Failed(result.error)
            }.also { states[rel] = it }
        } catch (cancelled: CancellationException) {
            // Never leave a cancelled read looking like it is still running.
            states[rel] = previousStates[rel] ?: DirectoryState.Unloaded
            throw cancelled
        } catch (cause: Throwable) {
            DirectoryState.Failed(
                WorkspaceError(
                    code = WorkspaceErrorCode.UNKNOWN,
                    message = UNREADABLE_FOLDER,
                    path = rel,
                    cause = cause,
                ),
            ).also { states[rel] = it }
        } finally {
            inFlight -= rel
            previousStates -= rel
        }
    }

    /**
     * Convenience for callers that do not render intermediate states:
     * [beginLoad] followed by [read].
     */
    suspend fun load(path: String, force: Boolean = false): DirectoryState =
        if (beginLoad(path, force)) read(path) else stateOf(path)

    /** Forgets everything, so the next [beginLoad] reads from storage again. */
    fun reset() {
        states.clear()
        inFlight.clear()
        previousStates.clear()
    }

    /** Immutable snapshot of the tree as far as it is known right now. */
    fun snapshot(rootName: String = ROOT_NAME): WorkspaceTreeEntry = WorkspaceTreeEntry(
        path = WorkspacePath.ROOT,
        name = rootName,
        kind = WorkspaceEntryKind.DIRECTORY,
        directory = stateOf(WorkspacePath.ROOT),
        children = childrenOf(WorkspacePath.ROOT),
    )

    private fun childrenOf(path: String): List<WorkspaceTreeEntry> {
        val loaded = states[path] as? DirectoryState.Loaded ?: return emptyList()
        return loaded.children.map { child ->
            when (child) {
                is WorkspaceDirectory -> WorkspaceTreeEntry(
                    path = child.path,
                    name = child.name,
                    kind = WorkspaceEntryKind.DIRECTORY,
                    directory = stateOf(child.path),
                    children = childrenOf(child.path),
                )

                is WorkspaceFile -> WorkspaceTreeEntry(
                    path = child.path,
                    name = child.name,
                    kind = WorkspaceEntryKind.FILE,
                )
            }
        }
    }

    private fun invalidPath(path: String): WorkspaceError =
        WorkspaceError(WorkspaceErrorCode.INVALID_PATH, "The path is not valid.", path)

    companion object {
        const val ROOT_NAME: String = "Workspace"

        /** Shown when storage threw while listing a folder. */
        const val UNREADABLE_FOLDER: String = "Could not read this folder."
    }
}
