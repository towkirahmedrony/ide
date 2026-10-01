package com.agentx.app.ui.ide.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentx.app.agent.catalog.AgentCatalog
import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.agent.model.AgentRoleModelRegistry
import com.agentx.app.agent.model.ProviderModelOption
import com.agentx.app.agent.model.RoleModelEvaluation
import com.agentx.app.agent.model.RoleModelState
import com.agentx.app.model.connect.KnownModelProviders
import com.agentx.app.model.connect.ModelSetupKind
import com.agentx.app.model.manager.ModelManager
import com.agentx.app.model.manager.ModelManagerState
import com.agentx.app.model.preset.ModelPreset
import kotlinx.coroutines.launch

/** One role row in Settings → Agent Models. */
data class AgentModelRow(
    val role: AgentRole,
    val name: String,
    val description: String,
    val providerId: String?,
    val providerLabel: String,
    val model: String?,
    val state: RoleModelState,
    val message: String,
    /** True when the user explicitly assigned this, false for the built-in default. */
    val explicit: Boolean,
)

/**
 * Settings → Agent Models state.
 *
 * It reads the same [AgentRoleModelRegistry] the Agent Core's model resolver
 * consumes, so what a role is shown as using is what it actually runs on. The
 * screen renders state and forwards intent; every configuration decision belongs
 * to the registry.
 */
class AgentModelsViewModel(
    private val registry: AgentRoleModelRegistry,
    private val modelManager: ModelManager,
) : ViewModel() {

    var rows by mutableStateOf<List<AgentModelRow>>(emptyList())
        private set

    /** Providers the user has configured, for the editor. */
    var options by mutableStateOf<List<ProviderModelOption>>(emptyList())
        private set

    var loading by mutableStateOf(true)
        private set

    var message by mutableStateOf<String?>(null)
        private set

    private var managerState: ModelManagerState = ModelManagerState()

    init {
        viewModelScope.launch {
            registry.load()
            rebuild()
            loading = false
        }
        viewModelScope.launch {
            modelManager.state.collect { state -> rebuild(state) }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            registry.load()
            modelManager.refresh()
            rebuild()
            loading = false
        }
    }

    /**
     * Assigns [providerId] (and an optional model within it) to [role]. Only the
     * identifiers are stored; the provider connection keeps its own credential.
     */
    fun save(role: AgentRole, providerId: String, model: String?, connectionId: String?) {
        if (providerId.isBlank()) {
            message = "Choose a provider for the ${name(role)}."
            return
        }
        viewModelScope.launch {
            registry.save(role, providerId, model, connectionId)
            rebuild()
            message = "Saved. The ${name(role)} will use that model on its next run."
        }
    }

    /** Removes the override, restoring the built-in default for [role]. */
    fun reset(role: AgentRole) {
        viewModelScope.launch {
            registry.reset(role)
            rebuild()
            message = "The ${name(role)} follows the built-in default again."
        }
    }

    fun dismissMessage() {
        message = null
    }

    fun optionsFor(providerId: String?): ProviderModelOption? =
        providerId?.let { id -> options.firstOrNull { it.providerId == id } }

    private fun rebuild(state: ModelManagerState = managerState) {
        managerState = state
        val current = optionsFor(state)
        options = current
        rows = AgentRole.entries.map { role ->
            val status = RoleModelEvaluation.evaluate(registry.selection(role), current)
            AgentModelRow(
                role = role,
                name = AgentCatalog.definition(role).name,
                description = describe(role),
                providerId = status.providerId,
                providerLabel = status.providerLabel,
                model = status.model,
                state = status.state,
                message = status.message,
                explicit = status.explicit,
            )
        }
    }

    /**
     * Builds the provider catalog from the Model Manager's saved presets: one
     * option per provider identity, with the models it is known to serve and
     * whether it is connected right now. Nothing here contacts a provider.
     */
    private fun optionsFor(state: ModelManagerState): List<ProviderModelOption> {
        val grouped = LinkedHashMap<String, MutableList<ModelPreset>>()
        state.presets.forEach { preset ->
            grouped.getOrPut(preset.providerId) { mutableListOf() }.add(preset)
        }
        return grouped.map { (providerId, presets) ->
            val usable = presets.firstOrNull { state.status(it.id).isUsable }
            val suggested = KnownModelProviders.spec(ModelSetupKind.fromId(providerId))
                ?.suggestedModels
                .orEmpty()
            val origin = usable ?: presets.first()
            ProviderModelOption(
                providerId = providerId,
                providerLabel = RoleModelEvaluation.providerLabel(providerId),
                models = (presets.map { it.modelIdentifier } + suggested).distinct(),
                connected = usable != null,
                connectionId = origin.id,
                connectionLabel = origin.displayName,
                endpoint = usable?.let { state.status(it.id).endpoint?.url },
            )
        }
    }

    private fun name(role: AgentRole): String = AgentCatalog.definition(role).name

    private companion object {
        /** Concise, user-facing reasons each role exists. */
        fun describe(role: AgentRole): String = when (role) {
            AgentRole.MAIN -> "Plans tasks and coordinates other agents."
            AgentRole.EXPLORER -> "Finds relevant files, symbols, and code."
            AgentRole.RESEARCHER -> "Researches external documentation and information."
            AgentRole.CODER -> "Implements code changes."
            AgentRole.DEBUGGER -> "Diagnoses and fixes errors."
            AgentRole.REVIEWER -> "Reviews changes for correctness and regressions."
            AgentRole.TESTER -> "Analyzes tests, logs, and verification results."
        }
    }
}
