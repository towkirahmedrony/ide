package com.agentx.app.termux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TermuxSessionManagerTest {

    /**
     * Stand-in for a PTY-backed session; the manager must not care which it is.
     *
     * `isRunning` is deliberately not implemented here: it is derived from [state] on the
     * interface, and the whole point of the state machine is that the two cannot disagree.
     */
    private class FakeSession(
        override val handle: String,
        override val executable: String? = TermuxShellResolver.SYSTEM_SHELL,
        override val temporarySystemShell: Boolean = executable == TermuxShellResolver.SYSTEM_SHELL,
    ) : TermuxSession {
        override var state: TerminalSessionState = TerminalSessionState.IDLE
            private set
        override var exitStatus: Int = -1
            private set
        override var failure: String? = null
            private set
        override var title: String? = null
        override var workingDirectory: String? = null
        var startCount: Int = 0
            private set
        var finishCount: Int = 0
            private set

        override fun start() {
            startCount += 1
            state = TerminalSessionState.RUNNING
        }

        override fun write(bytes: ByteArray, offset: Int, count: Int) = Unit
        override fun updateSize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) = Unit

        override fun finish() {
            finishCount += 1
            state = TerminalSessionState.STOPPED
            exitStatus = 137
        }

        fun exitOnItsOwn(code: Int) {
            state = TerminalSessionState.STOPPED
            exitStatus = code
        }

        fun failToStart(reason: String) {
            state = TerminalSessionState.FAILED
            failure = reason
        }
    }

    private class Recorder {
        val specs = mutableListOf<TermuxShellSpec>()
        val sessions = mutableListOf<FakeSession>()

        fun factory(): TermuxSessionFactory = TermuxSessionFactory { spec ->
            specs += spec
            FakeSession(
                handle = "session-${specs.size}",
                executable = spec.executable,
                temporarySystemShell = spec.temporarySystemShell,
            ).also { sessions += it }
        }
    }

    private fun spec(
        key: String = "demo",
        cwd: String = "/data/data/com.agentx.app/files/workspaces/demo",
        executable: String = TermuxShellResolver.SYSTEM_SHELL,
        processName: String = "sh",
        temporary: Boolean = executable == TermuxShellResolver.SYSTEM_SHELL,
        fullTermux: Boolean = false,
    ) = TermuxShellSpec(
        workspaceKey = key,
        executable = executable,
        processName = processName,
        arguments = listOf(processName),
        workingDirectory = cwd,
        environment = arrayOf("HOME=/data/data/com.agentx.app/files/home"),
        transcriptRows = 2000,
        temporarySystemShell = temporary,
        fullTermux = fullTermux,
    )

    @Test
    fun `reopening the same workspace reuses the running shell`() {
        // This is what keeps a recomposition or a tab switch from starting a second process and
        // orphaning the first one.
        val recorder = Recorder()
        val manager = TermuxSessionManager(recorder.factory())

        val first = manager.open(spec())
        val second = manager.open(spec())

        assertEquals(1, recorder.specs.size)
        assertSame(first, second)
        assertEquals(listOf("session-1"), manager.sessions().map { it.handle })
    }

    @Test
    fun `a finished shell is replaced by a fresh one`() {
        val recorder = Recorder()
        val manager = TermuxSessionManager(recorder.factory())

        val first = manager.open(spec())!!
        recorder.sessions.first().exitOnItsOwn(0)
        val second = manager.open(spec())!!

        assertNotEquals(first.handle, second.handle)
        assertEquals(2, recorder.specs.size)
        assertEquals(1, manager.sessions().size)
        assertEquals(1, recorder.sessions.first().finishCount)
    }

    @Test
    fun `different workspaces get different sessions`() {
        val recorder = Recorder()
        val manager = TermuxSessionManager(recorder.factory())

        manager.open(spec(key = "a"))
        manager.open(spec(key = "b"))

        assertEquals(2, manager.sessions().size)
    }

    @Test
    fun `capacity is enforced instead of growing without bound`() {
        val recorder = Recorder()
        val manager = TermuxSessionManager(recorder.factory(), maxSessions = 1)

        assertTrue(manager.open(spec(key = "a")) != null)
        assertNull(manager.open(spec(key = "b")))
        assertEquals(1, recorder.specs.size)
    }

    @Test
    fun `restart swaps the handle so a stale view cannot drive the new pty`() {
        val recorder = Recorder()
        val manager = TermuxSessionManager(recorder.factory())

        val first = manager.open(spec())!!
        val restarted = manager.restart(first.handle, spec())!!

        assertNotEquals(first.handle, restarted.handle)
        assertEquals(1, recorder.sessions.first().finishCount)
        assertEquals(restarted.handle, manager.activeHandle.value)
        assertEquals(1, manager.sessions().size)
    }

    @Test
    fun `terminate kills the process and forgets it`() {
        val recorder = Recorder()
        val manager = TermuxSessionManager(recorder.factory())

        val session = manager.open(spec())!!
        manager.terminate(session.handle)

        assertEquals(1, recorder.sessions.first().finishCount)
        assertTrue(manager.sessions().isEmpty())
        assertNull(manager.activeHandle.value)
    }

    @Test
    fun `a shell that exits on its own frees its workspace key`() {
        val recorder = Recorder()
        val manager = TermuxSessionManager(recorder.factory())

        val session = manager.open(spec())!!
        recorder.sessions.first().exitOnItsOwn(0)
        manager.onSessionFinished(session.handle)

        // The next open must start a new process rather than hand back the dead one.
        val next = manager.open(spec())!!
        assertNotEquals(session.handle, next.handle)
    }

    @Test
    fun `terminateAll stops every process`() {
        val recorder = Recorder()
        val manager = TermuxSessionManager(recorder.factory())

        manager.open(spec(key = "a"))
        manager.open(spec(key = "b"))
        manager.terminateAll()

        assertEquals(listOf(1, 1), recorder.sessions.map { it.finishCount })
        assertTrue(manager.sessions().isEmpty())
        assertTrue(manager.snapshots.value.isEmpty())
    }

    @Test
    fun `snapshots report running state and exit status`() {
        val recorder = Recorder()
        val manager = TermuxSessionManager(recorder.factory())

        val session = manager.open(spec())!!
        assertEquals(listOf(true), manager.snapshots.value.map { it.running })
        assertEquals("demo", manager.snapshots.value.single().workspaceKey)

        recorder.sessions.first().exitOnItsOwn(2)
        manager.refresh()
        val snapshot = manager.snapshots.value.single()
        assertEquals(false, snapshot.running)
        assertEquals(2, snapshot.exitStatus)
        assertEquals(session.handle, snapshot.handle)
    }

    @Test
    fun `the first session becomes active and stays active across reopens`() {
        val recorder = Recorder()
        val manager = TermuxSessionManager(recorder.factory())

        val first = manager.open(spec(key = "a"))!!
        manager.open(spec(key = "b"))

        assertEquals(first.handle, manager.activeHandle.value)
    }

    @Test
    fun `a temporary system shell can stay running while installation proceeds`() {
        val recorder = Recorder()
        val manager = TermuxSessionManager(recorder.factory())
        val session = manager.open(spec(temporary = true))!!
        assertTrue(session.isRunning)
        assertTrue(session.temporarySystemShell)
        assertEquals(TermuxShellResolver.SYSTEM_SHELL, session.executable)
        assertEquals(1, manager.sessions().size)
    }

    @Test
    fun `temporary system shells are restarted onto the custom prefix after install`() {
        val recorder = Recorder()
        val manager = TermuxSessionManager(recorder.factory())
        val first = manager.open(spec(temporary = true))!!
        val custom = spec(
            executable = "/data/data/com.agentx.app/files/usr/bin/bash",
            processName = "-bash",
            temporary = false,
            fullTermux = true,
        )
        val restarted = manager.restartTemporarySystemShells { custom }
        assertEquals(1, restarted.size)
        assertEquals(1, recorder.sessions.first().finishCount)
        assertNotEquals(first.handle, restarted.single().handle)
        assertEquals(custom.executable, restarted.single().executable)
        assertFalse(restarted.single().temporarySystemShell)
        assertEquals(1, manager.sessions().size)
        assertEquals(2, recorder.specs.size)
    }

    @Test
    fun `the manager starts every session it creates`() {
        val recorder = Recorder()
        val manager = TermuxSessionManager(recorder.factory())

        manager.open(spec())

        // A created-but-unstarted session reports RUNNING in the vendored class, so the manager
        // must be the thing that actually launches it.
        assertEquals(1, recorder.sessions.single().startCount)
        assertEquals(TerminalSessionState.RUNNING, manager.sessions().single().state)
    }

    @Test
    fun `a session that could not be created still leaves one to restart`() {
        // The regression this guards: a factory that threw used to leave the manager with an empty
        // list, no active handle, no reason on screen, and a Restart action that did nothing.
        val manager = TermuxSessionManager(
            TermuxSessionFactory { error("no pty available") },
        )

        val session = manager.open(spec())

        assertEquals(1, manager.sessions().size)
        assertEquals(TerminalSessionState.FAILED, session?.state)
        assertEquals("no pty available", session?.failure)
        assertEquals(session?.handle, manager.activeHandle.value)
        assertEquals(listOf(TerminalSessionState.FAILED), manager.snapshots.value.map { it.state })
    }

    @Test
    fun `a session whose start failed is reported as failed rather than stopped`() {
        val recorder = Recorder()
        val manager = TermuxSessionManager(recorder.factory())

        val session = manager.open(spec())!!
        recorder.sessions.single().failToStart("the pty did not report a shell pid")
        manager.refresh()

        val snapshot = manager.snapshots.value.single()
        assertEquals(TerminalSessionState.FAILED, snapshot.state)
        assertEquals("the pty did not report a shell pid", snapshot.failure)
        // Never RUNNING, so nothing may claim the shell is still there.
        assertFalse(snapshot.running)
        assertFalse(snapshot.state == TerminalSessionState.STOPPED)
    }

    @Test
    fun `restart works with no active handle at all`() {
        // Restart has to be usable from a state where no session exists, because that is exactly
        // what a failed start can leave behind.
        val recorder = Recorder()
        val manager = TermuxSessionManager(recorder.factory())

        val session = manager.restart(handle = null, spec = spec())

        assertEquals(1, manager.sessions().size)
        assertEquals(TerminalSessionState.RUNNING, session?.state)
    }

    @Test
    fun `restart replaces a failed session with a running one`() {
        val recorder = Recorder()
        val manager = TermuxSessionManager(recorder.factory())

        val failed = manager.open(spec())!!
        recorder.sessions.single().failToStart("PRoot could not start")
        manager.refresh()
        assertEquals(TerminalSessionState.FAILED, manager.snapshots.value.single().state)

        val restarted = manager.restart(failed.handle, spec())!!

        assertNotEquals(failed.handle, restarted.handle)
        assertEquals(TerminalSessionState.RUNNING, restarted.state)
        assertEquals(1, recorder.sessions.first().finishCount)
        assertEquals(1, manager.sessions().size)
        assertEquals(restarted.handle, manager.activeHandle.value)
    }

    @Test
    fun `restart is not blocked by a handle that is already gone`() {
        val recorder = Recorder()
        val manager = TermuxSessionManager(recorder.factory())

        val first = manager.open(spec())!!
        manager.terminate(first.handle)
        assertTrue(manager.sessions().isEmpty())

        val restarted = manager.restart(first.handle, spec())

        assertEquals(TerminalSessionState.RUNNING, restarted?.state)
        assertEquals(1, manager.sessions().size)
    }

    @Test
    fun `a finished session is evicted before the cap refuses to open`() {
        val recorder = Recorder()
        val manager = TermuxSessionManager(recorder.factory(), maxSessions = 1)

        manager.open(spec(key = "a"))!!
        recorder.sessions.single().exitOnItsOwn(0)

        // The cap must never be what wedges the terminal: a dead session costs nothing to drop.
        val replacement = manager.open(spec(key = "b"))
        assertEquals(1, manager.sessions().size)
        assertEquals("b", replacement?.let { manager.snapshots.value.single().workspaceKey })
    }
}
