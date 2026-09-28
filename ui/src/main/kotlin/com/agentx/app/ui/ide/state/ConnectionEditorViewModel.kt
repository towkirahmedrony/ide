package com.agentx.app.ui.ide.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentx.app.core.errorOrNull
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Add/Edit Connection form.
 *
 * The credential is write-only: it is never read back from storage. OAuth is
 * listed as a method but cannot succeed — there is no implementation yet.
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
) {
    val isEditing: Boolean get() = connectionId != null

    val credentialInput: String? get() = credential.takeIf { it.isNotBlank() }

    val showEndpoint: Boolean
        get() = type.requiresEndpoint ||
            (type == ConnectionType.MCP_SERVER && mcpTransport != McpTransportKind.STDIO)

    val showUsername: Boolean get() = authMethod == ConnectionAuthMethod.USERNAME_PASSWORD

    val showCredential: Boolean get() = authMethod.requiresSecret

    val showMcpFields: Boolean get() = type == ConnectionType.MCP_SERVER

    val oauthUnavailable: Boolean get() = authMethod == ConnectionAuthMethod.OAUTH

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
) : ViewModel() {

    var state by mutableStateOf(ConnectionEditorState(connectionId = connectionId))
        private set

    private var existing: Connection? = null

    init {
        viewModelScope.launch {
            val loaded = connectionId?.let { manager.connection(ConnectionId(it)) }
            existing = loaded
            state = when {
                loaded != null -> ConnectionEditorState.from(loaded)
                connectionId != null -> state.copy(loading = false, missing = true)
                else -> state.copy(loading = false)
            }
        }
    }

    fun edit(block: (ConnectionEditorState) -> ConnectionEditorState) {
        if (state.saving) return
        state = block(state).copy(errors = emptyList())
    }

    fun removeStoredCredential() {
        state = state.copy(clearCredential = true, hasStoredCredential = false, credential = "")
    }

    fun save() {
        if (state.saving) return
        state = state.copy(saving = true, errors = emptyList())
        viewModelScope.launch {
            try {
                val draft = state.toDraft()
                val result = if (existing == null) {
                    manager.addConnection(draft)
                } else {
                    manager.updateConnection(draft)
                }
                val failure = result.errorOrNull()
                if (failure == null) {
                    state = state.copy(saving = false, saved = true)
                } else {
                    val fieldErrors = failure.details["errors"] as? List<*>
                    state = state.copy(
                        saving = false,
                        errors = fieldErrors?.map { it.toString() }
                            ?: listOf(failure.message ?: "The connection could not be saved"),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                state = state.copy(
                    saving = false,
                    errors = listOf(error.message ?: "The connection could not be saved"),
                )
            }
        }
    }
}
