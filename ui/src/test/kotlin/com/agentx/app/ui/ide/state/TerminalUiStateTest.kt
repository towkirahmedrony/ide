package com.agentx.app.ui.ide.state

import com.agentx.app.termux.TermuxProvisioning
import com.agentx.app.termux.TermuxProvisioningState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The terminal screen is rendered by the vendored Termux view, so the only IDE-side logic
 * worth testing is what the chrome claims about the session.
 */
class TerminalUiStateTest {

    @Test
    fun `status reflects the running session`() {
        assertEquals("idle", TerminalUiState().statusLabel)
        assertEquals("running", TerminalUiState(running = true).statusLabel)
        assertEquals("exited 130", TerminalUiState(exitStatus = 130).statusLabel)
    }

    @Test
    fun `status reflects provisioning progress`() {
        assertEquals(
            "installing 42%",
            TerminalUiState(provisioning = TermuxProvisioningState.Downloading(42, 1, 2)).statusLabel,
        )
        assertEquals("verifying", TerminalUiState(provisioning = TermuxProvisioningState.Verifying(100)).statusLabel)
        assertEquals("installing", TerminalUiState(provisioning = TermuxProvisioningState.Extracting(10)).statusLabel)
        assertEquals(
            "install failed",
            TerminalUiState(provisioning = TermuxProvisioningState.Failed("nope")).statusLabel,
        )
    }

    @Test
    fun `an unavailable runtime says so rather than showing an idle shell`() {
        assertEquals("unavailable", TerminalUiState(unavailable = true).statusLabel)
        assertFalse(TerminalUiState(unavailable = true).canInstall)
    }

    @Test
    fun `install is offered only when it can actually work`() {
        assertTrue(TerminalUiState().canInstall)
        // The temporary Android shell may stay running while the Termux userland is installed.
        assertTrue(TerminalUiState(running = true).canInstall)
        // And nothing to install when the prefix cannot host the packages.
        assertFalse(TerminalUiState(prefixNote = "wrong prefix").canInstall)
        assertFalse(
            TerminalUiState(provisioning = TermuxProvisioningState.Extracting(3)).canInstall,
        )
    }

    @Test
    fun `an install result is reported with its file count`() {
        val state = TermuxProvisioningState.Ready(
            TermuxProvisioning.Installed(bytes = 1, files = 1234, symlinks = 300),
        )
        assertTrue(state.isReady)
        assertEquals("Termux userland installed (1234 files).", state.installedMessage())
    }

    @Test
    fun `an already installed prefix does not repeat itself`() {
        val state = TermuxProvisioningState.Ready(TermuxProvisioning.AlreadyInstalled)
        assertTrue(state.isReady)
        assertEquals(null, state.installedMessage())
    }
}
