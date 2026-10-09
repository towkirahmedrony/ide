package com.agentx.app.agent.model

import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.model.ModelConfig
import com.agentx.app.model.capability.CapabilitySupport
import com.agentx.app.model.capability.ModelCapabilityRegistry
import com.agentx.app.model.connect.ModelSetupKind
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
    /**
     * Why [models] is what it is, when discovery did not simply succeed:
     * "no model list", "refresh failed", "not connected". Null when the provider
     * answered normally, so a live list is never captioned as a problem.
     */
    val discoveryNote: String? = null,
)

/**
 * Whether a role's assignment can actually run right now.
 *
 * [CONNECTED] means *runtime-ready*: the exact assigned connection is addressable,
 * it offers the assigned model, and the model can serve the role's required
 * capabilities. It is deliberately not the same as "a string is saved": every other
 * value below is a state where the assignment exists but the runtime would not
 * execute it, and each is reported separately because they are repaired differently.
 */
enum class RoleModelState {
    /** Runtime-ready: connection, model and capabilities all agree. */
    CONNECTED,

    /** Nothing is assigned for this role yet. */
    NOT_CONFIGURED,

    /** The provider is connected but does not offer the assigned model. */
    MODEL_UNAVAILABLE,

    /**
     * The exact saved connection the role was assigned from is not currently
     * addressable (deleted, disconnected, or never restored).
     *
     * Distinct from [NOT_CONFIGURED] because the assignment is *stale*, not absent:
     * the user's choice is preserved and must not be silently re-pointed at another
     * connection of the same provider family.
     */
    CONNECTION_MISSING,

    /** The model authoritatively does not support a capability this role requires. */
    CAPABILITY_UNSUPPORTED,

    /**
     * Runtime support for a required capability is unconfirmed.
     *
     * The model is visible and the assignment is intact — this is an unresolved
     * verdict, not a rejection — but the runtime will not treat it as eligible, so
     * Settings must not present it as ready either.
     */
    CAPABILITY_UNKNOWN,

    /** The model definition exists but is disabled. */
    DISABLED,
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
    /**
     * The exact connection the assignment was saved against, when it named one.
     * Reported so the state can be explained by identity rather than by provider
     * family alone.
     */
    val connectionId: String? = null,
    /**
     * Why the model cannot serve this role's capabilities, when that is the state.
     * Null whenever nothing is known to be missing, so an unverified model is never
     * reported as broken.
     */
    val capabilityNote: String? = null,
) {
    /** True only when the runtime would actually run this assignment. */
    val runtimeReady: Boolean get() = state == RoleModelState.CONNECTED
}

/**
 * Judges a role's assignment against the providers the user has configured.
 *
 * Pure and side-effect free: it is the same logic the Settings screen renders and
 * the tests exercise, and it deliberately performs no network call. A provider is
 * "connected" only when the Model Manager already reports it usable, so the UI
 * never pretends a model is available when it is not.
 */
object RoleModelEvaluation {

    /**
     * Whether the capabilities of [providerId]'s models are the user's to state.
     *
     * True for the providers nothing authoritative describes: a Custom/Local
     * OpenAI-compatible endpoint, and an endpoint-addressed gateway (FreeLLMAPI), which
     * routes a model to whatever upstream serves it and is therefore listed with tool
     * calling unknown. False for a fixed-address catalogue provider (Gemini, Groq), which
     * states its own capabilities and must not be overridden by a switch.
     *
     * The same rule the connect flow uses to decide whether to show the switch
     * ([ModelSetupKind.showsEndpointField]), so the two surfaces cannot disagree about
     * whose statement counts.
     */
    fun declarableByUser(providerId: String?): Boolean =
        providerId == ModelProviderIds.OPENAI_COMPATIBLE ||
            ModelSetupKind.entries.any { it.id == providerId && it.showsEndpointField }

    /** Human provider name for the well-known identities. */
    fun providerLabel(providerId: String?): String = when (providerId) {
        null, "" -> "No provider"
        ModelProviderIds.GEMINI -> "Gemini"
        ModelProviderIds.GROQ -> "Groq"
        ModelProviderIds.OPENAI_COMPATIBLE -> "OpenAI-compatible"
        ModelProviderIds.FREELMAPI -> "FreeLLMAPI"
        else -> providerId
    }

    fun evaluate(
        selection: RoleModelSelection,
        options: List<ProviderModelOption>,
        /**
         * Connection identities the runtime can currently address.
         *
         * Null means "not reported", for a caller with no runtime view. It is never
         * read as "this connection is missing": a connection nobody reported on is not
         * the same as a connection that was deleted. The Settings screen always passes
         * the Model Manager's own set.
         */
        availableConnections: Set<String>? = null,
        /**
         * The authoritative capability registry — the same one the runtime's
         * eligibility check reads. Supplied so Settings reports the runtime's own
         * verdict rather than a second, weaker opinion.
         */
        capabilities: ModelCapabilityRegistry? = null,
    ): RoleModelStatus {
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

        // A saved assignment names one exact connection. When that connection is gone
        // the assignment is stale and is reported as such; it is never answered by a
        // sibling connection of the same provider family, which is the substitution the
        // runtime already refuses to make.
        val assignedConnection = selection.connectionId?.takeIf { it.isNotBlank() }
        if (assignedConnection != null && availableConnections != null && assignedConnection !in availableConnections) {
            return RoleModelStatus(
                role = selection.role,
                providerId = providerId,
                providerLabel = providerLabel(providerId),
                model = selection.model,
                state = RoleModelState.CONNECTION_MISSING,
                message = "The saved connection for this agent is no longer available. " +
                    "Choose its model again to repair the assignment.",
                explicit = selection.explicit,
                connectionId = assignedConnection,
            )
        }

        // The exact connection is matched first, then the provider family. Matching the
        // exact identity is what keeps two connections exposing the same model id apart.
        val option = assignedConnection
            ?.let { id -> options.firstOrNull { it.connectionId == id } }
            ?: options.firstOrNull { it.providerId == providerId }
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
                connectionId = assignedConnection,
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
                connectionId = assignedConnection,
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
                connectionId = assignedConnection,
            )
        }

        // The model the runtime would actually run: the assigned one, or the provider's
        // first when the assignment leaves the choice to the provider.
        val effectiveModel = model ?: option.models.firstOrNull()

        // The capability verdict, from the runtime's own source and in the runtime's own
        // order — disabled, then unsupported, then unknown — because a disagreement here
        // is exactly what lets Settings call a model ready while the runtime refuses to
        // run it. Quota and observed health are deliberately absent: Settings must not
        // consult admission, nor require a provider to be answering right now in order
        // to describe what its models can do.
        if (capabilities != null && effectiveModel != null) {
            // The assignment's own statement, applied through the rule the runtime applies
            // ([statingToolCalling]) rather than resolved from `providerId` + `model`
            // alone. Without it a model the user had already stated tool calling for was
            // reported here as `CAPABILITY_UNKNOWN` while the runtime ran it — the
            // disagreement this evaluation exists to prevent — and the screen offered no
            // way to see that the statement had taken effect. The rule is applied in its
            // pure form: judging an assignment must not change what any other consumer
            // believes about the model.
            val base = ModelConfig(
                providerId = providerId,
                // Inert for capability resolution, which reads identity and any saved
                // declaration only. Never logged and never used to connect.
                baseUrl = option.endpoint.orEmpty(),
                model = effectiveModel,
            )
            val config = if (selection.declaresToolCalling) {
                base.statingToolCalling(effectiveModel)
            } else {
                base
            }
            val profile = ModelEligibilityChecker(capabilities).profile(config)
            if (!profile.enabled) {
                return RoleModelStatus(
                    role = selection.role,
                    providerId = providerId,
                    providerLabel = label,
                    model = effectiveModel,
                    state = RoleModelState.DISABLED,
                    message = "$label reports $effectiveModel as disabled.",
                    explicit = selection.explicit,
                    connectionId = assignedConnection,
                    capabilityNote = "This model is disabled and cannot be run.",
                )
            }
            val missing = AgentRoleRequirements.required(selection.role).filterNot { profile.supports(it) }
            if (missing.isNotEmpty()) {
                val unsupported = missing.filter { profile.support(it) == CapabilitySupport.UNSUPPORTED }
                val definite = unsupported.isNotEmpty()
                val named = (if (definite) unsupported else missing).joinToString(", ") { it.id }
                return RoleModelStatus(
                    role = selection.role,
                    providerId = providerId,
                    providerLabel = label,
                    model = effectiveModel,
                    state = if (definite) {
                        RoleModelState.CAPABILITY_UNSUPPORTED
                    } else {
                        RoleModelState.CAPABILITY_UNKNOWN
                    },
                    message = if (definite) {
                        "$label does not support $named, which this agent requires."
                    } else {
                        "Runtime support for $named is not confirmed for $effectiveModel, " +
                            "so this agent cannot run on it yet."
                    },
                    explicit = selection.explicit,
                    connectionId = assignedConnection,
                    capabilityNote = if (definite) {
                        "This model does not support $named required by this agent."
                    } else {
                        "Support for $named has not been confirmed for this model."
                    },
                )
            }
        }

        return RoleModelStatus(
            role = selection.role,
            providerId = providerId,
            providerLabel = label,
            model = effectiveModel,
            state = RoleModelState.CONNECTED,
            message = "$label is connected.",
            explicit = selection.explicit,
            connectionId = assignedConnection,
        )
    }
}
