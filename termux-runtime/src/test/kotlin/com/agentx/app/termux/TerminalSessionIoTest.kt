package com.agentx.app.termux

import com.termux.terminal.TerminalSession
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The command-execution layer, exercised on the JVM.
 *
 * A real pty cannot exist in a unit test, and these cover exactly the parts that are testable
 * without one — the parts that decide whether a command can be run at all:
 *
 * - a launch that cannot complete must be *reported*, never left looking like a live shell;
 * - input must not be able to start a process of its own, and must be refused before there is a
 *   shell to receive it;
 * - output must reach whichever screen is bound now, including when the session outlives the screen
 *   that created it;
 * - the exit must reach the runtime that owns the session, not only whichever screen happens to be
 *   watching.
 *
 * The vendored [TerminalSession] is constructed for real, so the session this drives is the same
 * object the terminal view is handed.
 */
class TerminalSessionIoTest {

    /** Where the client looks for a bound screen. A holder, because the screen is replaced. */
    private class Screen {
        var host: TermuxTerminalHost? = null
    }

    /** One screen's callbacks, so a rebind can be told apart from a redraw of the same screen. */
    private class RecordingHost : TermuxTerminalHost {
        val screenUpdates = ArrayList<String>()
        val finished = ArrayList<String>()

        override fun onScreenUpdated(session: TerminalSession) {
            screenUpdates += session.mHandle
        }

        override fun onSessionFinished(session: TerminalSession) {
            finished += session.mHandle
        }

        override fun onTitleChanged(session: TerminalSession) = Unit

        override fun onColorsChanged(session: TerminalSession) = Unit

        override fun onCopyTextToClipboard(text: String) = Unit

        override fun onPasteTextFromClipboard(session: TerminalSession?) = Unit

        override fun onBell() = Unit

        override fun onShellPid(session: TerminalSession, pid: Int) = Unit
    }

    private fun session(
        screen: Screen = Screen(),
        onExit: (TerminalSession) -> Unit = {},
    ): Triple<TerminalSession, TermuxSessionClient, Screen> {
        val client = TermuxSessionClient(hostProvider = { screen.host }, onExit = onExit)
        val terminal = TerminalSession(
            /* shellPath = */ "/system/bin/sh",
            /* cwd = */ "/",
            /* args = */ arrayOf("sh"),
            /* env = */ arrayOf<String>(),
            /* transcriptRows = */ 2000,
            /* client = */ client,
        )
        return Triple(terminal, client, screen)
    }

    // --- a launch that cannot complete is a visible failure -----------------

    /**
     * The pty is created through `JNI.createSubprocess`, and on a JVM that call cannot succeed — the
     * native library is not loadable. That makes this the real path a device takes when the native
     * layer is missing or unloadable, which is precisely the case that used to leave the terminal
     * with no session, no reason and no way to restart.
     */
    @Test
    fun `a shell that cannot get a pty is failed with a reason, never running`() {
        val (terminal, _, _) = session()
        val adapter = TerminalSessionAdapter(
            delegate = terminal,
            executable = "/system/bin/sh",
            ioDispatcher = Dispatchers.IO,
        )

        adapter.start()

        assertTrue(
            awaitState(adapter, TerminalSessionState.FAILED),
            "launch ended in ${adapter.state} (${adapter.failure}) instead of FAILED",
        )
        assertFalse(adapter.isRunning, "a launch that never produced a shell must not read as running")
        assertTrue(
            !adapter.failure.isNullOrBlank(),
            "a failed launch must carry the reason the screen shows",
        )
    }

    // --- input ------------------------------------------------------------

    /**
     * Every keystroke reaches the session through `write`, and a write must never be what creates
     * the process: only `start`/`updateSize` may. Otherwise typing could fork a shell, and a refused
     * write could look like a successful one.
     */
    @Test
    fun `input is refused before there is a shell, and cannot start one`() {
        val (terminal, _, _) = session()
        val adapter = TerminalSessionAdapter(delegate = terminal, executable = "/system/bin/sh")

        adapter.write("pwd\n".toByteArray(), 0, 4)
        terminal.write("pwd\n".toByteArray(), 0, 4)

        assertEquals(TerminalSessionState.IDLE, adapter.state)
        assertEquals(0, terminal.pid, "a write must not create the process")
    }

    @Test
    fun `a stopped session refuses input instead of claiming it was delivered`() {
        val (terminal, _, _) = session()
        val adapter = TerminalSessionAdapter(delegate = terminal, executable = "/system/bin/sh")

        // No pid was ever reported, so finishing is immediate and leaves a stopped session.
        adapter.finish()

        assertEquals(TerminalSessionState.STOPPED, adapter.state)
        adapter.write("ls\n".toByteArray(), 0, 3)
        assertFalse(adapter.isRunning)
    }

    // --- output ------------------------------------------------------------

    /**
     * The screen is bound per callback, not captured when the session was created. That is what lets
     * a session outlive the screen — leaving the Terminal tab, a rotation, a new composition — and
     * still draw when the user comes back, and what stops a replaced screen from drawing a session
     * it no longer owns.
     */
    @Test
    fun `output reaches the screen bound now, and only that screen`() {
        val screen = Screen()
        val (terminal, client, _) = session(screen)

        // Nothing is watching: output produced while the tab is closed must not throw and must not
        // be pinned to a screen that no longer exists.
        client.onTextChanged(terminal)

        val first = RecordingHost()
        screen.host = first
        client.onTextChanged(terminal)
        assertEquals(listOf(terminal.mHandle), first.screenUpdates)

        val second = RecordingHost()
        screen.host = second
        client.onTextChanged(terminal)
        assertEquals(listOf(terminal.mHandle), second.screenUpdates, "the returned screen redraws")
        assertEquals(1, first.screenUpdates.size, "the replaced screen must not keep drawing")
    }

    // --- exit --------------------------------------------------------------

    /**
     * The exit belongs to the runtime: the session list, the derived session state, the command
     * record and the keep-alive service are all the runtime's, so a shell that ends while no screen
     * is bound must still be accounted for.
     */
    @Test
    fun `an exit is reported to the runtime even with no screen bound`() {
        val ended = ArrayList<String>()
        val (terminal, client, _) = session(onExit = { session -> ended += session.mHandle })

        client.onSessionFinished(terminal)

        assertEquals(listOf(terminal.mHandle), ended)
    }

    @Test
    fun `an exit is reported to the bound screen as well`() {
        val screen = Screen()
        val ended = ArrayList<String>()
        val (terminal, client, _) = session(screen, onExit = { session -> ended += session.mHandle })
        val host = RecordingHost()
        screen.host = host

        client.onSessionFinished(terminal)

        assertEquals(listOf(terminal.mHandle), ended)
        assertEquals(listOf(terminal.mHandle), host.finished)
    }

    /** Polls the session's own state; a launch reports its outcome from a background thread. */
    private fun awaitState(
        session: TermuxSession,
        expected: TerminalSessionState,
        timeoutMillis: Long = 3_000L,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline && session.state != expected) {
            Thread.sleep(10)
        }
        return session.state == expected
    }
}
