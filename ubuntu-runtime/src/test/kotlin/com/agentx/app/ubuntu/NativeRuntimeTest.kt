package com.agentx.app.ubuntu

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeRuntimeTest {

    private val layout = NativeRuntimeLayout.forContext(
        nativeLibraryDir = "/data/app/com.agentx.app/lib/arm64/",
        filesDir = "/data/user/0/com.agentx.app/files/",
    )

    @Test
    fun `paths derive from the injected roots, never a hardcoded install path`() {
        assertEquals("/data/app/com.agentx.app/lib/arm64", layout.nativeLibraryDir)
        assertEquals("/data/user/0/com.agentx.app/files/developer-runtime", layout.runtimeDir)
        assertEquals("${layout.nativeLibraryDir}/libproot.so", layout.proot)
        assertEquals("${layout.nativeLibraryDir}/libproot_loader.so", layout.loader)
        assertEquals("${layout.runtimeDir}/rootfs", layout.rootfs)
        assertEquals("${layout.runtimeDir}/l2s", layout.l2s)
        assertEquals("${layout.runtimeDir}/tmp", layout.tmp)
        assertEquals("${layout.rootfs}/etc/agentx/developer-runtime.ok", layout.marker)
    }

    @Test
    fun `a complete native directory probes ready`() {
        val present = setOf("libproot.so", "libproot_loader.so", "libandroid-shmem.so")
        val probe = NativeRuntimeProbe.probe(layout) { path -> present.any { path.endsWith(it) } }
        assertTrue(probe.ready)
        assertTrue(probe.missing.isEmpty())
    }

    @Test
    fun `a missing library is named, not swallowed`() {
        val probe = NativeRuntimeProbe.probe(layout) { false }
        assertFalse(probe.ready)
        assertEquals(NativeRuntimeLayout.REQUIRED_LIBRARIES.size, probe.missing.size)
        assertTrue(probe.summary.contains("Missing"))
    }

    @Test
    fun `the runtime directories are the ones the installer creates`() {
        val directories = layout.requiredDirectories
        assertTrue(directories.contains(layout.runtimeDir))
        assertTrue(directories.contains(layout.l2s))
        assertTrue(directories.contains(layout.tmp))
        assertTrue(directories.contains(layout.downloads))
    }
}
