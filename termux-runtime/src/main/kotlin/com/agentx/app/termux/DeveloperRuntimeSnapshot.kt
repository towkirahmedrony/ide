package com.agentx.app.termux

/**
 * Structured runtime snapshot for Developer → Logs.
 *
 * Only fields the caller actually has. Missing values render as `N/A`.
 */
data class DeveloperRuntimeSnapshot(
    val appVersion: String? = null,
    val androidVersion: String? = null,
    val deviceAbi: String? = null,
    val nativeLibraryDir: String? = null,
    val rootfsPath: String? = null,
    val rootfsExists: Boolean? = null,
    val rootfsBash: Boolean? = null,
    val rootfsOsRelease: Boolean? = null,
    val libproot: Boolean? = null,
    val libprootLoader: Boolean? = null,
    val libtalloc: Boolean? = null,
    val libandroidShmem: Boolean? = null,
    val sessionState: String? = null,
    val sessionHandle: String? = null,
    val sessionPid: String? = null,
    val sessionExitCode: String? = null,
    val processExecutable: String? = null,
    val processArguments: String? = null,
    val processWorkingDirectory: String? = null,
    val envHome: String? = null,
    val envPath: String? = null,
    val envShell: String? = null,
    val envTerm: String? = null,
    val envProotLoader: String? = null,
    val envProotLoader32: String? = null,
    val envProotL2sDir: String? = null,
) {

    fun render(): String = buildString {
        appendLine("=== AgentX Runtime Snapshot ===")
        appendLine()
        appendLine("App version: ${text(appVersion)}")
        appendLine("Android version: ${text(androidVersion)}")
        appendLine("Device ABI: ${text(deviceAbi)}")
        appendLine("nativeLibraryDir: ${text(nativeLibraryDir)}")
        appendLine()
        appendLine("RootFS:")
        appendLine("path: ${text(rootfsPath)}")
        appendLine("exists: ${flag(rootfsExists)}")
        appendLine("bin/bash: ${flag(rootfsBash)}")
        appendLine("etc/os-release: ${flag(rootfsOsRelease)}")
        appendLine()
        appendLine("PRoot:")
        appendLine("libproot.so: ${flag(libproot)}")
        appendLine("libproot_loader.so: ${flag(libprootLoader)}")
        appendLine("libtalloc.so: ${flag(libtalloc)}")
        appendLine("libandroid-shmem.so: ${flag(libandroidShmem)}")
        appendLine()
        appendLine("Current session:")
        appendLine("state: ${text(sessionState)}")
        appendLine("handle: ${text(sessionHandle)}")
        appendLine("PID: ${text(sessionPid)}")
        appendLine("exit code: ${text(sessionExitCode)}")
        appendLine()
        appendLine("Process:")
        appendLine("executable: ${text(processExecutable)}")
        appendLine("arguments: ${text(processArguments)}")
        appendLine("working directory: ${text(processWorkingDirectory)}")
        appendLine()
        appendLine("Environment:")
        appendLine("HOME: ${text(envHome)}")
        appendLine("PATH: ${text(envPath)}")
        appendLine("SHELL: ${text(envShell)}")
        appendLine("TERM: ${text(envTerm)}")
        appendLine("PROOT_LOADER: ${text(envProotLoader)}")
        appendLine("PROOT_LOADER32: ${text(envProotLoader32)}")
        appendLine("PROOT_L2S_DIR: ${text(envProotL2sDir)}")
        appendLine()
        append("=== End Snapshot ===")
    }

    companion object {
        private const val UNAVAILABLE = "N/A"

        private fun text(value: String?): String {
            val trimmed = value?.trim().orEmpty()
            return trimmed.ifBlank { UNAVAILABLE }
        }

        private fun flag(value: Boolean?): String = value?.toString() ?: UNAVAILABLE
    }
}
