package com.agentx.app.ui.ide.state

import com.agentx.app.termux.TerminalSessionState
import com.agentx.app.termux.TermuxBootstrapCatalog
import com.agentx.app.termux.TermuxInstallStage
import com.agentx.app.termux.TermuxProvisioningState
import com.agentx.app.ubuntu.UbuntuInstallStage
import com.agentx.app.ubuntu.UbuntuProjectBinding
import com.agentx.app.ubuntu.UbuntuProjectBindings

/**
 * The wording rules of the Terminal screen.
 *
 * Kept apart from the Compose and Android types on purpose: these are the decisions a user reads
 * when something is wrong — is the shell usable, can the keyboard be offered, which step failed —
 * and separating them means each one is unit tested on the JVM rather than only observed.
 */

/**
 * What to say when no bootstrap can be installed on this device.
 *
 * An unavailable catalog entry must not leave a dead terminal: the system shell still runs, so
 * the message says what is missing and what still works.
 */
fun bootstrapAvailabilityNote(entry: TermuxBootstrapCatalog.Entry?): String? = when {
    entry == null ->
        "No AgentX bootstrap is catalogued for this device's ABI, so the Termux userland cannot " +
            "be installed here. The terminal still runs the system shell."

    !entry.available ->
        "No AgentX bootstrap is available for this ABI/build yet (${entry.androidAbi}). " +
            "No verified artifact has been published, so installing would fail. " +
            "The terminal still runs the system shell."

    else -> null
}

/**
 * One line naming the step that failed for the primary developer runtime and what to do next.
 *
 * The stages mirror [UbuntuInstallStage]; a checksum mismatch and a validation failure are
 * different problems and must read differently.
 */
fun developerRuntimeStageGuidance(stage: UbuntuInstallStage?): String = when (stage) {
    UbuntuInstallStage.DOWNLOAD ->
        "Download failed: the Ubuntu rootfs could not be fetched. Check the connection and retry."
    UbuntuInstallStage.CHECKSUM ->
        "Checksum failed: the downloaded rootfs does not match the pinned SHA-256. It was deleted; retry to fetch it again."
    UbuntuInstallStage.EXTRACTION ->
        "Extraction failed: the verified rootfs could not be unpacked. The archive may be corrupt."
    UbuntuInstallStage.VALIDATION ->
        "Validation failed: the extracted tree does not contain a usable Ubuntu userland (bash, apt, dpkg)."
    UbuntuInstallStage.CONFIGURATION ->
        "Setup failed: the runtime directories or apt configuration could not be written."
    UbuntuInstallStage.ACTIVATION ->
        "Activation failed: the validated rootfs could not be moved into place."
    UbuntuInstallStage.RUNTIME ->
        "Runtime failed: PRoot could not start, or the Ubuntu guest did not pass verification."
    null ->
        "Runtime install failed."
}

/** One line naming the step that failed and what the user can do about it. */
fun installStageGuidance(stage: TermuxInstallStage?): String = when (stage) {
    TermuxInstallStage.DOWNLOAD ->
        "Download failed: the bootstrap archive could not be fetched. Check the connection and retry."
    TermuxInstallStage.CHECKSUM ->
        "Checksum failed: the downloaded archive does not match the published digest. " +
            "It was deleted; retry to fetch it again."
    TermuxInstallStage.EXTRACTION ->
        "Extraction failed: the archive could not be unpacked. It is not a valid bootstrap zip."
    TermuxInstallStage.SYMLINK ->
        "Symlink failed: the archive's SYMLINKS.txt is malformed. The archive is not usable."
    TermuxInstallStage.PERMISSIONS ->
        "Permissions failed: the installed files could not be made executable. " +
            "Check that this app still owns its data directory."
    TermuxInstallStage.PREFIX ->
        "Prefix failed: the archives are not built for this app's prefix. " +
            "Build and publish the AgentX bootstrap rather than the official Termux one."
    TermuxInstallStage.RUNTIME ->
        "Runtime failed: the shell could not run from the installed prefix. " +
            "The userland may be incomplete."
    null ->
        "Install failed."
}

/**
 * The message shown when the terminal could not expose the active project because AgentX has not
 * been granted access to shared storage.
 *
 * Stated in the user's terms on purpose: what they see is a shell that is not in their project, and
 * what they can do about it is grant one access. Nothing here names an internal component.
 */
const val WORKSPACE_ACCESS_REQUIRED_NOTE: String =
    "Terminal is running without the active project because storage access is not granted."

/** The action that opens the system screen which grants shared-storage access to AgentX. */
const val WORKSPACE_ACCESS_ACTION_LABEL: String = "Grant Access"

/**
 * Whether the terminal has to ask for shared-storage access before it can expose the active project.
 *
 * True only in the situation the user can actually act on: the shell fell back to the guest home
 * instead of binding the project ([UbuntuProjectBinding.Home]), the project really is a folder in
 * shared storage, and the access that would let the app read it is not granted.
 *
 * Deliberately narrow. A project that is merely unusable — a folder that no longer exists, a tree
 * from a provider with no filesystem path — is not this case and keeps its own reason, so the
 * terminal never asks for a permission that would not change anything.
 */
fun workspaceAccessRequired(
    binding: UbuntuProjectBinding,
    projectLocation: String?,
    allFilesAccessGranted: Boolean,
): Boolean =
    binding is UbuntuProjectBinding.Home &&
        !allFilesAccessGranted &&
        UbuntuProjectBindings.isSharedStorageLocation(projectLocation)

/**
 * Can the screen offer the on-screen keyboard?
 *
 * Only while a live process is attached. Offering it while a shell is still starting up, or after
 * it has stopped, would suggest typing goes somewhere when it does not. Note this is driven by the
 * session's own state, not by a separate "running" flag that could disagree with it.
 */
fun acceptsInput(unavailable: Boolean, state: TerminalSessionState?): Boolean =
    !unavailable && state == TerminalSessionState.RUNNING

/**
 * The line shown under the terminal for a session that is not running, or null while it is.
 *
 * This is the function that used to emit "the shell stopped before reporting a status" for a
 * session that did not exist. There is no such case any more: a session that never started is
 * [TerminalSessionState.FAILED] and carries its own reason, so the message always names something
 * real. [TerminalSessionState.STARTING] maps to null on purpose — a shell that is coming up has
 * nothing to report, and an exit line there would make a healthy launch look like a failure.
 */
fun exitSummary(
    state: TerminalSessionState,
    exitStatus: Int,
    failure: String?,
    lastError: String? = null,
): String? = when (state) {
    TerminalSessionState.RUNNING,
    TerminalSessionState.STARTING,
    TerminalSessionState.STOPPING,
    TerminalSessionState.IDLE,
    -> null

    TerminalSessionState.FAILED -> appendProcessError(
        failure?.takeIf { it.isNotBlank() }
            ?: "the shell exited with status $exitStatus before it started",
        lastError,
    )

    TerminalSessionState.STOPPED -> appendProcessError(stopReason(exitStatus), lastError)
}

/** The heading over the recovery panel, so a terminal that cannot be typed into is never blank. */
fun terminalFailureHeading(state: TerminalSessionState?): String? = when (state) {
    TerminalSessionState.FAILED -> "Terminal failed to start"
    TerminalSessionState.STOPPED -> "The shell session has ended"
    TerminalSessionState.IDLE -> "Terminal is not started"
    else -> null
}

/**
 * What to show when the screen has no session object at all.
 *
 * Distinct from a stopped shell: there is nothing to describe and nothing to type into. The screen
 * still offers the action that fixes it.
 */
fun noSessionSummary(): String = "No shell session is open."

private fun stopReason(status: Int): String = when {
    status == 0 -> "the shell exited normally"
    status == 1 -> "the shell exited with status 1 (a startup failure, such as a missing prefix or shell)"
    status == 9 -> "the shell was killed (signal 9)"
    status == 137 -> "the shell was killed (SIGKILL)"
    status > 128 -> "the shell was killed by signal ${status - 128}"
    else -> "the shell exited with status $status"
}

private fun appendProcessError(reason: String, lastError: String?): String {
    val error = lastError?.trim()?.lines()?.lastOrNull { it.isNotBlank() }
    return if (error.isNullOrEmpty()) reason else "$reason — $error"
}

/** The label under which a stopped session's restart action is offered. */
fun restartActionLabel(running: Boolean): String = if (running) "Restart" else "Restart terminal"

/** Whether an install can be started right now, and why not when it cannot. */
fun installBlockedReason(state: TerminalUiState): String? = when {
    state.unavailable -> "The embedded runtime is not available in this build."
    state.prefixNote != null -> state.prefixNote
    state.provisioning is TermuxProvisioningState.Downloading ||
        state.provisioning is TermuxProvisioningState.Verifying ||
        state.provisioning is TermuxProvisioningState.Extracting -> "An install is already running."
    state.bootstrapNote != null -> state.bootstrapNote
    else -> null
}
