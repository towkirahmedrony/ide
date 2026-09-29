package com.agentx.app.termux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TermuxBootstrapCatalogTest {

    @Test
    fun `every android abi this project builds for has an archive`() {
        val abis = setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        assertEquals(abis, TermuxBootstrapCatalog.entries.map { it.androidAbi }.toSet())
    }

    @Test
    fun `digests are lowercase sha256`() {
        for (entry in TermuxBootstrapCatalog.entries) {
            assertEquals(64, entry.sha256.length, entry.termuxArch)
            assertTrue(entry.sha256.all { it in "0123456789abcdef" }, entry.termuxArch)
        }
    }

    @Test
    fun `archive urls point at the pinned official release`() {
        val x86 = assertNotNull(TermuxBootstrapCatalog.forAbi("x86_64"))
        assertEquals(
            "https://github.com/termux/termux-packages/releases/download/" +
                "bootstrap-2026.02.12-r1%2Bapt.android-7/bootstrap-x86_64.zip",
            x86.url,
        )
    }

    @Test
    fun `the most preferred supported abi wins`() {
        // Android reports SUPPORTED_ABIS in preference order; the first match must be used, so a
        // 64-bit device never silently installs the 32-bit bootstrap.
        assertEquals(
            "arm64-v8a",
            TermuxBootstrapCatalog.forAbis(listOf("arm64-v8a", "armeabi-v7a"))?.androidAbi,
        )
        assertEquals(
            "armeabi-v7a",
            TermuxBootstrapCatalog.forAbis(listOf("armeabi-v7a", "arm64-v8a"))?.androidAbi,
        )
    }

    @Test
    fun `an unknown abi resolves to nothing instead of guessing`() {
        assertNull(TermuxBootstrapCatalog.forAbis(listOf("mips")))
        assertNull(TermuxBootstrapCatalog.forAbi("riscv64"))
    }
}
