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
import com.agentx.app.model.catalog.ModelCatalogRegistry
import com.agentx.app.model.connect.KnownModelProviders
import com.agentx.app.model.connect.ModelSetupKind
import com.agentx.app.model.manager.ModelManager
import com.agentx.app.model.manager.ModelManagerState
import com.agentx.app.model.preset.ModelPreset
import com.agentx.app.model.ratelimit.RateLimitManager
import com.agentx.app.model.ratelimit.RateLimitSource
import com.agentx.app.model.ratelimit.UsageTotals
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
 * consumes, so what a role is shown as using is what it actually runs on. When a
 * [ModelCatalogRegistry] is supplied, the model choices come from each provider's
 * live catalog (for example Groq's `/openai/v1/models`) instead of a static list;
 * deprecated models are marked unavailable rather than silently replaced. The
 * optional [RateLimitManager] supplies the current per-provider limits and usage
 * shown in the editor.
 */
class AgentModelsViewModel(
    private val registry: AgentRoleModelRegistry,
    private val modelManager: ModelManager,
    private val catalog: ModelCatalogRegistry? = null,
    private val rateLimits: RateLimitManager? = null,
) : ViewModel() {

    var rows by mutableStateOf<List<AgentModelRow>>(emptyList())
        private set

    /** Providers the user has configured, for the editor. */
    var options by mutableStateOf<List<ProviderModelOption>>(emptyList())
        private set

    /** Compact rate-limit + usage summary per provider, for the editor. */
    var providerSummaries by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    var loading by mutableStateOf(true)
        private set

    var message by mutableStateOf<String?>(null)
        private set

    var catalogBusy by mutableStateOf(false)
        private set

    var catalogMessage by mutableStateOf<String?>(null)
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
            // A provider connected since the last visit gets its catalog fetched;
            // a fresh cache is reused, so this never refetches on every open.
            catalog?.let { runCatching { it.refreshAll(force = false) } }
            rebuild()
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

    fun dismissCatalogMessage() {
        catalogMessage = null
    }

    /** Manual refresh of the dynamic model catalogs (for example after adding models). */
    fun refreshCatalog() {
        val registry = catalog ?: run {
            catalogMessage = "This build has no dynamic model catalog."
            return
        }
        viewModelScope.launch {
            catalogBusy = true
            val providers = options.map { it.providerId }.distinct()
            if (providers.isEmpty()) {
                catalogMessage = "Connect a provider first, then refresh its model list."
            } else {
                val refreshed = registry.refreshAll(force = true)
                rebuild()
                catalogMessage = if (refreshed.isEmpty()) {
                    "No connected provider exposes a refreshable model list."
                } else {
                    "Refreshed: ${refreshed.keys.sorted().joinToString(", ")}."
                }
            }
            catalogBusy = false
        }
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
        providerSummaries = current.associate { it.providerId to summaryFor(it) }
    }

    /**
     * Builds the provider catalog from the Model Manager's saved presets plus any
     * dynamic model catalog: one option per provider identity, with the models it
     * can serve, which are deprecated, and whether it is connected right now.
     */
    private fun optionsFor(state: ModelManagerState): List<ProviderModelOption> {
        val grouped = LinkedHashMap<String, MutableList<ModelPreset>>()
        state.presets.forEach { preset ->
            grouped.getOrPut(preset.providerId) { mutableListOf() }.add(preset)
        }
        return grouped.map { (providerId, presets) ->
            val usable = presets.firstOrNull { state.status(it.id).isUsable }
            val origin = usable ?: presets.first()
            val catalogSnapshot = catalog?.snapshot(providerId)
            // The provider's own live catalog wins outright. A saved model that the
            // live list no longer contains is not re-offered, so it is reported as
            // unavailable instead of being silently accepted as if it still existed.
            val liveModels = catalogSnapshot?.availableModels()?.map { it.id }.orEmpty()
            val available = LinkedHashSet<String>()
            if (liveModels.isNotEmpty()) {
                available += liveModels
            } else {
                // Nothing discovered: keep the saved models usable and, only when the
                // provider has never answered, offer its built-in compatibility list.
                presets.forEach { available += it.modelIdentifier }
                if (catalogSnapshot == null) {
                    KnownModelProviders.spec(ModelSetupKind.fromId(providerId))
                        ?.suggestedModels
                        .orEmpty()
                        .forEach { available += it }
                }
            }
            val unavailable = if (liveModels.isNotEmpty()) {
                catalogSnapshot?.models?.filterNot { it.available }?.map { it.id }.orEmpty()
            } else {
                emptyList()
            }
            ProviderModelOption(
                providerId = providerId,
                providerLabel = RoleModelEvaluation.providerLabel(providerId),
                models = available.toList(),
                unavailableModels = unavailable,
                connected = usable != null,
                connectionId = origin.id,
                connectionLabel = origin.displayName,
                endpoint = usable?.let { state.status(it.id).endpoint?.url },
            )
        }
    }

    /** A one-line, honest summary: where limits came from and what has been used. */
    private fun summaryFor(option: ProviderModelOption): String {
        val manager = rateLimits ?: return ""
        val parts = mutableListOf<String>()
        val limitLine = limitSummary(manager, option.providerId, option.models.firstOrNull())
        if (limitLine != null) parts += limitLine
        val usage = manager.usage.totals(option.providerId).fold(UsageTotals(option.providerId, "")) { acc, total ->
            acc.copy(
                requestCount = acc.requestCount + total.requestCount,
                inputTokens = acc.inputTokens + total.inputTokens,
                outputTokens = acc.outputTokens + total.outputTokens,
                totalTokens = acc.totalTokens + total.totalTokens,
                rateLimitEvents = acc.rateLimitEvents + total.rateLimitEvents,
            )
        }
        if (usage.requestCount > 0 || usage.rateLimitEvents > 0) {
            parts += "Used ${usage.requestCount} requests · ${usage.totalTokens} tokens" +
                (if (usage.rateLimitEvents > 0) " · ${usage.rateLimitEvents} rate-limited" else "")
        }
        return parts.joinToString("\n")
    }

    private fun limitSummary(manager: RateLimitManager, providerId: String, modelId: String?): String? {
        val profile = manager.profile(providerId, modelId) ?: manager.profile(providerId)
        val source = when (profile?.source) {
            RateLimitSource.PROVIDER_REPORTED -> "provider-reported"
            RateLimitSource.APP_CONFIGURED -> "app-configured"
            RateLimitSource.UNKNOWN -> "safe default (provider limits unknown)"
            null -> null
        }
        val rpm = profile?.requestsPerMinute
        val tpm = profile?.tokensPerMinute
        val rpd = profile?.requestsPerDay
        if (source == null && rpm == null && tpm == null && rpd == null) return null
        val limits = buildList {
            rpm?.let { add("$it rpm") }
            tpm?.let { add("$it tpm") }
            rpd?.let { add("$it rpd") }
        }.joinToString(", ")
        return if (limits.isBlank()) {
            "Limits: ${source ?: "unknown"}"
        } else {
            "Limits: $limits (${source ?: "unknown"})"
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
            AgentRole.PLANNER -> "Turns requirements into an implementation plan."
            AgentRole.FAST_CODER -> "Makes small, localized code edits."
            AgentRole.SECURITY_REVIEWER -> "Reviews auth, secrets, and permission boundaries."
            AgentRole.DOCS -> "Updates README and technical documentation."
            AgentRole.COMMIT_PR -> "Prepares commit messages and PR summaries."
        }
    }
}
