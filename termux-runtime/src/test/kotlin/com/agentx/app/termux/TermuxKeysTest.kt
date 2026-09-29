package com.agentx.app.termux

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TermuxKeysTest {

    @Test
    fun `control keys are the real control characters`() {
        // A pty turns 0x03 into SIGINT; anything else would just be typed at the prompt and a
        // long-running server would never stop.
        assertContentEquals(byteArrayOf(0x03), TermuxKeys.CTRL_C)
        assertContentEquals(byteArrayOf(0x04), TermuxKeys.CTRL_D)
        assertContentEquals(byteArrayOf(0x09), TermuxKeys.TAB)
        assertContentEquals(byteArrayOf(0x1B), TermuxKeys.ESCAPE)
    }

    @Test
    fun `ctrl plus a letter follows the ascii control encoding`() {
        assertContentEquals(byteArrayOf(0x01), TermuxKeys.control('a'))
        assertContentEquals(byteArrayOf(0x03), TermuxKeys.control('C'))
        assertContentEquals(byteArrayOf(0x1A), TermuxKeys.control('z'))
        assertEquals(TermuxKeys.CTRL_C.toList(), TermuxKeys.control('c')?.toList())
    }

    @Test
    fun `non letters are refused instead of sending a wrong byte`() {
        assertNull(TermuxKeys.control('1'))
        assertNull(TermuxKeys.control(' '))
        assertNull(TermuxKeys.control('\u001b'))
    }

    @Test
    fun `arrows use the standard csi sequences`() {
        assertContentEquals(byteArrayOf(0x1B, '['.code.toByte(), 'A'.code.toByte()), TermuxKeys.ARROW_UP)
        assertContentEquals(byteArrayOf(0x1B, '['.code.toByte(), 'D'.code.toByte()), TermuxKeys.ARROW_LEFT)
    }
}
