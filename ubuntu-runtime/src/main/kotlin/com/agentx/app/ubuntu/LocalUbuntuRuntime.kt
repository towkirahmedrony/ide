package com.agentx.app.ubuntu

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import com.agentx.app.termux.DeveloperLogCategory
import com.agentx.app.termux.DeveloperLogger
import com.agentx.app.termux.TerminalDiagnostics
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
        filesDir = appContext.filesDir.canonicalPath,
    )

    /** Whether the native PRoot components are actually present in `nativeLibraryDir`. */
    val nativeProbe: NativeRuntimeProbe = NativeRuntimeProbe.probe(
        layout = layout,
        exists = { path -> File(path).let { it.isFile && it.canExecute() } },
    )

    init {
        DeveloperLogger.attach(File(appContext.filesDir, DeveloperLogger.RELATIVE_PATH))
        UbuntuDeveloperDiagnostics.logNativeRuntime(
            layout = layout,
            abis = android.os.Build.SUPPORTED_ABIS?.toList().orEmpty(),
        )
        UbuntuDeveloperDiagnostics.logRootfs(layout)
    }

    private fun nativeExists(path: String): Boolean = File(path).isFile

    private fun nativeExecutable(path: String): Boolean = File(path).let { it.isFile && it.canExecute() }

    private val installer = UbuntuRootfsInstaller(
        layout = layout,
        supportedAbis = android.os.Build.SUPPORTED_ABIS?.toList().orEmpty(),
        dnsServers = ::activeDnsServers,
    )

    /**
     * Optional SAF materialiser: copies a `content://` tree into app storage.
     *
     * Not part of the terminal's path — the terminal resolves a SAF folder to its original
     * phone-storage path and binds it in place at `/workspace`. Kept for callers that explicitly
     * want a private copy.
     */
    private val materializer = UbuntuWorkspaceMaterializer(appContext, layout)

    /**
     * Runs a tree through PRoot before it may be called READY.
     *
     * Built per tree rather than once, because an installation is verified *before* it is
     * promoted: during a fresh install the tree under test is
     * [NativeRuntimeLayout.forInstalling], and on a runtime that is already in place it is the
     * live rootfs. Verifying the wrong one would be worse than not verifying at all.
     */
    private fun verifierFor(tree: NativeRuntimeLayout) = UbuntuRuntimeVerifier(tree)

    /**
     * Installs and then verifies the developer packages inside the guest.
     *
     * The runner reaches the guest the same way the terminal does — through PRoot, as
     * `/bin/bash` inside the rootfs — so a check that passes here is a check that passes for the
     * user. `resolv.conf` is read lazily: it is written by the installer's configure step, which
     * runs after this object is built.
     */
    private fun toolchainFor(tree: NativeRuntimeLayout) = UbuntuToolchain(
        layout = tree,
        runner = ubuntuGuestCommandRunner(
            layout = tree,
            hostWorkingDirectory = tree.runtimeDir,
            resolvConf = { tree.resolvConf.takeIf { File(it).isFile } },
        ),
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val statusFlow = MutableStateFlow(initialStatus())

    /** The single source of truth for the runtime's lifecycle state. */
    val status: StateFlow<RuntimeStatus> = statusFlow.asStateFlow()

    /**
     * True when the native PRoot components are present, the rootfs is installed, it has answered
     * the PRoot guest probes, *and* the developer toolchain is installed and verified.
     *
     * Every one of those is a marker on disk rather than a state this object remembers, so it
     * survives a process restart and cannot be optimistic. The toolchain marker is the last gate
     * and the one that matters to a user: a shell without git, python3 or node is not the
     * developer runtime this is supposed to be, and a package installation that failed — or was
     * interrupted halfway — leaves it absent, which keeps [isReady] false and stops any terminal
     * from claiming otherwise.
     */
    fun isReady(): Boolean =
        installer.isInstalled() &&
            nativeProbe.ready &&
            File(layout.verificationMarker).isFile &&
            File(layout.toolchainMarker).isFile

    /** True when the rootfs itself is not on disk yet and must be downloaded. */
    fun needsInstall(): Boolean = !installer.isInstalled() && !installer.hasExtractedRootfs()

    /**
     * Why the developer terminal cannot start, naming every gate rather than summarising.
     *
     * Shown in place of a shell, so it has to be actionable: it reports each condition separately —
     * where the rootfs is, whether it was extracted, whether the guest shell and `/etc/os-release`
     * are actually in it, whether the install marker and the verification marker were written, and
     * which native libraries are missing — because "the terminal did not start" is not something
     * anyone can act on. The last recorded runtime message is appended when there is one, which is
     * where a failed guest probe states its own reason.
     */
    fun notReadyReason(): String {
        val rootfs = File(layout.rootfs)
        val guestShell = File(layout.guestShell)
        val osRelease = File(layout.guestOsRelease)
        val installed = installer.isInstalled()
        val native = nativeProbe.ready
        val verified = File(layout.verificationMarker).isFile
        val toolchain = File(layout.toolchainMarker).isFile
        val recreate = File(layout.recreateMarker).isFile
        val status = statusFlow.value

        return buildString {
            append("The Ubuntu runtime is not ready, so no shell was started.")
            append(" rootfs=${layout.rootfs}")
            append(" rootfsExtracted=${rootfs.isDirectory}")
            append(" bin/bash=${guestShell.isFile}")
            append(" etc/os-release=${osRelease.isFile}")
            append(" installMarker=$installed")
            append(" nativeLibraries=$native")
            if (!native) append(" missing=${nativeProbe.missing.joinToString(",")}")
            append(" guestVerified=$verified")
            append(" toolchainInstalled=$toolchain")
            if (recreate) append(" rootfsRecreatePending=true")
            append(" proot=${File(layout.proot).isFile}")
            append(" lastStatus=${status.state}")
            status.message?.takeIf { it.isNotBlank() }?.let { append(" message=$it") }
        }
    }

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
     * Takes the runtime all the way to READY: download, verify, extract, run the rootfs through
     * PRoot, sanity-check `apt`/`dpkg`, install the developer packages, verify every required
     * executable. See [provisionBlocking].
     *
     * The returned signal completes with the status this attempt settled on. It exists because
     * the status flow alone cannot say whether an `ERROR` the caller can see is this attempt's or
     * the previous one's: a failed attempt and a retry that fails identically produce equal
     * values. A caller therefore waits on the signal rather than guessing from [status].
     *
     * This is a long call — the `apt-get install` is the slowest step by far — and a terminal is
     * only opened against it once it has settled on READY.
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
                    withContext(ioDispatcher) { provisionBlocking() }
                } catch (failure: Throwable) {
                    DeveloperLogger.error(
                        DeveloperLogCategory.ERROR,
                        "runtime provision failed",
                        failure,
                    )
                    fail(failure.message ?: failure.javaClass.simpleName)
                } finally {
                    synchronized(attemptLock) { inFlight = null }
                }
                signal.complete(settled)
            }
            return signal
        }
    }

    /**
     * The install lifecycle, in the order the brief fixes it:
     *
     * ```
     * fresh rootfs → PRoot validation → apt/dpkg sanity check → apt-get update →
     * install the developer packages → verify every required executable → READY
     * ```
     *
     * Nothing here writes a marker for a step that did not finish, and READY is the last thing
     * that happens — so the terminal is opened against a runtime whose tools actually answer.
     *
     * A tree the guest declares damaged — a `dpkg` database in a mess, a half-applied unpack, or
     * a toolchain that installs but does not verify — is thrown away and rebuilt from the archive
     * that is already on disk. That is the recovery path for "apt failed partway": the tree is
     * not repaired in place, because `dpkg` will happily report `install ok installed` for a
     * package whose files never arrived (measured, not assumed). The rebuild is bounded to one
     * attempt, so a persistent cause (no network, a repository outage) reports instead of looping.
     */
    private fun provisionBlocking(): RuntimeStatus {
        // An already verified runtime completes immediately, as documented: every gate is a
        // marker on disk, so there is nothing to re-derive and nothing to download.
        if (isReady()) {
            statusFlow.value = RuntimeStatus.Ready
            return statusFlow.value
        }

        val native = selfTestNativeRuntime()
        if (!native.ok) return fail(native.summary)

        var recreated = false
        while (true) {
            // 1. Make sure a complete, configured Ubuntu tree exists. A fresh one is assembled in
            //    rootfs.installing; an existing one is reused only if the filesystem passes the
            //    same authoritative check (see UbuntuRootfsInstaller.isConfiguredRootfs).
            val prepared = installer.provision { state -> statusFlow.value = state }
            when (prepared) {
                is UbuntuInstallResult.Unavailable -> {
                    Log.w(TAG, prepared.reason)
                    return statusFlow.value
                }
                is UbuntuInstallResult.Failed -> {
                    Log.w(TAG, "[${prepared.stage.wireName}] ${prepared.message}")
                    return statusFlow.value
                }
                is UbuntuInstallResult.AlreadyInstalled, is UbuntuInstallResult.Prepared -> Unit
            }
            // The tree this attempt is working on. Everything below — PRoot verification, the
            // package install — runs against *this* tree, and it is promoted only when all of it
            // has passed.
            val fresh = prepared is UbuntuInstallResult.Prepared
            val tree = if (fresh) layout.forInstalling() else layout
            DeveloperLogger.info(
                DeveloperLogCategory.ROOTFS,
                if (fresh) {
                    "RootFS extracted into ${tree.rootfs}; verifying before promotion"
                } else {
                    "RootFS already present and complete at ${tree.rootfs}; verifying"
                },
            )

            // 2. Run the tree through PRoot. Nothing is promoted or marked on the strength of the
            //    files being on disk.
            if (!verifyTree(tree)) {
                installer.clearInstallMarkers()
                if (fresh) {
                    installer.deleteInstallingTree()
                } else {
                    // A tree that is in place but cannot run is not reusable. Recording it means
                    // the next attempt re-extracts instead of failing the same way again.
                    installer.discardRootfs("PRoot guest verification failed: ${statusFlow.value.message}")
                }
                return statusFlow.value
            }

            // 3. The developer toolchain, inside the same tree.
            DeveloperLogger.info(
                DeveloperLogCategory.PROOT,
                "Toolchain installation started: tree=${tree.rootfs}",
            )
            when (val outcome = toolchainFor(tree).provision()) {
                is UbuntuToolchainOutcome.Ready -> {
                    // 4. Promote, then persist READY. In this order: a marker must never describe
                    //    a tree that is still under construction.
                    if (fresh && !installer.promote()) {
                        installer.deleteInstallingTree()
                        return fail(
                            "The Ubuntu rootfs was built and verified but could not be moved " +
                                "into place.",
                            UbuntuInstallStage.ACTIVATION,
                        )
                    }
                    installer.writeVerificationMarker()
                    installer.writeInstallMarker()
                    installer.writeToolchainMarker(outcome.verified)
                    DeveloperLogger.info(
                        DeveloperLogCategory.ROOTFS,
                        "Runtime READY: ${layout.rootfs} promoted and verified",
                    )
                    DeveloperLogger.info(
                        DeveloperLogCategory.PROOT,
                        "Toolchain validation passed: ${outcome.verified.joinToString()}",
                    )
                    DeveloperLogger.info(
                        DeveloperLogCategory.ENV,
                        "Developer toolchain verified: ${outcome.verified.joinToString()}",
                    )
                    Log.i(TAG, "Developer toolchain installed and verified with apt-get")
                    DeveloperLogger.info(
                        DeveloperLogCategory.ROOTFS,
                        "Runtime READY: markers written (verification, install, toolchain)",
                    )
                    val ready = RuntimeStatus.Ready
                    statusFlow.value = ready
                    return ready
                }

                is UbuntuToolchainOutcome.Failed -> {
                    // Nothing was unpacked, so the tree is still the one that just passed its
                    // PRoot probes. Keep it and report; the next attempt re-runs the install.
                    installer.clearInstallMarkers()
                    if (fresh) installer.deleteInstallingTree()
                    DeveloperLogger.warn(DeveloperLogCategory.ERROR, outcome.message)
                    return fail(outcome.message, outcome.stage)
                }

                is UbuntuToolchainOutcome.Damaged -> {
                    // The tree may be half-modified and there is no way to tell from dpkg's
                    // answers, so it is not repaired in place: it is discarded and rebuilt from
                    // the verified archive, which costs no download.
                    installer.clearInstallMarkers()
                    if (fresh) {
                        installer.deleteInstallingTree()
                    } else {
                        installer.discardRootfs(outcome.reason)
                    }
                    DeveloperLogger.warn(DeveloperLogCategory.ROOTFS, "RootFS recreated: ${outcome.reason}")
                    if (recreated) {
                        return fail(
                            "The developer toolchain could not be installed even after the " +
                                "rootfs was recreated: ${outcome.reason}",
                            UbuntuInstallStage.CONFIGURATION,
                        )
                    }
                    recreated = true
                }
            }
        }
    }

    /**
     * Runs the guest probes against [tree] and reports what the guest answered.
     *
     * This is the step that turns "the files are on disk" into "a real Ubuntu shell answered".
     * The `perl` and `uncompress` probes are the ones that matter most here: they are the
     * archive's hard-link pairs, and they fail when the link-to-symlink store is somewhere the
     * guest cannot follow it — the fault that used to reach the user as an `apt` unpack error
     * instead.
     *
     * No marker is written here. The caller writes them after promotion, so a tree that is still
     * under construction can never be described as usable.
     */
    private fun verifyTree(tree: NativeRuntimeLayout): Boolean {
        statusFlow.value = RuntimeStatus(AgentxRuntimeState.VALIDATING)
        DeveloperLogger.info(
            DeveloperLogCategory.PROOT,
            "PRoot guest validation started: root=${tree.rootfs} l2s=${tree.l2s}",
        )
        val verification = verifierFor(tree).verify()
        TerminalDiagnostics.record(
            TAG,
            "rootfs verification ok=${verification.ok} summary=${verification.summary}",
        )
        DeveloperLogger.info(
            DeveloperLogCategory.ROOTFS,
            "RootFS validation path=${tree.rootfs} ok=${verification.ok} summary=${verification.summary}",
        )
        if (verification.ok) {
            DeveloperLogger.info(
                DeveloperLogCategory.PROOT,
                "PRoot guest validation passed: ${verification.summary}",
            )
            return true
        }
        // Recorded verbatim: this is where an extracted-but-unrunnable rootfs is caught, and the
        // failure text names the probe (`/bin/sh`, `/bin/bash`, `/usr/bin/perl`, …) that failed.
        TerminalDiagnostics.record(TAG, "rootfs verification FAILED: ${verification.failure}")
        clearVerified()
        DeveloperLogger.warn(DeveloperLogCategory.ROOTFS, verification.failure.orEmpty())
        fail(verification.failure, UbuntuInstallStage.RUNTIME)
        return false
    }

    /** Publishes a failure and returns it, so every caller reports through one path. */
    private fun fail(message: String?, stage: UbuntuInstallStage = UbuntuInstallStage.RUNTIME): RuntimeStatus {
        val status = RuntimeStatus(
            state = AgentxRuntimeState.ERROR,
            stage = stage,
            message = message,
        )
        statusFlow.value = status
        Log.w(TAG, message.orEmpty())
        return status
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

        // Recorded at run time, not read from the build script: the question is what the installer
        // actually unpacked and whether it may be executed, not what the APK was built with.
        TerminalDiagnostics.record(TAG, "nativeLibraryDir=${layout.nativeLibraryDir}")
        TerminalDiagnostics.record(TAG, "nativeLibraryDir contents: $listing")
        UbuntuDeveloperDiagnostics.logNativeRuntime(
            layout = layout,
            abis = android.os.Build.SUPPORTED_ABIS?.toList().orEmpty(),
        )
        UbuntuDeveloperDiagnostics.logRootfs(layout)
        DeveloperLogger.info(DeveloperLogCategory.PROOT, "Native library verification")
        for (name in NativeRuntimeLayout.REQUIRED_LIBRARIES + NativeRuntimeLayout.OPTIONAL_LIBRARIES) {
            val file = File("${layout.nativeLibraryDir}/$name")
            TerminalDiagnostics.record(
                TAG,
                "  $name exists=${file.isFile} executable=${file.canExecute()}",
            )
        }
        TerminalDiagnostics.record(
            TAG,
            "PROOT_LOADER=${layout.loader} PROOT_TMP_DIR=${layout.tmp} PROOT_L2S_DIR=${layout.l2s}",
        )
        TerminalDiagnostics.record(TAG, "rootfs=${layout.rootfs} installed=${installer.isInstalled()}")

        val result = ProotSelfTest.run(
            layout = layout,
            exists = ::nativeExists,
            canExecute = ::nativeExecutable,
            starter = ::runHostProot,
        )
        if (result.ok) {
            Log.i(TAG, result.summary)
            DeveloperLogger.info(DeveloperLogCategory.PROOT, "PRoot self-test passed: ${result.summary}")
            TerminalDiagnostics.record(TAG, "PRoot self-test passed: ${result.summary}")
            result.versionOutput?.let {
                TerminalDiagnostics.record(TAG, "PRoot -V: ${it.take(300)}")
                Log.i(TAG, "PRoot -V: ${it.take(300)}")
            }
        } else {
            Log.w(TAG, result.summary)
            // The first gate in the chain. If this fails nothing after it can work, and the
            // summary names the file that is missing or not executable.
            DeveloperLogger.error(DeveloperLogCategory.PROOT, "PRoot self-test FAILED: ${result.summary}")
            TerminalDiagnostics.record(TAG, "PRoot self-test FAILED: ${result.summary}")
        }
        return result
    }

    private fun runHostProot(invocation: ProotInvocation): Pair<Int, String> {
        val builder = ProcessBuilder(invocation.processCommand).redirectErrorStream(true)
        for ((name, value) in invocation.environment) {
            builder.environment()[name] = value
        }
        val process = try {
            builder.start()
        } catch (error: Throwable) {
            DeveloperLogger.error(DeveloperLogCategory.ERROR, "process start failure", error)
            throw error
        }
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
        val verified = File(layout.verificationMarker).isFile
        return RuntimeStatus(
            state = AgentxRuntimeState.ERROR,
            stage = UbuntuInstallStage.RUNTIME,
            message = if (verified) {
                "The Ubuntu rootfs is installed and verified, but its developer toolchain is " +
                    "not. Install the runtime again to finish it; the rootfs is already on disk " +
                    "and is not downloaded again."
            } else {
                "The Ubuntu rootfs is installed but has not passed its PRoot verification. " +
                    "Install the runtime again to verify it; nothing is downloaded the second time."
            },
        )
    }

    /**
     * Prepares an explicit private copy of a project for a caller that needs one.
     *
     * Not how the terminal opens a project: a real directory needs nothing, and a SAF folder is
     * resolved to its original phone-storage path and bound in place at
     * [ProotCommand.GUEST_PROJECT_ROOT] — never copied. This exists only for callers that ask
     * for a copy, so PRoot is never handed a URI it cannot mount. Blocking: call it off the main
     * thread.
     */
    fun materializeProject(handle: String?, workspaceId: String): UbuntuWorkspaceMaterialization =
        materializer.materialize(handle, workspaceId)

    /**
     * Re-runs the developer toolchain against a rootfs that has already passed its PRoot probes.
     *
     * It is the same sequence `provision` runs — sanity check, `apt-get update`, the install,
     * then every required executable — for a caller that wants to retry just the package step
     * (a user who has since connected to a network, say) without a full re-verification. The
     * gating is identical: READY is written only for a fully verified toolchain, and any other
     * outcome clears the markers and, when the tree cannot be trusted, discards it.
     *
     * Blocking: call it off the main thread.
     *
     * The packages come from Ubuntu's own `ubuntu-ports` archive
     * ([UbuntuRootfsCatalog.TOOLCHAIN_PACKAGES]): git, gh, python3/pip, nodejs/npm, curl, wget,
     * ca-certificates, openssh-client, ripgrep and debconf. There is no Termux package repository
     * and no Termux package is installed — this is the Ubuntu userland installing into itself.
     */
    fun installToolchain(): UbuntuToolchainOutcome {
        if (!installer.hasExtractedRootfs() || !File(layout.verificationMarker).isFile) {
            return UbuntuToolchainOutcome.Failed(
                stage = UbuntuInstallStage.RUNTIME,
                message = "The Ubuntu rootfs has not passed its PRoot verification, so no " +
                    "toolchain can be installed into it. Install the runtime first.",
                transient = false,
            )
        }
        return when (val outcome = toolchainFor(layout).provision()) {
            is UbuntuToolchainOutcome.Ready -> {
                installer.writeToolchainMarker(outcome.verified)
                statusFlow.value = RuntimeStatus.Ready
                outcome
            }
            is UbuntuToolchainOutcome.Damaged -> {
                installer.discardRootfs(outcome.reason)
                statusFlow.value = RuntimeStatus(
                    state = AgentxRuntimeState.ERROR,
                    stage = UbuntuInstallStage.CONFIGURATION,
                    message = outcome.reason,
                )
                outcome
            }
            is UbuntuToolchainOutcome.Failed -> {
                installer.clearInstallMarkers()
                statusFlow.value = RuntimeStatus(
                    state = AgentxRuntimeState.ERROR,
                    stage = outcome.stage,
                    message = outcome.message,
                )
                outcome
            }
        }
    }

    /**
     * The terminal spec for a workspace, or null when the runtime cannot start yet.
     *
     * The returned spec is a normal `TermuxShellSpec`: the existing vendored PTY/session
     * machinery runs it unchanged, except that the executable is PRoot and the arguments are
     * the guest command. Nothing about the terminal emulator changes.
     *
     * @param projectHandle the workspace handle: a filesystem path (a project in managed
     *   storage) or a SAF `content://` tree URI. [UbuntuProjectBindings.resolve] derives the
     *   phone-storage path a SAF tree names, so the original folder is bound at
     *   [ProotCommand.GUEST_PROJECT_ROOT] without being copied.
     */
    fun specFor(
        workspaceKey: String,
        projectHandle: String?,
        displayLocation: String?,
        extraEnvironment: Map<String, String> = emptyMap(),
    ): TermuxShellSpec? {
        if (!isReady()) return null
        val binding = UbuntuProjectBindings.resolve(
            handle = projectHandle,
            displayLocation = displayLocation ?: projectHandle,
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

        UbuntuDeveloperDiagnostics.logNativeRuntime(
            layout = layout,
            abis = android.os.Build.SUPPORTED_ABIS?.toList().orEmpty(),
        )
        UbuntuDeveloperDiagnostics.logRootfs(layout)
        DeveloperLogger.info(DeveloperLogCategory.ENV, "Environment construction")
        DeveloperLogger.info(DeveloperLogCategory.PROCESS, "Command construction")
        val invocation = invocationFor(
            binding = binding,
            guestCommand = ProotCommand.LOGIN_SHELL,
            extraEnvironment = extraEnvironment,
        )
        val hostWorkingDirectory = (binding as? UbuntuProjectBinding.Direct)?.hostPath
            ?.takeIf { File(it).isDirectory }
            ?: layout.runtimeDir
        UbuntuDeveloperDiagnostics.logLaunch(invocation, hostWorkingDirectory, layout.rootfs)

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
        UbuntuDeveloperDiagnostics.logLaunch(invocation, hostCwd, layout.rootfs)
        val process = try {
            builder.start()
        } catch (error: Throwable) {
            DeveloperLogger.error(DeveloperLogCategory.ERROR, "process start failure", error)
            throw error
        }
        DeveloperLogger.info(DeveloperLogCategory.PROCESS, "process created")
        return ProcessAgentxExecution(command, guestCwd, process)
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
