package com.agentx.app.termux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TermuxWorkspaceBindingsTest {

    private val paths = TermuxPaths.forAppDataDir("/data/data/com.agentx.app")

    private fun resolve(
        handle: String? = null,
        display: String? = null,
        workspaceId: String = "demo",
        existing: Set<String> = emptySet(),
    ) = TermuxWorkspaceBindings.resolve(
        handle = handle,
        displayLocation = display,
        workspaceId = workspaceId,
        paths = paths,
        isDirectory = { it in existing },
    )

    @Test
    fun `a real path is used directly`() {
        val binding = resolve(handle = "/storage/emulated/0/projects/demo", existing = setOf("/storage/emulated/0/projects/demo"))
        val direct = assertIs<TermuxWorkspaceBinding.Direct>(binding)
        assertEquals("/storage/emulated/0/projects/demo", direct.path)
    }

    @Test
    fun `a file uri is accepted and normalised`() {
        val binding = resolve(
            handle = "file:///storage/emulated/0/projects/demo/",
            existing = setOf("/storage/emulated/0/projects/demo"),
        )
        assertEquals("/storage/emulated/0/projects/demo", assertIs<TermuxWorkspaceBinding.Direct>(binding).path)
    }

    @Test
    fun `a content uri is never treated as a posix path`() {
        // pwd must not lie about a SAF tree, so a content:// workspace is either mirrored or
        // reported; it is never mapped onto a made-up filesystem path.
        val binding = resolve(handle = "content://com.android.externalstorage.documents/tree/primary%3Ademo")
        val unavailable = assertIs<TermuxWorkspaceBinding.Unavailable>(binding)
        assertTrue(unavailable.reason.contains("Storage Access Framework"))
        assertTrue(unavailable.reason.contains(TermuxWorkspaceBindings.mirrorPath(paths, "demo")))
    }

    @Test
    fun `an already mirrored workspace is reused`() {
        val mirror = TermuxWorkspaceBindings.mirrorPath(paths, "demo")
        val binding = resolve(
            handle = "content://com.android.externalstorage.documents/tree/primary%3Ademo",
            existing = setOf(mirror),
        )
        val mirrored = assertIs<TermuxWorkspaceBinding.Mirrored>(binding)
        assertEquals(mirror, mirrored.termuxPath)
    }

    @Test
    fun `a path that is not readable is reported instead of silently ignored`() {
        val binding = resolve(handle = "/storage/emulated/0/projects/demo")
        val unavailable = assertIs<TermuxWorkspaceBinding.Unavailable>(binding)
        assertTrue(unavailable.reason.contains("not a readable directory"))
    }

    @Test
    fun `scheme detection only honours file`() {
        assertEquals("/a/b", TermuxWorkspaceBindings.asFilesystemPath("/a/b"))
        assertEquals("/a/b", TermuxWorkspaceBindings.asFilesystemPath("file:///a/b"))
        assertNull(TermuxWorkspaceBindings.asFilesystemPath("content://a/b"))
        assertNull(TermuxWorkspaceBindings.asFilesystemPath("https://example.com/a"))
        assertNull(TermuxWorkspaceBindings.asFilesystemPath(""))
        assertNull(TermuxWorkspaceBindings.asFilesystemPath("relative/path"))
    }

    @Test
    fun `mirror directories stay a single safe path segment`() {
        assertEquals("my_project", TermuxWorkspaceBindings.safeSegment("my/project"))
        assertEquals("a_b_c", TermuxWorkspaceBindings.safeSegment("a b c"))
        assertEquals("workspace", TermuxWorkspaceBindings.safeSegment("   "))
        assertEquals(64, TermuxWorkspaceBindings.safeSegment("x".repeat(200)).length)
    }
}
