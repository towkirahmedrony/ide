package com.agentx.app.ubuntu

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import com.agentx.app.termux.TermuxShellSpec
import kotlinx.coroutines.CompletableDeferred
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

    private fun nativeExists(path: String): Boolean = File(path).isFile

    private fun nativeExecutable(path: String): Boolean = File(path).let { it.isFile && it.canExecute() }

    private val installer = UbuntuRootfsInstaller(
        layout = layout,
        supportedAbis = android.os.Build.SUPPORTED_ABIS?.toList().orEmpty(),
        dnsServers = ::activeDnsServers,
    )

    /** Copies a SAF project into app storage so it can be bind-mounted as `/workspace/project`. */
    private val materializer = UbuntuWorkspaceMaterializer(appContext, layout)

    /** Runs the installed rootfs through PRoot before it may be called READY. */
    private val verifier = UbuntuRuntimeVerifier(layout)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val statusFlow = MutableStateFlow(initialStatus())

    /** The single source of truth for the runtime's lifecycle state. */
    val status: StateFlow<RuntimeStatus> = statusFlow.asStateFlow()

    /**
     * True when the rootfs is installed, the native PRoot components are present *and* the rootfs
     * has been run through PRoot successfully. The verification marker is what stops a rootfs
     * that extracted but cannot execute a shell from being offered as a working terminal.
     */
    fun isReady(): Boolean =
        installer.isInstalled() && nativeProbe.ready && File(layout.verificationMarker).isFile

    /** True when the rootfs itself is not on disk yet and must be downloaded. */
    fun needsInstall(): Boolean = !installer.isInstalled() && !installer.hasExtractedRootfs()

    /** Re-reads install state, e.g. after returning to the screen. */
    fun refresh() {
        statusFlow.value = when {
            !nativeProbe.ready -> RuntimeStatus(
                state = AgentxRuntimeState.ERROR,
                stage = UbuntuInstallStage.RUNTIME,
                message = nativeProbe.summary,
            )
            isReady() -> RuntimeStatus.Ready
            statusFlow.value.isBusy -> statusFlow.value
            installer.isInstalled() || installer.hasExtractedRootfs() -> unverifiedStatus()
            else -> RuntimeStatus.NotInstalled
        }
    }

    /**
     * Downloads, verifies, extracts and then *runs* the rootfs when it is missing or unverified.
     *
     * The returned signal completes with the status this attempt settled on. It exists because
     * the status flow alone cannot say whether an `ERROR` the caller can see is this attempt's or
     * the previous one's: a failed attempt and a retry that fails identically produce equal
     * values. A caller therefore waits on the signal rather than guessing from [status].
     *
     * Idempotent: a second call while one is running joins the attempt already in flight, and an
     * already verified runtime completes immediately. The legacy bootstrap is never consulted
     * here, whatever its state.
     */
    fun provision(): CompletableDeferred<RuntimeStatus> {
        synchronized(attemptLock) {
            inFlight?.let { running -> return running }
            val signal = CompletableDeferred<RuntimeStatus>()
            inFlight = signal
            scope.launch {
                val settled = try {
                    val native = withContext(ioDispatcher) { selfTestNativeRuntime() }
                    if (!native.ok) {
                        statusFlow.value = RuntimeStatus(
                            state = AgentxRuntimeState.ERROR,
                            stage = UbuntuInstallStage.RUNTIME,
                            message = native.summary,
                        )
                        statusFlow.value
                    } else {
                        val result = withContext(ioDispatcher) {
                            installer.provision { state -> statusFlow.value = state }
                        }
                        when (result) {
                            is UbuntuInstallResult.Unavailable -> Log.w(TAG, result.reason)
                            is UbuntuInstallResult.Failed -> Log.w(TAG, "[${result.stage.wireName}] ${result.message}")
                            is UbuntuInstallResult.AlreadyInstalled, is UbuntuInstallResult.Installed -> Unit
                        }
                        val installed = result is UbuntuInstallResult.AlreadyInstalled ||
                            result is UbuntuInstallResult.Installed
                        if (installed) verifyRootfs()
                        statusFlow.value
                    }
                } catch (failure: Throwable) {
                    val status = RuntimeStatus(
                        state = AgentxRuntimeState.ERROR,
                        stage = UbuntuInstallStage.RUNTIME,
                        message = failure.message ?: failure.javaClass.simpleName,
                    )
                    statusFlow.value = status
                    status
                } finally {
                    synchronized(attemptLock) { inFlight = null }
                }
                // The caller is released as soon as the runtime is usable; the toolchain is a
                // long apt run that must not delay the first shell.
                signal.complete(settled)
                if (settled.state == AgentxRuntimeState.READY) ensureToolchain()
            }
            return signal
        }
    }

    /**
     * Installs the developer toolchain once, with the guest's own `apt-get`.
     *
     * Never fatal: the shell is already usable and a failure (no network, a repository hiccup)
     * is logged and retried by the next provisioning attempt, because the marker is only written
     * on success. The Ubuntu guest installs Ubuntu packages into itself; no Termux package, no
     * Termux repository and no legacy bootstrap is involved.
     */
    private suspend fun ensureToolchain() {
        if (File(layout.toolchainMarker).isFile) return
        val result = withContext(ioDispatcher) { installToolchain() }
        if (result.success) {
            val marker = File(layout.toolchainMarker)
            marker.parentFile?.mkdirs()
            marker.writeText("ok\n")
            Log.i(TAG, "Developer toolchain installed with apt-get")
        } else {
            Log.w(TAG, "Toolchain install did not complete (exit ${result.exitCode}): ${result.stderr.take(300)}")
        }
    }

    /**
     * Runs the guest probes and only then writes the verification marker.
     *
     * This is the step that turns "the files are on disk" into "a real Ubuntu shell answered".
     * A failure here leaves the runtime in ERROR with the probe that failed, and the rootfs is
     * re-verified (not re-downloaded) on the next attempt.
     */
    private suspend fun verifyRootfs() {
        statusFlow.value = RuntimeStatus(AgentxRuntimeState.VALIDATING)
        val verification = withContext(ioDispatcher) { verifier.verify() }
        if (verification.ok) {
            markVerified()
            installer.writeInstallMarker()
            statusFlow.value = RuntimeStatus.Ready
            Log.i(TAG, verification.summary)
        } else {
            clearVerified()
            installer.clearInstallMarker()
            statusFlow.value = RuntimeStatus(
                state = AgentxRuntimeState.ERROR,
                stage = UbuntuInstallStage.RUNTIME,
                message = verification.failure,
            )
            Log.w(TAG, verification.failure.orEmpty())
        }
    }

    private fun markVerified() {
        val marker = File(layout.verificationMarker)
        marker.parentFile?.mkdirs()
        marker.writeText("ok\n${UbuntuEnvironment.RUNTIME_MARKER}\n")
    }

    private fun clearVerified() {
        runCatching { File(layout.verificationMarker).delete() }
    }

    private val attemptLock = Any()

    /** The attempt in flight, if any, so concurrent callers join it instead of racing it. */
    @Volatile
    private var inFlight: CompletableDeferred<RuntimeStatus>? = null

    private fun initialStatus(): RuntimeStatus = when {
        !nativeProbe.ready -> RuntimeStatus(
            state = AgentxRuntimeState.ERROR,
            stage = UbuntuInstallStage.RUNTIME,
            message = nativeProbe.summary,
        )
        isReady() -> RuntimeStatus.Ready
        installer.isInstalled() || installer.hasExtractedRootfs() -> unverifiedStatus()
        else -> RuntimeStatus.NotInstalled
    }

    /**
     * Proves PRoot and its loader exist in [NativeRuntimeLayout.nativeLibraryDir] and can start.
     *
     * This is the gate in front of Ubuntu download/extraction. A missing APK native library is
     * never treated as a missing rootfs.
     */
    private fun selfTestNativeRuntime(): ProotSelfTestResult {
        installer.ensureRuntimeDirectories()
        Log.i(TAG, "nativeLibraryDir=${layout.nativeLibraryDir}")
        Log.i(TAG, "PROOT_LOADER=${layout.loader}")
        val listing = File(layout.nativeLibraryDir).listFiles()?.joinToString { it.name } ?: "(unreadable)"
        Log.i(TAG, "nativeLibraryDir contents: $listing")
        val result = ProotSelfTest.run(
            layout = layout,
            exists = ::nativeExists,
            canExecute = ::nativeExecutable,
            starter = ::runHostProot,
        )
        if (result.ok) {
            Log.i(TAG, result.summary)
            result.versionOutput?.let { Log.i(TAG, "PRoot -V: ${it.take(300)}") }
        } else {
            Log.w(TAG, result.summary)
        }
        return result
    }

    private fun runHostProot(invocation: ProotInvocation): Pair<Int, String> {
        val builder = ProcessBuilder(invocation.processCommand).redirectErrorStream(true)
        for ((name, value) in invocation.environment) {
            builder.environment()[name] = value
        }
        val process = builder.start()
        val output = process.inputStream.bufferedReader().readText()
        val exited = process.waitFor(15, TimeUnit.SECONDS)
        if (!exited) {
            process.destroyForcibly()
            process.waitFor(2, TimeUnit.SECONDS)
            return -1 to output
        }
        return process.exitValue() to output
    }

    private fun unverifiedStatus(): RuntimeStatus {
        if (!nativeProbe.ready) {
            return RuntimeStatus(
                state = AgentxRuntimeState.ERROR,
                stage = UbuntuInstallStage.RUNTIME,
                message = nativeProbe.summary,
            )
        }
        return RuntimeStatus(
            state = AgentxRuntimeState.ERROR,
            stage = UbuntuInstallStage.RUNTIME,
            message = "The Ubuntu rootfs is installed but has not passed its PRoot verification. " +
                "Install the runtime again to verify it; nothing is downloaded the second time.",
        )
    }

    /**
     * Prepares a project for the guest.
     *
     * A real directory is bound as-is; a `content://` tree is copied into app storage first, so
     * PRoot is never handed a URI it cannot mount. Blocking: call it off the main thread.
     */
    fun materializeProject(handle: String?, workspaceId: String): UbuntuWorkspaceMaterialization =
        materializer.materialize(handle, workspaceId)

    /**
     * Installs the developer toolchain with the guest's own `apt-get`.
     *
     * The packages come from Ubuntu's own `ubuntu-ports` archive
     * ([UbuntuRootfsCatalog.TOOLCHAIN_PACKAGES]): git, gh, python3/pip, nodejs/npm, curl, wget,
     * ca-certificates, openssh-client and ripgrep. There is no Termux package repository and no
     * Termux package is installed — this is the Ubuntu userland installing into itself.
     */
    fun installToolchain(timeoutSeconds: Long = TOOLCHAIN_TIMEOUT_SECONDS): AgentxCommandResult {
        if (!isReady()) {
            return AgentxCommandResult(
                exitCode = -1,
                stdout = "",
                stderr = "The Ubuntu runtime is not ready, so no toolchain can be installed.",
            )
        }
        val packages = UbuntuRootfsCatalog.TOOLCHAIN_PACKAGES.joinToString(" ")
        val command = buildString {
            append("export DEBIAN_FRONTEND=noninteractive; ") 
            append("apt-get update && apt-get install -y --no-install-recommends ")
            append(packages)
        }
        return executeBlocking(command = command, timeoutSeconds = timeoutSeconds)
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
        /** `apt-get install` of the toolchain is slower than a single command. */
        const val TOOLCHAIN_TIMEOUT_SECONDS: Long = 1800L

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
