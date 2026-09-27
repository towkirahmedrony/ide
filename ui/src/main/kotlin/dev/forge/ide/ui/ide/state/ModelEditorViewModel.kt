package dev.forge.ide.ui.ide.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.forge.ide.core.errorOrNull
import dev.forge.ide.model.manager.ModelManager
import dev.forge.ide.model.preset.ColabRuntimeConfig
import dev.forge.ide.model.preset.EndpointConfig
import dev.forge.ide.model.preset.EndpointDiscoveryMode
import dev.forge.ide.model.preset.HealthCheckConfig
import dev.forge.ide.model.preset.ModelApiProtocol
import dev.forge.ide.model.preset.ModelPreset
import dev.forge.ide.model.preset.ModelProviderType
import dev.forge.ide.model.preset.TunnelConfig
import dev.forge.ide.model.preset.TunnelType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Add/Edit Model form.
 *
 * The form only carries what the user types. The credential is write-only: it is
 * never read back from storage, and the preset keeps a reference instead of the
 * secret. Validation is not duplicated here — the domain validates when the
 * preset is saved, and the resulting field problems are shown verbatim.
 */
data class ModelEditorState(
    /** Null while adding a model. */
    val presetId: String? = null,
    val loading: Boolean = true,
    val missing: Boolean = false,
    val displayName: String = "",
    val providerType: ModelProviderType = ModelProviderType.GOOGLE_COLAB,
    val modelIdentifier: String = "",
    val apiProtocol: ModelApiProtocol = ModelApiProtocol.OPENAI_COMPATIBLE,
    val apiBasePath: String = ModelApiProtocol.OPENAI_COMPATIBLE.defaultApiBasePath,
    val startupScript: String = "",
    val serverPort: String = "",
    val endpointMode: EndpointDiscoveryMode = EndpointDiscoveryMode.RUNTIME_OUTPUT,
    val explicitEndpoint: String = "",
    val tunnelType: TunnelType = TunnelType.CLOUDFLARE_QUICK,
    val tunnelMarker: String = TunnelConfig.DEFAULT_MARKER,
    val healthPath: String = "",
    val colabNotebookUrl: String = "",
    val credential: String = "",
    val hasStoredCredential: Boolean = false,
    val clearCredential: Boolean = false,
    val enabled: Boolean = true,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val errors: List<String> = emptyList(),
) {
    val isEditing: Boolean get() = presetId != null

    /** A credential the user typed, if any. Never persisted inside the preset. */
    val credentialInput: String? get() = credential.takeIf { it.isNotBlank() }

    val requireNotebookUrl: Boolean get() = providerType == ModelProviderType.GOOGLE_COLAB

    val requireEndpointField: Boolean
        get() = endpointMode == EndpointDiscoveryMode.CONFIGURED_ENDPOINT ||
            (endpointMode == EndpointDiscoveryMode.RUNTIME_OUTPUT && tunnelType == TunnelType.MANUAL)

    companion object {
        /** Populates the form from a saved preset when editing. */
        fun from(preset: ModelPreset): ModelEditorState = ModelEditorState(
            presetId = preset.id,
            loading = false,
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
            colabNotebookUrl = preset.colab?.notebookUrl.orEmpty(),
            hasStoredCredential = preset.credentialRef != null,
            enabled = preset.enabled,
        )
    }

    /**
     * Builds the preset to save. [existing] preserves identity, creation time and
     * the credential reference when editing.
     */
    fun toPreset(existing: ModelPreset?): ModelPreset {
        val port = serverPort.trim().toIntOrNull()
        val health = existing?.health ?: HealthCheckConfig()
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
            health = health.copy(path = healthPath.trim().ifBlank { null }),
            colab = if (providerType == ModelProviderType.GOOGLE_COLAB) {
                ColabRuntimeConfig(notebookUrl = colabNotebookUrl.trim())
            } else {
                null
            },
            enabled = enabled,
            createdAtMillis = existing?.createdAtMillis ?: 0L,
            updatedAtMillis = existing?.updatedAtMillis ?: 0L,
        )
    }
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

    /** The form edits one field at a time; the screen supplies the update. */
    fun edit(block: (ModelEditorState) -> ModelEditorState) {
        if (state.saving) return
        state = block(state).copy(errors = emptyList())
    }

    fun removeStoredCredential() {
        state = state.copy(clearCredential = true, hasStoredCredential = false)
    }

    fun save() {
        if (state.saving) return
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
                        errors = fieldErrors?.map { it.toString() } ?: listOf(failure.message),
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
