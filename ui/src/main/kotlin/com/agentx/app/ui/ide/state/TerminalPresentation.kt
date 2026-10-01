package com.agentx.app.ui.ide.state

import com.agentx.app.termux.TermuxBootstrapCatalog
import com.agentx.app.termux.TermuxInstallStage
import com.agentx.app.termux.TermuxProvisioningState
import com.agentx.app.ubuntu.UbuntuInstallStage

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
        "Runtime failed: the rootfs installed but the install marker or required files are missing."
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
 * Can the screen offer the on-screen keyboard?
 *
 * Only while a live process is attached. After the process exits, offering the keyboard would
 * suggest typing goes somewhere when it does not.
 */
fun acceptsInput(unavailable: Boolean, running: Boolean): Boolean = running && !unavailable

/**
 * The exit line for a session that has stopped, with the process's own error output when there is
 * any. Null while the process is running.
 */
fun exitSummary(running: Boolean, exitStatus: Int?, lastError: String?): String? {
    if (running) return null
    val status = exitStatus ?: 1
    val reason = when {
        exitStatus == null -> "the shell stopped before reporting a status"
        status == 0 -> "the shell exited normally"
        status == 1 -> "the shell exited with status 1 (a startup failure, such as a missing prefix or shell)"
        status == 9 -> "the shell was killed (signal 9)"
        status == 137 -> "the shell was killed (SIGKILL)"
        status > 128 -> "the shell was killed by signal ${status - 128}"
        else -> "the shell exited with status $status"
    }
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
