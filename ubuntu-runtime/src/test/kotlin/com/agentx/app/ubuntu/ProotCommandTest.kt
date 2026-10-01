package com.agentx.app.ubuntu

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProotCommandTest {

    private val layout = NativeRuntimeLayout(
        nativeLibraryDir = "/data/app/com.agentx.app/lib/arm64",
        runtimeDir = "/data/user/0/com.agentx.app/files/developer-runtime",
    )

    @Test
    fun `every required flag is present in the documented order`() {
        val invocation = ProotCommand.build(
            layout = layout,
            guestWorkingDirectory = ProotCommand.GUEST_PROJECT_ROOT,
            binds = listOf(BindMount("/dev"), BindMount("/tmp", "/tmp")),
            guestCommand = ProotCommand.LOGIN_SHELL,
        )

        assertEquals("${layout.nativeLibraryDir}/libproot.so", invocation.executable)
        assertEquals(
            listOf(
                "proot",
                "-r", layout.rootfs,
                "-0",
                "-l",
                "-w", "/workspace/project",
                "-b", "/dev",
                "-b", "/tmp:/tmp",
                "/bin/bash", "--login",
            ),
            invocation.arguments,
        )
    }

    @Test
    fun `the loader, l2s and tmp directories are exported`() {
        val invocation = ProotCommand.build(
            layout = layout,
            guestWorkingDirectory = ProotCommand.GUEST_HOME,
            binds = emptyList(),
            guestCommand = ProotCommand.LOGIN_SHELL,
        )
        assertEquals("${layout.nativeLibraryDir}/libproot_loader.so", invocation.environment["PROOT_LOADER"])
        assertEquals(layout.l2s, invocation.environment["PROOT_L2S_DIR"])
        assertEquals(layout.tmp, invocation.environment["PROOT_TMP_DIR"])
        // Never on by default: LD_LIBRARY_PATH would be inherited by guest processes.
        assertFalse(invocation.environment.containsKey("LD_LIBRARY_PATH"))
    }

    @Test
    fun `a host library path is applied only when explicitly asked for`() {
        val invocation = ProotCommand.build(
            layout = layout,
            guestWorkingDirectory = ProotCommand.GUEST_HOME,
            binds = emptyList(),
            guestCommand = ProotCommand.LOGIN_SHELL,
            hostLibraryPath = layout.nativeLibraryDir,
        )
        assertEquals(layout.nativeLibraryDir, invocation.environment["LD_LIBRARY_PATH"])
    }

    @Test
    fun `the process command drops the pty argv0 placeholder`() {
        val invocation = ProotCommand.build(
            layout = layout,
            guestWorkingDirectory = ProotCommand.GUEST_HOME,
            binds = emptyList(),
            guestCommand = ProotCommand.LOGIN_SHELL,
        )
        assertEquals(invocation.executable, invocation.processCommand.first())
        assertEquals(invocation.arguments.drop(1), invocation.processCommand.drop(1))
        assertFalse(invocation.processCommand.contains("proot"))
    }

    @Test
    fun `infrastructure binds include the kernel filesystems and the generated resolv conf`() {
        val binds = ProotCommand.infrastructureBinds(layout, "/x/etc/resolv.conf")
        assertTrue(binds.contains(BindMount("/dev")))
        assertTrue(binds.contains(BindMount("/proc")))
        assertTrue(binds.contains(BindMount("/sys")))
        assertTrue(binds.contains(BindMount("/x/etc/resolv.conf", "/etc/resolv.conf")))
        assertTrue(binds.contains(BindMount(layout.tmp, "/tmp")))
    }

    @Test
    fun `the project is bound at the guest project root`() {
        val binds = ProotCommand.withProject(listOf(BindMount("/dev")), "/sdcard/proj")
        assertTrue(binds.contains(BindMount("/sdcard/proj", ProotCommand.GUEST_PROJECT_ROOT)))
        // No real path: nothing is bound, so the shell cannot claim a project it does not have.
        assertEquals(1, ProotCommand.withProject(listOf(BindMount("/dev")), null).size)
    }

    @Test
    fun `a bind without a guest path maps onto itself`() {
        assertEquals("/dev", BindMount("/dev").spec)
        assertEquals("/a:/b", BindMount("/a", "/b").spec)
    }
}
