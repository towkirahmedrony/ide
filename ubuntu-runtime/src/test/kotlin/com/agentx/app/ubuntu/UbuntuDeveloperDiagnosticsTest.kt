package com.agentx.app.ubuntu

import com.agentx.app.termux.DeveloperLogger
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

class UbuntuDeveloperDiagnosticsTest {

    private lateinit var directory: File

    @BeforeTest
    fun setUp() {
        directory = File.createTempFile("ubuntu-diagnostics", "dir").apply {
            delete()
            mkdirs()
        }
        DeveloperLogger.attach(File(directory, "diagnostics/terminal.log"))
        DeveloperLogger.clear()
    }

    @AfterTest
    fun tearDown() {
        DeveloperLogger.clear()
        DeveloperLogger.attach(null)
    }

    @Test
    fun `rootfs probe logs existence of required paths and os-release contents`() {
        val filesDir = File(directory, "files").apply { mkdirs() }
        val nativeDir = File(directory, "lib").apply { mkdirs() }
        File(nativeDir, NativeRuntimeLayout.PROOT_LIBRARY).writeBytes(ByteArray(12))
        File(nativeDir, NativeRuntimeLayout.LOADER_LIBRARY).writeBytes(ByteArray(8))
        File(nativeDir, NativeRuntimeLayout.TALLOC_LIBRARY).writeBytes(ByteArray(4))
        File(nativeDir, NativeRuntimeLayout.SHMEM_LIBRARY).writeBytes(ByteArray(6))

        val layout = NativeRuntimeLayout.forContext(
            nativeLibraryDir = nativeDir.absolutePath,
            filesDir = filesDir.absolutePath,
        )
        File(layout.rootfs).mkdirs()
        File(layout.guestShell).apply { parentFile?.mkdirs(); writeText("bash") }
        File(layout.guestOsRelease).apply { parentFile?.mkdirs(); writeText("NAME=Ubuntu\nVERSION=24.04\n") }
        File(layout.rootfs, "usr").mkdirs()
        File(layout.rootfs, "var").mkdirs()
        File(layout.rootfs, "home").mkdirs()

        UbuntuDeveloperDiagnostics.logNativeRuntime(layout, listOf("arm64-v8a"))
        UbuntuDeveloperDiagnostics.logRootfs(layout)

        val contents = DeveloperLogger.readAll()
        assertTrue(contents.contains("applicationInfo.nativeLibraryDir = ${layout.nativeLibraryDir}"), contents)
        assertTrue(contents.contains("Build.SUPPORTED_ABIS = arm64-v8a"), contents)
        assertTrue(contents.contains("libproot.so exists=true size=12"), contents)
        assertTrue(contents.contains("RootFS path = ${layout.rootfs}"), contents)
        assertTrue(contents.contains("<rootfs>/bin/bash exists = true"), contents)
        assertTrue(contents.contains("NAME=Ubuntu"), contents)
    }

    @Test
    fun `launch log records executable arguments cwd rootfs and binds`() {
        val layout = NativeRuntimeLayout.forContext(
            nativeLibraryDir = "/data/app/lib",
            filesDir = "/data/user/0/com.agentx.app/files",
        )
        val invocation = ProotCommand.build(
            layout = layout,
            guestWorkingDirectory = "/root",
            binds = listOf(BindMount("/dev"), BindMount("/tmp", "/tmp")),
            guestCommand = ProotCommand.LOGIN_SHELL,
        )
        UbuntuDeveloperDiagnostics.logLaunch(invocation, layout.runtimeDir, layout.rootfs)
        val contents = DeveloperLogger.readAll()
        assertTrue(contents.contains("executable = ${layout.proot}"), contents)
        assertTrue(contents.contains("working directory = ${layout.runtimeDir}"), contents)
        assertTrue(contents.contains("rootfs = ${layout.rootfs}"), contents)
        assertTrue(contents.contains("bind mounts = /dev, /tmp:/tmp"), contents)
        assertTrue(contents.contains("PROOT_LOADER="), contents)
    }
}
