package com.agentx.app.termux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TermuxBootstrapArchiveTest {

    private val prefix = "/data/data/com.agentx.app/files/usr-staging"
    private val staging = prefix
    private val final = "/data/data/com.agentx.app/files/usr"

    @Test
    fun `symlinks are parsed with the arrow separator the manifest uses`() {
        val manifest = "bin/sh\u2190bin/sh\n" +
            "bin/bash\u2190bin/bash\n"
        val parsed = TermuxBootstrapArchive.parseSymlinks(manifest, staging, final)

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
        val parsed = TermuxBootstrapArchive.parseSymlinks("bin/sh\nbin/bash\u2190bin/bash\n", staging, final)

        assertFalse(parsed.invalid.isEmpty())
        assertEquals("bin/sh", parsed.invalid.first())
        assertEquals(1, parsed.links.size)
        assertTrue(parsed.isUsable)
    }

    @Test
    fun `a manifest with no links at all is unusable`() {
        assertFalse(TermuxBootstrapArchive.parseSymlinks("", staging, final).isUsable)
        assertFalse(TermuxBootstrapArchive.parseSymlinks("garbage\n", staging, final).isUsable)
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

    @Test
    fun `symlink targets that leave the prefix are rejected`() {
        // An absolute target is acceptable only inside the AgentX prefix, in either its
        // staged or its final form. The official Termux prefix is never acceptable.
        assertFalse(
            TermuxBootstrapArchive.isSafeSymlinkTarget(
                staging, final, "bin/sh", "/data/data/com.termux/files/usr/bin/sh",
            ),
        )
        assertFalse(
            TermuxBootstrapArchive.isSafeSymlinkTarget(staging, final, "bin/sh", "/etc/passwd"),
        )
        assertTrue(TermuxBootstrapArchive.isSafeSymlinkTarget(staging, final, "bin/bash", "bin/bash"))
        assertEquals(null, TermuxBootstrapArchive.resolveSymlink(prefix, "../bin/sh"))

        val parsed = TermuxBootstrapArchive.parseSymlinks("/etc/passwd\u2190bin/evil\n", staging, final)
        assertFalse(parsed.invalid.isEmpty())
        assertTrue(parsed.links.isEmpty())

        // `..` is only an escape when it climbs above the prefix root: from bin/ one level
        // up is the prefix itself, which is where the real archive's relative links point.
        assertFalse(
            TermuxBootstrapArchive.isSafeSymlinkTarget(staging, final, "bin/sh", "../../escape"),
        )
    }

    @Test
    fun `the three symlink target shapes a real bootstrap ships are all accepted`() {
        // Measured on the published bootstrap-aarch64.zip: 1111 bare-name targets,
        // 79 relative targets containing `..`, and 20 absolute targets into the prefix.
        // 99 of the 1213 links are in the last two groups, so a rule that refuses them
        // makes a real archive uninstallable.

        // 1. bare name, resolved against the link's own directory -> bin/coreutils
        assertTrue(TermuxBootstrapArchive.isSafeSymlinkTarget(staging, final, "bin/ls", "coreutils"))

        // 2. relative with `..`, still inside the prefix
        assertTrue(
            TermuxBootstrapArchive.isSafeSymlinkTarget(staging, final, "include/ncursesw/term.h", "../term.h"),
        )
        assertTrue(
            TermuxBootstrapArchive.isSafeSymlinkTarget(staging, final, "include/ncurses.h", "curses.h"),
        )

        // 3. absolute, into the final prefix: dangling during staging, correct after the rename
        assertTrue(
            TermuxBootstrapArchive.isSafeSymlinkTarget(
                staging,
                final,
                "share/pacman/keyrings/mradityaalok.gpg",
                "$final/share/termux-keyring/mradityaalok.gpg",
            ),
        )
        // ...and equally acceptable when it names the staging prefix.
        assertTrue(
            TermuxBootstrapArchive.isSafeSymlinkTarget(
                staging,
                final,
                "share/pacman/keyrings/mradityaalok.gpg",
                "$staging/share/termux-keyring/mradityaalok.gpg",
            ),
        )
        // A traversal that normalises away is not an escape.
        assertTrue(
            TermuxBootstrapArchive.isSafeSymlinkTarget(staging, final, "lib/libssl.so", "../lib/libssl.so.3"),
        )
    }

    @Test
    fun `a real manifest parses without dropping a single link`() {
        val manifest = listOf(
            "coreutils\u2190./bin/ls",
            "../ncurses.h\u2190./include/ncursesw/term.h",
            "$final/share/termux-keyring/mradityaalok.gpg\u2190./share/pacman/keyrings/mradityaalok.gpg",
        ).joinToString("\n") + "\n"

        val parsed = TermuxBootstrapArchive.parseSymlinks(manifest, staging, final)
        assertTrue(parsed.invalid.isEmpty())
        assertEquals(3, parsed.links.size)
    }
}
