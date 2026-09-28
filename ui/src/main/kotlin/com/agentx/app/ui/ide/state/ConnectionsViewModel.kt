package com.agentx.app.ui.ide.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentx.app.core.ForgeError
import com.agentx.app.core.ForgeResult
import com.agentx.app.core.errorOrNull
import com.agentx.app.core.valueOrNull
import com.agentx.app.integrations.connection.ConnectionId
import com.agentx.app.integrations.connection.ConnectionManager
import com.agentx.app.integrations.connection.ConnectionManagerState
import com.agentx.app.integrations.connection.ConnectionOAuthAvailability
import com.agentx.app.integrations.connection.ConnectionType
import com.agentx.app.ui.ide.data.OAuthBrowserLauncher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Connections page state.
 *
 * Lifecycle and credential handling live in the [ConnectionManager]; this holder
 * turns user intent into manager calls and opens the provider's authorization
 * page. It never sees a token: the callback is completed by the manager, and the
 * credentials stay in secure storage.
 */
class ConnectionsViewModel(
    private val manager: ConnectionManager,
    private val browser: OAuthBrowserLauncher,
) : ViewModel() {

    val state: StateFlow<ConnectionManagerState> = manager.state

    var availability by mutableStateOf(manager.oauthAvailability())
        private set

    var busyConnectionId by mutableStateOf<String?>(null)
        private set

    /** The connection whose authorization page is open, if any. */
    var awaitingAuthorizationId by mutableStateOf<String?>(null)
        private set

    var message by mutableStateOf<String?>(null)
        private set

    val credentialsPersistent: Boolean get() = manager.credentialsPersistent

    init {
        viewModelScope.launch { manager.refresh() }
        viewModelScope.launch {
            manager.state.collect { availability = manager.oauthAvailability() }
        }
        viewModelScope.launch {
            manager.state.collect { snapshot ->
                if (awaitingAuthorizationId != null &&
                    snapshot.connections.firstOrNull { it.id.value == awaitingAuthorizationId }?.status?.isAuthorizing != true
                ) {
                    // The manager settled the attempt (connected, denied, cancelled).
                    awaitingAuthorizationId = null
                }
            }
        }
    }

    /** Starts the OAuth flow for a service the user has not connected yet. */
    fun connect(type: ConnectionType) {
        if (busyConnectionId != null) return
        busyConnectionId = type.name
        viewModelScope.launch {
            try {
                when (val result = manager.connect(type)) {
                    is ForgeResult.Success -> openAuthorization(result.value.connectionId.value, result.value.authorizationUrl)
                    is ForgeResult.Failure -> message = result.error.message ?: "The authorization could not be started"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                message = error.message ?: "The authorization could not be started"
            } finally {
                busyConnectionId = null
            }
        }
    }

    /** Re-runs authorization for an existing connection (reconnect / expired). */
    fun reconnect(id: String) {
        if (busyConnectionId != null) return
        busyConnectionId = id
        viewModelScope.launch {
            try {
                when (val result = manager.beginAuthorization(ConnectionId(id))) {
                    is ForgeResult.Success -> openAuthorization(id, result.value.authorizationUrl)
                    is ForgeResult.Failure -> message = result.error.message ?: "The authorization could not be started"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                message = error.message ?: "The authorization could not be started"
            } finally {
                busyConnectionId = null
            }
        }
    }

    fun cancelAuthorization(id: String) = operate(id) { manager.cancelAuthorization(ConnectionId(it)) }

    fun disconnect(id: String) = operate(id) { manager.disconnect(ConnectionId(it)) }

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

    /**
     * Opens the provider's page. When no browser is available the attempt is
     * abandoned instead of leaving the connection stuck in "authorizing".
     */
    private suspend fun openAuthorization(connectionId: String, url: String) {
        if (browser.launch(url)) {
            awaitingAuthorizationId = connectionId
            return
        }
        awaitingAuthorizationId = null
        manager.cancelAuthorization(ConnectionId(connectionId))
        message = "No browser is available to open the authorization page."
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

    /** Availability entry for [type], used by the editor's OAuth-first section. */
    fun availabilityOf(type: ConnectionType): ConnectionOAuthAvailability = manager.oauthAvailability()
        .firstOrNull { it.type == type }
        ?: ConnectionOAuthAvailability(
            type = type,
            displayName = type.displayName,
            oauthSupported = type.oauthSupported,
            configured = false,
            unavailableReason = "OAuth is not available for ${type.displayName}.",
        )
}
