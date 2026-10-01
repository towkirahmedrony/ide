package com.agentx.app.ui.ide.state

import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentx.app.termux.TerminalSessionAdapter
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
import com.agentx.app.ubuntu.RuntimeStatus
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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
    val running: Boolean = false,
    val exitStatus: Int? = null,
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
) {
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
            running -> "running"
            exitStatus != null -> "exited $exitStatus"
            else -> "idle"
        }

    /**
     * Whether the on-screen keyboard may be offered. Only a live process can receive typing;
     * offering it after the process exited would suggest the keys go somewhere.
     */
    val canType: Boolean
        get() = acceptsInput(unavailable = unavailable, running = running)

    /**
     * The readable exit line, or null while the process is running. The process's own stderr is
     * above this line, in the terminal buffer itself.
     */
    val exitLine: String?
        get() = exitSummary(running = running, exitStatus = exitStatus, lastError = null)

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
        if (developer != null && developer.needsInstall()) {
            // The primary path: install the Ubuntu ARM64 rootfs, then replace the shell that was
            // running before it existed. The legacy bootstrap is not touched.
            viewModelScope.launch {
                developer.provision()
                val settled = developer.status.first { status ->
                    status.state == AgentxRuntimeState.READY || status.state == AgentxRuntimeState.ERROR
                }
                if (settled.state == AgentxRuntimeState.READY) restart()
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
     * Opens a shell in the workspace. Reuses the running one when there is one, so a
     * recomposition or a tab switch never spawns a duplicate process.
     */
    fun openWorkspaceShell() {
        scratch = false
        open(scratch = false)
    }

    /** Opens an extra shell rooted at `$HOME`, for commands that are not about the project. */
    fun openScratchShell() {
        scratch = true
        open(scratch = true)
    }

    fun restart() {
        val current = runtime ?: return
        val handle = current.sessions.activeHandle.value ?: return
        val spec = spec(scratch = scratch || current.sessions.find(handle) == null)
        current.sessions.restart(handle, spec)?.let { current.sessions.setActive(it.handle) }
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
            activeHandle = active,
            running = current?.running == true,
            exitStatus = current?.takeIf { !it.running }?.exitStatus,
            workingDirectory = current?.workingDirectory.orEmpty(),
        )
    }

    private fun open(scratch: Boolean) {
        val current = runtime ?: return
        val key = workspaceKey(scratch)
        val developerSpec = developerSpec(current, key, scratch)
        if (developerSpec != null) {
            // The primary backend: a real Ubuntu guest shell. The workspace note is cleared
            // because the project is bind-mounted, not copied.
            uiState = uiState.copy(usingDeveloperRuntime = true, workspaceNote = null)
            current.openSession(developerSpec)
            return
        }
        val binding = binding(current, scratch)
        uiState = uiState.copy(
            usingDeveloperRuntime = false,
            workspaceNote = workspaceNoteFor(binding)?.let(::trimNote),
        )
        current.openSession(current.specFor(key, binding, extraEnvironment(scratch)))
    }

    private fun spec(scratch: Boolean): TermuxShellSpec {
        val current = checkNotNull(runtime) { "Termux runtime is not available" }
        developerSpec(current, workspaceKey(scratch), scratch)?.let { return it }
        return current.specFor(
            workspaceKey = workspaceKey(scratch),
            binding = binding(current, scratch),
            extraEnvironment = extraEnvironment(scratch),
        )
    }

    private fun workspaceKey(scratch: Boolean): String =
        if (scratch) "$workspaceId::scratch" else workspaceId

    /**
     * The developer-runtime spec for this session, or null when it cannot be used yet.
     *
     * Null means the runtime is missing, not installed, or its native components are absent; the
     * caller then falls back to the legacy backend so the terminal is never left without a shell.
     */
    private fun developerSpec(
        current: TermuxRuntime,
        key: String,
        scratch: Boolean,
    ): TermuxShellSpec? {
        val developer = developerRuntime ?: return null
        if (!developer.isReady()) return null
        val projectHostPath = if (scratch) null else legacyHostPath(current)
        return developer.specFor(
            workspaceKey = key,
            projectHostPath = projectHostPath,
            displayLocation = workspaceLocation(),
            extraEnvironment = extraEnvironment(scratch),
        )
    }

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
