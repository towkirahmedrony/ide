package com.agentx.app.termux

/**
 * A session whose process could never be created.
 *
 * This exists purely so that a failure to construct a session does not leave the manager with an
 * empty list. That empty list was the whole bug: with no session object there is no handle to make
 * active, no state to report, no reason to show, and nothing for Restart to act on — so `restart`
 * returned early, the header's Restart icon stayed disabled, and the terminal was unusable until
 * the app was reinstalled. A failed session is a session that has failed, not an absence.
 *
 * [failure] is the constructor's message, which [TerminalSessionAdapter] and the runtime make sure
 * names the executable and the underlying error.
 */
class UnstartableTermuxSession(
    override val handle: String,
    override val executable: String?,
    override val temporarySystemShell: Boolean,
    override val failure: String,
    /**
     * Reported as a startup failure. The UI reads [failure] rather than this, but a plain status
     * keeps `exitStatus` meaningful for anything that only looks at numbers.
     */
    override val exitStatus: Int = STARTUP_FAILURE_EXIT_STATUS,
) : TermuxSession {

    override val state: TerminalSessionState get() = TerminalSessionState.FAILED

    /** No process ever ran, so it never set a title or a working directory. */
    override val title: String? = null

    override val workingDirectory: String? = null

    /** Nothing was ever launched, so there is nothing to launch now: only a restart can help. */
    override fun start() = Unit

    override fun write(bytes: ByteArray, offset: Int, count: Int) = Unit

    override fun updateSize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) = Unit

    /** Owns no pty, no process and no threads, so there is nothing to release. */
    override fun finish() = Unit

    companion object {
        const val STARTUP_FAILURE_EXIT_STATUS: Int = 1
    }
}
