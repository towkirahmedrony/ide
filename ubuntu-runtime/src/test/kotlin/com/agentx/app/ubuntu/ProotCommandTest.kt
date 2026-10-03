package com.agentx.app.ubuntu

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
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
                "-w", "/workspace",
                "-b", "/dev",
                "-b", "/tmp:/tmp",
                "/bin/bash", "--login",
            ),
            invocation.arguments,
        )
    }

    @Test
    fun `version starts packaged PRoot from nativeLibraryDir with PROOT_LOADER`() {
        val invocation = ProotCommand.version(layout)
        assertEquals("${layout.nativeLibraryDir}/libproot.so", invocation.executable)
        assertEquals(listOf("proot", "-V"), invocation.arguments)
        assertEquals("${layout.nativeLibraryDir}/libproot_loader.so", invocation.environment["PROOT_LOADER"])
        assertFalse(invocation.arguments.contains("-r"))
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

    @Test
    fun `the rootfs archive is unpacked through PRoot with link-to-symlink`() {
        val invocation = ProotCommand.extraction(
            layout = layout,
            hostTar = "/system/bin/tar",
            archivePath = "/x/ubuntu-base.tar.gz",
            intoDir = "/x/rootfs-staging",
        )

        assertEquals("${layout.nativeLibraryDir}/libproot.so", invocation.executable)
        assertEquals(
            listOf(
                "proot",
                "-l",
                "-w", "/",
                "/system/bin/tar",
                "-xzf", "/x/ubuntu-base.tar.gz",
                "-C", "/x/rootfs-staging",
            ),
            invocation.arguments,
        )
        // No -r: PRoot is the link interposer here, not a guest root.
        assertFalse(invocation.arguments.contains("-r"))
        assertEquals("${layout.nativeLibraryDir}/libproot_loader.so", invocation.environment["PROOT_LOADER"])
        // The store follows the destination, not the layout. This assertion used to read
        // `assertEquals(layout.l2s, ...)` while `intoDir` was a different directory, which is
        // precisely the mismatch that shipped: the log named one store and PRoot was given another.
        assertEquals("/x/rootfs-staging/.l2s", invocation.environment["PROOT_L2S_DIR"])
        assertTrue(invocation.environment["PROOT_L2S_DIR"]!!.startsWith("/x/rootfs-staging/"))
    }

    /**
     * The reported defect, pinned: extracting into `rootfs.installing` must put the emulated-link
     * store inside `rootfs.installing`, and must never name the live rootfs's store.
     *
     * `-l` records the store's absolute path inside every link it creates, so a store outside the
     * tree being unpacked makes links that are created and then cannot be resolved: `tar` reports
     * `can't link 'usr/bin/perl5.38.2' -> 'usr/bin/perl': No such file or directory`.
     */
    @Test
    fun `extraction into the installing tree keeps the link store inside it`() {
        val destination = "${layout.runtimeDir}/${NativeRuntimeLayout.INSTALLING_DIR}"
        val invocation = ProotCommand.extraction(
            layout = layout,
            hostTar = "/system/bin/tar",
            archivePath = "/x/ubuntu-base.tar.gz",
            intoDir = destination,
        )

        val store = invocation.environment["PROOT_L2S_DIR"]
        assertEquals("$destination/.l2s", store)
        assertEquals(ProotCommand.extractionStore(destination), store)
        // The live rootfs's store is a different directory and must not appear here.
        assertNotEquals(layout.l2s, store)
        assertFalse(store!!.startsWith("${layout.rootfs}/"))
        // It is inside the tree that tar is being pointed at.
        assertEquals(destination, invocation.arguments.last())
        assertTrue(store.startsWith("${destination.trimEnd('/')}/"))

        // And the derivation is the destination's, for any destination.
        assertEquals("/tmp/other-tree/.l2s", ProotCommand.extractionStore("/tmp/other-tree"))
        assertEquals("/tmp/other-tree/.l2s", ProotCommand.extractionStore("/tmp/other-tree/"))
    }
}
