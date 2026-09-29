package com.agentx.app.termux

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

    /** False once the process has exited, whatever the reason. */
    val isRunning: Boolean

    /** Exit status; only meaningful once [isRunning] is false. */
    val exitStatus: Int

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
            transcriptRows == other.transcriptRows
    }

    override fun hashCode(): Int {
        var result = workspaceKey.hashCode()
        result = 31 * result + executable.hashCode()
        result = 31 * result + processName.hashCode()
        result = 31 * result + arguments.hashCode()
        result = 31 * result + workingDirectory.hashCode()
        result = 31 * result + environment.contentHashCode()
        result = 31 * result + transcriptRows
        return result
    }
}

/** Immutable view of a session for the UI; safe to publish to Compose. */
data class TermuxSessionSnapshot(
    val handle: String,
    val workspaceKey: String,
    val running: Boolean,
    val exitStatus: Int,
    val title: String?,
    val workingDirectory: String?,
) {
    val label: String get() = title?.takeIf { it.isNotBlank() } ?: workspaceKey
}

/** Creates the real session. The only piece that needs a PTY. */
fun interface TermuxSessionFactory {
    fun create(spec: TermuxShellSpec): TermuxSession
}
