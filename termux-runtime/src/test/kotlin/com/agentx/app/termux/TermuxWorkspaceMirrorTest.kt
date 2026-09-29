package com.agentx.app.termux

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TermuxWorkspaceMirrorTest {

    private class FakeSource(
        private val entries: Map<String, List<MirrorEntry>>,
        private val contents: Map<String, ByteArray> = emptyMap(),
        private val failOn: String? = null,
        private val nameOverride: Map<String, String> = emptyMap(),
    ) : MirrorSource {
        var reads = 0
            private set

        override fun list(relativePath: String): List<MirrorEntry> {
            if (relativePath == failOn) throw IllegalStateException("unreadable")
            return entries[relativePath].orEmpty().map { entry ->
                val override = nameOverride["$relativePath/${entry.name}"]
                if (override == null) entry else entry.copy(name = override)
            }
        }

        override fun read(relativePath: String): ByteArray {
            reads++
            if (relativePath == failOn) throw IllegalStateException("unreadable")
            return contents[relativePath] ?: ByteArray(0)
        }
    }

    private class FakeSink(private val failOn: String? = null) : MirrorSink {
        val directories = mutableListOf<String>()
        val files = mutableMapOf<String, Int>()

        override fun createDirectory(relativePath: String) {
            if (relativePath == failOn) throw IllegalStateException("nope")
            directories += relativePath
        }

        override fun write(relativePath: String, bytes: ByteArray) {
            if (relativePath == failOn) throw IllegalStateException("nope")
            files[relativePath] = bytes.size
        }
    }

    @Test
    fun `a tree is copied with its directories, files and byte count`() {
        val source = FakeSource(
            entries = mapOf(
                "" to listOf(MirrorEntry("src", directory = true), MirrorEntry("README.md", directory = false, sizeBytes = 4)),
                "src" to listOf(MirrorEntry("main.kt", directory = false, sizeBytes = 3)),
            ),
            contents = mapOf("README.md" to "read".toByteArray(), "src/main.kt" to "abc".toByteArray()),
        )
        val sink = FakeSink()

        val outcome = assertIs<MirrorOutcome.Completed>(TermuxWorkspaceMirror.mirror(source, sink))

        assertEquals(listOf("src"), sink.directories)
        assertEquals(setOf("README.md", "src/main.kt"), sink.files.keys)
        assertEquals(2, outcome.files)
        assertEquals(1, outcome.directories)
        assertEquals(7L, outcome.bytes)
    }

    @Test
    fun `a hostile entry name fails the mirror instead of escaping it`() {
        val hostile = listOf(
            "../escape",
            "..",
            ".",
            "",
            "nested/path",
            "back\\slash",
            "nul\u0000byte",
            "a".repeat(300),
        )

        for (name in hostile) {
            val source = FakeSource(
                entries = mapOf("" to listOf(MirrorEntry(name, directory = false, sizeBytes = 1))),
                contents = mapOf(name to byteArrayOf(1)),
            )
            val sink = FakeSink()

            val outcome = assertIs<MirrorOutcome.Failed>(
                TermuxWorkspaceMirror.mirror(source, sink),
                "expected '$name' to be refused",
            )
            assertTrue(outcome.reason.contains("unusable entry name"), "unexpected reason: ${outcome.reason}")
            assertTrue(sink.files.isEmpty(), "a hostile name still produced ${sink.files.keys}")
            assertTrue(sink.directories.isEmpty())
        }
    }

    @Test
    fun `a hostile directory name cannot create a directory outside the mirror`() {
        val source = FakeSource(
            entries = mapOf(
                "" to listOf(MirrorEntry("../../etc", directory = true)),
                "../.." to emptyList(),
            ),
        )
        val sink = FakeSink()
        assertIs<MirrorOutcome.Failed>(TermuxWorkspaceMirror.mirror(source, sink))
        assertTrue(sink.directories.isEmpty())
    }

    @Test
    fun `a source that renames an entry mid-copy still cannot escape`() {
        // The name is validated per entry as it is walked, not only at the root.
        val source = FakeSource(
            entries = mapOf(
                "" to listOf(MirrorEntry("src", directory = true)),
                "src" to listOf(MirrorEntry("inner", directory = false, sizeBytes = 1)),
            ),
            contents = mapOf("src/inner" to byteArrayOf(1)),
            nameOverride = mapOf("src/inner" to "../../../escape"),
        )
        val sink = FakeSink()
        val outcome = assertIs<MirrorOutcome.Failed>(TermuxWorkspaceMirror.mirror(source, sink))
        assertTrue(outcome.reason.contains("unusable entry name"))
        assertTrue(sink.files.isEmpty())
    }

    @Test
    fun `safeRelativeName accepts ordinary names and refuses everything structural`() {
        assertEquals("main.kt", TermuxWorkspaceMirror.safeRelativeName("main.kt"))
        assertEquals("a-b_c d", TermuxWorkspaceMirror.safeRelativeName("a-b_c d"))
        assertNull(TermuxWorkspaceMirror.safeRelativeName(""))
        assertNull(TermuxWorkspaceMirror.safeRelativeName("."))
        assertNull(TermuxWorkspaceMirror.safeRelativeName(".."))
        assertNull(TermuxWorkspaceMirror.safeRelativeName("a/b"))
        assertNull(TermuxWorkspaceMirror.safeRelativeName("a\\b"))
        assertNull(TermuxWorkspaceMirror.safeRelativeName("a\u0000b"))
        assertNull(TermuxWorkspaceMirror.safeRelativeName("/etc/passwd"))
        assertNull(TermuxWorkspaceMirror.safeRelativeName("x".repeat(256)))
    }

    @Test
    fun `cancellation stops the copy and reports how far it got`() {
        val source = FakeSource(
            entries = mapOf(
                "" to (1..10).map { MirrorEntry("file$it.txt", directory = false, sizeBytes = 1) },
            ),
            contents = (1..10).associate { "file$it.txt" to byteArrayOf(1) },
        )
        val sink = FakeSink()

        var checks = 0
        val outcome = assertIs<MirrorOutcome.Cancelled>(
            TermuxWorkspaceMirror.mirror(source, sink, isCancelled = { ++checks > 4 }),
        )

        assertTrue(outcome.files in 1..3, "copied ${outcome.files} files before cancelling")
        assertEquals(outcome.files, sink.files.size)
        assertTrue(outcome.summary.contains("cancelled"))
        assertTrue(outcome.summary.contains("not written back"))
    }

    @Test
    fun `cancelling before the first entry copies nothing`() {
        val source = FakeSource(entries = mapOf("" to listOf(MirrorEntry("a.txt", false, 1))))
        val sink = FakeSink()
        val outcome = assertIs<MirrorOutcome.Cancelled>(TermuxWorkspaceMirror.mirror(source, sink, isCancelled = { true }))
        assertEquals(0, outcome.files)
        assertTrue(sink.files.isEmpty())
        assertEquals("", outcome.at)
    }

    @Test
    fun `a source that cannot be read fails with the path in the reason`() {
        val source = FakeSource(
            entries = mapOf("" to listOf(MirrorEntry("src", directory = true))),
            failOn = "src",
        )
        val outcome = assertIs<MirrorOutcome.Failed>(TermuxWorkspaceMirror.mirror(source, FakeSink()))
        assertTrue(outcome.reason.contains("could not read 'src'"), outcome.reason)
        assertTrue(outcome.summary.contains("running in home"))
    }

    @Test
    fun `a sink that cannot be written fails rather than reporting success`() {
        val source = FakeSource(
            entries = mapOf("" to listOf(MirrorEntry("a.txt", directory = false, sizeBytes = 1))),
            contents = mapOf("a.txt" to byteArrayOf(1)),
        )
        val outcome = assertIs<MirrorOutcome.Failed>(TermuxWorkspaceMirror.mirror(source, FakeSink(failOn = "a.txt")))
        assertTrue(outcome.reason.contains("could not write 'a.txt'"), outcome.reason)
    }

    @Test
    fun `an oversized folder is refused before it fills the data partition`() {
        val tooMany = FakeSource(
            entries = mapOf("" to (0..TermuxWorkspaceMirror.MAX_FILES).map { MirrorEntry("f$it", false, 0) }),
            contents = (0..TermuxWorkspaceMirror.MAX_FILES).associate { "f$it" to ByteArray(0) },
        )
        val countOutcome = assertIs<MirrorOutcome.Failed>(TermuxWorkspaceMirror.mirror(tooMany, FakeSink()))
        assertTrue(countOutcome.reason.contains("more than ${TermuxWorkspaceMirror.MAX_FILES} files"), countOutcome.reason)

        val tooBig = FakeSource(
            entries = mapOf(
                "" to listOf(MirrorEntry("huge.bin", directory = false, sizeBytes = TermuxWorkspaceMirror.MAX_TOTAL_BYTES + 1)),
            ),
        )
        val sizeOutcome = assertIs<MirrorOutcome.Failed>(TermuxWorkspaceMirror.mirror(tooBig, FakeSink()))
        assertTrue(sizeOutcome.reason.contains("larger than"), sizeOutcome.reason)
    }

    @Test
    fun `a tree deeper than the cap is refused instead of recursing forever`() {
        // Every directory contains exactly one more directory, and none of them is a file.
        val deep = object : MirrorSource {
            override fun list(relativePath: String): List<MirrorEntry> = listOf(MirrorEntry("deep", directory = true))
            override fun read(relativePath: String): ByteArray = ByteArray(0)
        }
        val outcome = assertIs<MirrorOutcome.Failed>(TermuxWorkspaceMirror.mirror(deep, FakeSink()))
        assertTrue(outcome.reason.contains("deeper than ${TermuxWorkspaceMirror.MAX_DEPTH}"), outcome.reason)
    }

    @Test
    fun `an empty source completes cleanly`() {
        val outcome = assertIs<MirrorOutcome.Completed>(
            TermuxWorkspaceMirror.mirror(FakeSource(entries = mapOf("" to emptyList())), FakeSink()),
        )
        assertEquals(0, outcome.files)
        assertTrue(outcome.summary.contains("not written back"))
    }

    @Test
    fun `the file source and sink round-trip through a real directory`() {
        val root = File(System.getProperty("java.io.tmpdir"), "agentx-mirror-src-${System.nanoTime()}")
        val mirror = File(System.getProperty("java.io.tmpdir"), "agentx-mirror-dst-${System.nanoTime()}")
        try {
            File(root, "src").mkdirs()
            File(root, "src/main.kt").writeText("fun main() = Unit")
            File(root, "README.md").writeText("hi")

            val outcome = assertIs<MirrorOutcome.Completed>(
                TermuxWorkspaceMirror.mirror(FileMirrorSource(root), FileMirrorSink(mirror)),
            )

            assertEquals(2, outcome.files)
            assertEquals(1, outcome.directories)
            assertEquals("fun main() = Unit", File(mirror, "src/main.kt").readText())
            assertEquals("hi", File(mirror, "README.md").readText())
        } finally {
            root.deleteRecursively()
            mirror.deleteRecursively()
        }
    }
}
