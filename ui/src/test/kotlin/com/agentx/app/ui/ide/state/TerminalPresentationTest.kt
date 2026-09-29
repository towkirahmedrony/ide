package com.agentx.app.ui.ide.state

import com.agentx.app.termux.TermuxBootstrapCatalog
import com.agentx.app.termux.TermuxInstallStage
import com.agentx.app.termux.TermuxProvisioning
import com.agentx.app.termux.TermuxProvisioningState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TerminalPresentationTest {

    @Test
    fun `an unavailable catalog entry names the abi and keeps the terminal usable`() {
        val entry = assertNotNull(TermuxBootstrapCatalog.forAbi("x86_64"))
        val note = assertNotNull(bootstrapAvailabilityNote(entry))

        assertTrue(note.contains("x86_64"), note)
        assertTrue(note.contains("No AgentX bootstrap is available for this ABI/build yet"), note)
        // A dead terminal is exactly what this message exists to avoid.
        assertTrue(note.contains("still runs the system shell"), note)
    }

    @Test
    fun `an abi with no catalogue entry at all is reported instead of guessed`() {
        val note = assertNotNull(bootstrapAvailabilityNote(null))
        assertTrue(note.contains("cannot be installed"), note)
        assertTrue(note.contains("still runs the system shell"), note)
    }

    @Test
    fun `a published entry needs no note`() {
        val pending = assertNotNull(TermuxBootstrapCatalog.forAbi("arm64-v8a"))
        val published = pending.copy(
            url = "https://github.com/o/r/releases/download/t/bootstrap-aarch64.zip",
            sha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            archiveSizeBytes = 1L,
            fileCount = 1,
            sourceRevision = "termux-packages@deadbeef+agentx",
        )
        assertNull(bootstrapAvailabilityNote(published))
    }

    @Test
    fun `every install stage has its own advice`() {
        val guidance = TermuxInstallStage.entries.map { installStageGuidance(it) }
        assertEquals(TermuxInstallStage.entries.size, guidance.toSet().size)
        assertTrue(guidance.none { it.isBlank() })

        // The three stages a user most often hits say something specific.
        assertTrue(installStageGuidance(TermuxInstallStage.CHECKSUM).contains("digest"))
        assertTrue(installStageGuidance(TermuxInstallStage.DOWNLOAD).contains("connection"))
        assertTrue(installStageGuidance(TermuxInstallStage.PREFIX).contains("prefix"))
        assertTrue(installStageGuidance(null).contains("Install failed"))
    }

    @Test
    fun `the keyboard is offered only while a process is attached`() {
        assertTrue(acceptsInput(unavailable = false, running = true))
        assertFalse(acceptsInput(unavailable = false, running = false))
        assertFalse(acceptsInput(unavailable = true, running = true))
    }

    @Test
    fun `an exited process explains the status instead of printing a bare number`() {
        assertNull(exitSummary(running = true, exitStatus = null, lastError = null))

        val startupFailure = assertNotNull(exitSummary(running = false, exitStatus = 1, lastError = null))
        assertTrue(startupFailure.contains("startup failure"), startupFailure)

        val killed = assertNotNull(exitSummary(running = false, exitStatus = 9, lastError = null))
        assertTrue(killed.contains("signal 9"), killed)

        val clean = assertNotNull(exitSummary(running = false, exitStatus = 0, lastError = null))
        assertTrue(clean.contains("exited normally"), clean)

        val unknown = assertNotNull(exitSummary(running = false, exitStatus = null, lastError = null))
        assertTrue(unknown.contains("before reporting a status"), unknown)

        val withError = assertNotNull(
            exitSummary(running = false, exitStatus = 1, lastError = "warning\nsh: no such file"),
        )
        assertTrue(withError.contains("sh: no such file"), withError)
    }

    @Test
    fun `the restart action is labelled as the way out of an exited session`() {
        assertEquals("Restart", restartActionLabel(running = true))
        assertEquals("Restart terminal", restartActionLabel(running = false))
    }

    @Test
    fun `an install with no artifact is blocked with the reason rather than failing later`() {
        val blocked = installBlockedReason(
            TerminalUiState(bootstrapNote = bootstrapAvailabilityNote(TermuxBootstrapCatalog.forAbi("x86"))),
        )
        assertNotNull(blocked)
        assertTrue(blocked.contains("No verified artifact has been published"), blocked)
        assertFalse(TerminalUiState(bootstrapNote = null).canInstall.not())
    }

    @Test
    fun `an install in flight cannot be started twice`() {
        val running = TerminalUiState(provisioning = TermuxProvisioningState.Downloading(10, 1, 10))
        assertFalse(running.canInstall)
        assertTrue(assertNotNull(installBlockedReason(running)).contains("already running"))

        val extracting = TerminalUiState(provisioning = TermuxProvisioningState.Extracting(3))
        assertFalse(extracting.canInstall)
    }

    @Test
    fun `a failure carries the stage it failed at`() {
        val failed = TermuxProvisioningState.Failed(message = "checksum mismatch", stage = TermuxInstallStage.CHECKSUM)
        assertEquals(TermuxInstallStage.CHECKSUM, failed.stage)
        // The stage is optional so a state built by older call sites still compiles.
        assertNull(TermuxProvisioningState.Failed(message = "something").stage)
    }

    @Test
    fun `an installed runtime still offers no install button`() {
        val ready = TerminalUiState(
            provisioning = TermuxProvisioningState.Ready(TermuxProvisioning.AlreadyInstalled),
        )
        assertFalse(ready.canInstall)
        assertNull(installBlockedReason(ready))
    }

    @Test
    fun `an exited session shows the restart label and hides the keyboard`() {
        val exited = TerminalUiState(running = false, exitStatus = 1)
        assertFalse(exited.canType)
        assertEquals("Restart terminal", exited.restartLabel)
        assertTrue(assertNotNull(exited.exitLine).contains("status 1"))

        val live = TerminalUiState(running = true)
        assertTrue(live.canType)
        assertNull(live.exitLine)
    }
}
