package com.agentx.app.ui.ide.state

import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentx.app.termux.DEVELOPER_LOGS_UI_LIMIT
import com.agentx.app.termux.DeveloperLogCategory
import com.agentx.app.termux.DeveloperLogFilter
import com.agentx.app.termux.DeveloperLogger
import com.agentx.app.termux.DeveloperRuntimeSnapshot
import com.agentx.app.termux.TerminalSessionState
import com.agentx.app.termux.TermuxEnvironment
import com.agentx.app.termux.TermuxRuntime
import com.agentx.app.termux.lastLogValue
import com.agentx.app.termux.visibleDeveloperLogs
import com.agentx.app.ubuntu.LocalUbuntuRuntime
import com.agentx.app.ubuntu.NativeRuntimeLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

class DeveloperLogsViewModel(
    private val appVersion: String,
    private val terminalRuntime: TermuxRuntime? = null,
    private val developerRuntime: LocalUbuntuRuntime? = null,
    private val androidVersion: () -> String? = { Build.VERSION.RELEASE },
    private val deviceAbi: () -> String? = {
        Build.SUPPORTED_ABIS?.joinToString().takeUnless { it.isNullOrBlank() }
    },
    /**
     * Whether terminal/runtime diagnostics are shown in the active view.
     *
     * Captured from the real navigation state when this destination is opened: true
     * when the user came from the Terminal page, false otherwise. It only filters what
     * is rendered — the stored log (and [fullLog]) always keeps every line — so leaving
     * the Terminal page never deletes history. Defaults to true so a caller that does
     * not supply the navigation context keeps the previous behaviour.
     */
    private val showTerminalLogs: Boolean = true,
) : ViewModel() {

    val lines: StateFlow<List<String>> = DeveloperLogger.lines

    var filter by mutableStateOf(DeveloperLogFilter.ALL)
        private set

    var query by mutableStateOf("")
        private set

    var autoScroll by mutableStateOf(true)
        private set

    fun updateFilter(value: DeveloperLogFilter) {
        filter = value
    }

    fun updateQuery(value: String) {
        query = value
    }

    fun toggleAutoScroll() {
        autoScroll = !autoScroll
    }

    fun displayed(raw: List<String>) = visibleDeveloperLogs(
        raw = raw,
        filter = filter,
        query = query,
        limit = DEVELOPER_LOGS_UI_LIMIT,
        showTerminalLogs = showTerminalLogs,
    )

    fun fullLog(): String = DeveloperLogger.readAll()

    fun clear() {
        DeveloperLogger.clear()
    }

    fun captureSnapshot() {
        DeveloperLogger.captureSnapshot(buildSnapshot().render())
    }

    /**
     * Records where the app's private storage actually went, as a `STORAGE` log entry.
     *
     * Deliberately not folded into [captureSnapshot]: this walks the runtime tree — tens of
     * thousands of files for an installed rootfs — so it runs off the main thread and is an action
     * the developer asks for, rather than a cost every snapshot pays. With no developer runtime
     * wired in there is nothing to measure and nothing is logged.
     */
    fun captureStorageAudit() {
        val runtime = developerRuntime ?: return
        viewModelScope.launch(Dispatchers.IO) { runtime.logStorageBreakdown() }
    }

    fun buildSnapshot(): DeveloperRuntimeSnapshot {
        val logs = lines.value
        val layout = developerRuntime?.layout
        val session = terminalRuntime?.sessions?.active()
            ?: terminalRuntime?.sessions?.sessions()?.lastOrNull()
        val nativeDir = layout?.nativeLibraryDir
        return DeveloperRuntimeSnapshot(
            appVersion = appVersion,
            androidVersion = androidVersion(),
            deviceAbi = deviceAbi(),
            nativeLibraryDir = nativeDir
                ?: lastLogValue(logs, DeveloperLogCategory.PROOT, "applicationInfo.nativeLibraryDir = "),
            rootfsPath = layout?.rootfs
                ?: lastLogValue(logs, DeveloperLogCategory.ROOTFS, "RootFS path = "),
            rootfsExists = layout?.let { File(it.rootfs).exists() },
            rootfsBash = layout?.let { File(it.guestShell).isFile },
            rootfsOsRelease = layout?.let { File(it.guestOsRelease).isFile },
            libproot = existsIn(nativeDir, NativeRuntimeLayout.PROOT_LIBRARY),
            libprootLoader = existsIn(nativeDir, NativeRuntimeLayout.LOADER_LIBRARY),
            libtalloc = existsIn(nativeDir, NativeRuntimeLayout.TALLOC_LIBRARY),
            libandroidShmem = existsIn(nativeDir, NativeRuntimeLayout.SHMEM_LIBRARY),
            sessionState = session?.state?.name,
            sessionHandle = session?.handle,
            sessionPid = (lastLogValue(logs, DeveloperLogCategory.PROCESS, "PID=")
                ?: lastLogValue(logs, DeveloperLogCategory.PROCESS, "PID = "))
                ?.substringBefore(' ')
                ?.ifBlank { null },
            sessionExitCode = session?.takeIf {
                it.state == TerminalSessionState.STOPPED || it.state == TerminalSessionState.FAILED
            }?.exitStatus?.toString(),
            processExecutable = session?.executable
                ?: lastLogValue(logs, DeveloperLogCategory.PROCESS, "executable = "),
            processArguments = lastLogValue(logs, DeveloperLogCategory.PROCESS, "arguments = "),
            processWorkingDirectory = session?.workingDirectory
                ?: lastLogValue(logs, DeveloperLogCategory.PROCESS, "working directory = "),
            envHome = envValue(logs, "HOME"),
            envPath = envValue(logs, "PATH"),
            envShell = envValue(logs, "SHELL"),
            envTerm = envValue(logs, "TERM"),
            envProotLoader = envValue(logs, "PROOT_LOADER"),
            envProotLoader32 = envValue(logs, "PROOT_LOADER32"),
            envProotL2sDir = envValue(logs, "PROOT_L2S_DIR"),
        )
    }

    private fun existsIn(directory: String?, name: String): Boolean? {
        if (directory.isNullOrBlank()) return null
        return File("$directory/$name").isFile
    }

    private fun envValue(logs: List<String>, name: String): String? {
        val raw = lastLogValue(logs, DeveloperLogCategory.ENV, "$name=") ?: return null
        return if (TermuxEnvironment.looksSecret(name)) "<redacted>" else raw
    }
}
