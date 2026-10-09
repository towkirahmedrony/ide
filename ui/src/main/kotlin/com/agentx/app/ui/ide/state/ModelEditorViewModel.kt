package com.agentx.app.ui.ide.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentx.app.core.ForgeError
import com.agentx.app.core.errorOrNull
import com.agentx.app.core.valueOrNull
import com.agentx.app.model.ModelConfig
import com.agentx.app.model.catalog.CatalogModel
import com.agentx.app.model.catalog.ModelCatalogRegistry
import com.agentx.app.model.catalog.ModelCatalogState
import com.agentx.app.model.connect.KnownModelProviders
import com.agentx.app.model.connect.ModelConnectOutcome
import com.agentx.app.model.connect.ModelConnectPhase
import com.agentx.app.model.connect.ModelSetupKind
import com.agentx.app.model.connect.selectDiscoveredModel
import com.agentx.app.model.manager.ModelConnectionKind
import com.agentx.app.model.manager.ModelManager
import com.agentx.app.model.preset.ModelApiProtocol
import com.agentx.app.model.preset.ModelPreset
import com.agentx.app.model.preset.ModelProviderIds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Add/Edit model state.
 *
 * The form is the simplified one: connection type, provider/model, server URL or
 * API key. Everything else is derived by [ModelSetupForm] and the provider
 * catalogue, so no Advanced section is needed.
 */
data class ModelEditorState(
    val loading: Boolean = true,
    val missing: Boolean = false,
    val form: ModelSetupForm = ModelSetupForm(),
    /** Which models the form offers for the current provider. */
    val models: List<ModelChoice> = emptyList(),
    /** True when [models] came from the provider's own catalog, not its suggestions. */
    val modelsFromCatalog: Boolean = false,
    val catalogLoading: Boolean = false,
    /** Set only when a connected provider's catalog could not be read. */
    val catalogError: String? = null,
    /**
     * What the last discovery attempt for this provider did, when the catalog
     * reports it. It is what separates "this provider publishes no model list"
     * from "the list could not be read" — two different fixes for the user.
     */
    val catalogState: ModelCatalogState? = null,
    /** Model ids a connect attempt discovered and wants the user to choose from. */
    val discoveredModels: List<String> = emptyList(),
    val saving: Boolean = false,
    val connecting: Boolean = false,
    val connectPhase: ModelConnectPhase? = null,
    /** Set once the model is saved; the screen leaves afterwards. */
    val saved: Boolean = false,
    val errors: List<String> = emptyList(),
) {
    val busy: Boolean get() = saving || connecting

    val isEditing: Boolean get() = form.isEditing

    /** True when the model id can be picked from a list. */
    val hasModelList: Boolean get() = models.isNotEmpty() && !form.manualModel

    fun issue(field: ModelSetupField): String? = form.issueFor(field)

    val primaryActionLabel: String
        get() = when {
            connecting -> connectPhase?.displayName ?: "Connecting…"
            isEditing -> "Save and connect"
            else -> "Add model"
        }
}

/**
 * Drives the Add/Edit model screen.
 *
 * The ViewModel keeps user intent, validation and the catalog in one place; all
 * connection work still happens inside the Model Manager, and nothing here talks
 * HTTP or invents a connection state.
 */
class ModelEditorViewModel(
    private val manager: ModelManager,
    private val presetId: String?,
    private val catalog: ModelCatalogRegistry? = null,
) : ViewModel() {

    var state by mutableStateOf(ModelEditorState())
        private set

    private var existing: ModelPreset? = null

    init {
        viewModelScope.launch {
            val loaded = presetId?.let { manager.preset(it) }
            existing = loaded
            state = when {
                loaded != null -> state.copy(loading = false, form = ModelSetupForm.from(loaded))
                presetId != null -> state.copy(loading = false, missing = true)
                else -> state.copy(loading = false, form = ModelSetupForm())
            }
            // A new model offers the provider's models straight away.
            loadModels(force = false)
        }
    }

    /** Applies a form edit and clears results that no longer apply. */
    fun edit(block: (ModelSetupForm) -> ModelSetupForm) {
        if (state.busy) return
        state = state.copy(
            form = block(state.form),
            errors = emptyList(),
            discoveredModels = emptyList(),
        )
    }

    /** Switches connection type, which also switches the fields on screen. */
    fun selectConnectionType(type: ModelConnectionType) {
        if (state.busy || state.form.connectionType == type) return
        edit { form -> form.copy(connectionType = type) }
        viewModelScope.launch { loadModels(force = false) }
    }

    /** Switches API provider; the model list is reloaded for the new provider. */
    fun selectProvider(kind: ModelSetupKind) {
        if (state.busy || state.form.apiProvider == kind) return
        edit { form -> form.copy(apiProvider = kind, manualModel = false) }
        viewModelScope.launch { loadModels(force = false) }
    }

    /**
     * Selects a model from the provider's list, showing what is stated for **it**.
     *
     * The statement the switch makes belongs to one model, so the switch follows the
     * selection: picking a model the connection already declares shows it on, and
     * picking one it says nothing about shows it off rather than carrying the
     * previous model's answer over. Without this the switch would misreport what the
     * saved connection claims, and a user could believe a model was declared that
     * never was.
     */
    fun selectModel(modelId: String) {
        edit { form -> form.copy(modelId = modelId, declaresToolCalling = statesToolCalling(modelId)) }
    }

    /** Whether the saved connection already states tool calling for [modelId]. */
    private fun statesToolCalling(modelId: String): Boolean =
        existing?.declarationFor(modelId) != null

    fun toggleManualModel(enabled: Boolean) {
        edit { form -> form.copy(manualModel = enabled) }
    }

    /** Retries the provider catalog after a failure, keeping the typed values. */
    fun retryCatalog() {
        if (state.busy || state.catalogLoading) return
        viewModelScope.launch { loadModels(force = true) }
    }

    fun removeStoredCredential() {
        edit { form -> form.copy(clearCredential = true, hasStoredCredential = false) }
    }

    /** Validates, then connects through the Model Manager and saves on success. */
    fun connect() {
        if (state.busy) return
        val issues = state.form.issues()
        if (issues.isNotEmpty()) {
            state = state.copy(errors = issues.map { it.message })
            return
        }
        state = state.copy(
            connecting = true,
            errors = emptyList(),
            discoveredModels = emptyList(),
            catalogError = null,
            connectPhase = ModelConnectPhase.CONNECTING,
            saved = false,
        )
        viewModelScope.launch {
            try {
                val duplicate = duplicateTarget()
                val result = manager.connectQuick(state.form.toConnectRequest(duplicate)) { phase ->
                    state = state.copy(connectPhase = phase)
                }
                val failure = result.errorOrNull()
                if (failure != null) {
                    state = state.copy(
                        connecting = false,
                        connectPhase = null,
                        errors = fieldErrorsOf(failure) ?: listOf(failure.message ?: "Could not connect"),
                    )
                    return@launch
                }
                when (val outcome = result.valueOrNull()) {
                    is ModelConnectOutcome.NeedsModelChoice -> state = state.copy(
                        connecting = false,
                        connectPhase = null,
                        discoveredModels = outcome.models,
                        errors = listOf(outcome.message),
                    )

                    is ModelConnectOutcome.Connected -> {
                        existing = outcome.preset
                        state = state.copy(
                            connecting = false,
                            connectPhase = ModelConnectPhase.CONNECTED,
                            saved = true,
                            form = ModelSetupForm.from(outcome.preset),
                        )
                    }

                    null -> state = state.copy(
                        connecting = false,
                        connectPhase = null,
                        errors = listOf("Could not connect"),
                    )
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

    /**
     * Saves without contacting the endpoint.
     *
     * This is the recovery path for a local server that is not up yet: the
     * configuration is kept, the user is never asked to retype it, and no
     * connection state is claimed.
     */
    fun save() {
        if (state.busy) return
        val issues = state.form.issues()
        if (issues.isNotEmpty()) {
            state = state.copy(errors = issues.map { it.message })
            return
        }
        state = state.copy(saving = true, errors = emptyList())
        viewModelScope.launch {
            try {
                val target = existing ?: duplicateTarget()
                val preset = state.form.toPreset(target)
                val result = if (target == null) {
                    manager.createPreset(preset, state.form.credential.takeIf { it.isNotBlank() })
                } else {
                    manager.updatePreset(
                        preset = preset,
                        credential = state.form.credential.takeIf { it.isNotBlank() },
                        clearCredential = state.form.clearCredential,
                    )
                }
                val failure = result.errorOrNull()
                state = if (failure == null) {
                    existing = result.valueOrNull() ?: existing
                    state.copy(saving = false, saved = true)
                } else {
                    state.copy(
                        saving = false,
                        errors = fieldErrorsOf(failure) ?: listOf(failure.message ?: "The model could not be saved"),
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

    /** An already-saved preset describing the same connection, if there is one. */
    private fun duplicateTarget(): ModelPreset? {
        if (state.form.presetId != null) return null
        return ModelSetupForm.equivalent(manager.state.value.presets, state.form)
    }

    private suspend fun loadModels(force: Boolean) {
        if (state.form.connectionType == ModelConnectionType.LOCAL) {
            state = state.copy(models = emptyList(), modelsFromCatalog = false, catalogError = null)
            return
        }
        val setupKind = state.form.setupKind
        val providerId = providerIdFor(setupKind)
        val registry = catalog
        state = state.copy(catalogLoading = true, catalogError = null)

        // 1. The provider's own catalog. A saved/connected provider is discovered
        //    here, through the same provider identity the runtime chats through.
        var live: List<CatalogModel> = emptyList()
        var failure: String? = if (registry == null) {
            null
        } else {
            runCatching { registry.refresh(providerId, force) }.getOrNull()?.errorOrNull()?.message
        }
        if (registry != null) {
            live = runCatching { registry.availableModels(providerId) }.getOrDefault(emptyList())
        }

        // 2. A provider the user has configured but not saved has no catalog yet, so
        //    nothing could be discovered for it above. When it is addressed by
        //    endpoint (FreeLLMAPI), discovery runs against the address and credential
        //    the form holds, through the same factory a saved connection uses — the
        //    picker then lists the endpoint's own model records instead of a built-in
        //    list. This is a read-only preview: nothing is persisted and no saved
        //    catalog is touched.
        var draftState: ModelCatalogState? = null
        if (live.isEmpty() &&
            registry != null &&
            setupKind.showsEndpointField &&
            state.form.effectiveServerUrl.isNotBlank()
        ) {
            val preview = runCatching { registry.preview(providerId, draftConnection(providerId)) }.getOrNull()
            val snapshot = preview?.valueOrNull()
            if (snapshot != null) {
                live = snapshot.availableModels()
                draftState = ModelCatalogState.Discovered(snapshot.availableModels().size, snapshot.fetchedAtMillis)
                // The endpoint answered, so any catalog-only failure no longer applies.
                failure = null
            } else {
                failure = preview?.errorOrNull()?.message ?: failure
                draftState = ModelCatalogState.Failed(
                    message = failure ?: "The model list could not be read.",
                )
            }
        }

        val connected = registry?.catalog(providerId) != null
        // Read after the refresh, so the state describes the attempt that just ran.
        val discoveryState = registry?.lastDiscovery(providerId)

        // The configured endpoint is the only authority for its own model list. Once
        // discovery has run against it — whether it answered with models, answered
        // with none, or could not be read — the built-in compatibility list is *not*
        // substituted, because those ids were never received from that endpoint. The
        // fallback list survives only for a provider no discovery was attempted for
        // (no endpoint on the form), so an offline provider stays usable without ever
        // presenting an outdated list as the live catalog.
        val liveChoices = ModelChoices.catalogChoices(live)
        val fromCatalog = liveChoices.isNotEmpty()
        val offers = when {
            fromCatalog -> liveChoices
            draftState != null -> emptyList()
            else -> ModelChoices.suggestions(providerId).map { id -> ModelChoice(id, id) }
        }
        // Feature the priority FreeLLMAPI ids — but only the ones the endpoint
        // actually returned, so the section can never invent a model.
        val presented = if (fromCatalog && setupKind == ModelSetupKind.FREELLMAPI) {
            ModelChoices.markRecommended(offers, FREELLMAPI_RECOMMENDED_MODELS)
        } else {
            offers
        }
        state = state.copy(
            catalogLoading = false,
            models = presented,
            modelsFromCatalog = fromCatalog,
            // Only something that failed to answer and could be retried is an error: a
            // connected provider that failed, or a draft endpoint that could not be
            // read. A provider with no catalog yet simply has no model list.
            catalogError = failure?.takeIf { connected || fromCatalog || draftState != null },
            catalogState = draftState ?: discoveryState,
            form = resolveStaleSavedModel(state.form, liveChoices, providerId),
        )
    }

    /** The provider family identity a setup kind resolves to for its model list. */
    private fun providerIdFor(kind: ModelSetupKind): String =
        ModelProviderIds.forPreset(kind.id, ModelApiProtocol.OPENAI_COMPATIBLE)

    /**
     * The connection a draft discovery runs against: the address and credential the
     * form currently holds, and nothing else. It is never saved —
     * [ModelCatalogRegistry.preview] discards it after the one refresh.
     */
    private fun draftConnection(providerId: String): ModelConfig = ModelConfig(
        providerId = providerId,
        baseUrl = state.form.effectiveServerUrl.trim().trimEnd('/'),
        model = state.form.modelId.trim(),
        apiKey = state.form.credential.takeIf { it.isNotBlank() },
        connectionKind = ModelConnectionKind.API,
        headers = state.form.setupKind.requestHeaders,
    )

    /**
     * Re-points a saved model the provider no longer lists at a valid one from the
     * live catalog, using the same selection rules connect uses.
     *
     * Only an edit of an existing preset is touched, and only when discovery really
     * answered: a failed refresh keeps the saved model untouched rather than
     * guessing. When no valid replacement can be resolved the saved value is kept,
     * so nothing is silently erased.
     */
    private fun resolveStaleSavedModel(
        form: ModelSetupForm,
        live: List<ModelChoice>,
        providerId: String,
    ): ModelSetupForm {
        if (!form.isEditing || form.manualModel || form.modelId.isBlank() || live.isEmpty()) return form
        if (live.any { it.id == form.modelId }) return form
        val preferred = KnownModelProviders.spec(ModelSetupKind.fromId(providerId))?.preferredModel
        val resolved = selectDiscoveredModel(live.map { it.id }, preferred = null, catalogPreferred = preferred)
            ?: return form
        return form.copy(modelId = resolved)
    }

    private fun fieldErrorsOf(failure: ForgeError): List<String>? =
        (failure.details["errors"] as? List<*>)?.map { it.toString() }?.takeIf { it.isNotEmpty() }
}
