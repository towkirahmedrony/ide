package com.agentx.app.termux

import com.termux.terminal.TerminalSession

/**
 * Binds the vendored Termux [TerminalSession] to [TermuxSession].
 *
 * `TerminalSession` is `final`, so it cannot be subclassed; wrapping it keeps the manager and
 * its tests independent of the PTY while the UI still gets the real object it must hand to
 * `TerminalView.attachSession`.
 */
class TerminalSessionAdapter(
    val delegate: TerminalSession,
    override val executable: String? = null,
    override val temporarySystemShell: Boolean = executable == TermuxShellResolver.SYSTEM_SHELL,
) : TermuxSession {

    override val handle: String get() = delegate.mHandle

    override val isRunning: Boolean get() = delegate.isRunning

    override val exitStatus: Int get() = delegate.exitStatus

    override val title: String? get() = delegate.title

    override val workingDirectory: String? get() = delegate.cwd

    override fun write(bytes: ByteArray, offset: Int, count: Int) = delegate.write(bytes, offset, count)

    override fun updateSize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) =
        delegate.updateSize(columns, rows, cellWidthPixels, cellHeightPixels)

    override fun finish() {
        // SIGKILL to the shell. TerminalSession.cleanupResources() then closes the pty master
        // file descriptor and both I/O queues, so nothing is left dangling.
        runCatching { delegate.finishIfRunning() }
    }
}
