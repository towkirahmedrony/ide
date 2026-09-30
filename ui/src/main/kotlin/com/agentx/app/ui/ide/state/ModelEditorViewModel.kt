package com.agentx.app.ui.ide.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentx.app.core.errorOrNull
import com.agentx.app.core.valueOrNull
import com.agentx.app.model.connect.ModelConnectOutcome
import com.agentx.app.model.connect.ModelConnectPhase
import com.agentx.app.model.connect.ModelConnectRequest
import com.agentx.app.model.connect.ModelSetupKind
import com.agentx.app.model.manager.ModelManager
import com.agentx.app.model.preset.ColabRuntimeConfig
import com.agentx.app.model.preset.EndpointConfig
import com.agentx.app.model.preset.EndpointDiscoveryMode
import com.agentx.app.model.preset.HealthCheckConfig
import com.agentx.app.model.preset.ModelApiProtocol
import com.agentx.app.model.preset.ModelPreset
import com.agentx.app.model.preset.ModelProviderType
import com.agentx.app.model.preset.TunnelConfig
import com.agentx.app.model.preset.TunnelType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Add/Edit Model form.
 *
 * Quick Connect is the default path. Advanced fields stay available but
 * collapsed. The credential is write-only: it is never read back from storage.
 */
data class ModelEditorState(
    val presetId: String? = null,
    val loading: Boolean = true,
    val missing: Boolean = false,
    val setupKind: ModelSetupKind = ModelSetupKind.CUSTOM,
    val displayName: String = "",
    val providerType: ModelProviderType = ModelProviderType.REMOTE_OPENAI_COMPATIBLE,
    val modelIdentifier: String = "",
    val apiProtocol: ModelApiProtocol = ModelApiProtocol.OPENAI_COMPATIBLE,
    val apiBasePath: String = ModelApiProtocol.OPENAI_COMPATIBLE.defaultApiBasePath,
    val startupScript: String = "",
    val serverPort: String = "",
    val endpointMode: EndpointDiscoveryMode = EndpointDiscoveryMode.CONFIGURED_ENDPOINT,
    val explicitEndpoint: String = "",
    val tunnelType: TunnelType = TunnelType.NONE,
    val tunnelMarker: String = TunnelConfig.DEFAULT_MARKER,
    val healthPath: String = "",
    val healthTimeoutMillis: String = HealthCheckConfig.DEFAULT_TIMEOUT_MILLIS.toString(),
    val colabNotebookUrl: String = "",
    val credential: String = "",
    val hasStoredCredential: Boolean = false,
    val clearCredential: Boolean = false,
    val enabled: Boolean = true,
    val saving: Boolean = false,
    val connecting: Boolean = false,
    val connectPhase: ModelConnectPhase? = null,
    val saved: Boolean = false,
    val connected: Boolean = false,
    val connectedSummary: ConnectedSummary? = null,
    val availableModels: List<String> = emptyList(),
    val advancedOpen: Boolean = false,
    val errors: List<String> = emptyList(),
) {
    val isEditing: Boolean get() = presetId != null

    val credentialInput: String? get() = credential.takeIf { it.isNotBlank() }

    val requireNotebookUrl: Boolean get() = providerType == ModelProviderType.GOOGLE_COLAB

    val requireEndpointField: Boolean
        get() = setupKind.showsEndpointField && (
            endpointMode == EndpointDiscoveryMode.CONFIGURED_ENDPOINT ||
                (endpointMode == EndpointDiscoveryMode.RUNTIME_OUTPUT && tunnelType == TunnelType.MANUAL)
            )

    val busy: Boolean get() = saving || connecting

    data class ConnectedSummary(
        val displayName: String,
        val state: String,
        val protocol: String,
        val endpoint: String,
        val modelId: String,
    )

    companion object {
        fun from(preset: ModelPreset): ModelEditorState = ModelEditorState(
            presetId = preset.id,
            loading = false,
            setupKind = ModelSetupKind.fromId(preset.setupKind),
            displayName = preset.displayName,
            providerType = preset.providerType,
            modelIdentifier = preset.modelIdentifier,
            apiProtocol = preset.apiProtocol,
            apiBasePath = preset.apiBasePath,
            startupScript = preset.startupScript,
            serverPort = preset.serverPort?.toString().orEmpty(),
            endpointMode = preset.endpoint.mode,
            explicitEndpoint = preset.endpoint.explicitUrl.orEmpty(),
            tunnelType = preset.tunnel.type,
            tunnelMarker = preset.tunnel.marker,
            healthPath = preset.health.path.orEmpty(),
            healthTimeoutMillis = preset.health.timeoutMillis.toString(),
            colabNotebookUrl = preset.colab?.notebookUrl.orEmpty(),
            hasStoredCredential = preset.credentialRef != null,
            enabled = preset.enabled,
            advancedOpen = preset.endpoint.mode != EndpointDiscoveryMode.CONFIGURED_ENDPOINT ||
                preset.providerType == ModelProviderType.GOOGLE_COLAB,
        )
    }

    fun toPreset(existing: ModelPreset?): ModelPreset {
        val port = serverPort.trim().toIntOrNull()
        val health = existing?.health ?: HealthCheckConfig()
        val timeout = healthTimeoutMillis.trim().toLongOrNull() ?: health.timeoutMillis
        return ModelPreset(
            id = presetId ?: "",
            displayName = displayName.trim(),
            providerType = providerType,
            modelIdentifier = modelIdentifier.trim(),
            apiProtocol = apiProtocol,
            apiBasePath = apiBasePath.trim().ifBlank { apiProtocol.defaultApiBasePath },
            credentialRef = existing?.credentialRef?.takeUnless { clearCredential },
            startupScript = startupScript,
            serverPort = port,
            endpoint = EndpointConfig(
                mode = endpointMode,
                explicitUrl = explicitEndpoint.trim().ifBlank { null },
            ),
            tunnel = TunnelConfig(
                type = tunnelType,
                marker = tunnelMarker.trim().ifBlank { TunnelConfig.DEFAULT_MARKER },
            ),
            health = health.copy(
                path = healthPath.trim().ifBlank { null },
                timeoutMillis = timeout,
            ),
            colab = if (providerType == ModelProviderType.GOOGLE_COLAB) {
                ColabRuntimeConfig(notebookUrl = colabNotebookUrl.trim())
            } else {
                null
            },
            enabled = enabled,
            setupKind = setupKind.id,
            createdAtMillis = existing?.createdAtMillis ?: 0L,
            updatedAtMillis = existing?.updatedAtMillis ?: 0L,
        )
    }

    fun toConnectRequest(): ModelConnectRequest = ModelConnectRequest(
        presetId = presetId,
        displayName = displayName.trim(),
        setupKind = setupKind,
        endpoint = explicitEndpoint,
        credential = credentialInput,
        clearCredential = clearCredential,
        modelIdentifier = modelIdentifier.trim(),
        apiProtocol = apiProtocol.takeIf { advancedOpen },
        apiBasePath = apiBasePath.trim().takeIf { advancedOpen && it.isNotBlank() },
        providerType = providerType.takeIf { advancedOpen },
        healthPath = healthPath.trim().ifBlank { null },
        healthTimeoutMillis = healthTimeoutMillis.trim().toLongOrNull(),
        serverPort = serverPort.trim().toIntOrNull(),
        enabled = enabled,
        startupScript = startupScript,
        tunnelType = tunnelType.takeIf { advancedOpen },
        tunnelMarker = tunnelMarker.takeIf { advancedOpen },
        colabNotebookUrl = colabNotebookUrl.trim().ifBlank { null },
        endpointMode = endpointMode.takeIf { advancedOpen },
    )
}

class ModelEditorViewModel(
    private val manager: ModelManager,
    private val presetId: String?,
) : ViewModel() {

    var state by mutableStateOf(ModelEditorState(presetId = presetId))
        private set

    private var existing: ModelPreset? = null

    init {
        viewModelScope.launch {
            val loaded = presetId?.let { manager.preset(it) }
            existing = loaded
            state = when {
                loaded != null -> ModelEditorState.from(loaded)
                presetId != null -> state.copy(loading = false, missing = true)
                else -> state.copy(loading = false)
            }
        }
    }

    fun edit(block: (ModelEditorState) -> ModelEditorState) {
        if (state.busy) return
        state = block(state).copy(errors = emptyList(), availableModels = emptyList())
    }

    fun toggleAdvanced() {
        if (state.busy) return
        state = state.copy(advancedOpen = !state.advancedOpen)
    }

    fun removeStoredCredential() {
        state = state.copy(clearCredential = true, hasStoredCredential = false)
    }

    fun connect() {
        if (state.busy) return
        state = state.copy(
            connecting = true,
            errors = emptyList(),
            availableModels = emptyList(),
            connectPhase = ModelConnectPhase.CONNECTING,
            connected = false,
            connectedSummary = null,
        )
        viewModelScope.launch {
            try {
                val result = manager.connectQuick(state.toConnectRequest()) { phase ->
                    state = state.copy(connectPhase = phase)
                }
                val failure = result.errorOrNull()
                if (failure != null) {
                    val fieldErrors = failure.details["errors"] as? List<*>
                    state = state.copy(
                        connecting = false,
                        connectPhase = null,
                        errors = fieldErrors?.map { it.toString() }
                            ?: listOf(failure.message ?: "Could not connect"),
                    )
                    return@launch
                }
                when (val outcome = result.valueOrNull()) {
                    is ModelConnectOutcome.NeedsModelChoice -> {
                        state = state.copy(
                            connecting = false,
                            connectPhase = null,
                            availableModels = outcome.models,
                            advancedOpen = true,
                            errors = listOf(outcome.message),
                        )
                    }
                    is ModelConnectOutcome.Connected -> {
                        existing = outcome.preset
                        state = state.copy(
                            connecting = false,
                            connectPhase = ModelConnectPhase.CONNECTED,
                            connected = true,
                            saved = true,
                            presetId = outcome.preset.id,
                            modelIdentifier = outcome.preset.modelIdentifier,
                            explicitEndpoint = outcome.preset.endpoint.explicitUrl.orEmpty(),
                            apiBasePath = outcome.preset.apiBasePath,
                            apiProtocol = outcome.preset.apiProtocol,
                            providerType = outcome.preset.providerType,
                            setupKind = ModelSetupKind.fromId(outcome.preset.setupKind),
                            hasStoredCredential = outcome.preset.credentialRef != null,
                            connectedSummary = ModelEditorState.ConnectedSummary(
                                displayName = outcome.preset.displayName,
                                state = outcome.status.state.displayName,
                                protocol = outcome.preset.apiProtocol.displayName,
                                endpoint = outcome.status.endpoint?.url
                                    ?: outcome.preset.endpoint.explicitUrl.orEmpty(),
                                modelId = outcome.preset.modelIdentifier,
                            ),
                        )
                    }
                    null -> {
                        state = state.copy(
                            connecting = false,
                            connectPhase = null,
                            errors = listOf("Could not connect"),
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                state = state.copy(
                    connecting = false,
                    connectPhase = null,
                    errors = listOf(error.message ?: "Could not connect"),
                )
            }
        }
    }

    fun save() {
        if (state.busy) return
        state = state.copy(saving = true, errors = emptyList())
        viewModelScope.launch {
            try {
                val preset = state.toPreset(existing)
                val credential = state.credentialInput
                val result = if (existing == null) {
                    manager.createPreset(preset, credential)
                } else {
                    manager.updatePreset(preset, credential, clearCredential = state.clearCredential)
                }
                val failure = result.errorOrNull()
                if (failure == null) {
                    state = state.copy(saving = false, saved = true)
                } else {
                    val fieldErrors = failure.details["errors"] as? List<*>
                    state = state.copy(
                        saving = false,
                        errors = fieldErrors?.map { it.toString() }
                            ?: listOf(failure.message ?: "The model could not be saved"),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                state = state.copy(
                    saving = false,
                    errors = listOf(error.message ?: "The model could not be saved"),
                )
            }
        }
    }
}
