package com.agentx.app.agent.model

import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.model.preset.ModelProviderIds

/**
 * One provider the user has configured, with the models it can serve and whether
 * it is currently connected. Built from the Model Manager's saved presets and
 * their live status; it never triggers a network call by itself.
 */
data class ProviderModelOption(
    val providerId: String,
    val providerLabel: String,
    /** Model identifiers this provider is known to offer; may be empty. */
    val models: List<String> = emptyList(),
    /**
     * Models a dynamic catalog previously listed but no longer reports. They are
     * shown as unavailable rather than being silently dropped, so a saved role
     * assignment can be reported instead of quietly replaced.
     */
    val unavailableModels: List<String> = emptyList(),
    /** True only when the Model Manager already reports this provider usable. */
    val connected: Boolean = false,
    /** Saved connection (model preset) the option was built from, when any. */
    val connectionId: String? = null,
    val connectionLabel: String? = null,
    val endpoint: String? = null,
)

/** Whether a role's assignment can actually run right now. */
enum class RoleModelState {
    /** The provider is connected and the model is available. */
    CONNECTED,

    /** The provider is not connected (or does not exist) yet. */
    NOT_CONFIGURED,

    /** The provider is connected but does not offer the assigned model. */
    MODEL_UNAVAILABLE,
}

/** A role's assignment plus the honest configuration state shown in Settings. */
data class RoleModelStatus(
    val role: AgentRole,
    val providerId: String?,
    val providerLabel: String,
    val model: String?,
    val state: RoleModelState,
    val message: String,
    val explicit: Boolean,
)

/**
 * Judges a role's assignment against the providers the user has configured.
 *
 * Pure and side-effect free: it is the same logic the Settings screen renders and
 * the tests exercise, and it deliberately performs no network call. A provider is
 * "connected" only when the Model Manager already reports it usable, so the UI
 * never pretends a model is available when it is not.
 */
object RoleModelEvaluation {

    /** Human provider name for the well-known identities. */
    fun providerLabel(providerId: String?): String = when (providerId) {
        null, "" -> "No provider"
        ModelProviderIds.GEMINI -> "Gemini"
        ModelProviderIds.GROQ -> "Groq"
        ModelProviderIds.OPENAI_COMPATIBLE -> "OpenAI-compatible"
        else -> providerId
    }

    fun evaluate(selection: RoleModelSelection, options: List<ProviderModelOption>): RoleModelStatus {
        val providerId = selection.providerId?.takeIf { it.isNotBlank() }
        if (providerId == null) {
            return RoleModelStatus(
                role = selection.role,
                providerId = null,
                providerLabel = providerLabel(null),
                model = selection.model,
                state = RoleModelState.NOT_CONFIGURED,
                message = "No provider is assigned to this agent.",
                explicit = selection.explicit,
            )
        }

        val option = options.firstOrNull { it.providerId == providerId }
        val label = option?.providerLabel ?: providerLabel(providerId)
        if (option == null || !option.connected) {
            return RoleModelStatus(
                role = selection.role,
                providerId = providerId,
                providerLabel = label,
                model = selection.model,
                state = RoleModelState.NOT_CONFIGURED,
                message = "$label is not connected. Add or start it in Models.",
                explicit = selection.explicit,
            )
        }

        val model = selection.model
        if (model != null && model in option.unavailableModels) {
            return RoleModelStatus(
                role = selection.role,
                providerId = providerId,
                providerLabel = label,
                model = model,
                state = RoleModelState.MODEL_UNAVAILABLE,
                message = "$label no longer lists $model. Refresh the catalog or choose another model.",
                explicit = selection.explicit,
            )
        }
        if (model != null && option.models.isNotEmpty() && model !in option.models) {
            return RoleModelStatus(
                role = selection.role,
                providerId = providerId,
                providerLabel = label,
                model = model,
                state = RoleModelState.MODEL_UNAVAILABLE,
                message = "$label does not offer $model. Choose another model or update the connection.",
                explicit = selection.explicit,
            )
        }

        return RoleModelStatus(
            role = selection.role,
            providerId = providerId,
            providerLabel = label,
            model = model ?: option.models.firstOrNull(),
            state = RoleModelState.CONNECTED,
            message = "$label is connected.",
            explicit = selection.explicit,
        )
    }
}
