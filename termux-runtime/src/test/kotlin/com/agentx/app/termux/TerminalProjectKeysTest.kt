package com.agentx.app.termux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Which project a terminal session belongs to.
 *
 * The switch in [TermuxSessionManager.closeOtherProjects] is a membership test against these keys,
 * so the only question that matters here is whether a key can be mistaken for another project's.
 */
class TerminalProjectKeysTest {

    @Test
    fun `a project's terminals are its first one and its extra one`() {
        assertTrue(TerminalProjectKeys.belongsTo(TerminalProjectKeys.primary("proj-a"), "proj-a"))
        assertTrue(TerminalProjectKeys.belongsTo(TerminalProjectKeys.secondary("proj-a"), "proj-a"))
        assertFalse(TerminalProjectKeys.belongsTo("proj-b", "proj-a"))
        assertFalse(TerminalProjectKeys.belongsTo("", "proj-a"))
    }

    @Test
    fun `a project's terminals are keyed by the project, not by session order`() {
        // A key always starts from the project it belongs to, so it cannot be read as another
        // project's session and cannot drift when the extra terminal is opened first.
        assertEquals("proj-a", TerminalProjectKeys.forSession("proj-a", secondary = false))
        assertEquals(
            TerminalProjectKeys.secondary("proj-a"),
            TerminalProjectKeys.forSession("proj-a", secondary = true),
        )
        assertNotEquals(
            TerminalProjectKeys.primary("proj-a"),
            TerminalProjectKeys.secondary("proj-a"),
        )
    }

    @Test
    fun `a workspace id that is a prefix of another does not claim its sessions`() {
        // The reason membership is an exact match and never `startsWith`: were it a prefix test,
        // opening the project `abc` would silently adopt the shells of the project `abcd`, and the
        // switch would leave a stranger's shell running against the new `/workspace`.
        assertFalse(TerminalProjectKeys.belongsTo("abcd", "abc"))
        assertFalse(TerminalProjectKeys.belongsTo(TerminalProjectKeys.secondary("abcd"), "abc"))
        assertTrue(TerminalProjectKeys.belongsTo("abc", "abc"))
    }

    @Test
    fun `the extra terminal of another project is not adopted`() {
        // `abc` must not claim `abc::2` when `abc::2` belongs to nobody else either — the suffix
        // is only a second terminal for the project whose id precedes it.
        assertFalse(TerminalProjectKeys.belongsTo(TerminalProjectKeys.secondary("abc"), "ab"))
        assertFalse(TerminalProjectKeys.belongsTo("abc::2", "ab"))
    }
}
