package com.agentx.app.ubuntu

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The unified workspace mapping: whatever the project source, the PRoot guest sees it at exactly
 * `/workspace`, the shell starts there, and no project means no bind at all.
 *
 * These tests exercise the same construction [LocalUbuntuRuntime] performs — resolve the handle,
 * add the project bind to the infrastructure binds, build the invocation — through the pure
 * pieces it is made of, so they run on the JVM without a device.
 */
class WorkspaceMountTest {

    private val layout = NativeRuntimeLayout(
        nativeLibraryDir = "/data/app/com.agentx.app/lib/arm64",
        runtimeDir = "/data/user/0/com.agentx.app/files/developer-runtime",
    )

    /** The invocation [LocalUbuntuRuntime] builds for a terminal session. */
    private fun sessionFor(binding: UbuntuProjectBinding): ProotInvocation = ProotCommand.build(
        layout = layout,
        guestWorkingDirectory = binding.guestPath,
        binds = ProotCommand.withProject(
            binds = ProotCommand.infrastructureBinds(layout, resolvConf = null),
            projectHostPath = binding.hostPath,
        ),
        guestCommand = ProotCommand.LOGIN_SHELL,
    )

    private fun bindsFor(binding: UbuntuProjectBinding): List<BindMount> = ProotCommand.withProject(
        binds = ProotCommand.infrastructureBinds(layout, resolvConf = null),
        projectHostPath = binding.hostPath,
    )

    @Test
    fun `the guest project root is exactly workspace`() {
        assertEquals("/workspace", ProotCommand.GUEST_PROJECT_ROOT)
    }

    @Test
    fun `a git-managed project path produces the correct workspace bind mount`() {
        // A repository AgentX cloned into its managed storage is a plain host path.
        val hostPath = "/data/user/0/com.agentx.app/files/projects/demo-repo"
        val binding = UbuntuProjectBindings.resolve(
            handle = hostPath,
            displayLocation = null,
            isDirectory = { it == hostPath },
        )
        val direct = assertIs<UbuntuProjectBinding.Direct>(binding)
        assertEquals(hostPath, direct.hostPath)
        assertEquals("/workspace", direct.guestPath)

        val projectBind = bindsFor(binding).single { it.guest == "/workspace" }
        assertEquals(BindMount(hostPath, "/workspace"), projectBind)
        assertEquals("$hostPath:/workspace", projectBind.spec)
    }

    @Test
    fun `a saf project binds its original folder at workspace without a copy`() {
        // A folder picked from phone storage: the tree URI names the original directory.
        val handle = "content://com.android.externalstorage.documents/tree/primary%3AProjects%2Fdemo"
        val original = "/storage/emulated/0/Projects/demo"
        val binding = UbuntuProjectBindings.resolve(
            handle = handle,
            displayLocation = "Projects/demo",
            isDirectory = { it == original },
        )
        val direct = assertIs<UbuntuProjectBinding.Direct>(binding)
        // The original location, not a mirror under app storage.
        assertEquals(original, direct.hostPath)
        assertFalse(direct.hostPath.contains("files/workspaces"))
        assertEquals("/workspace", direct.guestPath)

        val projectBind = bindsFor(binding).single { it.guest == "/workspace" }
        assertEquals("$original:/workspace", projectBind.spec)
    }

    @Test
    fun `workspace is the terminal working directory`() {
        val hostPath = "/data/user/0/com.agentx.app/files/projects/demo-repo"
        val binding = UbuntuProjectBindings.resolve(
            handle = hostPath,
            displayLocation = null,
            isDirectory = { it == hostPath },
        )
        val invocation = sessionFor(binding)
        val workingDirectoryFlag = invocation.arguments.indexOf("-w")
        assertTrue(workingDirectoryFlag >= 0)
        assertEquals("/workspace", invocation.arguments[workingDirectoryFlag + 1])
        assertEquals(binding.guestPath, invocation.arguments[workingDirectoryFlag + 1])
        // The shell itself is unchanged: still a login shell, started by PRoot.
        assertEquals(ProotCommand.LOGIN_SHELL, invocation.arguments.takeLast(2))
    }

    @Test
    fun `without a project the terminal behaviour is unchanged`() {
        val binding = UbuntuProjectBindings.resolve(
            handle = null,
            displayLocation = null,
            isDirectory = { false },
        )
        val home = assertIs<UbuntuProjectBinding.Home>(binding)
        assertEquals(ProotCommand.GUEST_HOME, home.guestPath)
        assertNull(home.hostPath)

        val invocation = sessionFor(binding)
        val workingDirectoryFlag = invocation.arguments.indexOf("-w")
        assertEquals(ProotCommand.GUEST_HOME, invocation.arguments[workingDirectoryFlag + 1])
        // No workspace bind of any kind.
        assertFalse(invocation.arguments.contains("/workspace"))
        assertFalse(invocation.arguments.any { it.endsWith(":/workspace") })
        // The infrastructure binds are all still there.
        assertTrue(invocation.arguments.contains("-b"))
        assertEquals(4, invocation.arguments.count { it == "-b" })
        assertEquals(ProotCommand.LOGIN_SHELL, invocation.arguments.takeLast(2))
    }

    @Test
    fun `project paths with spaces survive bind and cwd construction`() {
        // A SAF tree whose folder name contains spaces and percent-escapes.
        val handle = "content://com.android.externalstorage.documents/tree/primary%3AMy%20Projects%2Fdemo%20app"
        val original = "/storage/emulated/0/My Projects/demo app"
        val binding = UbuntuProjectBindings.resolve(
            handle = handle,
            displayLocation = "My Projects/demo app",
            isDirectory = { it == original },
        )
        assertEquals(original, assertIs<UbuntuProjectBinding.Direct>(binding).hostPath)
        val safBind = bindsFor(binding).single { it.guest == "/workspace" }
        assertEquals("$original:/workspace", safBind.spec)

        // A plain host path with spaces.
        val spaced = "/data/user/0/com.agentx.app/files/My Projects/repo one"
        val plain = UbuntuProjectBindings.resolve(
            handle = spaced,
            displayLocation = null,
            isDirectory = { it == spaced },
        )
        val invocation = sessionFor(plain)

        // The bind spec stays one argv element — no shell splits it — and the working directory
        // is the guest path, spaces and all.
        val workingDirectoryFlag = invocation.arguments.indexOf("-w")
        assertEquals("/workspace", invocation.arguments[workingDirectoryFlag + 1])
        val projectFlag = invocation.arguments.indexOf("$spaced:/workspace")
        assertTrue(projectFlag > 0)
        assertEquals("-b", invocation.arguments[projectFlag - 1])
        assertEquals(1, invocation.arguments.count { it == "$spaced:/workspace" })
    }

    @Test
    fun `the workspace bind is generated exactly once`() {
        val hostPath = "/data/user/0/com.agentx.app/files/projects/demo-repo"
        val infrastructure = ProotCommand.infrastructureBinds(layout, resolvConf = "/x/etc/resolv.conf")
        // Infrastructure never carries the workspace guest.
        assertEquals(0, infrastructure.count { it.guest == "/workspace" })

        val binds = ProotCommand.withProject(infrastructure, hostPath)
        assertEquals(1, binds.count { it.guest == "/workspace" })

        // Even a repeated call cannot produce a second workspace bind.
        val repeated = ProotCommand.withProject(binds, hostPath)
        assertEquals(1, repeated.count { it.guest == "/workspace" })

        val invocation = ProotCommand.build(
            layout = layout,
            guestWorkingDirectory = ProotCommand.GUEST_PROJECT_ROOT,
            binds = repeated,
            guestCommand = ProotCommand.LOGIN_SHELL,
        )
        assertEquals(1, invocation.arguments.count { it == "$hostPath:/workspace" })
        assertEquals(invocation.arguments.count { it == "-b" }, repeated.size)
    }
}
