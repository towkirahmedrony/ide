package com.agentx.app.termux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TermuxSessionManagerTest {

    /** Stand-in for a PTY-backed session; the manager must not care which it is. */
    private class FakeSession(override val handle: String) : TermuxSession {
        override var isRunning: Boolean = true
            private set
        override var exitStatus: Int = -1
            private set
        override var title: String? = null
        override var workingDirectory: String? = null
        var finishCount: Int = 0
            private set

        override fun write(bytes: ByteArray, offset: Int, count: Int) = Unit
        override fun updateSize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) = Unit

        override fun finish() {
            finishCount += 1
            isRunning = false
            exitStatus = 137
        }

        fun exitOnItsOwn(code: Int) {
            isRunning = false
            exitStatus = code
        }
    }

    private class Recorder {
        val specs = mutableListOf<TermuxShellSpec>()
        val sessions = mutableListOf<FakeSession>()

        fun factory(): TermuxSessionFactory = TermuxSessionFactory { spec ->
            specs += spec
            FakeSession("session-${specs.size}").also { sessions += it }
        }
    }

    private fun spec(key: String = "demo", cwd: String = "/data/data/com.agentx.app/files/workspaces/demo") =
        TermuxShellSpec(
            workspaceKey = key,
            executable = "/system/bin/sh",
            processName = "sh",
            arguments = listOf("sh"),
            workingDirectory = cwd,
            environment = arrayOf("HOME=/data/data/com.agentx.app/files/home"),
            transcriptRows = 2000,
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
}
