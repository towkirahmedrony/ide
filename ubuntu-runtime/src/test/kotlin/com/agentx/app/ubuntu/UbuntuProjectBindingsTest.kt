package com.agentx.app.ubuntu

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun `shared storage is recognised from every way a project location arrives`() {
        // A project in the AgentX folder in shared storage: a real path.
        assertEquals(
            "/storage/emulated/0/AgentX/demo",
            UbuntuProjectBindings.sharedStorageLocation("/storage/emulated/0/AgentX/demo"),
        )
        // A removable volume is shared storage too.
        assertEquals(
            "/storage/1234-ABCD/AgentX/demo",
            UbuntuProjectBindings.sharedStorageLocation("/storage/1234-ABCD/AgentX/demo"),
        )
        // The platform's aliases for the primary volume name the same place.
        assertEquals("/sdcard/Projects/app", UbuntuProjectBindings.sharedStorageLocation("/sdcard/Projects/app"))
        assertEquals(
            "/mnt/sdcard/Projects/app",
            UbuntuProjectBindings.sharedStorageLocation("/mnt/sdcard/Projects/app"),
        )
        // A `file://` URI is the same path.
        assertEquals(
            "/storage/emulated/0/AgentX/demo",
            UbuntuProjectBindings.sharedStorageLocation("file:///storage/emulated/0/AgentX/demo/"),
        )
        // A SAF tree over shared storage resolves to the folder it names.
        assertEquals(
            "/storage/emulated/0/AgentX/demo",
            UbuntuProjectBindings.sharedStorageLocation(
                "content://com.android.externalstorage.documents/tree/primary%3AAgentX%2Fdemo",
            ),
        )
    }

    @Test
    fun `an app-private project and an unresolvable location are not shared storage`() {
        // The root the runtime owns, and the legacy project folder under the app's own data.
        assertNull(UbuntuProjectBindings.sharedStorageLocation("/data/data/com.agentx.app/files/developer-runtime"))
        assertNull(
            UbuntuProjectBindings.sharedStorageLocation(
                "/data/user/0/com.agentx.app/files/projects/legacy",
            ),
        )
        // A cloud tree has no local path, so it cannot be asserted to be shared storage.
        assertNull(
            UbuntuProjectBindings.sharedStorageLocation(
                "content://com.google.android.apps.docs.storage/tree/primary%3AAgentX%2Fdemo",
            ),
        )
        // A directory that merely shares the prefix is not inside shared storage.
        assertNull(UbuntuProjectBindings.sharedStorageLocation("/storage-backups/app"))
        assertNull(UbuntuProjectBindings.sharedStorageLocation("/sdcardx/app"))
        assertNull(UbuntuProjectBindings.sharedStorageLocation("relative/path"))
        assertNull(UbuntuProjectBindings.sharedStorageLocation(null))
        assertNull(UbuntuProjectBindings.sharedStorageLocation(""))
    }

    @Test
    fun `the shared storage classifier is the boolean form of the same answer`() {
        assertTrue(UbuntuProjectBindings.isSharedStorageLocation("/storage/emulated/0/AgentX/demo"))
        assertFalse(UbuntuProjectBindings.isSharedStorageLocation("/data/data/com.agentx.app/files/projects/legacy"))
        assertFalse(UbuntuProjectBindings.isSharedStorageLocation(null))
    }

    @Test
    fun `a shared storage project that is now readable is bound and entered at workspace`() {
        // Exactly the construction the terminal performs once the access is in place: the project
        // path is probed, bound, and the shell is started with `/workspace` as its working
        // directory, so `pwd` inside the guest is the project.
        val host = "/storage/emulated/0/AgentX/demo"
        val layout = NativeRuntimeLayout(
            nativeLibraryDir = "/data/app/com.agentx.app/lib/arm64",
            runtimeDir = "/data/user/0/com.agentx.app/files/developer-runtime",
        )

        val binding = assertIs<UbuntuProjectBinding.Direct>(
            UbuntuProjectBindings.resolve(handle = host, displayLocation = host, isDirectory = { it == host }),
        )
        assertEquals(host, binding.hostPath)
        assertEquals(ProotCommand.GUEST_PROJECT_ROOT, binding.guestPath)

        val invocation = ProotCommand.build(
            layout = layout,
            guestWorkingDirectory = binding.guestPath,
            binds = ProotCommand.withProject(ProotCommand.infrastructureBinds(layout, resolvConf = null), binding.hostPath),
            guestCommand = ProotCommand.LOGIN_SHELL,
        )
        assertEquals(
            "/workspace",
            invocation.arguments[invocation.arguments.indexOf("-w") + 1],
        )
        assertEquals(1, invocation.arguments.count { it == "$host:/workspace" })
        assertEquals(ProotCommand.LOGIN_SHELL, invocation.arguments.takeLast(2))
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
