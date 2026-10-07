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
import com.agentx.app.integrations.connection.DeviceAuthorization
import com.agentx.app.integrations.connection.InstalledTool
import com.agentx.app.integrations.connection.ProviderAvailability
import com.agentx.app.integrations.connection.ProviderDescriptor
import com.agentx.app.integrations.setup.IntegrationSetupManager
import com.agentx.app.integrations.setup.ProviderSetupGuide
import com.agentx.app.integrations.setup.ProviderSetupSnapshot
import com.agentx.app.integrations.oauth.DeviceFlowState
import com.agentx.app.ui.ide.data.OAuthBrowserLauncher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
    /** When false the screen shows the code first and the user opens the page. */
    private val openVerificationAutomatically: Boolean = true,
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

    // --- Device flow (GitHub) state -----------------------------------------
    //
    // Declared before `init` on purpose. `viewModelScope` runs on
    // `Dispatchers.Main.immediate`, so the StateFlow collectors below can emit the
    // current value synchronously while this constructor is still running. Every
    // property `refreshDerived()` reads must therefore already hold its delegate
    // before those collectors start, or the emission dereferences a null delegate.

    /**
     * The typed device-flow state for the service being connected. It is separate
     * from the connection's saved status: a service can be AUTHORIZING while the
     * attempt is WAITING_FOR_USER or POLLING. It never carries a token.
     */
    var deviceFlowState by mutableStateOf(DeviceFlowState.DISCONNECTED)
        private set

    /** The user code and verification URI to show while the user authorizes. */
    var deviceAuthorization by mutableStateOf<DeviceAuthorization?>(null)
        private set

    private var deviceFlowJob: Job? = null
    private var deviceFlowConnectionId: String? = null

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

    // --- Device flow (GitHub) -----------------------------------------------

    /** True when this service can be authorized with a device code. */
    fun supportsDeviceAuthorization(type: ConnectionType): Boolean =
        manager.supportsDeviceAuthorization(type)

    /** Opens the provider page where the user enters the device code. */
    fun openDeviceVerificationPage(): Boolean {
        val authorization = deviceAuthorization ?: return false
        return browser.launch(authorization.verificationUri)
    }

    // --- Actions ------------------------------------------------------------

    /** Starts the connection flow for a service the user has not connected yet. */
    fun connect(type: ConnectionType) {
        if (busyKey != null || deviceFlowState.isInProgress) return
        // GitHub authorizes with a device code: no browser redirect is involved, so
        // the redirect flow is not started for it.
        if (manager.supportsDeviceAuthorization(type)) {
            startDeviceFlow(type)
            return
        }
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
        if (busyKey != null || deviceFlowState.isInProgress) return
        // A device-flow service re-authorizes with a new device code: reusing the
        // existing connection id, never a second connection record.
        val type = manager.state.value.connection(ConnectionId(id))?.type
        if (type != null && manager.supportsDeviceAuthorization(type)) {
            startDeviceFlow(type)
            return
        }
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

    fun cancelAuthorization(id: String) {
        if (deviceFlowConnectionId == id && deviceFlowState.isInProgress) {
            cancelDeviceFlow()
            return
        }
        operate(id) { manager.cancelAuthorization(ConnectionId(it)) }
    }

    fun disconnect(id: String) {
        if (deviceFlowConnectionId == id) stopDeviceFlow()
        operate(id) { manager.disconnect(ConnectionId(it)) }
    }

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
        // Once a device authorization settles the code is no longer usable; keep the
        // card from showing a stale code after a connect, cancel or disconnect.
        if (deviceAuthorization != null && !deviceFlowState.isInProgress) {
            deviceAuthorization = null
        }
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

    /**
     * Requests a device code, shows the user code and verification URI, and starts
     * polling. The access token is stored by the manager; nothing here ever sees it.
     */
    private fun startDeviceFlow(type: ConnectionType) {
        busyKey = type.name
        deviceAuthorization = null
        deviceFlowState = DeviceFlowState.AUTHORIZING
        viewModelScope.launch {
            try {
                when (val result = manager.beginDeviceAuthorization(type)) {
                    is ForgeResult.Success -> {
                        val authorization = result.value
                        deviceAuthorization = authorization
                        deviceFlowConnectionId = authorization.connectionId.value
                        deviceFlowState = DeviceFlowState.WAITING_FOR_USER
                        // Best effort: point the user at GitHub's page. The code and
                        // URL stay on screen either way.
                        if (openVerificationAutomatically) browser.launch(authorization.verificationUri)
                        busyKey = null
                        pollDeviceAuthorization(authorization.connectionId.value)
                    }

                    is ForgeResult.Failure -> {
                        deviceFlowState = DeviceFlowState.AUTH_ERROR
                        message = result.error.message
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                deviceFlowState = DeviceFlowState.AUTH_ERROR
                message = error.message ?: failedToStart(type)
            } finally {
                busyKey = null
            }
        }
    }

    /**
     * Runs the manager's polling loop. Cancelling [deviceFlowJob] stops it cleanly:
     * the manager's polling coroutine is the same one, so nothing keeps running in
     * the background.
     */
    private fun pollDeviceAuthorization(connectionId: String) {
        deviceFlowJob?.cancel()
        deviceFlowJob = viewModelScope.launch {
            try {
                val result = manager.completeDeviceAuthorization(ConnectionId(connectionId)) { state ->
                    deviceFlowState = state
                }
                when (result) {
                    is ForgeResult.Success -> {
                        deviceAuthorization = null
                        deviceFlowConnectionId = null
                        deviceFlowState = DeviceFlowState.CONNECTED
                        lastConnectedId = connectionId
                    }

                    is ForgeResult.Failure -> {
                        // The manager reported the terminal state through the callback;
                        // the message explains why.
                        message = result.error.message
                    }
                }
                refreshDerived()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                deviceAuthorization = null
                deviceFlowState = DeviceFlowState.AUTH_ERROR
                message = error.message ?: "The GitHub authorization failed"
            } finally {
                deviceFlowJob = null
            }
        }
    }

    /** Stops polling and forgets the attempt without touching the saved connection. */
    private fun stopDeviceFlow() {
        deviceFlowJob?.cancel()
        deviceFlowJob = null
        deviceFlowConnectionId = null
        deviceAuthorization = null
        deviceFlowState = DeviceFlowState.DISCONNECTED
    }

    /** Cancels a running device authorization and resets the service to not connected. */
    fun cancelDeviceFlow() {
        val id = deviceFlowConnectionId
        stopDeviceFlow()
        if (id != null) {
            viewModelScope.launch {
                manager.cancelAuthorization(ConnectionId(id))
                refreshDerived()
            }
        }
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
