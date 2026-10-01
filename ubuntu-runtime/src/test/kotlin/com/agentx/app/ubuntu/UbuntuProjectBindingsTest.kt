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
}
