package com.agentx.app.model.manager

import com.agentx.app.core.ForgeError
import com.agentx.app.core.ForgeErrorCode
import com.agentx.app.core.ForgeResult
import com.agentx.app.core.failure
import com.agentx.app.core.success
import com.agentx.app.model.ModelConfig
import com.agentx.app.model.connect.ModelConnectOutcome
import com.agentx.app.model.connect.ModelConnectPhase
import com.agentx.app.model.connect.ModelConnectRequest
import com.agentx.app.model.preset.ModelPreset
import com.agentx.app.model.runtime.ModelHealth
import com.agentx.app.model.runtime.ModelLifecycleState
import com.agentx.app.model.runtime.ModelRuntimeStatus
import kotlinx.coroutines.flow.StateFlow

/** Everything the Model Manager UI needs in one place. */
data class ModelManagerState(
    val loading: Boolean = true,
    val presets: List<ModelPreset> = emptyList(),
    val statuses: Map<String, ModelRuntimeStatus> = emptyMap(),
    val activePresetId: String? = null,
    /** The connection the Agent Core would use right now, or null. */
    val activeConfig: ModelConfig? = null,
    /**
     * Whether the Model Runner browser currently holds a session for a preset.
     * Informational only: it never gates the model connection or the agent.
     */
    val runnerSessions: Map<String, Boolean> = emptyMap(),
) {
    fun status(presetId: String): ModelRuntimeStatus =
        statuses[presetId] ?: ModelRuntimeStatus.notConfigured(presetId)

    val activePreset: ModelPreset? get() = presets.firstOrNull { it.id == activePresetId }

    val isEmpty: Boolean get() = !loading && presets.isEmpty()
}

/**
 * Owns saved model presets and their connection lifecycle.
 *
 * Layering: `UI → ViewModel → ModelManager → ModelRunner → Model Gateway`. No
 * Compose screen contains lifecycle logic and no runner knows about the gateway.
 */
interface ModelManager {
    val state: StateFlow<ModelManagerState>

    /** False when the platform could not offer encrypted storage for credentials. */
    val credentialsPersistent: Boolean

    /** Loads presets, restores the selected model, and probes its reachability once. */
    suspend fun refresh()

    /** Reads one saved preset, going back to the store so a deep link still resolves. */
    suspend fun preset(id: String): ModelPreset?

    suspend fun createPreset(preset: ModelPreset, credential: String? = null): ForgeResult<ModelPreset, ForgeError>

    suspend fun updatePreset(
        preset: ModelPreset,
        credential: String? = null,
        clearCredential: Boolean = false,
    ): ForgeResult<ModelPreset, ForgeError>

    suspend fun deletePreset(id: String): ForgeResult<Unit, ForgeError>

    /**
     * Quick Connect: normalize the endpoint, detect the API, verify chat, save
     * the preset and bring it online. The UI never talks HTTP itself.
     */
    suspend fun connectQuick(
        request: ModelConnectRequest,
        onPhase: (ModelConnectPhase) -> Unit = {},
    ): ForgeResult<ModelConnectOutcome, ForgeError>

    /** "Use": selects the preset and brings it online without restarting a healthy runtime. */
    suspend fun selectModel(id: String): ForgeResult<ModelRuntimeStatus, ForgeError>

    suspend fun startModel(id: String): ForgeResult<ModelRuntimeStatus, ForgeError>

    suspend fun stopModel(id: String): ForgeResult<ModelRuntimeStatus, ForgeError>

    suspend fun reconnectModel(id: String): ForgeResult<ModelRuntimeStatus, ForgeError>

    suspend fun checkModelHealth(id: String): ForgeResult<ModelHealth, ForgeError>

    /** The config the agent should use, or null when no model is online. */
    fun activeConfig(): ModelConfig?

    /**
     * Every connected provider's configuration, keyed by provider identity, so a
     * role can resolve a provider that is simultaneously connected beside the
     * active one. Empty when nothing is online.
     */
    fun connections(): Map<String, ModelConfig> = emptyMap()

    /** Called by the Model Runner browser; records session state only. */
    fun onRunnerSessionChanged(presetId: String, attached: Boolean)

    fun onAppForeground()

    fun onAppBackground()

    fun close()
}

/** Failure helper so every operation reports the same way. */
internal fun modelFailure(code: ForgeErrorCode, message: String, details: Map<String, Any?> = emptyMap()): ForgeError =
    ForgeError(code = code, message = message, details = details)

internal fun notFound(id: String): ForgeError = modelFailure(
    code = ForgeErrorCode.MODEL_PRESET_NOT_FOUND,
    message = "No model preset with id '$id'",
    details = mapOf("presetId" to id),
)

/**
 * Shared status vocabulary for the manager's own transitions, so the UI never has
 * to invent copy.
 */
internal fun statusFor(
    preset: ModelPreset,
    state: ModelLifecycleState,
    message: String,
    now: Long,
    previous: ModelRuntimeStatus? = null,
): ModelRuntimeStatus = (previous ?: ModelRuntimeStatus.notConfigured(preset.id)).copy(
    presetId = preset.id,
    state = state,
    message = message,
    updatedAtMillis = now,
)
