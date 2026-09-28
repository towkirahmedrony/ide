package com.agentx.app.ui.ide.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentx.app.core.ForgeError
import com.agentx.app.core.ForgeResult
import com.agentx.app.core.errorOrNull
import com.agentx.app.integrations.connection.ConnectionId
import com.agentx.app.integrations.connection.ConnectionManager
import com.agentx.app.integrations.connection.ConnectionManagerState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Connections page state.
 *
 * Lifecycle and credential handling live in the [ConnectionManager]; this
 * holder only turns user intent into manager calls.
 */
class ConnectionsViewModel(private val manager: ConnectionManager) : ViewModel() {

    val state: StateFlow<ConnectionManagerState> = manager.state

    var busyConnectionId by mutableStateOf<String?>(null)
        private set

    var message by mutableStateOf<String?>(null)
        private set

    val credentialsPersistent: Boolean get() = manager.credentialsPersistent

    init {
        viewModelScope.launch { manager.refresh() }
    }

    fun test(id: String) = operate(id) { manager.testConnection(ConnectionId(it)) }

    fun setEnabled(id: String, enabled: Boolean) =
        operate(id) { manager.setEnabled(ConnectionId(it), enabled) }

    fun delete(id: String) = operate(id) { manager.removeConnection(ConnectionId(it)) }

    fun refresh() {
        viewModelScope.launch { manager.refresh() }
    }

    fun dismissMessage() {
        message = null
    }

    private fun operate(id: String, block: suspend (String) -> ForgeResult<*, ForgeError>) {
        if (busyConnectionId != null) return
        busyConnectionId = id
        viewModelScope.launch {
            try {
                val failure = block(id).errorOrNull()
                if (failure != null) message = failure.message
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                message = error.message ?: "The connection operation failed"
            } finally {
                busyConnectionId = null
            }
        }
    }
}
