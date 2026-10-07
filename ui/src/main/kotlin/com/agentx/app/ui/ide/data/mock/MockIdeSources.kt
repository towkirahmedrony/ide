package com.agentx.app.ui.ide.data.mock

import android.webkit.WebView
import com.agentx.app.ui.ide.data.AgentSession
import com.agentx.app.ui.ide.data.AgentStreamEvent
import com.agentx.app.ui.ide.data.ModelRunnerBrowserHost
import com.agentx.app.ui.ide.data.WorkspacePicker
import com.agentx.app.ui.ide.model.AgentActivity
import com.agentx.app.ui.ide.model.AgentActivityStatus
import com.agentx.app.workspace.AgentxProjectRoot
import com.agentx.app.workspace.DefaultWorkspaceManager
import com.agentx.app.workspace.ManagedProjectDirectory
import com.agentx.app.workspace.WorkspaceManager
import com.agentx.app.workspace.memory.InMemoryWorkspaceBackend
import com.agentx.app.workspace.memory.InMemoryWorkspaceMetadataStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Stand-ins for layers that are not implemented yet (agent, git), plus
 * an in-memory workspace binding. The workspace runtime itself is real domain
 * code; only its backend is synthetic here. The terminal uses the real
 * process runtime.
 */

/** An in-memory workspace manager used by previews and tests. */
fun mockWorkspaceManager(
    projectsRoot: File = AgentxProjectRoot.under(
        File(System.getProperty("java.io.tmpdir") ?: ".", "agentx-demo"),
    ),
): WorkspaceManager = DefaultWorkspaceManager(
    backend = InMemoryWorkspaceBackend(),
    store = InMemoryWorkspaceMetadataStore(),
    projects = ManagedProjectDirectory(projectsRoot),
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

    override suspend fun run(
        input: String,
        onEvent: (AgentStreamEvent) -> Unit,
        workspaceId: String?,
        selectedFile: String?,
    ) {
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
