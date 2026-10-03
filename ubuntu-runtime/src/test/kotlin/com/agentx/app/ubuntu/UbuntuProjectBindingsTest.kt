package com.agentx.app.ubuntu

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class UbuntuProjectBindingsTest {

    @Test
    fun `a readable host directory is bound as a direct project`() {
        val binding = UbuntuProjectBindings.resolve(
            handle = "/sdcard/projects/app",
            displayLocation = null,
            isDirectory = { it == "/sdcard/projects/app" },
        )
        val direct = assertIs<UbuntuProjectBinding.Direct>(binding)
        assertEquals("/sdcard/projects/app", direct.hostPath)
        assertEquals(ProotCommand.GUEST_PROJECT_ROOT, direct.guestPath)
    }

    @Test
    fun `a saf tree falls back to the guest home with a reason`() {
        val binding = UbuntuProjectBindings.resolve(
            handle = "content://com.android.externalstorage.documents/tree/primary%3Aapp",
            displayLocation = "app",
            isDirectory = { false },
        )
        val home = assertIs<UbuntuProjectBinding.Home>(binding)
        assertEquals(ProotCommand.GUEST_HOME, home.guestPath)
        assertNull(home.hostPath)
        assertEquals(true, home.reason.contains("Storage Access Framework"))
    }

    @Test
    fun `a path that exists but is unreadable is reported as a permission problem`() {
        val binding = UbuntuProjectBindings.resolve(
            handle = "/sdcard/projects/app",
            displayLocation = "/sdcard/projects/app",
            isDirectory = { false },
        )
        val home = assertIs<UbuntuProjectBinding.Home>(binding)
        assertEquals(true, home.reason.contains("not a readable directory"))
    }

    @Test
    fun `only absolute paths and file uris are filesystem paths`() {
        assertEquals("/a/b", UbuntuProjectBindings.asFilesystemPath("file:///a/b/"))
        assertEquals("/a/b", UbuntuProjectBindings.asFilesystemPath("/a/b"))
        assertNull(UbuntuProjectBindings.asFilesystemPath("content://x/y"))
        assertNull(UbuntuProjectBindings.asFilesystemPath("relative/path"))
        assertNull(UbuntuProjectBindings.asFilesystemPath(""))
    }

    @Test
    fun `a saf tree resolves to the phone storage path it names`() {
        assertEquals(
            "/storage/emulated/0/app",
            UbuntuProjectBindings.safFilesystemPath(
                "content://com.android.externalstorage.documents/tree/primary%3Aapp",
            ),
        )
        assertEquals(
            "/storage/emulated/0/Projects/demo app",
            UbuntuProjectBindings.safFilesystemPath(
                "content://com.android.externalstorage.documents/tree/primary%3AProjects%2Fdemo%20app",
            ),
        )
        assertEquals(
            "/storage/1234-ABCD/backups",
            UbuntuProjectBindings.safFilesystemPath(
                "content://com.android.externalstorage.documents/tree/1234-ABCD%3Abackups",
            ),
        )
        // The tree id names the picked folder itself; a document id that follows is ignored.
        assertEquals(
            "/storage/emulated/0/app",
            UbuntuProjectBindings.safFilesystemPath(
                "content://com.android.externalstorage.documents/tree/primary%3Aapp" +
                    "/document/primary%3Aapp%2Fsub",
            ),
        )
    }

    @Test
    fun `only provably local tree uris produce a saf path`() {
        // Not the external-storage provider: a cloud tree has no local path to derive.
        assertNull(
            UbuntuProjectBindings.safFilesystemPath(
                "content://com.google.android.apps.docs.storage/tree/primary%3Aapp",
            ),
        )
        // Not a tree URI.
        assertNull(
            UbuntuProjectBindings.safFilesystemPath(
                "content://com.android.externalstorage.documents/document/primary%3Aapp",
            ),
        )
        // No volume separator, and traversal segments are refused rather than guessed at.
        assertNull(
            UbuntuProjectBindings.safFilesystemPath(
                "content://com.android.externalstorage.documents/tree/nocolon",
            ),
        )
        assertNull(
            UbuntuProjectBindings.safFilesystemPath(
                "content://com.android.externalstorage.documents/tree/primary%3A..%2Fetc",
            ),
        )
        // Plain paths and nothing at all are not SAF handles.
        assertNull(UbuntuProjectBindings.safFilesystemPath("/storage/emulated/0/app"))
        assertNull(UbuntuProjectBindings.safFilesystemPath(null))
    }
}
