package dev.forge.ide.ui.ide.state

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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Models page state.
 *
 * All lifecycle logic lives in the [ModelManager]; this holder only turns user
 * intent into manager calls and reports failures. Nothing here starts a runtime
 * or talks to the gateway directly.
 */
class ModelsViewModel(private val manager: ModelManager) : ViewModel() {

    val state: StateFlow<ModelManagerState> = manager.state

    /** Preset whose operation is in flight, so only that row shows progress. */
    var busyPresetId by mutableStateOf<String?>(null)
        private set

    /** Last failure, shown as a dismissible message. */
    var message by mutableStateOf<String?>(null)
        private set

    val credentialsPersistent: Boolean get() = manager.credentialsPersistent

    init {
        viewModelScope.launch { manager.refresh() }
    }

    /** "Use": select this model and bring it online without restarting a healthy one. */
    fun use(id: String) = operate(id) { manager.selectModel(it) }

    fun start(id: String) = operate(id) { manager.startModel(it) }

    fun stop(id: String) = operate(id) { manager.stopModel(it) }

    fun reconnect(id: String) = operate(id) { manager.reconnectModel(it) }

    fun checkHealth(id: String) = operate(id) { manager.checkModelHealth(it) }

    fun delete(id: String) = operate(id) { manager.deletePreset(it) }

    fun refresh() {
        viewModelScope.launch { manager.refresh() }
    }

    fun dismissMessage() {
        message = null
    }

    private fun operate(id: String, block: suspend (String) -> ForgeResult<*, ForgeError>) {
        if (busyPresetId != null) return
        busyPresetId = id
        viewModelScope.launch {
            try {
                val failure = block(id).errorOrNull()
                if (failure != null) message = failure.message
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                message = error.message ?: "The model operation failed"
            } finally {
                busyPresetId = null
            }
        }
    }
}
