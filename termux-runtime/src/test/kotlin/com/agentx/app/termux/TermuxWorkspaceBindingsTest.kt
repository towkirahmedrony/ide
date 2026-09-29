package com.agentx.app.termux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TermuxWorkspaceBindingsTest {

    private val paths = TermuxPaths.forAppDataDir("/data/data/com.agentx.app")
    private val safTree = "content://com.android.externalstorage.documents/tree/primary%3Ademo"

    /** `$HOME` exists by default: an unreadable workspace must still leave a usable shell. */
    private fun resolve(
        handle: String? = null,
        display: String? = null,
        workspaceId: String = "demo",
        existing: Set<String> = setOf(paths.home),
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
        // pwd must not lie about a SAF tree, so a content:// workspace is never mapped onto a
        // made-up filesystem path. Its mirror does not exist yet, so the shell falls back to
        // home with the reason -- and the terminal stays usable.
        val binding = resolve(handle = safTree)
        val home = assertIs<TermuxWorkspaceBinding.Home>(binding)
        assertEquals(paths.home, home.path)
        assertTrue(home.reason.contains("Storage Access Framework"))
        assertTrue(home.reason.contains(TermuxWorkspaceBindings.mirrorPathForHandle(paths, safTree)))
    }

    @Test
    fun `an already mirrored workspace is reused and labelled as a one-way copy`() {
        val mirror = TermuxWorkspaceBindings.mirrorPathForHandle(paths, safTree)
        val binding = resolve(handle = safTree, existing = setOf(mirror))
        val mirrored = assertIs<TermuxWorkspaceBinding.Mirrored>(binding)
        assertEquals(mirror, mirrored.termuxPath)
        assertEquals(MirrorDirection.SourceToMirrorOnly, mirrored.direction)
        // Write-back is not implemented, and the type says so rather than the UI guessing.
        assertFalse(mirrored.writesBack)
    }

    @Test
    fun `a path that is not readable falls back to home instead of killing the terminal`() {
        val binding = resolve(handle = "/storage/emulated/0/projects/demo")
        val home = assertIs<TermuxWorkspaceBinding.Home>(binding)
        assertEquals(paths.home, home.path)
        assertTrue(home.reason.contains("not a readable directory"))
    }

    @Test
    fun `with no home and no readable workspace there is genuinely nowhere to run`() {
        val binding = resolve(handle = "/storage/emulated/0/projects/demo", existing = emptySet())
        val unavailable = assertIs<TermuxWorkspaceBinding.Unavailable>(binding)
        assertTrue(unavailable.reason.contains("not a readable directory"))
        assertTrue(unavailable.reason.contains(paths.home))
    }

    @Test
    fun `fallbackHome keeps the reason and the original label`() {
        val home = assertIs<TermuxWorkspaceBinding.Home>(
            TermuxWorkspaceBindings.fallbackHome(
                paths = paths,
                shown = "Downloads",
                reason = "Mirroring failed.",
                isDirectory = { it == paths.home },
            ),
        )
        assertEquals(paths.home, home.path)
        assertEquals("Downloads", home.displayLocation)
        assertEquals("Mirroring failed.", home.reason)
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

    @Test
    fun `a hostile workspace handle cannot escape the workspaces directory`() {
        val hostile = listOf(
            "../../../../data/data/com.agentx.app/files/home",
            "content://x/../../etc/passwd",
            "content://x/tree/..%2F..%2Fsystem",
            "/",
            "..",
            ".",
            "",
            "   ",
            "\u0000bad",
            "a".repeat(4096),
            "café/☃/naïve",
            "content://x/tree/primary%3A/a?query=1#frag",
            "..\\..\\windows",
        )

        for (handle in hostile) {
            val segment = TermuxWorkspaceBindings.mirrorSegment(handle)
            val location = TermuxWorkspaceBindings.mirrorPathForHandle(paths, handle)

            assertTrue(segment.isNotEmpty(), "empty segment for '$handle'")
            assertTrue(segment.length <= 41, "segment too long for '$handle': $segment")
            assertTrue(
                segment.all { it.isLetterOrDigit() || it == '-' || it == '_' },
                "unsafe character in '$segment' from '$handle'",
            )
            assertNotEquals(".", segment)
            assertNotEquals("..", segment)
            assertFalse(segment.contains('/'), "separator survived in '$segment' from '$handle'")
            // The mirror always stays one directory below the app-owned workspaces directory.
            assertTrue(location.startsWith("${paths.workspaces}/"), "escaped workspaces: $location")
            val tail = location.removePrefix("${paths.workspaces}/")
            assertTrue(tail.isNotEmpty(), "empty mirror name for '$handle'")
            assertFalse(tail.contains('/'), "the mirror must be exactly one directory deep: $tail")
        }
    }

    @Test
    fun `the same handle always maps to the same mirror directory`() {
        assertEquals(
            TermuxWorkspaceBindings.mirrorSegment(safTree),
            TermuxWorkspaceBindings.mirrorSegment(safTree),
        )
        assertEquals(
            TermuxWorkspaceBindings.mirrorSegment(safTree),
            TermuxWorkspaceBindings.mirrorSegment("  $safTree  "),
        )
    }

    @Test
    fun `different handles never share a mirror directory`() {
        // Same readable tail, different trees: safeSegment() would collide here because it
        // truncates, which is exactly why the digest is part of the name.
        val first = TermuxWorkspaceBindings.mirrorSegment("content://provider/tree/primary%3A${"x".repeat(80)}")
        val second = TermuxWorkspaceBindings.mirrorSegment("content://provider/tree/primary%3A${"y".repeat(80)}")
        assertNotEquals(first, second)
        assertNotEquals(
            TermuxWorkspaceBindings.mirrorSegment("content://one/tree/primary%3Aproject"),
            TermuxWorkspaceBindings.mirrorSegment("content://two/tree/primary%3Aproject"),
        )
    }

    @Test
    fun `the mirror name keeps a recognisable tail for the user`() {
        // The document id is `primary:demo`, so the recognisable part is the folder name.
        assertTrue(TermuxWorkspaceBindings.mirrorSegment(safTree).startsWith("demo"),
            TermuxWorkspaceBindings.mirrorSegment(safTree))
        assertTrue(TermuxWorkspaceBindings.mirrorSegment("content://x/tree/primary%3Amy-project").startsWith("my-project"))
    }
}
