package dev.forge.ide.ui.ide.data.mock

import android.webkit.WebView
import dev.forge.ide.ui.ide.data.AgentSession
import dev.forge.ide.ui.ide.data.AgentStreamEvent
import dev.forge.ide.ui.ide.data.GitRepository
import dev.forge.ide.ui.ide.data.ModelRunnerBrowserHost
import dev.forge.ide.ui.ide.data.TerminalSession
import dev.forge.ide.ui.ide.data.WorkspacePicker
import dev.forge.ide.ui.ide.model.AgentActivity
import dev.forge.ide.ui.ide.model.AgentActivityStatus
import dev.forge.ide.ui.ide.model.GitSnapshot
import dev.forge.ide.ui.ide.model.TerminalLine
import dev.forge.ide.ui.ide.model.TerminalLineKind
import dev.forge.ide.ui.ide.model.TerminalResult
import dev.forge.ide.workspace.DefaultWorkspaceManager
import dev.forge.ide.workspace.WorkspaceManager
import dev.forge.ide.workspace.memory.InMemoryWorkspaceBackend
import dev.forge.ide.workspace.memory.InMemoryWorkspaceMetadataStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * Stand-ins for layers that are not implemented yet (agent, terminal, git), plus
 * an in-memory workspace binding. The workspace runtime itself is real domain
 * code; only its backend is synthetic here.
 */

/** An in-memory workspace manager used by previews and tests. */
fun mockWorkspaceManager(): WorkspaceManager = DefaultWorkspaceManager(
    backend = InMemoryWorkspaceBackend(),
    store = InMemoryWorkspaceMetadataStore(),
)

/** A picker that fabricates a unique handle each time, for previews and tests. */
fun mockWorkspacePicker(): WorkspacePicker {
    var counter = 0
    return WorkspacePicker { onPicked ->
        counter += 1
        onPicked("demo-workspace-$counter")
    }
}

/** Returns a canned response so the agent panel can be demonstrated. */
class MockAgentSession : AgentSession {

    override suspend fun run(input: String, onEvent: (AgentStreamEvent) -> Unit) {
        onEvent(AgentStreamEvent.Activity(AgentActivity(AgentActivityStatus.THINKING, "Thinking")))
        delay(700)

        onEvent(
            AgentStreamEvent.Activity(
                AgentActivity(AgentActivityStatus.USING_TOOL, "Using tool · read_file"),
            ),
        )
        delay(800)

        onEvent(
            AgentStreamEvent.Activity(
                AgentActivity(AgentActivityStatus.WAITING, "Waiting for model…"),
            ),
        )
        delay(600)

        val reply = buildString {
            append("Mock agent received: \"")
            append(input.take(120))
            append("\".\n\n")
            append("The Agent Core is not implemented yet, so this reply is generated ")
            append("locally to demonstrate the panel. Connect a model provider and the ")
            append("real loop will replace this without any UI changes.")
        }

        reply.chunked(24).forEach { chunk ->
            onEvent(AgentStreamEvent.Chunk(chunk))
            delay(28)
        }

        onEvent(AgentStreamEvent.Activity(AgentActivity(AgentActivityStatus.COMPLETED, "Completed")))
        onEvent(AgentStreamEvent.Completed(reply))
    }
}

/** Returns mock output. Never touches a real shell. */
class MockTerminalSession : TerminalSession {

    override suspend fun run(workspaceId: String, command: String): TerminalResult {
        val trimmed = command.trim()
        val lines = mutableListOf<TerminalLine>()

        lines += TerminalLine(
            id = UUID.randomUUID().toString(),
            text = "\$ ${trimmed}",
            kind = TerminalLineKind.INPUT,
        )

        delay(220)

        lines += TerminalLine(
            id = UUID.randomUUID().toString(),
            text = "[mock] real shell execution is not available yet",
            kind = TerminalLineKind.SYSTEM,
        )

        when {
            trimmed.isEmpty() -> Unit
            trimmed == "ls" || trimmed == "dir" -> lines += TerminalLine(
                id = UUID.randomUUID().toString(),
                text = "[mock] use the Files tab to browse the workspace",
                kind = TerminalLineKind.OUTPUT,
            )

            trimmed.startsWith("echo ") -> lines += TerminalLine(
                id = UUID.randomUUID().toString(),
                text = trimmed.removePrefix("echo "),
                kind = TerminalLineKind.OUTPUT,
            )

            trimmed == "pwd" -> lines += TerminalLine(
                id = UUID.randomUUID().toString(),
                text = "workspace://$workspaceId",
                kind = TerminalLineKind.OUTPUT,
            )

            else -> lines += TerminalLine(
                id = UUID.randomUUID().toString(),
                text = "command not executed (mock terminal): $trimmed",
                kind = TerminalLineKind.ERROR,
            )
        }

        return TerminalResult(lines = lines, exitCode = 0)
    }
}

/** Repository status is unavailable until the Git layer is implemented. */
class MockGitRepository : GitRepository {

    override suspend fun snapshot(workspaceId: String): GitSnapshot = GitSnapshot(available = false)
}

/**
 * A Model Runner browser that never exists. Previews and tests get the same
 * "the platform has no browser here" path the real host reports when a WebView
 * cannot be created, so the screen's fallback is exercised rather than bypassed.
 */
class MockModelRunnerBrowser : ModelRunnerBrowserHost {

    private val blocked = MutableStateFlow<String?>(null)

    override val available: Boolean = false

    override val blockedNavigation: StateFlow<String?> = blocked

    override fun view(presetId: String, url: String, outputMarker: String): WebView? = null

    override fun onSessionDetached(presetId: String) = Unit

    override fun release() = Unit

    override fun saveState(out: android.os.Bundle) = Unit

    override fun restoreState(state: android.os.Bundle?) = Unit

    override fun openExternally(url: String) = Unit

    override fun clearBlockedNavigation() {
        blocked.value = null
    }
}
