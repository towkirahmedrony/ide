package com.agentx.app.ui.ide.state

import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.agent.model.AgentRoleModelRegistry
import com.agentx.app.agent.model.RoleModelSelection
import com.agentx.app.model.capability.CapabilitySupport
import com.agentx.app.model.capability.ModelCapabilityDeclaration
import com.agentx.app.model.catalog.CatalogModel
import com.agentx.app.model.catalog.ModelCatalogRegistry
import com.agentx.app.model.connect.EndpointResolver
import com.agentx.app.model.connect.KnownModelProviders
import com.agentx.app.model.connect.ModelConnectRequest
import com.agentx.app.model.connect.ModelSetupKind
import com.agentx.app.model.preset.EndpointConfig
import com.agentx.app.model.preset.EndpointDiscoveryMode
import com.agentx.app.model.preset.HealthCheckConfig
import com.agentx.app.model.preset.ModelApiProtocol
import com.agentx.app.model.preset.ModelPreset
import com.agentx.app.model.preset.ModelProviderIds
import com.agentx.app.model.preset.ModelProviderType
import com.agentx.app.model.preset.TunnelConfig
import com.agentx.app.model.preset.TunnelType
import com.agentx.app.model.ratelimit.RateLimitManager
import com.agentx.app.model.ratelimit.UsageTotals

/**
 * How the user wants to reach a model. Everything that used to sit behind the
 * Advanced section is derived from this choice plus the existing provider
 * catalogue, and is never asked for again.
 */
enum class ModelConnectionType(val displayName: String, val helper: String) {
    /** An OpenAI-compatible server the user already runs: local, ngrok, Cloudflare Tunnel. */
    LOCAL(
        displayName = "Local",
        helper = "Use an OpenAI-compatible endpoint exposed through ngrok, Cloudflare Tunnel, " +
            "or a local server.",
    ),

    /** A hosted provider the user has an API key for. */
    API(
        displayName = "API",
        helper = "A hosted provider reached with an API key.",
    ),
}

/**
 * How a Custom/Local endpoint should be spoken to.
 *
 * AUTO is the default and the only value that probes more than one protocol: a
 * user-supplied URL says nothing about what answers it, so the compatible model
 * list is tried first and Ollama's second. A user who knows their server picks
 * that protocol outright, and then only that one is asked — an Ollama server is
 * never additionally probed as OpenAI-compatible, and vice versa.
 */
enum class ModelProtocolChoice(val displayName: String, val helper: String) {
    AUTO("Auto", "Try the OpenAI-compatible model list, then Ollama's."),
    OPENAI_COMPATIBLE("OpenAI-compatible", "llama.cpp, vLLM, LM Studio, most servers."),
    OLLAMA("Ollama", "Ollama's own /api/tags model list."),
    ;

    /** The protocol to probe, or null when detection decides. */
    fun toProtocol(): ModelApiProtocol? = when (this) {
        AUTO -> null
        OPENAI_COMPATIBLE -> ModelApiProtocol.OPENAI_COMPATIBLE
        OLLAMA -> ModelApiProtocol.OLLAMA
    }

    companion object {
        /** The choice describing [protocol], for filling the form from a preset. */
        fun forProtocol(protocol: ModelApiProtocol): ModelProtocolChoice = when (protocol) {
            ModelApiProtocol.OPENAI_COMPATIBLE -> OPENAI_COMPATIBLE
            ModelApiProtocol.OLLAMA -> OLLAMA
            // A provider's own API is stated by the provider catalogue, not here.
            ModelApiProtocol.GEMINI_NATIVE -> AUTO
        }
    }
}

/** Providers offered by the API flow, in presentation order. */
val API_PROVIDER_KINDS: List<ModelSetupKind> = listOf(
    ModelSetupKind.GEMINI,
    ModelSetupKind.GROQ,
    ModelSetupKind.FREELLMAPI,
)

/**
 * Model ids featured first when the live FreeLLMAPI endpoint returns them.
 *
 * This is a *presentation* preference, not an allowlist and not a catalog: a
 * candidate is only ever shown when the configured endpoint's own `/v1/models`
 * response contains that exact id, and every other discovered model stays
 * searchable and selectable beside it. Nothing is invented here, no id is
 * rewritten to an assumed upstream alias, and a candidate the endpoint does not
 * return simply does not appear.
 */
val FREELLMAPI_RECOMMENDED_MODELS: List<String> = listOf(
    "gemini-3.8-flash",
    "gemini-3.5-flash-lite",
    "openai/gpt-oss-120b",
    "qwen/qwen3.8-27b",
    "deepseek-v4-flash",
    "deepseek-v4-pro",
    "devstral-2",
    "glm-5.3",
)

/** Which input a validation issue belongs to, so the form can mark one field. */
enum class ModelSetupField { NAME, MODEL, SERVER_URL, API_KEY }

/** One thing the user has to fix before Connect/Add can run. */
data class ModelSetupIssue(val field: ModelSetupField, val message: String)

/**
 * The Add/Edit model form, reduced to the five values a user actually decides:
 * connection type, provider (API only), model, server URL (Local only) and the
 * API key (API only).
 *
 * Everything else a [ModelPreset] needs — protocol, API base path, provider
 * type, endpoint mode, tunnel and health settings — is either inherited from the
 * preset being edited or supplied by the existing provider catalogue. Those
 * capabilities still exist; they are simply not form fields any more.
 */
data class ModelSetupForm(
    val presetId: String? = null,
    val connectionType: ModelConnectionType = ModelConnectionType.LOCAL,
    val apiProvider: ModelSetupKind = ModelSetupKind.GEMINI,
    val name: String = "",
    val modelId: String = "",
    val serverUrl: String = "",
    val credential: String = "",
    /**
     * How the endpoint should be spoken to. Applies to Local only; an API provider
     * states its own protocol through the provider catalogue.
     */
    val protocol: ModelProtocolChoice = ModelProtocolChoice.AUTO,
    val hasStoredCredential: Boolean = false,
    val clearCredential: Boolean = false,
    /**
     * True when the preset being edited already carries an endpoint the runtime
     * discovers (Colab/tunnel output), so a typed URL stays optional.
     */
    val inheritsEndpointDiscovery: Boolean = false,
    /**
     * True when the user chose to type a model id instead of picking one from the
     * provider's list. Custom ids stay possible without cluttering the picker.
     */
    val manualModel: Boolean = false,
    /**
     * The user's statement that this one model can serve a tool-enabled agent
     * role — it calls tools and streams completions.
     *
     * AgentX does not infer this from the provider or a model name, so a model a
     * server merely lists stays unknown and cannot fill a role that needs tools.
     * Off by default: nothing is claimed on the user's behalf. The statement
     * belongs to the one model it was made for — not to `openai-compatible` or to
     * a gateway as a provider — so the switch never makes another model look
     * tool-capable. It is offered only where a catalogue cannot speak for the
     * connection (see [declarableCapabilities]).
     */
    val declaresToolCalling: Boolean = false,
) {

    val isEditing: Boolean get() = presetId != null

    /** Setup kind actually persisted: Local is the project's `custom` kind. */
    val setupKind: ModelSetupKind
        get() = if (connectionType == ModelConnectionType.LOCAL) ModelSetupKind.CUSTOM else apiProvider

    /** Provider identity this form will connect as. */
    val providerId: String
        get() = ModelProviderIds.forPreset(setupKind.id, protocolFor(null))

    /** True when this submit needs a credential the user has not typed yet. */
    val requiresCredential: Boolean
        get() = setupKind.requiresApiKey && !hasStoredCredential && credential.isBlank()

    /**
     * Whether a server URL field is shown: always for Local, and for an API
     * provider that declares it is addressed by endpoint (FreeLLMAPI).
     */
    val showsServerUrl: Boolean
        get() = connectionType == ModelConnectionType.LOCAL || setupKind.showsEndpointField

    /**
     * The address this connection will use: what the user typed, or the catalogue's
     * own address for a provider that ships one (FreeLLMAPI).
     *
     * The catalogue value is the starting point shown in the field, never a
     * substitute for it: editing the field changes the saved connection, so a
     * relocated or self-hosted gateway is reachable without a code change.
     */
    val effectiveServerUrl: String
        get() = serverUrl.ifBlank { KnownModelProviders.defaultEndpoint(setupKind) }

    /** The address in the shape the provider abstraction stores it. */
    val normalizedServerUrl: String?
        get() = when (val outcome = EndpointResolver.resolve(effectiveServerUrl)) {
            is EndpointResolver.Outcome.Ok -> outcome.resolved.normalizedUrl
            is EndpointResolver.Outcome.Invalid -> null
        }

    /**
     * The address of a connection whose endpoint *is* its API base, version path
     * included.
     *
     * [normalizedServerUrl] intentionally stores the bare root and keeps `/v1` in a
     * separate base path, which is right for a provider that owns a base path or for
     * a local endpoint. A provider that declares no base path of its own keeps its
     * API base in the endpoint, so that is the shape such a connection has to store.
     */
    private val normalizedEndpointWithApiBase: String?
        get() = when (val outcome = EndpointResolver.resolve(effectiveServerUrl)) {
            is EndpointResolver.Outcome.Ok -> outcome.resolved.providerBaseUrl
            is EndpointResolver.Outcome.Invalid -> null
        }

    /** The reason the address is rejected, or null when it is usable. */
    val serverUrlProblem: String?
        get() = when (val outcome = EndpointResolver.resolve(effectiveServerUrl)) {
            is EndpointResolver.Outcome.Ok -> null
            is EndpointResolver.Outcome.Invalid -> outcome.reason
        }

    val serverUrlRequired: Boolean
        get() = showsServerUrl && effectiveServerUrl.isBlank() && !inheritsEndpointDiscovery

    /**
     * Whether this connection's model capabilities are the user's to state.
     *
     * True for exactly the connections no provider catalogue speaks for: a
     * Custom/Local endpoint, and an API provider that is addressed by its own
     * endpoint (FreeLLMAPI). A gateway routes a model to whatever upstream serves
     * it, so the catalogue deliberately lists its models with tool calling unknown
     * — and unknown is not support, which is why the user's own statement is the
     * one thing that can resolve it for their connection.
     *
     * False for a fixed-address catalogue provider (Gemini, Groq): those state
     * their own capabilities, and a declaration must not override what the
     * catalogue says about them.
     */
    val declarableCapabilities: Boolean
        get() = connectionType == ModelConnectionType.LOCAL || setupKind.showsEndpointField

    /**
     * The declaration this form states, or null when the form has nothing to say
     * about capabilities.
     *
     * A [declarableCapabilities] connection states exactly what its switch says: a
     * declared endpoint produces the tool-enabled statement, and one the user does
     * not declare for produces an empty declaration, which withdraws an earlier
     * one. Every other connection returns null, which leaves whatever the preset
     * already carries untouched.
     */
    fun declaredCapabilities(): ModelCapabilityDeclaration? =
        if (declarableCapabilities) {
            if (declaresToolCalling) {
                ModelCapabilityDeclaration.toolEnabledEndpoint()
            } else {
                ModelCapabilityDeclaration.EMPTY
            }
        } else {
            null
        }

    /** Where the model runs, as the runtime will record it. */
    private fun providerTypeFor(existing: ModelPreset?): ModelProviderType =
        if (connectionType == ModelConnectionType.LOCAL) {
            existing?.providerType ?: inferredProviderType()
        } else {
            KnownModelProviders.spec(apiProvider)?.providerType ?: ModelProviderType.REMOTE_OPENAI_COMPATIBLE
        }

    private fun inferredProviderType(): ModelProviderType =
        when (val outcome = EndpointResolver.resolve(serverUrl)) {
            is EndpointResolver.Outcome.Ok -> outcome.resolved.inferredProviderType
            is EndpointResolver.Outcome.Invalid -> ModelProviderType.REMOTE_OPENAI_COMPATIBLE
        }

    private fun protocolFor(existing: ModelPreset?): ModelApiProtocol {
        val sameKind = existing != null && ModelSetupKind.fromId(existing.setupKind) == setupKind
        if (connectionType == ModelConnectionType.LOCAL) {
            // An explicit choice wins. Auto keeps the protocol the preset already
            // used, so editing a working connection never rewrites how it is spoken
            // to, and a brand-new one starts at the compatible surface.
            return protocol.toProtocol()
                ?: existing?.apiProtocol
                ?: ModelApiProtocol.OPENAI_COMPATIBLE
        }
        if (sameKind) return existing.apiProtocol
        // A known provider states how its own API is spoken; Gemini is not an
        // OpenAI-compatible service, so the protocol must come from the catalogue
        // rather than defaulting to the compatible surface.
        return KnownModelProviders.spec(setupKind)?.protocol ?: ModelApiProtocol.OPENAI_COMPATIBLE
    }

    /**
     * The preset this form describes.
     *
     * Settings the form no longer shows are inherited from [existing] whenever it
     * is still the same kind of connection, so an endpoint mode, tunnel, startup
     * script or Colab notebook that was configured earlier is never silently
     * erased by an edit.
     */
    fun toPreset(existing: ModelPreset?): ModelPreset {
        val sameKind = existing != null && ModelSetupKind.fromId(existing.setupKind) == setupKind
        val inherit = if (connectionType == ModelConnectionType.LOCAL || sameKind) existing else null
        val spec = KnownModelProviders.spec(setupKind)
        val protocol = protocolFor(existing)
        // A known provider declares its own API version path (Gemini: "/v1beta"), so
        // the base URL is that provider's root plus this path.
        val basePath = when {
            !inherit?.apiBasePath.isNullOrBlank() -> inherit.apiBasePath
            connectionType == ModelConnectionType.API && spec != null -> spec.apiBasePath
            else -> protocol.defaultApiBasePath
        }
        val endpointUrl = when (connectionType) {
            ModelConnectionType.LOCAL -> normalizedServerUrl ?: inherit?.endpoint?.explicitUrl
            // An endpoint-addressed provider keeps the address the connection carries
            // (typed, or its catalogue default) so an edited gateway is used as typed.
            // A fixed-address provider uses its catalogue root, and an existing
            // endpoint is preserved ahead of both.
            ModelConnectionType.API -> if (setupKind.showsEndpointField) {
                // An endpoint-addressed provider that declares its own base path
                // (there is none today) would have that path appended separately and
                // so keeps the bare root; one with no declared base path keeps its
                // API base in the endpoint, exactly as its connect request already
                // points at it.
                val stored = if (spec?.apiBasePath.isNullOrBlank()) {
                    normalizedEndpointWithApiBase
                } else {
                    normalizedServerUrl
                }
                stored ?: inherit?.endpoint?.explicitUrl
            } else {
                inherit?.endpoint?.explicitUrl?.takeIf { it.isNotBlank() } ?: spec?.rootUrl
            }
        }
        val endpointMode = when {
            // A local preset that never had a typed URL keeps its discovery mode.
            connectionType == ModelConnectionType.LOCAL && endpointUrl.isNullOrBlank() ->
                inherit?.endpoint?.mode ?: EndpointDiscoveryMode.CONFIGURED_ENDPOINT

            else -> EndpointDiscoveryMode.CONFIGURED_ENDPOINT
        }
        return ModelPreset(
            id = presetId.orEmpty(),
            displayName = name.trim(),
            providerType = providerTypeFor(existing),
            modelIdentifier = modelId.trim(),
            apiProtocol = protocol,
            apiBasePath = basePath,
            credentialRef = existing?.credentialRef?.takeUnless { clearCredential },
            startupScript = existing?.startupScript.orEmpty(),
            serverPort = existing?.serverPort,
            endpoint = EndpointConfig(mode = endpointMode, explicitUrl = endpointUrl),
            tunnel = inherit?.tunnel ?: TunnelConfig(type = TunnelType.NONE, marker = TunnelConfig.DEFAULT_MARKER),
            health = existing?.health ?: HealthCheckConfig(),
            colab = existing?.colab,
            enabled = existing?.enabled ?: true,
            declaredCapabilities = declaredCapabilities()
                ?: existing?.declaredCapabilities
                ?: ModelCapabilityDeclaration.EMPTY,
            setupKind = setupKind.id,
            createdAtMillis = existing?.createdAtMillis ?: 0L,
            updatedAtMillis = existing?.updatedAtMillis ?: 0L,
        )
    }

    /**
     * The connection request for Connect.
     *
     * Hidden fields are left null on purpose: the Model Connect service fills
     * them from the provider catalogue or from the endpoint's own discovery, which
     * is exactly what it already does when Advanced was collapsed.
     */
    fun toConnectRequest(duplicateOf: ModelPreset? = null): ModelConnectRequest = ModelConnectRequest(
        presetId = presetId ?: duplicateOf?.id,
        displayName = name.trim(),
        setupKind = setupKind,
        endpoint = if (showsServerUrl) effectiveServerUrl.trim() else "",
        credential = credential.takeIf { it.isNotBlank() },
        clearCredential = clearCredential,
        modelIdentifier = modelId.trim(),
        // Local only: null means "let detection decide", which is what makes an
        // unspecified protocol probe more than one. An API provider is addressed
        // through its catalogue's protocol instead.
        apiProtocol = if (connectionType == ModelConnectionType.LOCAL) protocol.toProtocol() else null,
        enabled = duplicateOf?.enabled ?: true,
        startupScript = duplicateOf?.startupScript.orEmpty(),
        colabNotebookUrl = duplicateOf?.colab?.notebookUrl,
        declaredCapabilities = declaredCapabilities(),
    )

    /** Every problem found, in field order. Empty means the form can be submitted. */
    fun issues(): List<ModelSetupIssue> {
        val issues = mutableListOf<ModelSetupIssue>()
        if (name.isBlank()) issues += ModelSetupIssue(ModelSetupField.NAME, "Give the model a name")
        if (modelId.isBlank()) {
            issues += ModelSetupIssue(ModelSetupField.MODEL, "Choose or enter a model id")
        }
        if (showsServerUrl) {
            if (serverUrlRequired) {
                issues += ModelSetupIssue(ModelSetupField.SERVER_URL, "A server URL is required")
            } else if (serverUrl.isNotBlank()) {
                serverUrlProblem?.let { issues += ModelSetupIssue(ModelSetupField.SERVER_URL, it) }
            }
        }
        if (requiresCredential) {
            issues += ModelSetupIssue(
                ModelSetupField.API_KEY,
                "An API key is required for ${setupKind.displayName}",
            )
        }
        return issues
    }

    /** True when the given issue belongs to this field. */
    fun issueFor(field: ModelSetupField): String? =
        issues().firstOrNull { it.field == field }?.message

    companion object {

        /**
         * The existing preset this form describes, if any.
         *
         * Used so adding a model that is already configured updates it instead of
         * saving a second, identical configuration.
         */
        fun equivalent(presets: List<ModelPreset>, form: ModelSetupForm): ModelPreset? {
            val protocol = form.protocolFor(null)
            val providerId = ModelProviderIds.forPreset(form.setupKind.id, protocol)
            val wantModel = form.modelId.trim()
            if (wantModel.isEmpty()) return null
            return presets.firstOrNull { preset ->
                preset.providerId == providerId &&
                    preset.modelIdentifier.trim() == wantModel &&
                    form.connectionMatches(preset)
            }
        }

        private fun ModelSetupForm.connectionMatches(preset: ModelPreset): Boolean =
            when (connectionType) {
                ModelConnectionType.API -> true
                ModelConnectionType.LOCAL -> {
                    val typed = normalizedServerUrl
                    val saved = preset.endpoint.explicitUrl?.let { EndpointResolver.normalizeUserInput(it) }
                    typed != null && saved != null && typed.equals(saved, ignoreCase = true)
                }
            }

        /** Fills the form from a saved preset; the credential is never read back. */
        fun from(preset: ModelPreset): ModelSetupForm {
            val kind = ModelSetupKind.fromId(preset.setupKind)
            return ModelSetupForm(
                presetId = preset.id,
                connectionType = if (kind == ModelSetupKind.CUSTOM) {
                    ModelConnectionType.LOCAL
                } else {
                    ModelConnectionType.API
                },
                apiProvider = if (kind == ModelSetupKind.CUSTOM) ModelSetupKind.GEMINI else kind,
                name = preset.displayName,
                modelId = preset.modelIdentifier,
                serverUrl = preset.endpoint.explicitUrl.orEmpty(),
                protocol = ModelProtocolChoice.forProtocol(preset.apiProtocol),
                credential = "",
                hasStoredCredential = preset.credentialRef != null,
                inheritsEndpointDiscovery = preset.endpoint.mode != EndpointDiscoveryMode.CONFIGURED_ENDPOINT,
                // Only a stated SUPPORTED is shown as on: an undeclared or unknown
                // capability must never read back as a claim the user did not make.
                declaresToolCalling = preset.declaredCapabilities.toolCalling == CapabilitySupport.SUPPORTED,
            )
        }
    }
}

/**
 * One selectable model: the id that gets saved, and what the picker shows for it.
 *
 * A provider that reports a human name gets it shown while the id is still what
 * is stored, so a model can be recognised without hiding what is actually sent.
 */
data class ModelChoice(
    val id: String,
    val label: String,
    /** True when the id is one the provider is asked to feature first. */
    val recommended: Boolean = false,
)

/**
 * Models the user may pick for a provider.
 *
 * A connected provider's own catalog wins; when it has not been fetched yet, the
 * provider catalogue's compatibility list is offered and a manual id stays
 * possible. Models the catalog reports as unavailable are never offered.
 */
object ModelChoices {

    fun catalogChoices(catalog: ModelCatalogRegistry?, providerId: String): List<ModelChoice> =
        catalogChoices(catalog?.availableModels(providerId).orEmpty())

    /** The choices a provider's own model records describe, in ascending id order. */
    fun catalogChoices(models: List<CatalogModel>): List<ModelChoice> =
        models
            .filter { it.id.isNotBlank() }
            .map { model ->
                ModelChoice(
                    id = model.id,
                    label = model.displayName?.takeIf { it.isNotBlank() } ?: model.id,
                )
            }
            .distinctBy { it.id }
            .sortedBy { it.id }

    /** The provider's built-in compatibility list, used only when discovery cannot answer. */
    fun suggestions(providerId: String): List<String> =
        KnownModelProviders.spec(ModelSetupKind.fromId(providerId))?.suggestedModels.orEmpty()

    /**
     * What the model picker offers for a provider.
     *
     * The provider's own live catalog wins outright: when discovery has answered,
     * the built-in compatibility list is never mixed into it, so a fallback id can
     * never be mistaken for a model the provider actually offers. The compatibility
     * list is offered only when there is no readable catalog at all (no key yet,
     * offline, rate limited) so a provider with nothing discovered yet stays usable.
     */
    fun offered(catalog: ModelCatalogRegistry?, providerId: String): List<ModelChoice> {
        val live = catalogChoices(catalog, providerId)
        if (live.isNotEmpty()) return live
        return suggestions(providerId).map { id -> ModelChoice(id, id) }
    }

    /**
     * Features the [recommendedIds] the catalog already contains, first.
     *
     * A candidate that discovery did not return has no entry to mark, so it simply
     * is not shown — the section can never contain a model the endpoint did not
     * actually list. Everything else keeps its place after the featured ids.
     */
    fun markRecommended(
        choices: List<ModelChoice>,
        recommendedIds: Collection<String>,
    ): List<ModelChoice> {
        val wanted = recommendedIds.toSet()
        return choices
            .map { choice -> choice.copy(recommended = choice.id in wanted) }
            .sortedWith(compareByDescending<ModelChoice> { it.recommended }.thenBy { it.id })
    }

    /**
     * Case-insensitive search over the full discovered catalog.
     *
     * Matches the exact model id as well as the human label, so a user can find a
     * model by either name; the returned [ModelChoice] keeps the id that is sent to
     * the endpoint verbatim.
     */
    fun search(choices: List<ModelChoice>, query: String): List<ModelChoice> {
        val q = query.trim()
        if (q.isEmpty()) return choices
        return choices.filter { choice ->
            choice.id.contains(q, ignoreCase = true) || choice.label.contains(q, ignoreCase = true)
        }
    }

    /** The label to show for the currently selected id, even when it is not listed. */
    fun labelFor(models: List<ModelChoice>, modelId: String): String =
        models.firstOrNull { it.id == modelId }?.label ?: modelId
}

/** Where a model's usage is visible today, or why nothing is shown. */
data class ModelUsageSummary(
    val local: Boolean,
    val requestCount: Long = 0L,
    val totalTokens: Long = 0L,
    val requestsPerMinute: Int? = null,
    val tokensPerMinute: Long? = null,
) {
    val hasLimits: Boolean get() = requestsPerMinute != null || tokensPerMinute != null
    val hasUsage: Boolean get() = requestCount > 0L || totalTokens > 0L

    /** Compact one-line form; null when there is nothing worth showing. */
    fun line(): String? {
        if (local) return "Local endpoint · outside remote API quotas"
        if (!hasUsage && !hasLimits) return null
        val parts = mutableListOf<String>()
        if (hasUsage) {
            parts += "$requestCount request${if (requestCount == 1L) "" else "s"}"
            if (totalTokens > 0L) parts += "${formatTokens(totalTokens)} tokens"
        }
        if (hasLimits) {
            val limits = mutableListOf<String>()
            requestsPerMinute?.let { limits += "$it rpm" }
            tokensPerMinute?.let { limits += "${formatTokens(it)} tpm" }
            parts += limits.joinToString(" / ")
        }
        return parts.joinToString(" · ")
    }

    private fun formatTokens(value: Long): String =
        if (value < 1_000L) "$value" else String.format("%.1fk", value / 1_000.0)
}

/** Model detail presentation, kept out of the composable so it can be tested. */
object ModelDetailPresentation {

    /**
     * Roles whose configured model is this preset.
     *
     * A role is counted when it names this preset as its connection, or when it
     * points at the same provider identity and model id — the same identity
     * [com.agentx.app.agent.model.AgentModelResolver] resolves a run with.
     */
    fun assignedRoles(registry: AgentRoleModelRegistry, preset: ModelPreset): List<AgentRole> =
        assignedRoles(preset) { role -> registry.selection(role) }

    fun assignedRoles(preset: ModelPreset, selectionOf: (AgentRole) -> RoleModelSelection): List<AgentRole> =
        AgentRole.entries.filter { role ->
            val selection = selectionOf(role)
            selection.connectionId == preset.id ||
                (selection.providerId == preset.providerId && selection.model == preset.modelIdentifier)
        }

    fun usage(rateLimits: RateLimitManager?, preset: ModelPreset): ModelUsageSummary {
        val local = preset.providerType == ModelProviderType.LOCAL_PHONE
        val manager = rateLimits ?: return ModelUsageSummary(local = local)
        val totals: UsageTotals? = manager.usage.totals(preset.providerId)
            .firstOrNull { it.modelId.isEmpty() || it.modelId == preset.modelIdentifier }
        val profile = manager.profile(preset.providerId, preset.modelIdentifier)
        return ModelUsageSummary(
            local = local,
            requestCount = totals?.requestCount ?: 0L,
            totalTokens = totals?.totalTokens ?: 0L,
            requestsPerMinute = profile?.requestsPerMinute,
            tokensPerMinute = profile?.tokensPerMinute,
        )
    }
}
