package dev.forge.ide.ui.ide.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.forge.ide.ui.ide.data.TerminalSession
import dev.forge.ide.ui.ide.model.TerminalLine
import dev.forge.ide.ui.ide.model.TerminalLineKind
import kotlinx.coroutines.launch
import java.util.UUID

data class TerminalUiState(
    val lines: List<TerminalLine> = emptyList(),
    val input: String = "",
    val running: Boolean = false,
)

/** Drives the Terminal screen. Commands are mocked; no real shell is used. */
class TerminalViewModel(
    private val workspaceId: String,
    private val session: TerminalSession,
) : ViewModel() {

    var uiState by mutableStateOf(
        TerminalUiState(
            lines = listOf(
                TerminalLine(
                    id = UUID.randomUUID().toString(),
                    text = "Forge terminal (mock) · real command execution arrives with the Workspace Runtime.",
                    kind = TerminalLineKind.SYSTEM,
                ),
            ),
        ),
    )
        private set

    fun onInputChange(text: String) {
        uiState = uiState.copy(input = text)
    }

    fun run() {
        val command = uiState.input.trim()
        if (command.isEmpty() || uiState.running) return
        uiState = uiState.copy(input = "", running = true)
        viewModelScope.launch {
            val result = runCatching { session.run(workspaceId, command) }
            uiState = result.fold(
                onSuccess = { uiState.copy(lines = uiState.lines + it.lines, running = false) },
                onFailure = {
                    uiState.copy(
                        lines = uiState.lines + TerminalLine(
                            id = UUID.randomUUID().toString(),
                            text = "error: ${it.message ?: "command failed"}",
                            kind = TerminalLineKind.ERROR,
                        ),
                        running = false,
                    )
                },
            )
        }
    }

    fun clear() {
        uiState = uiState.copy(
            lines = listOf(
                TerminalLine(
                    id = UUID.randomUUID().toString(),
                    text = "cleared",
                    kind = TerminalLineKind.SYSTEM,
                ),
            ),
        )
    }
}
