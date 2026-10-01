package com.agentx.app.termux

import android.util.Log
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
 * (the vendored `TerminalSession` spawns its own reader/writer/waiter threads and closes them on
 * exit, and [TerminalSessionAdapter] owns the launch and timeout bookkeeping), and this class only
 * tracks them.
 *
 * ## The invariant
 *
 * [open] and [restart] always resolve to a session object — including when the process could not be
 * created — unless the workspace cap is genuinely full of live shells. Nothing here may leave the
 * list empty while the terminal screen is asking for a shell, because an empty list is a terminal
 * with no handle, no reason and no way back.
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

    /** Handles for sessions that could not be constructed; never reused. */
    private var failureCounter = 0

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
     * A session that already finished is replaced, so "restart after a crash" is a plain call to
     * this method. Returns null only when the cap is full of *live* shells; a finished or failed
     * session is evicted to make room instead, because the cap must never be what wedges the
     * terminal.
     */
    fun open(spec: TermuxShellSpec): TermuxSession? {
        val disposals = ArrayList<TermuxSession>()
        val created: TermuxSession
        val superseded: String?
        synchronized(lock) {
            val existingHandle = byWorkspaceKey[spec.workspaceKey]
            superseded = existingHandle
            val existing = existingHandle?.let { handle -> ordered.firstOrNull { it.handle == handle } }
            if (existing != null && existing.isRunning) return existing
            if (existing != null) {
                // Detached before the replacement is built so the two never coexist under one
                // workspace key; finished outside the lock, because that kills a process.
                detachLocked(existing.handle)?.let(disposals::add)
            }
            if (ordered.size >= maxSessions) {
                val evictable = ordered.firstOrNull { it.state.isTerminal } ?: return null
                detachLocked(evictable.handle)?.let(disposals::add)
            }
            created = createLocked(spec)
            ordered += created
            byWorkspaceKey[spec.workspaceKey] = created.handle
        }

        val previousActive = activeFlow.value
        if (previousActive == null ||
            previousActive == superseded ||
            find(previousActive) == null ||
            disposals.any { it.handle == previousActive }
        ) {
            activeFlow.value = created.handle
        }

        // Published before the old sessions are killed and before the new one is started, so the UI
        // always has a handle to draw and to restart from — even if the start below fails outright.
        publish()
        disposals.forEach { session ->
            Log.i(TAG, "replacing dead session handle=${session.handle} state=${session.state}")
            runCatching { session.finish() }
        }
        created.start()
        publish()
        return created
    }

    /**
     * Kills [handle] if it exists and starts a fresh session for [spec].
     *
     * [handle] is nullable and may be stale on purpose. Restart is the only way out of a failed
     * session, and it used to be gated on `activeHandle != null` — which is exactly the condition
     * that is false after a start failure, so the button did nothing when it mattered. Restart now
     * does not depend on the old session existing or on receiving anything from it.
     */
    fun restart(handle: String?, spec: TermuxShellSpec): TermuxSession? {
        Log.i(TAG, "restart from handle=${handle ?: "(none)"} workspace=${spec.workspaceKey}")
        TerminalDiagnostics.record(
            TAG,
            "restart requested from=${handle ?: "(no handle)"} workspace=${spec.workspaceKey} " +
                "sessions=${sessions().size}",
        )
        if (handle != null) {
            // Detached without publishing so the UI never observes a gap with no session at all.
            val removed = synchronized(lock) { detachLocked(handle) }
            removed?.let { runCatching { it.finish() } }
        }
        return open(spec)
    }

    /**
     * Records a session whose shell could not even be described, and makes it the active one.
     *
     * This is what replaces a silent fallback to some other shell. When the Ubuntu runtime is not
     * ready there is no PRoot command to run, and the honest outcome is a FAILED session carrying
     * the reason — the screen draws it, the keyboard stays off, and Restart retries for real.
     * Opening an Android shell here instead is worse than failing: it looks like success while the
     * terminal is not the guest the user asked for, and it hides which link is broken.
     */
    fun openUnstartable(
        workspaceKey: String,
        executable: String?,
        reason: String,
        temporarySystemShell: Boolean = false,
    ): TermuxSession {
        val disposals = ArrayList<TermuxSession>(1)
        val created = synchronized(lock) {
            val existingHandle = byWorkspaceKey[workspaceKey]
            val existing = existingHandle?.let { handle -> ordered.firstOrNull { it.handle == handle } }
            if (existing != null && existing.isRunning) return existing
            if (existing != null) detachLocked(existing.handle)?.let(disposals::add)

            val session = UnstartableTermuxSession(
                handle = "unstartable-${failureCounter++}",
                executable = executable,
                temporarySystemShell = temporarySystemShell,
                failure = reason,
            )
            ordered += session
            byWorkspaceKey[workspaceKey] = session.handle
            session
        }
        activeFlow.value = created.handle
        publish()
        disposals.forEach { runCatching { it.finish() } }
        Log.w(TAG, "session could not be built workspace=$workspaceKey reason=$reason")
        TerminalDiagnostics.record(TAG, "FAILED session workspace=$workspaceKey reason=$reason")
        return created
    }

    /** Kills [handle] and replaces it with a recorded failure. Restart's counterpart to [restart]. */
    fun restartUnstartable(
        handle: String?,
        workspaceKey: String,
        executable: String?,
        reason: String,
    ): TermuxSession {
        // Detached without publishing, so the UI never observes a gap with no session at all.
        if (handle != null) {
            val removed = synchronized(lock) { detachLocked(handle) }
            removed?.let { runCatching { it.finish() } }
        }
        return openUnstartable(workspaceKey, executable, reason)
    }

    fun restartTemporarySystemShells(specFor: (TermuxSessionSnapshot) -> TermuxShellSpec): List<TermuxSession> {
        val targets = synchronized(lock) {
            ordered.map { it.toSnapshot() }.filter { snapshot ->
                snapshot.temporarySystemShell || snapshot.executable == TermuxShellResolver.SYSTEM_SHELL
            }
        }
        return targets.mapNotNull { snapshot -> restart(snapshot.handle, specFor(snapshot)) }
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
     * The single authoritative exit path. Called when a shell exits on its own — whether it was
     * running, or it died before it ever came up.
     *
     * Everything that needs to know a process ended goes through here, so no two components can
     * disagree about whether the shell is alive.
     */
    fun onSessionFinished(handle: String) {
        val session = find(handle) ?: return
        if (session.isRunning) return
        Log.i(TAG, "exit handle=$handle state=${session.state} exit=${session.exitStatus}")
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

    /**
     * Builds the session, turning a construction failure into a session that reports it.
     *
     * `Throwable`, not `Exception`: the PTY is reached through JNI, and a missing or unloadable
     * native library surfaces as `UnsatisfiedLinkError`, which is an `Error`. That case used to
     * escape `open` entirely and leave the list empty.
     */
    private fun createLocked(spec: TermuxShellSpec): TermuxSession = try {
        factory.create(spec)
    } catch (error: Throwable) {
        val handle = "failed-${failureCounter++}"
        Log.e(TAG, "could not create session for ${spec.workspaceKey} on $handle", error)
        TerminalDiagnostics.recordFailure(
            TAG,
            "session creation failed workspace=${spec.workspaceKey} executable=${spec.executable}",
            error,
        )
        UnstartableTermuxSession(
            handle = handle,
            executable = spec.executable,
            temporarySystemShell = spec.temporarySystemShell,
            failure = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName,
        )
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
        state = state,
        exitStatus = exitStatus,
        failure = failure,
        title = title,
        workingDirectory = workingDirectory,
        temporarySystemShell = temporarySystemShell,
        executable = executable,
    )

    companion object {
        const val DEFAULT_MAX_SESSIONS: Int = 8
        private const val TAG = "TermuxSessionManager"
    }
}
