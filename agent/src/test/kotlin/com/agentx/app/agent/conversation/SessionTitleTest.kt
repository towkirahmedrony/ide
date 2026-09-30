package com.agentx.app.agent.conversation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Deterministic session titles. No model call is involved, so these assertions
 * pin the exact output rather than a shape.
 */
class SessionTitleTest {

    @Test
    fun `a plain request becomes a title-cased phrase`() {
        assertEquals("Find the Authentication Code", SessionTitle.derive("Find the authentication code."))
    }

    @Test
    fun `a question about a failure becomes a fix title`() {
        assertEquals("Fix Login Page Authenticate", SessionTitle.derive("Why can't the login page authenticate?"))
    }

    @Test
    fun `polite filler is dropped`() {
        assertEquals("Fix the Terminal Crashes", SessionTitle.derive("Can you fix the terminal crashes"))
    }

    @Test
    fun `a later sentence does not leak into the title`() {
        assertEquals("Explain the Refresh Token Logic", SessionTitle.derive("Explain the refresh token logic."))
    }

    @Test
    fun `only the first line is used`() {
        val derived = SessionTitle.derive("Investigate the terminal crash\nand also the pty session")

        assertEquals("Fix Investigate the Terminal Crash", derived)
        assertFalse(derived.contains("pty"), derived)
    }

    @Test
    fun `blank input falls back to the default title`() {
        assertEquals(SessionTitle.DEFAULT, SessionTitle.derive(""))
        assertEquals(SessionTitle.DEFAULT, SessionTitle.derive("   \n  "))
    }

    @Test
    fun `long messages are truncated to the limit`() {
        val derived = SessionTitle.derive(
            "Investigate why the workspace runtime refuses every single one of the content URIs we hand it",
        )

        assertTrue(derived.length <= SessionTitle.MAX_LENGTH, "was ${derived.length}: $derived")
        assertFalse(derived.endsWith(" "))
    }

    @Test
    fun `isPlaceholder recognises only the default title`() {
        assertTrue(SessionTitle.isPlaceholder(""))
        assertTrue(SessionTitle.isPlaceholder("   "))
        assertTrue(SessionTitle.isPlaceholder(SessionTitle.DEFAULT))
        assertTrue(SessionTitle.isPlaceholder("new session"))
        assertFalse(SessionTitle.isPlaceholder("Auth work"))
    }
}
