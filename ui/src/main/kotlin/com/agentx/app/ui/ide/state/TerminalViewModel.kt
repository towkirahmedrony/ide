package com.agentx.app.ui.ide.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentx.app.ui.ide.model.TerminalLine
import com.agentx.app.ui.ide.model.TerminalLineKind
import com.agentx.app.workspace.ProcessEnvironment
import com.agentx.app.workspace.WorkspaceManager
import com.agentx.app.workspace.process.InteractiveShellSession
import com.agentx.app.workspace.process.ProcessSignal
import com.agentx.app.workspace.process.ProcessStreamKind
import com.agentx.app.workspace.process.ShellLaunchRequest
import com.agentx.app.workspace.process.TerminalEvent
import com.agentx.app.workspace.process.TerminalSessionManager
import com.agentx.app.workspace.process.TerminalSessionState
import com.agentx.app.workspace.process.WorkspaceShellLocation
import com.agentx.app.workspace.process.WorkspaceShellLocations
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.UUID

data class TerminalUiState(
    val lines: List<TerminalLine> = emptyList(),
    val input: String = "",
    val workingDirectory: String = "",
    val sessionState: TerminalSessionState = TerminalSessionState.IDLE,
    val exitCode: Int? = null,
    val limitation: String? = null,
    val busy: Boolean = false,
) {
    val statusLabel: String
        get() = when (sessionState) {
            TerminalSessionState.IDLE -> "idle"
            TerminalSessionState.STARTING -> "starting"
            TerminalSessionState.RUNNING -> "running"
            TerminalSessionState.STOPPED -> "stopped"
            TerminalSessionState.EXITED -> "exited" + (exitCode?.let { " $it" } ?: "")
            TerminalSessionState.FAILED -> "failed"
            TerminalSessionState.CANCELLED -> "cancelled"
        }

    val shellAlive: Boolean get() = sessionState == TerminalSessionState.RUNNING
}

/**
 * Drives the Terminal screen. Owns no OS process: [TerminalSessionManager]
 * keeps the interactive shell alive across configuration changes.
 *
 * Human commands run directly. Agent-issued commands must still go through
 * the Tool Router as COMMAND_EXECUTION.
 */
class TerminalViewModel(
    private val workspaceId: String,
    private val workspaceManager: WorkspaceManager,
    private val sessions: TerminalSessionManager,
) : ViewModel() {

    var uiState by mutableStateOf(TerminalUiState())
        private set

    private var session: InteractiveShellSession? = null
    private var collectJob: Job? = null
    private val history = mutableListOf<String>()
    private var historyIndex: Int = -1
    private var seenEventIds = LinkedHashSet<String>()

    init {
        attach()
    }

    fun onInputChange(text: String) {
        uiState = uiState.copy(input = text)
        historyIndex = -1
    }

    fun submit() {
        val command = uiState.input
        if (command.isEmpty()) {
            session?.submit("")
            return
        }
        val current = session
        if (current == null || !uiState.shellAlive) {
            append(systemLine("shell is not running"))
            return
        }
        append(TerminalLine(id = newId(), text = "$ $command", kind = TerminalLineKind.INPUT))
        if (history.lastOrNull() != command) history += command
        historyIndex = -1
        uiState = uiState.copy(input = "")
        current.submit(command)
    }

    fun interrupt() {
        session?.sendControl(ProcessSignal.INTERRUPT)
    }

    fun eof() {
        session?.sendControl(ProcessSignal.EOF)
    }

    fun insertTab() {
        uiState = uiState.copy(input = uiState.input + "\t")
    }

    fun historyPrevious() {
        if (history.isEmpty()) return
        val next = if (historyIndex < 0) history.lastIndex else (historyIndex - 1).coerceAtLeast(0)
        historyIndex = next
        uiState = uiState.copy(input = history[next])
    }

    fun historyNext() {
        if (history.isEmpty() || historyIndex < 0) return
        val next = historyIndex + 1
        if (next >= history.size) {
            historyIndex = -1
            uiState = uiState.copy(input = "")
        } else {
            historyIndex = next
            uiState = uiState.copy(input = history[next])
        }
    }

    fun clear() {
        session?.clearBuffer()
        seenEventIds.clear()
        uiState = uiState.copy(lines = listOf(systemLine("cleared")))
    }

    fun restart() {
        val current = session ?: return
        viewModelScope.launch {
            uiState = uiState.copy(busy = true, lines = emptyList())
            seenEventIds.clear()
            current.restart(launchRequest())
            hydrate(current)
            uiState = uiState.copy(busy = false)
        }
    }

    override fun onCleared() {
        collectJob?.cancel()
        collectJob = null
        session = null
    }

    private fun attach() {
        collectJob?.cancel()
        collectJob = viewModelScope.launch {
            uiState = uiState.copy(busy = true)
            val opened = sessions.sessionForWorkspace(workspaceId, launchRequest())
            session = opened
            hydrate(opened)
            uiState = uiState.copy(busy = false)
            launch {
                opened.events.collect { event -> appendEvent(event) }
            }
            launch {
                opened.state.collect { state ->
                    uiState = uiState.copy(sessionState = state, exitCode = opened.exitCode)
                }
            }
            launch {
                opened.workingDirectory.collect { cwd ->
                    uiState = uiState.copy(workingDirectory = cwd.orEmpty())
                }
            }
        }
    }

    private fun hydrate(opened: InteractiveShellSession) {
        val snapshot = opened.snapshot()
        seenEventIds.clear()
        val lines = snapshot.lines.map { event ->
            seenEventIds += event.id
            event.toLine()
        }.toMutableList()
        if (snapshot.droppedCount > 0) {
            lines.add(0, systemLine("… ${snapshot.droppedCount} older lines dropped"))
        }
        uiState = uiState.copy(
            lines = lines,
            workingDirectory = snapshot.workingDirectory.orEmpty(),
            sessionState = snapshot.state,
            exitCode = snapshot.exitCode,
            limitation = snapshot.workspaceLimitation,
        )
    }

    private fun launchRequest(): ShellLaunchRequest {
        val metadata = workspaceManager.current
            ?.takeIf { it.workspace.id.value == workspaceId }
            ?.workspace
            ?.metadata
        val location = WorkspaceShellLocations.resolve(
            handle = null,
            displayLocation = metadata?.displayLocation,
        )
        val working = (location as? WorkspaceShellLocation.Filesystem)?.path
        return ShellLaunchRequest(
            workingDirectory = working,
            workspaceLocation = location,
            environment = ProcessEnvironment(inheritParent = false),
            extraEnvironment = mapOf(
                "CODER_WORKSPACE_ID" to workspaceId,
                "CODER_WORKSPACE_NAME" to (metadata?.name ?: workspaceId),
            ),
        )
    }

    private fun appendEvent(event: TerminalEvent) {
        if (!seenEventIds.add(event.id)) return
        append(event.toLine())
    }

    private fun append(line: TerminalLine) {
        val next = uiState.lines + line
        uiState = uiState.copy(
            lines = if (next.size > MAX_UI_LINES) next.takeLast(MAX_UI_LINES) else next,
        )
    }

    private fun TerminalEvent.toLine(): TerminalLine = TerminalLine(
        id = id,
        text = text,
        kind = when (kind) {
            ProcessStreamKind.STDOUT -> TerminalLineKind.OUTPUT
            ProcessStreamKind.STDERR -> TerminalLineKind.ERROR
            ProcessStreamKind.SYSTEM -> TerminalLineKind.SYSTEM
        },
    )

    private fun systemLine(text: String) = TerminalLine(
        id = newId(),
        text = text,
        kind = TerminalLineKind.SYSTEM,
    )

    private fun newId(): String = UUID.randomUUID().toString()

    companion object {
        private const val MAX_UI_LINES = 2_000
    }
}
