package com.agentx.app.termux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TermuxBootstrapArchiveTest {

    private val prefix = "/data/data/com.agentx.app/files/usr-staging"

    @Test
    fun `symlinks are parsed with the arrow separator the manifest uses`() {
        val manifest = "bin/sh\u2190bin/sh\n" +
            "bin/bash\u2190bin/bash\n"
        val parsed = TermuxBootstrapArchive.parseSymlinks(manifest)

        assertTrue(parsed.isUsable)
        assertTrue(parsed.invalid.isEmpty())
        assertEquals(
            listOf(
                TermuxBootstrapArchive.Symlink("bin/sh", "bin/sh"),
                TermuxBootstrapArchive.Symlink("bin/bash", "bin/bash"),
            ),
            parsed.links,
        )
    }

    @Test
    fun `a malformed manifest line is reported, not skipped`() {
        // Silently dropping a link produces a prefix where the shell does not resolve, which is
        // much harder to diagnose than a refused install.
        val parsed = TermuxBootstrapArchive.parseSymlinks("bin/sh\nbin/bash\u2190bin/bash\n")

        assertFalse(parsed.invalid.isEmpty())
        assertEquals("bin/sh", parsed.invalid.first())
        assertEquals(1, parsed.links.size)
        assertTrue(parsed.isUsable)
    }

    @Test
    fun `a manifest with no links at all is unusable`() {
        assertFalse(TermuxBootstrapArchive.parseSymlinks("").isUsable)
        assertFalse(TermuxBootstrapArchive.parseSymlinks("garbage\n").isUsable)
    }

    @Test
    fun `link paths resolve against the staging prefix`() {
        assertEquals(
            "$prefix/bin/sh",
            TermuxBootstrapArchive.resolveSymlink(prefix, "bin/sh"),
        )
        assertEquals(
            "$prefix/lib/libssl.so",
            TermuxBootstrapArchive.resolveSymlink(prefix, "./lib/libssl.so"),
        )
    }

    @Test
    fun `executables are recognised the way upstream recognises them`() {
        assertTrue(TermuxBootstrapArchive.isExecutableEntry("bin/bash"))
        assertTrue(TermuxBootstrapArchive.isExecutableEntry("./bin/pkg"))
        assertTrue(TermuxBootstrapArchive.isExecutableEntry("libexec/termux-am/am"))
        assertTrue(TermuxBootstrapArchive.isExecutableEntry("lib/apt/methods/http"))
        assertTrue(TermuxBootstrapArchive.isExecutableEntry("lib/apt/apt-helper"))

        assertFalse(TermuxBootstrapArchive.isExecutableEntry("lib/libssl.so"))
        assertFalse(TermuxBootstrapArchive.isExecutableEntry("SYMLINKS.txt"))
        assertFalse(TermuxBootstrapArchive.isExecutableEntry("etc/profile"))
    }

    @Test
    fun `the symlink manifest is not written to disk as a file`() {
        assertTrue(TermuxBootstrapArchive.isManifestEntry("SYMLINKS.txt"))
        assertTrue(TermuxBootstrapArchive.isManifestEntry("./SYMLINKS.txt"))
        assertFalse(TermuxBootstrapArchive.isManifestEntry("bin/sh"))
    }

    @Test
    fun `entries that would escape the prefix are rejected`() {
        assertTrue(TermuxBootstrapArchive.isSafeEntry("bin/bash"))
        assertTrue(TermuxBootstrapArchive.isSafeEntry("./lib/libc.so"))

        assertFalse(TermuxBootstrapArchive.isSafeEntry("../../data/data/com.termux/files/usr/bin/sh"))
        assertFalse(TermuxBootstrapArchive.isSafeEntry("/etc/passwd"))
        assertFalse(TermuxBootstrapArchive.isSafeEntry(""))
        assertFalse(TermuxBootstrapArchive.isSafeEntry("./"))
    }
}
