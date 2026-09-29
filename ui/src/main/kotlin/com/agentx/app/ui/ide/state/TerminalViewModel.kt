package com.agentx.app.ui.ide.state

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
) {
    val statusLabel: String
        get() = when {
            unavailable -> "unavailable"
            provisioning is TermuxProvisioningState.Downloading -> "installing ${provisioning.percent}%"
            provisioning is TermuxProvisioningState.Verifying -> "verifying"
            provisioning is TermuxProvisioningState.Extracting -> "installing"
            provisioning is TermuxProvisioningState.Failed -> "install failed"
            running -> "running"
            exitStatus != null -> "exited $exitStatus"
            else -> "idle"
        }

    val canInstall: Boolean
        get() = !unavailable &&
            provisioning !is TermuxProvisioningState.Downloading &&
            provisioning !is TermuxProvisioningState.Verifying &&
            provisioning !is TermuxProvisioningState.Extracting &&
            prefixNote == null &&
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
            )
            observe(current)
            open(scratch = false)
        }
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
        val binding = binding(current, scratch)
        uiState = uiState.copy(
            workspaceNote = (binding as? TermuxWorkspaceBinding.Unavailable)?.reason?.let(::trimNote),
        )
        val key = if (scratch) "$workspaceId::scratch" else workspaceId
        current.openSession(current.specFor(key, binding, extraEnvironment(scratch)))
    }

    private fun spec(scratch: Boolean): TermuxShellSpec {
        val current = checkNotNull(runtime) { "Termux runtime is not available" }
        return current.specFor(
            workspaceKey = if (scratch) "$workspaceId::scratch" else workspaceId,
            binding = binding(current, scratch),
            extraEnvironment = extraEnvironment(scratch),
        )
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
