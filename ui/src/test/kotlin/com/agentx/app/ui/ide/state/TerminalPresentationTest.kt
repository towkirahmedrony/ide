package com.agentx.app.ui.ide.state

import com.agentx.app.termux.TerminalSessionState
import com.agentx.app.termux.TermuxBootstrapCatalog
import com.agentx.app.termux.TermuxInstallStage
import com.agentx.app.termux.TermuxProvisioning
import com.agentx.app.termux.TermuxProvisioningState
import com.agentx.app.ubuntu.UbuntuProjectBinding
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
        assertTrue(acceptsInput(unavailable = false, state = TerminalSessionState.RUNNING))
        // A shell that is still coming up cannot receive typing either.
        assertFalse(acceptsInput(unavailable = false, state = TerminalSessionState.STARTING))
        assertFalse(acceptsInput(unavailable = false, state = TerminalSessionState.STOPPED))
        assertFalse(acceptsInput(unavailable = false, state = TerminalSessionState.FAILED))
        assertFalse(acceptsInput(unavailable = false, state = null))
        assertFalse(acceptsInput(unavailable = true, state = TerminalSessionState.RUNNING))
    }

    @Test
    fun `an exited process explains the status instead of printing a bare number`() {
        // A shell that is still starting has nothing to report; an exit line there would make a
        // healthy launch look like a failure.
        assertNull(exitSummary(TerminalSessionState.STARTING, exitStatus = 1, failure = null))
        assertNull(exitSummary(TerminalSessionState.RUNNING, exitStatus = 1, failure = null))

        val startupFailure = assertNotNull(exitSummary(TerminalSessionState.STOPPED, 1, null))
        assertTrue(startupFailure.contains("startup failure"), startupFailure)

        val killed = assertNotNull(exitSummary(TerminalSessionState.STOPPED, 9, null))
        assertTrue(killed.contains("signal 9"), killed)

        val clean = assertNotNull(exitSummary(TerminalSessionState.STOPPED, 0, null))
        assertTrue(clean.contains("exited normally"), clean)

        val withError = assertNotNull(
            exitSummary(TerminalSessionState.STOPPED, 1, null, lastError = "warning\nsh: no such file"),
        )
        assertTrue(withError.contains("sh: no such file"), withError)
    }

    @Test
    fun `a shell that never started is never described as one that stopped`() {
        // The regression this guards: the old wording blamed a shell for stopping when in fact no
        // session existed at all, which made the failure unreadable and hid the way out.
        val failed = assertNotNull(
            exitSummary(
                state = TerminalSessionState.FAILED,
                exitStatus = 1,
                failure = "could not start /data/app/.../libproot.so",
            ),
        )
        assertTrue(failed.contains("could not start"), failed)
        assertFalse(failed.contains("before reporting a status"), failed)

        // Even with no reason recorded, it must not claim the shell ran and then stopped.
        val bare = assertNotNull(exitSummary(TerminalSessionState.FAILED, 1, failure = null))
        assertFalse(bare.contains("before reporting a status"), bare)
        assertTrue(bare.contains("before it started"), bare)
    }

    @Test
    fun `a failed terminal offers a heading and something to read`() {
        assertEquals("Terminal failed to start", terminalFailureHeading(TerminalSessionState.FAILED))
        assertEquals("The shell session has ended", terminalFailureHeading(TerminalSessionState.STOPPED))
        assertNull(terminalFailureHeading(TerminalSessionState.RUNNING))
        assertNull(terminalFailureHeading(TerminalSessionState.STARTING))
        // No session at all still says so rather than leaving the panel blank.
        assertTrue(noSessionSummary().isNotBlank())
    }

    @Test
    fun `a shared-storage project that fell back to the guest home asks for storage access`() {
        // The exact state the terminal lands in when All files access is missing: the binding fell
        // back to the guest home, and the project really is a folder in shared storage.
        val home = UbuntuProjectBinding.Home("The project path is not a readable directory from this app.")

        assertTrue(
            workspaceAccessRequired(
                binding = home,
                projectLocation = "/storage/emulated/0/AgentX/demo",
                allFilesAccessGranted = false,
            ),
        )
        // The same project once the access is granted is not a permission problem any more.
        assertFalse(
            workspaceAccessRequired(
                binding = home,
                projectLocation = "/storage/emulated/0/AgentX/demo",
                allFilesAccessGranted = true,
            ),
        )
        // Nor is its SAF spelling a different answer: the tree names the same shared-storage folder.
        assertTrue(
            workspaceAccessRequired(
                binding = home,
                projectLocation = "content://com.android.externalstorage.documents/tree/primary%3AAgentX%2Fdemo",
                allFilesAccessGranted = false,
            ),
        )
    }

    @Test
    fun `a bound project and a project with another problem never ask for storage access`() {
        val home = UbuntuProjectBinding.Home("no project is open")

        // Bound at the guest project root: nothing to ask for.
        assertFalse(
            workspaceAccessRequired(
                binding = UbuntuProjectBinding.Direct("/storage/emulated/0/AgentX/demo"),
                projectLocation = "/storage/emulated/0/AgentX/demo",
                allFilesAccessGranted = false,
            ),
        )
        // Fell back to the guest home, but the project is app-private, so no storage grant would
        // change the outcome — it keeps its own reason instead of being reported as a permission.
        assertFalse(
            workspaceAccessRequired(
                binding = home,
                projectLocation = "/data/data/com.agentx.app/files/projects/legacy",
                allFilesAccessGranted = false,
            ),
        )
        // No project at all, and a location that is not a filesystem path.
        assertFalse(
            workspaceAccessRequired(
                binding = home,
                projectLocation = null,
                allFilesAccessGranted = false,
            ),
        )
        assertFalse(
            workspaceAccessRequired(
                binding = UbuntuProjectBinding.Home("a cloud tree has no path"),
                projectLocation = "content://com.google.android.apps.docs.storage/tree/primary%3Ax",
                allFilesAccessGranted = false,
            ),
        )
    }

    @Test
    fun `the missing-access notice and its action are user-facing wording`() {
        // Neither names an internal component: the user sees a shell that is not in their project,
        // and one action that changes it.
        assertTrue(WORKSPACE_ACCESS_REQUIRED_NOTE.contains("without the active project"))
        assertTrue(WORKSPACE_ACCESS_REQUIRED_NOTE.contains("storage access is not granted"))
        assertFalse(WORKSPACE_ACCESS_REQUIRED_NOTE.contains("PRoot", ignoreCase = true))
        assertFalse(WORKSPACE_ACCESS_REQUIRED_NOTE.contains("bind", ignoreCase = true))
        assertFalse(WORKSPACE_ACCESS_REQUIRED_NOTE.contains("workspace", ignoreCase = true))
        assertTrue(WORKSPACE_ACCESS_ACTION_LABEL.isNotBlank())
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
        val exited = TerminalUiState(sessionState = TerminalSessionState.STOPPED, exitStatus = 1)
        assertFalse(exited.canType)
        assertEquals("Restart terminal", exited.restartLabel)
        assertTrue(assertNotNull(exited.exitLine).contains("status 1"))

        val live = TerminalUiState(sessionState = TerminalSessionState.RUNNING)
        assertTrue(live.canType)
        assertNull(live.exitLine)
    }

    @Test
    fun `a failed session stays readable and restartable from the ui state alone`() {
        val failed = TerminalUiState(
            sessionState = TerminalSessionState.FAILED,
            failure = "the pty did not report a shell pid",
        )
        assertFalse(failed.canType)
        assertEquals("failed", failed.statusLabel)
        assertEquals("Terminal failed to start", failed.failureHeading)
        assertTrue(assertNotNull(failed.exitLine).contains("did not report a shell pid"))
        // Restart is the whole recovery path, so it must still be offered.
        assertEquals("Restart terminal", failed.restartLabel)
    }

    @Test
    fun `a session that is still starting offers no exit line and no keyboard`() {
        val starting = TerminalUiState(sessionState = TerminalSessionState.STARTING)
        assertEquals("starting", starting.statusLabel)
        assertNull(starting.exitLine)
        assertNull(starting.failureHeading)
        assertFalse(starting.canType)
        assertFalse(starting.needsSession)
    }

    @Test
    fun `no session at all is a state the screen can describe and leave`() {
        val empty = TerminalUiState()
        // The screen notices and asks for one rather than waiting for a status that never arrives.
        assertTrue(empty.needsSession)
        assertNull(empty.sessionState)
        assertFalse(empty.canType)
        assertNull(empty.failureHeading)
        // There is no shell to describe, so there is no exit line — and in particular no claim that
        // a shell stopped. That wording was only ever produced by this state.
        assertNull(empty.exitLine)
        assertFalse(exitLineOrNoSession(empty).contains("before reporting a status"))
    }

    private fun exitLineOrNoSession(state: TerminalUiState): String =
        state.exitLine ?: noSessionSummary()
}
