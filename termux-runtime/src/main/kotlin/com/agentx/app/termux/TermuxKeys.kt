package com.agentx.app.termux

/**
 * Byte sequences for the keys an Android soft keyboard cannot produce.
 *
 * The terminal view already forwards every key a hardware or soft keyboard does send, including
 * Ctrl combinations, arrows and Tab. What it cannot send is a key the on-screen keyboard simply
 * has no way to express — which is exactly Ctrl+C, Ctrl+D and the editing keys a phone keyboard
 * hides. Those come from the terminal toolbar as raw pty bytes.
 *
 * These are the control characters themselves, not escape sequences invented here: 0x03 is what
 * a real terminal puts on the wire when Ctrl+C is pressed, so a foreground process gets SIGINT
 * from the pty's line discipline exactly as it would on a desktop.
 */
object TermuxKeys {

    /** ETX — Ctrl+C. The pty turns this into SIGINT for the foreground process group. */
    val CTRL_C: ByteArray = byteArrayOf(0x03)

    /** EOT — Ctrl+D. At an empty prompt this ends the shell; mid-line it flushes. */
    val CTRL_D: ByteArray = byteArrayOf(0x04)

    /** SUB — Ctrl+Z, suspend the foreground job. */
    val CTRL_Z: ByteArray = byteArrayOf(0x1A)

    /** FF — Ctrl+L, the conventional "redraw / clear screen" key. */
    val CTRL_L: ByteArray = byteArrayOf(0x0C)

    /** HT — Tab completes, and indents in an editor. */
    val TAB: ByteArray = byteArrayOf(0x09)

    /** ESC, used on its own for `vim` and for dismissing completions. */
    val ESCAPE: ByteArray = byteArrayOf(0x1B)

    val ARROW_UP: ByteArray = bytes("\u001b[A")
    val ARROW_DOWN: ByteArray = bytes("\u001b[B")
    val ARROW_RIGHT: ByteArray = bytes("\u001b[C")
    val ARROW_LEFT: ByteArray = bytes("\u001b[D")
    val HOME: ByteArray = bytes("\u001b[H")
    val END: ByteArray = bytes("\u001b[F")
    val PAGE_UP: ByteArray = bytes("\u001b[5~")
    val PAGE_DOWN: ByteArray = bytes("\u001b[6~")

    /**
     * Ctrl + a letter, encoded the way a terminal encodes it: the ASCII control character,
     * which is the letter's position in the alphabet.
     *
     * Returns null for anything that is not a letter, so a caller cannot silently send a byte
     * that means something else.
     */
    fun control(letter: Char): ByteArray? {
        val upper = letter.uppercaseChar()
        if (upper !in 'A'..'Z') return null
        return byteArrayOf((upper - 'A' + 1).toByte())
    }

    private fun bytes(text: String): ByteArray = text.toByteArray(Charsets.US_ASCII)
}
