package com.agentx.app.ui.ide.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentx.app.core.ForgeResult
import com.agentx.app.core.errorOrNull
import com.agentx.app.core.valueOrNull
import com.agentx.app.integrations.connection.Connection
import com.agentx.app.integrations.connection.ConnectionAuthMethod
import com.agentx.app.integrations.connection.ConnectionCapabilities
import com.agentx.app.integrations.connection.ConnectionConfig
import com.agentx.app.integrations.connection.ConnectionDraft
import com.agentx.app.integrations.connection.ConnectionId
import com.agentx.app.integrations.connection.ConnectionManager
import com.agentx.app.integrations.connection.ConnectionType
import com.agentx.app.integrations.mcp.McpTransportKind
import com.agentx.app.integrations.mcp.META_COMMAND
import com.agentx.app.integrations.mcp.META_TRANSPORT
import com.agentx.app.integrations.mcp.mcpConnectionConfig
import com.agentx.app.ui.ide.data.NoOpOAuthBrowserLauncher
import com.agentx.app.ui.ide.data.OAuthBrowserLauncher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Add/Edit Connection form.
 *
 * OAuth is the primary method for services that support it: the form offers
 * "Connect with OAuth" and only reveals manual credentials when the user picks
 * the fallback (or when the service has no OAuth flow at all). The credential is
 * write-only: it is never read back from storage or displayed.
 */
data class ConnectionEditorState(
    val connectionId: String? = null,
    val loading: Boolean = true,
    val missing: Boolean = false,
    val displayName: String = "",
    val type: ConnectionType = ConnectionType.GITHUB,
    val endpoint: String = "",
    val authMethod: ConnectionAuthMethod = ConnectionType.GITHUB.defaultAuthMethod,
    val username: String = "",
    val credential: String = "",
    val hasStoredCredential: Boolean = false,
    val clearCredential: Boolean = false,
    val enabled: Boolean = true,
    val mcpTransport: McpTransportKind = McpTransportKind.STDIO,
    val mcpCommand: String = "",
    val saving: Boolean = false,
    val saved: Boolean = false,
    val errors: List<String> = emptyList(),
    /** How the page is authorizing: false = OAuth, true = manual fallback. */
    val manualCredentials: Boolean = false,
    val oauthSupported: Boolean = ConnectionType.GITHUB.oauthSupported,
    val oauthConfigured: Boolean = false,
    val oauthUnavailableReason: String? = null,
    /** True while the provider's authorization page is open. */
    val awaitingAuthorization: Boolean = false,
) {
    val isEditing: Boolean get() = connectionId != null

    val credentialInput: String? get() = credential.takeIf { it.isNotBlank() }

    val showEndpoint: Boolean
        get() = type.requiresEndpoint && !usesOAuth ||
            (type == ConnectionType.MCP_SERVER && mcpTransport != McpTransportKind.STDIO)

    val showUsername: Boolean get() = authMethod == ConnectionAuthMethod.USERNAME_PASSWORD

    val showCredential: Boolean get() = authMethod.requiresSecret

    val showMcpFields: Boolean get() = type == ConnectionType.MCP_SERVER

    /** True while the connection is authorized through the provider's OAuth flow. */
    val usesOAuth: Boolean get() = authMethod == ConnectionAuthMethod.OAUTH

    /** The OAuth section is shown for providers that have an official flow. */
    val showOAuthSection: Boolean get() = oauthSupported

    /** Manual credentials are offered as a fallback, never as the default. */
    val showManualCredentialFields: Boolean get() = !usesOAuth || manualCredentials

    /** The OAuth button is only usable when the build is configured for it. */
    val canAuthorizeWithOAuth: Boolean get() = oauthSupported && oauthConfigured && !saving

    companion object {
        fun from(connection: Connection): ConnectionEditorState {
            val transport = McpTransportKind.fromWire(connection.config.metadata[META_TRANSPORT])
            return ConnectionEditorState(
                connectionId = connection.id.value,
                loading = false,
                displayName = connection.displayName,
                type = connection.type,
                endpoint = connection.config.endpoint.orEmpty(),
                authMethod = connection.config.authMethod,
                username = connection.config.username.orEmpty(),
                hasStoredCredential = connection.hasCredential,
                enabled = connection.enabled,
                mcpTransport = transport,
                mcpCommand = connection.config.metadata[META_COMMAND].orEmpty(),
                oauthSupported = connection.type.oauthSupported,
                // An existing OAuth connection keeps the OAuth entry point visible.
                manualCredentials = connection.config.authMethod.isManualCredential,
            )
        }
    }

    fun toDraft(): ConnectionDraft {
        val config = if (type == ConnectionType.MCP_SERVER) {
            mcpConnectionConfig(
                transport = mcpTransport,
                endpoint = endpoint.trim().ifBlank { null },
                command = mcpCommand.trim().ifBlank { null },
                authMethod = authMethod,
            ).copy(username = username.trim().ifBlank { null })
        } else {
            ConnectionConfig(
                endpoint = endpoint.trim().ifBlank { null },
                authMethod = authMethod,
                username = username.trim().ifBlank { null },
            )
        }
        return ConnectionDraft(
            id = connectionId?.let(::ConnectionId),
            displayName = displayName.trim(),
            type = type,
            config = config,
            capabilities = ConnectionCapabilities.defaultsFor(type),
            enabled = enabled,
            credential = credentialInput,
            clearCredential = clearCredential,
        )
    }
}

class ConnectionEditorViewModel(
    private val manager: ConnectionManager,
    private val connectionId: String?,
    private val browser: OAuthBrowserLauncher = NoOpOAuthBrowserLauncher,
) : ViewModel() {

    var state by mutableStateOf(ConnectionEditorState(connectionId = connectionId))
        private set

    private var existing: Connection? = null

    init {
        viewModelScope.launch {
            val loaded = connectionId?.let { manager.connection(ConnectionId(it)) }
            existing = loaded
            state = applyAvailability(
                when {
                    loaded != null -> ConnectionEditorState.from(loaded)
                    connectionId != null -> state.copy(loading = false, missing = true)
                    else -> state.copy(loading = false)
                },
            )
        }
    }

    fun edit(block: (ConnectionEditorState) -> ConnectionEditorState) {
        if (state.saving) return
        val updated = block(state)
        state = if (updated.type == state.type) {
            updated.copy(errors = emptyList())
        } else {
            // Switching service switches how it is authorized: OAuth when the build
            // supports it, the manual form otherwise.
            val withAvailability = applyAvailability(updated.copy(errors = emptyList()))
            val oauthReady = withAvailability.oauthSupported && withAvailability.oauthConfigured
            withAvailability.copy(
                authMethod = if (oauthReady) ConnectionAuthMethod.OAUTH else ConnectionAuthMethod.ACCESS_TOKEN,
                manualCredentials = !oauthReady,
                credential = "",
                clearCredential = false,
            )
        }
    }

    fun removeStoredCredential() {
        state = state.copy(clearCredential = true, hasStoredCredential = false, credential = "")
    }

    /** The user chose the manual API key / token fallback. */
    fun useManualCredentials() {
        state = state.copy(
            manualCredentials = true,
            authMethod = when (state.authMethod) {
                ConnectionAuthMethod.OAUTH -> ConnectionAuthMethod.ACCESS_TOKEN
                else -> state.authMethod
            },
            errors = emptyList(),
        )
    }

    /** The user went back to the provider's own authorization page. */
    fun useOAuth() {
        state = state.copy(
            manualCredentials = false,
            authMethod = ConnectionAuthMethod.OAUTH,
            credential = "",
            clearCredential = false,
            errors = emptyList(),
        )
    }

    /**
     * OAuth-first path: save the record (so the grant has somewhere to live), ask
     * the manager for the provider's authorization URL and open it. Nothing is
     * marked connected here — the callback does that.
     */
    fun connectWithOAuth() {
        if (state.saving) return
        state = state.copy(saving = true, errors = emptyList())
        viewModelScope.launch {
            try {
                val connection = persist()
                if (connection == null) return@launch
                when (val started = manager.beginAuthorization(connection.id)) {
                    is ForgeResult.Success -> {
                        val opened = browser.launch(started.value.authorizationUrl)
                        if (opened) {
                            state = state.copy(saving = false, awaitingAuthorization = true)
                        } else {
                            manager.cancelAuthorization(connection.id)
                            state = state.copy(
                                saving = false,
                                awaitingAuthorization = false,
                                errors = listOf("No browser is available to open the authorization page."),
                            )
                        }
                    }

                    is ForgeResult.Failure -> state = state.copy(
                        saving = false,
                        errors = listOf(started.error.message ?: "The authorization could not be started"),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                state = state.copy(
                    saving = false,
                    errors = listOf(error.message ?: "The authorization could not be started"),
                )
            }
        }
    }

    fun save() {
        if (state.saving) return
        state = state.copy(saving = true, errors = emptyList())
        viewModelScope.launch {
            if (persist() != null) {
                state = state.copy(saving = false, saved = true)
            }
        }
    }

    /**
     * Writes the form to the manager. Returns the saved connection, or null after
     * recording the failure on the form.
     */
    private suspend fun persist(): Connection? {
        return try {
            val draft = state.toDraft()
            val result = if (existing == null) {
                manager.addConnection(draft)
            } else {
                manager.updateConnection(draft)
            }
            val failure = result.errorOrNull()
            val saved = result.valueOrNull()
            if (failure != null || saved == null) {
                val fieldErrors = failure?.details?.get("errors") as? List<*>
                state = state.copy(
                    saving = false,
                    errors = fieldErrors?.map { it.toString() }
                        ?: listOf(failure?.message ?: "The connection could not be saved"),
                )
                null
            } else {
                existing = saved
                saved
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            state = state.copy(
                saving = false,
                errors = listOf(error.message ?: "The connection could not be saved"),
            )
            null
        }
    }

    /** Fills the OAuth section from the manager's availability for the type. */
    private fun applyAvailability(state: ConnectionEditorState): ConnectionEditorState {
        val availability = manager.oauthAvailability().firstOrNull { it.type == state.type }
        return state.copy(
            oauthSupported = availability?.oauthSupported ?: state.type.oauthSupported,
            oauthConfigured = availability?.configured == true,
            oauthUnavailableReason = availability?.unavailableReason,
        )
    }
}
