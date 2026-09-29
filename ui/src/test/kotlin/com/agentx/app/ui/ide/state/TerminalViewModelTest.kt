package com.agentx.app.ui.ide.state

import com.agentx.app.workspace.process.TerminalSessionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalViewModelTest {

    @Test
    fun `status labels cover every session state`() {
        fun label(state: TerminalSessionState, exit: Int? = null) = TerminalUiState(
            sessionState = state,
            exitCode = exit,
        ).statusLabel
        assertEquals("idle", label(TerminalSessionState.IDLE))
        assertEquals("starting", label(TerminalSessionState.STARTING))
        assertEquals("running", label(TerminalSessionState.RUNNING))
        assertEquals("stopped", label(TerminalSessionState.STOPPED))
        assertEquals("exited 7", label(TerminalSessionState.EXITED, 7))
        assertEquals("failed", label(TerminalSessionState.FAILED))
        assertEquals("cancelled", label(TerminalSessionState.CANCELLED))
    }

    @Test
    fun `shellAlive is true only while running`() {
        assertTrue(TerminalUiState(sessionState = TerminalSessionState.RUNNING).shellAlive)
        assertFalse(TerminalUiState(sessionState = TerminalSessionState.EXITED).shellAlive)
        assertFalse(TerminalUiState(sessionState = TerminalSessionState.FAILED).shellAlive)
    }
}
