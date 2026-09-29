package com.agentx.app.termux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TermuxPathsTest {

    @Test
    fun `multi user alias is rewritten to the canonical data directory`() {
        // /data/user/0/<pkg> and /data/data/<pkg> are the same directory; shell output and
        // /proc/<pid>/cwd comparisons only match when one form is used consistently.
        assertEquals(
            "/data/data/com.agentx.app",
            TermuxPaths.canonicalAppDataDir("/data/user/0/com.agentx.app"),
        )
        assertEquals(
            "/data/data/com.agentx.app/files",
            TermuxPaths.canonicalAppDataDir("/data/user/12/com.agentx.app/files"),
        )
    }

    @Test
    fun `canonical form is left alone`() {
        assertEquals(
            "/data/data/com.agentx.app",
            TermuxPaths.canonicalAppDataDir("/data/data/com.agentx.app/"),
        )
        assertEquals("/somewhere/else", TermuxPaths.canonicalAppDataDir("/somewhere/else"))
    }

    @Test
    fun `layout matches the termux file system layout`() {
        val paths = TermuxPaths.forAppDataDir("/data/data/com.agentx.app")

        assertEquals("/data/data/com.agentx.app/files", paths.rootfs)
        assertEquals("/data/data/com.agentx.app/files/usr", paths.prefix)
        assertEquals("/data/data/com.agentx.app/files/usr/bin", paths.bin)
        assertEquals("/data/data/com.agentx.app/files/usr/tmp", paths.tmp)
        assertEquals("/data/data/com.agentx.app/files/usr-staging", paths.stagingPrefix)
        assertEquals("/data/data/com.agentx.app/files/home", paths.home)
        assertEquals("/data/data/com.agentx.app/files/workspaces", paths.workspaces)
        assertEquals("/data/data/com.agentx.app/files/usr/etc/termux/termux.env", paths.envFile)
    }

    @Test
    fun `only the termux package directory uses the official prefix`() {
        val ours = TermuxPaths.forAppDataDir("/data/data/com.agentx.app")
        assertFalse(ours.usesOfficialPrefix)
        assertFalse(ours.usesOfficialPackageDir)
        assertTrue(ours.usesAgentxPrefix)
        assertTrue(ours.usesAgentxPackageDir)
        assertEquals(TermuxPaths.AGENTX_PREFIX, ours.prefix)

        val official = TermuxPaths.forAppDataDir(TermuxPaths.OFFICIAL_APP_DATA_DIR)
        assertTrue(official.usesOfficialPrefix)
        assertTrue(official.usesOfficialPackageDir)
        assertFalse(official.usesAgentxPrefix)
        assertEquals(TermuxPaths.OFFICIAL_PREFIX, official.prefix)
    }

    @Test
    fun `package identity is agentx, never official termux`() {
        assertEquals("com.agentx.app", TermuxPaths.AGENTX_PACKAGE_NAME)
        assertEquals("/data/data/com.agentx.app/files/usr", TermuxPaths.AGENTX_PREFIX)
        assertTrue(TermuxPaths.AGENTX_PACKAGE_NAME != TermuxPaths.OFFICIAL_PACKAGE_NAME)
    }
}
