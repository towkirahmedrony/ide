package com.agentx.app.termux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TermuxShellResolverTest {

    private val paths = TermuxPaths.forAppDataDir("/data/data/com.agentx.app")

    @Test
    fun `login wins over bash, exactly like a termux session`() {
        val resolved = TermuxShellResolver.resolve(paths) { it == "${paths.bin}/login" }
        assertEquals("${paths.bin}/login", resolved.executable)
        assertEquals("-login", resolved.processName)
        assertTrue(resolved.login)
    }

    @Test
    fun `the first available login shell is used`() {
        val present = setOf("${paths.bin}/bash", "${paths.bin}/sh")
        val resolved = TermuxShellResolver.resolve(paths) { it in present }
        assertEquals("${paths.bin}/bash", resolved.executable)
        assertEquals("-bash", resolved.processName)
    }

    @Test
    fun `a prefix with no shell falls back to the system shell`() {
        val resolved = TermuxShellResolver.resolve(paths) { false }
        assertEquals(TermuxShellResolver.SYSTEM_SHELL, resolved.executable)
        assertEquals("sh", resolved.processName)
        assertFalse(resolved.login)
    }

    @Test
    fun `only paths inside the prefix are probed before the fallback`() {
        val probed = mutableListOf<String>()
        TermuxShellResolver.resolve(paths) { path ->
            probed += path
            false
        }
        assertEquals(TermuxShellResolver.LOGIN_SHELL_BINARIES.map { "${paths.bin}/$it" }, probed)
    }
}
