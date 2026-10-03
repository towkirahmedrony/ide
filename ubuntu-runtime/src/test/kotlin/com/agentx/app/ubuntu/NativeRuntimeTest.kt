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
        assertEquals("${layout.rootfs}/.l2s", layout.l2s)
        assertEquals("/.l2s", layout.guestL2sPath)
        assertEquals("${layout.runtimeDir}/tmp", layout.tmp)
        assertEquals("${layout.rootfs}/etc/agentx/developer-runtime.ok", layout.marker)
    }

    /**
     * The link-to-symlink store has to be inside the rootfs, and this is the assertion that says
     * so out loud. `-l` writes the store's absolute host path into every symlink it creates and
     * PRoot only strips the guest root prefix from a target that lies under the root; a store
     * kept beside the rootfs therefore produced hard links that resolved on the host and dangled
     * in the guest, which is what made `dpkg` fail to unpack `perl-base` with
     * `error setting ownership of '/usr/bin/perl5.38.2.dpkg-new': No such file or directory`.
     */
    @Test
    fun `the link-to-symlink store lives inside the guest rootfs`() {
        assertTrue(
            layout.l2s.startsWith("${layout.rootfs}/"),
            "PROOT_L2S_DIR must be inside the rootfs, was ${layout.l2s}",
        )
        assertEquals(layout.rootfs, layout.l2s.substringBeforeLast('/'))
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
        assertTrue(probe.summary.contains("missing from this APK"), probe.summary)
        assertTrue(probe.summary.contains("libproot.so"), probe.summary)
    }

    @Test
    fun `the runtime directories are the ones the installer creates`() {
        val directories = layout.requiredDirectories
        assertTrue(directories.contains(layout.runtimeDir))
        assertTrue(directories.contains(layout.tmp))
        assertTrue(directories.contains(layout.downloads))
        // The store is created by the extraction, inside the rootfs, not ahead of it: it may not
        // exist before there is a tree for it to live in.
        assertFalse(directories.contains(layout.l2s))
    }
}
