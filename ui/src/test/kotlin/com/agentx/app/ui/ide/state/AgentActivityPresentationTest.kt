package com.agentx.app.ui.ide.state

import com.agentx.app.ui.ide.data.PersistedAgentMessage
import com.agentx.app.ui.ide.data.PersistedMessageKind
import com.agentx.app.ui.ide.model.ActivityItemStatus
import com.agentx.app.ui.ide.model.AgentActivityKind
import com.agentx.app.ui.ide.model.AgentTurnOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Presentation-level guarantees for the collapsible Agent Activity block:
 * real-event mapping, safe subjects, terminal parsing, headlines and
 * secret redaction. No raw JSON may reach the user-facing strings.
 */
class AgentActivityPresentationTest {

    // ───────────────────────────── Activity kinds ─────────────────────────────

    @Test
    fun `tools map to the right activity kinds`() {
        assertEquals(AgentActivityKind.TERMINAL, AgentChatPresentation.activityKind("run_command"))
        assertEquals(AgentActivityKind.FILE_READ, AgentChatPresentation.activityKind("read_file"))
        assertEquals(AgentActivityKind.FILE_WRITE, AgentChatPresentation.activityKind("write_file"))
        assertEquals(AgentActivityKind.FILE_WRITE, AgentChatPresentation.activityKind("apply_patch"))
        assertEquals(AgentActivityKind.SEARCH, AgentChatPresentation.activityKind("search_files"))
        assertEquals(AgentActivityKind.SEARCH, AgentChatPresentation.activityKind("find_symbol"))
        assertEquals(AgentActivityKind.TOOL, AgentChatPresentation.activityKind("git_status"))
        assertEquals(AgentActivityKind.TOOL, AgentChatPresentation.activityKind("unknown_thing"))
    }

    // ───────────────────────────── Subjects ─────────────────────────────

    @Test
    fun `a file tool shows the path as its subject`() {
        assertEquals(
            "src/main/AuthRepository.kt",
            AgentChatPresentation.toolSubject("read_file", "path=src/main/AuthRepository.kt"),
        )
        assertEquals(
            "config.yaml",
            AgentChatPresentation.toolSubject("write_file", "path=\"config.yaml\""),
        )
        assertEquals(
            "src/main",
            AgentChatPresentation.toolSubject("list_files", "path=src/main"),
        )
    }

    @Test
    fun `a search tool quotes its query`() {
        assertEquals(
            "\"SupabaseClient\"",
            AgentChatPresentation.toolSubject("search_files", "query=SupabaseClient"),
        )
    }

    @Test
    fun `the terminal command is extracted from the detail`() {
        assertEquals(
            "npm run build",
            AgentChatPresentation.terminalCommand("command=npm run build"),
        )
        assertEquals("git status", AgentChatPresentation.terminalCommand("command: git status"))
        assertNull(AgentChatPresentation.terminalCommand("(no arguments)"))
        assertNull(AgentChatPresentation.terminalCommand(null))
    }

    @Test
    fun `unknown tools fall back to the generic label`() {
        assertNull(AgentChatPresentation.toolSubject("git_status", "branch=main"))
        assertEquals("Checked git state", AgentChatPresentation.activityLabel("git_status"))
        assertEquals("Terminal command", AgentChatPresentation.activityLabel("run_command"))
    }

    // ───────────────────────────── Headlines ─────────────────────────────

    @Test
    fun `the running headline shows the active step and elapsed seconds`() {
        val headline = AgentChatPresentation.activityHeadline(
            outcome = AgentTurnOutcome.RUNNING,
            elapsedMillis = 7_000L,
            activeStepCount = 3,
            activeStepLabel = "Searching files",
            failedLabel = null,
        )
        assertEquals("Searching files · 7s", headline)
    }

    @Test
    fun `the completed headline freezes duration and step count`() {
        val headline = AgentChatPresentation.activityHeadline(
            outcome = AgentTurnOutcome.SUCCESS,
            elapsedMillis = 72_400L,
            activeStepCount = 14,
            activeStepLabel = "Applied a patch",
            failedLabel = null,
        )
        assertEquals("Completed · 1m 12s · 14 steps", headline)
    }

    @Test
    fun `the failed headline names the failed step`() {
        val headline = AgentChatPresentation.activityHeadline(
            outcome = AgentTurnOutcome.FAILED,
            elapsedMillis = 21_000L,
            activeStepCount = 4,
            activeStepLabel = "Running tests",
            failedLabel = "Terminal command",
        )
        assertEquals("Failed · 21.0s · Terminal command", headline)
    }

    @Test
    fun `the stopped headline stays neutral`() {
        val headline = AgentChatPresentation.activityHeadline(
            outcome = AgentTurnOutcome.STOPPED,
            elapsedMillis = 5_200L,
            activeStepCount = 2,
            activeStepLabel = "Working",
            failedLabel = null,
        )
        assertEquals("Stopped · 5.2s", headline)
    }

    @Test
    fun `the active label prefers the running step`() {
        val rows = listOf(
            row("Understanding request", ActivityItemStatus.DONE),
            row("Reading AuthRepository.kt", ActivityItemStatus.ACTIVE),
        )
        assertEquals("Reading AuthRepository.kt", AgentChatPresentation.activeStepLabel(rows))
    }

    // ───────────────────────────── Redaction ─────────────────────────────

    @Test
    fun `terminal output secrets are redacted before display`() {
        val redacted = AgentChatPresentation.redactOutput(
            "BUILD SUCCESSFUL\napi_key=sk-live-abc123\ndone",
        )
        requireNotNull(redacted)
        assertFalse(redacted.contains("sk-live-abc123"))
        assertTrue(redacted.contains("api_key=[REDACTED]"))
        assertTrue(redacted.contains("BUILD SUCCESSFUL"))
    }

    @Test
    fun `blank output produces no rows`() {
        assertNull(AgentChatPresentation.redactOutput("  "))
        assertNull(AgentChatPresentation.redactOutput(null))
        assertTrue(AgentChatPresentation.outputLines(null).isEmpty())
    }

    @Test
    fun `output lines are bounded`() {
        val many = (1..200).joinToString("\n") { "line $it" }
        val lines = AgentChatPresentation.outputLines(many)
        assertEquals(80, lines.size)
        assertEquals("line 1", lines.first())
    }

    // ───────────────────────────── Restored sessions ─────────────────────────────

    @Test
    fun `a restored tool entry becomes a structured activity row`() {
        val ui = AgentChatPresentation.persistedMessageToUi(
            PersistedAgentMessage(
                id = "t1",
                kind = PersistedMessageKind.TOOL,
                text = "run_command",
                toolName = "run_command",
                toolArguments = "command=npm run build",
                toolResult = "built in 18s",
                toolSuccess = true,
                timestampMillis = 42L,
            ),
        )

        val activity = ui.activities.single()
        assertEquals(AgentActivityKind.TERMINAL, activity.kind)
        assertEquals("npm run build", activity.label)
        assertEquals(ActivityItemStatus.DONE, activity.status)
        assertTrue(activity.outputLines.contains("built in 18s"))
    }

    @Test
    fun `a restored failed tool stays failed`() {
        val ui = AgentChatPresentation.persistedMessageToUi(
            PersistedAgentMessage(
                id = "t2",
                kind = PersistedMessageKind.TOOL,
                text = "run_command",
                toolName = "run_command",
                toolArguments = "command=./gradlew test",
                toolResult = "Tests failed",
                toolSuccess = false,
                timestampMillis = 43L,
            ),
        )

        val activity = ui.activities.single()
        assertEquals(ActivityItemStatus.FAILED, activity.status)
    }

    @Test
    fun `a restored search row quotes its query`() {
        val ui = AgentChatPresentation.persistedMessageToUi(
            PersistedAgentMessage(
                id = "t3",
                kind = PersistedMessageKind.TOOL,
                text = "search_files",
                toolName = "search_files",
                toolArguments = "query=SupabaseClient",
                toolResult = "2 matches",
                toolSuccess = true,
                timestampMillis = 44L,
            ),
        )

        val activity = ui.activities.single()
        assertEquals(AgentActivityKind.SEARCH, activity.kind)
        assertEquals("\"SupabaseClient\"", activity.label)
    }

    @Test
    fun `restored tool rows group under the following reply`() {
        val ui = AgentChatPresentation.persistedTranscriptToUi(
            listOf(
                PersistedAgentMessage("u1", PersistedMessageKind.USER, "find auth"),
                PersistedAgentMessage(
                    id = "t1",
                    kind = PersistedMessageKind.TOOL,
                    text = "search_files",
                    toolName = "search_files",
                    toolArguments = "query=auth",
                    toolResult = "2 matches",
                    toolSuccess = true,
                    timestampMillis = 20L,
                ),
                PersistedAgentMessage("a1", PersistedMessageKind.ASSISTANT, "Found it."),
            ),
        )

        val assistant = ui.single { it.kind == com.agentx.app.ui.ide.model.ChatMessageKind.ASSISTANT }
        assertEquals(1, assistant.activities.size)
        assertEquals("\"auth\"", assistant.activities.single().label)
        assertEquals(ActivityItemStatus.DONE, assistant.activities.single().status)
    }

    @Test
    fun `a restored sub-agent entry stays a system note without fabricated activity`() {
        val ui = AgentChatPresentation.persistedMessageToUi(
            PersistedAgentMessage(
                id = "s1",
                kind = PersistedMessageKind.SUB_AGENT,
                text = "Explorer finished: 3 files inspected",
                subAgentRole = "EXPLORER",
            ),
        )
        // No fabricated activity rows: restored sub-agent turns are plain notes.
        assertTrue(ui.activities.isEmpty())
        assertTrue(ui.rawText.contains("Explorer"))
    }

    private fun row(label: String, status: ActivityItemStatus) = AgentActivityUiModel(
        id = label,
        label = label,
        status = status,
        kind = AgentActivityKind.THINKING,
    )
}
