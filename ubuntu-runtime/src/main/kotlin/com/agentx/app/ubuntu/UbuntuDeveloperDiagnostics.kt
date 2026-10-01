package com.agentx.app.ubuntu

import com.agentx.app.termux.DeveloperLogCategory
import com.agentx.app.termux.DeveloperLogger
import java.io.File

internal object UbuntuDeveloperDiagnostics {

    private val NATIVE_LIBRARIES = listOf(
        NativeRuntimeLayout.PROOT_LIBRARY,
        NativeRuntimeLayout.LOADER_LIBRARY,
        NativeRuntimeLayout.TALLOC_LIBRARY,
        NativeRuntimeLayout.SHMEM_LIBRARY,
    )

    private val ROOTFS_ENTRIES = listOf(
        "bin/bash",
        "etc/os-release",
        "usr",
        "var",
        "home",
    )

    fun logNativeRuntime(layout: NativeRuntimeLayout, abis: List<String>) {
        DeveloperLogger.info(DeveloperLogCategory.PROOT, "Resolving PRoot")
        DeveloperLogger.info(
            DeveloperLogCategory.PROOT,
            "applicationInfo.nativeLibraryDir = ${layout.nativeLibraryDir}",
        )
        DeveloperLogger.info(
            DeveloperLogCategory.PROOT,
            "Build.SUPPORTED_ABIS = ${abis.joinToString()}",
        )
        DeveloperLogger.info(DeveloperLogCategory.PROOT, "PRoot path = ${layout.proot}")
        for (name in NATIVE_LIBRARIES) {
            val file = File("${layout.nativeLibraryDir}/$name")
            val exists = file.isFile
            val size = if (exists) file.length() else 0L
            DeveloperLogger.info(
                DeveloperLogCategory.PROOT,
                "$name exists=$exists size=$size",
            )
        }
    }

    fun logRootfs(layout: NativeRuntimeLayout) {
        DeveloperLogger.info(DeveloperLogCategory.ROOTFS, "RootFS resolution started")
        val rootfs = File(layout.rootfs)
        DeveloperLogger.info(DeveloperLogCategory.ROOTFS, "RootFS path = ${layout.rootfs}")
        DeveloperLogger.info(DeveloperLogCategory.ROOTFS, "rootfs exists = ${rootfs.exists()}")
        DeveloperLogger.info(DeveloperLogCategory.ROOTFS, "rootfs is directory = ${rootfs.isDirectory}")
        for (relative in ROOTFS_ENTRIES) {
            val file = File(rootfs, relative)
            DeveloperLogger.info(
                DeveloperLogCategory.ROOTFS,
                "<rootfs>/$relative exists = ${file.exists()}",
            )
        }
        val osRelease = File(rootfs, NativeRuntimeLayout.GUEST_OS_RELEASE_PATH)
        if (osRelease.isFile) {
            val contents = runCatching { osRelease.readText() }.getOrElse { error ->
                "unreadable: ${error.javaClass.name}: ${error.message ?: "(no message)"}"
            }
            DeveloperLogger.info(DeveloperLogCategory.ROOTFS, "os-release contents:\n${contents.trim()}")
        }
    }

    fun logLaunch(invocation: ProotInvocation, workingDirectory: String, rootfs: String) {
        DeveloperLogger.info(DeveloperLogCategory.PROCESS, "Starting process")
        DeveloperLogger.info(DeveloperLogCategory.PROCESS, "executable = ${invocation.executable}")
        DeveloperLogger.info(
            DeveloperLogCategory.PROCESS,
            "arguments = ${invocation.arguments.joinToString(" ")}",
        )
        DeveloperLogger.info(DeveloperLogCategory.PROCESS, "working directory = $workingDirectory")
        DeveloperLogger.info(DeveloperLogCategory.PROCESS, "rootfs = $rootfs")
        val binds = DeveloperLogger.flagValues(invocation.arguments, "-b")
        DeveloperLogger.info(
            DeveloperLogCategory.PROCESS,
            "bind mounts = ${binds.ifEmpty { listOf("(none)") }.joinToString()}",
        )
        DeveloperLogger.logEnvironment(invocation.environment)
    }
}
