package com.agentx.app.ubuntu

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProotSelfTestTest {

    private val layout = NativeRuntimeLayout(
        nativeLibraryDir = "/data/app/com.agentx.app/lib/arm64",
        runtimeDir = "/data/user/0/com.agentx.app/files/developer-runtime",
    )

    @Test
    fun `a complete nativeLibraryDir inspects ready`() {
        val present = setOf("libproot.so", "libproot_loader.so", "libandroid-shmem.so")
        val result = ProotSelfTest.inspect(
            layout = layout,
            exists = { path -> present.any { path.endsWith(it) } },
        )
        assertTrue(result.ok, result.failure)
        assertEquals(layout.nativeLibraryDir, result.nativeLibraryDir)
        assertEquals("${layout.nativeLibraryDir}/libproot.so", result.proot)
        assertEquals("${layout.nativeLibraryDir}/libproot_loader.so", result.loader)
        assertNull(result.failure)
    }

    @Test
    fun `a missing APK library is named and never treated as a rootfs problem`() {
        val result = ProotSelfTest.inspect(layout, exists = { false })
        assertFalse(result.ok)
        assertTrue(result.failure!!.contains("missing from this APK"), result.failure)
        assertTrue(result.failure!!.contains("libproot.so"), result.failure)
        assertTrue(result.failure!!.contains(layout.nativeLibraryDir), result.failure)
        assertFalse(result.failure!!.contains("rootfs"), result.failure)
        assertFalse(result.failure!!.contains("Ubuntu"), result.failure)
    }

    @Test
    fun `a present but not executable library fails the self-test`() {
        val present = setOf("libproot.so", "libproot_loader.so", "libandroid-shmem.so")
        val result = ProotSelfTest.inspect(
            layout = layout,
            exists = { path -> present.any { path.endsWith(it) } },
            canExecute = { false },
        )
        assertFalse(result.ok)
        assertTrue(result.failure!!.contains("Not executable"), result.failure)
    }

    @Test
    fun `run never starts PRoot when the APK libraries are missing`() {
        var started = false
        val result = ProotSelfTest.run(
            layout = layout,
            exists = { false },
            canExecute = { false },
            starter = {
                started = true
                0 to "proot 5.1"
            },
        )
        assertFalse(started)
        assertFalse(result.ok)
        assertTrue(result.failure!!.contains("missing from this APK"), result.failure)
    }

    @Test
    fun `run records PRoot version output when the binary starts`() {
        val present = setOf("libproot.so", "libproot_loader.so", "libandroid-shmem.so")
        val result = ProotSelfTest.run(
            layout = layout,
            exists = { path -> present.any { path.endsWith(it) } },
            canExecute = { path -> present.any { path.endsWith(it) } },
            starter = { invocation ->
                assertEquals("${layout.nativeLibraryDir}/libproot.so", invocation.executable)
                assertEquals(listOf("proot", "-V"), invocation.arguments)
                assertEquals("${layout.nativeLibraryDir}/libproot_loader.so", invocation.environment["PROOT_LOADER"])
                0 to "proot 5.1.107.95"
            },
        )
        assertTrue(result.ok, result.failure)
        assertEquals("proot 5.1.107.95", result.versionOutput)
    }
}
