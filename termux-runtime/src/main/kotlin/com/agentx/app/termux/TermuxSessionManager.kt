package com.agentx.app.termux

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns shell sessions for the whole process.
 *
 * Sessions live here and not in a `ViewModel` so that Compose recomposition, screen rotation or
 * navigating away from the Terminal tab cannot start a second shell: re-entering a screen calls
 * [open] again, which returns the running session for that workspace. A long-running
 * `npm run dev` therefore keeps streaming while the user edits files.
 *
 * No coroutine scope and no `GlobalScope`: process lifetime is owned by the sessions themselves
 * (Termux's `TerminalSession` spawns its own reader/writer/waiter threads and closes them on
 * exit), and this class only tracks them.
 */
class TermuxSessionManager(
    private val factory: TermuxSessionFactory,
    /** Upper bound only; one session per workspace is the normal case. */
    private val maxSessions: Int = DEFAULT_MAX_SESSIONS,
    private val onSessionsChanged: (List<TermuxSessionSnapshot>) -> Unit = {},
) {

    private val lock = Any()
    private val ordered = ArrayList<TermuxSession>()
    private val byWorkspaceKey = LinkedHashMap<String, String>()

    private val snapshotFlow = MutableStateFlow<List<TermuxSessionSnapshot>>(emptyList())
    private val activeFlow = MutableStateFlow<String?>(null)

    /** Everything the UI needs to draw the session list. */
    val snapshots: StateFlow<List<TermuxSessionSnapshot>> = snapshotFlow.asStateFlow()

    /** Handle of the session the terminal view is attached to. */
    val activeHandle: StateFlow<String?> = activeFlow.asStateFlow()

    fun sessions(): List<TermuxSession> = synchronized(lock) { ordered.toList() }

    fun find(handle: String): TermuxSession? = synchronized(lock) { ordered.firstOrNull { it.handle == handle } }

    fun active(): TermuxSession? = activeFlow.value?.let(::find)

    fun setActive(handle: String?) {
        if (handle != null && find(handle) == null) return
        activeFlow.value = handle
    }

    /**
     * Returns the session for [spec]'s workspace, starting one only when there is none running.
     *
     * A session that already exited is replaced, so a "restart after crash" is a plain call to
     * this method. Returns null only when the capacity limit would be exceeded.
     */
    fun open(spec: TermuxShellSpec): TermuxSession? {
        val created = synchronized(lock) {
            val existingHandle = byWorkspaceKey[spec.workspaceKey]
            val existing = existingHandle?.let { handle -> ordered.firstOrNull { it.handle == handle } }
            when {
                existing != null && existing.isRunning -> return@synchronized existing
                existing != null -> {
                    // Dead leftover: drop it from the tracking lists before replacing it.
                    detachLocked(existing.handle)
                    existing.finish()
                }
            }
            if (ordered.size >= maxSessions) return null
            // A factory that throws (no pty available, JNI missing) propagates: the caller
            // reports it instead of the terminal silently showing nothing.
            val session = factory.create(spec)
            ordered += session
            byWorkspaceKey[spec.workspaceKey] = session.handle
            session
        }
        if (activeFlow.value == null || activeFlow.value == created.handle) {
            activeFlow.value = created.handle
        }
        publish()
        return created
    }

    /**
     * Kills [handle] and starts a fresh session for [spec]. The old handle is never reused, so a
     * stale terminal view cannot drive the replacement's pty.
     */
    fun restart(handle: String, spec: TermuxShellSpec): TermuxSession? {
        val previous = find(handle)
        if (previous != null) terminate(handle)
        return open(spec)
    }

    /** Kills the process and forgets the session. */
    fun terminate(handle: String) {
        val removed = synchronized(lock) { detachLocked(handle) }
        removed?.finish()
        if (activeFlow.value == handle) {
            activeFlow.value = synchronized(lock) { ordered.firstOrNull()?.handle }
        }
        publish()
    }

    fun terminateAll() {
        val removed = synchronized(lock) {
            val all = ordered.toList()
            ordered.clear()
            byWorkspaceKey.clear()
            all
        }
        removed.forEach { runCatching { it.finish() } }
        activeFlow.value = null
        publish()
    }

    /**
     * Called when the shell exits on its own, so the snapshot stops claiming it is running and
     * the workspace stops pointing at a dead handle.
     */
    fun onSessionFinished(handle: String) {
        val session = find(handle) ?: return
        if (session.isRunning) return
        synchronized(lock) {
            byWorkspaceKey.entries.removeAll { it.value == handle }
        }
        publish()
    }

    /** Re-reads session state after a callback that changed it (title, cwd, cursor). */
    fun refresh() = publish()

    /** Terminates everything and stops publishing. Called when the app is shutting down. */
    fun release() {
        terminateAll()
    }

    private fun detachLocked(handle: String): TermuxSession? {
        val index = ordered.indexOfFirst { it.handle == handle }
        if (index < 0) return null
        val removed = ordered.removeAt(index)
        byWorkspaceKey.entries.removeAll { it.value == handle }
        return removed
    }

    private fun publish() {
        val snapshots = synchronized(lock) { ordered.map { it.toSnapshot() } }
        snapshotFlow.value = snapshots
        onSessionsChanged(snapshots)
    }

    private fun TermuxSession.toSnapshot(): TermuxSessionSnapshot = TermuxSessionSnapshot(
        handle = handle,
        workspaceKey = synchronized(lock) {
            byWorkspaceKey.entries.firstOrNull { it.value == handle }?.key
        } ?: handle,
        running = isRunning,
        exitStatus = exitStatus,
        title = title,
        workingDirectory = workingDirectory,
    )

    companion object {
        const val DEFAULT_MAX_SESSIONS: Int = 8
    }
}
