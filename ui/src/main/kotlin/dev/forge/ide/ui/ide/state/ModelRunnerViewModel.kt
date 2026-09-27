package dev.forge.ide.ui.ide.state

import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.forge.ide.core.ForgeError
import dev.forge.ide.core.ForgeResult
import dev.forge.ide.core.errorOrNull
import dev.forge.ide.model.manager.ModelManager
import dev.forge.ide.model.manager.ModelManagerState
import dev.forge.ide.model.runtime.RuntimeOutputBuffer
import dev.forge.ide.ui.ide.data.ModelRunnerBrowserHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Model Runner state holder.
 *
 * It owns the browser session bookkeeping and the captured runtime output, and
 * forwards connection actions to the [ModelManager]. Session state is reported to
 * the manager as information only: the model connection never depends on whether
 * a browser is on screen.
 */
class ModelRunnerViewModel(
    private val manager: ModelManager,
    private val browser: ModelRunnerBrowserHost,
    private val runtimeOutput: RuntimeOutputBuffer,
    val presetId: String,
) : ViewModel() {

    val state: StateFlow<ModelManagerState> = manager.state

    val blockedNavigation: StateFlow<String?> = browser.blockedNavigation

    val capturedLines: StateFlow<List<String>> = runtimeOutput.lines

    val browserAvailable: Boolean get() = browser.available

    var busy by mutableStateOf(false)
        private set

    var message by mutableStateOf<String?>(null)
        private set

    init {
        // Reached from a restored process: make sure the preset is loaded.
        viewModelScope.launch {
            if (manager.state.value.presets.none { it.id == presetId }) manager.refresh()
        }
    }

    fun reconnect() = operate { manager.reconnectModel(presetId) }

    fun check() = operate { manager.checkModelHealth(presetId) }

    fun stop() = operate { manager.stopModel(presetId) }

    fun onSessionAttached(attached: Boolean) {
        manager.onRunnerSessionChanged(presetId, attached)
    }

    fun createView(notebookUrl: String, outputMarker: String): WebView? =
        if (notebookUrl.isBlank()) null else browser.view(presetId, notebookUrl, outputMarker)

    fun openExternally(notebookUrl: String) {
        if (notebookUrl.isBlank()) return
        browser.openExternally(notebookUrl)
    }

    /** Captures pasted runtime output so endpoint detection can read it. */
    fun captureOutput(raw: String) {
        val lines = raw.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) {
            message = "Nothing was captured."
            return
        }
        runtimeOutput.appendAll(lines)
        message = "Captured ${lines.size} line(s). Reconnect to look for the endpoint."
    }

    fun clearOutput() {
        runtimeOutput.clear()
    }

    fun clearBlocked() = browser.clearBlockedNavigation()

    fun dismissMessage() {
        message = null
    }

    private fun operate(block: suspend () -> ForgeResult<*, ForgeError>) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                block().errorOrNull()?.let { message = it.message }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                message = error.message ?: "The model operation failed"
            } finally {
                busy = false
            }
        }
    }
}
