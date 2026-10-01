package com.agentx.app.ubuntu

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import com.agentx.app.termux.TermuxShellSpec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The primary AgentX execution backend: an embedded Ubuntu ARM64 developer runtime.
 *
 * It owns the native-layout probe, the rootfs installer and the runtime [status] flow, and it
 * turns a request into either a terminal spec or a live process:
 *
 * ```
 * AgentX → nativeLibraryDir/libproot.so → PROOT_LOADER → Ubuntu guest ELF → bash/git/python/node
 * ```
 *
 * Only [NativeRuntimeLayout.proot] is ever executed directly by Android. Guest binaries are
 * addressed through PRoot's loader, never `execve`d from app-private storage.
 *
 * One instance per process (the terminal's sessions outlive the Activity), so a rotation finds
 * the same runtime rather than a second one.
 */
class LocalUbuntuRuntime(
    context: Context,
    private val defaultColumns: Int = DEFAULT_COLUMNS,
    private val defaultRows: Int = DEFAULT_ROWS,
    private val transcriptRows: Int = DEFAULT_TRANSCRIPT_ROWS,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val appContext = context.applicationContext

    /** Native library directory and app-private runtime storage. */
    val layout: NativeRuntimeLayout = NativeRuntimeLayout.forContext(
        nativeLibraryDir = appContext.applicationInfo.nativeLibraryDir,
        filesDir = appContext.filesDir.absolutePath,
    )

    /** Whether the native PRoot components are actually present in `nativeLibraryDir`. */
    val nativeProbe: NativeRuntimeProbe = NativeRuntimeProbe.probe(
        layout = layout,
        exists = { path -> File(path).let { it.isFile && it.canExecute() } },
    )

    private val installer = UbuntuRootfsInstaller(
        layout = layout,
        supportedAbis = android.os.Build.SUPPORTED_ABIS?.toList().orEmpty(),
        dnsServers = ::activeDnsServers,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val statusFlow = MutableStateFlow(
        if (installer.isInstalled()) RuntimeStatus.Ready else RuntimeStatus.NotInstalled,
    )

    /** The single source of truth for the runtime's lifecycle state. */
    val status: StateFlow<RuntimeStatus> = statusFlow.asStateFlow()

    /** True when the rootfs is installed and the native components are present. */
    fun isReady(): Boolean = installer.isInstalled() && nativeProbe.ready

    /** True when a first-run install is required (native files present, rootfs missing). */
    fun needsInstall(): Boolean = !installer.isInstalled()

    /** Re-reads install state, e.g. after returning to the screen. */
    fun refresh() {
        statusFlow.value = when {
            installer.isInstalled() -> RuntimeStatus.Ready
            statusFlow.value.isBusy -> statusFlow.value
            else -> RuntimeStatus.NotInstalled
        }
    }

    /**
     * Downloads, verifies and installs the rootfs when missing. Idempotent: a second call while
     * one is running is ignored, and an installed runtime returns immediately.
     */
    fun provision() {
        if (statusFlow.value.isBusy) return
        scope.launch {
            val result = withContext(ioDispatcher) {
                installer.provision { state -> statusFlow.value = state }
            }
            when (result) {
                is UbuntuInstallResult.AlreadyInstalled -> statusFlow.value = RuntimeStatus.Ready
                is UbuntuInstallResult.Installed -> statusFlow.value = RuntimeStatus.Ready
                is UbuntuInstallResult.Unavailable -> Log.w(TAG, result.reason)
                is UbuntuInstallResult.Failed -> Log.w(TAG, "[${result.stage.wireName}] ${result.message}")
            }
        }
    }

    /**
     * The terminal spec for a workspace, or null when the runtime cannot start yet.
     *
     * The returned spec is a normal `TermuxShellSpec`: the existing vendored PTY/session
     * machinery runs it unchanged, except that the executable is PRoot and the arguments are
     * the guest command. Nothing about the terminal emulator changes.
     */
    fun specFor(
        workspaceKey: String,
        projectHostPath: String?,
        displayLocation: String?,
        extraEnvironment: Map<String, String> = emptyMap(),
    ): TermuxShellSpec? {
        if (!isReady()) return null
        val binding = UbuntuProjectBindings.resolve(
            handle = projectHostPath,
            displayLocation = displayLocation ?: projectHostPath,
            isDirectory = { path -> File(path).let { it.isDirectory && it.canRead() } },
        )
        return specForBinding(workspaceKey, binding, extraEnvironment)
    }

    /** The terminal spec for an already-resolved project binding. */
    fun specForBinding(
        workspaceKey: String,
        binding: UbuntuProjectBinding,
        extraEnvironment: Map<String, String> = emptyMap(),
    ): TermuxShellSpec {
        check(nativeProbe.ready) { nativeProbe.summary }

        val invocation = invocationFor(
            binding = binding,
            guestCommand = ProotCommand.LOGIN_SHELL,
            extraEnvironment = extraEnvironment,
        )
        val hostWorkingDirectory = (binding as? UbuntuProjectBinding.Direct)?.hostPath
            ?.takeIf { File(it).isDirectory }
            ?: layout.runtimeDir

        return TermuxShellSpec(
            workspaceKey = workspaceKey,
            executable = invocation.executable,
            processName = "proot",
            arguments = invocation.arguments,
            workingDirectory = hostWorkingDirectory,
            environment = mergeEnvironment(invocation, binding, extraEnvironment),
            transcriptRows = transcriptRows,
            temporarySystemShell = false,
            fullTermux = false,
        )
    }

    /** Starts a guest process directly (no pty) for one-shot or long-running command execution. */
    fun execute(
        command: List<String>,
        projectHostPath: String? = null,
        workingDirectory: String? = null,
        environment: Map<String, String> = emptyMap(),
    ): AgentxExecution {
        check(nativeProbe.ready) { nativeProbe.summary }
        val binding = UbuntuProjectBindings.resolve(
            handle = projectHostPath,
            displayLocation = projectHostPath,
            isDirectory = { path -> File(path).let { it.isDirectory && it.canRead() } },
        )
        val guestCwd = workingDirectory ?: binding.guestPath
        val invocation = invocationFor(binding = binding, guestCommand = command, extraEnvironment = environment)
        val hostCwd = (binding as? UbuntuProjectBinding.Direct)?.hostPath
            ?.takeIf { File(it).isDirectory }
            ?: layout.runtimeDir

        val builder = ProcessBuilder(invocation.processCommand)
            .directory(File(hostCwd))
            .redirectErrorStream(false)
        builder.environment().putAll(invocation.environment)
        for (entry in UbuntuEnvironment.build(
            projectGuestPath = binding.hostPath?.let { ProotCommand.GUEST_PROJECT_ROOT },
            androidEnv = System.getenv(),
            extra = environment,
        )) {
            val separator = entry.indexOf('=')
            if (separator > 0) builder.environment()[entry.substring(0, separator)] = entry.substring(separator + 1)
        }
        return ProcessAgentxExecution(command, guestCwd, builder.start())
    }

    /**
     * Runs a shell command to completion inside the guest and returns its captured output.
     *
     * Intended for the future agents' one-shot commands (`git status`, `python3 --version`).
     * Long-running commands should use [execute] so they are not tied to a caller's lifetime.
     */
    fun executeBlocking(
        command: String,
        projectHostPath: String? = null,
        workingDirectory: String? = null,
        environment: Map<String, String> = emptyMap(),
        timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
    ): AgentxCommandResult {
        val execution = execute(
            command = listOf("/bin/bash", "-lc", command),
            projectHostPath = projectHostPath,
            workingDirectory = workingDirectory,
            environment = environment,
        )
        val stdoutReader = execution.stdout.bufferedReader()
        val stderrReader = execution.stderr.bufferedReader()
        // Drain both streams concurrently so a command that fills the stderr buffer cannot
        // deadlock against its own stdout.
        val stdoutText = StringBuilder()
        val stderrText = StringBuilder()
        val stdoutThread = Thread { stdoutReader.forEachLine { stdoutText.appendLine(it) } }
            .apply { isDaemon = true; start() }
        val stderrThread = Thread { stderrReader.forEachLine { stderrText.appendLine(it) } }
            .apply { isDaemon = true; start() }

        val exited = execution.process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!exited) {
            execution.cancel()
        }
        stdoutThread.join(2_000)
        stderrThread.join(2_000)

        return AgentxCommandResult(
            exitCode = if (exited) execution.process.exitValue() else -1,
            stdout = stdoutText.toString(),
            stderr = stderrText.toString(),
            timedOut = !exited,
        )
    }

    /** Ends the runtime: cancels the provisioning scope. Sessions belong to the terminal layer. */
    fun release() {
        scope.cancel()
        synchronized(LOCK) {
            if (instance === this) instance = null
        }
    }

    private fun invocationFor(
        binding: UbuntuProjectBinding,
        guestCommand: List<String>,
        extraEnvironment: Map<String, String>,
    ): ProotInvocation {
        val binds = ProotCommand.withProject(
            binds = ProotCommand.infrastructureBinds(
                layout = layout,
                resolvConf = layout.resolvConf.takeIf { File(it).isFile },
            ),
            projectHostPath = binding.hostPath,
        )
        return ProotCommand.build(
            layout = layout,
            guestWorkingDirectory = binding.guestPath,
            binds = binds,
            guestCommand = guestCommand,
        )
    }

    private fun mergeEnvironment(
        invocation: ProotInvocation,
        binding: UbuntuProjectBinding,
        extra: Map<String, String>,
    ): Array<String> {
        val merged = LinkedHashMap<String, String>()
        merged.putAll(invocation.environment)
        for (entry in UbuntuEnvironment.build(
            projectGuestPath = binding.hostPath?.let { ProotCommand.GUEST_PROJECT_ROOT },
            androidEnv = System.getenv(),
            extra = extra,
        )) {
            val separator = entry.indexOf('=')
            if (separator > 0) merged[entry.substring(0, separator)] = entry.substring(separator + 1)
        }
        return merged.map { (name, value) -> "$name=$value" }.toTypedArray()
    }

    private fun activeDnsServers(): List<String> = try {
        val manager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = manager?.activeNetwork ?: return emptyList()
        manager.getLinkProperties(network)?.dnsServers?.mapNotNull { it.hostAddress } ?: emptyList()
    } catch (error: Exception) {
        Log.w(TAG, "Could not read active DNS servers", error)
        emptyList()
    }

    companion object {
        const val TAG: String = "LocalUbuntuRuntime"
        const val DEFAULT_COLUMNS: Int = 80
        const val DEFAULT_ROWS: Int = 24
        const val DEFAULT_TRANSCRIPT_ROWS: Int = 2000
        const val DEFAULT_TIMEOUT_SECONDS: Long = 120L

        private val LOCK = Any()

        @Volatile
        private var instance: LocalUbuntuRuntime? = null

        /** The runtime for this process; survives Activity recreation like the terminal does. */
        fun get(context: Context): LocalUbuntuRuntime =
            instance ?: synchronized(LOCK) {
                instance ?: LocalUbuntuRuntime(context).also { instance = it }
            }
    }
}
