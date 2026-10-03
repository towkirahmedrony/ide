package com.agentx.app.ui.ide.state

import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentx.app.termux.DeveloperLogCategory
import com.agentx.app.termux.DeveloperLogger
import com.agentx.app.termux.TerminalDiagnostics
import com.agentx.app.termux.TerminalSessionAdapter
import com.agentx.app.termux.TerminalSessionState
import com.agentx.app.termux.TermuxProvisioning
import com.agentx.app.termux.TermuxProvisioningState
import com.agentx.app.termux.TermuxSession
import com.agentx.app.termux.TermuxSessionSnapshot
import com.agentx.app.termux.TermuxShellSpec
import com.agentx.app.termux.TermuxRuntime
import com.agentx.app.termux.TermuxTerminalHost
import com.agentx.app.termux.TermuxWorkspaceBinding
import com.agentx.app.ubuntu.AgentxRuntimeState
import com.agentx.app.ubuntu.LocalUbuntuRuntime
import com.agentx.app.ubuntu.NativeRuntimeLayout
import com.agentx.app.ubuntu.RuntimeStatus
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/** How many diagnostic lines the terminal's failure panel shows. */
private const val DIAGNOSTIC_LINES: Int = 14

/**
 * What the Terminal screen draws.
 *
 * There are no "lines" here: the emulator owns the screen buffer, and rendering is
 * `TerminalView`'s job. This state only carries what the surrounding IDE chrome needs — which
 * session is attached, where it is rooted, whether a userland has been installed, and the
 * problems the user has to know about.
 */
data class TerminalUiState(
    val workingDirectory: String = "",
    val sessions: List<TermuxSessionSnapshot> = emptyList(),
    val activeHandle: String? = null,
    /**
     * Lifecycle state of the session the screen is attached to, or null when there is no session
     * at all.
     *
     * There is deliberately no separate `running` flag stored beside it. When there was, the two
     * could disagree — and the disagreement is exactly what produced a terminal that reported a
     * shell had "stopped" while no session existed, with Restart disabled and the keyboard gone.
     */
    val sessionState: TerminalSessionState? = null,
    val exitStatus: Int? = null,
    /** Why the active session failed, when it did. */
    val failure: String? = null,
    val fontSizePx: Int = DEFAULT_FONT_SIZE_PX,
    val provisioning: TermuxProvisioningState = TermuxProvisioningState.Idle,
    /** Why the workspace could not be used as-is, if it could not. */
    val workspaceNote: String? = null,
    /** Set when the prefix cannot host the official Termux packages. */
    val prefixNote: String? = null,
    /** True when the embedded runtime is missing entirely (previews, or a failed boot). */
    val unavailable: Boolean = false,
    /**
     * Why no AgentX bootstrap can be installed on this device, when that is the case. Empty means
     * an artifact is catalogued; it says nothing about whether installing would succeed.
     */
    val bootstrapNote: String? = null,
    /** Status of the primary embedded developer runtime (Ubuntu ARM64 through PRoot). */
    val developerRuntime: RuntimeStatus = RuntimeStatus.NotInstalled,
    /** True when a developer runtime is wired into this build (not a preview/test). */
    val developerRuntimeAvailable: Boolean = false,
    /** True when the live shell is the Ubuntu guest rather than the legacy backend. */
    val usingDeveloperRuntime: Boolean = false,
    /**
     * What the native runtime actually looks like on this device: `nativeLibraryDir` and, for each
     * library the runtime needs, whether it is really there and really executable.
     *
     * Present so a startup failure names the real paths instead of only describing a symptom. The
     * APK's contents are not evidence — the installer decides where these files land, and the
     * runtime resolves the directory at run time.
     */
    val nativeFacts: List<String> = emptyList(),
    /** Tail of the diagnostic record, so a failure shows something actionable. */
    val diagnostics: String? = null,
) {

    /**
     * True when the terminal cannot be drawn usefully, so the screen shows the recovery panel
     * instead of a blank surface: either there is no session, or the session failed to start and
     * therefore has no emulator to draw.
     */
    val showsRecoveryPanel: Boolean
        get() = !unavailable &&
            (sessionState == null || sessionState == TerminalSessionState.FAILED)

    /** True when the first-run developer-runtime install screen should be shown. */
    val developerRuntimeNeedsInstall: Boolean
        get() = developerRuntimeAvailable && developerRuntime.state == AgentxRuntimeState.NOT_INSTALLED

    /** True while the developer runtime is downloading or installing. */
    val developerRuntimeBusy: Boolean
        get() = developerRuntimeAvailable && developerRuntime.isBusy

    /** True when the developer-runtime install failed and can be retried. */
    val developerRuntimeFailed: Boolean
        get() = developerRuntimeAvailable && developerRuntime.state == AgentxRuntimeState.ERROR

    val statusLabel: String
        get() = when {
            unavailable -> "unavailable"
            developerRuntimeBusy && developerRuntime.state == AgentxRuntimeState.DOWNLOADING ->
                "installing ${developerRuntime.progressPercent}%"
            developerRuntimeBusy -> "installing"
            developerRuntimeFailed -> "install failed"
            provisioning is TermuxProvisioningState.Downloading -> "installing ${provisioning.percent}%"
            provisioning is TermuxProvisioningState.Verifying -> "verifying"
            provisioning is TermuxProvisioningState.Extracting -> "installing"
            provisioning is TermuxProvisioningState.Failed -> "install failed"
            sessionState == TerminalSessionState.RUNNING -> "running"
            sessionState == TerminalSessionState.STARTING -> "starting"
            sessionState == TerminalSessionState.STOPPING -> "stopping"
            sessionState == TerminalSessionState.FAILED -> "failed"
            sessionState == TerminalSessionState.STOPPED -> "exited ${exitStatus ?: 0}"
            else -> "idle"
        }

    /** Whether a live process is attached. Derived, so it cannot disagree with [sessionState]. */
    val running: Boolean get() = sessionState == TerminalSessionState.RUNNING

    /** True when the screen has no session object at all and must offer to create one. */
    val needsSession: Boolean get() = !unavailable && sessionState == null

    /**
     * Whether the on-screen keyboard may be offered. Only a live process can receive typing;
     * offering it after the process exited would suggest the keys go somewhere.
     */
    val canType: Boolean
        get() = acceptsInput(unavailable = unavailable, state = sessionState)

    /**
     * The readable exit line, or null while the process is running. The process's own stderr is
     * above this line, in the terminal buffer itself.
     */
    val exitLine: String?
        get() = sessionState?.let { state ->
            exitSummary(state = state, exitStatus = exitStatus ?: 0, failure = failure)
        }

    /** Heading over the recovery panel, so a terminal that cannot be typed into is never blank. */
    val failureHeading: String? get() = terminalFailureHeading(sessionState)

    /** The label for the restart action, which is the whole recovery path once a shell exits. */
    val restartLabel: String get() = restartActionLabel(running)

    /** Why an install cannot start right now, or null when it can. */
    val installBlocked: String? get() = installBlockedReason(this)

    val canInstall: Boolean
        get() = !unavailable &&
            provisioning !is TermuxProvisioningState.Downloading &&
            provisioning !is TermuxProvisioningState.Verifying &&
            provisioning !is TermuxProvisioningState.Extracting &&
            prefixNote == null &&
            bootstrapNote == null &&
            provisioning !is TermuxProvisioningState.Ready

    companion object {
        // TerminalView consumes this as a pixel-sized monospace glyph. 30 made a 720px-wide
        // phone show only a handful of columns and made the screen feel unresponsive.
        const val DEFAULT_FONT_SIZE_PX: Int = 18
    }
}

/**
 * Drives the Terminal screen.
 *
 * Owns no process. The shell belongs to [TermuxRuntime], which outlives this ViewModel, so
 * leaving the tab, rotating the device or a Compose recomposition cannot start a second shell
 * or kill the one that is running `npm run dev`.
 *
 * The runtime forwards emulator callbacks to whichever [TermuxTerminalHost] the screen has
 * bound, so the same session can be displayed, hidden and displayed again.
 */
class TerminalViewModel(
    private val workspaceId: String,
    private val workspaceName: String,
    /** The workspace's location as the workspace runtime reports it. */
    private val workspaceLocation: () -> String?,
    /**
     * The workspace's opaque handle (a `content://` tree URI, or a path), when the app has one.
     *
     * The embedded Ubuntu runtime binds the project at `/workspace`. A real path (a project in
     * managed storage) is bound as-is; a SAF tree is resolved to the phone-storage path it names
     * and bound in place, so the terminal, the file browser and the editor all operate on the
     * same original files — nothing is copied. That needs the handle, which the workspace manager
     * owns — the display location alone is not enough.
     */
    private val workspaceHandle: () -> String? = { null },
    private val runtime: TermuxRuntime?,
    /**
     * The primary developer runtime. When it is present and ready it supplies the terminal's
     * process (PRoot → Ubuntu guest); otherwise the legacy [runtime] does, unchanged.
     */
    private val developerRuntime: LocalUbuntuRuntime? = null,
    /**
     * The catalog entry for this device, injected so the screen's unavailable-ABI behaviour is
     * testable. Defaults to the real catalog resolved against the device's ABIs.
     */
    private val bootstrapEntry: (List<String>) -> com.agentx.app.termux.TermuxBootstrapCatalog.Entry? =
        com.agentx.app.termux.TermuxBootstrapCatalog::forAbis,
    private val deviceAbis: () -> List<String> = { Build.SUPPORTED_ABIS.toList() },
) : ViewModel() {

    var uiState by mutableStateOf(TerminalUiState())
        private set

    private var collectors: Job? = null
    private var boundHost: TermuxTerminalHost? = null

    /** Whether the user asked for a shell rooted at `$HOME` rather than at the workspace. */
    private var scratch = false

    init {
        val current = runtime
        if (current == null) {
            uiState = uiState.copy(
                unavailable = true,
                prefixNote = "The embedded Termux runtime is not available in this build.",
            )
        } else {
            uiState = uiState.copy(
                prefixNote = (current.prefixSupport as? com.agentx.app.termux.TermuxPrefixSupport.Unsupported)
                    ?.reason,
                // Resolved once here so the screen can say "no bootstrap for this ABI/build"
                // instead of offering an Install button that is guaranteed to fail.
                bootstrapNote = bootstrapAvailabilityNote(
                    runCatching { bootstrapEntry(deviceAbis()) }.getOrNull(),
                ),
            )
            observe(current)
            open(scratch = false)
        }
        uiState = uiState.copy(
            developerRuntimeAvailable = developerRuntime != null,
            developerRuntime = developerRuntime?.status?.value ?: RuntimeStatus.NotInstalled,
        )
    }

    /** The screen publishes itself here so emulator callbacks reach the view it renders. */
    fun bindTerminalHost(host: TermuxTerminalHost) {
        boundHost = host
        runtime?.terminalHost = host
    }

    fun unbindTerminalHost(host: TermuxTerminalHost) {
        if (boundHost === host) {
            boundHost = null
            runtime?.terminalHost = null
        }
    }

    /** The real pty session a `TerminalView` must attach to, or null when nothing is running. */
    fun activeTerminalSession(): TerminalSession? =
        (runtime?.sessions?.active() as? TerminalSessionAdapter)?.delegate

    fun refreshFromHost() {
        runtime?.sessions?.refresh()
    }

    fun provision() {
        val current = runtime ?: return
        val developer = developerRuntime
        if (developer != null && !developer.isReady()) {
            // The primary path: install and *verify* the Ubuntu ARM64 rootfs, then replace the
            // shell that was running before it existed. The legacy bootstrap is not touched.
            // `isReady` (not `needsInstall`) is the condition because an installed rootfs that
            // has not passed its PRoot verification must be re-verified, not re-downloaded.
            viewModelScope.launch {
                // The signal settles on the value *this* attempt produced, so a stale ERROR from
                // a previous attempt cannot be mistaken for this one's outcome.
                val settled = developer.provision().await()
                if (settled.state == AgentxRuntimeState.READY) switchToInstalledRuntime(developer)
            }
            return
        }
        uiState = uiState.copy(prefixNote = null)
        viewModelScope.launch {
            current.provision()
            if (current.provisioning.first { state ->
                    state is TermuxProvisioningState.Ready || state is TermuxProvisioningState.Failed
                } is TermuxProvisioningState.Ready
            ) {
                // The first shell may have been Android's temporary /system/bin/sh. Replace it
                // now that the real Termux-compatible prefix is ready.
                restart()
            }
        }
    }

    fun selectSession(handle: String) {
        runtime?.sessions?.setActive(handle)
    }

    /**
     * Moves the terminal onto the runtime that has just been installed.
     *
     * Three steps, in this order, because each is what the next depends on: re-read the runtime's
     * persisted state so the screen reports READY rather than whatever was set last, drop the
     * sessions that only existed because the runtime was missing, and only then open the guest
     * shell.
     *
     * The middle step is the one that was missing. Without it the FAILED session opened before the
     * install stayed bound to the workspace, with its NOT_INSTALLED reason frozen inside it, so the
     * screen kept presenting that failure next to a runtime that had since become usable.
     */
    private fun switchToInstalledRuntime(developer: LocalUbuntuRuntime) {
        val current = runtime ?: return
        // Read from disk through the same isReady() the terminal spec is gated on, so the state
        // shown and the decision to open a shell come from one source of truth.
        developer.refresh()
        current.sessions.discardUnusable(workspaceKey(scratch))
        uiState = uiState.copy(usingDeveloperRuntime = true, workspaceNote = null)
        // restart is what actually creates the new session; it needs no handle, so a workspace whose
        // session was just discarded still gets a completely fresh one.
        restart()
    }

    /**
     * Opens a shell in the workspace. Reuses the running one when there is one, so a
     * recomposition or a tab switch never spawns a duplicate process.
     */
    fun openWorkspaceShell() {
        scratch = false
        open(scratch = false)
    }

    /**
     * Guarantees the screen has a session to talk to, starting one if there is none.
     *
     * This is the app-restart path. Sessions belong to the process, so after a restart the manager
     * starts empty and no PTY, pid or handle from the previous run is restored — deliberately, since
     * none of them would still be valid. Rather than adopting stale metadata, this creates a clean
     * session, which is what makes reopening the app land on a usable terminal.
     */
    fun ensureSession() {
        val current = runtime ?: return
        if (current.sessions.sessions().isEmpty()) open(scratch = false)
    }

    /** Opens an extra shell rooted at `$HOME`, for commands that are not about the project. */
    fun openScratchShell() {
        scratch = true
        open(scratch = true)
    }

    /**
     * Destroys the current session and starts a completely new one.
     *
     * Deliberately not gated on an active handle existing. It used to return immediately when
     * `activeHandle` was null, which is exactly the situation after a startup failure — so the one
     * button that could have recovered the terminal did nothing precisely when it was needed.
     *
     * It also does not depend on receiving anything from the old session: `sessions.restart` tears
     * the old one down and builds the replacement itself, so it works from FAILED, STOPPED and
     * STARTING alike, and with no session at all.
     */
    fun restart() {
        val current = runtime ?: return
        DeveloperLogger.info(DeveloperLogCategory.RESTART, "Requested")
        val handle = current.sessions.activeHandle.value
        // A handle that is already gone means the shell this screen was on has been removed; fall
        // back to the workspace shell rather than restarting one that no longer exists.
        val background = scratch || (handle != null && current.sessions.find(handle) == null)
        viewModelScope.launch {
            // Never on the UI thread: resolving the developer spec probes the filesystem, and
            // starting a process must not block a frame either.
            val resolved = withContext(Dispatchers.IO) { runCatching { spec(background) } }
            val spec = resolved.getOrNull()
            if (spec != null) {
                current.sessions.restart(handle, spec)?.let { restarted ->
                    current.sessions.setActive(restarted.handle)
                }
                return@launch
            }
            // Same rule as opening: retry the real path and report why it could not be taken,
            // rather than restarting into an Android shell.
            val developer = developerRuntime
            val reason = withContext(Dispatchers.IO) {
                if (developer != null) {
                    developerUnavailableReason(developer, resolved)
                } else {
                    "The shell could not be prepared: " +
                        (resolved.exceptionOrNull()?.message ?: "unknown error")
                }
            }
            resolved.exceptionOrNull()?.let { error ->
                DeveloperLogger.error(DeveloperLogCategory.ERROR, "restart could not prepare a shell", error)
            }
            uiState = uiState.copy(usingDeveloperRuntime = developer != null, workspaceNote = reason)
            current.sessions.restartUnstartable(
                handle = handle,
                workspaceKey = workspaceKey(background),
                executable = developer?.layout?.proot,
                reason = reason,
            )
        }
    }

    fun terminateActive() {
        val current = runtime ?: return
        val handle = current.sessions.activeHandle.value ?: return
        current.terminate(handle)
    }

    fun terminateAll() {
        runtime?.terminateAll()
    }

    /** Writes raw bytes to the active pty. Escape sequences are the caller's business. */
    fun send(bytes: ByteArray) {
        val session = runtime?.sessions?.active() ?: return
        if (!session.isRunning) return
        session.write(bytes, 0, bytes.size)
    }

    fun zoomIn() {
        uiState = uiState.copy(
            fontSizePx = (uiState.fontSizePx + FONT_STEP_PX).coerceAtMost(MAX_FONT_SIZE_PX),
        )
    }

    fun zoomOut() {
        uiState = uiState.copy(
            fontSizePx = (uiState.fontSizePx - FONT_STEP_PX).coerceAtLeast(MIN_FONT_SIZE_PX),
        )
    }

    fun resetZoom() {
        uiState = uiState.copy(fontSizePx = TerminalUiState.DEFAULT_FONT_SIZE_PX)
    }

    override fun onCleared() {
        collectors?.cancel()
        collectors = null
        // The sessions deliberately survive: they belong to the runtime, not to this screen.
        if (boundHost != null) {
            runtime?.terminalHost = null
            boundHost = null
        }
    }

    private fun observe(current: TermuxRuntime) {
        collectors?.cancel()
        collectors = viewModelScope.launch {
            launch {
                current.sessions.snapshots.collect { sessions -> publish(sessions) }
            }
            launch {
                current.sessions.activeHandle.collect { handle ->
                    publish(current.sessions.snapshots.value, handle)
                }
            }
            launch {
                current.provisioning.collect { state -> uiState = uiState.copy(provisioning = state) }
            }
            val developer = developerRuntime
            if (developer != null) {
                launch {
                    developer.status.collect { status -> uiState = uiState.copy(developerRuntime = status) }
                }
            }
        }
    }

    private fun publish(
        sessions: List<TermuxSessionSnapshot>,
        active: String? = runtime?.sessions?.activeHandle?.value,
    ) {
        val current = sessions.firstOrNull { it.handle == active } ?: sessions.firstOrNull()
        uiState = uiState.copy(
            sessions = sessions,
            // Falling back to the first session keeps Restart pointed at something real when the
            // manager's active handle is momentarily unset.
            activeHandle = active ?: current?.handle,
            sessionState = current?.state,
            exitStatus = current?.takeIf { it.terminal }?.exitStatus,
            failure = current?.failure,
            workingDirectory = current?.workingDirectory.orEmpty(),
            // Re-read on every publish, not cached: whether these files exist is the single most
            // useful fact when a shell will not start.
            nativeFacts = nativeRuntimeFacts(),
            diagnostics = TerminalDiagnostics.report(DIAGNOSTIC_LINES),
        )
    }

    private fun open(scratch: Boolean) {
        DeveloperLogger.info(DeveloperLogCategory.TERMINAL, "Open requested")
        val current = runtime ?: return
        val developer = developerRuntime
        if (developer == null) {
            openLegacy(current, scratch)
            return
        }
        // The project handle has to be matched against real directories before the guest can
        // bind it, which is disk I/O; the session is opened on the main dispatcher once that is
        // done.
        val key = workspaceKey(scratch)
        viewModelScope.launch {
            val resolved = withContext(Dispatchers.IO) {
                runCatching { developerSpec(current, developer, key, scratch) }
            }
            val spec = resolved.getOrNull()
            if (spec != null) {
                // The primary backend: a real Ubuntu guest shell. The workspace note is cleared
                // because the project is bind-mounted, not copied.
                uiState = uiState.copy(usingDeveloperRuntime = true, workspaceNote = null)
                current.openSession(spec)
                return@launch
            }
            // No Android shell is opened in its place. This used to fall back to
            // `/system/bin/sh` with the host's Termux home as the working directory, which looked
            // like a working terminal while actually being the Android host: `id` reported
            // u0_a1005, `/` was unreadable and `/etc/os-release` did not exist. A terminal that
            // opens as the wrong system is harder to diagnose than one that says it failed, so the
            // failure is recorded and shown instead, and Restart retries the real path.
            val reason = withContext(Dispatchers.IO) {
                developerUnavailableReason(developer, resolved)
            }
            resolved.exceptionOrNull()?.let { error ->
                DeveloperLogger.error(
                    DeveloperLogCategory.ERROR,
                    "Ubuntu shell could not be prepared",
                    error,
                )
            }
            uiState = uiState.copy(usingDeveloperRuntime = true, workspaceNote = reason)
            current.sessions.openUnstartable(
                workspaceKey = key,
                executable = developer.layout.proot,
                reason = reason,
            )
        }
    }

    /**
     * The reason no Ubuntu shell could be prepared, preferring the real error.
     *
     * When resolving the spec threw, that throwable *is* the answer and is reported verbatim —
     * class name included, because for a native or linkage failure the message alone is often not
     * enough. Otherwise the runtime is asked which of its gates is not satisfied.
     */
    private fun developerUnavailableReason(
        developer: LocalUbuntuRuntime,
        resolved: Result<TermuxShellSpec?>,
    ): String {
        val error = resolved.exceptionOrNull()
        if (error != null) {
            return "The Ubuntu shell could not be prepared: ${error.javaClass.name}: " +
                "${error.message ?: "(no message)"}"
        }
        return developer.notReadyReason()
    }

    private fun openLegacy(current: TermuxRuntime, scratch: Boolean) {
        val key = workspaceKey(scratch)
        val binding = binding(current, scratch)
        uiState = uiState.copy(
            usingDeveloperRuntime = false,
            workspaceNote = workspaceNoteFor(binding)?.let(::trimNote),
        )
        current.openSession(current.specFor(key, binding, extraEnvironment(scratch)))
    }

    /**
     * The spec for the current workspace, or null when a developer runtime is configured but cannot
     * produce one.
     *
     * Null is only ever returned in that case, and it means "no shell can be described" — not
     * "use something else". The callers report it; none of them substitutes another shell, because
     * the terminal's contract in the developer runtime is a Ubuntu guest or a visible failure.
     */
    private fun spec(scratch: Boolean): TermuxShellSpec? {
        val current = checkNotNull(runtime) { "Termux runtime is not available" }
        val developer = developerRuntime
        if (developer != null) {
            // Resolving this can touch the filesystem (the workspace handle is matched against
            // real directories, including the path a SAF tree names). A failure there propagates:
            // the caller turns it into a visible failure with the real error rather than quietly
            // opening a different shell.
            return developerSpecNow(current, developer, workspaceKey(scratch), scratch)
        }
        return current.specFor(
            workspaceKey = workspaceKey(scratch),
            binding = binding(current, scratch),
            extraEnvironment = extraEnvironment(scratch),
        )
    }

    private fun workspaceKey(scratch: Boolean): String =
        if (scratch) "$workspaceId::scratch" else workspaceId

    /**
     * Reports the native runtime as it is on disk right now.
     *
     * Checked live rather than read from a cached probe: the whole question at this point is
     * whether the files the APK was built with are actually present and executable after the
     * installer put them somewhere, and a value captured at start-up could answer for a state
     * that no longer holds.
     */
    private fun nativeRuntimeFacts(): List<String> {
        val developer = developerRuntime ?: return emptyList()
        val directory = developer.layout.nativeLibraryDir
        val facts = ArrayList<String>(NativeRuntimeLayout.REQUIRED_LIBRARIES.size + 2)
        facts += "nativeLibraryDir: $directory"
        val names = NativeRuntimeLayout.REQUIRED_LIBRARIES + NativeRuntimeLayout.OPTIONAL_LIBRARIES
        for (name in names) {
            val file = File("$directory/$name")
            facts += when {
                !file.isFile -> "$name: MISSING"
                file.canExecute() -> "$name: present, executable"
                else -> "$name: present, NOT executable"
            }
        }
        facts += "PRoot executable: $directory/${NativeRuntimeLayout.PROOT_LIBRARY}"
        facts += "PROOT_LOADER: $directory/${NativeRuntimeLayout.LOADER_LIBRARY}"
        facts += "rootfs: ${developer.layout.rootfs}"
        facts += "rootfs verified: ${developer.isReady()}"
        return facts
    }

    /**
     * The developer-runtime spec for this session, or null when it cannot be used yet.
     *
     * Null means the runtime is missing, not installed, or its native components are absent; the
     * caller then falls back to the legacy backend so the terminal is never left without a shell.
     */
    private suspend fun developerSpec(
        current: TermuxRuntime,
        developer: LocalUbuntuRuntime,
        key: String,
        scratch: Boolean,
    ): TermuxShellSpec? {
        DeveloperLogger.info(DeveloperLogCategory.ROOTFS, "RootFS resolution started")
        DeveloperLogger.info(DeveloperLogCategory.ROOTFS, "RootFS path = ${developer.layout.rootfs}")
        DeveloperLogger.info(DeveloperLogCategory.PROOT, "PRoot path = ${developer.layout.proot}")
        DeveloperLogger.info(
            DeveloperLogCategory.ROOTFS,
            "RootFS validation ready=${developer.isReady()}",
        )
        if (!developer.isReady()) {
            DeveloperLogger.warn(DeveloperLogCategory.ROOTFS, developer.notReadyReason())
            return null
        }
        val handle = if (scratch) null else projectHandle(current)

        return developer.specFor(
            workspaceKey = key,
            projectHandle = handle,
            displayLocation = workspaceLocation(),
            extraEnvironment = extraEnvironment(scratch),
        )
    }

    /**
     * The same spec, resolved without suspending.
     *
     * Used by [restart], which runs on the main thread. Resolving the handle is string matching
     * against the filesystem — a SAF tree is translated to its phone-storage path — so no copy
     * and no suspending work stands between a restart and a shell.
     */
    private fun developerSpecNow(
        current: TermuxRuntime,
        developer: LocalUbuntuRuntime,
        key: String,
        scratch: Boolean,
    ): TermuxShellSpec? {
        DeveloperLogger.info(DeveloperLogCategory.ROOTFS, "RootFS resolution started")
        DeveloperLogger.info(DeveloperLogCategory.ROOTFS, "RootFS path = ${developer.layout.rootfs}")
        DeveloperLogger.info(DeveloperLogCategory.PROOT, "Resolving PRoot")
        DeveloperLogger.info(DeveloperLogCategory.PROOT, "PRoot path = ${developer.layout.proot}")
        if (!developer.isReady()) {
            DeveloperLogger.warn(DeveloperLogCategory.ROOTFS, developer.notReadyReason())
            return null
        }
        val handle = if (scratch) null else projectHandle(current)
        return developer.specFor(
            workspaceKey = key,
            projectHandle = handle,
            displayLocation = workspaceLocation(),
            extraEnvironment = extraEnvironment(scratch),
        )
    }

    /**
     * The workspace handle to bind at `/workspace`.
     *
     * The opaque handle first: a real path (a project in managed storage) is used as-is, and a
     * SAF `content://` tree is resolved to the phone-storage path it names — the original folder,
     * in its original location, never a copy. Only when the app has no handle does this fall
     * back to the directory the legacy runtime resolved.
     */
    private fun projectHandle(current: TermuxRuntime): String? =
        workspaceHandle() ?: legacyHostPath(current)

    /**
     * The real host directory of the workspace, when it has one.
     *
     * A SAF tree with no path yields null: the developer runtime then starts in the guest home
     * instead of binding a directory that does not exist.
     */
    private fun legacyHostPath(current: TermuxRuntime): String? =
        when (val resolved = binding(current, scratch = false)) {
            is TermuxWorkspaceBinding.Direct -> resolved.path
            is TermuxWorkspaceBinding.Mirrored -> resolved.termuxPath
            else -> null
        }

    private fun binding(current: TermuxRuntime, scratch: Boolean): TermuxWorkspaceBinding =
        if (scratch) {
            TermuxWorkspaceBinding.Direct(path = current.paths.home, displayLocation = "home")
        } else {
            current.bindingFor(
                workspaceId = workspaceId,
                handle = null,
                displayLocation = workspaceLocation(),
            )
        }

    /**
     * Context for the shell. Deliberately only identifiers: no credentials, no tokens, nothing
     * that came from a connection or a model preset.
     */
    private fun extraEnvironment(scratch: Boolean): Map<String, String> = mapOf(
        "CODER_WORKSPACE_ID" to workspaceId,
        "CODER_WORKSPACE_NAME" to workspaceName,
        "CODER_SHELL_KIND" to if (scratch) "scratch" else "workspace",
        "CODER_TERMUX_PREFIX" to (runtime?.paths?.prefix ?: ""),
    )

    /**
     * What the user has to know about where this shell is rooted.
     *
     * A mirrored workspace says so explicitly, because commands then run against a copy and the
     * original folder is never written back — silently looking like the real folder would be the
     * worst possible outcome.
     */
    private fun workspaceNoteFor(binding: TermuxWorkspaceBinding): String? = when (binding) {
        is TermuxWorkspaceBinding.Mirrored ->
            "Running in ${binding.termuxPath}, a copy of ${binding.source}. Commands run against " +
                "the copy; the original folder is not written back."
        is TermuxWorkspaceBinding.Home -> binding.reason
        is TermuxWorkspaceBinding.Unavailable -> binding.reason
        is TermuxWorkspaceBinding.Direct -> null
    }

    /** The banner is long by design; the header shows the first sentence. */
    private fun trimNote(note: String): String = note.substringBefore(". ") + "."

    private companion object {
        const val FONT_STEP_PX = 4
        const val MIN_FONT_SIZE_PX = 12
        const val MAX_FONT_SIZE_PX = 72
    }
}

/** Kept so callers can express "installed and ready" without importing the sealed type. */
val TermuxProvisioningState.isReady: Boolean get() = this is TermuxProvisioningState.Ready

/** Convenience for the screen: the installed message, when there is one. */
fun TermuxProvisioningState.installedMessage(): String? =
    (this as? TermuxProvisioningState.Ready)?.installed?.let { installed ->
        when (installed) {
            is TermuxProvisioning.Installed -> "Termux userland installed (${installed.files} files)."
            TermuxProvisioning.AlreadyInstalled -> null
            else -> null
        }
    }

/** Rounds a dp font size to the pixel value `TerminalView.setTextSize` expects. */
fun pixelsFor(sizeDp: Float, density: Float): Int = (sizeDp * density).roundToInt()

/** The label for a session in the session strip. */
fun TermuxSession.label(): String = title?.takeIf { it.isNotBlank() } ?: workspaceKeyLabel()

private fun TermuxSession.workspaceKeyLabel(): String = handle.take(8)
