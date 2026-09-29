package com.agentx.app.termux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TermuxShellResolverTest {

    private val paths = TermuxPaths.forAppDataDir("/data/data/com.agentx.app")
    private val supported = TermuxPrefixSupport.Supported(prefix = paths.prefix, official = false)

    @Test
    fun `login wins over bash, exactly like a termux session`() {
        val resolved = TermuxShellResolver.resolve(paths, { it == "${paths.bin}/login" }, supported)
        assertEquals("${paths.bin}/login", resolved.executable)
        assertEquals("-login", resolved.processName)
        assertTrue(resolved.login)
        assertEquals(TermuxShellResolver.Kind.CUSTOM_PREFIX, resolved.kind)
        assertTrue(resolved.isFullTermux)
    }

    @Test
    fun `the first available login shell is used`() {
        val present = setOf("${paths.bin}/bash", "${paths.bin}/sh")
        val resolved = TermuxShellResolver.resolve(paths, { it in present }, supported)
        assertEquals("${paths.bin}/bash", resolved.executable)
        assertEquals("-bash", resolved.processName)
    }

    @Test
    fun `a prefix with no shell falls back to the system shell without claiming full termux`() {
        val resolved = TermuxShellResolver.resolve(paths, { false }, supported)
        assertEquals(TermuxShellResolver.SYSTEM_SHELL, resolved.executable)
        assertEquals("sh", resolved.processName)
        assertFalse(resolved.login)
        assertEquals(TermuxShellResolver.Kind.TEMPORARY_SYSTEM, resolved.kind)
        assertFalse(resolved.isFullTermux)
        assertTrue(resolved.reason.orEmpty().contains("Full Termux support is not available"))
    }

    @Test
    fun `installation required is used when the temporary shell is refused`() {
        val resolved = TermuxShellResolver.resolve(
            paths = paths,
            isExecutable = { false },
            prefixSupport = supported,
            allowTemporarySystemShell = false,
        )
        assertEquals(TermuxShellResolver.Kind.INSTALLATION_REQUIRED, resolved.kind)
        assertFalse(resolved.isFullTermux)
        assertFalse(resolved.login)
    }

    @Test
    fun `official termux paths are never probed`() {
        val official = TermuxPaths.forAppDataDir(TermuxPaths.OFFICIAL_APP_DATA_DIR)
        val probed = mutableListOf<String>()
        val resolved = TermuxShellResolver.resolve(
            paths = official,
            isExecutable = { path ->
                probed += path
                true
            },
        )
        assertTrue(probed.isEmpty())
        assertEquals(TermuxShellResolver.Kind.INSTALLATION_REQUIRED, resolved.kind)
        assertTrue(resolved.reason.orEmpty().contains(TermuxPaths.OFFICIAL_APP_DATA_DIR))
        assertFalse(resolved.isFullTermux)
    }

    @Test
    fun `a file that exists but is not executable is skipped`() {
        val resolved = TermuxShellResolver.resolve(
            paths = paths,
            isExecutable = { false },
            prefixSupport = supported,
            probe = { path ->
                TermuxShellResolver.ProbeResult(
                    exists = path.endsWith("/bash"),
                    executable = false,
                    abiCompatible = true,
                    runtimeReady = true,
                )
            },
        )
        assertEquals(TermuxShellResolver.Kind.TEMPORARY_SYSTEM, resolved.kind)
        assertEquals(TermuxShellResolver.SYSTEM_SHELL, resolved.executable)
    }

    @Test
    fun `only paths inside the prefix are probed before the fallback`() {
        val probed = mutableListOf<String>()
        TermuxShellResolver.resolve(paths, { path ->
            probed += path
            false
        }, supported)
        assertEquals(TermuxShellResolver.LOGIN_SHELL_BINARIES.map { "${paths.bin}/$it" }, probed)
        assertFalse(probed.any { TermuxPrefixPolicy.isOfficialPath(it) })
    }
}
