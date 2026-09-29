package com.agentx.app.termux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TermuxBootstrapCatalogTest {

    @Test
    fun `every android abi this project builds for has a catalog entry`() {
        val abis = setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        assertEquals(abis, TermuxBootstrapCatalog.entries.map { it.androidAbi }.toSet())
    }

    @Test
    fun `pending entries target the agentx prefix and are not available`() {
        for (entry in TermuxBootstrapCatalog.entries) {
            assertEquals(TermuxPaths.AGENTX_PREFIX, entry.prefix)
            assertEquals(TermuxBootstrapCatalog.SOURCE_REVISION_UNBUILT, entry.sourceRevision)
            assertNull(entry.url)
            assertNull(entry.sha256)
            assertNull(entry.archiveSizeBytes)
            assertNull(entry.fileCount)
            assertFalse(entry.available)
            assertTrue(entry.unavailableReason().contains("No custom AgentX bootstrap is available yet"))
            assertFalse(entry.unavailableReason().contains("ea2aeba8819e517db711f8c32369e89e7c52cee73e07930ff91185e1ab93f4f3"))
        }
    }

    @Test
    fun `the most preferred supported abi wins`() {
        assertEquals(
            "arm64-v8a",
            TermuxBootstrapCatalog.forAbis(listOf("arm64-v8a", "armeabi-v7a"))?.androidAbi,
        )
        assertEquals(
            "armeabi-v7a",
            TermuxBootstrapCatalog.forAbis(listOf("armeabi-v7a", "arm64-v8a"))?.androidAbi,
        )
        assertEquals(
            "x86_64",
            TermuxBootstrapCatalog.forAbis(listOf("x86_64", "x86"))?.androidAbi,
        )
    }

    @Test
    fun `an unknown abi resolves to nothing instead of guessing`() {
        assertNull(TermuxBootstrapCatalog.forAbis(listOf("mips")))
        assertNull(TermuxBootstrapCatalog.forAbi("riscv64"))
    }

    @Test
    fun `an entry is available only with a real digest url size and file count`() {
        val pending = assertNotNull(TermuxBootstrapCatalog.forAbi("arm64-v8a"))
        assertFalse(pending.available)
        val ready = pending.copy(
            url = "https://example.invalid/bootstrap-aarch64.zip",
            sha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            archiveSizeBytes = 12L,
            fileCount = 4,
            sourceRevision = "termux-packages@deadbeef",
        )
        assertTrue(ready.available)
    }
}
