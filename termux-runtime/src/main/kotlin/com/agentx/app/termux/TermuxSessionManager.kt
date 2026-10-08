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

    /**
     * The command each live session was actually started with, keyed by handle.
     *
     * Reuse is decided by the process that exists and not by the workspace key alone. A guest
     * shell's working directory and its project bind are fixed at `exec` time, so a shell started
     * before storage access was granted — or before the project resolved — runs in the guest home
     * and cannot be moved to the project by any later call. Without this record the manager would
     * hand that process back for the same workspace and the terminal would claim to be in
     * `/workspace` while `pwd` still answered `/root`.
     */
    private val specByHandle = HashMap<String, TermuxShellSpec>()

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
        DeveloperLogger.info(
            DeveloperLogCategory.SESSION,
            "Session creation started workspace=${spec.workspaceKey} executable=${spec.executable}",
        )
        val disposals = ArrayList<TermuxSession>()
        val created: TermuxSession
        val superseded: String?
        synchronized(lock) {
            val existingHandle = byWorkspaceKey[spec.workspaceKey]
            superseded = existingHandle
            val existing = existingHandle?.let { handle -> ordered.firstOrNull { it.handle == handle } }
            if (existing != null && existing.isRunning) {
                // A running shell is reused only when it is the very process this spec describes.
                // The guest cwd and the project bind are fixed when the process is started, so a
                // shell started for a different mapping — the guest home before storage access was
                // granted, or an earlier project binding — cannot be moved into the project. It is
                // replaced rather than handed back, so the process the user gets is always the one
                // the binding resolves to now.
                if (specByHandle[existing.handle] == spec) return existing
                DeveloperLogger.info(
                    DeveloperLogCategory.SESSION,
                    "Replacing running session with a changed command workspace=${spec.workspaceKey} " +
                        "handle=${existing.handle}",
                )
                TerminalDiagnostics.record(
                    TAG,
                    "replacing running session with a changed command workspace=${spec.workspaceKey} " +
                        "handle=${existing.handle}",
                )
                detachLocked(existing.handle)?.let(disposals::add)
            } else if (existing != null) {
                // Detached before the replacement is built so the two never coexist under one
                // workspace key; finished outside the lock, because that kills a process.
                detachLocked(existing.handle)?.let(disposals::add)
            }
            if (ordered.size >= maxSessions) {
                val evictable = ordered.firstOrNull { it.state.isTerminal } ?: return null
                detachLocked(evictable.handle)?.let(disposals::add)
            }
            created = createLocked(spec)
            specByHandle[created.handle] = spec
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
        val previous = handle?.let(::find)
        DeveloperLogger.info(DeveloperLogCategory.RESTART, "Requested")
        DeveloperLogger.info(
            DeveloperLogCategory.RESTART,
            "Previous state = ${previous?.state ?: "(none)"} handle=${handle ?: "(no handle)"}",
        )
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
        DeveloperLogger.info(DeveloperLogCategory.RESTART, "Creating new session")
        val created = open(spec)
        DeveloperLogger.info(
            DeveloperLogCategory.RESTART,
            "Result = ${created?.state ?: "null"} handle=${created?.handle ?: "(none)"}",
        )
        return created
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
        DeveloperLogger.warn(
            DeveloperLogCategory.SESSION,
            "IDLE -> FAILED handle=${created.handle} reason=$reason",
        )
        return created
    }

    /** Kills [handle] and replaces it with a recorded failure. Restart's counterpart to [restart]. */
    fun restartUnstartable(
        handle: String?,
        workspaceKey: String,
        executable: String?,
        reason: String,
    ): TermuxSession {
        val previous = handle?.let(::find)
        DeveloperLogger.info(DeveloperLogCategory.RESTART, "Requested")
        DeveloperLogger.info(
            DeveloperLogCategory.RESTART,
            "Previous state = ${previous?.state ?: "(none)"} handle=${handle ?: "(no handle)"}",
        )
        // Detached without publishing, so the UI never observes a gap with no session at all.
        if (handle != null) {
            val removed = synchronized(lock) { detachLocked(handle) }
            removed?.let { runCatching { it.finish() } }
        }
        DeveloperLogger.info(DeveloperLogCategory.RESTART, "Creating new session")
        val created = openUnstartable(workspaceKey, executable, reason)
        DeveloperLogger.info(
            DeveloperLogCategory.RESTART,
            "Result = ${created.state} handle=${created.handle}",
        )
        return created
    }

    fun restartTemporarySystemShells(specFor: (TermuxSessionSnapshot) -> TermuxShellSpec): List<TermuxSession> {
        val targets = synchronized(lock) {
            ordered.map { it.toSnapshot() }.filter { snapshot ->
                snapshot.temporarySystemShell || snapshot.executable == TermuxShellResolver.SYSTEM_SHELL
            }
        }
        return targets.mapNotNull { snapshot -> restart(snapshot.handle, specFor(snapshot)) }
    }

    /**
     * Forgets the sessions that can no longer run and that the workspace should no longer point at.
     *
     * A recorded FAILED session exists so that a failure can be shown. Once the condition behind
     * that failure has gone — the runtime was installed and verified — keeping it means the screen
     * still presents a stale reason and a handle that can never start, which is how a successful
     * install left the terminal on `unstartable-0` with a NOT_INSTALLED message beside it. Restart
     * replaces the active session; this clears the leftovers it does not own.
     *
     * @param workspaceKey when given, only that workspace's bound session is considered.
     * @return how many sessions were removed, so the caller can tell whether anything changed.
     */
    fun discardUnusable(workspaceKey: String? = null): Int {
        val doomed = ArrayList<TermuxSession>(2)
        synchronized(lock) {
            for (session in ordered.toList()) {
                if (!session.state.isTerminal || session.isRunning) continue
                if (workspaceKey != null && byWorkspaceKey[workspaceKey] != session.handle) continue
                detachLocked(session.handle)?.let(doomed::add)
            }
        }
        if (doomed.isEmpty()) return 0
        doomed.forEach { runCatching { it.finish() } }
        // Keep the active handle pointing at something that exists, so a caller reading it next
        // does not try to restart a session that is already gone.
        synchronized(lock) {
            val active = activeFlow.value
            if (active != null && ordered.none { it.handle == active }) {
                activeFlow.value = ordered.firstOrNull()?.handle
            }
        }
        publish()
        return doomed.size
    }

    /**
     * Closes every session that does not belong to [activeWorkspaceId], and returns how many were
     * closed.
     *
     * This is the project-switch step. A project's shells are bound to its own directory at
     * `/workspace`, so a session left over from the project the user just left is a shell that is
     * still operating on a project that is no longer active — the one thing a project-aware terminal
     * must never do. Keys are compared through [TerminalProjectKeys], so the active project keeps
     * *all* of its terminals (its first one and its extra one) and nothing else survives.
     *
     * Safe to call for the project that is already active, and safe with no sessions at all: it is
     * then a no-op. Idempotent, so it can run on every entry into a project without the cost of a
     * switch.
     */
    fun closeOtherProjects(activeWorkspaceId: String): Int {
        val doomed = ArrayList<TermuxSession>(2)
        val closed = ArrayList<String>(2)
        synchronized(lock) {
            for (session in ordered.toList()) {
                val key = byWorkspaceKey.entries.firstOrNull { it.value == session.handle }?.key
                    ?: session.handle
                if (TerminalProjectKeys.belongsTo(key, activeWorkspaceId)) continue
                closed += key
                detachLocked(session.handle)?.let(doomed::add)
            }
        }
        if (doomed.isEmpty()) return 0

        // Finished outside the lock: this kills a process.
        doomed.forEach { session ->
            Log.i(TAG, "closing session of a project that is no longer active handle=${session.handle}")
            runCatching { session.finish() }
        }
        // Keep the active handle pointing at something that exists, so a caller reading it next does
        // not try to drive a session that is already gone.
        synchronized(lock) {
            val active = activeFlow.value
            if (active != null && ordered.none { it.handle == active }) {
                activeFlow.value = ordered.firstOrNull()?.handle
            }
        }
        publish()
        TerminalDiagnostics.record(
            TAG,
            "project switch to=$activeWorkspaceId closed=${closed.joinToString()}",
        )
        DeveloperLogger.info(
            DeveloperLogCategory.SESSION,
            "Project switch active=$activeWorkspaceId closed=${closed.size} session(s)",
        )
        return doomed.size
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
            specByHandle.clear()
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
        DeveloperLogger.info(
            DeveloperLogCategory.PROCESS,
            "process exit handle=$handle state=${session.state} exit code=${session.exitStatus}",
        )
        logSessionTransition(
            from = TerminalSessionState.RUNNING,
            to = session.state,
            handle = handle,
            reason = session.failure,
        )
        synchronized(lock) {
            byWorkspaceKey.entries.removeAll { it.value == handle }
            // The command record goes with the process: a finished shell is never handed back by
            // [open], so nothing may keep it for a future comparison either.
            specByHandle.remove(handle)
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
        specByHandle.remove(handle)
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
        DeveloperLogger.error(
            DeveloperLogCategory.ERROR,
            "process start failure workspace=${spec.workspaceKey} executable=${spec.executable}",
            error,
        )
        logSessionTransition(
            from = TerminalSessionState.IDLE,
            to = TerminalSessionState.FAILED,
            handle = handle,
            reason = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName,
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
