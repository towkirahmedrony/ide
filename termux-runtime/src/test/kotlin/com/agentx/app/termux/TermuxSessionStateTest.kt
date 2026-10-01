package com.agentx.app.termux

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The session lifecycle, tested without a device.
 *
 * This is the decision that used to be missing entirely: a session either "was running" or "was
 * not", so a shell that never launched and a shell that ran and exited were indistinguishable —
 * and the UI reported the first as a shell that had "stopped before reporting a status".
 */
class TermuxSessionStateTest {

    @Test
    fun `a session that was never asked to start is idle, not running`() {
        assertEquals(
            TerminalSessionState.IDLE,
            terminalSessionState(failure = null, started = false, stopping = false, pid = 0, reachedRunning = false),
        )
    }

    @Test
    fun `a shell that exits before it reports running has failed, not stopped`() {
        assertEquals(
            TerminalSessionState.FAILED,
            terminalSessionState(null, started = true, stopping = false, pid = -1, reachedRunning = false),
        )
    }

    @Test
    fun `a shell that ran and then exited has stopped`() {
        assertEquals(
            TerminalSessionState.STOPPED,
            terminalSessionState(null, started = true, stopping = false, pid = -1, reachedRunning = true),
        )
    }

    @Test
    fun `a launch that has not reported a pid yet is starting`() {
        assertEquals(
            TerminalSessionState.STARTING,
            terminalSessionState(null, started = true, stopping = false, pid = 0, reachedRunning = false),
        )
        // The pid can appear before the launch call has returned; still starting until it does.
        assertEquals(
            TerminalSessionState.STARTING,
            terminalSessionState(null, started = true, stopping = false, pid = 4321, reachedRunning = false),
        )
    }

    @Test
    fun `a live process that reported itself is running`() {
        assertEquals(
            TerminalSessionState.RUNNING,
            terminalSessionState(null, started = true, stopping = false, pid = 4321, reachedRunning = true),
        )
    }

    @Test
    fun `an explicit stop is stopping`() {
        assertEquals(
            TerminalSessionState.STOPPING,
            terminalSessionState(null, started = true, stopping = true, pid = 4321, reachedRunning = true),
        )
    }

    @Test
    fun `a recorded failure outranks everything else`() {
        // A session that could not be created must never be described as running or as stopped.
        assertEquals(
            TerminalSessionState.FAILED,
            terminalSessionState("boom", started = true, stopping = true, pid = 4321, reachedRunning = true),
        )
        assertEquals(
            TerminalSessionState.FAILED,
            terminalSessionState("boom", started = false, stopping = false, pid = 0, reachedRunning = false),
        )
    }

    @Test
    fun `every possible observation resolves to a state`() {
        // Exhaustive on purpose: the requirement is that there is no combination the UI can be left
        // waiting on, so every combination has to be accounted for, including the ones that look
        // contradictory.
        val observed = mutableSetOf<TerminalSessionState>()
        for (failure in listOf(null, "failed to start")) {
            for (started in listOf(false, true)) {
                for (stopping in listOf(false, true)) {
                    for (pid in listOf(-1, 0, 4321)) {
                        for (reachedRunning in listOf(false, true)) {
                            observed += terminalSessionState(failure, started, stopping, pid, reachedRunning)
                        }
                    }
                }
            }
        }

        assertEquals(TerminalSessionState.entries.toSet(), observed)
    }
}
