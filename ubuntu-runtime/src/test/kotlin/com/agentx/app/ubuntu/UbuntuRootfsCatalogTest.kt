package com.agentx.app.ubuntu

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class UbuntuRootfsCatalogTest {

    @Test
    fun `the arm64 entry is available and fully pinned`() {
        val entry = assertNotNull(UbuntuRootfsCatalog.forAbi("arm64-v8a"))
        assertTrue(entry.available)
        assertEquals(64, assertNotNull(entry.sha256).length)
        assertTrue(assertNotNull(entry.sha256).all { it in "0123456789abcdef" })
        assertTrue(assertNotNull(entry.url).startsWith("https://"))
        assertTrue(assertNotNull(entry.archiveSizeBytes) > 0L)
        assertEquals(29_936_675L, entry.archiveSizeBytes)
        assertTrue(entry.sourceRevision.contains(UbuntuRootfsCatalog.UBUNTU_RELEASE))
    }

    @Test
    fun `every other abi is honestly unavailable rather than fabricated`() {
        for (abi in listOf("armeabi-v7a", "x86", "x86_64")) {
            val entry = assertNotNull(UbuntuRootfsCatalog.forAbi(abi))
            assertFalse(entry.available, abi)
            assertTrue(entry.unavailableReason().contains(abi), abi)
        }
    }

    @Test
    fun `resolution prefers a device's own abi in order`() {
        val arm = assertNotNull(UbuntuRootfsCatalog.forAbi("arm64-v8a"))
        assertEquals(arm, UbuntuRootfsCatalog.forAbis(listOf("mips", "arm64-v8a", "x86_64")))
        assertEquals(null, UbuntuRootfsCatalog.forAbis(listOf("mips")))
    }

    @Test
    fun `the required guest files prove a usable userland`() {
        val required = UbuntuRootfsCatalog.REQUIRED_GUEST_FILES
        assertTrue(required.contains("usr/bin/bash"))
        assertTrue(required.contains("usr/bin/apt-get"))
        assertTrue(required.contains("usr/bin/dpkg"))
        // Proves the merged-/usr symlink survived extraction.
        assertTrue(required.contains("bin/bash"))
    }

    @Test
    fun `an incomplete entry is not available`() {
        val base = assertNotNull(UbuntuRootfsCatalog.forAbi("arm64-v8a"))
        assertFalse(base.copy(sha256 = "not-a-digest").available)
        assertFalse(base.copy(url = null).available)
        assertFalse(base.copy(archiveSizeBytes = 0L).available)
        assertFalse(base.copy(sourceRevision = UbuntuRootfsCatalog.SOURCE_REVISION_UNBUILT).available)
    }
}
