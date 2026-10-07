package com.agentx.app.ui.ide.state

import com.agentx.app.core.ForgeError
import com.agentx.app.core.ForgeErrorCode
import com.agentx.app.core.ForgeResult
import com.agentx.app.core.failure
import com.agentx.app.core.success
import com.agentx.app.integrations.connection.AuthorizationStart
import com.agentx.app.integrations.connection.AuthorizedConnection
import com.agentx.app.integrations.connection.Connection
import com.agentx.app.integrations.connection.ConnectionAuthMethod
import com.agentx.app.integrations.connection.ConnectionAuthorizationError
import com.agentx.app.integrations.connection.ConnectionCapability
import com.agentx.app.integrations.connection.ConnectionDraft
import com.agentx.app.integrations.connection.ConnectionId
import com.agentx.app.integrations.connection.ConnectionManager
import com.agentx.app.integrations.connection.ConnectionManagerState
import com.agentx.app.integrations.connection.ConnectionStatus
import com.agentx.app.integrations.connection.ConnectionTestResult
import com.agentx.app.integrations.connection.ConnectionType
import com.agentx.app.integrations.connection.DeviceAuthorization
import com.agentx.app.integrations.connection.InstalledTool
import com.agentx.app.integrations.connection.ProviderAvailability
import com.agentx.app.integrations.connection.ProviderDescriptor
import com.agentx.app.integrations.oauth.DeviceFlowState
import com.agentx.app.ui.ide.data.OAuthBrowserLauncher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Connections page and its GitHub device flow.
 *
 * These tests exist because the page used to crash the moment it opened: the
 * ViewModel's `init` block starts StateFlow collectors, and `viewModelScope` runs
 * on `Dispatchers.Main.immediate`, so the very first emission can call
 * `refreshDerived()` while the constructor is still running. When the device-flow
 * state was declared *after* `init`, that emission read a Compose `State` delegate
 * that had not been assigned yet and threw a `NullPointerException` from
 * `getDeviceAuthorization()`. The tests below construct the ViewModel under an
 * eager (`UnconfinedTestDispatcher`) main dispatcher, which reproduces exactly
 * that ordering.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionsViewModelTest {

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * A manager that only implements what the Connections page reads while
     * opening, plus the device-flow entry points. Everything else is unreachable
     * from these tests and fails loudly if a test starts depending on it.
     */
    private class FakeConnectionManager(
        connections: List<Connection> = emptyList(),
    ) : ConnectionManager {

        private val mutableState =
            MutableStateFlow(ConnectionManagerState(loading = false, connections = connections))
        override val state: StateFlow<ConnectionManagerState> = mutableState

        override val credentialsPersistent: Boolean = true

        var deviceAuthorizationSupported: Boolean = true
        var deviceAuthorizationStart: ForgeResult<DeviceAuthorization, ForgeError> =
            failure(ForgeError(ForgeErrorCode.CONNECTION_OPERATION_FAILED, "not configured"))
        var completeDeviceResult: ForgeResult<Connection, ForgeError> =
            failure(ForgeError(ForgeErrorCode.CONNECTION_OPERATION_FAILED, "not configured"))

        /** When set, [completeDeviceAuthorization] waits for it before settling. */
        var completeDeviceGate: CompletableDeferred<Unit>? = null
        val cancelled = mutableListOf<ConnectionId>()

        override fun providerAvailability(): List<ProviderAvailability> = listOf(
            ProviderAvailability(
                type = ConnectionType.GITHUB,
                displayName = "GitHub",
                description = "GitHub repositories, pull requests and issues.",
                registered = true,
                configured = true,
                authMethods = listOf(ConnectionAuthMethod.OAUTH),
            ),
        )

        override fun providerDescriptors(): List<ProviderDescriptor> = emptyList()

        override fun tools(): List<InstalledTool> = emptyList()

        override fun enabledToolNames(): List<String> = emptyList()

        override suspend fun refresh() {
            mutableState.value = mutableState.value.copy(loading = false)
        }

        override suspend fun connection(id: ConnectionId): Connection? = mutableState.value.connection(id)

        override suspend fun list(): List<Connection> = mutableState.value.connections

        override fun supportsDeviceAuthorization(type: ConnectionType): Boolean = deviceAuthorizationSupported

        override suspend fun beginDeviceAuthorization(
            type: ConnectionType,
            displayName: String?,
        ): ForgeResult<DeviceAuthorization, ForgeError> = deviceAuthorizationStart

        override suspend fun completeDeviceAuthorization(
            id: ConnectionId,
            onState: suspend (DeviceFlowState) -> Unit,
        ): ForgeResult<Connection, ForgeError> {
            completeDeviceGate?.await()
            onState(DeviceFlowState.POLLING)
            val result = completeDeviceResult
            onState(if (result is ForgeResult.Success) DeviceFlowState.CONNECTED else DeviceFlowState.AUTH_ERROR)
            return result
        }

        override suspend fun cancelAuthorization(id: ConnectionId): ForgeResult<Connection, ForgeError> {
            cancelled += id
            return failure(ForgeError(ForgeErrorCode.CONNECTION_OPERATION_FAILED, "cancelled"))
        }

        override fun status(id: ConnectionId): ConnectionStatus? = mutableState.value.connection(id)?.status

        // Unreachable from these tests; fail loudly instead of inventing a result.
        override suspend fun addConnection(draft: ConnectionDraft): ForgeResult<Connection, ForgeError> = unreachable()
        override suspend fun updateConnection(draft: ConnectionDraft): ForgeResult<Connection, ForgeError> = unreachable()
        override suspend fun removeConnection(id: ConnectionId): ForgeResult<Unit, ForgeError> = unreachable()
        override suspend fun setEnabled(
            id: ConnectionId,
            enabled: Boolean,
        ): ForgeResult<Connection, ForgeError> = unreachable()

        override suspend fun testConnection(id: ConnectionId): ForgeResult<ConnectionTestResult, ForgeError> = unreachable()

        override suspend fun connect(
            type: ConnectionType,
            displayName: String?,
        ): ForgeResult<AuthorizationStart, ForgeError> = unreachable()

        override suspend fun beginAuthorization(id: ConnectionId): ForgeResult<AuthorizationStart, ForgeError> = unreachable()

        override suspend fun completeAuthorization(callbackUri: String): ForgeResult<Connection, ForgeError> = unreachable()

        override suspend fun disconnect(id: ConnectionId): ForgeResult<Connection, ForgeError> = unreachable()

        override suspend fun refreshAuthorization(id: ConnectionId): ForgeResult<Connection, ForgeError> = unreachable()

        override suspend fun authorize(
            type: ConnectionType,
            capability: ConnectionCapability,
            connectionId: ConnectionId?,
        ): ForgeResult<AuthorizedConnection, ConnectionAuthorizationError> = unreachable()

        private fun unreachable(): Nothing =
            throw UnsupportedOperationException("The Connections page does not call this in the test")
    }

    private fun authorization(id: String = "gh-1") = DeviceAuthorization(
        connectionId = ConnectionId(id),
        type = ConnectionType.GITHUB,
        displayName = "GitHub",
        userCode = "ABCD-1234",
        verificationUri = "https://github.com/login/device",
        expiresAtMillis = 0L,
        intervalSeconds = 5L,
    )

    private fun model(manager: FakeConnectionManager): ConnectionsViewModel =
        ConnectionsViewModel(manager = manager, browser = OAuthBrowserLauncher { true })

    @Test
    fun `opening the page with no device flow in progress does not crash`() {
        val model = model(FakeConnectionManager())

        assertEquals(DeviceFlowState.DISCONNECTED, model.deviceFlowState)
        assertNull(model.deviceAuthorization)
    }

    @Test
    fun `the initial state emission populates derived state without touching the device code`() {
        val model = model(FakeConnectionManager())

        assertEquals(listOf(ConnectionType.GITHUB), model.providers.map { it.type })
        assertEquals(DeviceFlowState.DISCONNECTED, model.deviceFlowState)
        assertNull(model.deviceAuthorization)
        assertNull(model.message)
    }

    @Test
    fun `refreshing before any device flow keeps the device state disconnected`() {
        val model = model(FakeConnectionManager())

        model.refresh()

        assertEquals(DeviceFlowState.DISCONNECTED, model.deviceFlowState)
        assertNull(model.deviceAuthorization)
        assertNull(model.message)
    }

    @Test
    fun `starting a device flow publishes the user code and waits for the user`() {
        val manager = FakeConnectionManager()
        val gate = CompletableDeferred<Unit>()
        manager.completeDeviceGate = gate
        manager.deviceAuthorizationStart = success(authorization())
        val model = model(manager)

        model.connect(ConnectionType.GITHUB)

        assertEquals(DeviceFlowState.WAITING_FOR_USER, model.deviceFlowState)
        assertEquals("ABCD-1234", model.deviceAuthorization?.userCode)
        assertEquals("gh-1", model.deviceAuthorization?.connectionId?.value)
        assertNull(model.busyKey, "the card is no longer busy once the code is shown")

        gate.complete(Unit)
    }

    @Test
    fun `a completed device flow clears the code and reports the connection`() {
        val manager = FakeConnectionManager()
        manager.deviceAuthorizationStart = success(authorization())
        manager.completeDeviceResult = success(
            Connection(
                id = ConnectionId("gh-1"),
                displayName = "GitHub",
                type = ConnectionType.GITHUB,
                status = ConnectionStatus.CONNECTED,
            ),
        )
        val model = model(manager)

        model.connect(ConnectionType.GITHUB)

        assertEquals(DeviceFlowState.CONNECTED, model.deviceFlowState)
        assertNull(model.deviceAuthorization)
        assertEquals("gh-1", model.lastConnectedId)
    }

    @Test
    fun `a device flow that the provider refuses settles into an error`() {
        val manager = FakeConnectionManager()
        manager.deviceAuthorizationStart = success(authorization())
        manager.completeDeviceResult = failure(
            ForgeError(ForgeErrorCode.CONNECTION_OAUTH_DENIED, "The user denied the request"),
        )
        val model = model(manager)

        model.connect(ConnectionType.GITHUB)

        assertEquals(DeviceFlowState.AUTH_ERROR, model.deviceFlowState)
        assertEquals("The user denied the request", model.message)
    }

    @Test
    fun `a device flow that cannot start settles into an error`() {
        val manager = FakeConnectionManager()
        manager.deviceAuthorizationStart = failure(
            ForgeError(ForgeErrorCode.CONNECTION_OAUTH_UNAVAILABLE, "Device flow unavailable"),
        )
        val model = model(manager)

        model.connect(ConnectionType.GITHUB)

        assertEquals(DeviceFlowState.AUTH_ERROR, model.deviceFlowState)
        assertEquals("Device flow unavailable", model.message)
    }

    @Test
    fun `cancelling a device flow returns the service to disconnected`() {
        val manager = FakeConnectionManager()
        val gate = CompletableDeferred<Unit>()
        manager.completeDeviceGate = gate
        manager.deviceAuthorizationStart = success(authorization())
        val model = model(manager)
        model.connect(ConnectionType.GITHUB)
        assertTrue(model.deviceFlowState.isInProgress)

        model.cancelDeviceFlow()

        assertEquals(DeviceFlowState.DISCONNECTED, model.deviceFlowState)
        assertNull(model.deviceAuthorization)
        assertEquals(listOf(ConnectionId("gh-1")), manager.cancelled)
    }
}
