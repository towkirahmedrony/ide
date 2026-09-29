package com.agentx.app.termux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TermuxBootstrapCatalogTest {

    private val expectedAbis = setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")

    @Test
    fun `every android abi this project builds for has a catalog entry`() {
        assertEquals(expectedAbis, TermuxBootstrapCatalog.entries.map { it.androidAbi }.toSet())
    }

    @Test
    fun `every entry targets the agentx prefix and its own asset`() {
        for (entry in TermuxBootstrapCatalog.entries) {
            assertEquals(TermuxPaths.AGENTX_PREFIX, entry.prefix)
            assertEquals("bootstrap-${entry.termuxArch}.zip", entry.assetName)
            assertFalse(
                entry.prefix.contains(TermuxPaths.OFFICIAL_PACKAGE_NAME),
                "${entry.androidAbi} must not be provisioned from the official Termux prefix",
            )
        }
    }

    @Test
    fun `an entry that is still unbuilt carries no artifact values at all`() {
        // The staging invariant from Part 1, scoped to entries that have not been built yet.
        // Once Part 2 publishes an ABI this loop simply stops covering it, and the published
        // entry is then held to the contract in the test below.
        for (entry in TermuxBootstrapCatalog.entries) {
            if (entry.sourceRevision != TermuxBootstrapCatalog.SOURCE_REVISION_UNBUILT) continue
            assertNull(entry.url, "${entry.androidAbi}: an unbuilt entry must not publish a URL")
            assertNull(entry.sha256, "${entry.androidAbi}: an unbuilt entry must not publish a digest")
            assertNull(entry.archiveSizeBytes, "${entry.androidAbi}: an unbuilt entry must not publish a size")
            assertNull(entry.fileCount, "${entry.androidAbi}: an unbuilt entry must not publish a file count")
            assertFalse(entry.available, "${entry.androidAbi}: an unbuilt entry must not report itself available")
            assertTrue(entry.unavailableReason().contains("No custom AgentX bootstrap is available yet"))
            assertFalse(
                entry.unavailableReason().contains(OFFICIAL_BOOTSTRAP_SHA256),
                "${entry.androidAbi}: the official Termux digest must never be offered as this app's artifact",
            )
        }
    }

    @Test
    fun `a published artifact reference must be complete, immutable and hex`() {
        // Task 7's catalog contract. It is written so that it starts enforcing itself the
        // moment an ABI is published: an entry may only report available=true when every
        // value in it is real and came from a built archive.
        for (entry in TermuxBootstrapCatalog.entries) {
            if (!entry.available) continue
            val url = assertNotNull(entry.url, "${entry.androidAbi}: available entry has no url")
            assertTrue(url.isNotBlank(), "${entry.androidAbi}: available entry has a blank url")
            assertFalse(url.contains("latest"), "${entry.androidAbi}: url must pin a release, not 'latest': $url")
            assertTrue(
                url.startsWith("https://"),
                "${entry.androidAbi}: release artifact must be fetched over https: $url",
            )

            val sha = assertNotNull(entry.sha256, "${entry.androidAbi}: available entry has no sha256")
            assertEquals(64, sha.length, "${entry.androidAbi}: sha256 must be 64 hex characters")
            assertTrue(
                sha.all { it in '0'..'9' || it in 'a'..'f' },
                "${entry.androidAbi}: sha256 must be lower-case hex: $sha",
            )
            assertFalse(sha.all { it == '0' }, "${entry.androidAbi}: sha256 must not be a placeholder")

            val size = assertNotNull(entry.archiveSizeBytes, "${entry.androidAbi}: available entry has no size")
            assertTrue(size > 0L, "${entry.androidAbi}: size must be positive")

            val files = assertNotNull(entry.fileCount, "${entry.androidAbi}: available entry has no file count")
            assertTrue(files > 0, "${entry.androidAbi}: file count must be positive")

            assertTrue(
                entry.sourceRevision != TermuxBootstrapCatalog.SOURCE_REVISION_UNBUILT,
                "${entry.androidAbi}: a published entry must name the revision it was built from",
            )
            assertTrue(
                entry.sourceRevision.contains(TERMUX_PACKAGES_REVISION),
                "${entry.androidAbi}: source revision must name the pinned termux-packages commit, " +
                    "was ${entry.sourceRevision}",
            )
            assertFalse(
                sha == OFFICIAL_BOOTSTRAP_SHA256,
                "${entry.androidAbi}: the official Termux digest must never be published here",
            )
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
        // arm64 is now published; use an ABI that is still pending for this shape test.
        val pending = assertNotNull(TermuxBootstrapCatalog.forAbi("armeabi-v7a"))
        assertFalse(pending.available)
        val ready = pending.copy(
            url = "https://example.invalid/bootstrap-arm.zip",
            sha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            archiveSizeBytes = 12L,
            fileCount = 4,
            sourceRevision = "termux-packages@deadbeef",
        )
        assertTrue(ready.available)

        // Each missing or malformed value on its own must make the entry unavailable, so a
        // partially filled catalog can never be mistaken for a publishable one.
        assertFalse(ready.copy(url = null).available)
        assertFalse(ready.copy(url = "  ").available)
        assertFalse(ready.copy(sha256 = null).available)
        assertFalse(ready.copy(sha256 = "abc").available)
        assertFalse(ready.copy(sha256 = "Z".repeat(64)).available)
        assertFalse(ready.copy(archiveSizeBytes = null).available)
        assertFalse(ready.copy(archiveSizeBytes = 0L).available)
        assertFalse(ready.copy(fileCount = null).available)
        assertFalse(ready.copy(fileCount = 0).available)
        assertFalse(ready.copy(sourceRevision = TermuxBootstrapCatalog.SOURCE_REVISION_UNBUILT).available)
    }

    private companion object {
        /** The digest of the published official Termux bootstrap; never ours to use. */
        const val OFFICIAL_BOOTSTRAP_SHA256 =
            "ea2aeba8819e517db711f8c32369e89e7c52cee73e07930ff91185e1ab93f4f3"

        /** The termux-packages commit the AgentX bootstrap is built from. */
        const val TERMUX_PACKAGES_REVISION = "2fdb0c07f3fec34adf24c8af515c852fc51f4c9b"
    }
}
