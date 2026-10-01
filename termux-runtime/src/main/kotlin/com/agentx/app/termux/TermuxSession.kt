package com.agentx.app.termux

/**
 * Lifecycle of one shell session.
 *
 * [STARTING] is the state that matters most here. A session that never reached [RUNNING] must end
 * up [FAILED], not [STOPPED]: "the shell never started" and "the shell exited after running" are
 * different problems with different fixes, and only the second one ever had a working shell. The
 * previous model had no way to say "never started", so a session that failed to launch simply
 * disappeared and the UI was left with nothing to show and nothing to restart.
 *
 * [IDLE] exists so a session that has been constructed but not yet asked to start is not mistaken
 * for a running one — the vendored `TerminalSession` reports `isRunning` for a process that was
 * never spawned, because its pid field starts at 0 and only becomes -1 on exit.
 */
enum class TerminalSessionState {
    IDLE,
    STARTING,
    RUNNING,
    STOPPING,
    STOPPED,
    FAILED,
}

/**
 * True once the session can no longer be started where it stands, so the only way forward is a
 * restart. Used to decide which sessions are worth evicting when the workspace cap is reached.
 */
val TerminalSessionState.isTerminal: Boolean
    get() = this == TerminalSessionState.STOPPED ||
        this == TerminalSessionState.FAILED ||
        this == TerminalSessionState.IDLE

fun logSessionTransition(
    from: TerminalSessionState?,
    to: TerminalSessionState,
    handle: String? = null,
    pid: Int? = null,
    reason: String? = null,
) {
    if (from == to) return
    val details = buildString {
        if (!handle.isNullOrBlank()) append(" handle=$handle")
        if (pid != null && pid > 0) append(" pid=$pid")
        if (!reason.isNullOrBlank()) append(" reason=$reason")
    }
    DeveloperLogger.info(
        DeveloperLogCategory.SESSION,
        "${from?.name ?: TerminalSessionState.IDLE.name} -> $to$details",
    )
}

/**
 * Maps what the vendored `TerminalSession` can be observed to be doing onto a lifecycle state.
 *
 * Kept as a pure function so the transitions are unit tested on the JVM rather than only observed
 * on a device: the pid is `0` before the process is spawned, positive while it is alive and `-1`
 * once it has been reaped.
 */
fun terminalSessionState(
    failure: String?,
    started: Boolean,
    stopping: Boolean,
    pid: Int,
    reachedRunning: Boolean,
): TerminalSessionState = when {
    failure != null -> TerminalSessionState.FAILED
    stopping -> TerminalSessionState.STOPPING
    !started -> TerminalSessionState.IDLE
    pid > 0 -> if (reachedRunning) TerminalSessionState.RUNNING else TerminalSessionState.STARTING
    // pid == 0: start() has been called but the PTY has not produced a pid yet.
    pid == 0 -> TerminalSessionState.STARTING
    // pid == -1: the process is gone. A shell that never reported RUNNING failed to start.
    reachedRunning -> TerminalSessionState.STOPPED
    else -> TerminalSessionState.FAILED
}

/**
 * One shell process attached to a pseudoterminal.
 *
 * Deliberately a narrow view over `com.termux.terminal.TerminalSession`: the manager and its
 * tests work against this interface, while [TerminalSessionAdapter] binds it to the vendored
 * Termux class that the PTY JNI actually drives.
 */
interface TermuxSession {

    /** Stable identity, survives UI recreation. */
    val handle: String

    /** Where this session is in its lifecycle. Always known; never left unspecified. */
    val state: TerminalSessionState

    /** True only while a live process is attached and accepting input. */
    val isRunning: Boolean get() = state == TerminalSessionState.RUNNING

    /** Exit status; only meaningful once the process has exited. */
    val exitStatus: Int

    /**
     * Why the session could not start, or why it stopped. Non-null exactly when [state] is
     * [TerminalSessionState.FAILED]; the UI shows this instead of guessing.
     */
    val failure: String?

    /**
     * Launches the process. Must never throw: a start that cannot happen is recorded as
     * [TerminalSessionState.FAILED] on this same session, so the session list always holds
     * something the UI can show and restart. Idempotent.
     */
    fun start()

    /** Title the program set through escape sequences, or null. */
    val title: String?

    /** The shell's working directory as reported by `/proc/<pid>/cwd`, or null when unknown. */
    val workingDirectory: String?

    /** Bytes to the process's stdin. */
    fun write(bytes: ByteArray, offset: Int, count: Int)

    /** Announce a new window size to the pty so full-screen programs lay out correctly. */
    fun updateSize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int)

    /** Kill the shell. Used by "terminate session" and by a failed restart. */
    fun finish()

    val executable: String? get() = null

    val temporarySystemShell: Boolean get() = executable == TermuxShellResolver.SYSTEM_SHELL
}

/** What a session needs in order to start. */
data class TermuxShellSpec(
    /**
     * Identifies the reason this session exists, so re-entering the same screen reuses the
     * process instead of starting a second one.
     */
    val workspaceKey: String,
    val executable: String,
    /** argv[0], passed with a leading `-` for a login shell. */
    val processName: String,
    val arguments: List<String>,
    val workingDirectory: String,
    /** `KEY=VALUE` entries for the PTY. */
    val environment: Array<String>,
    val transcriptRows: Int,
    val temporarySystemShell: Boolean = false,
    val fullTermux: Boolean = false,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TermuxShellSpec) return false
        return workspaceKey == other.workspaceKey &&
            executable == other.executable &&
            processName == other.processName &&
            arguments == other.arguments &&
            workingDirectory == other.workingDirectory &&
            environment.contentEquals(other.environment) &&
            transcriptRows == other.transcriptRows &&
            temporarySystemShell == other.temporarySystemShell &&
            fullTermux == other.fullTermux
    }

    override fun hashCode(): Int {
        var result = workspaceKey.hashCode()
        result = 31 * result + executable.hashCode()
        result = 31 * result + processName.hashCode()
        result = 31 * result + arguments.hashCode()
        result = 31 * result + workingDirectory.hashCode()
        result = 31 * result + environment.contentHashCode()
        result = 31 * result + transcriptRows
        result = 31 * result + temporarySystemShell.hashCode()
        result = 31 * result + fullTermux.hashCode()
        return result
    }
}

/** Immutable view of a session for the UI; safe to publish to Compose. */
data class TermuxSessionSnapshot(
    val handle: String,
    val workspaceKey: String,
    val state: TerminalSessionState,
    val exitStatus: Int,
    val failure: String?,
    val title: String?,
    val workingDirectory: String?,
    val temporarySystemShell: Boolean = false,
    val executable: String? = null,
) {
    val label: String get() = title?.takeIf { it.isNotBlank() } ?: workspaceKey

    /**
     * Whether a live process is attached. Derived from [state] rather than stored beside it, so
     * the two can never disagree — they did before, and that is what let the UI report a shell
     * that had "stopped" while no session existed at all.
     */
    val running: Boolean get() = state == TerminalSessionState.RUNNING

    /** True once this session is no longer startable in place and only a restart can help. */
    val terminal: Boolean get() = state.isTerminal
}

/** Creates the real session. The only piece that needs a PTY. */
fun interface TermuxSessionFactory {
    fun create(spec: TermuxShellSpec): TermuxSession
}
