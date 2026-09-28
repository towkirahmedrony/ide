package com.agentx.app.ui.ide.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentx.app.core.ForgeError
import com.agentx.app.core.ForgeResult
import com.agentx.app.core.errorOrNull
import com.agentx.app.integrations.connection.AuthorizationStart
import com.agentx.app.integrations.connection.Connection
import com.agentx.app.integrations.connection.ConnectionId
import com.agentx.app.integrations.connection.ConnectionManager
import com.agentx.app.integrations.connection.ConnectionManagerState
import com.agentx.app.integrations.connection.ConnectionStatus
import com.agentx.app.integrations.connection.ConnectionType
import com.agentx.app.integrations.connection.InstalledTool
import com.agentx.app.integrations.connection.ProviderAvailability
import com.agentx.app.integrations.connection.ProviderDescriptor
import com.agentx.app.integrations.setup.IntegrationSetupManager
import com.agentx.app.integrations.setup.ProviderSetupGuide
import com.agentx.app.integrations.setup.ProviderSetupSnapshot
import com.agentx.app.ui.ide.data.OAuthBrowserLauncher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * State for the Connections page and the service details screen.
 *
 * Every lifecycle and credential decision belongs to the [ConnectionManager]; this
 * holder turns user intent into manager calls, opens the provider's authorization
 * page in the user's browser, and mirrors connection state into what the page
 * shows. It never receives a credential: the redirect is completed by the manager
 * and the tokens stay in secure storage.
 */
class ConnectionsViewModel(
    private val manager: ConnectionManager,
    private val browser: OAuthBrowserLauncher,
    private val setup: IntegrationSetupManager? = null,
) : ViewModel() {

    val state: StateFlow<ConnectionManagerState> = manager.state

    var providers by mutableStateOf(manager.providerAvailability())
        private set

    var descriptors by mutableStateOf(manager.providerDescriptors())
        private set

    var tools by mutableStateOf(manager.tools())
        private set

    /** Connection id (or type name) with an operation in flight. */
    var busyKey by mutableStateOf<String?>(null)
        private set

    /** The connection whose authorization page is open, if any. */
    var awaitingAuthorizationId by mutableStateOf<String?>(null)
        private set

    /** Set when a connection recently reached CONNECTED, for the details screen. */
    var lastConnectedId by mutableStateOf<String?>(null)
        private set

    var message by mutableStateOf<String?>(null)
        private set

    val credentialsPersistent: Boolean get() = manager.credentialsPersistent

    var setupBusy by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch { manager.refresh() }
        viewModelScope.launch { setup?.refresh() }
        viewModelScope.launch {
            manager.state.collect { refreshDerived() }
        }
        setup?.let { source ->
            viewModelScope.launch {
                source.state.collect { refreshDerived() }
            }
        }
    }

    // --- Lookups used by both screens ---------------------------------------

    fun availabilityOf(type: ConnectionType): ProviderAvailability =
        providers.firstOrNull { it.type == type } ?: ProviderAvailability(
            type = type,
            displayName = type.displayName,
            description = type.description,
            registered = false,
            configured = false,
            authMethods = emptyList(),
            unavailableReason = "No provider is registered for ${type.displayName} in this build.",
        )

    fun descriptorOf(type: ConnectionType): ProviderDescriptor? = descriptors.firstOrNull { it.type == type }

    /** The connection the page treats as "the" connection for a service. */
    fun connectionOf(type: ConnectionType, snapshot: ConnectionManagerState): Connection? =
        snapshot.ofType(type).firstOrNull { it.enabled } ?: snapshot.ofType(type).firstOrNull()

    fun toolsOf(type: ConnectionType): List<InstalledTool> = tools.filter { it.provider == type }

    fun setupOf(type: ConnectionType, connection: Connection? = null): ProviderSetupSnapshot? =
        setup?.snapshot(type, connection)

    fun guideOf(type: ConnectionType): ProviderSetupGuide? = setup?.guide(type)

    fun callbackUri(type: ConnectionType): String = setup?.callbackUri(type).orEmpty()

    fun saveSetup(
        type: ConnectionType,
        clientId: String,
        exchangeBrokerUrl: String? = null,
    ) {
        val manager = setup ?: return
        if (setupBusy) return
        setupBusy = true
        viewModelScope.launch {
            try {
                when (val result = manager.saveSetup(type, clientId, exchangeBrokerUrl)) {
                    is ForgeResult.Success -> {
                        refreshDerived()
                        message = "${type.displayName} Client ID saved. This does not connect the account."
                    }
                    is ForgeResult.Failure -> message = result.error.message
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                message = error.message ?: "The Client ID could not be saved"
            } finally {
                setupBusy = false
            }
        }
    }

    fun clearSetup(type: ConnectionType) {
        val manager = setup ?: return
        if (setupBusy) return
        setupBusy = true
        viewModelScope.launch {
            try {
                when (val result = manager.clearSetup(type)) {
                    is ForgeResult.Success -> {
                        refreshDerived()
                        message = "${type.displayName} Client ID cleared."
                    }
                    is ForgeResult.Failure -> message = result.error.message
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                message = error.message ?: "The Client ID could not be cleared"
            } finally {
                setupBusy = false
            }
        }
    }

    /** True while this service is between "connect" and a settled state. */
    fun isAuthorizing(connection: Connection?): Boolean =
        connection?.status == ConnectionStatus.AUTHORIZING ||
            (connection != null && awaitingAuthorizationId == connection.id.value)

    fun isBusy(key: String): Boolean = busyKey == key

    // --- Actions ------------------------------------------------------------

    /** Starts the connection flow for a service the user has not connected yet. */
    fun connect(type: ConnectionType) {
        if (busyKey != null) return
        busyKey = type.name
        viewModelScope.launch {
            try {
                when (val result = manager.connect(type)) {
                    is ForgeResult.Success -> handleStart(type.name, result.value)
                    is ForgeResult.Failure -> message = result.error.message ?: failedToStart(type)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                message = error.message ?: failedToStart(type)
            } finally {
                busyKey = null
            }
        }
    }

    /** Re-runs authorization for an existing connection (reconnect / re-authorize). */
    fun reconnect(id: String) {
        if (busyKey != null) return
        busyKey = id
        viewModelScope.launch {
            try {
                when (val result = manager.beginAuthorization(ConnectionId(id))) {
                    is ForgeResult.Success -> handleStart(id, result.value)
                    is ForgeResult.Failure -> message = result.error.message ?: "The authorization could not be started"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                message = error.message ?: "The authorization could not be started"
            } finally {
                busyKey = null
            }
        }
    }

    fun cancelAuthorization(id: String) = operate(id) { manager.cancelAuthorization(ConnectionId(it)) }

    fun disconnect(id: String) = operate(id) { manager.disconnect(ConnectionId(it)) }

    fun verify(id: String) = operate(id) { manager.testConnection(ConnectionId(it)) }

    fun setEnabled(id: String, enabled: Boolean) = operate(id) { manager.setEnabled(ConnectionId(it), enabled) }

    fun delete(id: String) = operate(id) { manager.removeConnection(ConnectionId(it)) }

    fun refresh() {
        viewModelScope.launch {
            manager.refresh()
            setup?.refresh()
            refreshDerived()
        }
    }

    fun dismissMessage() {
        message = null
    }

    fun acknowledgeConnected() {
        lastConnectedId = null
    }

    // --- Internals ----------------------------------------------------------

    private fun refreshDerived() {
        providers = manager.providerAvailability()
        descriptors = manager.providerDescriptors()
        tools = manager.tools()
        val awaiting = awaitingAuthorizationId
        if (awaiting != null && manager.state.value.connection(ConnectionId(awaiting))?.status?.isAuthorizing != true) {
            // The manager settled the attempt: connected, denied, cancelled or expired.
            awaitingAuthorizationId = null
        }
    }

    private suspend fun handleStart(key: String, start: AuthorizationStart) {
        when (start) {
            is AuthorizationStart.OpenUrl -> openAuthorization(key, start)
            // No hosted page: the service is configured through the app's own form.
            is AuthorizationStart.ManualFormRequired -> {
                awaitingAuthorizationId = null
                message = start.reason
            }
        }
    }

    /**
     * Opens the provider's page. When no browser is available the attempt is
     * abandoned instead of leaving the connection stuck waiting.
     */
    private suspend fun openAuthorization(key: String, start: AuthorizationStart.OpenUrl) {
        if (browser.launch(start.authorizationUrl)) {
            awaitingAuthorizationId = start.connectionId.value
            return
        }
        awaitingAuthorizationId = null
        manager.cancelAuthorization(start.connectionId)
        message = "No browser is available to open the authorization page."
    }

    private fun operate(id: String, block: suspend (String) -> ForgeResult<*, ForgeError>) {
        if (busyKey != null) return
        busyKey = id
        viewModelScope.launch {
            try {
                val failure = block(id).errorOrNull()
                if (failure != null) {
                    message = failure.message
                } else {
                    val connection = manager.connection(ConnectionId(id))
                    if (connection?.status == ConnectionStatus.CONNECTED) lastConnectedId = id
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                message = error.message ?: "The connection operation failed"
            } finally {
                busyKey = null
            }
        }
    }

    private fun failedToStart(type: ConnectionType): String =
        "The ${type.displayName} authorization could not be started"
}
